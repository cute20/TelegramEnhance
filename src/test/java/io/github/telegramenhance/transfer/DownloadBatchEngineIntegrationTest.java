package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.DownloadFileReader;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadBatchModels.Job;
import io.github.telegramenhance.transfer.DownloadBatchModels.Receipt;
import io.github.telegramenhance.transfer.DownloadBatchModels.Snapshot;
import io.github.telegramenhance.transfer.DownloadModels.FrozenFile;
import io.github.telegramenhance.transfer.DownloadModels.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real local publication and worker control; all Telegram operations are isolated mocks. */
class DownloadBatchEngineIntegrationTest {
    @TempDir Path directory;

    @Test void wholeBatchPauseAndRestartResumeKeepCompletedMembersAndTheirPublishedEvidence() throws Exception {
        var paths = new AppPaths(directory.resolve("data"));
        Path root = directory.resolve("output").toAbsolutePath();
        var reader = mock(DownloadFileReader.class);
        var telegram = mock(TelegramService.class);
        var allowSecondToComplete = new AtomicBoolean();
        var blockedUniqueId = new AtomicReference<String>();
        Map<String, byte[]> content = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> resolutions = new ConcurrentHashMap<>();

        when(telegram.accountId()).thenReturn("1");
        when(telegram.sessionGeneration()).thenReturn(1L);
        when(reader.resolve(any(), any(), anyLong())).thenAnswer(call -> {
            FrozenFile file = call.getArgument(1);
            resolutions.computeIfAbsent(file.uniqueId(), ignored -> new AtomicInteger()).incrementAndGet();
            return new DownloadFileReader.Resolved(file, 7);
        });
        when(reader.status(anyString(), anyLong(), anyInt(), anyString(), anyLong())).thenAnswer(call -> {
            boolean blocked = call.getArgument(3).equals(blockedUniqueId.get()) && !allowSecondToComplete.get();
            return new DownloadFileReader.Status(blocked ? 1 : 37, !blocked, blocked, true);
        });
        when(reader.chunk(anyString(), anyLong(), anyInt(), anyString(), anyLong(), anyLong(), anyInt())).thenAnswer(call -> {
            String unique = call.getArgument(3);
            long offset = call.getArgument(5);
            int count = call.getArgument(6);
            return Arrays.copyOfRange(content.get(unique), Math.toIntExact(offset), Math.toIntExact(offset) + count);
        });
        when(reader.release(anyString(), anyLong(), anyInt(), anyString(), anyBoolean())).thenReturn(true);

        String operation = UUID.randomUUID().toString();
        Receipt receipt;
        Task firstSaved;
        String firstId, secondId, thirdId, firstUnique, thirdUnique;
        try (var database = new Database(paths)) {
            var repository = new DownloadRepository(database);
            var jobs = List.of(job(100, content), job(90, content), job(80, content));
            var snapshot = new Snapshot(UUID.randomUUID().toString(), 3, "channel", List.of(), List.of(),
                    List.of("DOCUMENT"), true, root.toString(), 3, 3, 111);
            receipt = repository.createBatch("1", operation, "isolated-batch", snapshot, root, jobs);
            // The worker orders by these persisted fields, independently of membership ordinal.
            List<Task> order = receipt.taskIds().stream().map(id -> repository.task("1", id))
                    .sorted(Comparator.comparingLong(Task::createdAt).thenComparing(Task::id)).toList();
            firstId = order.get(0).id(); secondId = order.get(1).id(); thirdId = order.get(2).id();
            firstUnique = order.get(0).items().getFirst().file().uniqueId();
            thirdUnique = order.get(2).items().getFirst().file().uniqueId();
            blockedUniqueId.set(order.get(1).items().getFirst().file().uniqueId());
            var store = new DownloadFileStore(new DownloadPaths(paths), repository);

            try (var engine = new DownloadEngine(repository, reader, store, telegram, paths)) {
                firstSaved = awaitTask(repository, firstId, "COMPLETED");
                awaitDownloading(repository, secondId);
                assertEquals("QUEUED", repository.task("1", thirdId).state());
                assertFalse(resolutions.containsKey(thirdUnique));

                var pausing = repository.pauseBatch("1", receipt.id());
                assertEquals("PAUSING", pausing.state());
                assertEquals(1, pausing.completedTasks());
                assertEquals(1, pausing.completed());
                var secondAtPause = pausing.tasks().stream().filter(task -> task.id().equals(secondId)).findFirst().orElseThrow();
                assertEquals("RUNNING", secondAtPause.state());
                assertTrue(secondAtPause.pauseRequested());
                assertEquals("PAUSED", pausing.tasks().stream().filter(task -> task.id().equals(thirdId)).findFirst().orElseThrow().state());

                var secondPaused = awaitTask(repository, secondId, "PAUSED");
                assertEquals(1, secondPaused.items().getFirst().downloadedBytes());
                assertEquals(0, secondPaused.items().getFirst().writtenBytes());
                assertEquals(firstSaved, repository.task("1", firstId));
                assertEquals("PAUSED", repository.task("1", thirdId).state());
                assertEquals("PAUSED", repository.batchStatus("1", receipt.id()).state());
            }
        }

        try (var database = new Database(paths)) {
            var repository = new DownloadRepository(database);
            var restored = repository.batchStatus("1", receipt.id());
            assertEquals("PAUSED", restored.state());
            assertEquals(1, restored.completedTasks());
            assertEquals(2, restored.pausedTasks());
            assertEquals(firstSaved, repository.task("1", firstId));
            assertEquals(1, repository.task("1", secondId).items().getFirst().downloadedBytes());
            assertEquals(receipt, repository.batchByOperation("1", operation, "isolated-batch"));

            var resumed = repository.resumeBatch("1", receipt.id());
            assertEquals("QUEUED", resumed.state());
            assertEquals(1, resumed.completedTasks());
            assertEquals(2, resumed.queuedTasks());
            allowSecondToComplete.set(true);
            var store = new DownloadFileStore(new DownloadPaths(paths), repository);
            try (var engine = new DownloadEngine(repository, reader, store, telegram, paths)) {
                awaitTask(repository, secondId, "COMPLETED");
                awaitTask(repository, thirdId, "COMPLETED");
                var completed = repository.batchStatus("1", receipt.id());
                assertEquals("COMPLETED", completed.state());
                assertEquals(3, completed.completedTasks());
                assertEquals(3, completed.completed());
                assertEquals(0, completed.skipped());
                assertEquals(111, completed.downloadedBytes());
                assertEquals(111, completed.writtenBytes());
                assertFalse(completed.canPause()); assertFalse(completed.canResume());
                assertEquals(firstSaved, repository.task("1", firstId));
                assertEquals(1, resolutions.get(firstUnique).get(), "A finished child must not resolve its source again");
                assertEquals(1, resolutions.get(thirdUnique).get());

                for (String id : receipt.taskIds()) {
                    Task task = repository.task("1", id);
                    var item = task.items().getFirst();
                    Path output = Path.of(repository.view(task).items().getFirst().outputPath());
                    byte[] expected = content.get(item.file().uniqueId());
                    assertArrayEquals(expected, Files.readAllBytes(output));
                    assertEquals(37, Files.size(output));
                    assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)), item.sha256());
                }
                int completionLogs = database.read(connection -> {
                    try (var query = connection.prepareStatement("SELECT COUNT(*) FROM download_completion_log WHERE account_id=?")) {
                        query.setString(1, "1");
                        try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
                    }
                });
                assertEquals(3, completionLogs);
            }
        }
    }

    private static Job job(int id, Map<String, byte[]> content) {
        var post = DownloadBatchServiceTest.post(id);
        var media = DownloadBatchServiceTest.media(id, true);
        String unique = "isolated-uid-" + id;
        byte[] bytes = new byte[37]; Arrays.fill(bytes, (byte) id); content.put(unique, bytes);
        return new Job(post, List.of(new FrozenFile(media, unique, 37, media.fileName())));
    }

    private static Task awaitTask(DownloadRepository repository, String id, String state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        Task task;
        do {
            task = repository.task("1", id);
            if (state.equals(task.state())) return task;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("Expected isolated child state " + state + " but was " + task.state());
        return task;
    }

    private static void awaitDownloading(DownloadRepository repository, String id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        do {
            Task task = repository.task("1", id);
            if ("RUNNING".equals(task.state()) && "DOWNLOADING".equals(task.items().getFirst().state())
                    && task.items().getFirst().downloadedBytes() == 1) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("The isolated second child never reached its one-byte native checkpoint");
    }

}
