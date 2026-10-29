package io.github.telegramenhance.transfer;

import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.UploadPreviewModels.FileVersion;
import io.github.telegramenhance.transfer.UploadPreviewService.Prepared;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import io.github.telegramenhance.rules.RuleUploadPolicy;
import io.github.telegramenhance.rules.RuleModels.Rule;
import io.github.telegramenhance.library.ResourceModels.Post;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public final class UploadRepository {
    private final Database database; private final JsonMapper json = JsonMapper.builder().build();private final RuleUploadPolicy rules;
    public UploadRepository(Database database){this(database,new RuleUploadPolicy(database));}
    @org.springframework.beans.factory.annotation.Autowired
    public UploadRepository(Database database,RuleUploadPolicy rules) { this.database = database;this.rules=rules; database.write(c -> {
        sql(c,"UPDATE upload_groups SET state='READY' WHERE state='PREPARING'");
        sql(c,"UPDATE upload_groups SET state='UNCERTAIN',error='退出时发送结果未确认，禁止自动重发' WHERE state IN ('INTENT','DISPATCHING','SENDING')");
        rules.markRestartReady(c);
        sql(c,"UPDATE upload_tasks SET state=CASE WHEN EXISTS(SELECT 1 FROM upload_groups g WHERE g.task_id=upload_tasks.id AND g.state='UNCERTAIN') THEN 'NEEDS_REVIEW' ELSE 'PAUSED' END,stop_requested=1,error='应用已重启，请核对后继续' WHERE state IN ('QUEUED','RUNNING')"); return null; }); }
    public Task byOperation(String account, String operation) { return database.read(c -> { try(var q=c.prepareStatement("SELECT id FROM upload_tasks WHERE account_id=? AND operation_id=?")){q.setString(1,account);q.setString(2,operation);try(var r=q.executeQuery()){return r.next()?task(c,account,r.getString(1)):null;}} }); }
    public Task create(String account,String operation,String fingerprint,Prepared prepared){return create(account,operation,fingerprint,prepared,null);}
    public Task create(String account,String operation,String fingerprint,Prepared prepared,Rule rule) { return database.write(c -> {
        try(var q=c.prepareStatement("SELECT id,fingerprint FROM upload_tasks WHERE account_id=? AND operation_id=?")){q.setString(1,account);q.setString(2,operation);try(var r=q.executeQuery()){if(r.next()){if(!fingerprint.equals(r.getString(2)))throw new IllegalArgumentException("同一创建操作的参数已变化");return task(c,account,r.getString(1));}}}
        String id=UUID.randomUUID().toString(); long stamp=System.currentTimeMillis();String targetKey=targetKey(prepared.target().post());
        sql(c,"INSERT INTO upload_tasks VALUES(?,?,?,?,?,'READY',0,NULL,?,?)",id,account,operation,fingerprint,encode(prepared),stamp,stamp);
        for(var group:prepared.groups()) {
            sql(c,"INSERT INTO upload_groups(task_id,number,media_type,mode,state) VALUES(?,?,?,?,'READY')",id,group.number(),group.mediaType(),group.mode());
            long gid;try(var q=c.createStatement();var r=q.executeQuery("SELECT last_insert_rowid()")){r.next();gid=r.getLong(1);} if(gid>Integer.MAX_VALUE)throw new IllegalArgumentException("上传组编号达到上限");
            for(int n=0;n<group.files().size();n++) { var member=group.files().get(n);var version=prepared.scan().files().stream().filter(f->f.relativePath().equals(member.relativePath())&&f.mediaType().equals(group.mediaType())).findFirst().orElseThrow();
                try(var q=c.prepareStatement("SELECT task_id FROM upload_claims WHERE account_id=? AND target_key=? AND media_type=? AND sha256=? AND size=?")){q.setString(1,account);q.setString(2,targetKey);q.setString(3,version.mediaType());q.setString(4,version.sha256());q.setLong(5,version.size());try(var r=q.executeQuery()){if(r.next())throw new IllegalArgumentException("该主帖下相同类型和内容已有上传任务，请先到任务中心核对；不会重复创建或发送");}}
                sql(c,"INSERT INTO upload_items(id,task_id,group_id,ordinal,file_json,caption,state) VALUES(?,?,?,?,?,?,'READY')",UUID.randomUUID().toString(),id,gid,n,encode(version),member.caption());
                sql(c,"INSERT INTO upload_claims VALUES(?,?,?,?,?,?)",account,targetKey,version.mediaType(),version.sha256(),version.size(),id);
            }
        }
        if(rule!=null)rules.bind(c,account,id,rule,prepared);return task(c,account,id);
    }); }
    public Task task(String account,String id){return database.read(c->task(c,account,id));}
    public List<Task> list(String account,int limit,int offset){return database.read(c->{var ids=new ArrayList<String>();try(var q=c.prepareStatement("SELECT id FROM upload_tasks WHERE account_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?")){q.setString(1,account);q.setInt(2,limit);q.setInt(3,offset);try(var r=q.executeQuery()){while(r.next())ids.add(r.getString(1));}}var out=new ArrayList<Task>();for(var id:ids)out.add(task(c,account,id));return List.copyOf(out);});}
    public Task start(String account,String id){return database.write(c->{var t=task(c,account,id);if(!List.of("READY","PAUSED").contains(t.state())||t.groups().stream().anyMatch(g->!List.of("READY","CONFIRMED").contains(g.state())))throw new IllegalArgumentException("发送结果尚未明确，请先核对，不能直接重发");rules.checkStart(c,t);rules.clearPause(c,t);sql(c,"UPDATE upload_tasks SET state='QUEUED',stop_requested=0,error=NULL,updated_at=? WHERE id=?",now(),id);return task(c,account,id);});}
    public Task pause(String account,String id){return database.write(c->{var t=task(c,account,id);if(!List.of("QUEUED","RUNNING").contains(t.state()))return t;rules.markPause(c,t,"USER");
        if(t.state().equals("QUEUED")){try(var q=c.prepareStatement("UPDATE upload_retry_attempts SET state='REVIEW',error='用户暂停，需重新核对后继续' WHERE task_id=? AND state='QUEUED'")){q.setString(1,id);if(q.executeUpdate()>0){sql(c,"UPDATE upload_tasks SET state='NEEDS_REVIEW',stop_requested=1,error='失败成员重试已暂停，请重新核对后继续',updated_at=? WHERE id=?",now(),id);return task(c,account,id);}}}
        sql(c,"UPDATE upload_tasks SET stop_requested=1,state=CASE WHEN state='RUNNING' THEN state ELSE 'PAUSED' END,updated_at=? WHERE id=?",now(),id);return task(c,account,id);});}
    /** Reconciliation and archive retry never grant permission to send another group. */
    public Task reconcile(String account,String id){return database.write(c->{var t=task(c,account,id);if(!List.of("NEEDS_REVIEW","SENT_PENDING_ARCHIVE","PAUSED").contains(t.state()))throw new IllegalArgumentException("当前任务无需核对或重试归档");sql(c,"UPDATE upload_tasks SET state='QUEUED',stop_requested=1,error=NULL,updated_at=? WHERE id=?",now(),id);return task(c,account,id);});}
    /** Explicit acceptance of a delivered single GIF as a document. The frozen plan and native receipt stay intact. */
    public Task acceptDocument(String account,String id,long groupId){return database.write(c->{
        var t=task(c,account,id);var g=t.groups().stream().filter(x->x.id()==groupId).findFirst().orElseThrow(()->new IllegalArgumentException("上传组不属于当前任务"));
        var item=g.items().size()==1?g.items().getFirst():null;
        if(item!=null&&item.formalId()!=null&&event(c,g.id(),"accept-document:"+item.temporaryId())!=null)return t;
        var receipt=documentFallback(c,t,g);if(!List.of("NEEDS_REVIEW","PAUSED").contains(t.state())||receipt==null)throw new IllegalArgumentException("仅能确认已正式送达为文档的单条GIF；其他不确定结果仍需核对");
        storeEvent(c,g.id(),acceptance(receipt));apply(c,account,id,g.id());
        sql(c,"UPDATE upload_tasks SET state='QUEUED',stop_requested=1,error=NULL,updated_at=? WHERE id=?",now(),id);return task(c,account,id);
    });}
    public Task cancel(String account,String id){return database.write(c->{var t=task(c,account,id);if(!List.of("READY","PAUSED").contains(t.state())||t.groups().stream().anyMatch(g->!g.state().equals("READY")))throw new IllegalArgumentException("仅能取消尚未发起发送的任务，已有发送意图必须先核对");sql(c,"UPDATE upload_tasks SET state='CANCELLED',stop_requested=1,updated_at=? WHERE id=?",now(),id);sql(c,"DELETE FROM upload_claims WHERE task_id=?",id);return task(c,account,id);});}
    public Task next(String account){return database.write(c->{String id=null;try(var q=c.prepareStatement("SELECT id FROM upload_tasks WHERE account_id=? AND state='QUEUED' ORDER BY created_at LIMIT 1")){q.setString(1,account);try(var r=q.executeQuery()){if(r.next())id=r.getString(1);}}if(id==null)return null;sql(c,"UPDATE upload_tasks SET state='RUNNING',updated_at=? WHERE id=?",now(),id);return task(c,account,id);});}
    public void state(Task task,String state,String error){database.write(c->{sql(c,"UPDATE upload_tasks SET state=?,error=?,updated_at=? WHERE id=? AND account_id=?",state,error,now(),task.id(),task.accountId());return null;});}
    public void preparing(Task task,Group group){database.write(c->{sql(c,"UPDATE upload_groups SET state='PREPARING' WHERE id=? AND task_id=? AND state='READY'",group.id(),task.id());return null;});}
    public void staged(Task task,Item item,Staged staged){database.write(c->{sql(c,"UPDATE upload_items SET staging_json=?,state='STAGED',error=NULL WHERE id=? AND task_id=? AND formal_id IS NULL",encode(staged),item.id(),task.id());return null;});}
    public void intent(Task task,Group group,String run){database.write(c->{var t=task(c,task.accountId(),task.id());var fresh=t.groups().stream().filter(g->g.id()==group.id()).findFirst().orElseThrow();if(t.stopRequested()||!fresh.state().equals("PREPARING")||fresh.items().stream().anyMatch(i->i.staged()==null))throw new IllegalArgumentException("上传已暂停或文件尚未准备好");rules.checkStart(c,t);sql(c,"UPDATE upload_groups SET state='INTENT',owner_run=?,started_at=? WHERE id=?",run,now(),group.id());return null;});}
    public void checkRuleStart(Connection c,Task task)throws SQLException{rules.checkStart(c,task);}
    public void pauseByRule(Task task,RuleUploadPolicy.Blocked blocked){database.write(c->{rules.markPause(c,task,blocked.kind());sql(c,"UPDATE upload_tasks SET state='PAUSED',stop_requested=1,error=?,updated_at=? WHERE id=? AND account_id=?",blocked.getMessage(),now(),task.id(),task.accountId());return null;});}
    public void markAutomaticRestart(Task task){database.write(c->{rules.markPause(c,task,"RESTART_READY");return null;});}
    public Task takeover(String account,String id){rules.takeover(account,id);return task(account,id);}
    public static String targetKey(Post post){return post.key()+"\n"+post.discussionChatId()+"\n"+post.discussionRootMessageId();}
    public static String contentKey(FileVersion file){return file.mediaType()+"\n"+file.sha256()+"\n"+file.size();}
    public Set<String> claimed(String account,Post target,List<FileVersion> candidates){return database.read(c->{var out=new HashSet<String>();try(var q=c.prepareStatement("SELECT 1 FROM upload_claims WHERE account_id=? AND target_key=? AND media_type=? AND sha256=? AND size=?")){for(var file:candidates){q.setString(1,account);q.setString(2,targetKey(target));q.setString(3,file.mediaType());q.setString(4,file.sha256());q.setLong(5,file.size());try(var r=q.executeQuery()){if(r.next())out.add(contentKey(file));}}}return Set.copyOf(out);});}
    public void initial(Task task,Group group,List<Initial> initial){database.write(c->{var t=task(c,task.accountId(),task.id());var g=t.groups().stream().filter(x->x.id()==group.id()).findFirst().orElseThrow();
        if(initial.size()!=g.items().size()||initial.stream().map(Initial::id).distinct().count()!=initial.size())throw new IllegalArgumentException("临时消息数量或身份不符，需核对发送结果");
        for(int i=0;i<initial.size();i++){var response=initial.get(i);var item=g.items().get(i);if(response.id()==null||response.id().equals("0")||response.pending()&&response.sendingId()!=g.id())throw new IllegalArgumentException("临时发送回执无法关联到当前组");sql(c,"UPDATE upload_items SET temporary_id=?,state='PENDING' WHERE id=? AND formal_id IS NULL",response.id(),item.id());if(!response.pending())storeEvent(c,g.id(),response.evidence());}
        sql(c,"UPDATE upload_groups SET state='SENDING' WHERE id=?",g.id());apply(c,task.accountId(),task.id(),g.id());return null;
    });}
    public void dispatch(Task task,Group group,String run){database.write(c->{try(var q=c.prepareStatement("UPDATE upload_groups SET state='DISPATCHING' WHERE id=? AND task_id=? AND state='INTENT' AND owner_run=?")){q.setLong(1,group.id());q.setString(2,task.id());q.setString(3,run);if(q.executeUpdate()!=1)throw new IllegalArgumentException("此发送意图已使用或需要核对，不能重发");}return null;});}
    public void event(long groupId,Evidence evidence){database.write(c->{storeEvent(c,groupId,evidence);try(var q=c.prepareStatement("SELECT t.account_id,t.id FROM upload_groups g JOIN upload_tasks t ON t.id=g.task_id WHERE g.id=?")){q.setLong(1,groupId);try(var r=q.executeQuery()){if(r.next())apply(c,r.getString(1),r.getString(2),groupId);}}return null;});}
    public Long pendingGroup(String account,String run,String chat,String oldId){return database.read(c->{try(var q=c.prepareStatement("SELECT g.id,t.prepared_json FROM upload_items i JOIN upload_groups g ON g.id=i.group_id JOIN upload_tasks t ON t.id=i.task_id WHERE t.account_id=? AND i.temporary_id=? AND (g.owner_run=? OR EXISTS(SELECT 1 FROM upload_retry_attempts a WHERE a.item_id=i.id AND a.new_temporary_id=i.temporary_id AND a.owner_run=?))")){q.setString(1,account);q.setString(2,oldId);q.setString(3,run);q.setString(4,run);try(var r=q.executeQuery()){while(r.next())if(chat.equals(decode(r.getString(2),Prepared.class).target().post().discussionChatId()))return r.getLong(1);return null;}}});}
    public Evidence receipt(long group,String old){return database.read(c->event(c,group,old));}
    private boolean retried(Connection c,Item item)throws SQLException{try(var q=c.prepareStatement("SELECT 1 FROM upload_retry_attempts WHERE item_id=? AND new_temporary_id=? LIMIT 1")){q.setString(1,item.id());q.setString(2,item.temporaryId());try(var r=q.executeQuery()){return r.next();}}}
    private boolean hasRetries(long group){return database.read(c->{try(var q=c.prepareStatement("SELECT 1 FROM upload_retry_attempts WHERE group_id=? LIMIT 1")){q.setLong(1,group);try(var r=q.executeQuery()){return r.next();}}});}
    public void reapply(Task task,Group group){database.write(c->{apply(c,task.accountId(),task.id(),group.id());return null;});}
    public void uncertain(Group group,String error){database.write(c->{sql(c,"UPDATE upload_groups SET state=CASE WHEN state='CONFIRMED' THEN state ELSE 'UNCERTAIN' END,error=COALESCE(error,?) WHERE id=?",error,group.id());return null;});}
    public void unprepared(Group group){database.write(c->{sql(c,"UPDATE upload_groups SET state='READY' WHERE id=? AND state='PREPARING'",group.id());return null;});}
    public void archivePlan(Task task,Item item,String relative,String phase,String identity){database.write(c->{sql(c,"UPDATE upload_items SET archive_relative=?,archive_phase=?,archive_identity=?,error=NULL WHERE id=? AND task_id=? AND formal_id IS NOT NULL",relative,phase,identity,item.id(),task.id());return null;});}
    public void archived(Task task,Item item){database.write(c->{sql(c,"UPDATE upload_items SET state='ARCHIVED',archive_phase='COMPLETED',error=NULL WHERE id=? AND task_id=? AND formal_id IS NOT NULL",item.id(),task.id());return null;});}
    public void itemError(Item item,String error){database.write(c->{sql(c,"UPDATE upload_items SET error=? WHERE id=?",error,item.id());return null;});}
    private void storeEvent(Connection c,long gid,Evidence evidence)throws SQLException{
        if(evidence==null||evidence.oldId()==null)throw new IllegalArgumentException("发送证据无效");
        try(var q=c.prepareStatement("SELECT event_json FROM upload_events WHERE group_id=? AND old_id=?")){q.setLong(1,gid);q.setString(2,evidence.oldId());try(var r=q.executeQuery()){if(r.next()){if(!r.getString(1).equals(encode(evidence)))throw new IllegalArgumentException("收到互相矛盾的发送证据，需要人工核对");return;}}}
        try(var q=c.prepareStatement("SELECT count(*) FROM upload_events WHERE group_id=?")){q.setLong(1,gid);try(var r=q.executeQuery()){r.next();if(r.getInt(1)>=128)throw new IllegalArgumentException("发送证据超过核对上限");}}
        sql(c,"INSERT INTO upload_events VALUES(?,?,?)",gid,evidence.oldId(),encode(evidence));
    }
    private void apply(Connection c,String account,String taskId,long gid)throws SQLException{
        var t=task(c,account,taskId);var g=t.groups().stream().filter(x->x.id()==gid).findFirst().orElseThrow();var post=t.prepared().target().post();var evidence=new HashMap<String,Evidence>();
        try(var q=c.prepareStatement("SELECT old_id,event_json FROM upload_events WHERE group_id=?")){q.setLong(1,gid);try(var r=q.executeQuery()){while(r.next())evidence.put(r.getString(1),decode(r.getString(2),Evidence.class));}}
        boolean invalid=false,acceptedDocument=false;String invalidReason="正式回执与目标、类型或相册不符，请核对";var albums=new HashSet<String>();int confirmed=0;
        for(var item:g.items()) {var e=evidence.get(item.temporaryId());if(e==null){if(item.formalId()!=null)confirmed++;continue;}
            boolean identity=identity(t,item,e);
            boolean accepted=singleGifDocument(g,item,e)&&acceptance(e).equals(evidence.get("accept-document:"+item.temporaryId()));
            if(identity&&!item.file().mediaType().equals(e.mediaType())&&!accepted){
                invalidReason="文件已送达，但返回类型为"+typeLabel(e.mediaType())+"，预期为"+typeLabel(item.file().mediaType())+"；保留原件，请核对，不会重新发送";
                sql(c,"UPDATE upload_items SET error=? WHERE id=?",invalidReason+"（收到消息 "+e.formalId()+"）",item.id());
            }
            boolean proof=identity&&(item.file().mediaType().equals(e.mediaType())||accepted);
            if(!proof||item.formalId()!=null&&!item.formalId().equals(e.formalId())){invalid=true;if(!e.success()&&item.formalId()==null){sql(c,"UPDATE upload_items SET state='FAILED',error='Telegram报告发送失败，请核对后仅重试失败成员' WHERE id=?",item.id());invalidReason="存在发送失败或结果不明的成员，请核对并继续；已成功成员不会重发";}continue;}
            if(g.mode().equals("ALBUM")&&!retried(c,item)){if(e.albumId()==null||e.albumId().equals("0")){invalid=true;continue;}albums.add(e.albumId());}
            sql(c,"UPDATE upload_items SET formal_id=?,remote_unique_id=?,state=CASE WHEN state='ARCHIVED' THEN state ELSE 'SENT' END,error=? WHERE id=?",e.formalId(),e.uniqueId(),accepted?"已明确按文档确认送达；原计划动图保持不变":null,item.id());confirmed++;acceptedDocument|=accepted;
        }
        if(!invalid&&confirmed==g.items().size()&&albums.size()<=1)sql(c,"UPDATE upload_groups SET state='CONFIRMED',error=? WHERE id=?",acceptedDocument?"已明确接受GIF按文档送达；仅归档，不重新发送":null,gid);
        else if(invalid||albums.size()>1)sql(c,"UPDATE upload_groups SET state='UNCERTAIN',error=? WHERE id=?",invalidReason,gid);
        sql(c,"UPDATE upload_tasks SET updated_at=? WHERE id=?",now(),taskId);
    }
    private static boolean identity(Task task,Item item,Evidence e){var post=task.prepared().target().post();return e!=null&&e.success()&&e.outgoing()&&e.date()>0&&e.formalId()!=null&&e.formalId().matches("[1-9][0-9]*")&&post.discussionChatId().equals(e.chatId())&&item.caption().equals(e.caption())&&(post.discussionRootMessageId().equals(e.threadId())||post.discussionRootMessageId().equals(e.replyId()))&&(e.threadId()==null||post.discussionRootMessageId().equals(e.threadId()))&&(e.replyId()==null||post.discussionRootMessageId().equals(e.replyId()));}
    private static boolean singleGifDocument(Group g,Item item,Evidence e){return g.mode().equals("SINGLE")&&g.items().size()==1&&g.mediaType().equals("ANIMATION")&&item.file().mediaType().equals("ANIMATION")&&item.file().relativePath().toLowerCase(Locale.ROOT).endsWith(".gif")&&e!=null&&"DOCUMENT".equals(e.mediaType())&&"0".equals(e.albumId())&&e.uniqueId()!=null&&!e.uniqueId().isBlank();}
    private static Evidence acceptance(Evidence e){return new Evidence("accept-document:"+e.oldId(),e.formalId(),e.chatId(),e.threadId(),e.replyId(),e.albumId(),e.mediaType(),e.caption(),e.uniqueId(),e.outgoing(),e.success(),e.date());}
    private Evidence event(Connection c,long groupId,String oldId)throws SQLException{try(var q=c.prepareStatement("SELECT event_json FROM upload_events WHERE group_id=? AND old_id=?")){q.setLong(1,groupId);q.setString(2,oldId);try(var r=q.executeQuery()){return r.next()?decode(r.getString(1),Evidence.class):null;}}}
    private Evidence documentFallback(Connection c,Task t,Group g)throws SQLException{if(!g.state().equals("UNCERTAIN")||g.items().size()!=1)return null;var i=g.items().getFirst();if(i.formalId()!=null||i.temporaryId()==null)return null;var e=event(c,g.id(),i.temporaryId());return identity(t,i,e)&&singleGifDocument(g,i,e)?e:null;}
    private static String typeLabel(String type){return switch(type==null?"":type){case "PHOTO"->"图片";case "VIDEO"->"视频";case "AUDIO"->"音频";case "DOCUMENT"->"文档";case "ANIMATION"->"动图";default->"未识别媒体";};}
    public View view(Task task){var acceptable=database.read(c->{var ids=new HashSet<Long>();if(List.of("NEEDS_REVIEW","PAUSED").contains(task.state()))for(var g:task.groups())if(documentFallback(c,task,g)!=null)ids.add(g.id());return ids;});var groups=task.groups().stream().map(g->new GroupView(Long.toString(g.id()),g.number(),g.mediaType(),g.state(),g.error(),acceptable.contains(g.id()),hasRetries(g.id()),g.items().stream().map(i->new ItemView(i.id(),Path.of(i.file().relativePath()).getFileName().toString(),i.file().mediaType(),i.file().size(),i.state(),i.formalId(),i.archiveRelative()==null?null:Path.of(task.prepared().scan().archiveDirectory()).resolve(i.archiveRelative()).toString(),i.error())).toList())).toList();var items=task.groups().stream().flatMap(g->g.items().stream()).toList();var p=task.prepared();return new View(task.id(),task.state(),p.target().post().channelTitle(),p.target().post().title(),p.input().link(),p.target().discussionTitle(),p.scan().sourceDirectory(),p.scan().archiveDirectory(),task.stopRequested(),items.size(),items.stream().filter(i->i.formalId()!=null).count(),items.stream().filter(i->i.state().equals("ARCHIVED")).count(),task.error(),task.createdAt(),task.updatedAt(),groups,rules.info(task));}
    private Task task(Connection c,String account,String id)throws SQLException {try(var q=c.prepareStatement("SELECT * FROM upload_tasks WHERE id=? AND account_id=?")){q.setString(1,id);q.setString(2,account);try(var r=q.executeQuery()){if(!r.next())throw new IllegalArgumentException("当前账号没有此上传任务");var groups=new ArrayList<Group>();try(var gq=c.prepareStatement("SELECT * FROM upload_groups WHERE task_id=? ORDER BY number")){gq.setString(1,id);try(var gr=gq.executeQuery()){while(gr.next()){var items=new ArrayList<Item>();try(var iq=c.prepareStatement("SELECT * FROM upload_items WHERE group_id=? ORDER BY ordinal")){iq.setLong(1,gr.getLong("id"));try(var ir=iq.executeQuery()){while(ir.next())items.add(new Item(ir.getString("id"),ir.getInt("ordinal"),decode(ir.getString("file_json"),FileVersion.class),ir.getString("caption"),ir.getString("state"),ir.getString("temporary_id"),ir.getString("formal_id"),ir.getString("remote_unique_id"),ir.getString("staging_json")==null?null:decode(ir.getString("staging_json"),Staged.class),ir.getString("archive_relative"),ir.getString("archive_phase"),ir.getString("archive_identity"),ir.getString("error")));}}groups.add(new Group(gr.getLong("id"),gr.getInt("number"),gr.getString("media_type"),gr.getString("mode"),gr.getString("state"),gr.getString("owner_run"),gr.getObject("started_at")==null?null:gr.getLong("started_at"),gr.getString("error"),items));}}}return new Task(id,account,r.getString("operation_id"),r.getString("fingerprint"),decode(r.getString("prepared_json"),Prepared.class),r.getString("state"),r.getBoolean("stop_requested"),r.getString("error"),r.getLong("created_at"),r.getLong("updated_at"),groups);}}}
    private String encode(Object value){return json.writeValueAsString(value);}private <T>T decode(String value,Class<T> type){return json.readValue(value,type);}private static long now(){return System.currentTimeMillis();}
    private static void sql(Connection c,String text,Object...values)throws SQLException{try(var q=c.prepareStatement(text)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);q.executeUpdate();}}
}
