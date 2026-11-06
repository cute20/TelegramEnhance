package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.DownloadModels.*;
import it.tdlight.client.TelegramError;
import it.tdlight.jni.TdApi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real engine, reader, checkpoints and publication; only the remote transport is synthetic. */
class DownloadStreamingIntegrationTest {
    private static final int BLOCK=1024*1024, SIZE=2*BLOCK+17;
    @TempDir Path directory;

    @Test void aLateReferenceFailureDrainsItsCachedPrefixAndRefreshesOnceAtTheWrittenOffset()throws Exception{
        try(var f=new Fixture()){
            f.gateway.failAfterFirstChunk=true;
            var task=f.create();f.start();
            await(()->f.fresh(task.id()).items().getFirst().writtenBytes()==BLOCK);
            await(()->f.gateway.requests.size()==2);
            var partial=f.fresh(task.id()).items().getFirst();
            assertEquals(BLOCK,partial.downloadedBytes());assertEquals(BLOCK,partial.writtenBytes());
            assertNotNull(partial.sha256());assertEquals("RUNNING",f.fresh(task.id()).state());
            assertEquals(List.of(0L,(long)BLOCK),f.gateway.requests.stream().map(q->q.offset).toList());
            assertTrue(f.gateway.requests.stream().allMatch(q->q.synchronous&&q.limit==0));
            f.gateway.completeTail();
            await(()->f.fresh(task.id()).state().equals("COMPLETED"));
            var done=f.repository.view(f.fresh(task.id()));
            assertArrayEquals(f.gateway.bytes,Files.readAllBytes(Path.of(done.items().getFirst().outputPath())));
            assertEquals(List.of(0L,(long)BLOCK,2L*BLOCK),f.gateway.readOffsets);
            assertEquals(2,f.gateway.sourceReads);assertEquals(1,done.completed());
        }
    }

    @Test void aMissingRefreshSourceQueuesAnExplicitRetryAfterSavingTheNetworkPrefix()throws Exception{
        try(var f=new Fixture()){
            f.gateway.failAfterFirstChunk=true;f.gateway.sourceMissingAfterFailure=true;
            var task=f.create();f.start();
            await(()->f.repository.retry("1",task.id())!=null);
            var waiting=f.fresh(task.id());var item=waiting.items().getFirst();
            assertEquals("QUEUED",waiting.state());assertEquals(BLOCK,item.writtenBytes());
            assertEquals(BLOCK,item.downloadedBytes());assertTrue(waiting.error().contains("原消息"));
            assertFalse(waiting.error().contains("private"));assertEquals(1,f.gateway.requests.size());
            assertEquals(0,f.repository.view(waiting).completed());
        }
    }

    @Test void aManualPauseKeepsTheNetworkCheckpointAndDoesNotAutomaticallyRestartIt()throws Exception{
        try(var f=new Fixture()){
            var task=f.create();f.start();
            await(()->f.fresh(task.id()).items().getFirst().writtenBytes()==BLOCK);
            f.repository.pause("1",task.id());await(()->f.fresh(task.id()).state().equals("PAUSED"));
            assertEquals(BLOCK,f.fresh(task.id()).items().getFirst().writtenBytes());
            assertNull(f.repository.retry("1",task.id()));
            Thread.sleep(1100);assertEquals(1,f.gateway.requests.size());
            assertEquals("PAUSED",f.fresh(task.id()).state());
        }
    }

    @Test void restartingWithNoNativeCacheResumesTheVerifiedTargetPrefixAndNeverRequestsItsEarlierBytes()throws Exception{
        String id;
        try(var f=new Fixture()){
            var task=f.create();id=task.id();f.start();
            await(()->f.fresh(id).items().getFirst().writtenBytes()==BLOCK);
            f.repository.pause("1",id);await(()->f.fresh(id).state().equals("PAUSED"));
        }
        try(var resumed=new Fixture()){
            resumed.gateway.restarted=true;resumed.gateway.tailReady=true;
            resumed.repository.resume("1",id);resumed.start();
            await(()->resumed.fresh(id).state().equals("COMPLETED"));
            var done=resumed.repository.view(resumed.fresh(id));
            assertEquals(List.of((long)BLOCK),resumed.gateway.requests.stream().map(q->q.offset).toList());
            assertEquals(List.of((long)BLOCK,2L*BLOCK),resumed.gateway.readOffsets);
            assertArrayEquals(resumed.gateway.bytes,Files.readAllBytes(Path.of(done.items().getFirst().outputPath())));
        }
    }

