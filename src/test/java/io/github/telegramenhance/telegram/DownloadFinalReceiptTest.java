package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import it.tdlight.client.TelegramError;
import it.tdlight.jni.TdApi;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DownloadFinalReceiptTest {
    @Test void aPendingFinalReceiptCannotReleaseThePublicationGateBeforeLateSuccess()throws Exception{
        var f=new Fixture();f.start();var checks=new AtomicInteger();
        try(var worker=Executors.newSingleThreadExecutor()){
            var entered=new CountDownLatch(1);
            var gate=worker.submit(()->{entered.countDown();f.await(checks::incrementAndGet);});
            try{
                assertTrue(entered.await(1,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->gate.get(40,TimeUnit.MILLISECONDS));
                assertFalse(f.pending.isDone());f.pending.complete(f.file);gate.get(1,TimeUnit.SECONDS);
                assertTrue(checks.get()>=2);
            }finally{f.pending.complete(f.file);}
        }
    }
    @Test void aFailureArrivingAfterTheCachedBytesWereReadStillFailsTheFinalGate(){
        var f=new Fixture();f.start();var checks=new AtomicInteger();
        var failure=assertThrows(DownloadFileReader.DownloadFailed.class,()->f.await(()->{
            if(checks.incrementAndGet()==2)f.pending.completeExceptionally(new TelegramError(new TdApi.Error(400,"File download has failed or was canceled")));
        }));
        assertFalse(failure.referenceRejected());assertFalse(f.pending.isCancelled());assertEquals(1,f.starts);
    }
    @Test void aManualPauseWinsDuringTheWaitAndDoesNotCancelTheNativeOrAnotherSessionsAttempt(){
        var f=new Fixture();f.start();var checks=new AtomicInteger();var stopped=new IllegalStateException("manual-pause");
        assertSame(stopped,assertThrows(IllegalStateException.class,()->f.await(()->{if(checks.incrementAndGet()==2)throw stopped;})));
        assertFalse(f.pending.isDone());assertEquals(1,f.starts);
    }
    @Test void accountAndGenerationChangesDuringTheWaitCannotBecomeATemporaryNetworkRetry(){
        for(boolean accountChange:List.of(false,true)){
            var f=new Fixture();f.start();var checks=new AtomicInteger();
            var failure=assertThrows(IllegalArgumentException.class,()->f.await(()->{
                if(checks.incrementAndGet()==2){if(accountChange)f.account="2";else f.generation=2;}
            }));
            assertFalse(failure instanceof DownloadFileReader.TemporarilyUnavailable);assertFalse(f.pending.isDone());
        }
    }
    @Test void finalReceiptTimeoutUsesANewTwoMinuteClockBudgetWithoutCancellingTheNativeRequest(){
        var f=new Fixture();f.start();f.now.set(TimeUnit.HOURS.toNanos(2));
        var failure=assertThrows(DownloadFileReader.TemporarilyUnavailable.class,()->f.await(()->f.now.addAndGet(TimeUnit.MINUTES.toNanos(2))));
        assertTrue(failure.getMessage().contains("回执"));assertEquals(0,failure.minimumDelayMillis());
        assertFalse(f.pending.isDone());assertEquals(1,f.starts);
    }
    @Test void completedFinalErrorsKeepTheirClassificationAndManualPauseStillWins(){
        var f=new Fixture();f.start();f.pending.completeExceptionally(new TelegramError(new TdApi.Error(429,"FLOOD_WAIT_600 private")));
        var failure=assertThrows(DownloadFileReader.TemporarilyUnavailable.class,()->f.await(()->{}));
        assertEquals(600_000,failure.minimumDelayMillis());assertFalse(failure.getMessage().contains("private"));
        var stopped=new IllegalStateException("manual-pause");assertSame(stopped,assertThrows(IllegalStateException.class,()->f.await(()->{throw stopped;})));
    }
    @Test void anUntrackedCompletedCacheHasNoReceiptToWaitForButStillChecksItsSessionAndPause(){
        var f=new Fixture();var checks=new AtomicInteger();f.await(checks::incrementAndGet);assertTrue(checks.get()>0);assertEquals(0,f.starts);
        f.generation=2;assertThrows(IllegalArgumentException.class,()->f.await(()->{}));
    }
    private static final class Fixture implements DownloadFileReader.Gateway{
        final TdApi.File file=new TdApi.File();final CompletableFuture<TdApi.File>pending=new CompletableFuture<>();
        final AtomicLong now=new AtomicLong();String account="1";long generation=1;int starts;
        final DownloadFileReader reader=new DownloadFileReader(this,()->account,()->generation,now::get);
        Fixture(){file.id=7;file.size=11;file.remote=new TdApi.RemoteFile("private","stable",false,true,11);file.local=new TdApi.LocalFile();file.local.isDownloadingCompleted=true;file.local.downloadedSize=11;}
        void start(){reader.start("1",1,7,"stable",11);}
        void await(Runnable check){reader.awaitDownload("1",1,7,"stable",11,check);}
        @Override @SuppressWarnings("unchecked")public <R extends TdApi.Object>CompletableFuture<R>read(long session,TdApi.Function<R>query){
            if(query instanceof TdApi.GetFile)return CompletableFuture.completedFuture((R)file);
            if(query instanceof TdApi.DownloadFile){starts++;return(CompletableFuture<R>)pending;}
            throw new AssertionError("Final receipt wait must not issue cache mutations or extra native requests");
        }
    }
}
