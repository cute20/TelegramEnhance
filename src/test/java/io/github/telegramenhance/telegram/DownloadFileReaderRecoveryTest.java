package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import it.tdlight.jni.TdApi;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import it.tdlight.client.TelegramError;
import org.junit.jupiter.api.Test;

class DownloadFileReaderRecoveryTest {
    @Test void aPendingFinalDownloadFutureDoesNotBlockTheReaderOrGetCancelledByItsReadBudget() {
        var fixture = new Fixture();
        assertTimeoutPreemptively(Duration.ofMillis(250), () -> fixture.reader.start("1", 1, 7, "stable", 11));
        assertFalse(fixture.pending.isDone());
        assertTrue(fixture.downloads.getFirst().synchronous);
    }

    @Test void startingAFileRequestsItsFinalResultInsteadOfAnImmediateStartReceipt() {
        var fixture = new Fixture(); fixture.pending.complete(fixture.file);
        fixture.reader.start("1", 1, 7, "stable", 11);
        assertTrue(fixture.downloads.getFirst().synchronous);
        assertEquals(0, fixture.downloads.getFirst().offset);
        assertEquals(0, fixture.downloads.getFirst().limit);
    }

    @Test void lateNativeFailureRemainsVisibleAfterTheStartCallReturned() {
        for (String message : List.of("FILE_REFERENCE_0_EXPIRED private", "File download has failed or was canceled")) {
            var f = new Fixture(); f.reader.startAt("1", 1, 7, "stable", 11, 5);
            assertDoesNotThrow(() -> f.reader.checkDownload("1", 1, 7, "stable", 11));
            f.pending.completeExceptionally(new TelegramError(new TdApi.Error(400, message)));
            var failure = assertThrows(DownloadFileReader.DownloadFailed.class, () -> f.reader.checkDownload("1", 1, 7, "stable", 11));
            assertEquals(message.startsWith("FILE_REFERENCE_"), failure.referenceRejected());
            assertFalse(failure.getMessage().contains("private")); assertFalse(failure.getMessage().contains(message));
            assertEquals(5, f.downloads.getFirst().offset);
        }
    }

    @Test void finalRateLimitAndTransportFailuresKeepTheirRetryClassAndServerWait() {
        for (Throwable reason : List.of(new TelegramError(new TdApi.Error(429,"FLOOD_WAIT_600 private")), new TimeoutException("private"), new java.net.ConnectException("private"))) {
            var f = new Fixture(); f.reader.start("1", 1, 7, "stable", 11); f.pending.completeExceptionally(reason);
            var failure = assertThrows(DownloadFileReader.TemporarilyUnavailable.class, () -> f.reader.checkDownload("1", 1, 7, "stable", 11));
            assertEquals(reason instanceof TelegramError ? 600_000 : 0, failure.minimumDelayMillis());
            assertFalse(failure.getMessage().contains("private"));
        }
    }

    @Test void successfulFinalReceiptsCannotReplaceTheExpectedFileAndStaleSessionsWinOverNetworkErrors() {
        for (String mode : List.of("unique", "size", "id", "account", "session")) {
            var f = new Fixture(); f.reader.start("1",1,7,"stable",11);
            if (mode.equals("unique")) f.file.remote.uniqueId="replacement";
            if (mode.equals("size")) f.file.size=12;
            if (mode.equals("id")) f.file.id=8;
            if (mode.equals("account")) f.account="2";
            if (mode.equals("session")) f.session=2;
            if (mode.equals("account") || mode.equals("session")) f.pending.completeExceptionally(new TimeoutException("private"));
            else f.pending.complete(f.file);
            var failure=assertThrows(IllegalArgumentException.class,()->f.reader.checkDownload("1",1,7,"stable",11),mode);
            assertFalse(failure instanceof DownloadFileReader.TemporarilyUnavailable, mode);
        }
    }

    @Test void forgettingAnAttemptNeverCancelsItsNativeRequestAndCannotForgetAnotherSession() {
        var f = new Fixture(); f.reader.start("1",1,7,"stable",11);
        f.reader.forgetDownload("1",2,7,"stable",11);
        f.pending.completeExceptionally(new TelegramError(new TdApi.Error(400,"FILE_REFERENCE_EXPIRED")));
        assertThrows(DownloadFileReader.DownloadFailed.class,()->f.reader.checkDownload("1",1,7,"stable",11));
        f.reader.forgetDownload("1",1,7,"stable",11);
        assertDoesNotThrow(()->f.reader.checkDownload("1",1,7,"stable",11));
        assertFalse(f.pending.isCancelled()); assertEquals(1,f.downloads.size());
    }

