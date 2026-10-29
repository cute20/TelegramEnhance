package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadPersistenceTest {
    @TempDir Path directory;
    static Post post(String title) { return new Post("1:-1001:100", "1", "-1001", "channel", "100", title, "body", "0", List.of(new MessageRef("-1001", "100", "")), List.of(), null, null, List.of(), false, "UNAVAILABLE", List.of()); }
    static FrozenFile file(int size, String name) { return new FrozenFile(new Media("1:-1001:100:0", new MessageRef("-1001", "100", ""), "MAIN", "DOCUMENT", name, size, ""), "stable-unique", size, name); }
    @Test void creationIsIdempotentPerOperationAndCannotRewriteTheFrozenRequest() {
        try (var f = new Fixture()) {
            String op = UUID.randomUUID().toString(); var first = f.repository.create("1", op, "fingerprint", post("first"), f.root, List.of(file(10, "a.bin")));
            var duplicate = f.repository.create("1", op, "fingerprint", post("changed"), f.root, List.of(file(99, "b.bin")));
            assertEquals(first, duplicate); assertEquals(1, f.repository.list("1", 20, 0).size());
            assertThrows(IllegalArgumentException.class, () -> f.repository.create("1", op, "different", post("first"), f.root, List.of(file(10, "a.bin"))));
            assertTrue(f.repository.list("2", 20, 0).isEmpty()); assertNull(f.repository.byOperation("2", op));
            assertThrows(IllegalArgumentException.class, () -> f.repository.task("2", first.id()));
        }
    }
    @Test void aChangedPostTitleKeepsItsPreviouslyBoundDirectory() {
        try (var f = new Fixture()) {
            var first = f.task(10); var changed = f.repository.create("1", UUID.randomUUID().toString(), "next", post("new title"), f.root, List.of(file(10, "a.bin")));
            assertEquals(first.relativeDirectory(), changed.relativeDirectory());
        }
    }
    @Test void outputIsPublishedOnlyAfterSizeAndHashCheckAndNeverOverwritesAnUnownedCollision() throws Exception {
        try (var f = new Fixture()) {
            byte[] bytes = new byte[73]; Arrays.fill(bytes, (byte) 7); var task = f.task(bytes.length); var item = task.items().getFirst();
            Path output = f.store.directory(task).resolve(item.relativeFile()); Files.writeString(output, "existing-user-file");
            f.store.write(task, item, (offset, count) -> Arrays.copyOfRange(bytes, (int) offset, (int) offset + count), () -> {});
            var after = f.repository.task("1", task.id()).items().getFirst();
            assertEquals("existing-user-file", Files.readString(output)); assertEquals("COMPLETED", after.state()); assertNotEquals(item.relativeFile(), after.relativeFile());
            assertArrayEquals(bytes, Files.readAllBytes(output.getParent().resolve(after.relativeFile())));
            assertNotNull(f.repository.receipt("1", output.getParent().resolve(after.relativeFile())));
            try (var paths = Files.list(output.getParent())) { assertEquals(2, paths.count()); }
        }
    }
    @Test void ownedPartialDataResumesFromItsDurableCheckpointAndDiscardsOnlyItsVerifiedUncommittedTail() throws Exception {
        try (var f = new Fixture()) {
            byte[] bytes = new byte[1024 * 1024 + 17]; Arrays.fill(bytes, (byte) 3); var task = f.task(bytes.length); var item = task.items().getFirst();
            var calls = new AtomicInteger();
            assertThrows(DownloadFileStore.Stopped.class, () -> f.store.write(task, item, (offset, count) -> {
                if (calls.incrementAndGet() == 2) throw new DownloadFileStore.Stopped();
                return Arrays.copyOfRange(bytes, (int) offset, (int) offset + count);
            }, () -> {}));
            var saved = f.repository.task("1", task.id()).items().getFirst(); assertEquals(1024 * 1024, saved.writtenBytes()); assertNotNull(saved.sha256());
            Path part = f.store.directory(task).resolve("." + item.id() + ".part"); Files.write(part, new byte[]{9, 9}, java.nio.file.StandardOpenOption.APPEND);
            f.store.write(task, saved, (offset, count) -> { assertEquals(1024 * 1024, offset); return Arrays.copyOfRange(bytes, (int) offset, (int) offset + count); }, () -> {});
            var done = f.repository.task("1", task.id()).items().getFirst(); assertEquals("COMPLETED", done.state());
            assertArrayEquals(bytes, Files.readAllBytes(f.store.directory(task).resolve(done.relativeFile())));
        }
    }
    @Test void replacingAPartialFileOrChangingItsCommittedPrefixPreservesItAndRefusesToPublish() throws Exception {
        for (boolean replace : List.of(false, true)) try (var f = new Fixture()) {
            var task = f.task(1024 * 1024 + 5); var item = task.items().getFirst();
            assertThrows(DownloadFileStore.Stopped.class, () -> f.store.write(task, item, (offset, count) -> { if (offset > 0) throw new DownloadFileStore.Stopped(); return new byte[count]; }, () -> {}));
            var saved = f.repository.task("1", task.id()).items().getFirst(); Path dir = f.store.directory(task), part = dir.resolve("." + item.id() + ".part");
            if (replace) { Files.move(part, dir.resolve("keep-original-partial")); Files.write(part, new byte[1024 * 1024]); }
            else try (var stream = java.nio.channels.FileChannel.open(part, java.nio.file.StandardOpenOption.WRITE)) { stream.write(java.nio.ByteBuffer.wrap(new byte[]{7})); }
            assertThrows(IOException.class, () -> f.store.write(task, saved, (offset, count) -> new byte[count], () -> {}));
            assertTrue(Files.exists(part)); assertFalse(Files.exists(dir.resolve(item.relativeFile())));
        }
    }
    @Test void aCrashAfterPublishingRecoversTheReceiptWithoutReadingTheNativeFileAgain() throws Exception {
        try (var f = new Fixture()) {
            var task = f.task(7); var item = task.items().getFirst(); byte[] bytes = new byte[7]; String hash = java.util.HexFormat.of().formatHex(DownloadPaths.digest().digest(bytes));
            Path output = f.store.directory(task).resolve(item.relativeFile()); Files.write(output, bytes);
            f.repository.progress("1", task.id(), item.id(), "FINALIZING", 7, 7, hash, item.relativeFile());
            assertTrue(f.store.recoverPublished(f.repository.task("1", task.id()), f.repository.task("1", task.id()).items().getFirst(), () -> {}));
            assertEquals("COMPLETED", f.repository.task("1", task.id()).items().getFirst().state());
        }
    }
    @Test void completedHistorySkipsRenamedMovedOrModifiedFilesWithoutCheckingTheOldPath() throws Exception {
        try (var f = new Fixture()) {
            var first = f.task(13); f.store.write(first, first.items().getFirst(), (offset, count) -> new byte[count], () -> {});
            var next = f.repository.create("1", UUID.randomUUID().toString(), "new-name", post("renamed"), f.root, List.of(file(13, "renamed.bin")),DownloadModels.HISTORY_SKIP);
            assertEquals("SKIPPED_HISTORY", next.items().getFirst().state());
            assertEquals(0, f.repository.view(next).completed());assertEquals(1, f.repository.view(next).skipped());
            assertNull(f.repository.view(next).items().getFirst().outputPath());assertEquals(first.id(), next.items().getFirst().history().taskId());
            Path completed = f.store.directory(first).resolve(first.items().getFirst().relativeFile()); Files.write(completed, new byte[]{1});
            var third = f.task(13);assertEquals("SKIPPED_HISTORY", third.items().getFirst().state());assertArrayEquals(new byte[]{1}, Files.readAllBytes(completed));
            Files.move(completed, directory.resolve("moved-original.bin"));
            var fourth = f.repository.create("1", UUID.randomUUID().toString(), "elsewhere", post("title"), directory.resolve("never-created"), List.of(file(13,"again.bin")),DownloadModels.HISTORY_SKIP);
            assertEquals("COMPLETED", fourth.state());assertEquals(1, f.repository.view(fourth).skipped());assertFalse(Files.exists(Path.of(fourth.targetRoot())));
        }
    }
    @Test void historyNeverMatchesAnotherAccountAnotherVersionOrAnUnfinishedCheckpoint() throws Exception {
        try(var f=new Fixture()) {
            var first=f.task(13);f.store.write(first,first.items().getFirst(),(offset,count)->new byte[count],()->{});
            var otherAccount=f.repository.create("2",UUID.randomUUID().toString(),"account",post("title"),f.root,List.of(file(13,"test.bin")));
            assertEquals("QUEUED",otherAccount.items().getFirst().state());
            assertEquals("QUEUED",f.task(14).items().getFirst().state());
            var changedId=new FrozenFile(file(13,"test.bin").media(),"different-file",13,"test.bin");
            var changed=f.repository.create("1",UUID.randomUUID().toString(),"changed",post("title"),f.root,List.of(changedId));assertEquals("QUEUED",changed.items().getFirst().state());
            var partial=f.task(20);f.repository.progress("1",partial.id(),partial.items().getFirst().id(),"WRITING",20,10,"a".repeat(64),partial.items().getFirst().relativeFile());
            f.repository.finishState("1",partial.id(),"FAILED","synthetic");assertEquals("QUEUED",f.task(20).items().getFirst().state());
        }
    }
    @Test void aTaskQueuedBeforeAnotherSuccessIsSkippedAtExecutionAndSurvivesRestart() throws Exception {
        try(var f=new Fixture()) {
            var first=f.task(13);var queued=f.task(13);assertEquals("QUEUED",queued.items().getFirst().state());
            f.store.write(first,first.items().getFirst(),(offset,count)->new byte[count],()->{});
            assertTrue(f.repository.skipHistory(queued,queued.items().getFirst()));
            new DownloadRepository(f.database);var restored=f.repository.task("1",queued.id());
            assertEquals("SKIPPED_HISTORY",restored.items().getFirst().state());assertEquals(0,restored.items().getFirst().writtenBytes());
            assertEquals(first.id(),restored.items().getFirst().history().taskId());
            assertThrows(IllegalArgumentException.class,()->f.repository.complete("1",queued.id(),restored.items().getFirst().id(),restored.items().getFirst().relativeFile(),f.root.resolve("fake"),"a".repeat(64)));
        }
    }
    @Test void restartingPausesActiveTasksAndKeepsFilePhaseAndFrozenVersion() throws Exception {
        AppPaths paths = new AppPaths(directory.resolve("restart-data")); String id;
        try (var database = new Database(paths)) {
            var repository = new DownloadRepository(database); var task = repository.create("1", UUID.randomUUID().toString(), "fp", post("title"), directory.resolve("target").toAbsolutePath(), List.of(file(40, "a.bin"))); id = task.id();
            repository.next("1"); var item = task.items().getFirst(); repository.progress("1", id, item.id(), "WRITING", 40, 10, "saved-prefix", item.relativeFile());
        }
        try (var database = new Database(paths)) {
            var repository = new DownloadRepository(database); var task = repository.task("1", id);
            assertEquals("PAUSED", task.state()); assertTrue(task.pauseRequested()); assertEquals("WRITING", task.items().getFirst().state());
            assertEquals(10, task.items().getFirst().writtenBytes()); assertEquals("stable-unique", task.items().getFirst().file().uniqueId());
        }
    }
    @Test void namesAndRootValidationPreventWindowsReservedNamesAndBusinessDirectoryMixing() {
        try (var f = new Fixture()) {
            assertEquals("_CON", DownloadPaths.safe("CON", 40)); assertEquals("a_b_c", DownloadPaths.safe("a/b:c. ", 40));
            assertThrows(IllegalArgumentException.class, () -> f.paths.root("relative/path"));
            assertThrows(IllegalArgumentException.class, () -> f.paths.root(f.appPaths.root().resolve("media").toString()));
            assertFalse(DownloadPaths.file(file(10, "../CON?.mp4")).contains("/"));
        }
    }
    private final class Fixture implements AutoCloseable {
        final AppPaths appPaths = new AppPaths(directory.resolve(UUID.randomUUID().toString())); final Database database = new Database(appPaths);
        final DownloadRepository repository = new DownloadRepository(database); final DownloadPaths paths = new DownloadPaths(appPaths);
        final DownloadFileStore store = new DownloadFileStore(paths, repository); final Path root = directory.resolve("output").toAbsolutePath();
        Task task(int size) { return repository.create("1", UUID.randomUUID().toString(), UUID.randomUUID().toString(), post("title"), root, List.of(file(size, "test.bin")),DownloadModels.HISTORY_SKIP); }
        @Override public void close() { database.close(); }
    }
}
