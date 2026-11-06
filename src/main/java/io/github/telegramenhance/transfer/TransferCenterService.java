package io.github.telegramenhance.transfer;

import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.rules.RuleService;
import io.github.telegramenhance.rules.RulePreparationService;
import io.github.telegramenhance.downloadrules.DownloadRuleService;
import io.github.telegramenhance.transfer.TransferCenterModels.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Read projections and explicit local commands; no new send authority is granted here. */
@Service
public final class TransferCenterService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final int RECEIPT_LIMIT=100;
    private static final List<String> PAUSE_WARNINGS=List.of(
        "已包含当前账号的上传/下载队列及上传、下载规则生产；每项受理结果分别记录。",
        "正在发送的上传组仍需接收原请求回执并归档；暂停不会撤回消息，也不会重发未知结果。",
        "频道导入、手动频道扫描和批量媒体准备会在当前有界读取保存检查点后暂停后续步骤；恢复不会自动继续读取。",
        "单次规则准备不可中断，逐项回执会明确列明；准备结果和历史可继续核对，不会伪称已暂停。");
    private final Database db;private final TelegramService telegram;private final DownloadRepository downloads;private final UploadRepository uploads;
    private final DownloadTaskService downloadTasks;private final DownloadBatchService batches;private final UploadTaskService uploadTasks;
    private final DownloadRuleTaskService ruleTasks;private final RuleService uploadRules;private final DownloadRuleService downloadRules;
    private final RulePreparationService preparations;private final DownloadChannelService scans;private final UploadRetryService retries;
    private final Set<String> runningOperations=new HashSet<>();
    private final TransferRateSampler rates=new TransferRateSampler();
    private ScanTaskStore scanHistory;
    @org.springframework.beans.factory.annotation.Autowired public void trackScans(ScanTaskStore scanHistory){this.scanHistory=scanHistory;}
    public TransferCenterService(Database db,TelegramService telegram,DownloadRepository downloads,UploadRepository uploads,
        DownloadTaskService downloadTasks,DownloadBatchService batches,UploadTaskService uploadTasks,DownloadRuleTaskService ruleTasks,
        RuleService uploadRules,DownloadRuleService downloadRules,RulePreparationService preparations,DownloadChannelService scans,UploadRetryService retries){
        this.db=db;this.telegram=telegram;this.downloads=downloads;this.uploads=uploads;this.downloadTasks=downloadTasks;this.batches=batches;
        this.uploadTasks=uploadTasks;this.ruleTasks=ruleTasks;this.uploadRules=uploadRules;this.downloadRules=downloadRules;
        this.preparations=preparations;this.scans=scans;this.retries=retries;
    }
    private record Ref(String category,String id,String state,long created,long updated,Row row) { }
    public Page list(String kind,String status,int limit,int offset){
        if(!Set.of("all","upload","download","scan").contains(kind)||!Set.of("all","active","attention","waiting","completed").contains(status)
            ||limit<1||limit>50||offset<0)throw new IllegalArgumentException("任务筛选或分页参数无效");
        String account=telegram.accountId();long generation=telegram.sessionGeneration();
        var refs=references(account);var counts=new long[4];
        for(var ref:refs)counts[switch(group(ref)){case "active"->0;case "attention"->1;case "waiting"->2;default->3;}]++;
        var matching=refs.stream().filter(ref->kind.equals("all")||kind.equals(kind(ref.category())))
            .filter(ref->status.equals("all")||status.equals(group(ref)))
            .sorted(Comparator.comparingLong(Ref::updated).reversed().thenComparing(Ref::category).thenComparing(Ref::id)).toList();
        current(account,generation);
        var rows=matching.stream().skip(offset).limit(limit).map(ref->ref.row()!=null?ref.row():project(account,ref)).map(row->sample(account,generation,row)).toList();
        current(account,generation);return new Page(rows,matching.size(),new Counts(counts[0],counts[1],counts[2],counts[3]),
            List.of("批量下载、规则下载按父记录计数，所属子任务不重复列出。扫描进度按已观察记录展示，不推断未知总量。"));
    }
    private List<Ref> references(String account){
        var refs=db.read(c->{var result=new ArrayList<Ref>();
            addRefs(c,result,account,"upload","SELECT id,CASE WHEN state='RUNNING' AND stop_requested=1 THEN 'PAUSING' ELSE state END,created_at,updated_at FROM upload_tasks WHERE account_id=?");
            addRefs(c,result,account,"download","""
                SELECT t.task_id,CASE WHEN t.state='RUNNING' AND t.pause_requested=1 THEN 'PAUSING' ELSE t.state END,t.created_at,t.updated_at
                FROM download_tasks t WHERE t.account_id=?
                  AND NOT EXISTS(SELECT 1 FROM download_task_deletions d WHERE d.task_id=t.task_id AND d.account_id=t.account_id)
                  AND NOT EXISTS(SELECT 1 FROM download_batch_tasks b WHERE b.task_id=t.task_id)
                  AND NOT EXISTS(SELECT 1 FROM download_rule_tasks r WHERE r.task_id=t.task_id AND r.account_id=t.account_id)
                """);
            addRefs(c,result,account,"batch","""
                SELECT b.id,CASE WHEN MAX(t.state='RUNNING' AND t.pause_requested=1)=1 THEN 'PAUSING'
                  WHEN MAX(t.state='RUNNING')=1 THEN 'RUNNING' WHEN MAX(t.state='QUEUED')=1 THEN 'QUEUED'
                  WHEN MAX(t.state='FAILED')=1 THEN 'FAILED' WHEN MAX(t.state='PAUSED')=1 THEN 'PAUSED' ELSE 'COMPLETED' END,
                  b.created_at,MAX(COALESCE(t.updated_at,b.created_at))
                FROM download_batches b LEFT JOIN download_batch_tasks bt ON bt.batch_id=b.id
                LEFT JOIN download_tasks t ON t.task_id=bt.task_id AND t.account_id=b.account_id WHERE b.account_id=? GROUP BY b.id
                """);
            try(var q=c.prepareStatement("SELECT channel_id,run_id,status,processed,created,updated,incomplete,exhausted,warning,started_at,updated_at FROM channel_import_runs WHERE account_id=?")){
                q.setString(1,account);try(var r=q.executeQuery()){while(r.next()){
                    String state=r.getBoolean("exhausted")?"COMPLETED":"BLOCKED".equals(r.getString("status"))?"FAILED":"WAITING_STEP";
                    long processed=r.getLong("processed");var row=row(r.getString("run_id"),"import","频道导入",r.getString("channel_id"),state,
                        r.getLong("started_at"),r.getLong("updated_at"),0,processed,0,0,0,"IMPORTING",r.getString("channel_id"),List.of(),r.getString("warning"),null);
                    result.add(ref(row));
                }}
            }return result;});
        for(var value:ruleTasks.list().items()){
            if(value.taskCount()==0&&!value.enabled())continue;
            String state="STOPPED".equals(value.state())&&value.total()>0&&value.completed()+value.skipped()==value.total()?"COMPLETED":value.state();
            var row=row(value.id(),"rule-download",value.name(),value.channelTitle(),state,0,value.updatedAt(),value.total(),value.completed()+value.skipped(),
                value.totalBytes(),value.downloadedBytes(),value.writtenBytes(),downloadPhase(state,value.totalBytes(),value.downloadedBytes(),value.writtenBytes()),value.id(),
                value.enabled()?List.of("pause-rule"):List.of(),value.failedTasks()>0?value.failedTasks()+" 个子任务需要处理":null,value.targetDirectory());refs.add(ref(withStatistics(row,value.skipped(),null,null)));
        }
        for(var value:scans.snapshot()){
            String state=value.busy()?"RUNNING":value.error()!=null?"FAILED":value.exhausted()?"COMPLETED":"WAITING_STEP";
            refs.add(ref(row(value.id(),"channel-scan",value.channel().title(),"频道主帖扫描 · 已匹配 "+value.matched(),state,value.createdAt(),value.createdAt(),0,value.scanned(),0,0,0,"SCANNING",value.id(),List.of(),value.error(),null)));
        }
        for(var value:batches.snapshot()){
            String state=value.busy()?"RUNNING":value.error()!=null?"FAILED":value.exhausted()?"COMPLETED":"WAITING_STEP";
            refs.add(ref(row(value.id(),"preparation",value.channelTitle(),"批量媒体准备",state,value.createdAt(),value.createdAt(),value.posts().size(),value.posts().stream().filter(p->"READY".equals(p.state())).count(),value.totalBytes(),0,0,"PREPARING",value.id(),List.of(),value.error(),value.targetDirectory())));
        }
        for(var value:preparations.snapshot()){
            refs.add(ref(row(value.operationId(),"rule-preparation","规则准备",value.kind().equals("preview")?"生成上传清单":"检测目录",value.state(),value.startedAt(),value.finishedAt()==null?value.startedAt():value.finishedAt(),0,0,0,0,0,"PREPARING",value.ruleId(),List.of(),value.error(),null)));
        }
        if(scanHistory!=null){
            var history=scanHistory.list();
            refs.removeIf(ref->Set.of("channel-scan","preparation","import","rule-preparation").contains(ref.category())&&history.stream().anyMatch(saved->saved.category().equals(ref.category())&&saved.sourceId().equals(ref.id())));
            for(int index=0;index<refs.size();index++){var ref=refs.get(index);if(ref.category().equals("import")&&ref.row()!=null&&!ref.state().equals("COMPLETED")){var old=ref.row();refs.set(index,ref(row(old.id(),old.category(),old.title(),old.subtitle(),"NEEDS_REVIEW",old.createdAt(),old.updatedAt(),old.totalFiles(),old.resolvedFiles(),old.totalBytes(),0,0,old.phase(),old.detailId(),List.of(),"旧导入检查点尚未在本次会话核对，请回原面板显式开始以核对并继续",old.targetDirectory())));}}
            for(var saved:history)refs.add(ref(scanRow(saved)));
        }
        for(var value:uploadRules.list().items()){
            var rule=value.rule();String error=rule.observation()==null?null:rule.observation().error();
            if(!rule.enabled()&&!value.scanning()&&error==null)continue;
            var observation=rule.observation();long count=observation==null||observation.scan()==null?0:observation.scan().observed().size();
            refs.add(ref(row(rule.id(),"upload-rule-scan",rule.input().name(),"上传规则目录观察",value.scanning()?"RUNNING":error!=null?"FAILED":"WAITING_SCAN",rule.createdAt(),rule.updatedAt(),0,count,0,0,0,"OBSERVING",rule.id(),rule.enabled()?List.of("pause-rule"):List.of(),error,rule.input().sourceDirectory())));
        }
        for(var value:downloadRules.list().items()){
            var rule=value.rule();if(!rule.enabled()&&!value.busy()&&value.summary().scanError()==null)continue;
            refs.add(ref(row(rule.id(),"download-rule-scan",rule.input().name(),"下载规则来源扫描",value.busy()?"RUNNING":value.summary().scanError()!=null?"FAILED":"WAITING_SCAN",rule.createdAt(),rule.updatedAt(),0,value.summary().scanned(),0,0,0,"SCANNING",rule.id(),rule.enabled()?List.of("pause-rule"):List.of(),value.summary().scanError(),rule.input().targetDirectory())));
        }
        return refs;
    }
    private static void addRefs(Connection c,List<Ref> refs,String account,String category,String sql)throws SQLException{
        try(var q=c.prepareStatement(sql)){q.setString(1,account);try(var r=q.executeQuery()){while(r.next())refs.add(new Ref(category,r.getString(1),r.getString(2),r.getLong(3),r.getLong(4),null));}}
    }
    private Row project(String account,Ref ref){
        if(ref.category().equals("download")){
            var task=downloads.view(downloads.task(account,ref.id()));long downloaded=task.items().stream().mapToLong(DownloadModels.ItemView::downloadedBytes).sum(),written=task.items().stream().mapToLong(DownloadModels.ItemView::writtenBytes).sum();
            var actions=new ArrayList<String>();if(Set.of("RUNNING","QUEUED").contains(task.state())&&!task.pauseRequested())actions.add("pause");if(Set.of("PAUSED","FAILED").contains(task.state()))actions.add("resume");if(task.state().equals("PAUSED"))actions.add("delete");
            return withRetry(withStatistics(row(task.id(),"download",task.title(),task.channelTitle(),task.state(),task.createdAt(),task.updatedAt(),task.total(),task.completed()+task.skipped(),task.totalBytes(),downloaded,written,
                downloadPhase(task.state(),task.totalBytes(),downloaded,written),task.id(),actions,task.error(),task.targetDirectory()),task.skipped(),null,null),task.retry());
        }
        if(ref.category().equals("batch")){
            var task=downloads.batchStatus(account,ref.id());var actions=new ArrayList<String>();if(task.canPause())actions.add("pause");if(task.canResume())actions.add("resume");
            return withStatistics(row(task.id(),"batch",task.snapshot().channelTitle(),"批量下载 · "+task.totalTasks()+" 个主帖",task.state(),task.createdAt(),task.updatedAt(),task.snapshot().total(),task.completed()+task.skipped(),task.snapshot().totalBytes(),task.downloadedBytes(),task.writtenBytes(),downloadPhase(task.state(),task.snapshot().totalBytes(),task.downloadedBytes(),task.writtenBytes()),task.id(),actions,task.failedTasks()>0?task.failedTasks()+" 个子任务失败":null,task.snapshot().targetDirectory()),task.skipped(),null,null);
        }
        var raw=uploads.task(account,ref.id());var task=uploads.view(raw);var items=raw.groups().stream().flatMap(g->g.items().stream()).toList();var actions=new ArrayList<String>();
        boolean unsent=raw.groups().stream().anyMatch(g->g.state().equals("READY"));
        boolean managedStart=task.automation()==null||task.automation().manualTakeover()||task.automation().status().equals("AUTOMATIC");
        if(Set.of("READY","PAUSED").contains(task.state())&&unsent&&managedStart&&raw.groups().stream().allMatch(g->Set.of("READY","CONFIRMED").contains(g.state())))actions.add("start");
        if(Set.of("QUEUED","RUNNING").contains(task.state())&&!task.stopRequested())actions.add("pause");
        if(Set.of("READY","PAUSED").contains(task.state())&&raw.groups().stream().allMatch(g->Set.of("READY","PREPARING").contains(g.state()))&&items.stream().allMatch(i->i.temporaryId()==null&&i.formalId()==null&&Set.of("READY","STAGED").contains(i.state())))actions.add("cancel");
        boolean unarchived=raw.groups().stream().filter(g->g.state().equals("CONFIRMED")).anyMatch(g->g.items().stream().anyMatch(i->i.formalId()!=null&&!i.state().equals("ARCHIVED")));
        boolean sendingEvidence=raw.groups().stream().anyMatch(g->Set.of("INTENT","DISPATCHING","SENDING","UNCERTAIN","CONFIRMED").contains(g.state()));
        if(task.state().equals("SENT_PENDING_ARCHIVE")||task.state().equals("PAUSED")&&sendingEvidence)actions.add("reconcile");
        if(task.state().equals("NEEDS_REVIEW")){actions.add("recover-pending");actions.add("retry-check");}
        String phase=task.state().equals("COMPLETED")?"COMPLETED":task.state().equals("SENT_PENDING_ARCHIVE")||unarchived?"ARCHIVING":raw.groups().stream().anyMatch(g->g.state().equals("PREPARING"))?"PREPARING":raw.groups().stream().anyMatch(g->Set.of("INTENT","SENDING","DISPATCHING","UNCERTAIN").contains(g.state()))?"WAITING_RECEIPT":"QUEUED";
        var progress=uploadTasks.progress(task.id());
        if(phase.equals("WAITING_RECEIPT")&&progress.observedItems()>0&&progress.uploadedBytes()<progress.totalBytes())phase="UPLOADING";
        return new Row(task.id(),"upload","upload",task.postTitle(),task.channelTitle(),ref.state(),statusGroup(ref.state()),task.createdAt(),task.updatedAt(),task.total(),task.archived(),progress.totalBytes(),progress.uploadedBytes(),0,
            progress.speedBytesPerSecond(),progress.etaSeconds(),phase,task.id(),actions,task.error(),task.archiveDirectory(),0);
    }
    static String statusGroup(String state){return switch(state){case "RUNNING","PAUSING","PREPARING","SENDING"->"active";case "FAILED","ERROR","NEEDS_REVIEW","SENT_PENDING_ARCHIVE","EXPIRED"->"attention";case "COMPLETED","COMPLETE","CANCELLED"->"completed";default->"waiting";};}
    private static String group(Ref ref){return ref.row()==null?statusGroup(ref.state()):ref.row().statusGroup();}
    private static String kind(String category){return category.equals("upload")?"upload":Set.of("download","batch","rule-download").contains(category)?"download":"scan";}
    private static Ref ref(Row row){return new Ref(row.category(),row.id(),row.state(),row.createdAt(),row.updatedAt(),row);}
    private static Row row(String id,String category,String title,String subtitle,String state,long created,long updated,long total,long resolved,long bytes,long transferred,long written,String phase,String detail,List<String> actions,String error,String directory){
        return new Row(id,kind(category),category,title,subtitle,state,statusGroup(state),created,updated,total,resolved,bytes,transferred,written,null,null,phase,detail,actions,error,directory,0);
    }
    private Row sample(String account,long generation,Row row){if(!row.kind().equals("download"))return row;
        String phase=observedDownloadPhase(account,row);
        row=new Row(row.id(),row.kind(),row.category(),row.title(),row.subtitle(),row.state(),row.statusGroup(),row.createdAt(),row.updatedAt(),row.totalFiles(),row.resolvedFiles(),row.totalBytes(),row.transferredBytes(),row.writtenBytes(),row.speedBytesPerSecond(),row.etaSeconds(),phase,row.detailId(),row.actions(),row.error(),row.targetDirectory(),row.skippedFiles(),row.retry());
        var rate=rates.observe(account,generation,row.category()+":"+row.id(),row.transferredBytes(),row.skippedFiles()>0?null:row.totalBytes(),row.state().equals("RUNNING")&&row.phase().equals("DOWNLOADING"));
        return withStatistics(row,row.skippedFiles(),rate.bytesPerSecond(),rate.etaSeconds());
    }
    private String observedDownloadPhase(String account,Row row){
        if(row.state().equals("COMPLETED"))return "COMPLETED";
        if(!Set.of("RUNNING","PAUSING","QUEUED").contains(row.state()))return "QUEUED";
        // Keep the retry plan and its projected state from the same observation. A
        // worker may claim it after project(); the following GET will show that run.
        if(row.category().equals("download")&&row.retry()!=null)return "RETRY_WAIT";
        return db.read(c->{String binding=switch(row.category()){
            case "download"->"t.task_id=?";
            case "batch"->"EXISTS(SELECT 1 FROM download_batch_tasks b WHERE b.task_id=t.task_id AND b.batch_id=?)";
            case "rule-download"->"EXISTS(SELECT 1 FROM download_rule_tasks b WHERE b.task_id=t.task_id AND b.account_id=t.account_id AND b.rule_id=?)";
            default->throw new IllegalArgumentException("下载任务类型无效");};
            if(row.state().equals("QUEUED")){
                try(var q=c.prepareStatement("SELECT 1 FROM download_tasks t JOIN download_task_retries r ON r.task_id=t.task_id AND r.account_id=t.account_id WHERE t.account_id=? AND t.state='QUEUED' AND t.pause_requested=0 AND "+binding+" LIMIT 1")){
                    q.setString(1,account);q.setString(2,row.id());try(var r=q.executeQuery()){return r.next()?"RETRY_WAIT":"QUEUED";}}
            }
            try(var q=c.prepareStatement("SELECT MAX(i.state='DOWNLOADING'),MAX(i.state='WRITING'),MAX(i.state='FINALIZING') FROM download_tasks t JOIN download_items i ON i.task_id=t.task_id WHERE t.account_id=? AND "+binding)){
                q.setString(1,account);q.setString(2,row.id());try(var r=q.executeQuery()){r.next();return r.getBoolean(1)?"DOWNLOADING":r.getBoolean(2)?"WRITING":r.getBoolean(3)?"COMMITTING":"QUEUED";}}
        });
    }
    private static Row withStatistics(Row row,long skipped,Long rate,Long eta){return new Row(row.id(),row.kind(),row.category(),row.title(),row.subtitle(),row.state(),row.statusGroup(),row.createdAt(),row.updatedAt(),row.totalFiles(),row.resolvedFiles(),row.totalBytes(),row.transferredBytes(),row.writtenBytes(),rate,eta,row.phase(),row.detailId(),row.actions(),row.error(),row.targetDirectory(),skipped,row.retry());}
    private static Row withRetry(Row row,DownloadModels.Retry retry){return new Row(row.id(),row.kind(),row.category(),row.title(),row.subtitle(),row.state(),row.statusGroup(),row.createdAt(),row.updatedAt(),row.totalFiles(),row.resolvedFiles(),row.totalBytes(),row.transferredBytes(),row.writtenBytes(),row.speedBytesPerSecond(),row.etaSeconds(),row.phase(),row.detailId(),row.actions(),row.error(),row.targetDirectory(),row.skippedFiles(),retry);}
    private static String downloadPhase(String state,long bytes,long downloaded,long written){return state.equals("COMPLETED")?"COMPLETED":bytes>0&&downloaded>=bytes?(written>=bytes?"COMMITTING":"WRITING"):"DOWNLOADING";}
    private static Row scanRow(ScanTaskStore.View view){return row(view.id(),view.category(),view.title(),view.subtitle(),view.state(),view.createdAt(),view.updatedAt(),view.total(),view.processed(),view.bytes(),0,0,view.phase(),view.detailId(),view.actions(),view.error(),view.directory());}
    public CompletableFuture<ActionResult> action(Action input){
        if(input==null)throw new IllegalArgumentException("请选择任务操作");uuid(input.id());String account=telegram.accountId();long generation=telegram.sessionGeneration();
        Object result;
        switch(input.category()==null?"":input.category()){
            case "upload"->{
                if("recover-pending".equals(input.action()))return uploadTasks.recoverPending(input.id()).thenApply(item->{current(account,generation);return new ActionResult(item,null,"仅核对原发送请求；未知结果不会重发。");});
                if("retry-check".equals(input.action()))return retries.check(input.id()).thenApply(check->{current(account,generation);return new ActionResult(null,check,"已核对失败成员；尚未执行重试，请在上传详情确认。");});
                if(!Set.of("start","pause","cancel","reconcile").contains(input.action()==null?"":input.action()))throw new IllegalArgumentException("任务操作无效");result=uploadTasks.action(input.id(),input.action());
            }
            case "download"->{
                if("delete".equals(input.action())){
                    var deleted=downloadTasks.delete(input.id());current(account,generation);
                    return CompletableFuture.completedFuture(new ActionResult(null,new DeletionReceipt("download",deleted.id(),true),null));
                }
                result=switch(input.action()==null?"":input.action()){case "pause"->downloadTasks.pause(input.id());case "resume"->downloadTasks.resume(input.id());default->throw new IllegalArgumentException("任务操作无效");};
            }
            case "batch"->result=switch(input.action()==null?"":input.action()){case "pause"->batches.pause(input.id());case "resume"->batches.resume(input.id());default->throw new IllegalArgumentException("任务操作无效");};
            case "rule-download","download-rule-scan"->{if(!"pause-rule".equals(input.action()))throw new IllegalArgumentException("任务操作无效");var rule=downloadRules.get(input.id()).rule();result=downloadRules.toggle(input.id(),new DownloadRuleService.Toggle(rule.revision(),false));}
            case "upload-rule-scan"->{if(!"pause-rule".equals(input.action()))throw new IllegalArgumentException("任务操作无效");var rule=uploadRules.get(input.id()).rule();result=uploadRules.toggle(input.id(),new RuleService.Toggle(rule.revision(),false));}
            case "channel-scan","preparation","import"->{if(scanHistory==null)throw new IllegalArgumentException("扫描控制未初始化，请刷新核对");var controlled=scanHistory.list().stream().filter(v->v.id().equals(input.id())&&v.category().equals(input.category())).findFirst().orElseThrow(()->new IllegalArgumentException("当前账号没有这条扫描历史"));result=scanHistory.action(controlled.id(),input.action());}
            default->throw new IllegalArgumentException("该扫描没有暂停接口，请在原面板核对进度");
        }
        current(account,generation);return CompletableFuture.completedFuture(new ActionResult(result,null,null));
    }
    public ScanTaskStore.View scanDetail(String id){if(scanHistory==null)throw new IllegalArgumentException("扫描历史未初始化");return scanHistory.detail(id);}
    /** Same operation is never replayed, including after a crash with an unfinished per-item receipt. */
    public synchronized PauseReceipt pauseAll(PauseRequest request){
        if(request==null)throw new IllegalArgumentException("暂停操作号无效");uuid(request.operationId());String account=telegram.accountId();long generation=telegram.sessionGeneration();String key=receiptKey(account,request.operationId());
        var previous=readReceipt(key);if(previous!=null){current(account,generation);return visible(previous,key);}
        var accepted=new ArrayList<Outcome>();var failed=new ArrayList<Outcome>();var pending=new ArrayList<Outcome>();
        long created=System.currentTimeMillis();var initial=new PauseReceipt(request.operationId(),"RUNNING",accepted,failed,pending,PAUSE_WARNINGS,created,created);
        saveReceipt(key,initial);runningOperations.add(key);
        try{
            var uninterruptible=new LinkedHashMap<String,ScanTaskStore.View>();
            if(scanHistory!=null)for(var scan:scanHistory.list())if(scan.current()&&scan.category().equals("rule-preparation")&&scan.state().equals("RUNNING"))uninterruptible.put(scan.id(),scan);
            // Stop producers before collecting queues, so this command cannot silently omit newly produced work.
            for(var view:uploadRules.list().items())if(view.rule().enabled()){
                var rule=view.rule();attempt(account,generation,key,request.operationId(),created,accepted,failed,pending,"upload-rule",rule.id(),"disable",()->uploadRules.toggle(rule.id(),new RuleService.Toggle(rule.revision(),false)));
            }
            for(var view:downloadRules.list().items())if(view.rule().enabled()){
                var rule=view.rule();attempt(account,generation,key,request.operationId(),created,accepted,failed,pending,"download-rule",rule.id(),"disable",()->downloadRules.toggle(rule.id(),new DownloadRuleService.Toggle(rule.revision(),false)));
            }
            var targets=db.read(c->{var refs=new ArrayList<Ref>();addRefs(c,refs,account,"download","SELECT task_id,state,created_at,updated_at FROM download_tasks WHERE account_id=? AND state IN ('QUEUED','RUNNING') AND pause_requested=0");addRefs(c,refs,account,"upload","SELECT id,state,created_at,updated_at FROM upload_tasks WHERE account_id=? AND state IN ('QUEUED','RUNNING') AND stop_requested=0");return refs;});
            for(var target:targets)attempt(account,generation,key,request.operationId(),created,accepted,failed,pending,target.category(),target.id(),"pause",()->target.category().equals("upload")?uploadTasks.action(target.id(),"pause"):downloadTasks.pause(target.id()));
            if(scanHistory!=null)for(var scan:scanHistory.list()){
                if(scan.actions().contains("pause"))attempt(account,generation,key,request.operationId(),created,accepted,failed,pending,scan.category(),scan.id(),"pause",()->scanHistory.action(scan.id(),"pause"));
                else if(scan.current()&&scan.actions().contains("resume"))accepted.add(new Outcome(scan.category(),scan.id(),"pause","已处于暂停或当前步骤后暂停状态"));
                if(scan.current()&&scan.category().equals("rule-preparation")&&scan.state().equals("RUNNING"))uninterruptible.put(scan.id(),scan);
            }
            for(var scan:uninterruptible.values())failed.add(new Outcome(scan.category(),scan.id(),"pause","单次有界规则准备不可中断，未声称暂停；结果及历史可继续核对"));
            var done=new PauseReceipt(request.operationId(),"COMPLETED",accepted,failed,pending,PAUSE_WARNINGS,created,System.currentTimeMillis());saveReceipt(key,done);current(account,generation);return done;
        }finally{runningOperations.remove(key);}
    }
    private void attempt(String account,long generation,String key,String operation,long created,List<Outcome> accepted,List<Outcome> failed,List<Outcome> pending,String category,String id,String action,java.util.function.Supplier<?> command){
        var target=new Outcome(category,id,action,null);pending.add(target);saveReceipt(key,new PauseReceipt(operation,"RUNNING",accepted,failed,pending,PAUSE_WARNINGS,created,System.currentTimeMillis()));
        try{current(account,generation);command.get();current(account,generation);accepted.add(target);}
        catch(RuntimeException failure){failed.add(new Outcome(category,id,action,"本项未能确认受理，请刷新对应任务或规则核对当前状态。"));}
        pending.remove(target);saveReceipt(key,new PauseReceipt(operation,"RUNNING",accepted,failed,pending,PAUSE_WARNINGS,created,System.currentTimeMillis()));
    }
    public synchronized PauseReceipt receipt(String operation){uuid(operation);String account=telegram.accountId();long generation=telegram.sessionGeneration();String key=receiptKey(account,operation);var value=readReceipt(key);current(account,generation);return value==null?null:visible(value,key);}
    private PauseReceipt visible(PauseReceipt value,String key){return value.state().equals("RUNNING")&&!runningOperations.contains(key)?new PauseReceipt(value.operationId(),"NEEDS_REVIEW",value.accepted(),value.failed(),value.pending(),value.warnings(),value.createdAt(),value.updatedAt()):value;}
    private static String receiptKey(String account,String operation){return "transfer-center.pause."+DownloadPaths.hash(account)+"."+operation;}
    private PauseReceipt readReceipt(String key){return db.read(c->{try(var q=c.prepareStatement("SELECT setting_value FROM app_settings WHERE setting_key=?")){q.setString(1,key);try(var r=q.executeQuery()){return r.next()?JSON.readValue(r.getString(1),PauseReceipt.class):null;}}});}
    private void saveReceipt(String key,PauseReceipt value){db.write(c->{try(var q=c.prepareStatement("INSERT INTO app_settings(setting_key,setting_value,updated_at) VALUES(?,?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value,updated_at=excluded.updated_at")){q.setString(1,key);q.setString(2,JSON.writeValueAsString(value));q.setLong(3,value.updatedAt());q.executeUpdate();}
        // Preserve the current operation and the newest account-independent bounded receipts.
        try(var q=c.prepareStatement("DELETE FROM app_settings WHERE setting_key LIKE 'transfer-center.pause.%' AND setting_key<>? AND setting_key NOT IN (SELECT setting_key FROM app_settings WHERE setting_key LIKE 'transfer-center.pause.%' ORDER BY updated_at DESC,setting_key LIMIT ?)")){q.setString(1,key);q.setInt(2,RECEIPT_LIMIT);q.executeUpdate();}return null;});}
    private void current(String account,long generation){if(!Objects.equals(account,telegram.accountId())||generation!=telegram.sessionGeneration())throw new IllegalArgumentException("账号或会话已变化，请核对原账号任务中心");}
    private static void uuid(String value){if(value==null||!value.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))throw new IllegalArgumentException("任务或操作标识无效");}
}