    @Test void allCachedBytesAreForcedButCannotPublishWhileTheFinalDownloadReceiptIsStillPending()throws Exception{
        try(var f=new Fixture()){
            f.gateway.tailReady=true;f.gateway.holdFinal=true;
            var task=f.create();f.start();
            await(()->f.fresh(task.id()).items().getFirst().writtenBytes()==SIZE);
            Thread.sleep(150);
            assertEquals("RUNNING",f.fresh(task.id()).state());
            assertEquals(0,f.repository.view(f.fresh(task.id())).completed());
            f.gateway.completeTail();
            await(()->f.fresh(task.id()).state().equals("COMPLETED"));
            assertArrayEquals(f.gateway.bytes,Files.readAllBytes(Path.of(f.repository.view(f.fresh(task.id())).items().getFirst().outputPath())));
        }
    }

    @Test void aFinalFailureAfterTheLastCachedReadStillPreservesEveryReceivedByteBeforeRetrying()throws Exception{
        try(var f=new Fixture()){
            f.gateway.tailReady=true;f.gateway.holdFinal=true;f.gateway.failAtLastChunk=true;
            f.gateway.sourceMissingAfterFailure=true;
            var task=f.create();f.start();await(()->f.repository.retry("1",task.id())!=null);
            var waiting=f.fresh(task.id());
            assertEquals(SIZE,waiting.items().getFirst().writtenBytes());
            assertEquals(SIZE,waiting.items().getFirst().downloadedBytes());
            assertEquals(0,f.repository.view(waiting).completed());assertEquals(1,f.gateway.requests.size());
            assertEquals(List.of(0L,(long)BLOCK,2L*BLOCK),f.gateway.readOffsets);
        }
    }

    @Test void anAutomaticRetryCannotPublishACompleteCheckpointWhoseFinalReceiptWasRejected()throws Exception{
        try(var f=new Fixture()){
            f.gateway.tailReady=true;f.gateway.holdFinal=true;f.gateway.failAtLastChunk=true;f.gateway.sourceMissingAfterFailure=true;
            var task=f.create();f.start();await(()->f.repository.retry("1",task.id())!=null);
            f.clock.advance(300_000);
            await(()->f.gateway.requests.size()>=2||f.fresh(task.id()).state().equals("COMPLETED"));
            assertEquals(2,f.gateway.requests.size());assertEquals("RUNNING",f.fresh(task.id()).state());
            assertEquals(0,f.gateway.requests.getLast().offset);
            assertEquals(SIZE,f.fresh(task.id()).items().getFirst().writtenBytes());
            f.gateway.pending.completeExceptionally(new TelegramError(new TdApi.Error(400,"File download has failed or was canceled")));
            await(()->f.repository.retry("1",task.id())!=null);
            assertEquals(0,f.repository.view(f.fresh(task.id())).completed());
        }
    }

    @Test void aLateFinalReceiptRejectionCanRepairOnceWithoutRewritingAnyTargetBytes()throws Exception{
        try(var f=new Fixture()){
            f.gateway.tailReady=true;f.gateway.holdFinal=true;f.gateway.failAtLastChunk=true;
            var task=f.create();f.start();
            await(()->f.gateway.requests.size()==2||f.repository.retry("1",task.id())!=null);
            assertEquals(2,f.gateway.requests.size());assertEquals(0,f.gateway.requests.getLast().offset);
            assertEquals(SIZE,f.fresh(task.id()).items().getFirst().writtenBytes());
            assertEquals(List.of(0L,(long)BLOCK,2L*BLOCK),f.gateway.readOffsets);
            f.gateway.completeTail();await(()->f.fresh(task.id()).state().equals("COMPLETED"));
            assertEquals(2,f.gateway.sourceReads);assertEquals(3,f.gateway.readOffsets.size());
        }
    }

