package io.github.telegramenhance.transfer;

import static io.github.telegramenhance.transfer.DownloadModels.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.storage.DownloadSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadDuplicateSettingsTest {
    @TempDir Path root;
    private final byte[] content = "verified duplicate settings media".getBytes(StandardCharsets.UTF_8);

    @Test void defaultCreationFreezesTheCurrentSettingWhileExplicitLegacyCreationKeepsItsContract() {
        try (var f = new Fixture()) {
            var keep = f.task("1", "101", "first", content.length, "default-keep");
            assertEquals(KEEP_DUPLICATES, keep.duplicatePolicy());
            assertEquals(0, f.flag(keep));
            f.policy(HISTORY_SKIP);
            var skip = f.task("1", "102", "second", content.length, "default-skip");
            assertEquals(HISTORY_SKIP, skip.duplicatePolicy());
            assertEquals(1, f.flag(skip));
            var legacy = f.legacy("1", "103", "third", content.length, "explicit-legacy");
            assertEquals(HISTORY_SKIP, legacy.duplicatePolicy());
            assertEquals(0, f.flag(legacy));
            f.policy(KEEP_DUPLICATES);
            var next = f.task("1", "104", "fourth", content.length, "next-keep");
            assertEquals(KEEP_DUPLICATES, next.duplicatePolicy());
            assertEquals(0, f.flag(next));
            assertEquals(HISTORY_SKIP, f.fresh(skip).duplicatePolicy());
            assertEquals(1, f.flag(skip));
            assertEquals(KEEP_DUPLICATES, f.fresh(keep).duplicatePolicy());
        }
    }

    @Test void settingsChangesAndDatabaseReopenDoNotRewriteAnExistingOperation() {
        AppPaths paths;
        Task before;
        String operation = UUID.randomUUID().toString();
        try (var f = new Fixture()) {
            paths = f.app;
            f.policy(HISTORY_SKIP);
            before = f.repo.create("1", operation, "fixed", f.post("1", "1"), root.resolve("frozen"),
                    List.of(f.file("1", "101", "original", content.length)));
            f.policy(KEEP_DUPLICATES);
            var retried = f.repo.create("1", operation, "fixed", f.post("1", "1"), root.resolve("frozen"),
                    List.of(f.file("1", "101", "original", content.length)));
            assertEquals(before, retried);
            assertEquals(1, f.flag(retried));
        }
        try (var database = new Database(paths)) {
            var repository = new DownloadRepository(database);
            var restored = repository.byOperation("1", operation);
            assertEquals(before.id(), restored.id());
            assertEquals(HISTORY_SKIP, restored.duplicatePolicy());
            assertEquals(before.items(), restored.items());
            assertEquals(KEEP_DUPLICATES, new DownloadSettings(database).get().duplicatePolicy());
            assertEquals(1, flag(database, restored));
            var retry = repository.create("1", operation, "fixed", before.post(), Path.of(before.targetRoot()),
                    before.items().stream().map(Item::file).toList());
            assertEquals(restored, retry);
        }
    }

    @Test void callerOwnedTransactionsAndAllBatchChildrenUseOneFrozenSetting() {
        try (var f = new Fixture()) {
            for (String policy : List.of(HISTORY_SKIP, KEEP_DUPLICATES)) {
                f.policy(policy);
                var ruleTask = f.db.write(connection -> f.repo.createInTransaction(connection, "1",
                        UUID.randomUUID().toString(), "rule-" + policy, f.post("1", "1"),
                        root.resolve("rule-" + policy), List.of(f.file("1", "101", "rule-" + policy, content.length))));
                assertEquals(policy, ruleTask.duplicatePolicy());
                assertEquals(HISTORY_SKIP.equals(policy) ? 1 : 0, f.flag(ruleTask));

                String operation = UUID.randomUUID().toString();
                Path output = root.resolve("batch-" + policy);
                var jobs = List.of(
                        new DownloadBatchModels.Job(f.post("1", "1"), List.of(f.file("1", "102", "batch-a-" + policy, content.length))),
                        new DownloadBatchModels.Job(f.post("1", "2"), List.of(f.file("1", "103", "batch-b-" + policy, content.length))));
                var snapshot = f.snapshot(output, 2, 2, 2L * content.length);
                var batch = f.repo.createBatch("1", operation, "batch-" + policy, snapshot, output, jobs);
                assertEquals(2, batch.taskIds().size());
                for (String id : batch.taskIds()) {
                    var child = f.repo.task("1", id);
                    assertEquals(policy, child.duplicatePolicy());
                    assertEquals(HISTORY_SKIP.equals(policy) ? 1 : 0, f.flag(child));
                }
                f.policy(HISTORY_SKIP.equals(policy) ? KEEP_DUPLICATES : HISTORY_SKIP);
                assertEquals(batch, f.repo.createBatch("1", operation, "batch-" + policy, snapshot, output, jobs));
                assertTrue(batch.taskIds().stream().allMatch(id -> policy.equals(f.repo.task("1", id).duplicatePolicy())));
            }
        }
    }

    @Test void knownFileIdentitySkipsFromTheDatabaseAfterThePreviouslySavedMediaWasMoved() throws Exception {
        try (var f = new Fixture()) {
            var first = f.task("1", "101", "same-file", content.length, "first");
            f.save(first, content);
            Path moved = root.resolve("moved-original.bin");
            Files.move(f.output(first), moved);
            f.policy(HISTORY_SKIP);
            var second = f.task("1", "102", "same-file", content.length, "never-created");
            var item = second.items().getFirst();
            assertEquals("COMPLETED", second.state());
            assertEquals("SKIPPED_HISTORY", item.state());
            assertEquals(first.items().getFirst().id(), item.history().id());
            assertEquals(0, item.downloadedBytes());
            assertEquals(0, item.writtenBytes());
            assertNull(f.repo.view(second).items().getFirst().outputPath());
            assertFalse(Files.exists(Path.of(second.targetRoot())));
            assertArrayEquals(content, Files.readAllBytes(moved));
            assertEquals(1, f.logCount());
        }
    }

    @Test void differentFileIdentityWithTheSameVerifiedContentIsReadButNotPublishedOrLoggedAgain() throws Exception {
        try (var f = new Fixture()) {
            var first = f.task("1", "101", "file-a", content.length, "first");
            f.save(first, content);
            Files.move(f.output(first), root.resolve("moved-before-sha-comparison.bin"));
            f.policy(HISTORY_SKIP);
            var second = f.task("1", "102", "file-b", content.length, "skip-output");
            assertEquals("QUEUED", second.items().getFirst().state());
            var reads = new AtomicInteger();
            f.store.write(second, second.items().getFirst(), (offset, count) -> {
                reads.incrementAndGet();
                return Arrays.copyOfRange(content, (int) offset, (int) offset + count);
            }, () -> { });
            assertTrue(reads.get() > 0);
            var skipped = f.fresh(second).items().getFirst();
            assertEquals("SKIPPED_HISTORY", skipped.state());
            assertEquals(first.items().getFirst().id(), skipped.history().id());
            assertEquals(DownloadPaths.hash(new String(content, StandardCharsets.UTF_8)), skipped.sha256());
            assertEquals(0, skipped.downloadedBytes());
            assertEquals(0, skipped.writtenBytes());
            assertNull(skipped.plan());
            var view = f.repo.view(f.fresh(second));
            assertEquals(0, view.completed());
            assertEquals(1, view.skipped());
            assertNull(view.items().getFirst().outputPath());
            assertEquals(1, f.logCount());
            f.assertNoOutputFiles(second);
        }
    }

    @Test void keepModeStillPublishesIdenticalContentToTheDuplicateDirectory() throws Exception {
        try (var f = new Fixture()) {
            f.save(f.task("1", "101", "file-a", content.length, "first"), content);
            f.policy(KEEP_DUPLICATES);
            var second = f.task("1", "102", "file-b", content.length, "keep-output");
            f.save(second, content);
            var item = f.fresh(second).items().getFirst();
            assertEquals("COMPLETED", item.state());
            assertEquals("CONTENT_SHA", item.plan().duplicate().reason());
            assertEquals("重复媒体", f.output(second).getParent().getFileName().toString());
            assertArrayEquals(content, Files.readAllBytes(f.output(second)));
            assertEquals(2, f.logCount());
        }
    }

    @Test void explicitLegacySkipDoesNotAcquireTheNewCrossFileContentRule() throws Exception {
        try (var f = new Fixture()) {
            f.save(f.task("1", "101", "file-a", content.length, "first"), content);
            f.policy(HISTORY_SKIP);
            var legacy = f.legacy("1", "102", "file-b", content.length, "legacy-output");
            assertEquals(0, f.flag(legacy));
            f.save(legacy, content);
            var done = f.fresh(legacy).items().getFirst();
            assertEquals("COMPLETED", done.state());
            assertNull(done.history());
            assertNull(done.plan());
            assertArrayEquals(content, Files.readAllBytes(f.output(legacy)));
            assertEquals(2, f.logCount());
        }
    }

    @Test void otherAccountsSizesAndDifferentVerifiedHashesDoNotMatchTheSkipHistory() throws Exception {
        try (var f = new Fixture()) {
            f.save(f.task("1", "101", "file-a", content.length, "first"), content);
            f.policy(HISTORY_SKIP);
            byte[] changed = content.clone();
            changed[0]++;
            byte[] longer = Arrays.copyOf(content, content.length + 1);
            var otherAccount = f.task("2", "102", "file-a", content.length, "other-account");
            var otherSize = f.task("1", "103", "file-a", longer.length, "other-size");
            var otherHash = f.task("1", "104", "file-b", changed.length, "other-hash");
            for (Task task : List.of(otherAccount, otherSize, otherHash))
                assertEquals("QUEUED", task.items().getFirst().state());
            f.save(otherAccount, content);
            f.save(otherSize, longer);
            f.save(otherHash, changed);
            for (Task task : List.of(otherAccount, otherSize, otherHash)) {
                assertEquals("COMPLETED", f.fresh(task).items().getFirst().state());
                assertNull(f.fresh(task).items().getFirst().history());
                assertTrue(Files.exists(f.output(task)));
            }
            assertEquals(4, f.logCount());
        }
    }

    @Test void aFullVerifiedPartCanResumeAfterReopenUsingItsFrozenSkipPolicyWithoutNativeRereads() throws Exception {
        AppPaths app;
        String taskId;
        try (var f = new Fixture()) {
            app = f.app;
            f.save(f.task("1", "101", "file-a", content.length, "first"), content);
            f.policy(HISTORY_SKIP);
            var second = f.task("1", "102", "file-b", content.length, "resume-output");
            taskId = second.id();
            assertThrows(DownloadFileStore.Stopped.class, () -> f.store.write(second, second.items().getFirst(),
                    (offset, count) -> Arrays.copyOfRange(content, (int) offset, (int) offset + count), () -> {
                        var current = f.fresh(second).items().getFirst();
                        if ("WRITING".equals(current.state()) && current.writtenBytes() == current.file().size())
                            throw new DownloadFileStore.Stopped();
                    }));
            assertEquals(content.length, f.fresh(second).items().getFirst().writtenBytes());
            f.policy(KEEP_DUPLICATES);
        }
        try (var database = new Database(app)) {
            var repository = new DownloadRepository(database);
            var store = new DownloadFileStore(new DownloadPaths(app), repository);
            var task = repository.task("1", taskId);
            assertEquals(HISTORY_SKIP, task.duplicatePolicy());
            assertEquals(1, flag(database, task));
            store.write(task, task.items().getFirst(), (offset, count) -> {
                throw new AssertionError("A verified full part must not be read from Telegram again");
            }, () -> { });
            var restored = repository.task("1", taskId);
            assertEquals("SKIPPED_HISTORY", restored.items().getFirst().state());
            assertEquals(1, repository.view(restored).skipped());
            try (var entries = Files.walk(Path.of(restored.targetRoot()))) {
                assertEquals(0, entries.filter(Files::isRegularFile).count());
            }
        }
    }

    @Test void batchAggregatesRecognizeCrossFileContentSkipEvidenceWithoutACurrentCompletionLog() throws Exception {
        try (var f = new Fixture()) {
            f.policy(HISTORY_SKIP);
            Path output = root.resolve("batch-content");
            var jobs = List.of(
                    new DownloadBatchModels.Job(f.post("1", "1"), List.of(f.file("1", "101", "file-a", content.length))),
                    new DownloadBatchModels.Job(f.post("1", "2"), List.of(f.file("1", "102", "file-b", content.length))));
            var batch = f.repo.createBatch("1", UUID.randomUUID().toString(), "two-content-items",
                    f.snapshot(output, 2, 2, 2L * content.length), output, jobs);
            for (String id : batch.taskIds()) {
                var task = f.repo.task("1", id);
                f.save(task, content);
                f.repo.finishState("1", id, "COMPLETED", null);
            }
            var status = f.repo.batchStatus("1", batch.id());
            assertEquals("COMPLETED", status.state());
            assertEquals(1, status.completed());
            assertEquals(1, status.skipped());
            assertEquals(2, status.completedTasks());
            assertEquals(1, f.logCount());
            var skipped = f.repo.task("1", batch.taskIds().get(1));
            assertEquals("SKIPPED_HISTORY", skipped.items().getFirst().state());
            assertNull(f.repo.view(skipped).items().getFirst().outputPath());
        }
    }

    private static int flag(Database database, Task task) {
        return database.read(connection -> {
            try (var statement = connection.prepareStatement("SELECT skip_content_duplicates FROM download_tasks WHERE task_id=?")) {
                statement.setString(1, task.id());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    return rows.getInt(1);
                }
            }
        });
    }

    private final class Fixture implements AutoCloseable {
        final AppPaths app = new AppPaths(root.resolve("app"));
        final Database db = new Database(app);
        final DownloadRepository repo = new DownloadRepository(db);
        final DownloadFileStore store = new DownloadFileStore(new DownloadPaths(app), repo);

        void policy(String policy) { new DownloadSettings(db).save(new DownloadSettings.Value(policy)); }
        int flag(Task task) { return DownloadDuplicateSettingsTest.flag(db, task); }
        Post post(String account, String message) {
            return new Post(account + ":-1001:" + message, account, "-1001", "channel", message, "title", "body", "0",
                    List.of(new MessageRef("-1001", message, "https://t.me/synthetic/" + message)),
                    List.of(), null, null, List.of(), false, "UNAVAILABLE", List.of());
        }
        FrozenFile file(String account, String message, String uid, int size) {
            return new FrozenFile(new Media(account + ":-1001:" + message + ":0",
                    new MessageRef("-1001", message, "https://t.me/synthetic/" + message), "MAIN", "DOCUMENT", "sample.bin", size, ""),
                    uid, size, "sample.bin");
        }
        Task task(String account, String message, String uid, int size, String output) {
            return repo.create(account, UUID.randomUUID().toString(), UUID.randomUUID().toString(), post(account, "1"),
                    root.resolve(output), List.of(file(account, message, uid, size)));
        }
        Task legacy(String account, String message, String uid, int size, String output) {
            return repo.create(account, UUID.randomUUID().toString(), UUID.randomUUID().toString(), post(account, "1"),
                    root.resolve(output), List.of(file(account, message, uid, size)), HISTORY_SKIP);
        }
        Task fresh(Task task) { return repo.task(task.accountId(), task.id()); }
        Path output(Task task) { return Path.of(repo.view(fresh(task)).items().getFirst().outputPath()); }
        void save(Task task, byte[] bytes) throws IOException {
            store.write(task, task.items().getFirst(),
                    (offset, count) -> Arrays.copyOfRange(bytes, (int) offset, (int) offset + count), () -> { });
        }
        void assertNoOutputFiles(Task task) throws IOException {
            if (!Files.exists(Path.of(task.targetRoot()))) return;
            try (var entries = Files.walk(Path.of(task.targetRoot()))) {
                assertEquals(0, entries.filter(Files::isRegularFile).count());
            }
        }
        DownloadBatchModels.Snapshot snapshot(Path output, int posts, int files, long bytes) {
            return new DownloadBatchModels.Snapshot(UUID.randomUUID().toString(), 1, "channel", List.of(), List.of(),
                    List.of("DOCUMENT"), true, output.toString(), posts, files, bytes);
        }
        int logCount() {
            return db.read(connection -> {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM download_completion_log")) {
                    assertTrue(rows.next());
                    return rows.getInt(1);
                }
            });
        }
        @Override public void close() { db.close(); }
    }
}
