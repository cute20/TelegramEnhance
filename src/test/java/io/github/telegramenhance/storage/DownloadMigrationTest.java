package io.github.telegramenhance.storage;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadMigrationTest {
    @TempDir Path directory;
    private static final List<String> DOWNLOADS = List.of("download_batch_tasks","download_batches","auto_upload_bindings","auto_rule_authorizations","upload_retry_attempts","upload_send_failures","auto_rule_observations","auto_rule_versions","auto_rules","download_output_plans", "download_completion_log", "download_items", "download_history", "download_tasks", "download_target_bindings", "download_file_receipts", "download_cache_owned");
    @Test void v6UpgradeBacksUpCommittedSettingsAndKeepsPriorChannelCheckpoints() throws Exception {
        AppPaths paths = seedV6();
        try (var original = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var sql = original.createStatement()) {
            sql.execute("PRAGMA journal_mode=WAL"); sql.execute("PRAGMA wal_autocheckpoint=0"); sql.execute("INSERT INTO app_settings VALUES('protected-test','opaque-value',1)");
            try (var migrated = new Database(paths)) {
                assertEquals(Database.SCHEMA_VERSION, migrated.diagnostics().get("schemaVersion")); assertEquals("ok", migrated.diagnostics().get("integrity"));
                assertEquals("saved-cursor", scalar(migrated, "SELECT source_cursor FROM channel_import_runs"));
                assertEquals("opaque-value", scalar(migrated, "SELECT setting_value FROM app_settings WHERE setting_key='protected-test'"));
                try (var backup = DriverManager.getConnection("jdbc:sqlite:" + migrated.diagnostics().get("lastMigrationBackup")); var query = backup.createStatement()) {
                    try (var r = query.executeQuery("PRAGMA user_version")) { assertTrue(r.next()); assertEquals(6, r.getInt(1)); }
                    try (var r = query.executeQuery("SELECT source_cursor FROM channel_import_runs")) { assertTrue(r.next()); assertEquals("saved-cursor", r.getString(1)); }
                }
            }
        }
    }
    @Test void aConflictingNewTableRollsBackAndKeepsTheV6Backup() throws Exception {
        AppPaths paths = seedV6();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var s = connection.createStatement()) { s.execute("CREATE TABLE download_tasks(legacy TEXT)"); s.execute("INSERT INTO download_tasks VALUES('keep')"); }
        assertThrows(IllegalStateException.class, () -> new Database(paths));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var s = connection.createStatement(); var r = s.executeQuery("SELECT legacy FROM download_tasks")) { assertTrue(r.next()); assertEquals("keep", r.getString(1)); }
        try (var backups = Files.list(paths.backups())) { assertEquals(1, backups.count()); }
    }
    @Test void aClaimedV7WithoutRequiredDownloadColumnsIsRefusedWithoutMigration() throws Exception {
        AppPaths paths = new AppPaths(directory); try (var ignored = new Database(paths)) { }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var s = connection.createStatement()) { s.execute("ALTER TABLE download_items RENAME COLUMN owner_token TO missing_owner"); }
        assertThrows(IllegalStateException.class, () -> new Database(paths)); try (var backups = Files.list(paths.backups())) { assertEquals(0, backups.count()); }
    }
    private AppPaths seedV6() throws Exception {
        AppPaths paths = new AppPaths(directory); try (var ignored = new Database(paths)) { }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var s = connection.createStatement()) {
            DownloadRuleMigrationFixture.drop(s);
            for (String uploadTable : List.of("upload_events","upload_claims","upload_items","upload_groups","upload_tasks")) s.execute("DROP TABLE " + uploadTable);
            for (String table : DOWNLOADS) s.execute("DROP TABLE " + table);
            s.execute("INSERT INTO channel_import_runs(account_id,channel_id,run_id,revision,source_cursor,pending_json,pending_offset,pending_exhausted,pending_has_more,status,processed,created,updated,incomplete,exhausted,started_at,updated_at) VALUES('1','-1001','run',0,'saved-cursor','[]',0,0,1,'READY',0,0,0,0,0,1,1)"); s.execute("PRAGMA user_version=6");
        }
        return paths;
    }
    private static String scalar(Database database, String sql) { return database.read(c -> { try (var q = c.createStatement(); var r = q.executeQuery(sql)) { assertTrue(r.next()); return r.getString(1); } }); }
}
