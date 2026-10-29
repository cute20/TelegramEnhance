package io.github.telegramenhance.rules;

import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.rules.RuleModels.*;
import io.github.telegramenhance.transfer.UploadPreviewModels.Scan;
import io.github.telegramenhance.transfer.UploadPreviewModels.Target;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public final class RuleRepository {
    private final Database db;private final JsonMapper json=JsonMapper.builder().build();private final RuleUploadPolicy uploadPolicy;
    public RuleRepository(Database db){this.db=db;this.uploadPolicy=new RuleUploadPolicy(db);}
    public Job latestJob(String account,String rule){return uploadPolicy.latestJob(account,rule);}
    public Rule create(String account,String operation,Input input,long now){return db.write(c->{
        String fingerprint=json.writeValueAsString(input);try(var q=c.prepareStatement("SELECT id,create_fingerprint FROM auto_rules WHERE account_id=? AND create_operation=?")){q.setString(1,account);q.setString(2,operation);try(var r=q.executeQuery()){if(r.next()){if(!fingerprint.equals(r.getString(2)))throw new IllegalArgumentException("同一保存操作的规则内容已变化，请先核对列表");return get(c,account,r.getString(1));}}}
        try(var q=c.prepareStatement("SELECT COUNT(*) FROM auto_rules WHERE account_id=?")){q.setString(1,account);try(var r=q.executeQuery()){r.next();if(r.getInt(1)>=20)throw new IllegalArgumentException("此阶段每账号最多保存20条目录观察规则");}}
        String id=UUID.randomUUID().toString();sql(c,"INSERT INTO auto_rules VALUES(?,?,?,?,1,1,0,0,?,?)",id,account,operation,fingerprint,now,now);
        sql(c,"INSERT INTO auto_rule_versions VALUES(?,1,?,?)",id,fingerprint,now);return get(c,account,id);
    });}
    public Rule byOperation(String account,String operation){return db.read(c->{try(var q=c.prepareStatement("SELECT id FROM auto_rules WHERE account_id=? AND create_operation=?")){q.setString(1,account);q.setString(2,operation);try(var r=q.executeQuery()){return r.next()?get(c,account,r.getString(1)):null;}}});}
    public Rule update(String account,String id,long revision,Input input,long now){return db.write(c->{
        var before=get(c,account,id);same(before,revision);if(before.version()>=1000)throw new IllegalArgumentException("规则版本达到此阶段上限，请保留记录后另建规则");
        long version=before.version()+1;sql(c,"INSERT INTO auto_rule_versions VALUES(?,?,?,?)",id,version,json.writeValueAsString(input),now);
        boolean enabled=before.enabled()&&!before.input().executionMode().equals("AUTO_UPLOAD")&&!input.executionMode().equals("AUTO_UPLOAD");
        sql(c,"UPDATE auto_rules SET current_version=?,revision=revision+1,next_scan_at=0,updated_at=?,enabled=? WHERE id=?",version,now,enabled?1:0,id);
        sql(c,"DELETE FROM auto_rule_observations WHERE rule_id=?",id);return get(c,account,id);
    });}
    public Rule enabled(String account,String id,long revision,boolean enabled,long now){return enabled(account,id,revision,enabled,now,null);}
    public Rule enabled(String account,String id,long revision,boolean enabled,long now,Target approved){return db.write(c->{var before=get(c,account,id);same(before,revision);
        if(enabled&&before.input().executionMode().equals("AUTO_UPLOAD")){if(approved==null||!account.equals(approved.post().accountId()))throw new IllegalArgumentException("请先预览并确认自动上传的目标、目录和时段");sql(c,"INSERT INTO auto_rule_authorizations VALUES(?,?,?,?,?)",id,before.version(),before.revision()+1,json.writeValueAsString(approved),now);}
        sql(c,"UPDATE auto_rules SET enabled=?,revision=revision+1,next_scan_at=0,updated_at=? WHERE id=?",enabled?1:0,now,id);return get(c,account,id);
    });}
    public Rule get(String account,String id){return db.read(c->get(c,account,id));}
    public List<Rule> list(String account){return db.read(c->{var ids=new ArrayList<String>();try(var q=c.prepareStatement("SELECT id FROM auto_rules WHERE account_id=? ORDER BY created_at DESC,id LIMIT 20")){q.setString(1,account);try(var r=q.executeQuery()){while(r.next())ids.add(r.getString(1));}}var out=new ArrayList<Rule>();for(var id:ids)out.add(get(c,account,id));return List.copyOf(out);});}
    public List<Version> versions(String account,String id){return db.read(c->{get(c,account,id);var versions=new ArrayList<Version>();try(var q=c.prepareStatement("SELECT version,created_at,config_json FROM auto_rule_versions WHERE rule_id=? ORDER BY version DESC LIMIT 20")){q.setString(1,id);try(var r=q.executeQuery()){while(r.next())versions.add(new Version(r.getLong(1),r.getLong(2),json.readValue(r.getString(3),Input.class)));}}return List.copyOf(versions);});}
    public Rule due(String account,long now){return db.read(c->{try(var q=c.prepareStatement("SELECT id FROM auto_rules WHERE account_id=? AND enabled=1 AND next_scan_at<=? ORDER BY next_scan_at,id LIMIT 1")){q.setString(1,account);q.setLong(2,now);try(var r=q.executeQuery()){return r.next()?get(c,account,r.getString(1)):null;}}});}
    public boolean defer(Rule rule,long until){return db.write(c->{try(var q=c.prepareStatement("UPDATE auto_rules SET next_scan_at=? WHERE id=? AND account_id=? AND revision=? AND enabled=1")){q.setLong(1,until);q.setString(2,rule.id());q.setString(3,rule.accountId());q.setLong(4,rule.revision());return q.executeUpdate()==1;}});}
    public boolean observe(Rule original,Scan scan,String error,boolean automatic,long now){return db.write(c->{
        var current=get(c,original.accountId(),original.id());if(current.revision()!=original.revision()||current.version()!=original.version()||automatic&&!current.enabled())return false;
        var previous=current.observation();int added=0,changed=0,unobserved=0;
        if(scan!=null){var old=new HashMap<String,io.github.telegramenhance.transfer.UploadPreviewModels.FileVersion>();if(previous!=null&&previous.scan()!=null)for(var f:previous.scan().observed())old.put(f.relativePath(),f);
            for(var f:scan.observed()){var known=old.remove(f.relativePath());if(known==null)added++;else if(!known.equals(f))changed++;}unobserved=old.size();}
        var result=scan!=null?new Observation(current.version(),current.revision(),now,now,"OBSERVED",scan,added,changed,unobserved,null,previous==null?null:previous.upload()):
            new Observation(current.version(),current.revision(),now,previous==null?0:previous.scanAt(),"ERROR",previous==null?null:previous.scan(),previous==null?0:previous.added(),previous==null?0:previous.changed(),previous==null?0:previous.unobserved(),error,previous==null?null:previous.upload());
        sql(c,"INSERT INTO auto_rule_observations VALUES(?,?,?) ON CONFLICT(rule_id) DO UPDATE SET version=excluded.version,observation_json=excluded.observation_json",current.id(),current.version(),json.writeValueAsString(result));return true;
    });}
    public void uploadOutcome(Rule expected,UploadOutcome outcome){db.write(c->{var current=get(c,expected.accountId(),expected.id());if(current.revision()!=expected.revision()||current.version()!=expected.version()||current.observation()==null)return null;var p=current.observation();var observation=new Observation(p.version(),p.revision(),p.checkedAt(),p.scanAt(),p.outcome(),p.scan(),p.added(),p.changed(),p.unobserved(),p.error(),outcome);sql(c,"UPDATE auto_rule_observations SET observation_json=? WHERE rule_id=?",json.writeValueAsString(observation),current.id());return null;});}
    private Rule get(Connection c,String account,String id)throws SQLException{
        try(var q=c.prepareStatement("SELECT r.*,v.config_json,o.observation_json FROM auto_rules r JOIN auto_rule_versions v ON v.rule_id=r.id AND v.version=r.current_version LEFT JOIN auto_rule_observations o ON o.rule_id=r.id WHERE r.account_id=? AND r.id=?")){q.setString(1,account);q.setString(2,id);try(var r=q.executeQuery()){
            if(!r.next())throw new IllegalArgumentException("当前账号中没有这条规则");String observed=r.getString("observation_json");
            return new Rule(id,account,r.getLong("current_version"),r.getLong("revision"),r.getBoolean("enabled"),r.getLong("next_scan_at"),r.getLong("created_at"),r.getLong("updated_at"),json.readValue(r.getString("config_json"),Input.class),observed==null?null:json.readValue(observed,Observation.class));
        }}
    }
    private static void same(Rule rule,long revision){if(revision<1||rule.revision()!=revision)throw new IllegalArgumentException("规则已被修改，请刷新后核对当前版本");}
    private static void sql(Connection c,String sql,Object...args)throws SQLException{try(var q=c.prepareStatement(sql)){for(int n=0;n<args.length;n++)q.setObject(n+1,args[n]);q.executeUpdate();}}
}
