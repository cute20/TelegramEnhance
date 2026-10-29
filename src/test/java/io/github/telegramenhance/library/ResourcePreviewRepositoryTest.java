package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.storage.Database;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ResourcePreviewRepositoryTest {
    @TempDir Path directory;

    @Test
    void snapshotIsAccountScopedAndPreservesTheStoredPostAndDatabaseRevision() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post first = post("1", "1:-1001:100");
            Post second = post("2", "2:-1001:100");
            repository.savePost(first); repository.savePost(second);
            database.write(connection -> {
                try (var statement = connection.prepareStatement("UPDATE resource_posts SET updated_at=? WHERE post_key=?")) {
                    statement.setLong(1, 9_007_199_254_740_993L); statement.setString(2, first.key()); statement.executeUpdate();
                }
                return null;
            });
            var source = repository.previewSource("1", first.key());
            assertEquals(first, source.post());
            assertEquals(9_007_199_254_740_993L, source.updatedAt());
            assertEquals(second, repository.previewSource("2", second.key()).post());
            assertThrows(IllegalArgumentException.class, () -> repository.previewSource("1", second.key()));
            assertThrows(IllegalArgumentException.class, () -> repository.previewSource("2", first.key()));
            assertThrows(IllegalArgumentException.class, () -> repository.previewSource("1", "missing"));
        }
    }

    @Test
    void previewSourceDoesNotNeedOrChangeCommentCheckpointsOrCoverage() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1", "1:-1001:100"); repository.savePost(post);
            database.write(connection -> {
                try (Statement statement = connection.createStatement()) { statement.executeUpdate("DELETE FROM resource_scan_state"); }
                return null;
            });
            assertEquals(post, repository.previewSource("1", post.key()).post());
            int checkpoints = database.read(connection -> {
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM resource_scan_state")) {
                    assertTrue(rows.next()); return rows.getInt(1);
                }
            });
            assertEquals(0, checkpoints);
        }
    }

    @Test
    void mismatchedJsonIdentityCannotEscapeTheSqlAccountFilter() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post original = post("1", "1:-1001:100"); repository.savePost(original);
            var mapper = JsonMapper.builder().build();
            for (Post corrupt : List.of(post("2", original.key()), post("1", "wrong-key"))) {
                database.write(connection -> {
                    try (var statement = connection.prepareStatement("UPDATE resource_posts SET post_json=? WHERE post_key=?")) {
                        statement.setString(1, mapper.writeValueAsString(corrupt)); statement.setString(2, original.key()); statement.executeUpdate();
                    }
                    return null;
                });
                var failure = assertThrows(IllegalStateException.class, () -> repository.previewSource("1", original.key()));
                assertFalse(failure.getMessage().contains("private synthetic body"));
                assertNull(failure.getCause());
            }
        }
    }

    private Post post(String account, String key) {
        return new Post(key, account, "-1001", "Synthetic channel", "100", "Synthetic title", "private synthetic body", "0",
            List.of(), List.of(), null, null, List.of(), false, "UNAVAILABLE", List.of());
    }
}
