package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.DownloadFileReader;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadAutomaticRetryTest {
    @TempDir Path directory;

    @Test void activeOwnedStallIsCancelledAndEveryRetryActuallyRequestsTheFileAgain()throws Exception{
        for(boolean cancellationFails:List.of(false,true))try(var f=new Fixture()){
            var starts=new AtomicInteger();var nanos=new AtomicLong();f.nanoTime=()->nanos.getAndAdd(TimeUnit.MINUTES.toNanos(2));
            doAnswer(call->{starts.incrementAndGet();return null;}).when(f.reader).start(anyString(),anyLong(),anyInt(),anyString(),anyLong());
            when(f.reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenAnswer(call->{boolean complete=starts.get()>=2;return new DownloadFileReader.Status(complete?37:0,complete,!complete,true);});
            if(cancellationFails)doThrow(new DownloadFileReader.TemporarilyUnavailable("取消回执未确认")).when(f.reader).cancel(anyString(),anyLong(),anyInt(),anyString(),anyLong());
            var before=f.task();f.repository.own("1","stable-unique",7);f.start();var waiting=f.awaitRetry(before.id());
            assertEquals(1,starts.get());assertEquals(0,waiting.items().getFirst().downloadedBytes());assertTrue(waiting.error().contains("两分钟"));
            verify(f.reader).cancel("1",1,7,"stable-unique",37);verify(f.reader,never()).release("1",1,7,"stable-unique",true);
            f.clock.advance(300_000);var done=f.await(before.id(),"COMPLETED");assertEquals(2,starts.get());assertEquals(before.items().getFirst().file(),done.items().getFirst().file());
            assertEquals(37,done.items().getFirst().writtenBytes());assertNull(f.repository.view(done).retry());
        }
    }

    @Test void anAlreadyActiveUnownedCacheIsRequestedWithoutBeingClaimedCancelledOrDeleted()throws Exception{
        try(var f=new Fixture()){
            when(f.reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenReturn(new DownloadFileReader.Status(5,false,true,true));
            var nanos=new AtomicLong();f.nanoTime=()->nanos.getAndAdd(TimeUnit.MINUTES.toNanos(2));var task=f.task();f.start();f.awaitRetry(task.id());
            verify(f.reader).start("1",1,7,"stable-unique",37);assertFalse(f.repository.owned("1","stable-unique"));
            verify(f.reader,never()).cancel(anyString(),anyLong(),anyInt(),anyString(),anyLong());verify(f.reader,never()).release(anyString(),anyLong(),anyInt(),anyString(),anyBoolean());
        }
    }

    @Test void fallingAndRecoveringToTheSameByteHighWaterDoesNotRestartTheIdleClock()throws Exception{
        try(var f=new Fixture()){
            var calls=new AtomicInteger();var nanos=new AtomicLong();f.nanoTime=nanos::get;
            when(f.reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenAnswer(call->{int read=calls.getAndIncrement();if(read>=2)nanos.addAndGet(TimeUnit.SECONDS.toNanos(60));return new DownloadFileReader.Status(read==2?0:5,false,true,true);});
            var task=f.task();f.start();f.awaitRetry(task.id());assertEquals(4,calls.get());assertEquals(TimeUnit.MINUTES.toNanos(2),nanos.get());
        }
    }

    @Test void aPauseDuringStallCancellationWinsOverAutomaticRetryEvenWhenCancelFails()throws Exception{
        try(var f=new Fixture()){
            var task=f.task();f.repository.own("1","stable-unique",7);var nanos=new AtomicLong();f.nanoTime=()->nanos.getAndAdd(TimeUnit.MINUTES.toNanos(2));
            when(f.reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenReturn(new DownloadFileReader.Status(5,false,true,true));
            doAnswer(call->{f.repository.pause("1",task.id());throw new DownloadFileReader.TemporarilyUnavailable("取消回执未确认");}).when(f.reader).cancel(anyString(),anyLong(),anyInt(),anyString(),anyLong());
            f.start();var paused=f.await(task.id(),"PAUSED");assertTrue(paused.pauseRequested());assertNull(f.repository.view(paused).retry());
            f.clock.advance(TimeUnit.DAYS.toMillis(1));assertNull(f.repository.next("1"));verify(f.reader,times(1)).start("1",1,7,"stable-unique",37);
        }
    }

    static FrozenFile selectedFile(int id){var media=DownloadBatchServiceTest.media(id,false);return new FrozenFile(media,"separate-"+id,media.size(),media.fileName());}
    @Test void aSerialStalledFileDoesNotPreventTheOtherSelectedFilesFromCompletingThisPass()throws Exception{
        try(var f=new Fixture()){
            var first=selectedFile(101);var second=selectedFile(102);var starts=new AtomicInteger();var nanos=new AtomicLong();f.nanoTime=()->nanos.getAndAdd(TimeUnit.MINUTES.toNanos(2));
            when(f.reader.resolve(any(),any(),anyLong())).thenAnswer(call->{FrozenFile file=call.getArgument(1);return new DownloadFileReader.Resolved(file,Integer.parseInt(file.media().message().messageId()));});
            when(f.reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenAnswer(call->{boolean complete=(Integer)call.getArgument(2)==102;return new DownloadFileReader.Status(complete?37:0,complete,!complete,true);});
            doAnswer(call->{if((Integer)call.getArgument(2)==101)starts.incrementAndGet();return null;}).when(f.reader).start(anyString(),anyLong(),anyInt(),anyString(),anyLong());
            var task=f.repository.create("1",UUID.randomUUID().toString(),"one-pass",DownloadPersistenceTest.post("title"),directory.resolve("output"),List.of(first,second));f.start();var waiting=f.awaitRetry(task.id());
            assertEquals(1,waiting.completed());assertEquals("DOWNLOADING",f.repository.task("1",task.id()).items().getFirst().state());assertEquals("COMPLETED",f.repository.task("1",task.id()).items().getLast().state());assertEquals(1,starts.get(),"a pass must not immediately retry its stalled file");
            verify(f.reader).chunk("1",1,102,"separate-102",37,0,37);
        }
    }

    @Test void aManualPauseAfterATemporaryFileFailureStopsLaterAdmissionsAndClearsRetry()throws Exception{
        try(var f=new Fixture()){
            var first=selectedFile(101);var second=selectedFile(102);var task=f.repository.create("1",UUID.randomUUID().toString(),"pause-pass",DownloadPersistenceTest.post("title"),directory.resolve("output"),List.of(first,second));
            when(f.reader.resolve(any(),any(),anyLong())).thenAnswer(call->{f.repository.pause("1",task.id());throw new DownloadFileReader.TemporarilyUnavailable("暂时不可用");});
            f.start();var paused=f.await(task.id(),"PAUSED");assertTrue(paused.pauseRequested());assertNull(f.repository.view(paused).retry());verify(f.reader,times(1)).resolve(any(),any(),anyLong());
        }
    }

    @Test void aParallelTemporaryFailureLeavesHealthyMembersAndTheNextGroupRunnable()throws Exception{
        try(var f=new Fixture()){
            var preferences=new io.github.telegramenhance.storage.TransferPreferences(f.database);preferences.save(new io.github.telegramenhance.storage.TransferPreferences.View("",2));
            var selected=java.util.stream.IntStream.rangeClosed(101,103).mapToObj(DownloadAutomaticRetryTest::selectedFile).toList();
            when(f.reader.resolve(any(),any(),anyLong())).thenAnswer(call->{FrozenFile file=call.getArgument(1);if(file.uniqueId().equals("separate-101"))throw new DownloadFileReader.TemporarilyUnavailable("首个原文件暂时不可用");return new DownloadFileReader.Resolved(file,Integer.parseInt(file.media().message().messageId()));});
            var task=f.repository.create("1",UUID.randomUUID().toString(),"parallel-pass",DownloadPersistenceTest.post("title"),directory.resolve("output"),selected);
            f.engine=new DownloadEngine(f.repository,f.reader,f.store,f.telegram,f.paths,null,preferences,f.nanoTime);var waiting=f.awaitRetry(task.id());
            assertEquals(2,waiting.completed());assertEquals(1,waiting.retry().attempt());
            verify(f.reader).chunk("1",1,102,"separate-102",37,0,37);verify(f.reader).chunk("1",1,103,"separate-103",37,0,37);
        }
    }

    @Test void aServerRetryDelayStopsSerialAdmissionsBeforeAnyLaterFileIsRequested()throws Exception{
        try(var f=new Fixture()){
            var selected=List.of(selectedFile(101),selectedFile(102));
            when(f.reader.resolve(any(),any(),anyLong())).thenThrow(new DownloadFileReader.TemporarilyUnavailable("Telegram 暂时限制请求频率",61_000));
            var task=f.repository.create("1",UUID.randomUUID().toString(),"server-delay",DownloadPersistenceTest.post("title"),directory.resolve("output"),selected);f.start();var waiting=f.awaitRetry(task.id());
            assertEquals(f.clock.millis()+300_000,waiting.retry().nextAttemptAt());assertEquals(0,waiting.completed());verify(f.reader,times(1)).resolve(any(),any(),anyLong());verify(f.reader,never()).start(anyString(),anyLong(),anyInt(),anyString(),anyLong());
        }
    }

    @Test void aServerDelayDuringOwnedStallCancellationAlsoStopsLaterFileAdmissions()throws Exception{
        try(var f=new Fixture()){
            var selected=List.of(selectedFile(101),selectedFile(102));var nanos=new AtomicLong();f.nanoTime=()->nanos.getAndAdd(TimeUnit.MINUTES.toNanos(2));
            when(f.reader.resolve(any(),any(),anyLong())).thenAnswer(call->{FrozenFile file=call.getArgument(1);return new DownloadFileReader.Resolved(file,Integer.parseInt(file.media().message().messageId()));});
            when(f.reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenReturn(new DownloadFileReader.Status(0,false,true,true));
            doThrow(new DownloadFileReader.TemporarilyUnavailable("Telegram 暂时限制请求频率",61_000)).when(f.reader).cancel("1",1,101,"separate-101",37);
            var task=f.repository.create("1",UUID.randomUUID().toString(),"cancel-server-delay",DownloadPersistenceTest.post("title"),directory.resolve("output"),selected);f.repository.own("1","separate-101",101);f.start();var waiting=f.awaitRetry(task.id());
            assertEquals(f.clock.millis()+300_000,waiting.retry().nextAttemptAt());assertEquals(0,waiting.completed());assertEquals("QUEUED",f.repository.task("1",task.id()).items().getLast().state());
            verify(f.reader,times(1)).resolve(any(),any(),anyLong());verify(f.reader).cancel("1",1,101,"separate-101",37);verify(f.reader,never()).start(eq("1"),eq(1L),eq(102),anyString(),anyLong());
        }
    }

    @Test void aParallelServerDelaySettlesAdmittedMediaButDoesNotAdmitTheNextGroup()throws Exception{
        try(var f=new Fixture()){
            var preferences=new io.github.telegramenhance.storage.TransferPreferences(f.database);preferences.save(new io.github.telegramenhance.storage.TransferPreferences.View("",2));var healthyStarted=new CountDownLatch(1);
            var selected=java.util.stream.IntStream.rangeClosed(101,103).mapToObj(DownloadAutomaticRetryTest::selectedFile).toList();
            when(f.reader.resolve(any(),any(),anyLong())).thenAnswer(call->{FrozenFile file=call.getArgument(1);if(file.uniqueId().equals("separate-101")){assertTrue(healthyStarted.await(4,TimeUnit.SECONDS));throw new DownloadFileReader.TemporarilyUnavailable("Telegram 暂时限制请求频率",61_000);}return new DownloadFileReader.Resolved(file,Integer.parseInt(file.media().message().messageId()));});
            doAnswer(call->{if((Integer)call.getArgument(2)==102)healthyStarted.countDown();return null;}).when(f.reader).start(anyString(),anyLong(),anyInt(),anyString(),anyLong());
            var task=f.repository.create("1",UUID.randomUUID().toString(),"parallel-server-delay",DownloadPersistenceTest.post("title"),directory.resolve("output"),selected);f.engine=new DownloadEngine(f.repository,f.reader,f.store,f.telegram,f.paths,null,preferences,f.nanoTime);
            var waiting=f.awaitRetry(task.id());assertEquals(1,waiting.completed());assertEquals(f.clock.millis()+300_000,waiting.retry().nextAttemptAt());verify(f.reader,times(2)).resolve(any(),any(),anyLong());verify(f.reader).chunk("1",1,102,"separate-102",37,0,37);verify(f.reader,never()).chunk(eq("1"),eq(1L),eq(103),anyString(),anyLong(),anyLong(),anyInt());
        }
    }

    @Test void healthyGrowthInTheFiniteParallelGroupDoesNotTimeOutAnOtherwiseQueuedFile()throws Exception{
        try(var f=new Fixture()){
            var preferences=new io.github.telegramenhance.storage.TransferPreferences(f.database);preferences.save(new io.github.telegramenhance.storage.TransferPreferences.View("",2));
            var selected=List.of(selectedFile(101),selectedFile(102));var nanos=new AtomicLong();f.nanoTime=nanos::get;var moving=new AtomicInteger(1);var movingPolls=new AtomicInteger();var queuedComplete=new java.util.concurrent.atomic.AtomicBoolean();var bothStarted=new CountDownLatch(2);
            when(f.reader.resolve(any(),any(),anyLong())).thenAnswer(call->{FrozenFile file=call.getArgument(1);return new DownloadFileReader.Resolved(file,Integer.parseInt(file.media().message().messageId()));});
            doAnswer(call->{bothStarted.countDown();assertTrue(bothStarted.await(4,TimeUnit.SECONDS));return null;}).when(f.reader).start(anyString(),anyLong(),anyInt(),anyString(),anyLong());
            when(f.reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenAnswer(call->{int id=call.getArgument(2);int bytes=id==101?(queuedComplete.get()?37:0):moving.get();if(id==102)movingPolls.incrementAndGet();boolean complete=bytes==37;return new DownloadFileReader.Status(bytes,complete,!complete,true);});
            var task=f.repository.create("1",UUID.randomUUID().toString(),"healthy-group",DownloadPersistenceTest.post("title"),directory.resolve("output"),selected);f.engine=new DownloadEngine(f.repository,f.reader,f.store,f.telegram,f.paths,null,preferences,f.nanoTime);
            awaitCondition(()->f.repository.task("1",task.id()).items().stream().allMatch(item->item.state().equals("DOWNLOADING")));
            for(int turn=0;turn<3;turn++){
                int before=movingPolls.get(),bytes=turn+2;nanos.addAndGet(TimeUnit.SECONDS.toNanos(90));moving.set(bytes);
                awaitCondition(()->movingPolls.get()>=before+2&&f.repository.task("1",task.id()).items().getLast().downloadedBytes()==bytes);
            }
            assertTrue(nanos.get()>TimeUnit.MINUTES.toNanos(2));assertEquals("RUNNING",f.repository.task("1",task.id()).state());assertNull(f.repository.view(f.repository.task("1",task.id())).retry());verify(f.reader,never()).cancel(anyString(),anyLong(),anyInt(),anyString(),anyLong());
            moving.set(37);queuedComplete.set(true);assertEquals("COMPLETED",f.await(task.id(),"COMPLETED").state());
        }
    }
    static void awaitCondition(java.util.function.BooleanSupplier condition)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(10);assertTrue(condition.getAsBoolean());
    }

    @Test void aLongSerialPassReleasesCompletedOwnedCachesBeforeAdmittingTheNextFile()throws Exception{
        try(var f=new Fixture()){
            var cached=new java.util.concurrent.ConcurrentHashMap<Integer,Boolean>();var maximumOwners=new AtomicInteger();
            var selected=java.util.stream.IntStream.range(101,241).mapToObj(DownloadAutomaticRetryTest::selectedFile).toList();
            when(f.reader.resolve(any(),any(),anyLong())).thenAnswer(call->{FrozenFile file=call.getArgument(1);return new DownloadFileReader.Resolved(file,Integer.parseInt(file.media().message().messageId()));});
            when(f.reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenAnswer(call->{boolean complete=cached.getOrDefault(call.getArgument(2),false);return new DownloadFileReader.Status(complete?37:0,complete,false,true);});
            doAnswer(call->{maximumOwners.accumulateAndGet(f.repository.owners("1",500).size(),Math::max);cached.put(call.getArgument(2),true);return null;}).when(f.reader).start(anyString(),anyLong(),anyInt(),anyString(),anyLong());
            var task=f.repository.create("1",UUID.randomUUID().toString(),"long-pass",DownloadPersistenceTest.post("title"),directory.resolve("output"),selected);f.start();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!f.repository.task("1",task.id()).state().equals("COMPLETED")&&System.nanoTime()<until)Thread.sleep(20);
            var done=f.repository.task("1",task.id());assertEquals("COMPLETED",done.state());assertEquals(140,f.repository.view(done).completed());assertEquals(1,maximumOwners.get());assertTrue(f.repository.owners("1",500).isEmpty());
            verify(f.reader,times(140)).release(eq("1"),eq(1L),anyInt(),anyString(),eq(true));
        }
    }

    @Test void transientFailureWaitsThenCompletesTheSameTaskWithoutManualResume()throws Exception{
        try(var f=new Fixture()){
            var calls=new AtomicInteger();
            when(f.reader.resolve(any(),any(),anyLong())).thenAnswer(call->{
                if(calls.getAndIncrement()==0)throw new DownloadFileReader.TemporarilyUnavailable("Telegram 文件读取超时");
                return new DownloadFileReader.Resolved(call.getArgument(1),7);
            });
            var before=f.task();f.start();var waiting=f.awaitRetry(before.id());
            assertEquals(1,waiting.retry().attempt());assertEquals(f.clock.millis()+300_000,waiting.retry().nextAttemptAt());
            assertFalse(waiting.pauseRequested());assertEquals(0,waiting.completed());assertEquals(1,calls.get());
            f.clock.advance(300_000);var done=f.await(before.id(),"COMPLETED");
            assertEquals(before.operationId(),done.operationId());assertEquals(before.fingerprint(),done.fingerprint());
            assertEquals(before.items().getFirst().file(),done.items().getFirst().file());
            assertEquals(1,f.repository.view(done).completed());assertNull(f.repository.view(done).retry());assertNull(done.error());assertEquals(2,calls.get());
            verify(f.reader,times(1)).chunk("1",1,7,"stable-unique",37,0,37);
        }
    }
    @Test void stalledDownloadsKeepTheirProgressAndAutomaticallyRestartAfterTheDelay()throws Exception{
        try(var f=new Fixture()){
            when(f.reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenReturn(new DownloadFileReader.Status(5,false,true,true));
            var nanos=new AtomicLong();f.nanoTime=()->nanos.getAndAdd(TimeUnit.MINUTES.toNanos(2));
            var task=f.task();f.start();var waiting=f.awaitRetry(task.id());
            assertEquals(5,waiting.items().getFirst().downloadedBytes());assertTrue(waiting.error().contains("两分钟"));
            when(f.reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenReturn(new DownloadFileReader.Status(37,true,false,true));
            f.clock.advance(300_000);assertEquals(1,f.repository.view(f.await(task.id(),"COMPLETED")).completed());
        }
    }
    @Test void retryResumesTheVerifiedOutputCheckpointWithoutRewritingTheCompletedPrefix()throws Exception{
        try(var f=new Fixture()){
            int prefix=1024*1024,size=prefix+17;var prefixReads=new AtomicInteger();var tailReads=new AtomicInteger();
            when(f.reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenReturn(new DownloadFileReader.Status(size,true,false,true));
            when(f.reader.chunk(anyString(),anyLong(),anyInt(),anyString(),anyLong(),anyLong(),anyInt())).thenAnswer(call->{
                long offset=call.getArgument(5);if(offset==0)prefixReads.incrementAndGet();
                else if(tailReads.getAndIncrement()==0)throw new DownloadFileReader.TemporarilyUnavailable("Telegram 文件读取超时");
                return new byte[(Integer)call.getArgument(6)];
            });
            var task=f.repository.create("1",UUID.randomUUID().toString(),"checkpoint",DownloadPersistenceTest.post("title"),directory.resolve("output"),List.of(DownloadPersistenceTest.file(size,"large.bin")));
            f.start();var waiting=f.awaitRetry(task.id());var checkpoint=waiting.items().getFirst();
            assertEquals(prefix,checkpoint.writtenBytes());assertEquals(size,checkpoint.downloadedBytes());
            f.clock.advance(300_000);var done=f.repository.view(f.await(task.id(),"COMPLETED"));
            assertEquals(size,done.items().getFirst().writtenBytes());assertEquals(1,prefixReads.get());assertEquals(2,tailReads.get());
            assertEquals(size,java.nio.file.Files.size(Path.of(done.items().getFirst().outputPath())));
        }
    }
    @Test void manualPauseCancelsAnAlreadyScheduledRetry()throws Exception{
        try(var f=new Fixture()){
            when(f.reader.resolve(any(),any(),anyLong())).thenThrow(new DownloadFileReader.TemporarilyUnavailable("连接暂时中断"));
            var task=f.task();f.start();f.awaitRetry(task.id());f.repository.pause("1",task.id());
            f.clock.advance(TimeUnit.DAYS.toMillis(1));Thread.sleep(1200);
            var paused=f.repository.view(f.repository.task("1",task.id()));
            assertEquals("PAUSED",paused.state());assertTrue(paused.pauseRequested());assertNull(paused.retry());
            verify(f.reader,times(1)).resolve(any(),any(),anyLong());assertNull(f.repository.next("1"));
        }
    }
    @Test void aLateNetworkFailureCannotUndoAnAcceptedManualPause()throws Exception{
        try(var f=new Fixture()){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            when(f.reader.resolve(any(),any(),anyLong())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));throw new DownloadFileReader.TemporarilyUnavailable("连接暂时中断");});
            var task=f.task();f.start();
            try{assertTrue(entered.await(5,TimeUnit.SECONDS));f.repository.pause("1",task.id());}finally{release.countDown();}
            var paused=f.await(task.id(),"PAUSED");assertTrue(paused.pauseRequested());assertNull(f.repository.view(paused).retry());
            f.clock.advance(600_000);assertNull(f.repository.next("1"));verify(f.reader,times(1)).resolve(any(),any(),anyLong());
        }
    }
    @Test void aDifferentAccountCannotRunTheWaitingDownload()throws Exception{
        try(var f=new Fixture()){
            var calls=new AtomicInteger();when(f.reader.resolve(any(),any(),anyLong())).thenAnswer(call->{
                if(calls.getAndIncrement()==0)throw new DownloadFileReader.TemporarilyUnavailable("连接暂时中断");
                return new DownloadFileReader.Resolved(call.getArgument(1),7);
            });
            var task=f.task();f.start();f.awaitRetry(task.id());when(f.telegram.accountId()).thenReturn("2");
            f.clock.advance(300_000);Thread.sleep(1200);assertEquals(1,calls.get());assertEquals("QUEUED",f.repository.task("1",task.id()).state());
            when(f.telegram.accountId()).thenReturn("1");f.await(task.id(),"COMPLETED");assertEquals(2,calls.get());
        }
    }
    @Test void permanentSourceFailuresRemainForManualReview()throws Exception{
        try(var f=new Fixture()){
            when(f.reader.resolve(any(),any(),anyLong())).thenThrow(new IllegalArgumentException("源文件身份已变化"));
            var task=f.task();f.start();var failed=f.await(task.id(),"FAILED");assertNull(f.repository.view(failed).retry());
            f.clock.advance(600_000);assertNull(f.repository.next("1"));assertEquals("源文件身份已变化",failed.error());
        }
    }
    private final class Fixture implements AutoCloseable{
        final AppPaths paths=new AppPaths(directory.resolve(UUID.randomUUID().toString()));final Database database=new Database(paths);
        final MutableClock clock=new MutableClock();final DownloadRepository repository=new DownloadRepository(database,clock);
        final DownloadFileReader reader=mock(DownloadFileReader.class);final TelegramService telegram=mock(TelegramService.class);
        final DownloadFileStore store=new DownloadFileStore(new DownloadPaths(paths),repository);DownloadEngine engine;
        java.util.function.LongSupplier nanoTime=System::nanoTime;
        Fixture(){
            when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);
            when(reader.resolve(any(),any(),anyLong())).thenAnswer(call->new DownloadFileReader.Resolved(call.getArgument(1),7));
            when(reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenReturn(new DownloadFileReader.Status(37,true,false,true));
            when(reader.chunk(anyString(),anyLong(),anyInt(),anyString(),anyLong(),anyLong(),anyInt())).thenAnswer(call->new byte[(Integer)call.getArgument(6)]);
            when(reader.release(anyString(),anyLong(),anyInt(),anyString(),anyBoolean())).thenReturn(true);
        }
        Task task(){return repository.create("1",UUID.randomUUID().toString(),"frozen",DownloadPersistenceTest.post("title"),directory.resolve("output"),List.of(DownloadPersistenceTest.file(37,"file.bin")));}
        void start(){engine=new DownloadEngine(repository,reader,store,telegram,paths,null,null,nanoTime);}
        TaskView awaitRetry(String id)throws Exception{
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
            while(System.nanoTime()<until){var task=repository.view(repository.task("1",id));if(task.retry()!=null)return task;Thread.sleep(20);}
            fail("No automatic retry: "+repository.task("1",id));return null;
        }
        Task await(String id,String state)throws Exception{
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
            while(System.nanoTime()<until){var task=repository.task("1",id);if(task.state().equals(state))return task;Thread.sleep(20);}
            fail("Expected "+state+", got "+repository.task("1",id));return null;
        }
        public void close(){if(engine!=null)engine.close();database.close();}
    }
    private static final class MutableClock extends Clock{
        final AtomicLong millis=new AtomicLong(1_800_000_000_000L);
        public ZoneId getZone(){return ZoneId.of("UTC");}public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(millis());}public long millis(){return millis.get();}
        void advance(long value){millis.addAndGet(value);}
    }
}