    @Test void partialChunksUseTheContiguousPrefixAtTheirActualOffsetRatherThanTotalDownloadedBytes() {
        var f = new Fixture(); f.file.local.downloadedSize=9; f.prefix=3;
        assertEquals(3,f.reader.availableBytes("1",1,7,"stable",11,5));
        f.partLength=3; assertArrayEquals(new byte[3],f.reader.chunk("1",1,7,"stable",11,5,3));
        assertEquals(List.of(5L,5L),f.prefixOffsets);
        f.prefix=0; assertEquals(0,f.reader.availableBytes("1",1,7,"stable",11,2));
        assertThrows(IllegalArgumentException.class,()->f.reader.chunk("1",1,7,"stable",11,2,1));
        assertEquals(1,f.readParts);
    }

    @Test void prefixAndChunkBoundsRejectGapsShortReceiptsWrongIdentityAndInvalidTailOffsets() {
        var f = new Fixture();
        for(long offset:List.of(-1L,12L)){
            assertThrows(IllegalArgumentException.class,()->f.reader.availableBytes("1",1,7,"stable",11,offset));
            assertThrows(IllegalArgumentException.class,()->f.reader.startAt("1",1,7,"stable",11,offset));
        }
        for(long prefix:List.of(-1L,12L,7L)){
            f.prefix=prefix;
            assertThrows(IllegalArgumentException.class,()->f.reader.availableBytes("1",1,7,"stable",11,5));
        }
        f.prefix=6;f.partLength=2;
        assertThrows(IllegalArgumentException.class,()->f.reader.chunk("1",1,7,"stable",11,5,3));
        f.file.remote.uniqueId="replacement";
        assertThrows(IllegalArgumentException.class,()->f.reader.availableBytes("1",1,7,"stable",11,5));
    }

    @Test void completedFilesAndTheExactTailDoNotNeedAnExtraPrefixRequest() {
        var f = new Fixture(); f.file.local.isDownloadingCompleted=true; f.file.local.downloadedSize=11;
        assertEquals(6,f.reader.availableBytes("1",1,7,"stable",11,5));
        assertEquals(0,f.reader.availableBytes("1",1,7,"stable",11,11));
        assertTrue(f.prefixOffsets.isEmpty());
        assertThrows(IllegalArgumentException.class,()->f.reader.chunk("1",1,7,"stable",11,11,1));
    }

    @Test void theProductionAdapterSeparatesMetadataReadsFromTheLongDownloadFuture(){
        var f=new Fixture();var telegram=mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);
        when(telegram.readInSession(eq(1L),any(TdApi.GetFile.class))).thenReturn(CompletableFuture.completedFuture(f.file));
        when(telegram.downloadInSession(eq(1L),any(TdApi.DownloadFile.class))).thenReturn(f.pending);
        var reader=new DownloadFileReader(telegram);
        assertTimeoutPreemptively(Duration.ofMillis(250),()->reader.startAt("1",1,7,"stable",11,5));
        verify(telegram).downloadInSession(eq(1L),argThat((TdApi.DownloadFile query)->query.synchronous&&query.offset==5));
        verify(telegram,never()).readInSession(eq(1L),any(TdApi.DownloadFile.class));
        assertFalse(f.pending.isDone());
    }

    private static final class Fixture implements DownloadFileReader.Gateway {
        final TdApi.File file = new TdApi.File();
        final CompletableFuture<TdApi.File> pending = new CompletableFuture<>();
        final List<TdApi.DownloadFile> downloads = new ArrayList<>();
        final List<Long> prefixOffsets = new ArrayList<>();
        String account="1"; long session=1,prefix;int partLength,readParts;
        final AtomicLong now=new AtomicLong();
        final DownloadFileReader reader = new DownloadFileReader(this, () -> account, () -> session,now::get);
        Fixture() {
            file.id = 7; file.size = 11; file.remote = new TdApi.RemoteFile("private", "stable", false, true, 11);
            file.local = new TdApi.LocalFile(); file.local.canBeDownloaded = true;
        }
        @Override @SuppressWarnings("unchecked")
        public <R extends TdApi.Object> CompletableFuture<R> read(long generation, TdApi.Function<R> query) {
            if (query instanceof TdApi.GetFile) return CompletableFuture.completedFuture((R) file);
            if (query instanceof TdApi.DownloadFile download) { downloads.add(download); return (CompletableFuture<R>) pending; }
            if (query instanceof TdApi.GetFileDownloadedPrefixSize get) {prefixOffsets.add(get.offset);return CompletableFuture.completedFuture((R)new TdApi.FileDownloadedPrefixSize(prefix));}
            if (query instanceof TdApi.ReadFilePart) {readParts++;return CompletableFuture.completedFuture((R)new TdApi.Data(new byte[partLength]));}
            throw new AssertionError("Unexpected native request");
        }
    }
}
