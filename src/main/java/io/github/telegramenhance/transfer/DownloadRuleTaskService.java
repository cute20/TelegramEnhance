package io.github.telegramenhance.transfer;

import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.downloadrules.DownloadRuleRepository;
import io.github.telegramenhance.downloadrules.DownloadRuleModels.Rule;
import io.github.telegramenhance.rules.RuleModels.Window;
import io.github.telegramenhance.telegram.TelegramService;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Task-centre projection only. Immutable child tasks, receipts and checkpoints are never merged or rewritten. */
@Service
public final class DownloadRuleTaskService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SOURCE_SQL = "CASE WHEN json_valid(i.spec_json) THEN json_extract(i.spec_json,'$.media.source') ELSE NULL END";
    private static final String FILES_FROM = """
        FROM download_rule_tasks b JOIN download_tasks t ON t.task_id=b.task_id AND t.account_id=b.account_id
        JOIN download_items i ON i.task_id=t.task_id
        """;
    private final Database db;
    private final DownloadRuleRepository rules;
    private final DownloadRepository downloads;
    private final TelegramService telegram;
    public DownloadRuleTaskService(Database db,DownloadRuleRepository rules,DownloadRepository downloads,TelegramService telegram){
        this.db=db;this.rules=rules;this.downloads=downloads;this.telegram=telegram;
    }
    public record Group(String id,String name,String channelTitle,String targetDirectory,long revision,boolean enabled,Window window,
                        String state,long taskCount,long total,long completed,long skipped,long duplicates,long mainTotal,long commentTotal,
                        long totalBytes,long downloadedBytes,long writtenBytes,long updatedAt,long failedTasks) { }
    public record Listing(List<Group> items){public Listing{items=List.copyOf(items);}}
    public record Members(List<DownloadModels.TaskView> items){public Members{items=List.copyOf(items);}}
    public record FileTotals(long total,long completed,long skipped,long duplicates,long totalBytes,long downloadedBytes,long writtenBytes) { }
    public record SourceFile(String taskId,long ruleVersion,String taskState,boolean pauseRequested,String source,DownloadModels.ItemView item) { }
    public record FilesPage(String ruleId,String source,FileTotals totals,List<SourceFile> items,String nextCursor,boolean hasMore) {
        public FilesPage { items=List.copyOf(items); }
    }
    private record FileCursor(int version,String accountId,String ruleId,String source,long createdAt,String taskId,int ordinal,String itemId) { }
    public Listing list(){
        String account=telegram.accountId();long generation=telegram.sessionGeneration();
        var result=rules.list(account).stream().map(this::summary).toList();current(account,generation);return new Listing(result);
    }
    public Group get(String id){
        validId(id);String account=telegram.accountId();long generation=telegram.sessionGeneration();
        var result=summary(rules.get(account,id));current(account,generation);return result;
    }
    public Members members(String id,int limit,int offset){
        validId(id);if(limit<1||limit>20||offset<0)throw new IllegalArgumentException("下载明细分页参数无效");
        String account=telegram.accountId();long generation=telegram.sessionGeneration();rules.get(account,id);
        var ids=db.read(c->{var result=new ArrayList<String>();try(var q=c.prepareStatement("""
            SELECT t.task_id FROM download_rule_tasks b JOIN download_tasks t ON t.task_id=b.task_id AND t.account_id=b.account_id
            WHERE b.account_id=? AND b.rule_id=? ORDER BY t.created_at DESC,t.task_id LIMIT ? OFFSET ?
            """)){q.setString(1,account);q.setString(2,id);q.setInt(3,limit);q.setInt(4,offset);try(var r=q.executeQuery()){while(r.next())result.add(r.getString(1));}}return result;});
        var items=ids.stream().map(task->downloads.view(downloads.task(account,task))).toList();current(account,generation);return new Members(items);
    }
    public FilesPage files(String id,String source,int limit,String cursor) {
        validId(id);
        if (!Set.of("MAIN","COMMENT").contains(source == null ? "" : source) || limit < 1 || limit > 50)
            throw new IllegalArgumentException("下载来源或文件分页参数无效");
        String account=telegram.accountId();long generation=telegram.sessionGeneration();
        var after=decodeCursor(cursor,account,id,source);
        rules.get(account,id);
        var page=db.read(connection -> filePage(connection,account,id,source,limit,after));
        current(account,generation);return page;
    }
    private FilesPage filePage(Connection connection,String account,String id,String source,int limit,FileCursor after) throws SQLException {
        // Refuse unknown source data rather than silently assigning it to the comment group.
        try (var query=connection.prepareStatement("SELECT 1 " + FILES_FROM + " WHERE b.account_id=? AND b.rule_id=? AND (" + SOURCE_SQL + " IS NULL OR " + SOURCE_SQL + " NOT IN ('MAIN','COMMENT','REPLY')) LIMIT 1")) {
            query.setString(1,account);query.setString(2,id);
            try (var rows=query.executeQuery()) { if(rows.next())throw new IllegalStateException("下载媒体来源无法核对，请刷新或检查记录"); }
        }
        String filter=source.equals("MAIN") ? SOURCE_SQL+"='MAIN'" : SOURCE_SQL+" IN ('COMMENT','REPLY')";
        FileTotals totals;
        try (var query=connection.prepareStatement("""
            SELECT COUNT(i.item_id) AS total,
              COALESCE(SUM(CASE WHEN i.state='COMPLETED' AND i.downloaded_bytes=i.size AND i.written_bytes=i.size AND l.item_id IS NOT NULL THEN 1 ELSE 0 END),0) AS completed,
              COALESCE(SUM(CASE WHEN i.state='SKIPPED_HISTORY' THEN 1 ELSE 0 END),0) AS skipped,
              COALESCE(SUM(CASE WHEN i.state='COMPLETED' AND i.downloaded_bytes=i.size AND i.written_bytes=i.size AND l.duplicate_of IS NOT NULL THEN 1 ELSE 0 END),0) AS duplicates,
              COALESCE(SUM(i.size),0) AS bytes,COALESCE(SUM(i.downloaded_bytes),0) AS downloaded,COALESCE(SUM(i.written_bytes),0) AS written
            """ + FILES_FROM + """
            LEFT JOIN download_completion_log l ON l.item_id=i.item_id AND l.task_id=t.task_id AND l.account_id=t.account_id
              AND l.unique_id=i.unique_id AND l.size=i.size AND l.sha256=i.sha256
            WHERE b.account_id=? AND b.rule_id=? AND
            """ + filter)) {
            query.setString(1,account);query.setString(2,id);
            try(var rows=query.executeQuery()) {
                rows.next();totals=new FileTotals(rows.getLong("total"),rows.getLong("completed"),rows.getLong("skipped"),rows.getLong("duplicates"),rows.getLong("bytes"),rows.getLong("downloaded"),rows.getLong("written"));
            }
        }
        var items=new ArrayList<SourceFile>();FileCursor last=null;boolean more=false;
        try(var query=connection.prepareStatement("""
            SELECT t.task_id,t.state AS task_state,t.pause_requested,t.created_at AS task_created_at,t.target_root,t.relative_directory,
              b.rule_version,i.*,h.source_task_id,h.original_path,h.completed_at,h.sha256 AS history_sha,
              p.relative_path,p.sha256 AS plan_sha,p.duplicate_of,p.reason,p.manifest_relative,
              matched.item_id AS matched_id,matched.source_link AS matched_link,matched.output_path AS matched_path,
              completed.item_id AS receipt_id
            """ + FILES_FROM + """
            LEFT JOIN download_history h ON h.id=i.history_id AND h.account_id=t.account_id AND h.unique_id=i.unique_id AND h.size=i.size
            LEFT JOIN download_output_plans p ON p.item_id=i.item_id
            LEFT JOIN download_completion_log matched ON matched.item_id=p.duplicate_of AND matched.account_id=t.account_id
            LEFT JOIN download_completion_log completed ON completed.item_id=i.item_id AND completed.task_id=t.task_id AND completed.account_id=t.account_id
              AND completed.unique_id=i.unique_id AND completed.size=i.size AND completed.sha256=i.sha256
            WHERE b.account_id=? AND b.rule_id=? AND
            """ + filter + (after == null ? "" : " AND (t.created_at,t.task_id,i.ordinal,i.item_id)<(?,?,?,?)") +
            " ORDER BY t.created_at DESC,t.task_id DESC,i.ordinal DESC,i.item_id DESC LIMIT ?")) {
            int parameter=1;query.setString(parameter++,account);query.setString(parameter++,id);
            if(after!=null){query.setLong(parameter++,after.createdAt());query.setString(parameter++,after.taskId());query.setInt(parameter++,after.ordinal());query.setString(parameter++,after.itemId());}
            query.setInt(parameter,limit+1);
            try(var rows=query.executeQuery()) {
                while(rows.next()) {
                    if(items.size()==limit){more=true;break;}
                    items.add(sourceFile(rows,source));
                    last=new FileCursor(1,account,id,source,rows.getLong("task_created_at"),rows.getString("task_id"),rows.getInt("ordinal"),rows.getString("item_id"));
                }
            }
        }
        return new FilesPage(id,source,totals,items,more?encodeCursor(last):null,more);
    }
    private static SourceFile sourceFile(ResultSet row,String source) throws SQLException {
        // Decode only this page's frozen files, never the containing task's post or complete item list.
        var file=JSON.readValue(row.getString("spec_json"),DownloadModels.FrozenFile.class);
        if(file==null||file.media()==null||file.media().message()==null||file.fileName()==null||file.uniqueId()==null
            || !Objects.equals(file.uniqueId(),row.getString("unique_id")) || file.size()!=row.getLong("size")
            || !(source.equals("MAIN")?"MAIN".equals(file.media().source()):Set.of("COMMENT","REPLY").contains(file.media().source()==null?"":file.media().source())))
            throw new IllegalStateException("下载媒体冻结记录无法核对");
        if("COMPLETED".equals(row.getString("state")) && (row.getLong("downloaded_bytes")!=file.size()||row.getLong("written_bytes")!=file.size()||row.getString("receipt_id")==null))
            throw new IllegalStateException("下载完成回执无法核对，未把字节进度标记为成功");
        var history=row.getString("history_id")==null||row.getString("source_task_id")==null?null:
            new DownloadModels.History(row.getString("history_id"),row.getString("source_task_id"),row.getString("original_path"),row.getString("history_sha"),row.getLong("completed_at"));
        if("SKIPPED_HISTORY".equals(row.getString("state"))&&history==null)throw new IllegalStateException("历史跳过证据无法核对");
        if(row.getString("duplicate_of")!=null&&row.getString("matched_id")==null)throw new IllegalStateException("重复媒体关联证据无法核对");
        var duplicate=row.getString("duplicate_of")==null?null:new DownloadModels.Duplicate(row.getString("duplicate_of"),row.getString("reason"),row.getString("matched_link"),row.getString("matched_path"));
        Path root=Path.of(row.getString("target_root"));
        String output="SKIPPED_HISTORY".equals(row.getString("state"))?null:
            (row.getString("relative_path")==null?root.resolve(row.getString("relative_directory")).resolve(row.getString("relative_file")):root.resolve(row.getString("relative_path"))).toString();
        String manifest=row.getString("manifest_relative")==null?null:root.resolve(row.getString("manifest_relative")).toString();
        var item=new DownloadModels.ItemView(row.getString("item_id"),file.fileName(),file.media().type(),row.getString("state"),file.size(),row.getLong("downloaded_bytes"),row.getLong("written_bytes"),output,row.getString("error_message"),history,file.media().message().link(),duplicate,manifest);
        return new SourceFile(row.getString("task_id"),row.getLong("rule_version"),row.getString("task_state"),row.getBoolean("pause_requested"),source,item);
    }
    private static FileCursor decodeCursor(String value,String account,String id,String source) {
        if(value==null)return null;
        try {
            if(value.length()>2048||!value.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
            byte[] decoded=Base64.getUrlDecoder().decode(value);
            var fields=JSON.readTree(decoded);
            if(!fields.isObject()||fields.size()!=8||!fields.path("version").isIntegralNumber()||!fields.path("version").canConvertToInt()
                ||!fields.path("createdAt").isIntegralNumber()||!fields.path("createdAt").canConvertToLong()
                ||!fields.path("ordinal").isIntegralNumber()||!fields.path("ordinal").canConvertToInt()
                ||!fields.path("accountId").isTextual()||!fields.path("ruleId").isTextual()||!fields.path("source").isTextual()
                ||!fields.path("taskId").isTextual()||!fields.path("itemId").isTextual())throw new IllegalArgumentException();
            var cursor=JSON.readValue(decoded,FileCursor.class);
            if(cursor.version()!=1||!Objects.equals(account,cursor.accountId())||!id.equals(cursor.ruleId())||!source.equals(cursor.source())
                ||cursor.createdAt()<0||cursor.ordinal()<0||cursor.ordinal()>99)throw new IllegalArgumentException();
            validId(cursor.taskId());validId(cursor.itemId());return cursor;
        }catch(RuntimeException invalid){throw new IllegalArgumentException("文件读取位置无效，请重新展开下载明细");}
    }
    private static String encodeCursor(FileCursor cursor) {return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(cursor));}
    private Group summary(Rule rule){return db.read(c->{
        try(var q=c.prepareStatement("""
            SELECT COUNT(DISTINCT t.task_id) AS tasks,COUNT(i.item_id) AS total,
              COALESCE(SUM(CASE WHEN i.state='COMPLETED' AND i.downloaded_bytes=i.size AND i.written_bytes=i.size AND l.item_id IS NOT NULL THEN 1 ELSE 0 END),0) AS completed,
              COALESCE(SUM(CASE WHEN i.state='SKIPPED_HISTORY' THEN 1 ELSE 0 END),0) AS skipped,
              COALESCE(SUM(CASE WHEN i.state='COMPLETED' AND l.duplicate_of IS NOT NULL THEN 1 ELSE 0 END),0) AS duplicates,
              COALESCE(SUM(CASE WHEN json_extract(i.spec_json,'$.media.source')='MAIN' THEN 1 ELSE 0 END),0) AS main_total,
              COALESCE(SUM(i.size),0) AS bytes,COALESCE(SUM(i.downloaded_bytes),0) AS downloaded,COALESCE(SUM(i.written_bytes),0) AS written,
              COALESCE(MAX(t.updated_at),0) AS updated,
              COUNT(DISTINCT CASE WHEN t.state='RUNNING' THEN t.task_id END) AS running,
              COUNT(DISTINCT CASE WHEN t.state='RUNNING' AND t.pause_requested=1 THEN t.task_id END) AS pausing,
              COUNT(DISTINCT CASE WHEN t.state='QUEUED' THEN t.task_id END) AS queued,
              COUNT(DISTINCT CASE WHEN t.state='FAILED' THEN t.task_id END) AS failed,
              COUNT(DISTINCT CASE WHEN t.state='PAUSED' THEN t.task_id END) AS paused
            FROM download_rule_tasks b JOIN download_tasks t ON t.task_id=b.task_id AND t.account_id=b.account_id
            LEFT JOIN download_items i ON i.task_id=t.task_id
            LEFT JOIN download_completion_log l ON l.item_id=i.item_id AND l.task_id=t.task_id AND l.account_id=t.account_id
                AND l.unique_id=i.unique_id AND l.size=i.size AND l.sha256=i.sha256
            WHERE b.account_id=? AND b.rule_id=?
            """)){
            q.setString(1,rule.accountId());q.setString(2,rule.id());try(var r=q.executeQuery()){
                r.next();long total=r.getLong("total"),completed=r.getLong("completed"),skipped=r.getLong("skipped"),main=r.getLong("main_total");
                String state=r.getLong("running")>0 ? (!rule.enabled()||r.getLong("pausing")>0?"PAUSING":"RUNNING")
                    :r.getLong("queued")>0?"QUEUED":r.getLong("failed")>0||completed+skipped<total&&r.getLong("paused")==0?"FAILED"
                    :r.getLong("paused")>0?"PAUSED":rule.enabled()?"WATCHING":"STOPPED";
                return new Group(rule.id(),rule.input().name(),rule.channelTitle(),rule.input().targetDirectory(),rule.revision(),rule.enabled(),rule.input().window(),state,
                    r.getLong("tasks"),total,completed,skipped,r.getLong("duplicates"),main,total-main,r.getLong("bytes"),r.getLong("downloaded"),r.getLong("written"),
                    Math.max(rule.updatedAt(),r.getLong("updated")),r.getLong("failed"));
            }
        }
    });}
    private void current(String account,long generation){if(!Objects.equals(account,telegram.accountId())||generation!=telegram.sessionGeneration())throw new IllegalArgumentException("账号或会话已变化，请刷新下载任务");}
    private static void validId(String id){if(id==null||!id.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))throw new IllegalArgumentException("下载规则任务标识无效");}
}
