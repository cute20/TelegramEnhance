package io.github.telegramenhance.transfer;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import io.github.telegramenhance.transfer.UploadRetryModels.*;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Append-only native failures and one durable, non-replayable intent for each explicitly retried member. */
@Repository
public final class UploadRetryRepository {
    private final Database db;private final UploadRepository uploads;private final JsonMapper json=JsonMapper.builder().build();
    public UploadRetryRepository(Database db,UploadRepository uploads){this.db=db;this.uploads=uploads;db.write(c->{try(var q=c.createStatement()){q.executeUpdate("UPDATE upload_retry_attempts SET state='REVIEW',error='应用已重启，请重新核对原失败消息，不自动重试' WHERE state IN ('QUEUED','DISPATCHING','WAITING')");}return null;});}
    public Failure failure(long group,String old){return db.read(c->{try(var q=c.prepareStatement("SELECT failure_json FROM upload_send_failures WHERE group_id=? AND old_id=?")){q.setLong(1,group);q.setString(2,old);try(var r=q.executeQuery()){return r.next()?json.readValue(r.getString(1),Failure.class):null;}}});}
    public void failure(long group,Failure proof){if(proof==null||proof.evidence()==null)return;db.write(c->{String encoded=json.writeValueAsString(proof);try(var q=c.prepareStatement("SELECT failure_json FROM upload_send_failures WHERE group_id=? AND old_id=?")){q.setLong(1,group);q.setString(2,proof.evidence().oldId());try(var r=q.executeQuery()){if(r.next()){if(!encoded.equals(r.getString(1)))throw new IllegalArgumentException("失败消息证据冲突，请人工核对");return null;}}}try(var q=c.prepareStatement("SELECT COUNT(*) FROM upload_send_failures WHERE group_id=?")){q.setLong(1,group);try(var r=q.executeQuery()){r.next();if(r.getInt(1)>=128)throw new IllegalArgumentException("失败记录达到本阶段上限，请人工核对");}}sql(c,"INSERT INTO upload_send_failures VALUES(?,?,?,?)",group,proof.evidence().oldId(),proof.failedId(),encoded);return null;});}
    public boolean operation(String account,String task,String operation,String check){uploads.task(account,task);return db.read(c->{try(var q=c.prepareStatement("SELECT check_id FROM upload_retry_attempts WHERE task_id=? AND operation_id=? LIMIT 1")){q.setString(1,task);q.setString(2,operation);try(var r=q.executeQuery()){if(!r.next())return false;if(check!=null&&!check.equals(r.getString(1)))throw new IllegalArgumentException("同一重试操作的核对清单已变化");return true;}}});}
    public synchronized Task enqueue(String account,String taskId,String operation,String checkId,long groupId,List<Candidate> candidates,String run){
        return db.write(c->{
            var task=uploads.task(account,taskId);if(operation(account,taskId,operation,checkId))return task;
            if(!task.state().equals("NEEDS_REVIEW")||candidates.isEmpty()||candidates.size()>10)throw new IllegalArgumentException("请先核对待处理任务的失败成员");
            uploads.checkRuleStart(c,task);
            var group=task.groups().stream().filter(g->g.id()==groupId).findFirst().orElseThrow();
            try(var q=c.prepareStatement("SELECT COUNT(*) FROM upload_retry_attempts WHERE group_id=?")){q.setLong(1,groupId);try(var r=q.executeQuery()){r.next();if(r.getInt(1)+candidates.size()>100)throw new IllegalArgumentException("本组重试记录达到上限，请人工核对");}}
            try(var q=c.prepareStatement("SELECT 1 FROM upload_retry_attempts WHERE task_id=? AND state IN ('QUEUED','DISPATCHING','WAITING') LIMIT 1")){q.setString(1,taskId);try(var r=q.executeQuery()){if(r.next())throw new IllegalArgumentException("已有失败项重试正在处理，请先刷新核对");}}
            var seen=new HashSet<String>();for(var candidate:candidates){
                var item=group.items().stream().filter(i->i.id().equals(candidate.itemId())).findFirst().orElseThrow();
                if(!seen.add(item.id())||item.formalId()!=null||!Objects.equals(item.temporaryId(),candidate.temporaryId())||!Objects.equals(item.staged(),candidate.staged()))throw new IllegalArgumentException("文件或发送结果已变化，请重新核对");
                long now=System.currentTimeMillis();sql(c,"INSERT INTO upload_retry_attempts VALUES(?,?,?,?,?,?,?,?,NULL,?,'QUEUED',?,?,NULL)",UUID.randomUUID().toString(),taskId,groupId,item.id(),operation,checkId,item.temporaryId(),candidate.failedId(),run,now,now);
            }
            sql(c,"UPDATE upload_tasks SET state='QUEUED',stop_requested=1,error='仅重试已核实失败成员；后续未发送组保持暂停',updated_at=? WHERE id=?",System.currentTimeMillis(),taskId);return uploads.task(account,taskId);
        });
    }
    public List<Attempt> queued(String task){return db.read(c->{var out=new ArrayList<Attempt>();try(var q=c.prepareStatement("SELECT * FROM upload_retry_attempts WHERE task_id=? AND state='QUEUED' ORDER BY created_at,id LIMIT 10")){q.setString(1,task);try(var r=q.executeQuery()){while(r.next())out.add(read(r));}}return List.copyOf(out);});}
    public void dispatch(Attempt a,String run){db.write(c->{try(var lookup=c.prepareStatement("SELECT account_id FROM upload_tasks WHERE id=?")){lookup.setString(1,a.taskId());try(var r=lookup.executeQuery()){if(!r.next())throw new IllegalArgumentException("任务不存在");uploads.checkRuleStart(c,uploads.task(r.getString(1),a.taskId()));}}try(var q=c.prepareStatement("UPDATE upload_retry_attempts SET state='DISPATCHING',updated_at=? WHERE id=? AND state='QUEUED' AND owner_run=?")){q.setLong(1,System.currentTimeMillis());q.setString(2,a.id());q.setString(3,run);if(q.executeUpdate()!=1)throw new IllegalArgumentException("重试意图已使用或会话变化，请重新核对，不能直接重发");}return null;});}
    public void bind(Attempt a,String run,String newId){db.write(c->{try(var q=c.prepareStatement("UPDATE upload_retry_attempts SET state='WAITING',new_temporary_id=?,updated_at=? WHERE id=? AND state='DISPATCHING' AND owner_run=?")){q.setString(1,newId);q.setLong(2,System.currentTimeMillis());q.setString(3,a.id());q.setString(4,run);if(q.executeUpdate()!=1)throw new IllegalArgumentException("重试初始消息无法关联，请核对");}try(var q=c.prepareStatement("UPDATE upload_items SET temporary_id=?,state='PENDING',error=NULL WHERE id=? AND task_id=? AND formal_id IS NULL AND temporary_id=?")){q.setString(1,newId);q.setString(2,a.itemId());q.setString(3,a.taskId());q.setString(4,a.priorTemporaryId());if(q.executeUpdate()!=1)throw new IllegalArgumentException("原成员结果发生变化，保留新旧证据待核对");}return null;});}
    public void state(Attempt a,String state,String error){db.write(c->{sql(c,"UPDATE upload_retry_attempts SET state=?,error=?,updated_at=? WHERE id=?",state,error,System.currentTimeMillis(),a.id());return null;});}
    public void reviewQueued(String task){db.write(c->{sql(c,"UPDATE upload_retry_attempts SET state='REVIEW',error='本轮停止，需重新核对失败消息' WHERE task_id=? AND state IN ('QUEUED','DISPATCHING','WAITING')",task);return null;});}
    private static Attempt read(ResultSet r)throws SQLException{return new Attempt(r.getString("id"),r.getString("task_id"),r.getLong("group_id"),r.getString("item_id"),r.getString("operation_id"),r.getString("check_id"),r.getString("prior_temporary_id"),r.getString("failed_id"),r.getString("new_temporary_id"),r.getString("owner_run"),r.getString("state"));}
    private static void sql(Connection c,String sql,Object...args)throws SQLException{try(var q=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
}
