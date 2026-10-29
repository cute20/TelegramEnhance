package io.github.telegramenhance.downloadrules;

import static io.github.telegramenhance.downloadrules.DownloadRuleModels.*;
import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import io.github.telegramenhance.library.ChannelImportModels.ManagedChannel;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.ChannelReader;
import io.github.telegramenhance.transfer.DownloadModels.FrozenFile;
import io.github.telegramenhance.transfer.DownloadRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** All callbacks contain only bounded local SQL. Remote reads/freezing happen before these CAS transactions. */
@Repository
public final class DownloadRuleRepository {
    public static final long REVIEW_MILLIS=300_000;
    private static final int MAX_RULES=20,MAX_POSTS=10_000,MAX_MEDIA=100_000;
    private final Database db;
    private final DownloadRepository downloads;
    private final JsonMapper json=JsonMapper.builder().build();
    public DownloadRuleRepository(Database db,DownloadRepository downloads){this.db=db;this.downloads=downloads;}

    public Rule create(String account,String operation,String fingerprint,Input input,ManagedChannel channel,long now,Runnable guard){
        return create(account,operation,fingerprint,input,channel,null,now,guard);
    }
    public Rule create(String account,String operation,String fingerprint,Input input,ManagedChannel channel,Post fixedPost,long now,Runnable guard){
        identity(account,input,channel,fixedPost);
        return db.write(c->{guard.run();var previous=byOperation(c,account,operation,fingerprint);if(previous!=null){guard.run();return previous;}
            if(count(c,"SELECT COUNT(*) FROM download_rules WHERE account_id=?",account)>=MAX_RULES)throw new IllegalArgumentException("每个账号最多保存20条持续下载规则");
            String id=UUID.randomUUID().toString();sql(c,"INSERT INTO download_rules VALUES(?,?,?,?,1,1,0,0,?,?)",id,account,operation,fingerprint,now,now);
            sql(c,"INSERT INTO download_rule_versions VALUES(?,1,?,?,?,?)",id,encode(input),channel.id(),channel.title(),now);initialScan(c,id,1,input,fixedPost,now);guard.run();return get(c,account,id);
        });
    }
    public Rule update(String account,String id,long revision,Input input,ManagedChannel channel,long now,Runnable guard){
        return update(account,id,revision,input,channel,null,now,guard);
    }
    public Rule update(String account,String id,long revision,Input input,ManagedChannel channel,Post fixedPost,long now,Runnable guard){
        identity(account,input,channel,fixedPost);
        return db.write(c->{guard.run();var before=get(c,account,id);same(before,revision);if(before.version()>=1000)throw new IllegalArgumentException("规则配置已达1000版本，请保留旧记录后另建规则");
            long version=before.version()+1;sql(c,"INSERT INTO download_rule_versions VALUES(?,?,?,?,?,?)",id,version,encode(input),channel.id(),channel.title(),now);
            sql(c,"UPDATE download_rules SET current_version=?,revision=revision+1,enabled=0,next_scan_at=0,updated_at=? WHERE id=?",version,now,id);
            pauseManaged(c,account,id,now);initialScan(c,id,version,input,fixedPost,now);guard.run();return get(c,account,id);
        });
    }
    public Rule toggle(String account,String id,long revision,boolean enabled,long now,Runnable guard){return db.write(c->{
        guard.run();var before=get(c,account,id);same(before,revision);sql(c,"UPDATE download_rules SET enabled=?,revision=revision+1,next_scan_at=0,updated_at=? WHERE id=?",enabled?1:0,now,id);
        if(!enabled)pauseManaged(c,account,id,now);guard.run();return get(c,account,id);
    });}
    public Rule get(String account,String id){return db.read(c->get(c,account,id));}
    public Rule byOperation(String account,String operation,String fingerprint){return db.read(c->byOperation(c,account,operation,fingerprint));}
    public Rule byOperation(String account,String operation){return byOperation(account,operation,null);}
    public List<Rule> list(String account){return db.read(c->{var ids=new ArrayList<String>();try(var q=c.prepareStatement("SELECT id FROM download_rules WHERE account_id=? ORDER BY created_at DESC,id LIMIT 20")){q.setString(1,account);try(var r=q.executeQuery()){while(r.next())ids.add(r.getString(1));}}var out=new ArrayList<Rule>();for(String id:ids)out.add(get(c,account,id));return List.copyOf(out);});}
    public List<Version> versions(String account,String id){return db.read(c->{get(c,account,id);var out=new ArrayList<Version>();try(var q=c.prepareStatement("SELECT version,created_at,config_json,channel_id,channel_title FROM download_rule_versions WHERE rule_id=? ORDER BY version DESC LIMIT 20")){q.setString(1,id);try(var r=q.executeQuery()){while(r.next())out.add(new Version(r.getLong(1),r.getLong(2),decode(r.getString(3),Input.class),r.getString(4),r.getString(5)));}}return List.copyOf(out);});}
    public Rule nextDue(String account,long now){return db.read(c->{try(var q=c.prepareStatement("SELECT id FROM download_rules WHERE account_id=? AND enabled=1 AND next_scan_at<=? ORDER BY next_scan_at,id LIMIT 1")){q.setString(1,account);q.setLong(2,now);try(var r=q.executeQuery()){return r.next()?get(c,account,r.getString(1)):null;}}});}
    public void schedule(Rule expected,long nextAt,String error,long now,Runnable guard){db.write(c->{guard.run();expect(c,expected);sql(c,"UPDATE download_rules SET next_scan_at=?,updated_at=? WHERE id=?",nextAt,now,expected.id());sql(c,"UPDATE download_rule_scans SET error=?,updated_at=?,turn=turn+1 WHERE rule_id=? AND version=?",boundedError(error),now,expected.id(),expected.version());guard.run();return null;});}
    public Scan scan(Rule rule){return db.read(c->{expect(c,rule);return scan(c,rule);});}
    public PostState getPost(Rule rule,String key){return db.read(c->{expect(c,rule);return post(c,rule,key);});}
    public PostState nextPendingPost(Rule rule){return selectPost(rule,"state IN('MAIN','COMMENTS')",Long.MAX_VALUE);}
    public PostState duePost(Rule rule,long now){return selectPost(rule,"state IN('DONE','ERROR')",now);}
    private PostState selectPost(Rule rule,String states,long now){return db.read(c->{expect(c,rule);try(var q=c.prepareStatement("SELECT post_key FROM download_rule_posts WHERE rule_id=? AND version=? AND "+states+" AND next_review_at<=?"+(rule.input().postSource()?" AND post_key=?":"")+" ORDER BY next_review_at,post_key LIMIT 1")){q.setString(1,rule.id());q.setLong(2,rule.version());q.setLong(3,now);if(rule.input().postSource())q.setString(4,rule.input().postKey());try(var r=q.executeQuery()){return r.next()?post(c,rule,r.getString(1)):null;}}});}

