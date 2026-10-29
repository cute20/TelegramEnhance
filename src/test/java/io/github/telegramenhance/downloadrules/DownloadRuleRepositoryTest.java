package io.github.telegramenhance.downloadrules;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.telegramenhance.downloadrules.DownloadRuleModels.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import io.github.telegramenhance.library.ChannelImportModels.*;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.rules.RuleModels.Window;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.DownloadModels.FrozenFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DownloadRuleRepositoryTest {
    @TempDir Path directory;
    static final ManagedChannel CHANNEL=new ManagedChannel("-1001","test channel");
    static final Runnable GUARD=()->{};
    final class Fixture implements AutoCloseable {
        final AppPaths paths=new AppPaths(directory.resolve("data"));
        final Database db=new Database(paths);
        final DownloadRepository downloads=new DownloadRepository(db);
        final DownloadRuleRepository repo=new DownloadRuleRepository(db,downloads);
        final Path root=directory.resolve("out").toAbsolutePath();
        Rule rule;
        Fixture(){rule=repo.create("1",UUID.randomUUID().toString(),"fp",input("rule",root),CHANNEL,1,GUARD);rule=repo.toggle("1",rule.id(),rule.revision(),true,2,GUARD);}
        PostState observe(Post post,boolean matched){var scan=repo.scan(rule);var page=new HistoryPage(CHANNEL,List.of(new Candidate(post.mainMessageId(),null,"preview")),"CHANNEL_PAGE",10,null,true,false,List.of());scan=repo.saveHistoryPage(rule,scan,page,3,GUARD);repo.observePost(rule,scan,post,matched,4,GUARD);return repo.getPost(rule,post.key());}
        PostState comments(){var p=observe(post(100,true,true),true);return repo.commitPage(rule,p,null,false,List.of(),root,5,GUARD).post();}
        long count(String table){return db.read(c->{try(var q=c.createStatement();var r=q.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}});}
        @Override public void close(){db.close();}
    }
    static Input input(String name,Path root){return new Input(name,CHANNEL.id(),"tag","word",List.of("DOCUMENT"),false,root.toString(),new Window("ALL_DAY",null,null,"Asia/Shanghai"));}
    static Post post(int id,boolean complete,boolean readable){return new Post("1:-1001:"+id,"1","-1001","test channel",String.valueOf(id),"title "+id,"#tag word","0",List.of(new MessageRef("-1001",String.valueOf(id),"https://t.me/synthetic/"+id)),List.of(),"-2001","500",List.of(new MessageRef("-2001","500","")),readable,"PARTIAL",List.of(),new AlbumEvidence(complete,"test",null,null),complete?"COMPLETE":"PARTIAL");}
    static FrozenFile file(int id,String uid,String caption){var media=new Media("1:-2001:"+id+":0",new MessageRef("-2001",String.valueOf(id),"https://t.me/synthetic/"+id),"COMMENT","DOCUMENT","file-"+id+".txt",37,caption);return new FrozenFile(media,uid,37,media.fileName());}
    static String cursor(long id){return Base64.getUrlEncoder().withoutPadding().encodeToString(("v1|1|-1001|"+id+"|0").getBytes(StandardCharsets.UTF_8));}

    @Test void createIsPausedIdempotentAndAccountScoped(){try(var f=new Fixture()){
        String operation=UUID.randomUUID().toString();var a=f.repo.create("1",operation,"same",input("second",f.root),CHANNEL,10,GUARD);assertFalse(a.enabled());assertEquals(1,a.version());assertEquals(a,f.repo.create("1",operation,"same",input("second",f.root),CHANNEL,11,GUARD));assertEquals(a,f.repo.byOperation("1",operation,"same"));assertNull(f.repo.byOperation("2",operation));assertThrows(IllegalArgumentException.class,()->f.repo.byOperation("1",operation,"different"));assertThrows(IllegalArgumentException.class,()->f.repo.get("2",a.id()));assertEquals(0,f.count("download_tasks"));
    }}
    @Test void updateCreatesPausedVersionWithoutChangingOldScanOrTaskSnapshot(){try(var f=new Fixture()){
        var p=f.comments();var job=f.repo.commitPage(f.rule,p,"601",false,List.of(file(601,"u1","a")),f.root,8,GUARD).job();var before=f.downloads.task("1",job.taskId());var updated=f.repo.update("1",f.rule.id(),f.rule.revision(),input("new name",f.root),CHANNEL,10,GUARD);assertFalse(updated.enabled());assertEquals(2,updated.version());assertEquals("HISTORY",f.repo.scan(updated).phase());assertFalse(f.repo.scan(updated).historyDone());assertEquals(2,f.repo.versions("1",updated.id()).size());assertEquals("rule",f.repo.versions("1",updated.id()).get(1).input().name());var old=f.downloads.task("1",job.taskId());assertEquals(before.post(),old.post());assertEquals(before.items(),old.items());assertEquals("PAUSED",old.state());assertEquals(1,f.repo.activeJob(updated).ruleVersion());assertEquals(job.taskId(),f.repo.summary(updated).job().taskId());assertEquals("rule",f.downloads.view(old).automation().ruleName());
    }}
    @Test void historyBatchAndOffsetRemainDurableUntilEveryCandidateIsConsumed(){try(var f=new Fixture()){
        var scan=f.repo.scan(f.rule);var page=new HistoryPage(CHANNEL,List.of(new Candidate("100",null,"a"),new Candidate("90",null,"b")),"CHANNEL_PAGE",10,cursor(80),false,true,List.of());scan=f.repo.saveHistoryPage(f.rule,scan,page,3,GUARD);assertNull(scan.cursor());assertEquals(0,scan.offset());scan=f.repo.observePost(f.rule,scan,post(100,true,true),false,4,GUARD);assertNull(scan.cursor());assertEquals(1,scan.offset());scan=f.repo.observePost(f.rule,scan,post(90,true,true),false,5,GUARD);assertEquals(cursor(80),scan.cursor());assertNull(scan.pending());assertFalse(scan.historyDone());assertEquals(2,scan.scanned());
    }}
    @Test void emptyShortHistoryPageNeedsStrictlyOlderCursorAndOnlyTrueEofFinishes(){try(var f=new Fixture()){
        var s=f.repo.scan(f.rule);var empty=new HistoryPage(CHANNEL,List.of(),"CHANNEL_PAGE",10,cursor(80),false,true,List.of());s=f.repo.saveHistoryPage(f.rule,s,empty,3,GUARD);assertFalse(s.historyDone());assertEquals(cursor(80),s.cursor());var before=s;assertThrows(IllegalArgumentException.class,()->f.repo.saveHistoryPage(f.rule,before,empty,4,GUARD));assertEquals(before,f.repo.scan(f.rule));s=f.repo.saveHistoryPage(f.rule,s,new HistoryPage(CHANNEL,List.of(),"CHANNEL_PAGE",10,null,true,false,List.of()),5,GUARD);assertTrue(s.historyDone());assertEquals("HEAD",s.phase());
    }}
    @Test void partialPostIsUnresolvedRatherThanANegativeMatchAndDoesNotBlockOtherCandidates(){try(var f=new Fixture()){
        var scan=f.repo.saveHistoryPage(f.rule,f.repo.scan(f.rule),new HistoryPage(CHANNEL,List.of(new Candidate("100",null,"a"),new Candidate("90",null,"b")),"CHANNEL_PAGE",10,null,true,false,List.of()),3,GUARD);scan=f.repo.observePost(f.rule,scan,post(100,false,true),false,4,GUARD);assertEquals("ERROR",f.repo.getPost(f.rule,post(100,false,true).key()).state());f.repo.observePost(f.rule,scan,post(90,true,true),false,5,GUARD);assertEquals("DONE",f.repo.getPost(f.rule,post(90,true,true).key()).state());assertTrue(f.repo.scan(f.rule).historyDone());assertEquals(1,f.repo.summary(f.rule).unresolved());assertEquals(1,f.repo.summary(f.rule).errors());
    }}
    @Test void postAndRuleCasRejectLateResultsAfterResetOrPause(){try(var f=new Fixture()){
        var old=f.comments();var failed=f.repo.postError(f.rule,old,"temporary",6,GUARD);var reset=f.repo.resetPost(f.rule,failed,post(100,true,true),true,7,GUARD);assertNull(reset.cursor());assertThrows(IllegalArgumentException.class,()->f.repo.commitPage(f.rule,old,"601",false,List.of(),f.root,8,GUARD));var prior=f.rule;f.rule=f.repo.toggle("1",prior.id(),prior.revision(),false,9,GUARD);assertThrows(IllegalArgumentException.class,()->f.repo.commitPage(prior,reset,null,false,List.of(),f.root,10,GUARD));assertEquals(0,f.count("download_tasks"));
    }}
    @Test void pageClaimsTaskBindingAndCursorArePublishedTogetherAndRepeatObservationDoesNotCreateAgain(){try(var f=new Fixture()){
        var p=f.comments();var result=f.repo.commitPage(f.rule,p,"601",true,List.of(file(601,"stable","first caption")),f.root,6,GUARD);assertEquals(1,result.createdMedia());assertEquals("DONE",result.post().state());assertEquals(1,f.count("download_tasks"));assertEquals(1,f.count("download_rule_tasks"));assertEquals(1,f.count("download_rule_media"));var reset=f.repo.resetPost(f.rule,result.post(),post(100,true,true),true,7,GUARD);reset=f.repo.commitPage(f.rule,reset,null,false,List.of(),f.root,8,GUARD).post();var repeat=f.repo.commitPage(f.rule,reset,"601",true,List.of(file(601,"stable","edited caption")),f.root,9,GUARD);assertEquals(0,repeat.createdMedia());assertNull(repeat.job());assertEquals(1,f.count("download_tasks"));assertEquals(2,repeat.post().pages());assertEquals(1,f.repo.summary(f.rule).media());assertEquals(0,f.repo.summary(f.rule).completed());
    }}
    @Test void exceptionAtCommitGuardRollsBackTaskClaimsDirectoryBindingAndCursor(){try(var f=new Fixture()){
        var p=f.comments();var calls=new AtomicInteger();Runnable guard=()->{if(calls.incrementAndGet()==2)throw new IllegalArgumentException("changed session");};assertThrows(IllegalArgumentException.class,()->f.repo.commitPage(f.rule,p,"601",true,List.of(file(601,"u","")),f.root,6,guard));assertEquals(p,f.repo.getPost(f.rule,p.key()));for(String table:List.of("download_tasks","download_items","download_target_bindings","download_rule_tasks","download_rule_media"))assertEquals(0,f.count(table),table);
    }}
    @Test void sqlClaimFailureRollsBackOrdinaryTaskAndCursor(){try(var f=new Fixture()){
        var p=f.comments();f.db.write(c->{c.createStatement().execute("CREATE TRIGGER reject_rule_media BEFORE INSERT ON download_rule_media BEGIN SELECT RAISE(ABORT,'fixture'); END");return null;});assertThrows(IllegalStateException.class,()->f.repo.commitPage(f.rule,p,"601",true,List.of(file(601,"u","")),f.root,6,GUARD));assertEquals(p,f.repo.getPost(f.rule,p.key()));assertEquals(0,f.count("download_tasks"));assertEquals(0,f.count("download_rule_tasks"));
    }}
    @Test void failedOrPausedJobKeepsItsClaimsAndBlocksANewFileTask(){try(var f=new Fixture()){
        var p=f.comments();var result=f.repo.commitPage(f.rule,p,"601",false,List.of(file(601,"u","")),f.root,6,GUARD);f.downloads.finishState("1",result.job().taskId(),"FAILED","disk");assertThrows(IllegalArgumentException.class,()->f.repo.commitPage(f.rule,result.post(),"600",true,List.of(file(600,"new","")),f.root,7,GUARD));assertEquals(result.post(),f.repo.getPost(f.rule,p.key()));assertEquals(1,f.count("download_tasks"));assertEquals("FAILED",f.repo.activeJob(f.rule).state());assertEquals(1,f.repo.summary(f.rule).media());assertEquals(0,f.repo.summary(f.rule).completed());
    }}
    @Test void takeoverRequiresStoppedTaskAndPreservesItsClaimsWhileAllowingNextProduction(){try(var f=new Fixture()){
        var p=f.comments();var first=f.repo.commitPage(f.rule,p,"601",false,List.of(file(601,"u","")),f.root,6,GUARD);assertThrows(IllegalArgumentException.class,()->f.repo.takeover(f.rule,first.job().taskId(),7,GUARD));f.downloads.pause("1",first.job().taskId());var manual=f.repo.takeover(f.rule,first.job().taskId(),8,GUARD);assertTrue(manual.manual());assertEquals("PAUSED",f.downloads.task("1",manual.taskId()).state());assertNull(f.repo.activeJob(f.rule));var second=f.repo.commitPage(f.rule,first.post(),"600",true,List.of(file(600,"v","")),f.root,9,GUARD);assertNotEquals(first.job().taskId(),second.job().taskId());assertEquals(2,f.count("download_rule_media"));assertEquals(second.job().taskId(),f.repo.summary(f.rule).job().taskId());
    }}
    @Test void replacementUniqueIdIsNewMediaButSameVersionNeverExpandsFromAnOldTask(){try(var f=new Fixture()){
        var p=f.comments();var first=f.repo.commitPage(f.rule,p,"601",false,List.of(file(601,"u","")),f.root,6,GUARD);f.downloads.pause("1",first.job().taskId());f.repo.takeover(f.rule,first.job().taskId(),7,GUARD);var replacement=f.repo.commitPage(f.rule,first.post(),"600",true,List.of(file(601,"replacement","")),f.root,8,GUARD);assertEquals(1,replacement.createdMedia());assertEquals(2,f.count("download_rule_media"));assertEquals("u",f.downloads.task("1",first.job().taskId()).items().getFirst().file().uniqueId());assertEquals("replacement",f.downloads.task("1",replacement.job().taskId()).items().getFirst().file().uniqueId());
    }}
    @Test void commentNonEofCursorMustStrictlyDecreaseAndPermissionFailureIsNotEmptyEof(){try(var f=new Fixture()){
        var initial=f.comments();assertThrows(IllegalArgumentException.class,()->f.repo.commitPage(f.rule,initial,null,false,List.of(),f.root,6,GUARD));var current=f.repo.commitPage(f.rule,initial,"601",false,List.of(),f.root,6,GUARD).post();assertThrows(IllegalArgumentException.class,()->f.repo.commitPage(f.rule,current,"601",false,List.of(),f.root,7,GUARD));assertThrows(IllegalArgumentException.class,()->f.repo.commitPage(f.rule,current,"602",false,List.of(),f.root,7,GUARD));var noAccess=f.repo.resetPost(f.rule,current,post(100,true,false),true,8,GUARD);var result=f.repo.commitPage(f.rule,noAccess,null,false,List.of(),f.root,9,GUARD);assertEquals("ERROR",result.post().state());assertNotNull(result.post().error());assertEquals(1,f.repo.summary(f.rule).errors());
    }}
    @Test void disabledAndOutsideWindowCannotPublishFreshFiles(){try(var f=new Fixture()){
        var p=f.comments();f.rule=f.repo.toggle("1",f.rule.id(),f.rule.revision(),false,6,GUARD);assertThrows(IllegalArgumentException.class,()->f.repo.commitPage(f.rule,p,"601",true,List.of(file(601,"u","")),f.root,7,GUARD));var input=new Input("night",CHANNEL.id(),"","",List.of("DOCUMENT"),false,f.root.toString(),new Window("WINDOW","22:00","23:00","Asia/Shanghai"));f.rule=f.repo.update("1",f.rule.id(),f.rule.revision(),input,CHANNEL,8,GUARD);f.rule=f.repo.toggle("1",f.rule.id(),f.rule.revision(),true,9,GUARD);var newPost=f.comments();assertThrows(IllegalArgumentException.class,()->f.repo.commitPage(f.rule,newPost,"601",true,List.of(file(601,"u","")),f.root,10,GUARD));assertEquals(0,f.count("download_tasks"));
    }}
    @Test void headStopsAtKnownPostAndOldDonePostIsEligibleForFairReview(){try(var f=new Fixture()){
        var p=f.observe(post(100,true,true),false);assertTrue(f.repo.scan(f.rule).historyDone());assertNull(f.repo.duePost(f.rule,100));assertEquals(p,f.repo.duePost(f.rule,400_000));var scan=f.repo.saveHistoryPage(f.rule,f.repo.scan(f.rule),new HistoryPage(CHANNEL,List.of(new Candidate("100",null,"a")),"CHANNEL_PAGE",10,cursor(90),false,true,List.of()),400_001,GUARD);scan=f.repo.observePost(f.rule,scan,post(100,true,true),false,400_002,GUARD);assertNull(scan.pending());assertNull(scan.cursor());assertEquals("HEAD",scan.phase());assertEquals(1,f.count("download_rule_posts"));assertEquals(p,f.repo.getPost(f.rule,p.key()));
    }}
    @Test void restartPreservesPostCursorAndClaimsWithoutRecreatingTask(){AppPaths paths;String ruleId,taskId;PostState persisted;
        try(var f=new Fixture()){paths=f.paths;ruleId=f.rule.id();var p=f.comments();var result=f.repo.commitPage(f.rule,p,"601",false,List.of(file(601,"u","")),f.root,6,GUARD);taskId=result.job().taskId();persisted=result.post();}
        try(var db=new Database(paths)){var downloads=new DownloadRepository(db);var repo=new DownloadRuleRepository(db,downloads);var rule=repo.get("1",ruleId);assertTrue(rule.enabled());assertEquals(persisted,repo.getPost(rule,persisted.key()));assertEquals(taskId,repo.activeJob(rule).taskId());assertEquals("PAUSED",repo.activeJob(rule).state());assertEquals(1,repo.summary(rule).media());assertEquals(0,repo.summary(rule).completed());}
    }
    @Test void completionSummaryRequiresPersistedFullFileEvidence(){try(var f=new Fixture()){
        var result=f.repo.commitPage(f.rule,f.comments(),"601",true,List.of(file(601,"u","")),f.root,6,GUARD);f.db.write(c->{try(var q=c.prepareStatement("UPDATE download_items SET state='COMPLETED',downloaded_bytes=size,written_bytes=size,sha256=? WHERE task_id=?")){q.setString(1,"a".repeat(64));q.setString(2,result.job().taskId());q.executeUpdate();}return null;});assertEquals(0,f.repo.summary(f.rule).completed());assertEquals(0,f.repo.latestJob(f.rule).completed());
    }}
    @Test void historicalScopeWarningCannotBecomeASilentCompleteRange(){try(var f=new Fixture()){
        var before=f.repo.scan(f.rule);var page=new HistoryPage(CHANNEL,List.of(),"CHANNEL_PAGE",10,null,true,false,List.of("unsupported messages"));assertThrows(IllegalArgumentException.class,()->f.repo.saveHistoryPage(f.rule,before,page,4,GUARD));assertEquals(before,f.repo.scan(f.rule));assertFalse(f.repo.summary(f.rule).historyDone());assertThrows(IllegalArgumentException.class,()->f.repo.finishHead(f.rule,before,5,GUARD));
    }}
    @Test void unreadableCandidateBecomesDurableErrorWithoutBlockingRemainingHistory(){try(var f=new Fixture()){
        var scan=f.repo.saveHistoryPage(f.rule,f.repo.scan(f.rule),new HistoryPage(CHANNEL,List.of(new Candidate("100",null,"gone"),new Candidate("90",null,"good")),"CHANNEL_PAGE",10,null,true,false,List.of()),3,GUARD);scan=f.repo.observeCandidateError(f.rule,scan,"temporarily inaccessible, not deleted",4,GUARD);var unavailable=f.repo.getPost(f.rule,"1:-1001:100");assertEquals("ERROR",unavailable.state());assertNull(unavailable.post());assertEquals("100",unavailable.messageId());assertEquals(1,scan.offset());assertNull(scan.cursor());f.repo.observePost(f.rule,scan,post(90,true,true),false,5,GUARD);assertTrue(f.repo.scan(f.rule).historyDone());assertEquals(1,f.repo.summary(f.rule).errors());var recovered=f.repo.resetPost(f.rule,unavailable,post(100,true,true),true,400_000,GUARD);assertNotNull(recovered.post());assertEquals("MAIN",recovered.state());assertEquals(0,f.repo.summary(f.rule).errors());
    }}
    @Test void unreadableKnownHeadRetainsOldSnapshotAndEndsHeadWithVisibleError(){try(var f=new Fixture()){
        var original=f.observe(post(100,true,true),false);var scan=f.repo.saveHistoryPage(f.rule,f.repo.scan(f.rule),new HistoryPage(CHANNEL,List.of(new Candidate("100",null,"known")),"CHANNEL_PAGE",10,cursor(90),false,true,List.of()),400_000,GUARD);scan=f.repo.observeCandidateError(f.rule,scan,"unavailable",400_001,GUARD);var kept=f.repo.getPost(f.rule,original.key());assertEquals(original.post(),kept.post());assertEquals("ERROR",kept.state());assertNull(scan.pending());assertNull(scan.cursor());assertTrue(scan.historyDone());assertEquals(1,f.count("download_rule_posts"));
    }}
    @Test void schedulingTurnIsIndependentOfTimestampCasGuardedAndSurvivesRestart(){AppPaths paths;String id;
        try(var f=new Fixture()){
            paths=f.paths;id=f.rule.id();var before=f.repo.scan(f.rule);assertEquals(0,before.turn());f.repo.schedule(f.rule,100,null,10,GUARD);f.repo.schedule(f.rule,200,null,10,GUARD);var after=f.repo.scan(f.rule);assertEquals(2,after.turn());assertEquals(before.revision(),after.revision());assertEquals(10,after.updatedAt());var calls=new AtomicInteger();assertThrows(IllegalArgumentException.class,()->f.repo.schedule(f.rule,300,"error",11,()->{if(calls.incrementAndGet()==2)throw new IllegalArgumentException("new session");}));assertEquals(after,f.repo.scan(f.rule));
        }
        try(var db=new Database(paths)){var repo=new DownloadRuleRepository(db,new DownloadRepository(db));assertEquals(2,repo.scan(repo.get("1",id)).turn());assertEquals(200,repo.get("1",id).nextScanAt());}
    }
    private record LegacyInput(String name,String source,String tags,String keywords,List<String> types,boolean includeMain,String targetDirectory,Window window) { }
    @Test void legacyChannelJsonAndFingerprintProjectionStayByteForByteCompatible(){
        var mapper=JsonMapper.builder().build();var old=new LegacyInput("legacy","-1001","tag","word",List.of("DOCUMENT"),false,"D:\\out",new Window("ALL_DAY",null,null,"Asia/Shanghai"));
        String raw=mapper.writeValueAsString(old);var decoded=mapper.readValue(raw,Input.class);assertEquals("CHANNEL",decoded.sourceMode());assertFalse(decoded.postSource());assertEquals(raw,mapper.writeValueAsString(decoded));
        var explicitlyDefaulted=new Input(old.name(),old.source(),old.tags(),old.keywords(),old.types(),old.includeMain(),old.targetDirectory(),old.window(),"CHANNEL",null,null);
        assertEquals(raw,mapper.writeValueAsString(explicitlyDefaulted));
    }
    static Input fixed(Post p,Path root,List<String> types,boolean main){return new Input("fixed",p.mainMessages().getFirst().link(),"","",types,main,root.toString(),new Window("ALL_DAY",null,null,"Asia/Shanghai"),"POST",p.key(),p.mainMessageId());}
    @Test void fixedPostStateAndCommentCursorSurviveRestartWithoutChannelHistory(){AppPaths paths;String id;PostState checkpoint;Input configuration;
        try(var f=new Fixture()){
            paths=f.paths;var p=post(100,true,true);configuration=fixed(p,f.root,List.of("DOCUMENT"),false);
            var paused=f.repo.create("1",UUID.randomUUID().toString(),"post-fp",configuration,CHANNEL,p,20,GUARD);var rule=f.repo.toggle("1",paused.id(),1,true,21,GUARD);id=rule.id();
            assertEquals("HEAD",f.repo.scan(rule).phase());assertNull(f.repo.scan(rule).pending());assertNull(f.repo.scan(rule).cursor());
            var main=f.repo.nextPendingPost(rule);var comments=f.repo.commitPage(rule,main,null,false,List.of(),f.root,22,GUARD).post();checkpoint=f.repo.commitPage(rule,comments,"601",false,List.of(file(601,"post-uid","")),f.root,23,GUARD).post();
            assertThrows(IllegalArgumentException.class,()->f.repo.saveHistoryPage(rule,f.repo.scan(rule),new HistoryPage(CHANNEL,List.of(),"CHANNEL_PAGE",10,null,true,false,List.of()),24,GUARD));
        }
        try(var db=new Database(paths)){
            var downloads=new DownloadRepository(db);var repo=new DownloadRuleRepository(db,downloads);var rule=repo.get("1",id);assertTrue(rule.enabled());assertEquals(configuration,rule.input());assertEquals(checkpoint,repo.nextPendingPost(rule));assertEquals("HEAD",repo.scan(rule).phase());
            assertEquals(1,repo.summary(rule).scanned());assertEquals(1,repo.summary(rule).media());assertEquals(1,downloads.list("1",20,0).size());assertEquals("PAUSED",repo.activeJob(rule).state());
        }
    }
    @Test void editingChannelToFixedPostAppendsNewIdentityWithoutRewritingOldVersionOrTask(){try(var f=new Fixture()){
        var comment=f.comments();var job=f.repo.commitPage(f.rule,comment,"601",false,List.of(file(601,"old","")),f.root,10,GUARD).job();var task=f.downloads.task("1",job.taskId());var versions=f.repo.versions("1",f.rule.id());
        String raw=f.db.read(c->{try(var q=c.createStatement();var r=q.executeQuery("SELECT config_json FROM download_rule_versions WHERE version=1")){r.next();return r.getString(1);}});
        var target=post(90,true,true);var updated=f.repo.update("1",f.rule.id(),f.rule.revision(),fixed(target,f.root,List.of("DOCUMENT"),false),CHANNEL,target,20,GUARD);
        assertFalse(updated.enabled());assertEquals(2,updated.version());assertEquals("HEAD",f.repo.scan(updated).phase());assertEquals(target,f.repo.nextPendingPost(updated).post());assertNull(f.repo.getPost(updated,comment.key()));
        assertEquals(versions.getFirst(),f.repo.versions("1",updated.id()).get(1));assertEquals(task.post(),f.downloads.task("1",task.id()).post());assertEquals(task.items(),f.downloads.task("1",task.id()).items());
        assertEquals(raw,f.db.read(c->{try(var q=c.createStatement();var r=q.executeQuery("SELECT config_json FROM download_rule_versions WHERE version=1")){r.next();return r.getString(1);}}));
    }}
    @Test void fixedSourceSeedIsAtomicAndForeignPostCannotBePublishedOrReset(){try(var f=new Fixture()){
        var p=post(100,true,true);var input=fixed(p,f.root,List.of("DOCUMENT"),false);var calls=new AtomicInteger();
        assertThrows(IllegalArgumentException.class,()->f.repo.create("1",UUID.randomUUID().toString(),"bad",input,CHANNEL,p,20,()->{if(calls.incrementAndGet()==2)throw new IllegalArgumentException("changed session");}));
        assertEquals(1,f.count("download_rules"));assertEquals(1,f.count("download_rule_versions"));assertEquals(1,f.count("download_rule_scans"));assertEquals(0,f.count("download_rule_posts"));
        var paused=f.repo.create("1",UUID.randomUUID().toString(),"good",input,CHANNEL,p,21,GUARD);var rule=f.repo.toggle("1",paused.id(),1,true,22,GUARD);var original=f.repo.nextPendingPost(rule);var other=post(90,true,true);
        assertThrows(IllegalArgumentException.class,()->f.repo.resetPost(rule,original,other,true,23,GUARD));
        var forged=new PostState(rule.id(),rule.version(),original.key(),original.messageId(),other,"MAIN",null,0,true,23,null,original.revision());
        assertThrows(IllegalArgumentException.class,()->f.repo.commitPage(rule,forged,null,false,List.of(),f.root,24,GUARD));assertEquals(original,f.repo.nextPendingPost(rule));assertEquals(0,f.count("download_tasks"));
    }}
}
