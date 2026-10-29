package io.github.telegramenhance.rules;

import io.github.telegramenhance.rules.RuleModels.*;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.UploadPreviewModels.Target;
import io.github.telegramenhance.transfer.UploadPreviewService.Prepared;
import io.github.telegramenhance.transfer.UploadTaskModels.Task;
import java.sql.*;
import java.time.Clock;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Frozen rule execution constraints. Manual tasks without a binding retain their existing behavior. */
@Component
public final class RuleUploadPolicy {
    private final Database db;private final Clock clock;private final JsonMapper json=JsonMapper.builder().build();
    @org.springframework.beans.factory.annotation.Autowired public RuleUploadPolicy(Database db){this(db,Clock.systemUTC());}
    public RuleUploadPolicy(Database db,Clock clock){this.db=db;this.clock=clock;}
    public record Info(String ruleId,String name,long version,boolean manualTakeover,String windowMode,String start,String end,String timezone,String status,String reason,long nextAllowedAt){}
    public static final class Blocked extends IllegalArgumentException {
        private final String kind;public Blocked(String kind,String message){super(message);this.kind=kind;}public String kind(){return kind;}
    }
    public void bind(Connection c,String account,String task,Rule expected,Prepared prepared)throws SQLException{
        try(var q=c.prepareStatement("SELECT r.enabled,r.current_version,r.revision,v.config_json,a.target_json FROM auto_rules r JOIN auto_rule_versions v ON v.rule_id=r.id AND v.version=r.current_version LEFT JOIN auto_rule_authorizations a ON a.rule_id=r.id AND a.revision=r.revision WHERE r.account_id=? AND r.id=?")){
            q.setString(1,account);q.setString(2,expected.id());try(var r=q.executeQuery()){
                if(!r.next()||!r.getBoolean(1)||r.getLong(2)!=expected.version()||r.getLong(3)!=expected.revision()||!expected.input().equals(json.readValue(r.getString(4),Input.class))||!expected.input().executionMode().equals("AUTO_UPLOAD")||r.getString(5)==null||!sameTarget(json.readValue(r.getString(5),Target.class),prepared.target()))throw new Blocked("RULE","规则或启用时核对的目标已变化，未创建自动任务");
            }
        }
        if(!expected.input().window().allows(clock.instant()))throw new Blocked("WINDOW","当前不在此规则的自动上传时段内");
        settle(c,expected.id());
        sql(c,"INSERT INTO auto_upload_bindings VALUES(?,?,?,?,?,NULL,NULL,NULL,?)",task,expected.id(),account,expected.version(),json.writeValueAsString(expected.input()),clock.millis());
    }
    public static boolean sameTarget(Target a,Target b){return a.post().accountId().equals(b.post().accountId())&&a.post().key().equals(b.post().key())&&a.post().channelId().equals(b.post().channelId())&&java.util.Objects.equals(a.post().discussionChatId(),b.post().discussionChatId())&&java.util.Objects.equals(a.post().discussionRootMessageId(),b.post().discussionRootMessageId());}
    public void checkStart(Connection c,Task task)throws SQLException{
        try(var q=c.prepareStatement("SELECT b.rule_id,b.rule_version,b.snapshot_json,b.manual_takeover_at,r.enabled,r.current_version,a.target_json FROM auto_upload_bindings b JOIN auto_rules r ON r.id=b.rule_id LEFT JOIN auto_rule_authorizations a ON a.rule_id=r.id AND a.revision=r.revision WHERE b.task_id=? AND b.account_id=?")){
            q.setString(1,task.id());q.setString(2,task.accountId());try(var r=q.executeQuery()){
                if(!r.next()||r.getObject(4)!=null)return;
                if(!r.getBoolean(5))throw new Blocked("RULE","所属自动规则已暂停；可重新启用规则或转为手动处理");
                if(r.getLong(2)!=r.getLong(6))throw new Blocked("RULE","所属规则已有新版本，原任务参数保留；请处理旧任务或转为手动处理");
                if(r.getString(7)==null||!sameTarget(json.readValue(r.getString(7),Target.class),task.prepared().target()))throw new Blocked("RULE","规则当前核对的目标与此任务不符，请处理旧任务");
                var input=json.readValue(r.getString(3),Input.class);if(!input.window().allows(clock.instant()))throw new Blocked("WINDOW","等待自动上传时段（北京时间 "+input.window().start()+"—"+input.window().end()+"）；本轮未启动新发送");
            }
        }
    }
    public void markPause(Connection c,Task task,String kind)throws SQLException{sql(c,"UPDATE auto_upload_bindings SET pause_kind=? WHERE task_id=? AND manual_takeover_at IS NULL",kind,task.id());}
    public void clearPause(Connection c,Task task)throws SQLException{sql(c,"UPDATE auto_upload_bindings SET pause_kind=NULL WHERE task_id=?",task.id());}
    public void markRestartReady(Connection c)throws SQLException{
        sql(c,"UPDATE auto_upload_bindings SET pause_kind='RESTART_READY' WHERE manual_takeover_at IS NULL AND task_id IN(SELECT t.id FROM upload_tasks t WHERE t.state IN ('QUEUED','RUNNING') AND NOT EXISTS(SELECT 1 FROM upload_groups g WHERE g.task_id=t.id AND g.state<>'READY'))");
    }
    public String pauseKind(String account,String task){return db.read(c->{try(var q=c.prepareStatement("SELECT pause_kind FROM auto_upload_bindings WHERE task_id=? AND account_id=? AND manual_takeover_at IS NULL")){q.setString(1,task);q.setString(2,account);try(var r=q.executeQuery()){return r.next()?r.getString(1):null;}}});}
    public String activeTask(String account,String rule){return db.read(c->{try(var q=c.prepareStatement("SELECT b.task_id FROM auto_upload_bindings b JOIN upload_tasks t ON t.id=b.task_id WHERE b.rule_id=? AND b.account_id=? AND b.manual_takeover_at IS NULL AND b.settled_at IS NULL AND t.state NOT IN ('COMPLETED','CANCELLED') LIMIT 1")){q.setString(1,rule);q.setString(2,account);try(var r=q.executeQuery()){return r.next()?r.getString(1):null;}}});}
    public Target authorization(Rule rule){return db.read(c->{try(var q=c.prepareStatement("SELECT target_json FROM auto_rule_authorizations WHERE rule_id=? AND version=? AND revision=?")){q.setString(1,rule.id());q.setLong(2,rule.version());q.setLong(3,rule.revision());try(var r=q.executeQuery()){if(!r.next())throw new Blocked("RULE","请重新预览并启用自动规则");return json.readValue(r.getString(1),Target.class);}}});}
    public Job latestJob(String account,String rule){return db.read(c->{try(var q=c.prepareStatement("SELECT b.task_id,b.rule_version,t.state,b.manual_takeover_at,t.error,COUNT(i.id) AS total,SUM(i.formal_id IS NOT NULL) AS confirmed,SUM(i.state='ARCHIVED') AS archived FROM auto_upload_bindings b JOIN upload_tasks t ON t.id=b.task_id JOIN upload_items i ON i.task_id=t.id WHERE b.rule_id=? AND b.account_id=? GROUP BY b.task_id ORDER BY b.created_at DESC,b.task_id DESC LIMIT 1")){q.setString(1,rule);q.setString(2,account);try(var r=q.executeQuery()){return r.next()?new Job(r.getString(1),r.getLong(2),r.getString(3),r.getInt("total"),r.getLong("confirmed"),r.getLong("archived"),r.getObject(4)!=null,r.getString(5)):null;}}});}
    public Info info(Task task){return db.read(c->{try(var q=c.prepareStatement("SELECT rule_id,rule_version,snapshot_json,manual_takeover_at FROM auto_upload_bindings WHERE task_id=? AND account_id=?")){q.setString(1,task.id());q.setString(2,task.accountId());try(var r=q.executeQuery()){if(!r.next())return null;var input=json.readValue(r.getString(3),Input.class);boolean manual=r.getObject(4)!=null;String status=manual?"MANUAL":"AUTOMATIC",reason=manual?"已明确转为手动处理，不受规则时段和启停控制":"自动任务遵守保存的规则时段和启停状态";if(!manual&&!java.util.List.of("COMPLETED","CANCELLED").contains(task.state()))try{checkStart(c,task);}catch(Blocked blocked){status=blocked.kind().equals("WINDOW")?"WAITING_WINDOW":"RULE_PAUSED";reason=blocked.getMessage();}return new Info(r.getString(1),input.name(),r.getLong(2),manual,input.window().mode(),input.window().start(),input.window().end(),"Asia/Shanghai",status,reason,input.window().nextAllowed(clock.instant()));}}});}
    public void takeover(String account,String task){db.write(c->{try(var q=c.prepareStatement("SELECT t.state FROM auto_upload_bindings b JOIN upload_tasks t ON t.id=b.task_id WHERE b.account_id=? AND b.task_id=?")){q.setString(1,account);q.setString(2,task);try(var r=q.executeQuery()){if(!r.next()||java.util.List.of("COMPLETED","CANCELLED","RUNNING").contains(r.getString(1)))throw new IllegalArgumentException("此任务无需手动接管");}}sql(c,"UPDATE auto_upload_bindings SET manual_takeover_at=COALESCE(manual_takeover_at,?),pause_kind='USER' WHERE task_id=? AND account_id=?",clock.millis(),task,account);sql(c,"UPDATE upload_retry_attempts SET state='REVIEW',error='已转为手动处理，请重新核对' WHERE task_id=? AND state='QUEUED'",task);sql(c,"UPDATE upload_tasks SET stop_requested=1,state=CASE WHEN state='QUEUED' THEN CASE WHEN EXISTS(SELECT 1 FROM upload_groups g WHERE g.task_id=upload_tasks.id AND g.state NOT IN ('READY','CONFIRMED')) THEN 'NEEDS_REVIEW' ELSE 'PAUSED' END ELSE state END,error='已转为手动处理；未发起新发送，需手动确认开始',updated_at=? WHERE id=? AND account_id=?",clock.millis(),task,account);return null;});}
    private void settle(Connection c,String rule)throws SQLException{sql(c,"UPDATE auto_upload_bindings SET settled_at=? WHERE rule_id=? AND settled_at IS NULL AND task_id IN(SELECT id FROM upload_tasks WHERE state IN ('COMPLETED','CANCELLED'))",clock.millis(),rule);}
    private static void sql(Connection c,String sql,Object...args)throws SQLException{try(var q=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
}
