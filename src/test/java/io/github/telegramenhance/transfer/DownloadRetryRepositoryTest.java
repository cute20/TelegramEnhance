package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.downloadrules.DownloadRuleModels.Input;
import io.github.telegramenhance.downloadrules.DownloadRulePolicy;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.rules.RuleModels.Window;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DownloadRetryRepositoryTest {
    @TempDir Path directory;

    @Test void cacheOwnershipDoesNotImposeALifetimeDownloadCountLimit(){try(var f=new Fixture()){
        for(int id=1;id<=140;id++)f.repo.own("1","owned-"+id,id);
        assertEquals(140,f.count("download_cache_owned"));
        assertEquals(140,f.repo.owners("1",150).size());assertTrue(f.repo.owners("2",150).isEmpty());
        f.repo.own("1","owned-140",240);
        assertEquals(140,f.count("download_cache_owned"));
        assertEquals(240,f.repo.owners("1",150).getLast().fileId());
    }}

    @Test void completedCacheIsCleanableAheadOfEightOlderPausedReferences()throws Exception{try(var f=new Fixture()){
        var pending=new ArrayList<String>();
        for(int id=1;id<=8;id++){
            var task=f.task("1");String unique=task.items().getFirst().file().uniqueId();pending.add(unique);
            f.repo.own("1",unique,id);f.repo.pause("1",task.id());
        }
        var completed=f.task("1");var item=completed.items().getFirst();String completedUnique=item.file().uniqueId();
        var store=new DownloadFileStore(new DownloadPaths(f.paths),f.repo);
        store.write(completed,item,(offset,count)->new byte[count],()->{});
        f.repo.finishState("1",completed.id(),"COMPLETED",null);f.repo.own("1",completedUnique,99);
        var owners=f.repo.owners("1",8);
        assertEquals(8,owners.size());assertEquals(completedUnique,owners.getFirst().uniqueId());
        assertFalse(f.repo.pendingReference("1",completedUnique));
        for(String unique:pending){assertTrue(f.repo.owned("1",unique));assertTrue(f.repo.pendingReference("1",unique));}
        assertTrue(f.repo.owners("2",8).isEmpty());
        assertEquals(1,f.count("download_completion_log"));
    }}

    @Test void delayedRetryDoesNotBlockOtherTasksAndBecomesEligibleExactlyAtItsDeadline(){
        try(var f=new Fixture()){
            var task=f.running();long started=f.clock.millis();assertTrue(f.repo.scheduleRetry("1",task.id(),"temporary network read",0));
            var queued=f.repo.task("1",task.id());assertEquals("QUEUED",queued.state());assertFalse(queued.pauseRequested());assertEquals("temporary network read",queued.error());
            assertEquals(new Retry(1,started+300_000),f.repo.view(queued).retry());assertNull(f.repo.next("1"));
            var other=f.task("1");assertEquals(other.id(),f.repo.next("1").id());f.repo.finishState("1",other.id(),"COMPLETED",null);
            f.clock.advance(299_998);assertNull(f.repo.next("1"));f.clock.advance(1);assertEquals(task.id(),f.repo.next("1").id());
            assertNull(f.repo.retry("1",task.id()),"A running attempt does not expose a future retry in its UI projection");assertEquals(1,f.attempt(task.id()));
        }
    }
    @Test void retriesContinueWithoutAnAttemptLimitAndBackOffToHalfAnHourAfterFiveMinutes(){
        try(var f=new Fixture()){
            var task=f.running();var snapshot=task.items();
            long[] delays={300_000L,1_800_000L};
            for(int attempt=1;attempt<=150;attempt++){
                long started=f.clock.millis();assertTrue(f.repo.scheduleRetry("1",task.id(),"still unavailable",0));
                long delay=delays[Math.min(attempt-1,delays.length-1)];
                assertEquals(new Retry(attempt,started+delay),f.repo.retry("1",task.id()));
                f.clock.advance(delay-1);assertNull(f.repo.next("1"));f.clock.advance(1);assertEquals(task.id(),f.repo.next("1").id());
                assertEquals(snapshot,f.repo.task("1",task.id()).items());
            }
            assertEquals(150,f.attempt(task.id()));f.repo.finishState("1",task.id(),"COMPLETED",null);assertEquals(0,f.retryCount());
        }
    }
    @Test void serverMinimumDelayIsRespectedAndExtremeDelaysStayRepresentableByTheUi(){
        try(var f=new Fixture()){
            var task=f.running();long started=f.clock.millis();assertTrue(f.repo.scheduleRetry("1",task.id(),"rate limited",900_000));
            assertEquals(started+900_000,f.repo.retry("1",task.id()).nextAttemptAt());f.clock.advance(900_000);assertEquals(task.id(),f.repo.next("1").id());
            assertTrue(f.repo.scheduleRetry("1",task.id(),"ordinary timeout",1));assertEquals(f.clock.millis()+1_800_000,f.repo.retry("1",task.id()).nextAttemptAt());
            f.clock.advance(1_800_000);f.repo.next("1");assertTrue(f.repo.scheduleRetry("1",task.id(),"huge native retry-after",Long.MAX_VALUE));
            assertEquals(8_640_000_000_000_000L,f.repo.retry("1",task.id()).nextAttemptAt());assertNull(f.repo.next("1"));
        }
    }
    @Test void bothOrdersOfManualPauseAndRetryRegistrationKeepTheTaskPaused(){
        try(var f=new Fixture()){
            var first=f.running();f.repo.pause("1",first.id());assertFalse(f.repo.scheduleRetry("1",first.id(),"late timeout",0));
            assertPaused(f,first.id());f.repo.finishState("1",first.id(),"FAILED","late worker error");assertPaused(f,first.id());
            var second=f.running();assertTrue(f.repo.scheduleRetry("1",second.id(),"timeout",0));f.repo.pause("1",second.id());assertPaused(f,second.id());
            f.repo.finishState("1",second.id(),"COMPLETED",null);assertPaused(f,second.id());
            f.clock.advance(3_600_000);assertNull(f.repo.next("1"));assertEquals(0,f.retryCount());
            f.repo.resume("1",second.id());assertEquals(second.id(),f.repo.next("1").id());assertTrue(f.repo.scheduleRetry("1",second.id(),"new attempt",0));assertEquals(new Retry(1,f.clock.millis()+300_000),f.repo.retry("1",second.id()));
        }
    }
    @Test void concurrentManualPausesAlwaysWinOverRetryRegistration()throws Exception{
        try(var f=new Fixture();var workers=Executors.newFixedThreadPool(2)){
            for(int turn=0;turn<20;turn++){
                var task=f.running();var start=new CountDownLatch(1);
                var retry=workers.submit(()->{start.await();return f.repo.scheduleRetry("1",task.id(),"interrupted read",0);});
                var pause=workers.submit(()->{start.await();return f.repo.pause("1",task.id());});
                start.countDown();retry.get(3,TimeUnit.SECONDS);pause.get(3,TimeUnit.SECONDS);
                // A worker that was already stopping may still publish its terminal callback.
                f.repo.finishState("1",task.id(),"FAILED","late worker callback");assertPaused(f,task.id());assertEquals(0,f.retryCount());
            }
        }
    }
    @Test void anExplicitResumeAfterRepeatedFailuresRestartsAtFiveMinutesAndPausedTasksNeverWakeThemselves(){
        try(var f=new Fixture()){
            var task=f.running();assertTrue(f.repo.scheduleRetry("1",task.id(),"first timeout",0));
            f.clock.advance(300_000);assertEquals(task.id(),f.repo.next("1").id());
            assertTrue(f.repo.scheduleRetry("1",task.id(),"second timeout",0));assertEquals(2,f.repo.retry("1",task.id()).attempt());
            f.repo.pause("1",task.id());f.clock.advance(TimeUnit.DAYS.toMillis(1));assertNull(f.repo.next("1"));assertPaused(f,task.id());
            f.repo.resume("1",task.id());assertEquals(task.id(),f.repo.next("1").id());
            assertTrue(f.repo.scheduleRetry("1",task.id(),"timeout after explicit resume",0));
            assertEquals(new Retry(1,f.clock.millis()+300_000),f.repo.retry("1",task.id()));
        }
    }
    @Test void terminalTasksAndDeletedTasksNeverBecomeAutomaticRetryCandidates(){
        for(String terminal:List.of("FAILED","PAUSED","COMPLETED"))try(var f=new Fixture()){
            var task=f.running();assertTrue(f.repo.scheduleRetry("1",task.id(),"timeout",0));f.clock.advance(300_000);f.repo.next("1");
            f.repo.finishState("1",task.id(),terminal,"terminal reason");assertEquals(0,f.retryCount());
            assertFalse(f.repo.scheduleRetry("1",task.id(),"old exception",0));assertEquals(terminal,f.repo.task("1",task.id()).state());
        }
        try(var f=new Fixture()){
            var task=f.running();f.repo.pause("1",task.id());f.repo.finishState("1",task.id(),"PAUSED",null);f.insertRetry(task,7,f.clock.millis()+30_000);
            assertFalse(f.repo.deletePausedStandalone("1",task.id(),()->{}));assertEquals(0,f.retryCount());
            assertFalse(f.repo.scheduleRetry("1",task.id(),"old worker exception",0));f.repo.finishState("1",task.id(),"FAILED","late callback");
            assertEquals("PAUSED",f.repo.byOperation("1",task.operationId()).state());assertTrue(f.repo.list("1",50,0).isEmpty());assertNull(f.repo.next("1"));
        }
    }
    @Test void batchPauseAndExplicitResumeClearAllTheirRetrySchedules(){
        try(var f=new Fixture()){
            var files=List.of(file("1",100),file("1",101));
            var snapshot=new DownloadBatchModels.Snapshot(UUID.randomUUID().toString(),1,"channel",List.of(),List.of(),List.of("DOCUMENT"),true,f.output.toString(),2,2,74);
            var batch=f.repo.createBatch("1",UUID.randomUUID().toString(),"batch",snapshot,f.output,List.of(new DownloadBatchModels.Job(post("1",100),List.of(files.get(0))),new DownloadBatchModels.Job(post("1",101),List.of(files.get(1)))));
            var first=f.repo.next("1");assertTrue(f.repo.scheduleRetry("1",first.id(),"timeout",0));var second=f.repo.next("1");assertTrue(f.repo.scheduleRetry("1",second.id(),"timeout",0));
            assertEquals(2,f.retryCount());assertEquals("PAUSED",f.repo.pauseBatch("1",batch.id()).state());assertEquals(0,f.retryCount());
            for(var id:batch.taskIds()){assertPaused(f,id);f.insertRetry(f.repo.task("1",id),5,f.clock.millis()+300_000);}
            assertEquals("QUEUED",f.repo.resumeBatch("1",batch.id()).state());assertEquals(0,f.retryCount());assertNotNull(f.repo.next("1"));
        }
    }
    @Test void retriesNeverResetSavedBytesFrozenFilesOutputPlansReceiptsOrCacheOwnership()throws Exception{
        try(var f=new Fixture()){
            var task=f.repo.create("1",UUID.randomUUID().toString(),"two-files",post("1",100),f.output,List.of(file("1",100),file("1",101)),DownloadModels.KEEP_DUPLICATES);
            task=f.repo.next("1");var store=new DownloadFileStore(new DownloadPaths(f.paths),f.repo);store.write(task,task.items().getFirst(),(offset,count)->new byte[count],()->{});
            var unfinished=task.items().getLast();String hash="b".repeat(64);f.repo.progress("1",task.id(),unfinished.id(),"WRITING",37,37,hash,unfinished.relativeFile());
            var plan=f.repo.outputPlan(task,unfinished,hash);f.repo.own("1",unfinished.file().uniqueId(),7);var before=f.repo.task("1",task.id());
            assertTrue(f.repo.scheduleRetry("1",task.id(),"transient source read",0));var after=f.repo.task("1",task.id());
            assertEquals(before.items(),after.items());assertEquals(before.post(),after.post());assertEquals(before.targetRoot(),after.targetRoot());assertEquals(plan,after.items().getLast().plan());
            assertEquals(1,f.count("download_history"));assertEquals(1,f.count("download_completion_log"));assertEquals(1,f.count("download_file_receipts"));assertEquals(1,f.repo.owners("1",8).size());
            assertArrayEquals(new byte[37],Files.readAllBytes(f.output.resolve("file-100.bin")));f.clock.advance(300_000);assertEquals(before.items(),f.repo.next("1").items());
        }
    }
    @Test void resumingPausedBatchChildrenDoesNotBringForwardAnotherChildsServerBackoff(){
        try(var f=new Fixture()){
            var snapshot=new DownloadBatchModels.Snapshot(UUID.randomUUID().toString(),1,"channel",List.of(),List.of(),List.of("DOCUMENT"),true,f.output.toString(),2,2,74);
            var batch=f.repo.createBatch("1",UUID.randomUUID().toString(),"mixed-batch",snapshot,f.output,List.of(
                new DownloadBatchModels.Job(post("1",100),List.of(file("1",100))),new DownloadBatchModels.Job(post("1",101),List.of(file("1",101)))));
            var waiting=f.repo.next("1");assertTrue(f.repo.scheduleRetry("1",waiting.id(),"rate limited",600_000));var retry=f.repo.retry("1",waiting.id());
            var paused=f.repo.next("1");f.repo.pause("1",paused.id());f.repo.finishState("1",paused.id(),"PAUSED",null);
            f.repo.resumeBatch("1",batch.id());assertEquals(retry,f.repo.retry("1",waiting.id()));
            assertEquals(paused.id(),f.repo.next("1").id());f.repo.finishState("1",paused.id(),"COMPLETED",null);
            f.clock.advance(599_999);assertNull(f.repo.next("1"));f.clock.advance(1);assertEquals(waiting.id(),f.repo.next("1").id());
        }
    }
    @Test void retryRowsAndQueueClaimsAreAccountScoped(){
        try(var f=new Fixture()){
            var one=f.running();assertTrue(f.repo.scheduleRetry("1",one.id(),"timeout",0));var second=f.task("2");assertEquals(second.id(),f.repo.next("2").id());assertTrue(f.repo.scheduleRetry("2",second.id(),"timeout",0));
            assertThrows(IllegalArgumentException.class,()->f.repo.scheduleRetry("2",one.id(),"foreign",0));assertNull(f.repo.retry("2",one.id()));assertEquals(2,f.retryCount());
            f.repo.pause("1",one.id());assertEquals(1,f.retryCount());f.clock.advance(300_000);assertNull(f.repo.next("1"));assertEquals(second.id(),f.repo.next("2").id());
        }
    }
    @Test void restartRetainsOnlyPreviouslyScheduledAutomaticRetries()throws Exception{
        var paths=new AppPaths(directory.resolve("restart-data"));var clock=new MutableClock();String waiting,active,ordinaryRunning,ordinaryQueued,failed,paused,completed;
        Retry waitingRetry;
        try(var db=new Database(paths)){
            var repo=new DownloadRepository(db,clock);var one=create(repo,"1",100);waiting=one.id();repo.next("1");repo.scheduleRetry("1",waiting,"rate limited",600_000);waitingRetry=repo.retry("1",waiting);
            active=create(repo,"1",101).id();repo.next("1");repo.scheduleRetry("1",active,"timeout",0);clock.advance(300_000);assertEquals(active,repo.next("1").id());
            ordinaryRunning=create(repo,"1",102).id();assertEquals(ordinaryRunning,repo.next("1").id());ordinaryQueued=create(repo,"1",103).id();
            failed=create(repo,"1",104).id();repo.finishState("1",failed,"FAILED","old failure");paused=create(repo,"1",105).id();repo.pause("1",paused);
            completed=create(repo,"1",106).id();repo.finishState("1",completed,"COMPLETED",null);
        }
        try(var db=new Database(paths)){
            var repo=new DownloadRepository(db,clock);assertEquals("QUEUED",repo.task("1",waiting).state());assertEquals(waitingRetry,repo.retry("1",waiting));
            assertEquals("QUEUED",repo.task("1",active).state());assertEquals(1,repo.retry("1",active).attempt());
            for(var id:List.of(ordinaryRunning,ordinaryQueued,paused))assertEquals("PAUSED",repo.task("1",id).state());
            assertEquals("FAILED",repo.task("1",failed).state());assertEquals("COMPLETED",repo.task("1",completed).state());assertEquals(active,repo.next("1").id());
            repo.finishState("1",active,"COMPLETED",null);assertNull(repo.next("1"));clock.advance(300_000);assertEquals(waiting,repo.next("1").id());
        }
    }
    @Test void ruleDisableVersionWindowAndUserHoldAreCheckedBeforeRegisteringOrClaimingARetry(){
        for(String block:List.of("disabled","version","window","held"))try(var f=new Fixture()){
            var task=f.running();String rule=f.bind(task,block.equals("window")?new Window("WINDOW","22:00","23:00","Asia/Shanghai"):new Window("ALL_DAY",null,null,"Asia/Shanghai"));
            f.block(rule,task.id(),block);assertFalse(f.repo.scheduleRetry("1",task.id(),"network timeout",0));assertEquals("PAUSED",f.repo.task("1",task.id()).state());assertEquals(0,f.retryCount());
        }
        try(var f=new Fixture()){
            var task=f.running();String rule=f.bind(task,new Window("ALL_DAY",null,null,"Asia/Shanghai"));assertTrue(f.repo.scheduleRetry("1",task.id(),"timeout",0));
            f.block(rule,task.id(),"disabled");f.clock.advance(300_000);var ordinary=f.task("1");assertEquals(ordinary.id(),f.repo.next("1").id());
            assertEquals("PAUSED",f.repo.task("1",task.id()).state());assertEquals(DownloadRulePolicy.RULE_PAUSE,f.repo.task("1",task.id()).error());assertEquals(0,f.retryCount());
        }
    }
    @Test void retryProjectionNeverAttachesAQueueScheduleToAnOldRunningSnapshot(){
        try(var f=new Fixture()){
            var running=f.running();assertTrue(f.repo.scheduleRetry("1",running.id(),"timeout",0));assertNull(f.repo.view(running).retry());
            var queued=f.repo.task("1",running.id());assertNotNull(f.repo.view(queued).retry());f.clock.advance(300_000);f.repo.next("1");assertNull(f.repo.view(queued).retry());
            var json=JsonMapper.builder().build();var current=f.repo.view(f.repo.task("1",running.id()));String old=json.writeValueAsString(current).replace(",\"retry\":null","");
            assertNull(json.readValue(old,TaskView.class).retry());
        }
    }
    private static void assertPaused(Fixture f,String id){var task=f.repo.task("1",id);assertEquals("PAUSED",task.state());assertTrue(task.pauseRequested());assertNull(f.repo.retry("1",id));}
    private Task create(DownloadRepository repo,String account,int id){return repo.create(account,UUID.randomUUID().toString(),UUID.randomUUID().toString(),post(account,id),directory.resolve("output"),List.of(file(account,id)),DownloadModels.KEEP_DUPLICATES);}
    private static FrozenFile file(String account,int id){return new FrozenFile(new Media(account+":-1001:"+id+":0",new MessageRef("-1001",Integer.toString(id),null),"MAIN","DOCUMENT","file-"+id+".bin",37,""),"unique-"+id,37,"file-"+id+".bin");}
    private static Post post(String account,int id){var media=file(account,id).media();return new Post(account+":-1001:"+id,account,"-1001","channel",Integer.toString(id),"post","","0",List.of(media.message()),List.of(media),null,null,List.of(),false,"PARTIAL",List.of());}
    private final class Fixture implements AutoCloseable{
        final AppPaths paths=new AppPaths(directory.resolve(UUID.randomUUID().toString()));final MutableClock clock=new MutableClock();final Database db=new Database(paths);final DownloadRepository repo=new DownloadRepository(db,clock);final Path output=directory.resolve("output").toAbsolutePath();int sequence=100;
        Task task(String account){clock.advance(1);return create(repo,account,sequence++);}
        Task running(){var task=task("1");assertEquals(task.id(),repo.next("1").id());return repo.task("1",task.id());}
        long count(String table){return db.read(c->{try(var rows=c.createStatement().executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();return rows.getLong(1);}});}
        long retryCount(){return count("download_task_retries");}
        int attempt(String id){return db.read(c->{try(var q=c.prepareStatement("SELECT attempt FROM download_task_retries WHERE task_id=?")){q.setString(1,id);try(var rows=q.executeQuery()){assertTrue(rows.next());return rows.getInt(1);}}});}
        void insertRetry(Task task,int attempt,long next){db.write(c->{try(var q=c.prepareStatement("INSERT INTO download_task_retries VALUES(?,?,?,?)")){q.setString(1,task.id());q.setString(2,task.accountId());q.setInt(3,attempt);q.setLong(4,next);q.executeUpdate();}return null;});}
        String bind(Task task,Window window){String id=UUID.randomUUID().toString();String config=JsonMapper.builder().build().writeValueAsString(new Input("rule","-1001","","",List.of("DOCUMENT"),true,task.targetRoot(),window));
            db.write(c->{try(var q=c.prepareStatement("INSERT INTO download_rules VALUES(?,'1',?,'fingerprint',1,1,1,0,1,1)")){q.setString(1,id);q.setString(2,UUID.randomUUID().toString());q.executeUpdate();}
                try(var q=c.prepareStatement("INSERT INTO download_rule_versions VALUES(?,1,?,'-1001','channel',1)")){q.setString(1,id);q.setString(2,config);q.executeUpdate();}
                try(var q=c.prepareStatement("INSERT INTO download_rule_tasks VALUES(?,?,'1',1,?,0,0,NULL,1)")){q.setString(1,task.id());q.setString(2,id);q.setString(3,config);q.executeUpdate();}return null;});return id;
        }
        void block(String rule,String task,String kind){db.write(c->{
            try(var q=c.prepareStatement(switch(kind){case "disabled"->"UPDATE download_rules SET enabled=0 WHERE id=?";case "version"->"UPDATE download_rules SET current_version=2 WHERE id=?";case "held"->"UPDATE download_rule_tasks SET held=1 WHERE task_id=?";default->"SELECT 1 WHERE ? IS NOT NULL";})){q.setString(1,kind.equals("held")?task:rule);q.execute();}return null;});}
        public void close(){db.close();}
    }
    private static final class MutableClock extends Clock{
        long now=Instant.parse("2026-11-04T00:00:00Z").toEpochMilli();void advance(long millis){now=Math.addExact(now,millis);}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}
    }
}