    @Test void restartingACompleteCheckpointStillRequiresThePendingFinalReceiptBeforePublication()throws Exception{
        String id;
        try(var f=new Fixture()){
            f.gateway.tailReady=true;f.gateway.holdFinal=true;
            var task=f.create();id=task.id();f.start();await(()->f.fresh(id).items().getFirst().writtenBytes()==SIZE);
            f.repository.pause("1",id);await(()->f.fresh(id).state().equals("PAUSED"));
        }
        try(var resumed=new Fixture()){
            resumed.gateway.restarted=true;resumed.gateway.tailReady=true;resumed.gateway.holdFinal=true;
            resumed.repository.resume("1",id);resumed.start();
            await(()->!resumed.gateway.requests.isEmpty()||resumed.fresh(id).state().equals("COMPLETED"));
            assertEquals(1,resumed.gateway.requests.size());assertEquals(0,resumed.gateway.requests.getFirst().offset);
            assertEquals("RUNNING",resumed.fresh(id).state());assertTrue(resumed.gateway.readOffsets.isEmpty());
            resumed.gateway.completeTail();await(()->resumed.fresh(id).state().equals("COMPLETED"));
        }
    }

    @Test void nativeRevalidationOfAFullCheckpointCanKeepDownloadingBeyondTheFinalReceiptBudget()throws Exception{
        String id;
        try(var f=new Fixture()){
            f.gateway.tailReady=true;f.gateway.holdFinal=true;
            var task=f.create();id=task.id();f.start();await(()->f.fresh(id).items().getFirst().writtenBytes()==SIZE);
            f.repository.pause("1",id);await(()->f.fresh(id).state().equals("PAUSED"));
        }
        try(var resumed=new Fixture()){
            resumed.gateway.restarted=true;resumed.gateway.holdFinal=true;
            resumed.repository.resume("1",id);resumed.start();await(()->!resumed.gateway.requests.isEmpty());
            Thread.sleep(250);
            resumed.gateway.cacheEnd=2L*BLOCK;
            resumed.nativeClock.addAndGet(TimeUnit.MINUTES.toNanos(3));
            Thread.sleep(450);
            assertEquals("RUNNING",resumed.fresh(id).state(),"Growing native bytes are not a final-receipt timeout");
            assertNull(resumed.repository.retry("1",id));assertTrue(resumed.gateway.readOffsets.isEmpty());
            resumed.gateway.completeTail();await(()->resumed.fresh(id).state().equals("COMPLETED"));
        }
    }

