package io.github.telegramenhance.storage;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.library.ResourceRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseRunMigrationTest {
    @TempDir Path directory;

    @Test void v5ToV6BacksUpCommittedWalAndPreservesAllPriorMetadataAndCredentials() throws Exception {
        AppPaths paths = seed();
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var statement = original.createStatement()) {
            downgrade(statement); statement.execute("PRAGMA journal_mode=WAL"); statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.executeUpdate("INSERT INTO app_settings VALUES ('protected-key','opaque-protected-value',999)");
            String post = scalar(original, "SELECT post_json FROM resource_posts"), media = scalar(original, "SELECT media_json FROM resource_comment_media");
            String observation = scalar(original, "SELECT observation_json FROM resource_comment_messages");
            assertTrue(Files.size(Path.of(paths.database() + "-wal")) > 0);
            try (Database upgraded = new Database(paths)) {
                assertEquals(Database.SCHEMA_VERSION, upgraded.diagnostics().get("schemaVersion"));
                assertEquals("ok", upgraded.diagnostics().get("integrity"));
                assertMetadata(upgraded, post, media, observation);
                assertEquals("0", value(upgraded, "SELECT COUNT(*) FROM channel_import_runs"));
                assertEquals("0", value(upgraded, "SELECT COUNT(*) FROM channel_import_run_entries"));
                Path backupPath = Path.of((String) upgraded.diagnostics().get("lastMigrationBackup"));
                assertTrue(backupPath.getFileName().toString().startsWith("metadata-v5-"));
                try (Connection backup = DriverManager.getConnection("jdbc:sqlite:" + backupPath)) {
                    assertEquals("5", scalar(backup, "PRAGMA user_version")); assertEquals("ok", scalar(backup, "PRAGMA quick_check"));
                    assertEquals(post, scalar(backup, "SELECT post_json FROM resource_posts"));
                    assertEquals(media, scalar(backup, "SELECT media_json FROM resource_comment_media"));
                    assertEquals(observation, scalar(backup, "SELECT observation_json FROM resource_comment_messages"));
                    assertEquals("601", scalar(backup, "SELECT next_cursor FROM resource_scan_state"));
                    assertEquals("opaque-protected-value", scalar(backup, "SELECT setting_value FROM app_settings WHERE setting_key='protected-key'"));
                    assertEquals("0", scalar(backup, "SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'channel_import_%'"));
                    assertEquals("#keep body", scalar(backup, "SELECT normalized_text FROM resource_post_search"));
                    assertEquals("keep", scalar(backup, "SELECT normalized_tag FROM resource_post_tags"));
                }
            }
        }
        try (Database reopened = new Database(paths)) {
            assertEquals("", reopened.diagnostics().get("lastMigrationBackup"));
            assertEquals("601", value(reopened, "SELECT next_cursor FROM resource_scan_state"));
            assertEquals("1", value(reopened, "SELECT COUNT(*) FROM resource_comment_media"));
        }
    }

    @Test void aConflictingV6TableRollsBackTheWholeMigrationAndKeepsItsV5Backup() throws Exception {
        AppPaths paths = seed();
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var statement = original.createStatement()) {
            downgrade(statement); statement.execute("CREATE TABLE channel_import_run_entries (legacy TEXT)");
            statement.executeUpdate("INSERT INTO channel_import_run_entries VALUES ('keep-conflicting-row')");
        }
        assertThrows(IllegalStateException.class, () -> new Database(paths));
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database())) {
            assertEquals("5", scalar(original, "PRAGMA user_version"));
            assertEquals("keep-conflicting-row", scalar(original, "SELECT legacy FROM channel_import_run_entries"));
            assertEquals("0", scalar(original, "SELECT COUNT(*) FROM sqlite_master WHERE name='channel_import_runs'"));
            assertEquals("1", scalar(original, "SELECT COUNT(*) FROM resource_comment_media"));
        }
        try (var backups = Files.list(paths.backups())) {
            var files = backups.toList(); assertEquals(1, files.size());
            try (Connection backup = DriverManager.getConnection("jdbc:sqlite:" + files.getFirst())) {
                assertEquals("5", scalar(backup, "PRAGMA user_version")); assertEquals("ok", scalar(backup, "PRAGMA quick_check"));
                assertEquals("keep-conflicting-row", scalar(backup, "SELECT legacy FROM channel_import_run_entries"));
            }
        }
    }

    @Test void aFileClaimingV6WithoutRequiredRunColumnsIsRefusedWithoutRewritingMetadata() throws Exception {
        AppPaths paths = seed();
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var statement = original.createStatement()) {
            statement.execute("ALTER TABLE channel_import_runs RENAME COLUMN pending_json TO obsolete_pending");
        }
        assertThrows(IllegalStateException.class, () -> new Database(paths));
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database())) {
            assertEquals(Integer.toString(Database.SCHEMA_VERSION), scalar(original, "PRAGMA user_version"));
            assertEquals("1", scalar(original, "SELECT COUNT(*) FROM resource_comment_media"));
        }
        try (var backups = Files.list(paths.backups())) { assertEquals(0, backups.count()); }
    }

    private AppPaths seed() {
        AppPaths paths = new AppPaths(directory);
        try (Database database = new Database(paths)) {
            var repository = new ResourceRepository(database);
            var post = new Post("1:-1001:100", "1", "-1001", "Synthetic", "100", "title", "#keep body", "0",
                List.of(new MessageRef("-1001", "100", "")), List.of(), "-2001", "500", List.of(new MessageRef("-2001", "500", "")),
                true, "PARTIAL", List.of(), new AlbumEvidence(true, "SINGLE_MESSAGE", null, null), "COMPLETE");
            repository.savePost(post);
            var message = new MessageRef("-2001", "601", "");
            var media = new Media("1:-2001:601:0", message, "COMMENT", "VIDEO", "synthetic.mp4", 100, "preserved caption");
            repository.savePage(new Page(post.key(), List.of(media), List.of(message), 1, "601", true, "COMPLETE", List.of(),
                "COMPLETE", List.of(), List.of(new CommentObservation(message, true, List.of())), List.of()));
        }
        return paths;
    }
    private static void downgrade(java.sql.Statement statement) throws Exception {
        DownloadRuleMigrationFixture.drop(statement);
        for(var table:List.of("download_batch_tasks","download_batches","auto_upload_bindings","auto_rule_authorizations","upload_retry_attempts","upload_send_failures","auto_rule_observations","auto_rule_versions","auto_rules"))statement.execute("DROP TABLE "+table);
        for (var uploadTable : List.of("upload_events","upload_claims","upload_items","upload_groups","upload_tasks")) statement.execute("DROP TABLE " + uploadTable);
        for (var table : List.of("download_output_plans", "download_completion_log", "download_items", "download_history", "download_tasks", "download_target_bindings", "download_file_receipts", "download_cache_owned")) statement.execute("DROP TABLE " + table);
        statement.execute("DROP TABLE channel_import_run_entries"); statement.execute("DROP TABLE channel_import_runs"); statement.execute("PRAGMA user_version=5");
    }
    private static void assertMetadata(Database database, String post, String media, String observation) {
        assertEquals(post, value(database, "SELECT post_json FROM resource_posts")); assertEquals(media, value(database, "SELECT media_json FROM resource_comment_media"));
        assertEquals(observation, value(database, "SELECT observation_json FROM resource_comment_messages"));
        assertEquals("601", value(database, "SELECT next_cursor FROM resource_scan_state"));
        assertEquals("opaque-protected-value", value(database, "SELECT setting_value FROM app_settings WHERE setting_key='protected-key'"));
        assertEquals("#keep body", value(database, "SELECT normalized_text FROM resource_post_search")); assertEquals("keep", value(database, "SELECT normalized_tag FROM resource_post_tags"));
    }
    private static String value(Database database, String sql) {
        return database.read(connection -> { try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getString(1); } });
    }
    private static String scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getString(1); }
    }
}
