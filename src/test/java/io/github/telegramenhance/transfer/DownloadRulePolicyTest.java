package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.downloadrules.DownloadRulePolicy;
import io.github.telegramenhance.downloadrules.DownloadRuleModels.Input;
import io.github.telegramenhance.rules.RuleModels.Window;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.DownloadFileReader;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadRulePolicyTest {
    @TempDir Path directory;
    @Test void ordinaryDownloadsKeepTheirExistingManualExecutionPath() {
        try (var f = new Fixture()) {
            Task task = f.task(1);
            assertTrue(f.policy.allowed("1", task.id(), Instant.parse("2026-10-28T12:00:00Z")));
            assertFalse(f.policy.manualPause("1", task.id(), () -> {}));
            assertFalse(f.policy.manualResume("1", task.id(), () -> {}));
            assertEquals("QUEUED", f.repository.task("1", task.id()).state());
        }
    }
    @Test void admissionUsesFrozenCrossMidnightWindowAndRejectsWrongAccountChangedVersionAndHolds() {
        try (var f = new Fixture()) {
            Task task = f.task(1); f.bind(task, "22:00", "08:00");
            assertTrue(f.policy.allowed("1", task.id(), Instant.parse("2026-10-28T14:00:00Z")));
            assertTrue(f.policy.allowed("1", task.id(), Instant.parse("2026-10-28T23:59:59Z")));
            assertFalse(f.policy.allowed("1", task.id(), Instant.parse("2026-10-29T00:00:00Z")));
            assertFalse(f.policy.allowed("2", task.id(), f.clock.instant()));
            f.sql("UPDATE download_rule_tasks SET held=1"); assertFalse(f.policy.allowed("1", task.id(), f.clock.instant()));
            f.sql("UPDATE download_rule_tasks SET held=0"); f.sql("UPDATE download_rules SET enabled=0");
            assertFalse(f.policy.allowed("1", task.id(), f.clock.instant()));
            f.sql("UPDATE download_rules SET enabled=1,current_version=2"); assertFalse(f.policy.allowed("1", task.id(), f.clock.instant()));
        }
    }
    @Test void manualPauseHoldsTheBindingAndCannotBeUndoneByAutomaticRecovery() {
        try (var f = new Fixture()) {
            Task task = f.task(1); f.bind(task, "22:00", "23:00"); f.repository.next("1");
            assertTrue(f.policy.manualPause("1", task.id(), () -> {}));
            assertEquals("RUNNING", f.repository.task("1", task.id()).state()); assertTrue(f.repository.task("1", task.id()).pauseRequested());
            f.repository.finishState("1", task.id(), "PAUSED", DownloadRulePolicy.RESTART_PAUSE);
            assertFalse(f.policy.autoResume("1", task.id(), () -> {})); assertEquals(1, f.held(task.id()));
            assertTrue(f.policy.manualResume("1", task.id(), () -> {}));
            assertEquals(0, f.held(task.id())); assertEquals("QUEUED", f.repository.task("1", task.id()).state());
        }
    }
    @Test void aLateSessionGuardRollsBackBothTheHoldAndEnqueue() {
        try (var f = new Fixture()) {
            Task task = f.task(1); f.bind(task, "22:00", "23:00");
            f.policy.manualPause("1", task.id(), () -> {}); var before=f.repository.task("1", task.id());
            var calls=new AtomicInteger();
            assertThrows(IllegalArgumentException.class, () -> f.policy.manualResume("1",task.id(),()->{if(calls.incrementAndGet()==2)throw new IllegalArgumentException("changed session");}));
            assertEquals(before,f.repository.task("1",task.id())); assertEquals(1,f.held(task.id()));
        }
    }
    @Test void manualResumeCannotBypassTheRuleWindowOrClearItsFailedEvidence() {
        try (var f = new Fixture()) {
            Task task=f.task(1);f.bind(task,"22:00","23:00");f.policy.manualPause("1",task.id(),()->{});
            f.repository.finishState("1",task.id(),"FAILED","retained failure");f.clock.now=Instant.parse("2026-10-28T15:00:00Z");
            var before=f.repository.task("1",task.id());
            assertThrows(DownloadRulePolicy.Blocked.class,()->f.policy.manualResume("1",task.id(),()->{}));
            assertEquals(before,f.repository.task("1",task.id()));assertEquals(1,f.held(task.id()));
        }
    }
    @Test void automaticRecoveryAdmitsOnlySafeRestartOrMediaBoundaryPauses() {
        try (var f = new Fixture()) {
            Task task=f.task(1);f.bind(task,"22:00","23:00");
            f.repository.finishState("1",task.id(),"FAILED",DownloadRulePolicy.RESTART_PAUSE);assertFalse(f.policy.autoResume("1",task.id(),()->{}));
            f.repository.finishState("1",task.id(),"PAUSED","explicit user or unknown pause");assertFalse(f.policy.autoResume("1",task.id(),()->{}));
            f.repository.finishState("1",task.id(),"PAUSED",DownloadRulePolicy.WINDOW_PAUSE);
            f.clock.now=Instant.parse("2026-10-28T15:00:00Z");assertFalse(f.policy.autoResume("1",task.id(),()->{}));
            f.clock.now=Instant.parse("2026-10-29T14:00:00Z");assertTrue(f.policy.autoResume("1",task.id(),()->{}));
            var queued=f.repository.task("1",task.id());assertFalse(f.policy.autoResume("1",task.id(),()->{}));assertEquals(queued,f.repository.task("1",task.id()));
        }
    }
    @Test void manualTakeoverBypassesRuleConstraintsButIsNeverAutomaticallyResumed() {
        try (var f=new Fixture()) {
            Task task=f.task(1);f.bind(task,"22:00","23:00");f.sql("UPDATE download_rule_tasks SET manual=1,held=1");f.sql("UPDATE download_rules SET enabled=0,current_version=2");
            f.clock.now=Instant.parse("2026-10-28T15:00:00Z");f.repository.finishState("1",task.id(),"PAUSED",DownloadRulePolicy.RESTART_PAUSE);
            assertTrue(f.policy.allowed("1",task.id(),f.clock.instant()));assertFalse(f.policy.autoResume("1",task.id(),()->{}));
            assertTrue(f.policy.manualResume("1",task.id(),()->{}));assertEquals("QUEUED",f.repository.task("1",task.id()).state());
        }
    }
    @Test void theTaskServiceUsesTransactionalRulePauseAndCannotResumeOutsideTheWindow() {
        try (var f=new Fixture()) {
            Task task=f.task(1);f.bind(task,"22:00","23:00");
            var service=new DownloadTaskService(mock(DownloadSourceService.class),mock(ResourceService.class),f.reader,f.repository,new DownloadPaths(f.paths),f.telegram,mock(DownloadEngine.class),f.policy);
            assertEquals("PAUSED",service.pause(task.id()).state());assertEquals(1,f.held(task.id()));
            f.clock.now=Instant.parse("2026-10-28T15:00:00Z");assertThrows(DownloadRulePolicy.Blocked.class,()->service.resume(task.id()));assertEquals(1,f.held(task.id()));
        }
    }
    @Test void aMediaStartedBeforeTheWindowEndsCompletesButTheNextMediaWaits() throws Exception {
        try (var f=new Fixture()) {
            Task task=f.task(2);f.bind(task,"22:00","23:00");
            when(f.reader.chunk(anyString(),anyLong(),anyInt(),anyString(),anyLong(),anyLong(),anyInt())).thenAnswer(call->{f.clock.now=Instant.parse("2026-10-28T15:00:00Z");return new byte[(Integer)call.getArgument(6)];});
            f.engine=new DownloadEngine(f.repository,f.reader,f.store,f.telegram,f.paths,f.policy);
            var paused=f.await(task.id(),"PAUSED");assertEquals(DownloadRulePolicy.WINDOW_PAUSE,paused.error());assertEquals(1,f.repository.view(paused).completed());
            assertEquals("COMPLETED",paused.items().get(0).state());assertEquals("QUEUED",paused.items().get(1).state());
            verify(f.reader,times(1)).resolve(any(),any(FrozenFile.class),anyLong());
            var successfulItem=paused.items().getFirst();
            f.clock.now=Instant.parse("2026-10-29T14:00:00Z");assertTrue(f.policy.autoResume("1",task.id(),()->{}));
            var done=f.await(task.id(),"COMPLETED");assertEquals(2,f.repository.view(done).completed());assertEquals(successfulItem,done.items().getFirst());
            verify(f.reader,times(2)).resolve(any(),any(FrozenFile.class),anyLong());
        }
    }
    @Test void normalShutdownOfAnActiveRuleRetainsItsSnapshotAndCanSafelyResumeAfterStartup() throws Exception {
        try(var f=new Fixture()) {
            Task task=f.task(1);f.bind(task,"22:00","23:00");
            when(f.reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenReturn(new DownloadFileReader.Status(1,false,true,true));
            f.engine=new DownloadEngine(f.repository,f.reader,f.store,f.telegram,f.paths,f.policy);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while(!f.repository.task("1",task.id()).items().getFirst().state().equals("DOWNLOADING")&&System.nanoTime()<deadline)Thread.sleep(20);
            assertEquals("DOWNLOADING",f.repository.task("1",task.id()).items().getFirst().state());
            f.engine.close();var paused=f.repository.task("1",task.id());
            assertEquals("PAUSED",paused.state());assertEquals(DownloadRulePolicy.SHUTDOWN_PAUSE,paused.error());assertEquals(0,f.held(task.id()));
            assertEquals(task.post(),paused.post());assertEquals(task.items().getFirst().file(),paused.items().getFirst().file());assertEquals(0,paused.items().getFirst().writtenBytes());
            var restarted=new DownloadRepository(f.db);assertEquals(paused,restarted.task("1",task.id()));
            assertTrue(f.policy.autoResume("1",task.id(),()->{}));
            when(f.reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenReturn(new DownloadFileReader.Status(37,true,false,true));
            f.engine=new DownloadEngine(f.repository,f.reader,f.store,f.telegram,f.paths,f.policy);
            assertEquals(1,f.repository.view(f.await(task.id(),"COMPLETED")).completed());
            verify(f.reader,times(2)).resolve(any(),any(FrozenFile.class),anyLong());
        }
    }
    private final class Fixture implements AutoCloseable {
        final AppPaths paths=new AppPaths(directory.resolve(UUID.randomUUID().toString()));final Database db=new Database(paths);final DownloadRepository repository=new DownloadRepository(db);
        final MutableClock clock=new MutableClock();final DownloadRulePolicy policy=new DownloadRulePolicy(db,clock);final DownloadFileReader reader=mock(DownloadFileReader.class);final TelegramService telegram=mock(TelegramService.class);
        final DownloadFileStore store=new DownloadFileStore(new DownloadPaths(paths),repository);DownloadEngine engine;
        Fixture(){when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);
            when(reader.resolve(any(),any(FrozenFile.class),anyLong())).thenAnswer(call->new DownloadFileReader.Resolved(call.getArgument(1),7));
            when(reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenReturn(new DownloadFileReader.Status(37,true,false,true));
            when(reader.chunk(anyString(),anyLong(),anyInt(),anyString(),anyLong(),anyLong(),anyInt())).thenAnswer(call->new byte[(Integer)call.getArgument(6)]);
        }
        Task task(int count){var first=DownloadPersistenceTest.file(37,"first.bin");var second=new FrozenFile(new Media("1:-1001:100:1",new MessageRef("-1001","100",""),"MAIN","DOCUMENT","second.bin",37,""),"stable-second",37,"second.bin");return repository.create("1",UUID.randomUUID().toString(),UUID.randomUUID().toString(),DownloadPersistenceTest.post("title"),directory.resolve("out"),count==1?List.of(first):List.of(first,second));}
        void bind(Task task,String start,String end){String rule=UUID.randomUUID().toString();String config=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(new Input("sample","-1001","","",List.of("DOCUMENT"),true,task.targetRoot(),new Window("WINDOW",start,end,"Asia/Shanghai")));
            db.write(c->{try(var q=c.prepareStatement("INSERT INTO download_rules VALUES(?, '1', ?, 'fingerprint',1,1,1,0,1,1)")){q.setString(1,rule);q.setString(2,UUID.randomUUID().toString());q.executeUpdate();}
                try(var q=c.prepareStatement("INSERT INTO download_rule_versions VALUES(?,1,?,'-1001','channel',1)")){q.setString(1,rule);q.setString(2,config);q.executeUpdate();}
                try(var q=c.prepareStatement("INSERT INTO download_rule_tasks VALUES(?,?,'1',1,?,0,0,NULL,1)")){q.setString(1,task.id());q.setString(2,rule);q.setString(3,config);q.executeUpdate();}return null;});}
        int held(String task){return db.read(c->{try(var q=c.prepareStatement("SELECT held FROM download_rule_tasks WHERE task_id=?")){q.setString(1,task);try(var r=q.executeQuery()){assertTrue(r.next());return r.getInt(1);}}});}
        void sql(String sql){db.write(c->{try(var q=c.createStatement()){q.executeUpdate(sql);}return null;});}
        Task await(String id,String state)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);Task task;do{task=repository.task("1",id);if(state.equals(task.state()))return task;Thread.sleep(20);}while(System.nanoTime()<end);throw new AssertionError("Expected "+state+", got "+task.state()+" / "+task.error());}
        public void close(){if(engine!=null)engine.close();db.close();}
    }
    private static final class MutableClock extends Clock {
        volatile Instant now=Instant.parse("2026-10-28T14:59:59Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
}
