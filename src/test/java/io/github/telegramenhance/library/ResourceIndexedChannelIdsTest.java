package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceIndexedChannelIdsTest {
    @TempDir Path directory;

    @Test void distinctOrderedSeedsAreLimitedToTheRequestedAccount() {
        try (Database database = new Database(new AppPaths(directory))) {
            insert(database, "1", "-1003", "100"); insert(database, "1", "-1001", "100");
            insert(database, "1", "-1001", "101"); insert(database, "1", "-1002", "100");
            insert(database, "2", "-1000", "100"); insert(database, "2", "-1001", "100");
            var repository = new ResourceRepository(database);
            assertEquals(List.of("-1001", "-1002"), repository.indexedChannelIds("1", 2));
            assertEquals(List.of("-1001", "-1002", "-1003"), repository.indexedChannelIds("1", 101));
            assertEquals(List.of("-1000", "-1001"), repository.indexedChannelIds("2", 101));
            assertTrue(repository.indexedChannelIds("missing", 101).isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> repository.indexedChannelIds("1", 1).clear());
        }
    }

    @Test void seedBoundsAllowThe101stSentinelButNeverReturnMoreThanTheSqlLimit() {
        try (Database database = new Database(new AppPaths(directory))) {
            for (int index = 0; index < 105; index++) insert(database, "1", "-" + (1000 + index), "100");
            var repository = new ResourceRepository(database);
            assertEquals(1, repository.indexedChannelIds("1", 1).size());
            assertEquals(100, repository.indexedChannelIds("1", 100).size());
            assertEquals(101, repository.indexedChannelIds("1", 101).size());
            for (int limit : new int[]{-1, 0, 102, Integer.MAX_VALUE}) {
                assertThrows(IllegalArgumentException.class, () -> repository.indexedChannelIds("1", limit));
            }
            for (String account : new String[]{null, "", " "}) {
                assertThrows(IllegalArgumentException.class, () -> repository.indexedChannelIds(account, 1));
            }
        }
    }

    @Test void invalidPostJsonDoesNotGetDecodedAndMissingSearchProjectionsRemainUntouched() {
        try (Database database = new Database(new AppPaths(directory))) {
            insert(database, "1", "-1001", "100"); insert(database, "1", "-1002", "100"); insert(database, "2", "-1003", "100");
            database.write(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO resource_post_search(post_key,normalized_text) VALUES ('1:-1001:100','sentinel text')");
                    statement.executeUpdate("INSERT INTO resource_post_tags(post_key,normalized_tag) VALUES ('1:-1001:100','sentinel-tag')");
                    statement.execute("CREATE TRIGGER forbid_seed_backfill BEFORE INSERT ON resource_post_search BEGIN SELECT RAISE(ABORT,'unexpected projection write'); END");
                }
                return null;
            });
            var repository = new ResourceRepository(database);
            assertEquals(List.of("-1001", "-1002"), repository.indexedChannelIds("1", 101));
            assertEquals(List.of("-1003"), repository.indexedChannelIds("2", 101));
            database.read(connection -> {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                        SELECT (SELECT COUNT(*) FROM resource_post_search),
                               (SELECT normalized_text FROM resource_post_search WHERE post_key='1:-1001:100'),
                               (SELECT COUNT(*) FROM resource_post_tags),
                               (SELECT normalized_tag FROM resource_post_tags WHERE post_key='1:-1001:100'),
                               (SELECT COUNT(*) FROM resource_posts WHERE post_json='malformed-private-json{' AND updated_at=123)
                        """)) {
                    assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertEquals("sentinel text", rows.getString(2));
                    assertEquals(1, rows.getInt(3)); assertEquals("sentinel-tag", rows.getString(4)); assertEquals(3, rows.getInt(5));
                }
                return null;
            });
        }
    }

    private static void insert(Database database, String account, String channel, String message) {
        database.write(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO resource_posts(post_key,account_id,channel_id,main_message_id,post_json,updated_at)
                    VALUES (?,?,?,?,'malformed-private-json{',123)
                    """)) {
                statement.setString(1, account + ":" + channel + ":" + message); statement.setString(2, account);
                statement.setString(3, channel); statement.setString(4, message); statement.executeUpdate();
            }
            return null;
        });
    }
}
