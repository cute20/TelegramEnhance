package io.github.telegramenhance.transfer;

import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.library.ResourceModels.MessageRef;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.DownloadModels.*;
import io.github.telegramenhance.transfer.DownloadBatchModels.BatchStatus;
import io.github.telegramenhance.transfer.DownloadBatchModels.TaskSummary;
import static io.github.telegramenhance.transfer.DownloadModels.*;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public final class DownloadRepository {
    private final Database database; private final JsonMapper json = JsonMapper.builder().build();
    public DownloadRepository(Database database) {
        this.database = database;
        database.write(c -> { update(c, "UPDATE download_tasks SET state='PAUSED',pause_requested=1,error_message=?,updated_at=? WHERE state IN ('QUEUED','RUNNING')", "应用已重启，请核对后继续未完成项", now()); return null; });
    }
    public Task byOperation(String account, String operation) { return database.read(c -> {
        try (var s = c.prepareStatement("SELECT task_id FROM download_tasks WHERE account_id=? AND operation_id=?")) {
            s.setString(1, account); s.setString(2, operation); try (var r = s.executeQuery()) { return r.next() ? task(c, account, r.getString(1)) : null; }
        }
    }); }
    public Task create(String account, String operation, String fingerprint, Post post, Path root, List<FrozenFile> files) {
        return create(account, operation, fingerprint, post, root, files, KEEP_DUPLICATES);
    }
    public Task create(String account, String operation, String fingerprint, Post post, Path root, List<FrozenFile> files, String policy) {
        if (!List.of(HISTORY_SKIP, KEEP_DUPLICATES).contains(policy)) throw new IllegalArgumentException("下载策略无效");
        return database.write(c -> create(c,account,operation,fingerprint,post,root,files,policy));
    }
    /** Short caller-owned SQL transaction: allows durable source checkpoints and tasks to commit together. */
    public Task createInTransaction(Connection connection,String account,String operation,String fingerprint,Post post,Path root,List<FrozenFile> files)throws SQLException {
        if(connection.getAutoCommit()||post==null||!account.equals(post.accountId())||files==null||files.isEmpty()||files.size()>100)throw new IllegalArgumentException("持续下载任务事务或清单无效");
        return create(connection,account,operation,fingerprint,post,root,List.copyOf(files),KEEP_DUPLICATES);
    }
    private Task create(Connection c,String account,String operation,String fingerprint,Post post,Path root,List<FrozenFile> files,String policy)throws SQLException{
            try (var q = c.prepareStatement("SELECT task_id,fingerprint FROM download_tasks WHERE account_id=? AND operation_id=?")) {
                q.setString(1, account); q.setString(2, operation); try (var rows = q.executeQuery()) {
                    if (rows.next()) { if (!fingerprint.equals(rows.getString(2))) throw new IllegalArgumentException("同一操作的参数已变化，请重新创建任务"); return task(c, account, rows.getString(1)); }
                }
            }
            // Legacy bindings remain untouched for old checkpoints. Never reuse their extra directories for a new task.
            String directory = DownloadPaths.directory(post);
            String id = UUID.randomUUID().toString(); long stamp = now();
            update(c, "INSERT INTO download_tasks VALUES(?,?,?,?,?,?,?,?,'QUEUED',0,NULL,?,?,?)", id, account, operation, fingerprint, post.key(), encode(post), root.toString(), directory, stamp, stamp, policy);
            for (int i = 0; i < files.size(); i++) {
                var file = files.get(i); String relative = DownloadPaths.file(file,root);
                if (root.resolve(directory).resolve(relative).toString().length() > 240) throw new IllegalArgumentException("保存路径过长，请选择更短的根目录");
                var history = HISTORY_SKIP.equals(policy) ? history(c, account, file.uniqueId(), file.size()) : null;
                update(c, "INSERT INTO download_items VALUES(?,?,?,?,?,?,?,?,?,?,0,0,?,NULL,?)", UUID.randomUUID().toString(), id, i, file.media().key(), encode(file), file.uniqueId(), file.size(), relative, UUID.randomUUID().toString(), history == null ? "QUEUED" : "SKIPPED_HISTORY", history == null ? null : history.sha256(), history == null ? null : history.id());
            }
            update(c, "UPDATE download_tasks SET state='COMPLETED' WHERE task_id=? AND NOT EXISTS(SELECT 1 FROM download_items WHERE task_id=? AND state<>'SKIPPED_HISTORY')", id, id);
            return task(c, account, id);
    }
    public DownloadBatchModels.Receipt createBatch(String account,String operation,String fingerprint,DownloadBatchModels.Snapshot snapshot,Path root,List<DownloadBatchModels.Job> jobs){
        if(jobs==null||jobs.isEmpty()||jobs.size()>29||snapshot.postCount()<1||snapshot.postCount()>20||!DownloadPaths.key(root).equals(DownloadPaths.key(Path.of(snapshot.targetDirectory()))))throw new IllegalArgumentException("批量下载清单无效。");
        var keys=new java.util.HashSet<String>();int total=0;long bytes=0;
        for(var job:jobs){if(!account.equals(job.post().accountId())||job.files().isEmpty()||job.files().size()>100)throw new IllegalArgumentException("批量主帖或媒体数量无效。");for(var file:job.files()){if(!keys.add(file.media().key())||file.size()<=0)throw new IllegalArgumentException("批量清单包含重复或无效媒体。");total++;bytes=Math.addExact(bytes,file.size());}}
        if(total>1000||total!=snapshot.total()||bytes!=snapshot.totalBytes())throw new IllegalArgumentException("批量媒体统计不符。");
        return database.write(c->{var previous=batchByOperation(c,account,operation,fingerprint);if(previous!=null)return previous;
            String id=UUID.randomUUID().toString();long stamp=now();update(c,"INSERT INTO download_batches VALUES(?,?,?,?,?,?)",id,account,operation,fingerprint,encode(snapshot),stamp);
            var ids=new ArrayList<String>();int index=0;
            for(var job:jobs){String childOperation=UUID.nameUUIDFromBytes(("batch:"+id+":"+index).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                var child=create(c,account,childOperation,fingerprint+":"+index,job.post(),root,job.files(),KEEP_DUPLICATES);ids.add(child.id());
                update(c,"INSERT INTO download_batch_tasks VALUES(?,?,?)",id,index++,child.id());}
            return new DownloadBatchModels.Receipt(id,snapshot,ids,stamp);
        });
    }
    public DownloadBatchModels.Receipt batchByOperation(String account,String operation,String fingerprint){return database.read(c->batchByOperation(c,account,operation,fingerprint));}
    private DownloadBatchModels.Receipt batchByOperation(Connection c,String account,String operation,String fingerprint)throws SQLException{
        try(var q=c.prepareStatement("SELECT id,fingerprint,snapshot_json,created_at FROM download_batches WHERE account_id=? AND operation_id=?")){q.setString(1,account);q.setString(2,operation);try(var r=q.executeQuery()){
            if(!r.next())return null;if(fingerprint!=null&&!fingerprint.equals(r.getString(2)))throw new IllegalArgumentException("同一批量操作的参数已变化。");String id=r.getString(1);var snapshot=decode(r.getString(3),DownloadBatchModels.Snapshot.class);long stamp=r.getLong(4);var ids=new ArrayList<String>();
            try(var tasks=c.prepareStatement("SELECT task_id FROM download_batch_tasks WHERE batch_id=? ORDER BY ordinal")){tasks.setString(1,id);try(var rows=tasks.executeQuery()){while(rows.next())ids.add(rows.getString(1));}}
            return new DownloadBatchModels.Receipt(id,snapshot,ids,stamp);
        }}
    }
    public BatchStatus batchStatus(String account,String id){return database.read(c->batchStatus(c,account,id));}
    public List<BatchStatus> batchStatuses(String account,int limit,int offset){
        if(limit<1||limit>20||offset<0)throw new IllegalArgumentException("批量任务分页参数无效");
        return database.read(c->{
            var ids=new ArrayList<String>();
            try(var q=c.prepareStatement("SELECT id FROM download_batches WHERE account_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?")){
                q.setString(1,account);q.setInt(2,limit);q.setInt(3,offset);try(var r=q.executeQuery()){while(r.next())ids.add(r.getString(1));}
            }
            var batches=new ArrayList<BatchStatus>();for(var id:ids)batches.add(batchStatus(c,account,id));return List.copyOf(batches);
        });
    }
    public BatchStatus pauseBatch(String account,String id){return pauseBatch(account,id,()->{});}
    public BatchStatus pauseBatch(String account,String id,Runnable guard){return database.write(c->{
        guard.run();var before=batchStatus(c,account,id);if(!before.canPause()){guard.run();return before;}
        update(c,"""
            UPDATE download_tasks SET pause_requested=1,state=CASE WHEN state='RUNNING' THEN 'RUNNING' ELSE 'PAUSED' END,updated_at=?
            WHERE account_id=? AND (state='QUEUED' OR (state='RUNNING' AND pause_requested=0))
              AND task_id IN (SELECT bt.task_id FROM download_batch_tasks bt JOIN download_batches b ON b.id=bt.batch_id
                  WHERE b.account_id=? AND b.id=?)
            """,now(),account,account,id);
        var after=batchStatus(c,account,id);guard.run();return after;
    });}
    public BatchStatus resumeBatch(String account,String id){return resumeBatch(account,id,()->{});}
    public BatchStatus resumeBatch(String account,String id,Runnable guard){return database.write(c->{
        guard.run();var before=batchStatus(c,account,id);
        if("PAUSING".equals(before.state()))throw new IllegalArgumentException("本批仍在收尾暂停，请等待当前任务停稳后再继续");
        if(!before.canResume()){guard.run();return before;}
        // Identify resumable children before changing their states. Success rows, their evidence,
        // byte checkpoints and output plans are never reset by this control operation.
        update(c,"""
            UPDATE download_items SET error_message=NULL WHERE error_message IS NOT NULL AND state NOT IN ('COMPLETED','SKIPPED_HISTORY')
              AND task_id IN (SELECT t.task_id FROM download_tasks t JOIN download_batch_tasks bt ON bt.task_id=t.task_id
                  JOIN download_batches b ON b.id=bt.batch_id AND b.account_id=t.account_id
                  WHERE t.account_id=? AND b.account_id=? AND b.id=? AND t.state IN ('PAUSED','FAILED'))
            """,account,account,id);
        update(c,"""
            UPDATE download_tasks SET state='QUEUED',pause_requested=0,error_message=NULL,updated_at=?
            WHERE account_id=? AND state IN ('PAUSED','FAILED')
              AND task_id IN (SELECT bt.task_id FROM download_batch_tasks bt JOIN download_batches b ON b.id=bt.batch_id
                  WHERE b.account_id=? AND b.id=?)
            """,now(),account,account,id);
        var after=batchStatus(c,account,id);guard.run();return after;
    });}
    /** One account-scoped SQL snapshot; item payloads and complete Post structures are not decoded. */
    private BatchStatus batchStatus(Connection c,String account,String id)throws SQLException{
        var tasks=new ArrayList<TaskSummary>();DownloadBatchModels.Snapshot snapshot=null;long createdAt=0,updatedAt=0;
        try(var q=c.prepareStatement("""
            SELECT b.snapshot_json,b.created_at,bt.ordinal,t.task_id,json_extract(t.post_json,'$.title') AS title,
                   t.state,t.pause_requested,t.error_message,t.updated_at,
                   COUNT(i.item_id) AS total,
                   COALESCE(SUM(CASE WHEN i.state='COMPLETED' THEN 1 ELSE 0 END),0) AS completed,
                   COALESCE(SUM(CASE WHEN i.state='SKIPPED_HISTORY' THEN 1 ELSE 0 END),0) AS skipped,
                   COALESCE(SUM(CASE WHEN i.state='COMPLETED' AND p.duplicate_of IS NOT NULL THEN 1 ELSE 0 END),0) AS duplicates,
                   COALESCE(SUM(i.size),0) AS total_bytes,COALESCE(SUM(i.downloaded_bytes),0) AS downloaded_bytes,
                   COALESCE(SUM(i.written_bytes),0) AS written_bytes,
                   COALESCE(SUM(CASE WHEN i.item_id IS NOT NULL AND
                     (i.downloaded_bytes<0 OR i.downloaded_bytes>i.size OR i.written_bytes<0 OR i.written_bytes>i.size
                      OR (i.state='COMPLETED' AND (i.downloaded_bytes<>i.size OR i.written_bytes<>i.size))
                      OR (i.state='COMPLETED' AND receipt.item_id IS NULL)
                      OR (i.state='COMPLETED' AND t.duplicate_policy='KEEP_DUPLICATES_V2'
                          AND (p.item_id IS NULL OR p.sha256<>i.sha256 OR p.duplicate_of IS NOT receipt.duplicate_of OR p.reason<>receipt.reason))
                      OR (i.state='SKIPPED_HISTORY' AND h.id IS NULL)
                      OR (p.duplicate_of IS NOT NULL AND matched.item_id IS NULL)) THEN 1 ELSE 0 END),0) AS invalid_items
            FROM download_batches b LEFT JOIN download_batch_tasks bt ON bt.batch_id=b.id
            LEFT JOIN download_tasks t ON t.task_id=bt.task_id AND t.account_id=b.account_id
            LEFT JOIN download_items i ON i.task_id=t.task_id
            LEFT JOIN download_output_plans p ON p.item_id=i.item_id
            LEFT JOIN download_completion_log receipt ON receipt.item_id=i.item_id AND receipt.task_id=t.task_id
                AND receipt.account_id=t.account_id AND receipt.unique_id=i.unique_id AND receipt.size=i.size AND receipt.sha256=i.sha256
            LEFT JOIN download_completion_log matched ON matched.item_id=p.duplicate_of AND matched.account_id=t.account_id
            LEFT JOIN download_history h ON h.id=i.history_id AND h.account_id=t.account_id AND h.unique_id=i.unique_id AND h.size=i.size
            WHERE b.account_id=? AND b.id=? GROUP BY bt.ordinal,bt.task_id ORDER BY bt.ordinal
            """)){
            q.setString(1,account);q.setString(2,id);try(var r=q.executeQuery()){
                while(r.next()){
                    if(snapshot==null){snapshot=batchSnapshot(r.getString("snapshot_json"));createdAt=r.getLong("created_at");updatedAt=createdAt;}
                    if(r.getString("task_id")==null||r.getInt("ordinal")!=tasks.size()||r.getLong("invalid_items")!=0)throw invalidBatch();
                    int total=r.getInt("total");long completed=r.getLong("completed"),skipped=r.getLong("skipped");
                    if(total<1||total>100||completed+skipped>total||("COMPLETED".equals(r.getString("state"))&&completed+skipped!=total))throw invalidBatch();
                    String title=r.getString("title");
                    tasks.add(new TaskSummary(r.getString("task_id"),title==null?"主帖":title,r.getString("state"),r.getBoolean("pause_requested"),
                        total,completed,skipped,r.getLong("duplicates"),r.getLong("total_bytes"),r.getLong("downloaded_bytes"),
                        r.getLong("written_bytes"),r.getLong("updated_at"),r.getString("error_message")));
                    updatedAt=Math.max(updatedAt,r.getLong("updated_at"));
                }
            }
        }
        if(snapshot==null)throw new IllegalArgumentException("当前账号中没有这条批量下载任务");
        if(tasks.isEmpty()||tasks.size()>29)throw invalidBatch();
        int total=0,completedTasks=0,runningTasks=0,queuedTasks=0,pausedTasks=0,failedTasks=0;
        long bytes=0,completed=0,skipped=0,duplicates=0,downloaded=0,written=0;boolean pausing=false;
        try{
            for(var task:tasks){
                total=Math.addExact(total,task.total());bytes=Math.addExact(bytes,task.totalBytes());completed=Math.addExact(completed,task.completed());
                skipped=Math.addExact(skipped,task.skipped());duplicates=Math.addExact(duplicates,task.duplicates());
                downloaded=Math.addExact(downloaded,task.downloadedBytes());written=Math.addExact(written,task.writtenBytes());
                switch(task.state()){
                    case "COMPLETED"->completedTasks++;
                    case "RUNNING"->{runningTasks++;pausing|=task.pauseRequested();}
                    case "QUEUED"->queuedTasks++;
                    case "PAUSED"->pausedTasks++;
                    case "FAILED"->failedTasks++;
                    default->throw invalidBatch();
                }
            }
        }catch(ArithmeticException overflow){throw invalidBatch();}
        if(snapshot.total()!=total||snapshot.totalBytes()!=bytes)throw invalidBatch();
        String state=pausing?"PAUSING":runningTasks>0?"RUNNING":queuedTasks>0?"QUEUED":failedTasks>0?"FAILED":pausedTasks>0?"PAUSED":"COMPLETED";
        if("COMPLETED".equals(state)&&(completedTasks!=tasks.size()||completed+skipped!=total))throw invalidBatch();
        return new BatchStatus(id,snapshot,state,tasks.size(),completedTasks,runningTasks,queuedTasks,pausedTasks,failedTasks,completed,skipped,duplicates,
            downloaded,written,createdAt,updatedAt,!pausing&&runningTasks+queuedTasks>0,!pausing&&pausedTasks+failedTasks>0,tasks);
    }
    private DownloadBatchModels.Snapshot batchSnapshot(String value){
        try{
            var snapshot=decode(value,DownloadBatchModels.Snapshot.class);
            if(snapshot==null||snapshot.total()<1||snapshot.total()>1000||snapshot.totalBytes()<1||snapshot.postCount()<1||snapshot.postCount()>20)throw invalidBatch();
            return snapshot;
        }catch(RuntimeException invalid){throw invalidBatch();}
    }
    private static IllegalStateException invalidBatch(){return new IllegalStateException("批量下载的冻结清单、子任务或完成证据不一致，请核对已有任务");}
    public Task task(String account, String id) { return database.read(c -> task(c, account, id)); }
    public List<Task> list(String account, int limit, int offset) { return list(account,limit,offset,false); }
    public List<Task> list(String account, int limit, int offset, boolean standalone) { return database.read(c -> {
        var ids = new ArrayList<String>();
        try (var q = c.prepareStatement("SELECT task_id FROM download_tasks t WHERE account_id=?" + (standalone ? " AND NOT EXISTS(SELECT 1 FROM download_rule_tasks b WHERE b.task_id=t.task_id AND b.account_id=t.account_id)" : "") + " ORDER BY created_at DESC,task_id LIMIT ? OFFSET ?")) {
            q.setString(1, account); q.setInt(2, limit); q.setInt(3, offset); try (var r = q.executeQuery()) { while (r.next()) ids.add(r.getString(1)); }
        }
        var tasks = new ArrayList<Task>(); for (var id : ids) tasks.add(task(c, account, id)); return List.copyOf(tasks);
    }); }
    public Task next(String account) { return database.write(c -> {
        String id = null; try (var q = c.prepareStatement("SELECT task_id FROM download_tasks WHERE account_id=? AND state='QUEUED' AND pause_requested=0 ORDER BY created_at,task_id LIMIT 1")) {
            q.setString(1, account); try (var r = q.executeQuery()) { if (r.next()) id = r.getString(1); }
        }
        if (id == null) return null;
        update(c, "UPDATE download_tasks SET state='RUNNING',updated_at=? WHERE task_id=?", now(), id); return task(c, account, id);
    }); }
    public Task pause(String account, String id) { return database.write(c -> {
        var task = task(c, account, id); if (task.state().equals("COMPLETED")) return task;
        update(c, "UPDATE download_tasks SET pause_requested=1,state=CASE WHEN state='RUNNING' THEN 'RUNNING' ELSE 'PAUSED' END,updated_at=? WHERE task_id=? AND account_id=?", now(), id, account);
        return task(c, account, id);
    }); }
    public Task resume(String account, String id) { return database.write(c -> {
        var task = task(c, account, id);
        if (!List.of("PAUSED", "FAILED").contains(task.state())) throw new IllegalArgumentException("任务当前不能继续，请刷新状态");
        update(c, "UPDATE download_tasks SET state='QUEUED',pause_requested=0,error_message=NULL,updated_at=? WHERE task_id=? AND account_id=?", now(), id, account);
        update(c, "UPDATE download_items SET error_message=NULL WHERE task_id=?", id); return task(c, account, id);
    }); }
    public boolean pauseRequested(String account, String id) { return database.read(c -> {
        try (var q = c.prepareStatement("SELECT pause_requested,state FROM download_tasks WHERE account_id=? AND task_id=?")) {
            q.setString(1, account); q.setString(2, id); try (var r = q.executeQuery()) { return !r.next() || r.getBoolean(1) || !"RUNNING".equals(r.getString(2)); }
        }
    }); }
    public void finishState(String account, String id, String state, String error) { database.write(c -> {
        update(c, "UPDATE download_tasks SET state=?,error_message=?,updated_at=?,pause_requested=CASE WHEN ?='COMPLETED' THEN 0 ELSE pause_requested END WHERE account_id=? AND task_id=?", state, error, now(), state, account, id); return null;
    }); }
    public void progress(String account, String taskId, String itemId, String state, long downloaded, long written, String hash, String relativeFile) { database.write(c -> {
        update(c, "UPDATE download_items SET state=?,downloaded_bytes=?,written_bytes=?,sha256=?,relative_file=?,error_message=NULL WHERE task_id=? AND item_id=? AND state NOT IN ('COMPLETED','SKIPPED_HISTORY') AND task_id IN(SELECT task_id FROM download_tasks WHERE account_id=?)", state, downloaded, written, hash, relativeFile, taskId, itemId, account);
        update(c, "UPDATE download_tasks SET updated_at=? WHERE task_id=?", now(), taskId); return null;
    }); }
    public void complete(String account, String taskId, String itemId, String relativeFile, Path path, String hash) { database.write(c -> {
        var task = task(c, account, taskId); var item = task.items().stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
        if (item.state().equals("SKIPPED_HISTORY") || hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("只有完整校验保存的文件才能写入下载历史");
        var plan=item.plan();
        if(KEEP_DUPLICATES.equals(task.duplicatePolicy()) && (plan==null||!plan.sha256().equals(hash)||!Path.of(task.targetRoot()).resolve(plan.relativePath()).normalize().equals(path.normalize())))throw new IllegalArgumentException("下载输出计划与完成文件不符");
        try(var q=c.prepareStatement("SELECT sha256,output_path FROM download_completion_log WHERE item_id=?")){q.setString(1,itemId);try(var r=q.executeQuery()){if(r.next()&&(!hash.equals(r.getString(1))||!path.toString().equals(r.getString(2))))throw new IllegalArgumentException("已有完成日志不能改写");}}
        update(c, "INSERT INTO download_file_receipts VALUES(?,?,?,?,?) ON CONFLICT(account_id,path_key) DO UPDATE SET unique_id=excluded.unique_id,size=excluded.size,sha256=excluded.sha256", account, DownloadPaths.key(path), item.file().uniqueId(), item.file().size(), hash);
        update(c, "INSERT OR IGNORE INTO download_history VALUES(?,?,?,?,?,?,?,?)", itemId, account, item.file().uniqueId(), item.file().size(), hash, taskId, path.toString(), now());
        var ref=item.file().media().message();var duplicate=plan==null?null:plan.duplicate();
        update(c,"INSERT INTO download_completion_log VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(item_id) DO NOTHING",itemId,taskId,account,item.file().media().key(),ref.chatId(),ref.messageId(),ref.link(),postLink(task),item.file().uniqueId(),item.file().size(),hash,path.toString(),now(),duplicate==null?null:duplicate.itemId(),duplicate==null?"NEW":duplicate.reason(),plan==null||plan.manifestRelative()==null?null:Path.of(task.targetRoot()).resolve(plan.manifestRelative()).toString());
        update(c, "UPDATE download_items SET state='COMPLETED',downloaded_bytes=size,written_bytes=size,sha256=?,relative_file=?,error_message=NULL WHERE item_id=?", hash, relativeFile, itemId);
        update(c, "UPDATE download_tasks SET updated_at=? WHERE task_id=?", now(), taskId); return null;
    }); }
    /** Only committed successes count, across destinations and source messages within this account. */
    public boolean skipHistory(Task task, Item item) { return database.write(c -> {
        if (!HISTORY_SKIP.equals(task.duplicatePolicy())) return false;
        var fresh = task(c, task.accountId(), task.id());var current = fresh.items().stream().filter(i -> i.id().equals(item.id())).findFirst().orElseThrow();
        if (current.state().equals("SKIPPED_HISTORY")) return true;
        if (!List.of("QUEUED", "DOWNLOADING").contains(current.state()) || current.writtenBytes() != 0) return false;
        var history = history(c, task.accountId(), current.file().uniqueId(), current.file().size());if (history == null) return false;
        update(c, "UPDATE download_items SET state='SKIPPED_HISTORY',downloaded_bytes=0,written_bytes=0,sha256=?,error_message=NULL,history_id=? WHERE item_id=?", history.sha256(), history.id(), current.id());
        update(c, "UPDATE download_tasks SET updated_at=? WHERE task_id=?", now(), task.id());return true;
    }); }
    /** Hash is from the newly read complete file; source identity is checked before content fallback. */
    public OutputPlan outputPlan(Task task,Item item,String hash){return database.write(c->{
        if(!KEEP_DUPLICATES.equals(task.duplicatePolicy()))return null;
        if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("完整SHA-256缺失");
        var current=task(c,task.accountId(),task.id()).items().stream().filter(i->i.id().equals(item.id())).findFirst().orElseThrow();
        if(current.plan()!=null){if(!current.plan().sha256().equals(hash))throw new IllegalArgumentException("下载输出内容已变化");return current.plan();}
        if(current.writtenBytes()!=current.file().size()||!hash.equals(current.sha256()))throw new IllegalArgumentException("下载文件尚未完整校验");
        var duplicate=findDuplicate(c,task.accountId(),current,hash);String relative=Path.of(duplicate==null?task.relativeDirectory():"重复媒体").resolve(current.relativeFile()).toString();
        // Provenance and duplicate evidence are retained in download_completion_log, without new sidecar files.
        String manifest=null;long stamp=now();
        update(c,"INSERT INTO download_output_plans VALUES(?,?,?,?,?,?,?)",current.id(),relative,hash,duplicate==null?null:duplicate.itemId(),duplicate==null?"NEW":duplicate.reason(),manifest,stamp);
        return new OutputPlan(relative,hash,duplicate,manifest,stamp);
    });}
    private Duplicate findDuplicate(Connection c,String account,Item item,String hash)throws SQLException{
        String[] conditions={"source_key=? AND unique_id=? AND size=? AND sha256=?","unique_id=? AND size=? AND sha256=?","size=? AND sha256=?"};
        Object[][] arguments={{item.file().media().key(),item.file().uniqueId(),item.file().size(),hash},{item.file().uniqueId(),item.file().size(),hash},{item.file().size(),hash}};
        String[] reasons={"SOURCE_MESSAGE","FILE_ID","CONTENT_SHA"};
        for(int n=0;n<conditions.length;n++)try(var q=c.prepareStatement("SELECT item_id,source_link,output_path FROM download_completion_log WHERE account_id=? AND item_id<>? AND "+conditions[n]+" ORDER BY completed_at,item_id LIMIT 1")){
            q.setString(1,account);q.setString(2,item.id());for(int j=0;j<arguments[n].length;j++)q.setObject(j+3,arguments[n][j]);
            try(var r=q.executeQuery()){if(r.next())return new Duplicate(r.getString(1),reasons[n],r.getString(2),r.getString(3));}
        }return null;
    }
    public void finalizing(Task task,Item item,OutputPlan plan,String relative){database.write(c->{
        if(plan==null)throw new IllegalArgumentException("缺少下载输出计划");
        var before=Path.of(plan.relativePath());var next=Path.of(relative);
        if(next.isAbsolute()||next.normalize().startsWith("..")||!java.util.Objects.equals(before.getParent(),next.getParent()))throw new IllegalArgumentException("输出路径不属于已绑定目录");
        update(c,"UPDATE download_output_plans SET relative_path=? WHERE item_id=? AND sha256=?",relative,item.id(),plan.sha256());
        update(c,"UPDATE download_items SET state='FINALIZING',downloaded_bytes=size,written_bytes=size,sha256=?,relative_file=? WHERE item_id=? AND task_id=? AND state NOT IN ('COMPLETED','SKIPPED_HISTORY')",plan.sha256(),next.getFileName().toString(),item.id(),task.id());return null;
    });}
    private static String postLink(Task task){return task.post().mainMessages().stream().map(r->r.link()).filter(s->s!=null&&!s.isBlank()).findFirst().orElse(null);}
    public List<MessageRef> missingSourceLinks(String account){return database.read(c->{
        var refs=new ArrayList<MessageRef>();try(var q=c.prepareStatement("SELECT source_chat_id,source_message_id FROM download_completion_log WHERE account_id=? AND (source_link IS NULL OR source_link='') AND source_chat_id IS NOT NULL AND source_message_id IS NOT NULL GROUP BY source_chat_id,source_message_id ORDER BY MIN(completed_at),source_chat_id,source_message_id LIMIT 20")){q.setString(1,account);try(var r=q.executeQuery()){while(r.next())refs.add(new MessageRef(r.getString(1),r.getString(2),null));}}return List.copyOf(refs);
    });}
    public int fillSourceLink(String account,MessageRef ref,String link){if(link==null)return 0;return database.write(c->{
        try(var q=c.prepareStatement("UPDATE download_completion_log SET source_link=? WHERE account_id=? AND source_chat_id=? AND source_message_id=? AND (source_link IS NULL OR source_link='')")){q.setString(1,link);q.setString(2,account);q.setString(3,ref.chatId());q.setString(4,ref.messageId());return q.executeUpdate();}
    });}
    public int missingSourceLinkCount(String account){return database.read(c->{try(var q=c.prepareStatement("SELECT COUNT(*) FROM download_completion_log WHERE account_id=? AND (source_link IS NULL OR source_link='')")){q.setString(1,account);try(var r=q.executeQuery()){r.next();return r.getInt(1);}}});}
    private History history(Connection c, String account, String uniqueId, long size) throws SQLException {
        try (var q = c.prepareStatement("SELECT id,source_task_id,original_path,sha256,completed_at FROM download_history WHERE account_id=? AND unique_id=? AND size=?")) {
            q.setString(1, account);q.setString(2, uniqueId);q.setLong(3, size);
            try (var r = q.executeQuery()) { return r.next() ? new History(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5)) : null; }
        }
    }
    public record Receipt(String uniqueId, long size, String sha256) { }
    public Receipt receipt(String account, Path path) { return database.read(c -> {
        try (var q = c.prepareStatement("SELECT unique_id,size,sha256 FROM download_file_receipts WHERE account_id=? AND path_key=?")) {
            q.setString(1, account); q.setString(2, DownloadPaths.key(path)); try (var r = q.executeQuery()) { return r.next() ? new Receipt(r.getString(1), r.getLong(2), r.getString(3)) : null; }
        }
    }); }
    public void own(String account, String uniqueId, int fileId) { database.write(c -> {
        try (var q = c.prepareStatement("SELECT COUNT(*),SUM(CASE WHEN unique_id=? THEN 1 ELSE 0 END) FROM download_cache_owned WHERE account_id=?")) {
            q.setString(1, uniqueId); q.setString(2, account); try (var r = q.executeQuery()) {
                if (r.next() && r.getLong(1) >= 128 && r.getLong(2) == 0) throw new IllegalArgumentException("待核对的原文件缓存较多，请先继续或核对已有任务");
            }
        }
        update(c, "INSERT INTO download_cache_owned VALUES(?,?,?) ON CONFLICT(account_id,unique_id) DO UPDATE SET file_id=excluded.file_id", account, uniqueId, fileId); return null;
    }); }
    public boolean owned(String account, String uniqueId) { return database.read(c -> {
        try (var q = c.prepareStatement("SELECT 1 FROM download_cache_owned WHERE account_id=? AND unique_id=?")) {
            q.setString(1, account); q.setString(2, uniqueId); try (var r = q.executeQuery()) { return r.next(); }
        }
    }); }
    public List<CacheOwner> owners(String account, int limit) { return database.read(c -> {
        var items = new ArrayList<CacheOwner>(); try (var q = c.prepareStatement("SELECT unique_id,file_id FROM download_cache_owned WHERE account_id=? ORDER BY rowid LIMIT ?")) {
            q.setString(1, account); q.setInt(2, limit); try (var r = q.executeQuery()) { while (r.next()) items.add(new CacheOwner(account, r.getString(1), r.getInt(2))); }
        } return List.copyOf(items);
    }); }
    public boolean pendingReference(String account, String uniqueId) { return database.read(c -> {
        try (var q = c.prepareStatement("SELECT 1 FROM download_items i JOIN download_tasks t ON t.task_id=i.task_id WHERE t.account_id=? AND i.unique_id=? AND i.state NOT IN ('COMPLETED','SKIPPED_HISTORY') LIMIT 1")) {
            q.setString(1, account); q.setString(2, uniqueId); try (var r = q.executeQuery()) { return r.next(); }
        }
    }); }
    public void released(String account, String uniqueId) { database.write(c -> { update(c, "DELETE FROM download_cache_owned WHERE account_id=? AND unique_id=?", account, uniqueId); return null; }); }
    public TaskView view(Task task) {
        Path directory = Path.of(task.targetRoot()).resolve(task.relativeDirectory());
        var items = task.items().stream().map(i -> new ItemView(i.id(), i.file().fileName(), i.file().media().type(), i.state(), i.file().size(), i.downloadedBytes(), i.writtenBytes(), i.state().equals("SKIPPED_HISTORY") ? null : i.plan()==null?directory.resolve(i.relativeFile()).toString():Path.of(task.targetRoot()).resolve(i.plan().relativePath()).toString(), i.error(), i.history(),i.file().media().message().link(),i.plan()==null?null:i.plan().duplicate(),i.plan()==null||i.plan().manifestRelative()==null?null:Path.of(task.targetRoot()).resolve(i.plan().manifestRelative()).toString())).toList();
        return new TaskView(task.id(), task.post().title(), task.post().channelTitle(), task.state(), task.pauseRequested(), directory.toString(), "OBSERVED_SELECTION", items.size(), items.stream().filter(i -> i.state().equals("COMPLETED")).count(), items.stream().filter(i -> i.state().equals("SKIPPED_HISTORY")).count(), items.stream().mapToLong(ItemView::size).sum(), task.error(), task.createdAt(), task.updatedAt(), items,task.duplicatePolicy(),items.stream().filter(i->i.state().equals("COMPLETED")&&i.duplicate()!=null).count(),Path.of(task.targetRoot(),"重复媒体").toString(),automation(task));
    }
    private Automation automation(Task task){return database.read(c->{try(var q=c.prepareStatement("SELECT b.rule_id,b.rule_version,b.snapshot_json,b.manual,b.held,r.enabled,r.current_version FROM download_rule_tasks b JOIN download_rules r ON r.id=b.rule_id AND r.account_id=b.account_id WHERE b.task_id=? AND b.account_id=? LIMIT 1")){q.setString(1,task.id());q.setString(2,task.accountId());try(var r=q.executeQuery()){if(!r.next())return null;var frozen=decode(r.getString(3),io.github.telegramenhance.downloadrules.DownloadRuleModels.Input.class);return new Automation(r.getString(1),r.getLong(2),frozen.name(),r.getBoolean(4),r.getBoolean(5),r.getBoolean(6),r.getLong(7),frozen.window());}}});}
    private Task task(Connection c, String account, String id) throws SQLException {
        try (var q = c.prepareStatement("SELECT * FROM download_tasks WHERE account_id=? AND task_id=?")) {
            q.setString(1, account); q.setString(2, id); try (var r = q.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("当前账号中没有这条下载任务");
                var items = new ArrayList<Item>(); try (var iq = c.prepareStatement("SELECT i.*,h.source_task_id,h.original_path,h.completed_at,h.sha256 AS history_sha,p.relative_path,p.sha256 AS plan_sha,p.duplicate_of,p.reason,p.manifest_relative,p.planned_at,l.item_id AS matched_id,l.source_link AS matched_link,l.output_path AS matched_path FROM download_items i LEFT JOIN download_history h ON h.id=i.history_id AND h.account_id=? AND h.unique_id=i.unique_id AND h.size=i.size LEFT JOIN download_output_plans p ON p.item_id=i.item_id LEFT JOIN download_completion_log l ON l.item_id=p.duplicate_of AND l.account_id=? WHERE i.task_id=? ORDER BY i.ordinal")) {
                    iq.setString(1, account);iq.setString(2, account);iq.setString(3, id); try (var ir = iq.executeQuery()) { while (ir.next()) items.add(item(ir)); }
                }
                return new Task(id, account, r.getString("operation_id"), r.getString("fingerprint"), decode(r.getString("post_json"), Post.class), r.getString("target_root"), r.getString("relative_directory"), r.getString("state"), r.getBoolean("pause_requested"), r.getString("error_message"), r.getLong("created_at"), r.getLong("updated_at"), items,r.getString("duplicate_policy"));
            }
        }
    }
    private Item item(ResultSet r) throws SQLException {
        var history = r.getString("history_id") == null || r.getString("source_task_id") == null ? null : new History(r.getString("history_id"), r.getString("source_task_id"), r.getString("original_path"), r.getString("history_sha"), r.getLong("completed_at"));
        if (r.getString("state").equals("SKIPPED_HISTORY") && history == null) throw new IllegalStateException("历史跳过证据无法核对");
        if(r.getString("duplicate_of")!=null&&r.getString("matched_id")==null)throw new IllegalStateException("重复媒体关联证据无法核对");
        var duplicate=r.getString("duplicate_of")==null?null:new Duplicate(r.getString("duplicate_of"),r.getString("reason"),r.getString("matched_link"),r.getString("matched_path"));
        var plan=r.getString("relative_path")==null?null:new OutputPlan(r.getString("relative_path"),r.getString("plan_sha"),duplicate,r.getString("manifest_relative"),r.getLong("planned_at"));
        return new Item(r.getString("item_id"), r.getInt("ordinal"), decode(r.getString("spec_json"), FrozenFile.class), r.getString("relative_file"), r.getString("owner_token"), r.getString("state"), r.getLong("downloaded_bytes"), r.getLong("written_bytes"), r.getString("sha256"), r.getString("error_message"), history,plan);
    }
    private String encode(Object value) { return json.writeValueAsString(value); }
    private <T> T decode(String value, Class<T> type) { return json.readValue(value, type); }
    private static long now() { return System.currentTimeMillis(); }
    private static void update(Connection c, String sql, Object... values) throws SQLException { try (var q = c.prepareStatement(sql)) { for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i]); q.executeUpdate(); } }
}
