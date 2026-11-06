package io.github.telegramenhance.transfer;

import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.TelegramService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Bounded scan receipts and a gate between explicit steps. Never interrupts an admitted native read. */
@Service
public final class ScanTaskStore {
    private static final String PREFIX="transfer-center.scan.";
    private static final int ACCOUNT_LIMIT=100,GLOBAL_LIMIT=500;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Database db;private final TelegramService telegram;private final LongSupplier clock;
    private final String process=UUID.randomUUID().toString();private final Set<String> active=new HashSet<>();
    public record Snapshot(String sourceId,String category,String title,String subtitle,String state,long createdAt,long updatedAt,
        long total,long processed,long bytes,String phase,String detailId,String directory,String error,long revision,long expiresAt,boolean controllable,boolean canContinue) { }
    public record Entry(String id,String process,String account,long generation,Snapshot snapshot,boolean paused) { }
    public record View(String id,String sourceId,String category,String title,String subtitle,String state,long createdAt,long updatedAt,
        long total,long processed,long bytes,String phase,String detailId,String directory,String error,long revision,List<String> actions,boolean current) {
        public View{actions=List.copyOf(actions);}
    }
    public static final class Paused extends IllegalArgumentException {public Paused(){super("扫描已暂停，请在任务中心恢复后使用原位置继续");}}
    @org.springframework.beans.factory.annotation.Autowired
    public ScanTaskStore(Database db,TelegramService telegram){this(db,telegram,System::currentTimeMillis);}
    ScanTaskStore(Database db,TelegramService telegram,LongSupplier clock){this.db=db;this.telegram=telegram;this.clock=clock;}
    private String id(String account,long generation,String category,String source){return UUID.nameUUIDFromBytes((account+"\n"+process+"\n"+generation+"\n"+category+"\n"+source).getBytes(StandardCharsets.UTF_8)).toString();}
    /** claim is reserved for an explicit creation/start after the owning service has checked its source. */
    public synchronized void remember(String account,long generation,Snapshot snapshot,boolean claim){
        if(!current(account,generation))return;String id=id(account,generation,snapshot.category(),snapshot.sourceId());var previous=read(id);
        if(previous==null&&!claim)return;
        if(previous==null&&entries(account).stream().filter(e->e.process().equals(process)&&e.generation()==generation&&live(e)).count()>=80)throw new IllegalArgumentException("当前账号保留的扫描检查点已达80份，请先完成或等待过期后再开始");
        boolean terminal=terminal(snapshot);save(new Entry(id,process,account,generation,snapshot,!terminal&&previous!=null&&previous.paused()));
    }
    public synchronized void check(String account,long generation,String category,String source){
        require(account,generation);var entry=read(id(account,generation,category,source));if(entry==null)throw new IllegalArgumentException("扫描记录需重新核对，请回原面板显式开始后继续");
        if(entry.paused())throw new Paused();if(!live(entry))throw new IllegalArgumentException("扫描检查点已过期，请核对原面板；未自动恢复读取");
    }
    public synchronized Step begin(String account,long generation,String category,String source){
        check(account,generation,category,source);String id=id(account,generation,category,source);if(!active.add(id))throw new IllegalArgumentException("当前扫描步骤尚未结束，请等待原结果");return new Step(id);
    }
    public final class Step implements AutoCloseable {private final String id;private boolean closed;private Step(String id){this.id=id;}public void close(){synchronized(ScanTaskStore.this){if(!closed){active.remove(id);closed=true;}}}}
    public synchronized boolean paused(String account,long generation,String category,String source){var entry=read(id(account,generation,category,source));return entry!=null&&entry.paused()&&current(account,generation);}
    public synchronized long deadline(String account,long generation,String category,String source){var entry=read(id(account,generation,category,source));return entry==null?0:entry.snapshot().expiresAt();}
    public synchronized void expire(String account,long generation,String category,String source,String reason){
        var entry=read(id(account,generation,category,source));if(entry==null||terminal(entry.snapshot()))return;var s=entry.snapshot();
        save(new Entry(entry.id(),entry.process(),entry.account(),entry.generation(),new Snapshot(s.sourceId(),s.category(),s.title(),s.subtitle(),"EXPIRED",s.createdAt(),clock.getAsLong(),s.total(),s.processed(),s.bytes(),s.phase(),s.detailId(),s.directory(),reason,s.revision(),s.expiresAt(),false,false),false));
    }
    public synchronized View action(String id,String action){
        String account=telegram.accountId();long generation=telegram.sessionGeneration();var entry=read(id);
        if(entry==null||!entry.account().equals(account)||!entry.process().equals(process)||entry.generation()!=generation||!entry.snapshot().controllable()||!live(entry))throw new IllegalArgumentException("这条历史扫描不可恢复，请回原面板核对；未自动读取或重放");
        if(!Set.of("pause","resume").contains(action==null?"":action))throw new IllegalArgumentException("扫描操作无效");
        var s=entry.snapshot();long now=clock.getAsLong();var snapshot=new Snapshot(s.sourceId(),s.category(),s.title(),s.subtitle(),s.state(),s.createdAt(),now,s.total(),s.processed(),s.bytes(),s.phase(),s.detailId(),s.directory(),s.error(),s.revision(),s.expiresAt()==0?0:Math.max(s.expiresAt(),now+600_000),s.controllable(),s.canContinue());
        var next=new Entry(entry.id(),entry.process(),entry.account(),entry.generation(),snapshot,action.equals("pause"));save(next);require(account,generation);return view(next,account,generation);
    }
    public synchronized List<View> list(){String account=telegram.accountId();long generation=telegram.sessionGeneration();var result=entries(account).stream().map(entry->view(entry,account,generation)).toList();require(account,generation);return result;}
    /** Historical receipt only: no checkpoint is claimed and no native request is scheduled. */
    public synchronized View detail(String id){
        if(id==null||!id.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))throw new IllegalArgumentException("扫描历史标识无效");
        String account=telegram.accountId();long generation=telegram.sessionGeneration();var entry=read(id);
        if(entry==null||!entry.account().equals(account))throw new IllegalArgumentException("当前账号没有这条扫描历史");
        var result=view(entry,account,generation);require(account,generation);return result;
    }
    public synchronized View find(String account,long generation,String category,String source){var entry=read(id(account,generation,category,source));return entry==null?null:view(entry,account,generation);}
    private View view(Entry entry,String account,long generation){
        var s=entry.snapshot();boolean current=entry.account().equals(account)&&entry.process().equals(process)&&entry.generation()==generation;
        String state=s.state(),error=s.error();var actions=new ArrayList<String>();
        if(!terminal(s)){
            if(!current){state=s.controllable()?"EXPIRED":"NEEDS_REVIEW";error="旧会话或重启前的记录，仅保留历史摘要；请回原面板核对，未自动重放。";}
            else if(!live(entry)){state="EXPIRED";error="原预览检查点已过期，保留历史摘要；请回原面板重新准备。";}
            else if(s.controllable()){
                if(entry.paused()){state=active.contains(entry.id())?"PAUSING":"PAUSED";actions.add("resume");}
                else{state=active.contains(entry.id())?"RUNNING":state;actions.add("pause");}
            }
        }
        return new View(entry.id(),s.sourceId(),s.category(),s.title(),s.subtitle(),state,s.createdAt(),s.updatedAt(),s.total(),s.processed(),s.bytes(),s.phase(),s.detailId(),s.directory(),error,s.revision(),actions,current);
    }
    private boolean live(Entry entry){var s=entry.snapshot();return !terminal(s)&&s.canContinue()&&(entry.paused()||s.expiresAt()==0||clock.getAsLong()<s.expiresAt());}
    private static boolean terminal(Snapshot s){return Set.of("COMPLETED","EXPIRED","CANCELLED").contains(s.state())||s.state().equals("FAILED")&&!s.canContinue();}
    private boolean current(String account,long generation){try{return Objects.equals(account,telegram.accountId())&&generation==telegram.sessionGeneration();}catch(RuntimeException disconnected){return false;}}
    private void require(String account,long generation){if(!current(account,generation))throw new IllegalArgumentException("账号或会话已变化，请核对原账号扫描历史");}
    private Entry read(String id){return db.read(c->{try(var q=c.prepareStatement("SELECT setting_value FROM app_settings WHERE setting_key=?")){q.setString(1,PREFIX+id);try(var r=q.executeQuery()){return r.next()?JSON.readValue(r.getString(1),Entry.class):null;}}});}
    private List<Entry> entries(String account){return db.read(c->{var result=new ArrayList<Entry>();try(var q=c.prepareStatement("SELECT setting_value FROM app_settings WHERE setting_key LIKE ? AND json_extract(setting_value,'$.account')=? ORDER BY updated_at DESC,setting_key")){q.setString(1,PREFIX+"%");q.setString(2,account);try(var r=q.executeQuery()){while(r.next())result.add(JSON.readValue(r.getString(1),Entry.class));}}return result;});}
    private void save(Entry entry){String currentAccount;long currentGeneration=telegram.sessionGeneration();try{currentAccount=telegram.accountId();}catch(RuntimeException disconnected){currentAccount=entry.account();}final String liveAccount=currentAccount;long now=clock.getAsLong();
        db.write(c->{try(var q=c.prepareStatement("INSERT INTO app_settings(setting_key,setting_value,updated_at) VALUES(?,?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value,updated_at=excluded.updated_at")){q.setString(1,PREFIX+entry.id());q.setString(2,JSON.writeValueAsString(entry));q.setLong(3,now);q.executeUpdate();}
        String priority="CASE WHEN setting_key=? THEN 0 WHEN json_extract(setting_value,'$.process')=? AND json_extract(setting_value,'$.account')=? AND json_extract(setting_value,'$.generation')=? AND json_extract(setting_value,'$.snapshot.canContinue')=1 AND (json_extract(setting_value,'$.paused')=1 OR json_extract(setting_value,'$.snapshot.expiresAt')=0 OR json_extract(setting_value,'$.snapshot.expiresAt')>?) THEN 1 ELSE 2 END,updated_at DESC,setting_key";
        try(var q=c.prepareStatement("DELETE FROM app_settings WHERE setting_key LIKE ? AND json_extract(setting_value,'$.account')=? AND setting_key NOT IN(SELECT setting_key FROM app_settings WHERE setting_key LIKE ? AND json_extract(setting_value,'$.account')=? ORDER BY "+priority+" LIMIT ?)")){
            q.setString(1,PREFIX+"%");q.setString(2,entry.account());q.setString(3,PREFIX+"%");q.setString(4,entry.account());q.setString(5,PREFIX+entry.id());q.setString(6,process);q.setString(7,liveAccount);q.setLong(8,currentGeneration);q.setLong(9,now);q.setInt(10,ACCOUNT_LIMIT);q.executeUpdate();}
        try(var q=c.prepareStatement("DELETE FROM app_settings WHERE setting_key LIKE ? AND setting_key NOT IN(SELECT setting_key FROM app_settings WHERE setting_key LIKE ? ORDER BY "+priority+" LIMIT ?)")){
            q.setString(1,PREFIX+"%");q.setString(2,PREFIX+"%");q.setString(3,PREFIX+entry.id());q.setString(4,process);q.setString(5,liveAccount);q.setLong(6,currentGeneration);q.setLong(7,now);q.setInt(8,GLOBAL_LIMIT);q.executeUpdate();}return null;});}
}
