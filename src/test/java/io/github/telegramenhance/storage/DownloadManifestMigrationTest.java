package io.github.telegramenhance.storage;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadManifestMigrationTest {
    @TempDir Path directory;
    private static final String HASH = "a".repeat(64);

    @Test void schema15WalBackupAndMigrationPreserveAllRowsFrozenPlansIndexesAndTriggers() throws Exception {
        var paths = v15();
        try (var original = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var statement = original.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL"); statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("INSERT INTO app_settings VALUES('latest-wal','keep-committed-wal',9)");
            assertTrue(Files.size(Path.of(paths.database() + "-wal")) > 0);
            var before = rows(original); var objects = planObjects(original);
            try (var migrated = new Database(paths)) {
                assertEquals(16, migrated.diagnostics().get("schemaVersion"));
                assertEquals("ok", migrated.diagnostics().get("integrity"));
                assertEquals(before, migrated.read(DownloadManifestMigrationTest::rows));
                assertEquals(objects, migrated.read(DownloadManifestMigrationTest::planObjects));
                assertEquals("2", value(migrated, "SELECT COUNT(*) FROM download_output_plans WHERE manifest_relative IS NOT NULL"));
                assertEquals("1", value(migrated, "SELECT COUNT(*) FROM download_completion_log WHERE manifest_path IS NOT NULL"));
                assertEquals("11,37,83", value(migrated, "SELECT group_concat(rowid,',') FROM (SELECT rowid FROM download_output_plans ORDER BY rowid)"));
                assertEquals("0", value(migrated, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
                try (var backup = DriverManager.getConnection("jdbc:sqlite:" + migrated.diagnostics().get("lastMigrationBackup"))) {
                    assertEquals("15", scalar(backup, "PRAGMA user_version"));
                    assertEquals("ok", scalar(backup, "PRAGMA quick_check"));
                    assertEquals(before, rows(backup)); assertEquals(objects, planObjects(backup));
                    assertTrue(scalar(backup, "SELECT sql FROM sqlite_master WHERE name='download_output_plans'").contains("manifest_relative IS NOT NULL"));
                }
                migrated.write(connection -> {
                    try (var update = connection.createStatement()) { update.execute("UPDATE download_output_plans SET relative_path=relative_path||'.updated' WHERE rowid=83"); }
                    return null;
                });
                assertEquals("1", value(migrated, "SELECT COUNT(*) FROM test_plan_audit"));
            }
        }
        try (var reopened = new Database(paths)) {
            assertEquals("", reopened.diagnostics().get("lastMigrationBackup"));
            assertEquals(16, reopened.diagnostics().get("schemaVersion"));
        }
    }

    @Test void schema16AllowsDatabaseOnlyDuplicateEvidenceAndKeepsEveryOtherRelationshipConstraint() throws Exception {
        var paths = v15();
        try (var migrated = new Database(paths)) {
            for (String reason : List.of("SOURCE_MESSAGE", "FILE_ID", "CONTENT_SHA")) {
                migrated.write(connection -> {
                    try (var update = connection.prepareStatement("UPDATE download_output_plans SET manifest_relative=NULL,reason=? WHERE rowid=83")) {
                        update.setString(1, reason); assertEquals(1, update.executeUpdate());
                    }
                    return null;
                });
            }
            assertEquals("1", value(migrated, "SELECT COUNT(*) FROM download_output_plans WHERE rowid=83 AND duplicate_of IS NOT NULL AND manifest_relative IS NULL"));
            for (String mutation : List.of(
                "UPDATE download_output_plans SET duplicate_of=NULL WHERE rowid=83",
                "UPDATE download_output_plans SET reason='NEW' WHERE rowid=83",
                "UPDATE download_output_plans SET reason='UNCONFIRMED' WHERE rowid=83",
                "UPDATE download_output_plans SET manifest_relative='user.json' WHERE rowid=11",
                "UPDATE download_output_plans SET duplicate_of='missing-log' WHERE rowid=83",
                "UPDATE download_output_plans SET item_id='missing-item' WHERE rowid=83",
                "UPDATE download_output_plans SET sha256='invalid' WHERE rowid=83",
                "UPDATE download_output_plans SET relative_path=NULL WHERE rowid=83",
                "UPDATE download_output_plans SET planned_at=NULL WHERE rowid=83",
                "INSERT INTO download_output_plans SELECT * FROM download_output_plans WHERE rowid=83")) {
                var before = migrated.read(DownloadManifestMigrationTest::rows);
                assertThrows(IllegalStateException.class, () -> migrated.write(connection -> {
                    try (var update = connection.createStatement()) { update.execute(mutation); }
                    return null;
                }), mutation);
                assertEquals(before, migrated.read(DownloadManifestMigrationTest::rows), mutation);
            }
            assertEquals("0", value(migrated, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test void failedFinalForeignKeyCheckRollsBackTheRebuiltTableAndPreservesOriginalAndBackup() throws Exception {
        var paths = v15();
        Map<String, List<List<String>>> before; Map<String, String> objects; String schema;
        try (var original = DriverManager.getConnection("jdbc:sqlite:" + paths.database()); var statement = original.createStatement()) {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.execute("CREATE TABLE test_orphan(id INTEGER PRIMARY KEY,parent TEXT REFERENCES download_items(item_id))");
            statement.execute("INSERT INTO test_orphan VALUES(7,'missing-item')");
            before = rows(original); objects = planObjects(original);
            schema = scalar(original, "SELECT sql FROM sqlite_master WHERE name='download_output_plans'");
        }
        assertThrows(IllegalStateException.class, () -> new Database(paths));
        try (var original = DriverManager.getConnection("jdbc:sqlite:" + paths.database())) {
            assertEquals("15", scalar(original, "PRAGMA user_version"));
            assertEquals(before, rows(original)); assertEquals(objects, planObjects(original));
            assertEquals(schema, scalar(original, "SELECT sql FROM sqlite_master WHERE name='download_output_plans'"));
            assertEquals("0", scalar(original, "SELECT COUNT(*) FROM sqlite_master WHERE name='download_output_plans_v16'"));
        }
        try (var backups = Files.list(paths.backups())) {
            var files = backups.toList(); assertEquals(1, files.size());
            try (var backup = DriverManager.getConnection("jdbc:sqlite:" + files.getFirst())) {
                assertEquals("15", scalar(backup, "PRAGMA user_version")); assertEquals(before, rows(backup));
                assertEquals(schema, scalar(backup, "SELECT sql FROM sqlite_master WHERE name='download_output_plans'"));
            }
        }
    }

    private AppPaths v15() throws Exception {
        var paths = new AppPaths(directory.resolve("app"));
        try (var database = new Database(paths)) {
            var repository = new DownloadRepository(database);
            var post = new Post("1:-1001:1", "1", "-1001", "Synthetic", "1", "title", "body", "0",
                List.of(new MessageRef("-1001", "1", "https://t.me/synthetic/1")), List.of(), null, null, List.of(), false, "UNAVAILABLE", List.of());
            var file = new FrozenFile(new Media("1:-1001:100:0", new MessageRef("-1001", "100", "https://t.me/synthetic/100"),
                "MAIN", "DOCUMENT", "sample.bin", 17, ""), "stable-uid", 17, "sample.bin");
            var tasks = new ArrayList<Task>();
            for (int number = 0; number < 3; number++) {
                var task = repository.create("1", UUID.randomUUID().toString(), "frozen-" + number, post, directory.resolve("out-" + number), List.of(file));
                var item = task.items().getFirst();
                repository.progress("1", task.id(), item.id(), "WRITING", 17, 17, HASH, item.relativeFile());
                task = repository.task("1", task.id()); item = task.items().getFirst();
                var plan = repository.outputPlan(task, item, HASH);
                if (number > 0) {
                    String manifest = Path.of("重复媒体", "来源-" + item.id() + ".json").toString();
                    final String itemId = item.id();
                    database.write(connection -> {
                        try (var update = connection.prepareStatement("UPDATE download_output_plans SET manifest_relative=? WHERE item_id=?")) {
                            update.setString(1, manifest); update.setString(2, itemId); update.executeUpdate();
                        }
                        return null;
                    });
                    task = repository.task("1", task.id()); item = task.items().getFirst(); plan = item.plan();
                }
                repository.finalizing(task, item, plan, plan.relativePath());
                if (number < 2) repository.complete("1", task.id(), item.id(), Path.of(plan.relativePath()).getFileName().toString(), Path.of(task.targetRoot()).resolve(plan.relativePath()), HASH);
                tasks.add(repository.task("1", task.id()));
            }
            final var seeded = tasks;
            database.write(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("INSERT INTO app_settings VALUES('protected-key','opaque-current-user-ciphertext',7)");
                    restoreV15(statement);
                    statement.execute("CREATE TABLE test_plan_audit(item_id TEXT)");
                    statement.execute("CREATE INDEX preserved_plan_reason ON download_output_plans(reason) WHERE duplicate_of IS NOT NULL");
                    statement.execute("CREATE TRIGGER preserved_plan_update AFTER UPDATE OF relative_path ON download_output_plans BEGIN INSERT INTO test_plan_audit VALUES(new.item_id); END");
                    for (int number = 0; number < seeded.size(); number++) {
                        try (var update = connection.prepareStatement("UPDATE download_output_plans SET rowid=? WHERE item_id=?")) {
                            update.setInt(1, new int[]{11,37,83}[number]); update.setString(2, seeded.get(number).items().getFirst().id()); update.executeUpdate();
                        }
                    }
                    statement.execute("PRAGMA user_version=15");
                }
                return null;
            });
        }
        return paths;
    }

    private static void restoreV15(Statement statement) throws SQLException {
        statement.execute("""
            CREATE TABLE download_output_plans_v15 (
                item_id TEXT PRIMARY KEY NOT NULL REFERENCES download_items(item_id),relative_path TEXT NOT NULL,
                sha256 TEXT NOT NULL CHECK(length(sha256)=64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
                duplicate_of TEXT REFERENCES download_completion_log(item_id),reason TEXT NOT NULL,
                manifest_relative TEXT,planned_at INTEGER NOT NULL,
                CHECK((duplicate_of IS NULL AND manifest_relative IS NULL AND reason='NEW')
                   OR (duplicate_of IS NOT NULL AND manifest_relative IS NOT NULL AND reason IN ('SOURCE_MESSAGE','FILE_ID','CONTENT_SHA'))))
            """);
        statement.execute("INSERT INTO download_output_plans_v15 SELECT * FROM download_output_plans");
        statement.execute("DROP TABLE download_output_plans");
        statement.execute("ALTER TABLE download_output_plans_v15 RENAME TO download_output_plans");
    }

    private static Map<String, List<List<String>>> rows(Connection connection) throws SQLException {
        var result = new TreeMap<String, List<List<String>>>(); var tables = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rows.next()) tables.add(rows.getString(1));
        }
        for (String table : tables) {
            var records = new ArrayList<List<String>>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT rowid,* FROM \"" + table.replace("\"", "\"\"") + "\" ORDER BY rowid")) {
                while (rows.next()) {
                    var record = new ArrayList<String>();
                    for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) record.add(rows.getString(column));
                    records.add(record);
                }
            }
            result.put(table, records);
        }
        return result;
    }
    private static Map<String, String> planObjects(Connection connection) throws SQLException {
        var result = new TreeMap<String, String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT name,sql FROM sqlite_master WHERE tbl_name='download_output_plans' AND type IN('index','trigger') ORDER BY name")) {
            while (rows.next()) result.put(rows.getString(1), rows.getString(2));
        }
        return result;
    }
    private static String value(Database database, String sql) { return database.read(connection -> scalar(connection, sql)); }
    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getString(1); }
    }
}
