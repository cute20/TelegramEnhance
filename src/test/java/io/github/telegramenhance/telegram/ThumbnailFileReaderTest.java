package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;

import it.tdlight.jni.TdApi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThumbnailFileReaderTest {
    @TempDir Path directory;
    private static final byte[] BYTES = {1, 2, 3, 4};

    @Test
    void existingCompleteCacheUsesOnlyExplicitNativeByteReadAndIsNeverDeleted() {
        Scenario s = new Scenario(file(7, "unique-7", 4, true));
        var reader = s.reader(ledger());
        byte[] data = reader.read(s.current, "1", deadline());
        assertArrayEquals(BYTES, data); assertNotSame(BYTES, data);
        assertEquals(1, s.reads); assertEquals(0, s.downloads); assertEquals(0, s.cancels); assertEquals(0, s.deletes);
        assertFalse(Files.exists(ledger()));
        assertEquals(0, s.lastRead.offset); assertEquals(4, s.lastRead.count);
        assertEquals("this-path-must-never-be-opened", s.current.local.path);
    }

    @Test
    void ownedDownloadIsRegisteredBeforeNativeDownloadAndRemovedAfterReadCancelAndDelete() throws Exception {
        Scenario s = new Scenario(file(7, "unique-7", 4, false));
        s.beforeDownload = () -> {
            assertTrue(Files.isRegularFile(ledger()));
            try { assertTrue(Files.readString(ledger()).contains("unique-7")); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        assertArrayEquals(BYTES, s.reader(ledger()).read(s.current, "1", deadline()));
        assertEquals(1, s.downloads); assertEquals(1, s.cancels); assertEquals(1, s.deletes);
        assertEquals(1, s.lastDownload.priority); assertEquals(0, s.lastDownload.offset);
        assertEquals(ThumbnailFileReader.MAX_BYTES + 1L, s.lastDownload.limit); assertTrue(s.lastDownload.synchronous);
        assertEquals("[]", Files.readString(ledger()));
        assertFalse(Files.exists(ledger().resolveSibling(ledger().getFileName() + ".tmp")));
    }

    @Test
    void externalActiveTransferUnknownSizeOversizeAndMissingUniqueIdAreNotTakenOver() {
        List<TdApi.File> invalid = new ArrayList<>();
        TdApi.File active = file(7, "unique-7", 4, false); active.local.isDownloadingActive = true; invalid.add(active);
        invalid.add(file(7, "unique-7", 0, false));
        invalid.add(file(7, "unique-7", ThumbnailFileReader.MAX_BYTES + 1L, false));
        TdApi.File expectedTooLarge = file(7, "unique-7", 4, false); expectedTooLarge.expectedSize = ThumbnailFileReader.MAX_BYTES + 1L; invalid.add(expectedTooLarge);
        invalid.add(file(7, "", 4, false));
        for (TdApi.File candidate : invalid) {
            Scenario s = new Scenario(candidate);
            assertNull(s.reader(ledger()).read(candidate, "1", deadline()));
            assertEquals(0, s.downloads); assertEquals(0, s.reads); assertEquals(0, s.cancels); assertEquals(0, s.deletes);
        }
    }

    @Test
    void candidateAndFreshGetFileMustHaveTheSameFileAndUniqueIdentity() {
        Scenario mismatch = new Scenario(file(7, "different-remote-file", 4, true));
        assertNull(mismatch.reader(ledger()).read(file(7, "expected-remote-file", 4, true), "1", deadline()));
        assertEquals(0, mismatch.reads); assertEquals(0, mismatch.deletes);
        Scenario wrongId = new Scenario(file(8, "unique-7", 4, true));
        assertNull(wrongId.reader(ledger()).read(file(7, "unique-7", 4, true), "1", deadline()));
        assertEquals(0, wrongId.reads); assertEquals(0, wrongId.deletes);
    }

    @Test
    void partialOrOversizedDownloadCannotBeReadButItsOwnedFileIsStillCleaned() throws Exception {
        for (boolean oversized : List.of(false, true)) {
            Files.deleteIfExists(ledger());
            Scenario s = new Scenario(file(7, "unique-7", 4, false));
            s.downloadResult = file(7, "unique-7", oversized ? ThumbnailFileReader.MAX_BYTES + 1L : 4, false);
            s.downloadResult.local.downloadedPrefixSize = 4;
            assertNull(s.reader(ledger()).read(s.current, "1", deadline()));
            assertEquals(0, s.reads); assertEquals(1, s.cancels); assertEquals(1, s.deletes);
            assertEquals("[]", Files.readString(ledger()));
        }
    }

    @Test
    void completedFlagOffsetAndExactReadLengthAreAllRequired() throws Exception {
        Scenario badOffset = new Scenario(file(7, "unique-7", 4, true));
        badOffset.current.local.downloadOffset = 1;
        assertNull(badOffset.reader(ledger()).read(badOffset.current, "1", deadline()));
        assertEquals(0, badOffset.reads); assertEquals(0, badOffset.deletes);

        Scenario wrongLength = new Scenario(file(7, "unique-7", 4, false));
        wrongLength.bytes = new byte[]{1, 2, 3};
        assertNull(wrongLength.reader(ledger()).read(wrongLength.current, "1", deadline()));
        assertEquals(1, wrongLength.reads); assertEquals(1, wrongLength.deletes);
        assertEquals("[]", Files.readString(ledger()));
    }

    @Test
    void downloadTimeoutAndInterruptionAttemptCleanupAndDoNotLeakNativeFailureText() throws Exception {
        Scenario timeout = new Scenario(file(7, "unique-7", 4, false));
        timeout.downloadFuture = new CompletableFuture<>();
        assertNull(timeout.reader(ledger()).read(timeout.current, "1", System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)));
        assertEquals(1, timeout.downloads); assertEquals(1, timeout.cancels); assertEquals(1, timeout.deletes);
        assertEquals("[]", Files.readString(ledger()));

        Scenario interrupted = new Scenario(file(7, "unique-7", 4, false));
        interrupted.downloadFuture = new CompletableFuture<>() {
            @Override public TdApi.File get(long timeout, TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("private-native-detail");
            }
        };
        try {
            assertNull(interrupted.reader(ledger()).read(interrupted.current, "1", deadline()));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, interrupted.cancels); assertEquals(1, interrupted.deletes);
        } finally { Thread.interrupted(); }
    }

    @Test
    void cleanupFailureKeepsLedgerAndARecreatedReaderRetriesBeforeForgettingOwnership() throws Exception {
        Scenario s = new Scenario(file(7, "unique-7", 4, false));
        s.failDelete = true;
        assertArrayEquals(BYTES, s.reader(ledger()).read(s.current, "1", deadline()));
        assertTrue(Files.readString(ledger()).contains("unique-7"));
        int previousDeletes = s.deletes;
        s.failDelete = false;
        assertArrayEquals(BYTES, s.reader(ledger()).read(s.current, "1", deadline()));
        assertTrue(s.deletes > previousDeletes);
        assertEquals("[]", Files.readString(ledger()));
    }

    @Test
    void reusedFileIdOrAnotherAccountCanNeverAuthorizeDeletingTheNewCache() throws Exception {
        Scenario first = new Scenario(file(7, "old-unique", 4, false)); first.failDelete = true;
        first.reader(ledger()).read(first.current, "1", deadline());
        String pending = Files.readString(ledger());
        Scenario reused = new Scenario(file(7, "new-unique", 4, true));
        assertArrayEquals(BYTES, reused.reader(ledger()).read(reused.current, "1", deadline()));
        assertEquals(0, reused.cancels); assertEquals(0, reused.deletes);
        assertEquals(pending, Files.readString(ledger()), "Unconfirmed leftovers retain their bounded ownership slot");
        reused.account.set("2");
        assertArrayEquals(BYTES, reused.reader(ledger()).read(reused.current, "2", deadline()));
        assertEquals(0, reused.cancels); assertEquals(0, reused.deletes);
        assertEquals(pending, Files.readString(ledger()));
    }

    @Test
    void fullOrUnreadableLedgerBlocksNewDownloadsButExistingCacheRemainsUsable() throws Exception {
        StringBuilder full = new StringBuilder("[");
        for (int index = 1; index <= 128; index++) {
            if (index > 1) full.append(',');
            full.append("{\"account\":\"other\",\"fileId\":").append(index).append(",\"uniqueId\":\"uid-").append(index).append("\"}");
        }
        Files.writeString(ledger(), full.append(']').toString());
        Scenario pending = new Scenario(file(1000, "new-unique", 4, false));
        assertNull(pending.reader(ledger()).read(pending.current, "1", deadline()));
        assertEquals(0, pending.downloads); assertTrue(Files.size(ledger()) <= 64 * 1024);
        Scenario cached = new Scenario(file(1000, "new-unique", 4, true));
        assertArrayEquals(BYTES, cached.reader(ledger()).read(cached.current, "1", deadline()));
        assertEquals(0, cached.deletes);
        Files.writeString(ledger(), "x".repeat(64 * 1024 + 1));
        assertNull(pending.reader(ledger()).read(pending.current, "1", deadline()));
        assertEquals(0, pending.downloads);
        assertArrayEquals(BYTES, cached.reader(ledger()).read(cached.current, "1", deadline()));
    }

    @Test
    void unavailableLedgerStoragePreventsStartingAnUntrackedDownload() throws Exception {
        Path parentFile = directory.resolve("ordinary-file"); Files.writeString(parentFile, "keep");
        Scenario s = new Scenario(file(7, "unique-7", 4, false));
        assertNull(s.reader(parentFile.resolve("ledger.json")).read(s.current, "1", deadline()));
        assertEquals(0, s.downloads); assertEquals("keep", Files.readString(parentFile));
    }

    @Test
    void sameAccountSessionAbaCannotSendFinallyCleanupToTheNewClient() throws Exception {
        Scenario s = new Scenario(file(7, "unique-7", 4, false));
        AtomicLong generation = new AtomicLong(1);
        List<Long> sentGenerations = new ArrayList<>();
        var gateway = new ThumbnailFileReader.SessionGateway() {
            @Override public <R extends TdApi.Object> CompletableFuture<R> read(long selected, TdApi.Function<R> query) {
                sentGenerations.add(selected);
                CompletableFuture<R> result = s.read(query);
                if (query instanceof TdApi.DownloadFile) generation.set(2);
                return result;
            }
        };
        var reader = new ThumbnailFileReader(gateway, s.account::get, ledger(), generation::get);
        assertThrows(IllegalArgumentException.class, () -> reader.read(s.current, "1", deadline()));
        assertEquals(List.of(1L, 1L), sentGenerations);
        assertEquals(0, s.cancels); assertEquals(0, s.deletes);
        assertTrue(Files.readString(ledger()).contains("unique-7"));
        assertArrayEquals(BYTES, reader.read(s.current, "1", deadline()));
        assertEquals("[]", Files.readString(ledger()));
        assertTrue(sentGenerations.subList(2, sentGenerations.size()).stream().allMatch(value -> value == 2L));
    }

    @Test
    void accountChangingDuringDownloadLeavesOwnershipForTheCorrectAccountsLaterCleanup() throws Exception {
        Scenario s = new Scenario(file(7, "unique-7", 4, false));
        s.afterDownload = () -> s.account.set("2");
        assertThrows(IllegalArgumentException.class, () -> s.reader(ledger()).read(s.current, "1", deadline()));
        assertEquals(0, s.cancels); assertEquals(0, s.deletes);
        assertTrue(Files.readString(ledger()).contains("unique-7"));
    }

    @Test
    void aSecondPostCannotReadOrDeleteTheSameFileWhileTheFirstOperationIsInFlight() throws Exception {
        Scenario s = new Scenario(file(7, "unique-7", 4, false));
        CountDownLatch downloadStarted = new CountDownLatch(1);
        s.downloadFuture = new CompletableFuture<>(); s.beforeDownload = downloadStarted::countDown;
        var reader = s.reader(ledger());
        TdApi.File candidate = s.current;
        var first = CompletableFuture.supplyAsync(() -> reader.read(candidate, "1", deadline()));
        assertTrue(downloadStarted.await(2, TimeUnit.SECONDS));
        var second = CompletableFuture.supplyAsync(() -> reader.read(candidate, "1", deadline()));
        try {
            assertThrows(java.util.concurrent.TimeoutException.class, () -> second.get(50, TimeUnit.MILLISECONDS));
            assertEquals(1, s.downloads);
            TdApi.File completed = file(7, "unique-7", 4, true); s.current = completed;
            CompletableFuture<TdApi.File> blocked = s.downloadFuture; s.downloadFuture = null; blocked.complete(completed);
            assertArrayEquals(BYTES, first.get(3, TimeUnit.SECONDS));
            assertArrayEquals(BYTES, second.get(3, TimeUnit.SECONDS));
            assertEquals(2, s.downloads); assertEquals(2, s.deletes);
        } finally { s.downloadFuture = null; }
    }

    private Path ledger() { return directory.resolve("thumbnail-ledger.json"); }
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(8); }
    private static TdApi.File file(int id, String unique, long size, boolean completed) {
        var local = new TdApi.LocalFile("this-path-must-never-be-opened", true, true, false, completed, 0,
            completed ? size : 0, completed ? size : 0);
        return new TdApi.File(id, size, size, local, new TdApi.RemoteFile("remote-id", unique, false, true, size));
    }

    private static final class Scenario implements ResourceReader.Gateway {
        final AtomicReference<String> account = new AtomicReference<>("1");
        volatile TdApi.File current;
        TdApi.File downloadResult;
        volatile CompletableFuture<TdApi.File> downloadFuture;
        byte[] bytes = BYTES;
        boolean failDelete;
        int downloads, reads, cancels, deletes;
        TdApi.DownloadFile lastDownload;
        TdApi.ReadFilePart lastRead;
        Runnable beforeDownload = () -> {}, afterDownload = () -> {};
        Scenario(TdApi.File current) { this.current = current; }
        ThumbnailFileReader reader(Path ledger) { return new ThumbnailFileReader(this, account::get, ledger); }
        @Override @SuppressWarnings("unchecked")
        public <R extends TdApi.Object> CompletableFuture<R> read(TdApi.Function<R> query) {
            TdApi.Object result;
            if (query instanceof TdApi.GetFile) result = current;
            else if (query instanceof TdApi.DownloadFile download) {
                downloads++; lastDownload = download; beforeDownload.run();
                if (downloadFuture != null) return (CompletableFuture<R>) downloadFuture;
                current = downloadResult == null ? file(current.id, current.remote.uniqueId, current.size, true) : downloadResult;
                result = current; afterDownload.run();
            } else if (query instanceof TdApi.ReadFilePart part) { reads++; lastRead = part; result = new TdApi.Data(bytes); }
            else if (query instanceof TdApi.CancelDownloadFile) { cancels++; current.local.isDownloadingActive = false; result = new TdApi.Ok(); }
            else if (query instanceof TdApi.DeleteFile) {
                deletes++;
                if (failDelete) return CompletableFuture.failedFuture(new IllegalStateException("private-native-path-and-error"));
                current = file(current.id, current.remote.uniqueId, current.size, false); result = new TdApi.Ok();
            } else throw new AssertionError("Unexpected native operation");
            return CompletableFuture.completedFuture((R) result);
        }
    }
}
