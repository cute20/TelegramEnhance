package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.storage.DownloadSettings;
import io.github.telegramenhance.telegram.DownloadFileReader;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadFileStoreStreamingTest {
    private static final int BLOCK = 1024 * 1024;
    @TempDir Path directory;

    @Test void aFailedNetworkReadKeepsOnlyTheDownloadedAndWrittenCheckpoint() throws Exception {
        try (var f = new Fixture()) {
            byte[] bytes = data(BLOCK + 31); var task = f.task(bytes.length); var item = task.items().getFirst();
            var failure = new DownloadFileReader.TemporarilyUnavailable("connection dropped");
            var reads = new AtomicInteger();
            assertSame(failure, assertThrows(DownloadFileReader.TemporarilyUnavailable.class, () -> streaming(f.store, task, item, (offset, count) -> {
                var checkpoint = f.saved(task);
                assertEquals("DOWNLOADING", checkpoint.state());
                assertEquals(offset, checkpoint.downloadedBytes()); assertEquals(offset, checkpoint.writtenBytes());
                if (reads.incrementAndGet() == 2) { assertEquals(BLOCK, offset); throw failure; }
                assertEquals(0, offset); assertEquals(BLOCK, count);
                return Arrays.copyOfRange(bytes, (int) offset, (int) offset + count);
            }, () -> {})));
            var checkpoint = f.saved(task);
            assertEquals(2, reads.get()); assertEquals(BLOCK, checkpoint.downloadedBytes()); assertEquals(BLOCK, checkpoint.writtenBytes());
            assertTrue(checkpoint.downloadedBytes() < item.file().size());
            assertEquals(hash(Arrays.copyOf(bytes, BLOCK)), checkpoint.sha256());
            assertArrayEquals(Arrays.copyOf(bytes, BLOCK), Files.readAllBytes(f.part(task, item)));
            assertFalse(Files.exists(f.store.directory(task).resolve(item.relativeFile())));
        }
    }

    @Test void reopeningTheDatabaseResumesTheVerifiedPrefixWithoutRequestingEarlierBytes() throws Exception {
        byte[] bytes = data(BLOCK + 31); var appPaths = new AppPaths(directory.resolve("restart-data"));
        Path root = directory.resolve("output").toAbsolutePath(); String id;
        try (var database = new Database(appPaths)) {
            var repository = new DownloadRepository(database); var store = new DownloadFileStore(new DownloadPaths(appPaths), repository);
            var task = create(repository, root, bytes.length); id = task.id(); var item = task.items().getFirst();
            assertThrows(DownloadFileReader.TemporarilyUnavailable.class, () -> streaming(store, task, item, (offset, count) -> {
                if (offset != 0) throw new DownloadFileReader.TemporarilyUnavailable("connection dropped");
                return Arrays.copyOfRange(bytes, 0, count);
            }, () -> {}));
            Files.write(store.directory(task).resolve("." + item.id() + ".part"), new byte[]{9, 9}, StandardOpenOption.APPEND);
        }
        try (var database = new Database(appPaths)) {
            var repository = new DownloadRepository(database); var store = new DownloadFileStore(new DownloadPaths(appPaths), repository);
            var paused = repository.task("1", id); assertEquals("PAUSED", paused.state());
            assertEquals(BLOCK, paused.items().getFirst().writtenBytes()); assertEquals(BLOCK, paused.items().getFirst().downloadedBytes());
            repository.resume("1", id); var resumed = repository.next("1"); assertEquals(id, resumed.id());
            var reads = new AtomicInteger();
            streaming(store, resumed, resumed.items().getFirst(), (offset, count) -> {
                assertEquals(BLOCK, offset); assertEquals(31, count); reads.incrementAndGet();
                return Arrays.copyOfRange(bytes, (int) offset, (int) offset + count);
            }, () -> {});
            var completed = repository.task("1", id).items().getFirst();
            assertEquals(1, reads.get()); assertEquals("COMPLETED", completed.state());
            assertEquals(bytes.length, completed.downloadedBytes()); assertEquals(bytes.length, completed.writtenBytes());
            assertEquals(hash(bytes), completed.sha256());
            Path output = store.directory(resumed).resolve(completed.relativeFile());
            assertArrayEquals(bytes, Files.readAllBytes(output)); assertNotNull(repository.receipt("1", output));
            assertFalse(Files.exists(store.directory(resumed).resolve("." + completed.id() + ".part")));
        }
    }

    @Test void nativeBytesAheadOfTheOutputCheckpointAreNotReducedAfterSavingABlock() throws Exception {
        try (var f = new Fixture()) {
            var task = f.task(BLOCK + 31); var item = task.items().getFirst();
            assertThrows(DownloadFileReader.TemporarilyUnavailable.class, () -> streaming(f.store, task, item, (offset, count) -> {
                if (offset > 0) throw new DownloadFileReader.TemporarilyUnavailable("copy interrupted");
                f.repository.networkProgress("1", task.id(), item.id(), BLOCK + 31); return new byte[count];
            }, () -> {}));
            var saved = f.saved(task); assertEquals(BLOCK + 31, saved.downloadedBytes()); assertEquals(BLOCK, saved.writtenBytes());
            assertEquals(BLOCK, Files.size(f.part(task, item)));
        }
    }

    @Test void aChangedOrReplacedCommittedPrefixIsRejectedBeforeReadingMoreNetworkData() throws Exception {
        for (boolean replace : List.of(false, true)) try (var f = new Fixture()) {
            var task = f.task(BLOCK + 31); var item = task.items().getFirst();
            assertThrows(DownloadFileReader.TemporarilyUnavailable.class, () -> streaming(f.store, task, item, (offset, count) -> {
                if (offset > 0) throw new DownloadFileReader.TemporarilyUnavailable("connection dropped");
                return new byte[count];
            }, () -> {}));
            Path part = f.part(task, item);
            if (replace) { Files.move(part, part.resolveSibling("original-part")); Files.write(part, new byte[BLOCK]); }
            else try (var channel = FileChannel.open(part, StandardOpenOption.WRITE)) { channel.write(ByteBuffer.wrap(new byte[]{7})); }
            assertThrows(IOException.class, () -> streaming(f.store, task, f.saved(task), (offset, count) -> {
                fail("Changed partial bytes must be rejected before another network read"); return null;
            }, () -> {}));
            assertEquals(BLOCK, f.saved(task).writtenBytes()); assertEquals(BLOCK, f.saved(task).downloadedBytes());
            assertTrue(Files.exists(part)); assertFalse(Files.exists(f.store.directory(task).resolve(item.relativeFile())));
        }
    }

    @Test void aManualStopBetweenBlocksKeepsTheDurablePrefixAndDoesNotReadTheNextBlock() throws Exception {
        try (var f = new Fixture()) {
            byte[] bytes = data(BLOCK + 31); var task = f.task(bytes.length); var item = task.items().getFirst();
            var reads = new AtomicInteger();
            assertThrows(DownloadFileStore.Stopped.class, () -> streaming(f.store, task, item, (offset, count) -> {
                reads.incrementAndGet(); return Arrays.copyOfRange(bytes, (int) offset, (int) offset + count);
            }, () -> { if (f.saved(task).writtenBytes() == BLOCK) throw new DownloadFileStore.Stopped(); }));
            var checkpoint = f.saved(task); assertEquals(1, reads.get());
            assertEquals(BLOCK, checkpoint.downloadedBytes()); assertEquals(BLOCK, checkpoint.writtenBytes());
            assertEquals(hash(Arrays.copyOf(bytes, BLOCK)), checkpoint.sha256());
            assertArrayEquals(Arrays.copyOf(bytes, BLOCK), Files.readAllBytes(f.part(task, item)));
            assertFalse(Files.exists(f.store.directory(task).resolve(item.relativeFile())));
        }
    }

    @Test void anIncompleteSourceBlockDoesNotAdvanceTheCheckpointOrPublishAnOutput() throws Exception {
        try (var f = new Fixture()) {
            var task = f.task(73); var item = task.items().getFirst();
            assertThrows(IOException.class, () -> streaming(f.store, task, item, (offset, count) -> new byte[count - 1], () -> {}));
            assertEquals(0, f.saved(task).downloadedBytes()); assertEquals(0, f.saved(task).writtenBytes());
            assertEquals(0, Files.size(f.part(task, item)));
            assertFalse(Files.exists(f.store.directory(task).resolve(item.relativeFile())));
        }
    }

    @Test void aStopReceivedWhileWaitingForNetworkDataPreventsTheReturnedBlockFromBeingWritten() throws Exception {
        try (var f = new Fixture()) {
            var task = f.task(BLOCK + 31); var item = task.items().getFirst(); var stopped = new AtomicBoolean();
            assertThrows(DownloadFileStore.Stopped.class, () -> streaming(f.store, task, item, (offset, count) -> {
                stopped.set(true); return new byte[count];
            }, () -> { if (stopped.get()) throw new DownloadFileStore.Stopped(); }));
            assertEquals(0, f.saved(task).downloadedBytes()); assertEquals(0, f.saved(task).writtenBytes());
            assertEquals(0, Files.size(f.part(task, item)));
            assertFalse(Files.exists(f.store.directory(task).resolve(item.relativeFile())));
        }
    }

    @Test void ownershipChangedDuringANetworkReadIsRejectedBeforeTheReturnedBlockIsWritten() throws Exception {
        try (var f = new Fixture()) {
            var task = f.task(BLOCK + 31); var item = task.items().getFirst();
            Path owner = f.store.directory(task).resolve("." + item.id() + ".owner");
            assertThrows(IOException.class, () -> streaming(f.store, task, item, (offset, count) -> {
                try { Files.writeString(owner, "replacement-owner"); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
                return new byte[count];
            }, () -> {}));
            assertEquals(0, f.saved(task).downloadedBytes()); assertEquals(0, f.saved(task).writtenBytes());
            assertEquals(0, Files.size(f.part(task, item))); assertEquals("replacement-owner", Files.readString(owner));
            assertFalse(Files.exists(f.store.directory(task).resolve(item.relativeFile())));
        }
    }

    @Test void completeCacheWritesStillReportTheWholeSourceDownloadedBeforeCopying() throws Exception {
        try (var f = new Fixture()) {
            var task = f.task(BLOCK + 31); var item = task.items().getFirst();
            assertThrows(DownloadFileStore.Stopped.class, () -> f.store.write(task, item, (offset, count) -> {
                var checkpoint = f.saved(task); assertEquals("WRITING", checkpoint.state());
                assertEquals(BLOCK + 31, checkpoint.downloadedBytes()); assertEquals(offset, checkpoint.writtenBytes());
                if (offset > 0) throw new DownloadFileStore.Stopped();
                return new byte[count];
            }, () -> {}));
            assertEquals(BLOCK + 31, f.saved(task).downloadedBytes()); assertEquals(BLOCK, f.saved(task).writtenBytes());
        }
    }

    @Test void fullyVerifiedStreamingContentUsesTheFrozenContentDuplicatePreference() throws Exception {
        try (var f = new Fixture()) {
            new DownloadSettings(f.database).save(new DownloadSettings.Value(DownloadModels.HISTORY_SKIP));
            var firstFile = DownloadPersistenceTest.file(73, "first.bin");
            var secondFile = new FrozenFile(firstFile.media(), "other-unknown-file", 73, "second.bin");
            var first = f.repository.create("1", UUID.randomUUID().toString(), "first", DownloadPersistenceTest.post("stream"), f.root, List.of(firstFile));
            var second = f.repository.create("1", UUID.randomUUID().toString(), "second", DownloadPersistenceTest.post("stream"), f.root, List.of(secondFile));
            byte[] bytes = data(73);
            streaming(f.store, first, first.items().getFirst(), (offset, count) -> bytes, () -> {});
            streaming(f.store, second, second.items().getFirst(), (offset, count) -> bytes, () -> {});
            assertEquals("COMPLETED", f.saved(first).state()); assertEquals("SKIPPED_HISTORY", f.saved(second).state());
            assertEquals(1, f.repository.view(f.repository.task("1", second.id())).skipped());
            try (var entries = Files.list(f.store.directory(first))) { assertEquals(1, entries.count()); }
        }
    }

    @Test void aFailedFinalReceiptKeepsTheCompleteVerifiedPartialForALaterAttempt() throws Exception {
        try (var f = new Fixture()) {
            byte[] bytes = data(BLOCK + 31); var task = f.task(bytes.length); var item = task.items().getFirst();
            var failure = new DownloadFileReader.TemporarilyUnavailable("final receipt unavailable"); var callbacks = new AtomicInteger();
            assertSame(failure, assertThrows(DownloadFileReader.TemporarilyUnavailable.class, () -> streaming(f.store, task, item,
                    (offset, count) -> Arrays.copyOfRange(bytes, (int) offset, (int) offset + count), () -> {}, () -> {
                        callbacks.incrementAndGet(); var saved = f.saved(task);
                        assertEquals("DOWNLOADING", saved.state()); assertEquals(bytes.length, saved.downloadedBytes()); assertEquals(bytes.length, saved.writtenBytes());
                        assertNotNull(saved.sha256()); throw failure;
                    })));
            var saved = f.saved(task); assertEquals(1, callbacks.get()); assertEquals("DOWNLOADING", saved.state());
            assertEquals(hash(bytes), saved.sha256()); assertArrayEquals(bytes, Files.readAllBytes(f.part(task, item)));
            assertFalse(Files.exists(f.store.directory(task).resolve(item.relativeFile())));
            streaming(f.store, task, saved, (offset, count) -> { fail("A verified complete partial must not be downloaded again"); return null; }, () -> {});
            var done = f.saved(task); assertEquals("COMPLETED", done.state());
            assertArrayEquals(bytes, Files.readAllBytes(f.store.directory(task).resolve(done.relativeFile())));
        }
    }

    @Test void waitingForTheFinalReceiptDoesNotPublishTheAlreadySavedBytes() throws Exception {
        try (var f = new Fixture()) {
            byte[] bytes = data(73); var task = f.task(bytes.length); var item = task.items().getFirst();
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var worker = Executors.newSingleThreadExecutor();
            try {
                var result = worker.submit(() -> {
                    streaming(f.store, task, item, (offset, count) -> bytes, () -> {}, () -> {
                        entered.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Final receipt was not released"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new DownloadFileStore.Stopped(); }
                    }); return null;
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS)); assertFalse(result.isDone());
                var saved = f.saved(task); assertEquals("DOWNLOADING", saved.state()); assertEquals(73, saved.writtenBytes());
                assertArrayEquals(bytes, Files.readAllBytes(f.part(task, item)));
                Path output = f.store.directory(task).resolve(item.relativeFile()); assertFalse(Files.exists(output)); assertNull(f.repository.receipt("1", output));
                release.countDown(); result.get(5, TimeUnit.SECONDS);
                assertEquals("COMPLETED", f.saved(task).state()); assertArrayEquals(bytes, Files.readAllBytes(output));
            } finally { release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
        }
    }

    @Test void aPauseDuringFinalReceiptConfirmationPreventsTheWritingAndPublicationTransition() throws Exception {
        try (var f = new Fixture()) {
            var task = f.task(73); var item = task.items().getFirst(); var stopped = new AtomicBoolean(); var callbacks = new AtomicInteger();
            assertThrows(DownloadFileStore.Stopped.class, () -> streaming(f.store, task, item, (offset, count) -> data(count),
                    () -> { if (stopped.get()) throw new DownloadFileStore.Stopped(); }, () -> { callbacks.incrementAndGet(); stopped.set(true); }));
            assertEquals(1, callbacks.get()); assertEquals("DOWNLOADING", f.saved(task).state());
            assertEquals(73, f.saved(task).writtenBytes()); assertEquals(73, Files.size(f.part(task, item)));
            assertFalse(Files.exists(f.store.directory(task).resolve(item.relativeFile())));
        }
    }

    @Test void finalReceiptConfirmationDoesNotRunWhenTheFullPartialFailsHashVerification() throws Exception {
        try (var f = new Fixture()) {
            var task = f.task(73); var item = task.items().getFirst(); var changed = new AtomicBoolean(); var confirmed = new AtomicBoolean();
            assertThrows(IOException.class, () -> streaming(f.store, task, item, (offset, count) -> data(count), () -> {
                if (f.saved(task).writtenBytes() == 73 && changed.compareAndSet(false, true)) try (var file = FileChannel.open(f.part(task, item), StandardOpenOption.WRITE)) {
                    file.write(ByteBuffer.wrap(new byte[]{99}));
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            }, () -> confirmed.set(true)));
            assertTrue(changed.get()); assertFalse(confirmed.get()); assertEquals("DOWNLOADING", f.saved(task).state());
            assertFalse(Files.exists(f.store.directory(task).resolve(item.relativeFile())));
        }
    }

    @Test void changingVerifiedBytesWhileAwaitingTheFinalReceiptCannotPublishAnIncorrectHash() throws Exception {
        try (var f = new Fixture()) {
            var task = f.task(73); var item = task.items().getFirst();
            assertThrows(IOException.class, () -> streaming(f.store, task, item, (offset, count) -> data(count), () -> {}, () -> {
                try {
                    Path part = f.part(task, item); String identity = DownloadFileStore.fileKey(part);
                    byte[] changed = new byte[73]; Arrays.fill(changed, (byte) 7); Files.write(part, changed);
                    assertEquals(identity, DownloadFileStore.fileKey(part), "The owner identity alone cannot detect an in-place content change");
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            }));
            assertEquals("DOWNLOADING", f.saved(task).state()); assertEquals(73, f.saved(task).writtenBytes());
            Path output = f.store.directory(task).resolve(item.relativeFile()); assertFalse(Files.exists(output)); assertNull(f.repository.receipt("1", output));
            byte[] changed = new byte[73]; Arrays.fill(changed, (byte) 7); assertArrayEquals(changed, Files.readAllBytes(f.part(task, item)));
        }
    }

    @Test void ownershipChangedWhileAwaitingTheFinalReceiptCannotEnterThePublicationPhase() throws Exception {
        try (var f = new Fixture()) {
            var task = f.task(73); var item = task.items().getFirst();
            Path owner = f.store.directory(task).resolve("." + item.id() + ".owner");
            assertThrows(IOException.class, () -> streaming(f.store, task, item, (offset, count) -> data(count), () -> {}, () -> {
                try { Files.writeString(owner, "replacement-owner"); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            }));
            assertEquals("DOWNLOADING", f.saved(task).state()); assertEquals("replacement-owner", Files.readString(owner));
            assertEquals(73, Files.size(f.part(task, item))); assertFalse(Files.exists(f.store.directory(task).resolve(item.relativeFile())));
        }
    }

    private static Task create(DownloadRepository repository, Path root, int size) {
        return repository.create("1", UUID.randomUUID().toString(), UUID.randomUUID().toString(), DownloadPersistenceTest.post("stream"), root,
                List.of(DownloadPersistenceTest.file(size, "stream.bin")), DownloadModels.KEEP_DUPLICATES);
    }
    private static byte[] data(int size) {
        byte[] bytes = new byte[size]; for (int i = 0; i < size; i++) bytes[i] = (byte) (i % 251); return bytes;
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void streaming(DownloadFileStore store, Task task, Item item, DownloadFileStore.Content content, Runnable check) throws IOException {
        store.writeStreaming(task, item, content, check);
    }
    private static void streaming(DownloadFileStore store, Task task, Item item, DownloadFileStore.Content content, Runnable check, Runnable beforePublish) throws IOException {
        store.writeStreaming(task, item, content, check, beforePublish);
    }
    private final class Fixture implements AutoCloseable {
        final AppPaths appPaths = new AppPaths(directory.resolve(UUID.randomUUID().toString())); final Database database = new Database(appPaths);
        final DownloadRepository repository = new DownloadRepository(database);
        final DownloadFileStore store = new DownloadFileStore(new DownloadPaths(appPaths), repository);
        final Path root = directory.resolve(UUID.randomUUID().toString()).toAbsolutePath();
        Task task(int size) { return create(repository, root, size); }
        Item saved(Task task) { return repository.task("1", task.id()).items().getFirst(); }
        Path part(Task task, Item item) throws IOException { return store.directory(task).resolve("." + item.id() + ".part"); }
        @Override public void close() { database.close(); }
    }
}
