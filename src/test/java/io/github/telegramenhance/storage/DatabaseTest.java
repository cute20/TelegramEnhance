package io.github.telegramenhance.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {
    @TempDir Path directory;

    @Test
    void createsDurableSchemaAndReportsActualPragmas() {
        AppPaths paths = new AppPaths(directory);
        try (Database database = new Database(paths)) {
            Map<String, Object> diagnostics = database.diagnostics();
            assertEquals(Database.SCHEMA_VERSION, diagnostics.get("schemaVersion"));
            assertEquals("wal", diagnostics.get("journalMode"));
            assertEquals("FULL", diagnostics.get("synchronous"));
            assertEquals(true, diagnostics.get("foreignKeys"));
            assertEquals(5_000, diagnostics.get("busyTimeoutMs"));
            assertEquals("ok", diagnostics.get("integrity"));
            assertFalse(((String) diagnostics.get("sqliteVersion")).isBlank());
            assertEquals("", diagnostics.get("lastMigrationBackup"));
            database.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO app_settings VALUES ('example', 'persisted', 1)");
                }
                return null;
            });
        }
        try (Database reopened = new Database(paths)) {
            assertEquals("persisted", value(reopened, "SELECT setting_value FROM app_settings WHERE setting_key='example'"));
            assertEquals("ok", reopened.diagnostics().get("integrity"));
        }
    }

    @Test
    void failedWriteRollsBackAndTheNextWriteWorks() {
        try (Database database = new Database(new AppPaths(directory))) {
            assertThrows(IllegalArgumentException.class, () -> database.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO app_settings VALUES ('partial', 'must rollback', 1)");
                }
                throw new IllegalArgumentException("deliberately fail transaction");
            }));
            assertEquals("0", value(database, "SELECT COUNT(*) FROM app_settings"));
            database.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO app_settings VALUES ('complete', 'committed', 2)");
                }
                return null;
            });
            assertEquals("1", value(database, "SELECT COUNT(*) FROM app_settings"));
        }
    }

    @Test
    void migrationBackupIncludesCommittedWalAndKeepsOriginalVersion() throws Exception {
        AppPaths paths = new AppPaths(directory);
        // Keep the writer open, with auto-checkpoint disabled, to exercise a real WAL backup.
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
             Statement statement = original.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("CREATE TABLE existing_data (message TEXT NOT NULL)");
            statement.executeUpdate("INSERT INTO existing_data VALUES ('keep this committed WAL row')");
            assertTrue(Files.size(Path.of(paths.database() + "-wal")) > 0);
            try (Database database = new Database(paths)) {
                assertEquals("keep this committed WAL row", value(database, "SELECT message FROM existing_data"));
                Path backupPath = Path.of((String) database.diagnostics().get("lastMigrationBackup"));
                assertTrue(Files.isRegularFile(backupPath));
                try (Connection backup = DriverManager.getConnection("jdbc:sqlite:" + backupPath);
                     Statement read = backup.createStatement()) {
                    try (ResultSet rows = read.executeQuery("SELECT message FROM existing_data")) {
                        assertTrue(rows.next());
                        assertEquals("keep this committed WAL row", rows.getString(1));
                    }
                    try (ResultSet version = read.executeQuery("PRAGMA user_version")) {
                        assertTrue(version.next());
                        assertEquals(0, version.getInt(1));
                    }
                }
            }
        }
    }

    @Test
    void futureSchemaIsRefusedWithoutLosingData() throws Exception {
        AppPaths paths = new AppPaths(directory);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE future_data (value TEXT)");
            statement.executeUpdate("INSERT INTO future_data VALUES ('untouched')");
            statement.execute("PRAGMA user_version=42");
        }
        assertThrows(IllegalStateException.class, () -> new Database(paths));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT value FROM future_data")) {
                assertTrue(rows.next());
                assertEquals("untouched", rows.getString(1));
            }
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(version.next());
                assertEquals(42, version.getInt(1));
            }
        }
    }

    @Test
    void migrationFailurePreservesTheExistingDatabaseAndBackup() throws Exception {
        AppPaths paths = new AppPaths(directory);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE app_settings (legacy_value TEXT)");
            statement.executeUpdate("INSERT INTO app_settings VALUES ('existing data')");
        }
        assertThrows(IllegalStateException.class, () -> new Database(paths));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT legacy_value FROM app_settings")) {
            assertTrue(rows.next());
            assertEquals("existing data", rows.getString(1));
        }
        try (var backups = Files.list(paths.backups())) { assertEquals(1, backups.count()); }
    }

    @Test
    void v1MigrationPreservesAllCredentialsAndBacksUpTheCommittedWal() throws Exception {
        AppPaths paths = new AppPaths(directory);
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
             Statement statement = original.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("CREATE TABLE app_settings (setting_key TEXT PRIMARY KEY NOT NULL, setting_value TEXT NOT NULL, updated_at INTEGER NOT NULL)");
            statement.execute("PRAGMA user_version=1");
            statement.executeUpdate("INSERT INTO app_settings VALUES ('telegram.api_id', '12345', 1)");
            statement.executeUpdate("INSERT INTO app_settings VALUES ('telegram.api_hash.protected', 'opaque-dpapi-hash', 2)");
            statement.executeUpdate("INSERT INTO app_settings VALUES ('telegram.tdlib_key.protected', 'opaque-dpapi-session-key', 3)");
            statement.executeUpdate("INSERT INTO app_settings VALUES ('proxy.password.protected', 'opaque-dpapi-proxy-password', 4)");
            try (Database database = new Database(paths)) {
                assertEquals(Database.SCHEMA_VERSION, database.diagnostics().get("schemaVersion"));
                assertEquals("12345", value(database, "SELECT setting_value FROM app_settings WHERE setting_key='telegram.api_id'"));
                assertEquals("opaque-dpapi-hash", value(database, "SELECT setting_value FROM app_settings WHERE setting_key='telegram.api_hash.protected'"));
                assertEquals("opaque-dpapi-session-key", value(database, "SELECT setting_value FROM app_settings WHERE setting_key='telegram.tdlib_key.protected'"));
                assertEquals("opaque-dpapi-proxy-password", value(database, "SELECT setting_value FROM app_settings WHERE setting_key='proxy.password.protected'"));
                assertEquals("4", value(database, "SELECT COUNT(*) FROM app_settings"));
                assertEquals("0", value(database, "SELECT COUNT(*) FROM resource_posts"));
                Path backupPath = Path.of((String) database.diagnostics().get("lastMigrationBackup"));
                try (Connection backup = DriverManager.getConnection("jdbc:sqlite:" + backupPath);
                     Statement read = backup.createStatement()) {
                    try (ResultSet version = read.executeQuery("PRAGMA user_version")) {
                        assertTrue(version.next());
                        assertEquals(1, version.getInt(1));
                    }
                    try (ResultSet rows = read.executeQuery("SELECT setting_value FROM app_settings WHERE setting_key='telegram.tdlib_key.protected'")) {
                        assertTrue(rows.next());
                        assertEquals("opaque-dpapi-session-key", rows.getString(1));
                    }
                    try (ResultSet rows = read.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='resource_posts'")) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1));
                    }
                }
            }
        }
    }

    @Test
    void failedV2MigrationRollsBackNewTablesAndKeepsVersionOneAndCredentials() throws Exception {
        AppPaths paths = new AppPaths(directory);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE app_settings (setting_key TEXT PRIMARY KEY NOT NULL, setting_value TEXT NOT NULL, updated_at INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO app_settings VALUES ('telegram.api_hash.protected', 'keep-protected-credential', 1)");
            // Conflict late in v2 migration, after resource_posts has already been created.
            statement.execute("CREATE TABLE resource_comment_media (legacy_value TEXT)");
            statement.executeUpdate("INSERT INTO resource_comment_media VALUES ('existing media')");
            statement.execute("PRAGMA user_version=1");
        }
        assertThrows(IllegalStateException.class, () -> new Database(paths));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            try (ResultSet rows = statement.executeQuery("SELECT setting_value FROM app_settings")) {
                assertTrue(rows.next());
                assertEquals("keep-protected-credential", rows.getString(1));
            }
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('resource_posts', 'resource_comment_messages')")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            try (ResultSet rows = statement.executeQuery("SELECT legacy_value FROM resource_comment_media")) {
                assertTrue(rows.next());
                assertEquals("existing media", rows.getString(1));
            }
        }
        try (var backups = Files.list(paths.backups())) { assertEquals(1, backups.count()); }
    }

    @Test
    void v2ToCurrentSchemaPreservesLegacyJsonCredentialsAndWalCheckpointWithConsistentBackup() throws Exception {
        AppPaths paths = new AppPaths(directory);
        String legacyPost = """
            {"key":"1001:-1001:album:9999","accountId":"1001","channelId":"-1001","channelTitle":"旧频道",
             "mainMessageId":"100","title":"保留标题","text":"保留旧正文","albumId":"9999",
             "mainMessages":[{"chatId":"-1001","messageId":"100","link":"https://t.me/example/100"}],"mainMedia":[],
             "discussionChatId":"-2001","discussionRootMessageId":"500",
             "discussionRoots":[{"chatId":"-2001","messageId":"500","link":""}],
             "canReadComments":true,"coverage":"PARTIAL","warnings":[]}
            """;
        String legacyMedia = """
            {"key":"1001:-2001:601:0","message":{"chatId":"-2001","messageId":"601","link":""},
             "source":"COMMENT","type":"VIDEO","fileName":"keep.mp4","size":4567,"caption":"保留说明"}
            """;
        String mixedWarnings = "[\"主帖相册通过有限邻域读取识别，请与 Telegram 核对成员；本片不宣称已证明相册完整。\"]";
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
             Statement statement = original.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            createV2Schema(original);
            statement.executeUpdate("INSERT INTO app_settings VALUES ('telegram.api_hash.protected', 'opaque-dpapi-v2-hash', 1)");
            statement.executeUpdate("INSERT INTO app_settings VALUES ('telegram.tdlib_key.protected', 'opaque-dpapi-v2-key', 2)");
            try (var insert = original.prepareStatement("INSERT INTO resource_posts VALUES ('1001:-1001:album:9999', '1001', '-1001', '100', ?, 3)")) {
                insert.setString(1, legacyPost); insert.executeUpdate();
            }
            statement.executeUpdate("INSERT INTO resource_comment_messages VALUES ('1001:-1001:album:9999', '-2001', '601', '')");
            statement.executeUpdate("INSERT INTO resource_comment_messages VALUES ('1001:-1001:album:9999', '-2001', '602', '')");
            try (var insert = original.prepareStatement("""
                    INSERT INTO resource_comment_media(post_key, media_key, chat_id, message_id, media_json)
                    VALUES ('1001:-1001:album:9999', '1001:-2001:601:0', '-2001', '601', ?)
                    """)) {
                insert.setString(1, legacyMedia); insert.executeUpdate();
            }
            try (var insert = original.prepareStatement("INSERT INTO resource_scan_state VALUES ('1001:-1001:album:9999', '600', 1, 'PARTIAL', ?, 2, 4)")) {
                insert.setString(1, mixedWarnings); insert.executeUpdate();
            }
            assertTrue(Files.size(Path.of(paths.database() + "-wal")) > 0);
            try (Database migrated = new Database(paths)) {
                assertEquals(Database.SCHEMA_VERSION, migrated.diagnostics().get("schemaVersion"));
                assertEquals("ok", migrated.diagnostics().get("integrity"));
                assertEquals(legacyPost, value(migrated, "SELECT post_json FROM resource_posts"));
                assertEquals(legacyMedia, value(migrated, "SELECT media_json FROM resource_comment_media"));
                assertEquals(mixedWarnings, value(migrated, "SELECT warnings_json FROM resource_scan_state"));
                assertEquals("opaque-dpapi-v2-hash", value(migrated, "SELECT setting_value FROM app_settings WHERE setting_key='telegram.api_hash.protected'"));
                assertEquals("opaque-dpapi-v2-key", value(migrated, "SELECT setting_value FROM app_settings WHERE setting_key='telegram.tdlib_key.protected'"));
                var detail = new ResourceRepository(migrated).detail("1001:-1001:album:9999", 1, 0);
                assertEquals("PARTIAL", detail.mainCoverage(), "Old JSON cannot retroactively prove the main album");
                assertEquals("COMPLETE", detail.commentCoverage(), "The legacy album-only warning is not a comment failure");
                assertEquals("PARTIAL", detail.coverage());
                assertEquals(2L, detail.indexedCommentMessages());
                assertEquals(1L, detail.indexedCommentMedia());
                assertEquals("600", detail.nextCursor());
                assertEquals("keep.mp4", detail.commentMedia().getFirst().fileName());

                Path backupPath = Path.of((String) migrated.diagnostics().get("lastMigrationBackup"));
                assertTrue(Files.isRegularFile(backupPath));
                assertTrue(backupPath.getFileName().toString().startsWith("metadata-v2-"));
                try (Connection backup = DriverManager.getConnection("jdbc:sqlite:" + backupPath)) {
                    assertEquals("2", scalar(backup, "PRAGMA user_version"));
                    assertEquals("ok", scalar(backup, "PRAGMA quick_check"));
                    assertEquals(legacyPost, scalar(backup, "SELECT post_json FROM resource_posts"));
                    assertEquals(legacyMedia, scalar(backup, "SELECT media_json FROM resource_comment_media"));
                    assertEquals("2", scalar(backup, "SELECT COUNT(*) FROM resource_comment_messages"));
                    assertEquals("600", scalar(backup, "SELECT next_cursor FROM resource_scan_state"));
                    assertEquals(mixedWarnings, scalar(backup, "SELECT warnings_json FROM resource_scan_state"));
                    assertEquals("opaque-dpapi-v2-key", scalar(backup, "SELECT setting_value FROM app_settings WHERE setting_key='telegram.tdlib_key.protected'"));
                    try (Statement check = backup.createStatement(); ResultSet violations = check.executeQuery("PRAGMA foreign_key_check")) {
                        assertFalse(violations.next(), "The backup must contain matching message/media/parent rows");
                    }
                }
            }
        }
        try (Database reopened = new Database(paths)) {
            assertEquals("", reopened.diagnostics().get("lastMigrationBackup"), "An already migrated database is not migrated again");
            assertEquals("600", new ResourceRepository(reopened).detail("1001:-1001:album:9999", 20, 0).nextCursor());
        }
        try (var backups = Files.list(paths.backups())) { assertEquals(1L, backups.count()); }
    }

    @Test
    void v3ToCurrentSchemaAddsNullableObservationsAndPreservesWalDataCredentialsAndVersionThreeBackup() throws Exception {
        AppPaths paths = new AppPaths(directory);
        String postJson = """
            {"key":"1001:-1001:100","accountId":"1001","channelId":"-1001","channelTitle":"保留频道",
             "mainMessageId":"100","title":"保留标题","text":"保留正文","albumId":"0","mainMessages":[],"mainMedia":[],
             "discussionChatId":"-2001","discussionRootMessageId":"500","discussionRoots":[],
             "canReadComments":true,"coverage":"PARTIAL","warnings":[]}
            """;
        String mediaJson = """
            {"key":"1001:-2001:601:0","message":{"chatId":"-2001","messageId":"601","link":""},
             "source":"COMMENT","type":"VIDEO","fileName":"v3-kept.mp4","size":3456,"caption":"保留说明"}
            """;
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
             Statement statement = original.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            createV2Schema(original); // v3 changed the JSON contract, without changing these tables.
            statement.execute("PRAGMA user_version=3");
            statement.executeUpdate("INSERT INTO app_settings VALUES ('telegram.api_hash.protected', 'opaque-v3-dpapi-hash', 1)");
            statement.executeUpdate("INSERT INTO app_settings VALUES ('telegram.tdlib_key.protected', 'opaque-v3-dpapi-key', 2)");
            try (var insert = original.prepareStatement("INSERT INTO resource_posts VALUES ('1001:-1001:100', '1001', '-1001', '100', ?, 3)")) {
                insert.setString(1, postJson); insert.executeUpdate();
            }
            statement.executeUpdate("INSERT INTO resource_comment_messages VALUES ('1001:-1001:100', '-2001', '601', 'kept-link')");
            try (var insert = original.prepareStatement("""
                    INSERT INTO resource_comment_media(post_key, media_key, chat_id, message_id, media_json)
                    VALUES ('1001:-1001:100', '1001:-2001:601:0', '-2001', '601', ?)
                    """)) {
                insert.setString(1, mediaJson); insert.executeUpdate();
            }
            statement.executeUpdate("INSERT INTO resource_scan_state VALUES ('1001:-1001:100', 'v3-cursor', 0, 'PARTIAL', '[\"legacy unresolved scan\"]', 1, 4)");
            assertTrue(Files.size(Path.of(paths.database() + "-wal")) > 0);
            try (Database migrated = new Database(paths)) {
                assertEquals(Database.SCHEMA_VERSION, migrated.diagnostics().get("schemaVersion"));
                assertEquals("ok", migrated.diagnostics().get("integrity"));
                assertEquals(postJson, value(migrated, "SELECT post_json FROM resource_posts"));
                assertEquals(mediaJson, value(migrated, "SELECT media_json FROM resource_comment_media"));
                assertEquals("1", value(migrated, "SELECT COUNT(*) FROM resource_comment_messages WHERE observation_json IS NULL"));
                assertEquals("v3-cursor", value(migrated, "SELECT next_cursor FROM resource_scan_state"));
                assertEquals("opaque-v3-dpapi-hash", value(migrated, "SELECT setting_value FROM app_settings WHERE setting_key='telegram.api_hash.protected'"));
                assertEquals("opaque-v3-dpapi-key", value(migrated, "SELECT setting_value FROM app_settings WHERE setting_key='telegram.tdlib_key.protected'"));
                var detail = new ResourceRepository(migrated).detail("1001:-1001:100", 20, 0);
                assertEquals(1L, detail.indexedCommentMessages());
                assertEquals(1L, detail.indexedCommentMedia());
                assertEquals("v3-kept.mp4", detail.commentMedia().getFirst().fileName());
                assertEquals(java.util.List.of("legacy unresolved scan"), detail.commentScanWarnings());
                assertEquals("PARTIAL", detail.commentCoverage());

                Path backupPath = Path.of((String) migrated.diagnostics().get("lastMigrationBackup"));
                assertTrue(backupPath.getFileName().toString().startsWith("metadata-v3-"));
                try (Connection backup = DriverManager.getConnection("jdbc:sqlite:" + backupPath)) {
                    assertEquals("3", scalar(backup, "PRAGMA user_version"));
                    assertEquals("ok", scalar(backup, "PRAGMA quick_check"));
                    assertEquals(postJson, scalar(backup, "SELECT post_json FROM resource_posts"));
                    assertEquals(mediaJson, scalar(backup, "SELECT media_json FROM resource_comment_media"));
                    assertEquals("0", scalar(backup, "SELECT COUNT(*) FROM pragma_table_info('resource_comment_messages') WHERE name='observation_json'"));
                    assertEquals("v3-cursor", scalar(backup, "SELECT next_cursor FROM resource_scan_state"));
                    assertEquals("opaque-v3-dpapi-key", scalar(backup, "SELECT setting_value FROM app_settings WHERE setting_key='telegram.tdlib_key.protected'"));
                    try (Statement check = backup.createStatement(); ResultSet violations = check.executeQuery("PRAGMA foreign_key_check")) {
                        assertFalse(violations.next());
                    }
                }
            }
        }
        try (Database reopened = new Database(paths)) {
            assertEquals("", reopened.diagnostics().get("lastMigrationBackup"));
            assertEquals("1", value(reopened, "SELECT COUNT(*) FROM resource_comment_messages WHERE observation_json IS NULL"));
            assertEquals("v3-cursor", value(reopened, "SELECT next_cursor FROM resource_scan_state"));
        }
        try (var backups = Files.list(paths.backups())) { assertEquals(1L, backups.count()); }
    }

    /** Frozen schema-v2 fixture, independent of the current migration implementation. */
    private void createV2Schema(Connection connection) throws Exception {
        String[] tables = {
            "CREATE TABLE app_settings (setting_key TEXT PRIMARY KEY NOT NULL, setting_value TEXT NOT NULL, updated_at INTEGER NOT NULL)",
            """
            CREATE TABLE resource_posts (post_key TEXT PRIMARY KEY NOT NULL, account_id TEXT NOT NULL, channel_id TEXT NOT NULL,
                main_message_id TEXT NOT NULL, post_json TEXT NOT NULL, updated_at INTEGER NOT NULL,
                UNIQUE(account_id, channel_id, main_message_id))
            """,
            "CREATE INDEX resource_posts_channel ON resource_posts(account_id, channel_id)",
            """
            CREATE TABLE resource_comment_messages (post_key TEXT NOT NULL REFERENCES resource_posts(post_key) ON DELETE CASCADE,
                chat_id TEXT NOT NULL, message_id TEXT NOT NULL, link TEXT, PRIMARY KEY(post_key, chat_id, message_id))
            """,
            """
            CREATE TABLE resource_comment_media (position INTEGER PRIMARY KEY AUTOINCREMENT, post_key TEXT NOT NULL, media_key TEXT NOT NULL,
                chat_id TEXT NOT NULL, message_id TEXT NOT NULL, media_json TEXT NOT NULL, UNIQUE(post_key, media_key),
                FOREIGN KEY(post_key, chat_id, message_id) REFERENCES resource_comment_messages(post_key, chat_id, message_id) ON DELETE CASCADE)
            """,
            "CREATE INDEX resource_comment_media_post ON resource_comment_media(post_key, position)",
            """
            CREATE TABLE resource_scan_state (post_key TEXT PRIMARY KEY NOT NULL REFERENCES resource_posts(post_key) ON DELETE CASCADE,
                next_cursor TEXT, exhausted INTEGER NOT NULL CHECK(exhausted IN (0,1)),
                coverage TEXT NOT NULL CHECK(coverage IN ('PARTIAL','COMPLETE','UNAVAILABLE')),
                warnings_json TEXT NOT NULL, message_count INTEGER NOT NULL CHECK(message_count >= 0), updated_at INTEGER NOT NULL)
            """,
            "PRAGMA user_version=2"
        };
        try (Statement statement = connection.createStatement()) {
            for (String sql : tables) statement.execute(sql);
        }
    }

    @Test
    void v4ToV5OnlyAddsEmptyProjectionsAndBacksUpAllCommittedWalMetadata() throws Exception {
        AppPaths paths = new AppPaths(directory);
        String snapshot = """
            {"key":"1:-1001:100","accountId":"1","channelId":"-1001","channelTitle":"旧频道","mainMessageId":"100",
             "title":"保留标题","text":"#保留 完整正文","albumId":"0","mainMessages":[],"mainMedia":[],
             "discussionChatId":"-2001","discussionRootMessageId":"500","discussionRoots":[],"canReadComments":true,
             "coverage":"PARTIAL","warnings":[],"mainCoverage":"COMPLETE","albumEvidence":{"complete":true,"method":"SINGLE_MESSAGE"}}
            """;
        String media = """
            {"key":"1:-2001:601:0","message":{"chatId":"-2001","messageId":"601","link":"kept-link"},
             "source":"COMMENT","type":"VIDEO","fileName":"kept.mp4","size":12345,"caption":"保留说明"}
            """;
        String observation = """
            {"message":{"chatId":"-2001","messageId":"601","link":"kept-link"},"mediaComplete":false,"warnings":["旧媒体尚未展开"]}
            """;
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
             Statement statement = original.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL"); statement.execute("PRAGMA wal_autocheckpoint=0");
            createV2Schema(original);
            statement.execute("ALTER TABLE resource_comment_messages ADD COLUMN observation_json TEXT");
            statement.execute("PRAGMA user_version=4");
            statement.executeUpdate("INSERT INTO app_settings VALUES ('telegram.tdlib_key.protected','opaque-v4-dpapi',1)");
            try (var insert = original.prepareStatement("INSERT INTO resource_posts VALUES ('1:-1001:100','1','-1001','100',?,2)")) {
                insert.setString(1, snapshot); insert.executeUpdate();
            }
            try (var insert = original.prepareStatement("INSERT INTO resource_comment_messages VALUES ('1:-1001:100','-2001','601','kept-link',?)")) {
                insert.setString(1, observation); insert.executeUpdate();
            }
            try (var insert = original.prepareStatement("INSERT INTO resource_comment_media(post_key,media_key,chat_id,message_id,media_json) VALUES ('1:-1001:100','1:-2001:601:0','-2001','601',?)")) {
                insert.setString(1, media); insert.executeUpdate();
            }
            statement.executeUpdate("INSERT INTO resource_scan_state VALUES ('1:-1001:100','v4-checkpoint',0,'PARTIAL','[]',1,3)");
            assertTrue(Files.size(Path.of(paths.database() + "-wal")) > 0);
            try (Database migrated = new Database(paths)) {
                assertEquals(Database.SCHEMA_VERSION, migrated.diagnostics().get("schemaVersion"));
                assertEquals("ok", migrated.diagnostics().get("integrity"));
                assertEquals(snapshot, value(migrated, "SELECT post_json FROM resource_posts"));
                assertEquals(media, value(migrated, "SELECT media_json FROM resource_comment_media"));
                assertEquals(observation, value(migrated, "SELECT observation_json FROM resource_comment_messages"));
                assertEquals("v4-checkpoint", value(migrated, "SELECT next_cursor FROM resource_scan_state"));
                assertEquals("opaque-v4-dpapi", value(migrated, "SELECT setting_value FROM app_settings"));
                assertEquals("0", value(migrated, "SELECT COUNT(*) FROM resource_post_search"));
                assertEquals("0", value(migrated, "SELECT COUNT(*) FROM resource_post_tags"));
                Path backupPath = Path.of((String) migrated.diagnostics().get("lastMigrationBackup"));
                assertTrue(backupPath.getFileName().toString().startsWith("metadata-v4-"));
                try (Connection backup = DriverManager.getConnection("jdbc:sqlite:" + backupPath)) {
                    assertEquals("4", scalar(backup, "PRAGMA user_version"));
                    assertEquals("ok", scalar(backup, "PRAGMA quick_check"));
                    assertEquals(snapshot, scalar(backup, "SELECT post_json FROM resource_posts"));
                    assertEquals(media, scalar(backup, "SELECT media_json FROM resource_comment_media"));
                    assertEquals(observation, scalar(backup, "SELECT observation_json FROM resource_comment_messages"));
                    assertEquals("v4-checkpoint", scalar(backup, "SELECT next_cursor FROM resource_scan_state"));
                    assertEquals("opaque-v4-dpapi", scalar(backup, "SELECT setting_value FROM app_settings"));
                    assertEquals("0", scalar(backup, "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('resource_post_search','resource_post_tags')"));
                }
            }
        }
        try (Database reopened = new Database(paths)) {
            assertEquals("", reopened.diagnostics().get("lastMigrationBackup"));
            assertEquals(media, value(reopened, "SELECT media_json FROM resource_comment_media"));
            assertEquals("v4-checkpoint", value(reopened, "SELECT next_cursor FROM resource_scan_state"));
        }
    }

    @Test
    void failedV5ProjectionMigrationRollsBackNewTablesAndKeepsVersionFourDataAndBackup() throws Exception {
        AppPaths paths = new AppPaths(directory);
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
             Statement statement = original.createStatement()) {
            createV2Schema(original);
            statement.execute("ALTER TABLE resource_comment_messages ADD COLUMN observation_json TEXT");
            statement.execute("PRAGMA user_version=4");
            statement.executeUpdate("INSERT INTO app_settings VALUES ('telegram.tdlib_key.protected','keep-v4-key',1)");
            statement.execute("CREATE TABLE resource_post_tags (legacy_value TEXT)");
            statement.executeUpdate("INSERT INTO resource_post_tags VALUES ('keep-conflicting-data')");
        }
        assertThrows(IllegalStateException.class, () -> new Database(paths));
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database())) {
            assertEquals("4", scalar(original, "PRAGMA user_version"));
            assertEquals("keep-v4-key", scalar(original, "SELECT setting_value FROM app_settings"));
            assertEquals("keep-conflicting-data", scalar(original, "SELECT legacy_value FROM resource_post_tags"));
            assertEquals("0", scalar(original, "SELECT COUNT(*) FROM sqlite_master WHERE name='resource_post_search'"));
        }
        try (var backups = Files.list(paths.backups())) {
            var files = backups.toList(); assertEquals(1, files.size());
            try (Connection backup = DriverManager.getConnection("jdbc:sqlite:" + files.getFirst())) {
                assertEquals("4", scalar(backup, "PRAGMA user_version"));
                assertEquals("keep-v4-key", scalar(backup, "SELECT setting_value FROM app_settings"));
                assertEquals("ok", scalar(backup, "PRAGMA quick_check"));
            }
        }
    }

    @Test
    void claimedCurrentVersionWithoutObservationColumnIsRefusedWithoutDeletingData() throws Exception {
        AppPaths paths = new AppPaths(directory);
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
             Statement statement = original.createStatement()) {
            createV2Schema(original);
            statement.executeUpdate("INSERT INTO app_settings VALUES ('telegram.tdlib_key.protected', 'keep-this-key', 1)");
            statement.execute("PRAGMA user_version=" + Database.SCHEMA_VERSION);
        }
        assertThrows(IllegalStateException.class, () -> new Database(paths));
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database())) {
            assertEquals(Integer.toString(Database.SCHEMA_VERSION), scalar(original, "PRAGMA user_version"));
            assertEquals("keep-this-key", scalar(original, "SELECT setting_value FROM app_settings"));
            assertEquals("0", scalar(original, "SELECT COUNT(*) FROM pragma_table_info('resource_comment_messages') WHERE name='observation_json'"));
        }
        try (var backups = Files.list(paths.backups())) { assertEquals(0L, backups.count()); }
    }

    @Test
    void claimedCurrentVersionWithoutSearchTablesIsRefusedWithoutRewritingTheDatabase() throws Exception {
        AppPaths paths = new AppPaths(directory);
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database());
             Statement statement = original.createStatement()) {
            createV2Schema(original);
            statement.execute("ALTER TABLE resource_comment_messages ADD COLUMN observation_json TEXT");
            statement.execute("PRAGMA user_version=" + Database.SCHEMA_VERSION);
            statement.executeUpdate("INSERT INTO app_settings VALUES ('telegram.tdlib_key.protected','keep-before-projections',1)");
        }
        assertThrows(IllegalStateException.class, () -> new Database(paths));
        try (Connection original = DriverManager.getConnection("jdbc:sqlite:" + paths.database())) {
            assertEquals(Integer.toString(Database.SCHEMA_VERSION), scalar(original, "PRAGMA user_version"));
            assertEquals("keep-before-projections", scalar(original, "SELECT setting_value FROM app_settings"));
            assertEquals("0", scalar(original, "SELECT COUNT(*) FROM sqlite_master WHERE name='resource_post_search'"));
        }
        try (var backups = Files.list(paths.backups())) { assertEquals(0L, backups.count()); }
    }

    private String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    @Test
    void closeIsIdempotentAndFurtherAccessFails() {
        Database database = new Database(new AppPaths(directory));
        database.close();
        database.close();
        assertThrows(IllegalStateException.class, database::diagnostics);
        assertThrows(IllegalStateException.class, () -> database.read(connection -> null));
        assertThrows(IllegalStateException.class, () -> database.write(connection -> null));
    }

    private String value(Database database, String query) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        });
    }
}
