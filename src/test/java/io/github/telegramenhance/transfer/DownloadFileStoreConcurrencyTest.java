package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.DownloadModels.Task;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadFileStoreConcurrencyTest {
    @TempDir Path directory;
    @Test void concurrentMediaMayCreateTheSameLegacyNestedDirectoryWithoutTreatingTheWinnerAsFailure() throws Exception {
        var paths = new AppPaths(directory.resolve("data"));
        try (var db = new Database(paths)) {
            var repository = new DownloadRepository(db); var store = new DownloadFileStore(new DownloadPaths(paths), repository);
            var original = repository.create("1", UUID.randomUUID().toString(), "directory", DownloadPersistenceTest.post("title"), directory.resolve("output"),
                    List.of(DownloadPersistenceTest.file(37, "same.bin")));
            var task = new Task(original.id(), original.accountId(), original.operationId(), original.fingerprint(), original.post(), original.targetRoot(),
                    Path.of("nested", "same", "post").toString(), original.state(), false, null, original.createdAt(), original.updatedAt(), original.items(), original.duplicatePolicy());
            var workers = Executors.newFixedThreadPool(8); var ready = new CountDownLatch(8); var start = new CountDownLatch(1);
            try {
                var results = new ArrayList<Future<Path>>();
                for (int i = 0; i < 8; i++) results.add(workers.submit(() -> { ready.countDown(); start.await(); return store.directory(task); }));
                assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
                var expected = directory.resolve("output/nested/same/post").toAbsolutePath();
                for (var result : results) assertEquals(expected, result.get(5, TimeUnit.SECONDS));
            } finally { start.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
        }
    }
}