    private final class Fixture implements AutoCloseable{
        final AppPaths paths=new AppPaths(directory.resolve("business"));
        final Database database=new Database(paths);final MutableClock clock=new MutableClock();final DownloadRepository repository=new DownloadRepository(database,clock);
        final Gateway gateway=new Gateway();final java.util.concurrent.atomic.AtomicLong nativeClock=new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        final DownloadFileReader reader=new DownloadFileReader(gateway,()->"1",()->1L,nativeClock::get);
        final TelegramService telegram=mock(TelegramService.class);DownloadEngine engine;
        Fixture(){when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);}
        Task create(){
            var ref=new MessageRef("71","101",null);var media=new Media("1:71:101:0",ref,"BOT","DOCUMENT","stream.bin",SIZE,"");
            var post=new Post("bot:72:71:101","1","71","synthetic","101","stream","",null,List.of(ref),List.of(media),null,null,List.of(),false,"PARTIAL",List.of());
            return repository.create("1",UUID.randomUUID().toString(),"stream",post,directory.resolve("output"),List.of(new FrozenFile(media,"stable",SIZE,"stream.bin",new BotFileReference(7,"private-remote"))));
        }
        void start(){engine=new DownloadEngine(repository,reader,new DownloadFileStore(new DownloadPaths(paths),repository),telegram,paths);}
        Task fresh(String id){return repository.task("1",id);}
        public void close(){if(engine!=null)engine.close();database.close();}
    }
    private static void await(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);
        assertTrue(condition.getAsBoolean(),"Expected download checkpoint/state was never reached");
    }
    private static final class MutableClock extends java.time.Clock{
        private volatile long now=System.currentTimeMillis();
        void advance(long millis){now+=millis;}
        public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
        public java.time.Clock withZone(java.time.ZoneId zone){return this;}
        public java.time.Instant instant(){return java.time.Instant.ofEpochMilli(now);}
        public long millis(){return now;}
    }
    private static final class Gateway implements DownloadFileReader.Gateway{
        final byte[] bytes=new byte[SIZE];
        final List<TdApi.DownloadFile> requests=new CopyOnWriteArrayList<>();
        final List<Long> readOffsets=new CopyOnWriteArrayList<>();
        volatile boolean failAfterFirstChunk,sourceMissingAfterFailure,restarted,tailReady,failed,holdFinal,failAtLastChunk;
        volatile int sourceReads;volatile long cacheStart,cacheEnd;
        volatile int currentId=7;volatile CompletableFuture<TdApi.File> pending;
        Gateway(){for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*31+7);}
        synchronized void completeTail(){tailReady=true;cacheEnd=SIZE;if(pending!=null)pending.complete(file(currentId));}
        @Override @SuppressWarnings("unchecked")public synchronized <R extends TdApi.Object>CompletableFuture<R>read(long generation,TdApi.Function<R> query){
            assertEquals(1,generation);TdApi.Object value;
            if(query instanceof TdApi.GetChat){var chat=new TdApi.Chat();chat.id=71;chat.type=new TdApi.ChatTypePrivate(72);value=chat;}
            else if(query instanceof TdApi.GetUser){var user=new TdApi.User();user.id=72;user.type=new TdApi.UserTypeBot();value=user;}
            else if(query instanceof TdApi.GetMessage){
                sourceReads++;
                if(failed&&sourceMissingAfterFailure)return CompletableFuture.failedFuture(new TelegramError(new TdApi.Error(404,"private-source-missing")));
                var message=new TdApi.Message();message.id=101;message.chatId=71;message.senderId=new TdApi.MessageSenderUser(72);
                message.content=new TdApi.MessageDocument(new TdApi.Document("stream.bin","application/octet-stream",null,null,file(failed||restarted?27:7)),new TdApi.FormattedText("",new TdApi.TextEntity[0]));value=message;
            }
            else if(query instanceof TdApi.GetFile get)value=file(get.fileId);
            else if(query instanceof TdApi.GetRemoteFile)value=file(currentId);
            else if(query instanceof TdApi.DownloadFile request){
                requests.add(request);currentId=request.fileId;cacheStart=request.offset;
                cacheEnd=tailReady?SIZE:request.offset==0?BLOCK:request.offset;
                pending=new CompletableFuture<>();if(tailReady&&!holdFinal)pending.complete(file(currentId));
                return request.synchronous?(CompletableFuture<R>)pending:CompletableFuture.completedFuture((R)file(currentId));
            }
            else if(query instanceof TdApi.GetFileDownloadedPrefixSize prefix){
                long count=prefix.fileId==currentId&&prefix.offset>=cacheStart&&prefix.offset<=cacheEnd?cacheEnd-prefix.offset:0;
                value=new TdApi.FileDownloadedPrefixSize(count);
            }
            else if(query instanceof TdApi.ReadFilePart part){
                assertEquals(currentId,part.fileId);assertTrue(part.offset>=cacheStart&&part.offset+part.count<=cacheEnd);
                readOffsets.add(part.offset);value=new TdApi.Data(Arrays.copyOfRange(bytes,(int)part.offset,(int)(part.offset+part.count)));
                if(failAfterFirstChunk&&!failed){failed=true;pending.completeExceptionally(new TelegramError(new TdApi.Error(400,"FILE_REFERENCE_0_EXPIRED private")));}
                if(failAtLastChunk&&part.offset+part.count==SIZE){failed=true;pending.completeExceptionally(new TelegramError(new TdApi.Error(400,"File download has failed or was canceled")));}
            }
            else if(query instanceof TdApi.CancelDownloadFile){if(pending!=null&&!pending.isDone())pending.completeExceptionally(new TelegramError(new TdApi.Error(400,"File download has failed or was canceled")));value=new TdApi.Ok();}
            else if(query instanceof TdApi.DeleteFile){cacheStart=cacheEnd=0;value=new TdApi.Ok();}
            else throw new AssertionError("Unexpected request "+query.getClass().getSimpleName());
            return CompletableFuture.completedFuture((R)value);
        }
        private TdApi.File file(int id){
            var file=new TdApi.File();file.id=id;file.size=SIZE;file.remote=new TdApi.RemoteFile("private-remote","stable",false,true,SIZE);
            file.local=new TdApi.LocalFile();file.local.canBeDownloaded=true;
            file.local.downloadedSize=id==currentId?cacheEnd-cacheStart:0;
            file.local.isDownloadingCompleted=id==currentId&&cacheStart==0&&cacheEnd==SIZE;
            file.local.isDownloadingActive=pending!=null&&!pending.isDone();return file;
        }
    }
}