    public Scan saveHistoryPage(Rule rule,Scan expected,HistoryPage page,long now,Runnable guard){
        channelSource(rule);
        if(page==null||page.channel()==null||!rule.channelId().equals(page.channel().id())||page.items().size()>10||page.limit()<1||page.limit()>10||!page.warnings().isEmpty()||page.exhausted()&&page.hasMore())throw new IllegalArgumentException("频道历史批次身份或范围无效，原位置保留");
        String payload=encode(page);if(payload.length()>65_536)throw new IllegalArgumentException("频道历史批次超过保存上限，原位置保留");
        if(!page.exhausted()&&(!page.hasMore()||!ChannelReader.advancesCursor(expected.cursor(),page.nextCursor(),rule.accountId(),rule.channelId())))throw new IllegalArgumentException("频道历史未到边界且游标未向旧推进，原位置保留");
        return db.write(c->{guard.run();expect(c,rule);var current=scan(c,rule);sameScan(current,expected);if(current.pending()!=null)throw conflict();
            if(page.items().isEmpty()){
                if(page.exhausted())finish(c,rule,current,"HISTORY".equals(current.phase()),now);
                else sql(c,"UPDATE download_rule_scans SET revision=revision+1,cursor=?,error=NULL,updated_at=? WHERE rule_id=? AND version=?",page.nextCursor(),now,rule.id(),rule.version());
            }else sql(c,"UPDATE download_rule_scans SET revision=revision+1,pending_json=?,pending_offset=0,error=NULL,updated_at=? WHERE rule_id=? AND version=?",payload,now,rule.id(),rule.version());
            guard.run();return scan(c,rule);
        });
    }
    /** Save one complete/partial main observation and consume exactly one persisted candidate. */
    public Scan observePost(Rule rule,Scan expected,Post value,boolean matched,long now,Runnable guard){
        channelSource(rule);
        validatePost(rule,value);
        return db.write(c->{guard.run();expect(c,rule);var current=scan(c,rule);sameScan(current,expected);if(current.pending()==null||current.offset()>=current.pending().items().size())throw conflict();
            var candidate=current.pending().items().get(current.offset());if(!value.mainMessageId().equals(candidate.messageId())&&value.mainMessages().stream().noneMatch(m->candidate.messageId().equals(m.messageId())))throw new IllegalArgumentException("主帖不属于当前待处理频道消息，位置未推进");
            var previous=post(c,rule,value.key());
            if(previous!=null&&"HEAD".equals(current.phase())){finish(c,rule,current,false,now);guard.run();return scan(c,rule);}
            if(previous==null){
                if(count(c,"SELECT COUNT(*) FROM download_rule_posts WHERE rule_id=? AND version=?",rule.id(),rule.version())>=MAX_POSTS)throw new IllegalArgumentException("本版本已扫描10000条主帖，规则暂停以保留已有范围");
                String state=mainState(value,matched);String error="ERROR".equals(state)?"主帖或相册范围尚未核全，保留待复查。":null;
                sql(c,"INSERT INTO download_rule_posts VALUES(?,?,?,?,?,?,NULL,0,?,?,?,0)",rule.id(),rule.version(),value.key(),value.mainMessageId(),encodePost(value),state,matched?1:0,"MAIN".equals(state)?now:now+REVIEW_MILLIS,error);
            }
            consume(c,rule,current,now);guard.run();return scan(c,rule);
        });
    }
    /** A failed candidate retains its immutable source identity, so one inaccessible post cannot starve later history. */
    public Scan observeCandidateError(Rule rule,Scan expected,String error,long now,Runnable guard){channelSource(rule);return db.write(c->{
        guard.run();expect(c,rule);var current=scan(c,rule);sameScan(current,expected);if(current.pending()==null||current.offset()>=current.pending().items().size())throw conflict();
        var candidate=current.pending().items().get(current.offset());String message=candidate.messageId();
        if(message==null||!message.matches("[1-9][0-9]{0,18}")||Long.parseLong(message)<=0)throw new IllegalArgumentException("原候选消息身份无效，检查点保留");
        String album=candidate.albumId();boolean grouped=album!=null&&!"0".equals(album);if(grouped&&!Long.toString(Long.parseLong(album)).equals(album))throw new IllegalArgumentException("原候选相册身份无效，检查点保留");
        String key=rule.accountId()+":"+rule.channelId()+":"+(grouped?"album:"+album:message);var previous=post(c,rule,key);
        if(previous==null){if(count(c,"SELECT COUNT(*) FROM download_rule_posts WHERE rule_id=? AND version=?",rule.id(),rule.version())>=MAX_POSTS)throw new IllegalArgumentException("本版本已扫描10000条主帖，原位置保留");sql(c,"INSERT INTO download_rule_posts VALUES(?,?,?,?,NULL,'ERROR',NULL,0,0,?,?,0)",rule.id(),rule.version(),key,message,now+REVIEW_MILLIS,boundedError(error));}
        else sql(c,"UPDATE download_rule_posts SET state='ERROR',next_review_at=?,error=?,revision=revision+1 WHERE rule_id=? AND version=? AND post_key=?",now+REVIEW_MILLIS,boundedError(error),rule.id(),rule.version(),key);
        if(previous!=null&&"HEAD".equals(current.phase()))finish(c,rule,current,false,now);else consume(c,rule,current,now);guard.run();return scan(c,rule);
    });}
    public Scan finishHistory(Rule rule,Scan expected,long now,Runnable guard){return finishScan(rule,expected,true,now,guard);}
    public Scan finishHead(Rule rule,Scan expected,long now,Runnable guard){return finishScan(rule,expected,false,now,guard);}
    private Scan finishScan(Rule rule,Scan expected,boolean history,long now,Runnable guard){channelSource(rule);return db.write(c->{guard.run();expect(c,rule);var current=scan(c,rule);sameScan(current,expected);if(!history&&!"HEAD".equals(current.phase()))throw conflict();if(history&&(current.pending()==null||!current.pending().exhausted()||current.offset()<current.pending().items().size()))throw new IllegalArgumentException("未证明频道历史边界，不能标记完成");finish(c,rule,current,history,now);guard.run();return scan(c,rule);});}
    public PostState resetPost(Rule rule,PostState expected,Post fresh,boolean matched,long now,Runnable guard){validatePost(rule,fresh);if(!fresh.key().equals(expected.key()))throw conflict();return db.write(c->{guard.run();expect(c,rule);samePost(post(c,rule,expected.key()),expected);String state=mainState(fresh,matched);String error="ERROR".equals(state)?"主帖或相册范围尚未核全，保留待复查。":null;sql(c,"UPDATE download_rule_posts SET post_json=?,state=?,cursor=NULL,matched=?,next_review_at=?,error=?,revision=revision+1 WHERE rule_id=? AND version=? AND post_key=?",encodePost(fresh),state,matched?1:0,"MAIN".equals(state)?now:now+REVIEW_MILLIS,error,rule.id(),rule.version(),expected.key());guard.run();return post(c,rule,expected.key());});}
    public PostState postError(Rule rule,PostState expected,String error,long now,Runnable guard){return db.write(c->{guard.run();expect(c,rule);samePost(post(c,rule,expected.key()),expected);sql(c,"UPDATE download_rule_posts SET state='ERROR',next_review_at=?,error=?,revision=revision+1 WHERE rule_id=? AND version=? AND post_key=?",now+REVIEW_MILLIS,boundedError(error),rule.id(),rule.version(),expected.key());guard.run();return post(c,rule,expected.key());});}

