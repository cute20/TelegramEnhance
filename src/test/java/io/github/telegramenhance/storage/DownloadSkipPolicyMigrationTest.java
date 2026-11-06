package io.github.telegramenhance.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.config.AppPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadSkipPolicyMigrationTest {
    @TempDir Path directory;

    @Test void v16WalBackupAndAdditiveMigrationPreserveOldTaskPoliciesAndDefaultTheirContentSkipFlagToZero() throws Exception {
        var paths = seed("upgrade", true);
        try (var original = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var statement = original.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL"); statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("INSERT INTO app_settings VALUES('latest-wal','preserved-after-last-checkpoint',456)");
            assertTrue(Files.size(Path.of(paths.database() + "-wal")) > 0);
            var before = rows(original);
            try (var migrated = new Database(paths)) {
                assertEquals(Database.SCHEMA_VERSION, migrated.diagnostics().get("schemaVersion"));
                assertEquals("ok", migrated.diagnostics().get("integrity"));
                assertEquals(before, migrated.read(DownloadSkipPolicyMigrationTest::rows));
                assertEquals("2", value(migrated, "SELECT COUNT(*) FROM download_tasks WHERE skip_content_duplicates=0"));
                assertEquals("HISTORY_SKIP_V1,KEEP_DUPLICATES_V2", value(migrated,
                        "SELECT group_concat(duplicate_policy,',') FROM (SELECT duplicate_policy FROM download_tasks ORDER BY rowid)"));
                assertEquals("0", value(migrated, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
                for (String invalid : List.of("2", "-1", "NULL")) {
                    assertThrows(IllegalStateException.class, () -> migrated.write(connection -> {
                        try (var update = connection.createStatement()) { update.executeUpdate("UPDATE download_tasks SET skip_content_duplicates=" + invalid); }
                        return null;
                    }));
                    assertEquals("2", value(migrated, "SELECT COUNT(*) FROM download_tasks WHERE skip_content_duplicates=0"));
                }
                try (var backup = DriverManager.getConnection("jdbc:sqlite:" + migrated.diagnostics().get("lastMigrationBackup"))) {
                    assertEquals("16", scalar(backup, "PRAGMA user_version"));
                    assertEquals("ok", scalar(backup, "PRAGMA quick_check"));
                    assertEquals(before, rows(backup));
                    assertEquals("0", scalar(backup, "SELECT COUNT(*) FROM pragma_table_info('download_tasks') WHERE name='skip_content_duplicates'"));
                }
            }
        }
        try (var reopened = new Database(paths)) {
            assertEquals("", reopened.diagnostics().get("lastMigrationBackup"));
            assertEquals("2", value(reopened, "SELECT COUNT(*) FROM download_tasks WHERE skip_content_duplicates=0"));
        }
        try (var backups = Files.list(paths.backups())) { assertEquals(1, backups.count()); }
    }

    @Test void aCorrectAlreadyPresentFutureColumnIsPreservedInsteadOfBeingAddedOrReset() throws Exception {
        var paths = seed("future-column", false);
        try (var original = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var statement = original.createStatement()) {
            statement.executeUpdate("UPDATE download_tasks SET skip_content_duplicates=1 WHERE task_id='legacy-0'");
            var before = rows(original);
            String definition = scalar(original, "SELECT sql FROM sqlite_master WHERE name='download_tasks'");
            try (var migrated = new Database(paths)) {
                assertEquals(Database.SCHEMA_VERSION, migrated.diagnostics().get("schemaVersion"));
                assertEquals(before, migrated.read(DownloadSkipPolicyMigrationTest::rows));
                assertEquals(definition, value(migrated, "SELECT sql FROM sqlite_master WHERE name='download_tasks'"));
                assertEquals("1", value(migrated, "SELECT skip_content_duplicates FROM download_tasks WHERE task_id='legacy-0'"));
                assertEquals("0", value(migrated, "SELECT skip_content_duplicates FROM download_tasks WHERE task_id='legacy-1'"));
            }
        }
    }

    @Test void aMalformedFutureColumnFailsWithoutAdvancingTheVersionOrRewritingOriginalDataAndBackup() throws Exception {
        int index = 0;
        for (String definition : List.of(
                "TEXT NOT NULL DEFAULT 0 CHECK(skip_content_duplicates IN(0,1))",
                "INTEGER DEFAULT 0 CHECK(skip_content_duplicates IN(0,1))",
                "INTEGER NOT NULL DEFAULT 1 CHECK(skip_content_duplicates IN(0,1))",
                "INTEGER NOT NULL DEFAULT 0",
                "INTEGER NOT NULL DEFAULT 0 CHECK(skip_content_duplicates IN(0,2))")) {
            var paths = seed("malformed-" + index++, true);
            Map<String, List<List<String>>> before; String schema;
            try (var original = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var statement = original.createStatement()) {
                statement.execute("ALTER TABLE download_tasks ADD COLUMN skip_content_duplicates " + definition);
                before = rows(original); schema = scalar(original, "SELECT sql FROM sqlite_master WHERE name='download_tasks'");
            }
            assertThrows(IllegalStateException.class, () -> new Database(paths), definition);
            try (var original = DriverManager.getConnection("jdbc:sqlite:" + paths.database())) {
                assertEquals("16", scalar(original, "PRAGMA user_version"));
                assertEquals(before, rows(original));
                assertEquals(schema, scalar(original, "SELECT sql FROM sqlite_master WHERE name='download_tasks'"));
            }
            try (var backups = Files.list(paths.backups())) {
                var files = backups.toList(); assertEquals(1, files.size());
                try (var backup = DriverManager.getConnection("jdbc:sqlite:" + files.getFirst())) {
                    assertEquals("16", scalar(backup, "PRAGMA user_version")); assertEquals("ok", scalar(backup, "PRAGMA quick_check"));
                    assertEquals(before, rows(backup)); assertEquals(schema, scalar(backup, "SELECT sql FROM sqlite_master WHERE name='download_tasks'"));
                }
            }
        }
    }

    @Test void aClaimedCurrentSchemaWithAMissingOrUnconstrainedFlagIsRejectedWithoutRepairOrBackup() throws Exception {
        for (boolean unconstrained : List.of(false, true)) {
            var paths = seed("claimed-current-" + unconstrained, true);
            try (var original = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var statement = original.createStatement()) {
                if (unconstrained) statement.execute("ALTER TABLE download_tasks ADD COLUMN skip_content_duplicates INTEGER NOT NULL DEFAULT 0");
                statement.execute("PRAGMA user_version=" + Database.SCHEMA_VERSION);
            }
            assertThrows(IllegalStateException.class, () -> new Database(paths));
            try (var original = DriverManager.getConnection("jdbc:sqlite:" + paths.database())) {
                assertEquals(Integer.toString(Database.SCHEMA_VERSION), scalar(original, "PRAGMA user_version"));
                assertEquals("2", scalar(original, "SELECT COUNT(*) FROM download_tasks"));
                assertEquals(unconstrained ? "1" : "0", scalar(original,
                        "SELECT COUNT(*) FROM pragma_table_info('download_tasks') WHERE name='skip_content_duplicates'"));
            }
            try (var backups = Files.list(paths.backups())) { assertEquals(0, backups.count()); }
        }
    }

    private AppPaths seed(String name, boolean removeFutureColumn) throws Exception {
        var paths = new AppPaths(directory.resolve(name));
        try (var database = new Database(paths)) {
            database.write(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("INSERT INTO app_settings VALUES('telegram.api_hash.protected','synthetic-opaque-protected-value',123)");
                }
                try (var statement = connection.prepareStatement("""
                        INSERT INTO download_tasks(rowid,task_id,account_id,operation_id,fingerprint,post_key,post_json,target_root,
                            relative_directory,state,pause_requested,error_message,created_at,updated_at,duplicate_policy)
                        VALUES(?,?,?,?,?,?,?,?,?,'PAUSED',1,?,?,?,?)
                        """)) {
                    for (int index = 0; index < 2; index++) {
                        statement.setInt(1, 11 + index * 19); statement.setString(2, "legacy-" + index); statement.setString(3, "1");
                        statement.setString(4, "operation-" + index); statement.setString(5, "frozen-" + index); statement.setString(6, "source-" + index);
                        statement.setString(7, "{\"preserved\":\"task-" + index + "\"}"); statement.setString(8, "D:/synthetic-output-" + index);
                        statement.setString(9, "legacy-bound-directory"); statement.setString(10, "original-pause-reason");
                        statement.setLong(11, 100 + index); statement.setLong(12, 200 + index);
                        statement.setString(13, index == 0 ? "HISTORY_SKIP_V1" : "KEEP_DUPLICATES_V2"); statement.executeUpdate();
                    }
                }
                try (var statement = connection.createStatement()) {
                    statement.execute("INSERT INTO download_items(item_id,task_id,ordinal,media_key,spec_json,unique_id,size,relative_file,owner_token,state) VALUES('legacy-item','legacy-0',0,'media-key','{\"frozen\":true}','synthetic-uid',17,'sample.bin','synthetic-owner','QUEUED')");
                }
                return null;
            });
        }
        try (var original = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var statement = original.createStatement()) {
            if (removeFutureColumn) statement.execute("ALTER TABLE download_tasks DROP COLUMN skip_content_duplicates");
            statement.execute("PRAGMA user_version=16");
        }
        return paths;
    }

    private static Map<String, List<List<String>>> rows(Connection connection) throws SQLException {
        var result = new LinkedHashMap<String, List<List<String>>>();
        for (var entry : Map.of("settings", "SELECT rowid,* FROM app_settings ORDER BY rowid", "items", "SELECT rowid,* FROM download_items ORDER BY rowid",
                "tasks", "SELECT rowid,task_id,account_id,operation_id,fingerprint,post_key,post_json,target_root,relative_directory,state,pause_requested,error_message,created_at,updated_at,duplicate_policy FROM download_tasks ORDER BY rowid").entrySet()) {
            var records = new ArrayList<List<String>>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(entry.getValue())) {
                int columns = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    var values = new ArrayList<String>();
                    for (int index = 1; index <= columns; index++) values.add(rows.getString(index));
                    records.add(values);
                }
            }
            result.put(entry.getKey(), records);
        }
        return result;
    }
    private static String value(Database database, String query) { return database.read(connection -> scalar(connection, query)); }
    private static String scalar(Connection connection, String query) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            assertTrue(rows.next()); return rows.getString(1);
        }
    }
}