    /** Media claims, ordinary task, binding and source cursor are one atomic publication. */
    public Commit commitPage(Rule rule,PostState expected,String nextCursor,boolean exhausted,List<FrozenFile> files,Path root,long now,Runnable guard){
        if(files==null||files.size()>100||expected.post()==null||!root.normalize().toString().equals(Path.of(rule.input().targetDirectory()).normalize().toString()))throw new IllegalArgumentException("持续下载媒体或目标目录无效");
        validatePost(rule,expected.post());
        var seen=new HashSet<String>();for(var file:files){if(file==null||file.media()==null||file.uniqueId()==null||file.uniqueId().isBlank()||file.size()<=0||!seen.add(mediaIdentity(file)))throw new IllegalArgumentException("持续下载页含无效或重复媒体");}
        boolean main="MAIN".equals(expected.state());if(!main&&!"COMMENTS".equals(expected.state()))throw conflict();
        if(!main&&!exhausted&&!older(expected.cursor(),nextCursor))throw new IllegalArgumentException("评论未到边界且游标未向旧推进，原位置保留");
        return db.write(c->{guard.run();expect(c,rule);samePost(post(c,rule,expected.key()),expected);
            var fresh=new ArrayList<FrozenFile>();for(var file:files)if(!claimed(c,rule,expected.key(),file))fresh.add(file);
            Job job=null;
            if(!fresh.isEmpty()){
                if(!rule.enabled()||!rule.input().window().allows(java.time.Instant.ofEpochMilli(now)))throw new IllegalArgumentException("规则未启用或当前不在允许时段，原读取位置保留");
                settleCompleted(c,rule.id(),now);if(job(c,rule,true)!=null)throw new IllegalArgumentException("此规则已有未完成下载，请先处理已有任务");
                if(count(c,"SELECT COUNT(*) FROM download_rule_media WHERE rule_id=? AND version=?",rule.id(),rule.version())+fresh.size()>MAX_MEDIA)throw new IllegalArgumentException("本版本已达到100000媒体声明上限，原位置保留");
                String operation=UUID.nameUUIDFromBytes(("download-rule:"+rule.id()+":"+rule.version()+":"+expected.key()+":"+expected.revision()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                var task=downloads.createInTransaction(c,rule.accountId(),operation,operation,expected.post(),root,fresh);
                sql(c,"INSERT INTO download_rule_tasks VALUES(?,?,?,?,?,0,0,NULL,?)",task.id(),rule.id(),rule.accountId(),rule.version(),encode(rule.input()),now);
                for(var item:task.items())sql(c,"INSERT INTO download_rule_media VALUES(?,?,?,?,?,?,?,?,?)",rule.id(),rule.version(),expected.key(),item.file().media().key(),item.file().uniqueId(),item.file().size(),task.id(),item.id(),now);
                job=jobById(c,rule,task.id());
            }
            String state=main?(rule.input().types().isEmpty()?"DONE":"COMMENTS"):exhausted?"DONE":"COMMENTS";String error=null;
            if(main&&!rule.input().types().isEmpty()&&!expected.post().canReadComments()){state="ERROR";error="评论当前不可读取，未将其当作空评论或完整范围。";}
            long nextReview="DONE".equals(state)||"ERROR".equals(state)?now+REVIEW_MILLIS:now;
            sql(c,"UPDATE download_rule_posts SET state=?,cursor=?,pages=pages+?,next_review_at=?,error=?,revision=revision+1 WHERE rule_id=? AND version=? AND post_key=?",state,main?null:nextCursor,main?0:1,nextReview,error,rule.id(),rule.version(),expected.key());
            guard.run();return new Commit(post(c,rule,expected.key()),job,fresh.size());
        });
    }
    public Job activeJob(Rule rule){return db.read(c->{expect(c,rule);return job(c,rule,true);});}
    public Job latestJob(Rule rule){return db.read(c->{expect(c,rule);return job(c,rule,false);});}
    public Job takeover(Rule rule,String taskId,long now,Runnable guard){return db.write(c->{guard.run();expect(c,rule);var job=jobById(c,rule,taskId);if(job==null||job.manual()||!List.of("PAUSED","FAILED").contains(job.state()))throw new IllegalArgumentException("请先将规则任务停稳，再转为手动处理");sql(c,"UPDATE download_rule_tasks SET manual=1,held=0,settled_at=? WHERE task_id=? AND rule_id=?",now,taskId,rule.id());guard.run();return jobById(c,rule,taskId);});}
    public Summary summary(Rule rule){return db.read(c->{expect(c,rule);var scan=scan(c,rule);long matched=count(c,"SELECT COUNT(*) FROM download_rule_posts WHERE rule_id=? AND version=? AND matched=1",rule.id(),rule.version());long unresolved=count(c,"SELECT COUNT(*) FROM download_rule_posts WHERE rule_id=? AND version=? AND state IN('MAIN','COMMENTS','ERROR')",rule.id(),rule.version());long errors=count(c,"SELECT COUNT(*) FROM download_rule_posts WHERE rule_id=? AND version=? AND state='ERROR'",rule.id(),rule.version());long pages=count(c,"SELECT COALESCE(SUM(pages),0) FROM download_rule_posts WHERE rule_id=? AND version=?",rule.id(),rule.version());long media=count(c,"SELECT COUNT(*) FROM download_rule_media WHERE rule_id=? AND version=?",rule.id(),rule.version());long completed=count(c,"SELECT COUNT(*) FROM download_rule_media m JOIN download_items i ON i.item_id=m.item_id JOIN download_completion_log l ON l.item_id=i.item_id AND l.task_id=i.task_id AND l.account_id=? AND l.unique_id=m.unique_id AND l.size=m.size AND l.sha256=i.sha256 WHERE m.rule_id=? AND m.version=? AND i.state='COMPLETED' AND i.downloaded_bytes=m.size AND i.written_bytes=m.size",rule.accountId(),rule.id(),rule.version());var active=job(c,rule,true);return new Summary(scan.historyDone(),scan.scanned(),matched,unresolved,pages,media,completed,rule.nextScanAt(),scan.error(),active==null?job(c,rule,false):active,errors);});}

    private void initialScan(Connection c,String id,long version,Input input,Post fixedPost,long now)throws SQLException{
        if(!input.postSource()){sql(c,"INSERT INTO download_rule_scans VALUES(?,?,0,'HISTORY',NULL,NULL,0,0,0,0,NULL,?,0)",id,version,now);return;}
        // HEAD is the existing schema marker for an initialized source. POST's service lane
        // never reads channel history/head; the frozen source mode and identity govern it.
        sql(c,"INSERT INTO download_rule_scans VALUES(?,?,0,'HEAD',NULL,NULL,0,1,0,1,NULL,?,0)",id,version,now);
        String state=mainState(fixedPost,true);String error="ERROR".equals(state)?"主帖或相册范围尚未核全，保留待复查。":null;
        sql(c,"INSERT INTO download_rule_posts VALUES(?,?,?,?,?,?,NULL,0,1,?,?,0)",id,version,fixedPost.key(),fixedPost.mainMessageId(),encodePost(fixedPost),state,"MAIN".equals(state)?now:now+REVIEW_MILLIS,error);
    }
    private Rule byOperation(Connection c,String account,String operation,String fingerprint)throws SQLException{try(var q=c.prepareStatement("SELECT id,create_fingerprint FROM download_rules WHERE account_id=? AND create_operation=?")){q.setString(1,account);q.setString(2,operation);try(var r=q.executeQuery()){if(!r.next())return null;if(fingerprint!=null&&!fingerprint.equals(r.getString(2)))throw new IllegalArgumentException("同一保存操作的持续下载配置已变化，请先核对原结果");return get(c,account,r.getString(1));}}}
    private Rule get(Connection c,String account,String id)throws SQLException{try(var q=c.prepareStatement("SELECT r.*,v.config_json,v.channel_id,v.channel_title FROM download_rules r JOIN download_rule_versions v ON v.rule_id=r.id AND v.version=r.current_version WHERE r.account_id=? AND r.id=?")){q.setString(1,account);q.setString(2,id);try(var r=q.executeQuery()){if(!r.next())throw new IllegalArgumentException("当前账号中没有这条持续下载规则");return new Rule(id,account,r.getLong("current_version"),r.getLong("revision"),r.getBoolean("enabled"),r.getLong("next_scan_at"),r.getLong("created_at"),r.getLong("updated_at"),decode(r.getString("config_json"),Input.class),r.getString("channel_id"),r.getString("channel_title"));}}}
    private Rule expect(Connection c,Rule expected)throws SQLException{var current=get(c,expected.accountId(),expected.id());if(current.version()!=expected.version()||current.revision()!=expected.revision()||current.enabled()!=expected.enabled())throw conflict();return current;}
    private Scan scan(Connection c,Rule rule)throws SQLException{try(var q=c.prepareStatement("SELECT * FROM download_rule_scans WHERE rule_id=? AND version=?")){q.setString(1,rule.id());q.setLong(2,rule.version());try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("持续下载检查点不存在，原记录保留");String pending=r.getString("pending_json");HistoryPage page=pending==null?null:decode(pending,HistoryPage.class);int offset=r.getInt("pending_offset");if(offset<0||page==null&&offset!=0||page!=null&&(page.items().isEmpty()||page.items().size()>10||offset>=page.items().size()))throw new IllegalStateException("持续下载候选批检查点无效，原记录保留");return new Scan(rule.id(),rule.version(),r.getLong("revision"),r.getString("phase"),r.getString("cursor"),page,offset,r.getBoolean("history_done"),r.getLong("next_head_at"),r.getLong("scanned"),r.getString("error"),r.getLong("updated_at"),r.getLong("turn"));}}}
    private PostState post(Connection c,Rule rule,String key)throws SQLException{try(var q=c.prepareStatement("SELECT * FROM download_rule_posts WHERE rule_id=? AND version=? AND post_key=?")){q.setString(1,rule.id());q.setLong(2,rule.version());q.setString(3,key);try(var r=q.executeQuery()){if(!r.next())return null;String value=r.getString("post_json");return new PostState(rule.id(),rule.version(),key,r.getString("message_id"),value==null?null:decode(value,Post.class),r.getString("state"),r.getString("cursor"),r.getLong("pages"),r.getBoolean("matched"),r.getLong("next_review_at"),r.getString("error"),r.getLong("revision"));}}}
    private Job job(Connection c,Rule rule,boolean active)throws SQLException{String condition=active?" AND b.manual=0 AND t.state<>'COMPLETED'":"";try(var q=c.prepareStatement("SELECT b.task_id FROM download_rule_tasks b JOIN download_tasks t ON t.task_id=b.task_id AND t.account_id=b.account_id WHERE b.rule_id=? AND b.account_id=?"+condition+" ORDER BY b.created_at DESC,b.task_id DESC LIMIT 1")){q.setString(1,rule.id());q.setString(2,rule.accountId());try(var r=q.executeQuery()){return r.next()?jobById(c,rule,r.getString(1)):null;}}}
    private Job jobById(Connection c,Rule rule,String id)throws SQLException{try(var q=c.prepareStatement("SELECT b.task_id,b.rule_version,b.manual,b.held,t.state,t.error_message,COUNT(i.item_id) AS total,SUM(CASE WHEN i.state='COMPLETED' AND i.downloaded_bytes=i.size AND i.written_bytes=i.size AND l.item_id IS NOT NULL THEN 1 ELSE 0 END) AS completed FROM download_rule_tasks b JOIN download_tasks t ON t.task_id=b.task_id AND t.account_id=b.account_id LEFT JOIN download_items i ON i.task_id=t.task_id LEFT JOIN download_completion_log l ON l.item_id=i.item_id AND l.task_id=i.task_id AND l.account_id=t.account_id AND l.unique_id=i.unique_id AND l.size=i.size AND l.sha256=i.sha256 WHERE b.rule_id=? AND b.account_id=? AND b.task_id=? GROUP BY b.task_id")){q.setString(1,rule.id());q.setString(2,rule.accountId());q.setString(3,id);try(var r=q.executeQuery()){return r.next()?new Job(id,r.getLong("rule_version"),r.getString("state"),r.getInt("total"),r.getLong("completed"),r.getString("error_message"),r.getBoolean("manual"),r.getBoolean("held")):null;}}}
    private void consume(Connection c,Rule rule,Scan current,long now)throws SQLException{var page=current.pending();boolean last=current.offset()+1==page.items().size();if(last&&page.exhausted()){sql(c,"UPDATE download_rule_scans SET scanned=scanned+1 WHERE rule_id=? AND version=?",rule.id(),rule.version());finish(c,rule,current,"HISTORY".equals(current.phase()),now);}else if(last)sql(c,"UPDATE download_rule_scans SET revision=revision+1,cursor=?,pending_json=NULL,pending_offset=0,scanned=scanned+1,error=NULL,updated_at=? WHERE rule_id=? AND version=?",page.nextCursor(),now,rule.id(),rule.version());else sql(c,"UPDATE download_rule_scans SET revision=revision+1,pending_offset=pending_offset+1,scanned=scanned+1,error=NULL,updated_at=? WHERE rule_id=? AND version=?",now,rule.id(),rule.version());}
    private void finish(Connection c,Rule rule,Scan current,boolean history,long now)throws SQLException{if(history&&!"HISTORY".equals(current.phase()))throw conflict();sql(c,"UPDATE download_rule_scans SET revision=revision+1,phase='HEAD',cursor=NULL,pending_json=NULL,pending_offset=0,history_done=CASE WHEN ?=1 THEN 1 ELSE history_done END,next_head_at=?,error=NULL,updated_at=? WHERE rule_id=? AND version=?",history?1:0,now+60_000,now,rule.id(),rule.version());}
    private void pauseManaged(Connection c,String account,String rule,long now)throws SQLException{sql(c,"UPDATE download_tasks SET state='PAUSED',pause_requested=1,error_message=?,updated_at=? WHERE account_id=? AND state='QUEUED' AND task_id IN(SELECT task_id FROM download_rule_tasks WHERE rule_id=? AND account_id=? AND manual=0)",DownloadRulePolicy.RULE_PAUSE,now,account,rule,account);}
    private void settleCompleted(Connection c,String rule,long now)throws SQLException{sql(c,"UPDATE download_rule_tasks SET settled_at=? WHERE rule_id=? AND manual=0 AND settled_at IS NULL AND task_id IN(SELECT task_id FROM download_tasks WHERE state='COMPLETED')",now,rule);}
    private boolean claimed(Connection c,Rule rule,String key,FrozenFile file)throws SQLException{return count(c,"SELECT COUNT(*) FROM download_rule_media WHERE rule_id=? AND version=? AND post_key=? AND media_key=? AND unique_id=? AND size=?",rule.id(),rule.version(),key,file.media().key(),file.uniqueId(),file.size())!=0;}
    private static void validatePost(Rule rule,Post value){if(value==null||!rule.accountId().equals(value.accountId())||!rule.channelId().equals(value.channelId())||rule.input().postSource()&&(!java.util.Objects.equals(rule.input().postKey(),value.key())||!java.util.Objects.equals(rule.input().mainMessageId(),value.mainMessageId())))throw new IllegalArgumentException("下载规则主帖身份不符");}
    private String encodePost(Post post){String value=encode(post);if(value.length()>1_048_576)throw new IllegalArgumentException("主帖快照超过保存上限，原检查点保留");return value;}
    private static void identity(String account,Input input,ManagedChannel channel,Post fixedPost){
        if(account==null||account.isBlank()||input==null||channel==null||input.window()==null||!List.of("CHANNEL","POST").contains(input.sourceMode())
                ||!input.postSource()&&(!channel.id().equals(input.source())||fixedPost!=null)
                ||input.postSource()&&(fixedPost==null||!account.equals(fixedPost.accountId())||!channel.id().equals(fixedPost.channelId())
                    ||!java.util.Objects.equals(input.postKey(),fixedPost.key())||!java.util.Objects.equals(input.mainMessageId(),fixedPost.mainMessageId())
                    ||!DownloadRuleService.postLink(input.source()).equals(input.source())))throw new IllegalArgumentException("下载规则账号、频道、固定主帖或时段无效");
    }
    private static void channelSource(Rule rule){if(rule.input().postSource())throw new IllegalArgumentException("单主帖规则不读取频道目录。");}
    private static String mainState(Post value,boolean matched){return !"COMPLETE".equals(value.mainCoverage())?"ERROR":matched?"MAIN":"DONE";}
    private static void same(Rule rule,long revision){if(revision<1||revision!=rule.revision())throw conflict();}
    private static void sameScan(Scan current,Scan expected){if(current.version()!=expected.version()||current.revision()!=expected.revision()||!current.ruleId().equals(expected.ruleId()))throw conflict();}
    private static void samePost(PostState current,PostState expected){if(current==null||current.version()!=expected.version()||current.revision()!=expected.revision()||!java.util.Objects.equals(current.cursor(),expected.cursor())||!current.state().equals(expected.state()))throw conflict();}
    private static boolean older(String previous,String next){try{if(next==null||!next.matches("[1-9][0-9]{0,18}"))return false;long n=Long.parseLong(next);return n>0&&(previous==null||n<Long.parseLong(previous));}catch(NumberFormatException failure){return false;}}
    private static String mediaIdentity(FrozenFile file){return file.media().key()+"\n"+file.uniqueId()+"\n"+file.size();}
    private static String boundedError(String error){return error==null?null:error.substring(0,Math.min(2000,error.length()));}
    private String encode(Object value){return json.writeValueAsString(value);}
    private <T>T decode(String value,Class<T> type){if(value==null||value.length()>1_048_576)throw new IllegalStateException("持续下载保存记录无效，原记录保留");return json.readValue(value,type);}
    private static long count(Connection c,String statement,Object...args)throws SQLException{try(var q=c.prepareStatement(statement)){for(int n=0;n<args.length;n++)q.setObject(n+1,args[n]);try(var r=q.executeQuery()){return r.next()?r.getLong(1):0;}}}
    private static void sql(Connection c,String statement,Object...args)throws SQLException{try(var q=c.prepareStatement(statement)){for(int n=0;n<args.length;n++)q.setObject(n+1,args[n]);q.executeUpdate();}}
    private static IllegalArgumentException conflict(){return new IllegalArgumentException("持续下载配置或读取位置已变化，请读取最新状态后继续");}
}
