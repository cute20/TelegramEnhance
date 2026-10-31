package io.github.telegramenhance.storage;

import io.github.telegramenhance.config.AppPaths;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** One process owns one connection. Callbacks must contain only short local SQL work. */
@Component
public final class Database implements AutoCloseable {
    public static final int SCHEMA_VERSION = 16;
    private static final int BUSY_TIMEOUT_MS = 5_000;
    private final Path databasePath;
    private final Connection connection;
    private Path lastMigrationBackup;
    private boolean closed;

    public Database(AppPaths paths) {
        databasePath = paths.database();
        Connection opened = null;
        try {
            boolean existed = Files.exists(databasePath) && Files.size(databasePath) > 0;
            opened = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            connection = opened;
            int version = intPragma("user_version");
            if (version > SCHEMA_VERSION) {
                throw new IllegalStateException("数据库版本高于本程序支持的版本，请使用匹配的新版本程序");
            }
            execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
            execute("PRAGMA foreign_keys=ON");
            execute("PRAGMA journal_mode=WAL");
            execute("PRAGMA synchronous=FULL");
            if (version < SCHEMA_VERSION) {
                if (existed) lastMigrationBackup = backupBeforeMigration(paths.backups(), version);
                migrate(version);
            }
            // Fail early for damaged files or a foreign schema with the same version number.
            try (Statement statement = connection.createStatement();
                 ResultSet ignored = statement.executeQuery("SELECT setting_key, setting_value, updated_at FROM app_settings LIMIT 0")) {
                // Preparing the read validates the required schema without touching settings.
            }
            try (Statement statement = connection.createStatement();
                 ResultSet ignored = statement.executeQuery("""
                     SELECT p.post_json, s.next_cursor, s.coverage, m.media_json, r.message_id, r.observation_json
                     FROM resource_posts p LEFT JOIN resource_scan_state s ON s.post_key=p.post_key
                     LEFT JOIN resource_comment_media m ON m.post_key=p.post_key
                     LEFT JOIN resource_comment_messages r ON r.post_key=p.post_key LIMIT 0
                     """)) {
                // Validate required v4 columns even when the file already claims schema version 4.
            }
            try (Statement statement = connection.createStatement();
                 ResultSet ignored = statement.executeQuery("""
                     SELECT s.normalized_text, t.normalized_tag FROM resource_post_search s
                     LEFT JOIN resource_post_tags t ON t.post_key=s.post_key LIMIT 0
                     """)) {
                // The claimed schema version alone does not prove that projections exist.
            }
            try (Statement statement = connection.createStatement();
                 ResultSet ignored = statement.executeQuery("""
                     SELECT r.run_id,r.revision,r.source_cursor,r.pending_json,r.pending_offset,r.pending_next_cursor,
                            r.pending_exhausted,r.pending_has_more,r.status,r.processed,r.created,r.updated,r.incomplete,
                            r.exhausted,r.warning,r.started_at,r.updated_at,e.post_key,e.main_complete
                     FROM channel_import_runs r LEFT JOIN channel_import_run_entries e
                       ON e.account_id=r.account_id AND e.channel_id=r.channel_id AND e.run_id=r.run_id LIMIT 0
                     """)) { }
            try (Statement statement = connection.createStatement(); ResultSet ignored = statement.executeQuery("""
                SELECT t.task_id,t.operation_id,t.fingerprint,t.post_json,t.target_root,t.relative_directory,t.state,
                       t.pause_requested,t.error_message,i.item_id,i.spec_json,i.relative_file,i.owner_token,
                       i.state,i.downloaded_bytes,i.written_bytes,i.sha256,c.unique_id,c.file_id,b.relative_directory,
                       r.unique_id,r.sha256 FROM download_tasks t
                LEFT JOIN download_items i ON i.task_id=t.task_id
                LEFT JOIN download_cache_owned c ON c.account_id=t.account_id
                LEFT JOIN download_target_bindings b ON b.account_id=t.account_id
                LEFT JOIN download_file_receipts r ON r.account_id=t.account_id LIMIT 0
                """)) { }
            try (Statement statement = connection.createStatement(); ResultSet ignored = statement.executeQuery("SELECT t.prepared_json,g.owner_run,i.temporary_id,i.formal_id,i.staging_json,i.archive_phase,i.archive_identity,e.event_json,c.sha256 FROM upload_tasks t LEFT JOIN upload_groups g ON g.task_id=t.id LEFT JOIN upload_items i ON i.task_id=t.id LEFT JOIN upload_events e ON e.group_id=g.id LEFT JOIN upload_claims c ON c.task_id=t.id LIMIT 0")) { }
            try (Statement statement = connection.createStatement(); ResultSet ignored = statement.executeQuery("SELECT i.history_id,h.account_id,h.unique_id,h.size,h.sha256,h.source_task_id,h.original_path,h.completed_at FROM download_items i LEFT JOIN download_history h ON h.id=i.history_id LIMIT 0")) { }
            try (Statement statement = connection.createStatement(); ResultSet ignored = statement.executeQuery("SELECT t.duplicate_policy,p.relative_path,p.sha256,p.duplicate_of,p.reason,p.manifest_relative,p.planned_at,l.source_key,l.source_link,l.post_link,l.output_path FROM download_tasks t LEFT JOIN download_items i ON i.task_id=t.task_id LEFT JOIN download_output_plans p ON p.item_id=i.item_id LEFT JOIN download_completion_log l ON l.item_id=i.item_id LIMIT 0")) { }
            try (Statement statement=connection.createStatement();ResultSet ignored=statement.executeQuery("SELECT r.account_id,r.current_version,r.revision,r.enabled,r.next_scan_at,v.config_json,o.version,o.observation_json FROM auto_rules r LEFT JOIN auto_rule_versions v ON v.rule_id=r.id AND v.version=r.current_version LEFT JOIN auto_rule_observations o ON o.rule_id=r.id LIMIT 0")){}
            try (Statement statement=connection.createStatement();ResultSet ignored=statement.executeQuery("SELECT a.item_id,a.operation_id,a.check_id,a.prior_temporary_id,a.failed_id,a.new_temporary_id,a.owner_run,a.state,f.failure_json FROM upload_retry_attempts a LEFT JOIN upload_send_failures f ON f.group_id=a.group_id AND f.old_id=a.prior_temporary_id LIMIT 0")){}
            try (Statement statement=connection.createStatement();ResultSet ignored=statement.executeQuery("SELECT a.rule_id,a.version,a.revision,a.target_json,b.task_id,b.snapshot_json,b.pause_kind,b.manual_takeover_at,b.settled_at FROM auto_rule_authorizations a LEFT JOIN auto_upload_bindings b ON b.rule_id=a.rule_id LIMIT 0")){}
            try(Statement statement=connection.createStatement();ResultSet ignored=statement.executeQuery("SELECT b.id,b.account_id,b.operation_id,b.fingerprint,b.snapshot_json,t.task_id,t.ordinal FROM download_batches b LEFT JOIN download_batch_tasks t ON t.batch_id=b.id LIMIT 0")){}
            try(Statement statement=connection.createStatement();ResultSet ignored=statement.executeQuery("""
                SELECT r.current_version,r.revision,r.enabled,r.next_scan_at,v.config_json,v.channel_id,s.phase,s.cursor,
                       s.pending_json,s.pending_offset,s.history_done,s.next_head_at,s.scanned,s.error,s.turn,p.post_key,p.post_json,
                       p.cursor,p.revision,p.next_review_at,m.media_key,m.unique_id,m.size,m.task_id,m.item_id,t.snapshot_json,t.manual,t.held,t.settled_at
                FROM download_rules r JOIN download_rule_versions v ON v.rule_id=r.id AND v.version=r.current_version
                LEFT JOIN download_rule_scans s ON s.rule_id=r.id AND s.version=r.current_version
                LEFT JOIN download_rule_posts p ON p.rule_id=r.id AND p.version=r.current_version
                LEFT JOIN download_rule_media m ON m.rule_id=r.id AND m.version=r.current_version
                LEFT JOIN download_rule_tasks t ON t.rule_id=r.id LIMIT 0
                """)){}
        } catch (SQLException | IOException | RuntimeException failure) {
            if (opened != null) {
                try { opened.close(); }
                catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            if (failure instanceof IllegalStateException stateFailure) throw stateFailure;
            throw new IllegalStateException("无法打开或迁移本机数据库；原数据库已保留", failure);
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Serializes reads with writes because the JDBC connection is deliberately shared. */
    public synchronized <T> T read(SqlWork<T> work) {
        requireOpen();
        try { return work.run(connection); }
        catch (SQLException failure) { throw new IllegalStateException("读取本机数据库失败", failure); }
    }

    /** Serial short transaction; no Telegram calls, file copying, or asynchronous work here. */
    public synchronized <T> T write(SqlWork<T> work) {
        requireOpen();
        try {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("保存本机数据库失败，事务未成功完成", failure);
        }
    }

    public synchronized Map<String, Object> diagnostics() {
        requireOpen();
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("databasePath", databasePath.toString());
            result.put("schemaVersion", intPragma("user_version"));
            result.put("sqliteVersion", scalar("SELECT sqlite_version()"));
            result.put("journalMode", scalar("PRAGMA journal_mode"));
            int synchronous = intPragma("synchronous");
            result.put("synchronous", switch (synchronous) {
                case 0 -> "OFF";
                case 1 -> "NORMAL";
                case 2 -> "FULL";
                case 3 -> "EXTRA";
                default -> "UNKNOWN";
            });
            result.put("foreignKeys", intPragma("foreign_keys") == 1);
            result.put("busyTimeoutMs", intPragma("busy_timeout"));
            result.put("integrity", scalar("PRAGMA quick_check"));
            result.put("lastMigrationBackup", lastMigrationBackup == null ? "" : lastMigrationBackup.toString());
            return Map.copyOf(result);
        } catch (SQLException failure) {
            throw new IllegalStateException("检查本机数据库失败", failure);
        }
    }

    private Path backupBeforeMigration(Path backupDirectory, int oldVersion) throws SQLException, IOException {
        Files.createDirectories(backupDirectory);
        String stamp = Instant.now().toString().replace(':', '-');
        Path target = backupDirectory.resolve("metadata-v" + oldVersion + "-" + stamp + "-" + UUID.randomUUID() + ".sqlite");
        // SQLite performs the copy, including committed WAL data, rather than copying a live file.
        try (PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, target.toString());
            statement.execute();
        }
        try (Connection backup = DriverManager.getConnection("jdbc:sqlite:" + target);
             Statement statement = backup.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equals(result.getString(1))) {
                throw new IllegalStateException("升级前数据库备份校验失败，已停止迁移");
            }
        }
        return target;
    }

    private void migrate(int oldVersion) throws SQLException {
        if (oldVersion < 0 || oldVersion >= SCHEMA_VERSION) throw new IllegalStateException("缺少所需数据库迁移，原数据库已保留");
        write(sql -> {
            try (Statement statement = sql.createStatement()) {
                if (oldVersion == 0) {
                    statement.execute("""
                        CREATE TABLE app_settings (
                            setting_key TEXT PRIMARY KEY NOT NULL,
                            setting_value TEXT NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
                }
                // Validate the v1 prerequisite within the same transaction as the v2 schema.
                try (ResultSet ignored = statement.executeQuery("SELECT setting_key, setting_value, updated_at FROM app_settings LIMIT 0")) { }
                if (oldVersion < 2) {
                statement.execute("""
                    CREATE TABLE resource_posts (
                        post_key TEXT PRIMARY KEY NOT NULL,
                        account_id TEXT NOT NULL,
                        channel_id TEXT NOT NULL,
                        main_message_id TEXT NOT NULL,
                        post_json TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        UNIQUE(account_id, channel_id, main_message_id)
                    )
                    """);
                statement.execute("CREATE INDEX resource_posts_channel ON resource_posts(account_id, channel_id)");
                statement.execute("""
                    CREATE TABLE resource_comment_messages (
                        post_key TEXT NOT NULL REFERENCES resource_posts(post_key) ON DELETE CASCADE,
                        chat_id TEXT NOT NULL,
                        message_id TEXT NOT NULL,
                        link TEXT,
                        PRIMARY KEY(post_key, chat_id, message_id)
                    )
                    """);
                statement.execute("""
                    CREATE TABLE resource_comment_media (
                        position INTEGER PRIMARY KEY AUTOINCREMENT,
                        post_key TEXT NOT NULL,
                        media_key TEXT NOT NULL,
                        chat_id TEXT NOT NULL,
                        message_id TEXT NOT NULL,
                        media_json TEXT NOT NULL,
                        UNIQUE(post_key, media_key),
                        FOREIGN KEY(post_key, chat_id, message_id)
                            REFERENCES resource_comment_messages(post_key, chat_id, message_id) ON DELETE CASCADE
                    )
                    """);
                statement.execute("CREATE INDEX resource_comment_media_post ON resource_comment_media(post_key, position)");
                statement.execute("""
                    CREATE TABLE resource_scan_state (
                        post_key TEXT PRIMARY KEY NOT NULL REFERENCES resource_posts(post_key) ON DELETE CASCADE,
                        next_cursor TEXT,
                        exhausted INTEGER NOT NULL CHECK(exhausted IN (0, 1)),
                        coverage TEXT NOT NULL CHECK(coverage IN ('PARTIAL', 'COMPLETE', 'UNAVAILABLE')),
                        warnings_json TEXT NOT NULL,
                        message_count INTEGER NOT NULL CHECK(message_count >= 0),
                        updated_at INTEGER NOT NULL
                    )
                    """);
                }
                // v3 versions the structured album/main/comment coverage JSON contract. Existing
                // v2 rows remain readable but unproven until refreshed; all media/checkpoints remain.
                if (oldVersion < 4) {
                    statement.execute("ALTER TABLE resource_comment_messages ADD COLUMN observation_json TEXT");
                }
                try (ResultSet ignored = statement.executeQuery("""
                        SELECT p.post_json,s.next_cursor,s.coverage,m.media_json,r.message_id,r.observation_json
                        FROM resource_posts p LEFT JOIN resource_scan_state s ON s.post_key=p.post_key
                        LEFT JOIN resource_comment_media m ON m.post_key=p.post_key
                        LEFT JOIN resource_comment_messages r ON r.post_key=p.post_key LIMIT 0
                        """)) {
                    // Refuse a damaged old schema before committing the new version number.
                }
                if (oldVersion < 5) {
                    statement.execute("""
                        CREATE TABLE resource_post_search (
                            post_key TEXT PRIMARY KEY NOT NULL REFERENCES resource_posts(post_key) ON DELETE CASCADE,
                            normalized_text TEXT NOT NULL
                        )
                        """);
                    statement.execute("""
                        CREATE TABLE resource_post_tags (
                            post_key TEXT NOT NULL REFERENCES resource_posts(post_key) ON DELETE CASCADE,
                            normalized_tag TEXT NOT NULL,
                            PRIMARY KEY(post_key, normalized_tag)
                        )
                        """);
                    statement.execute("CREATE INDEX resource_post_tags_lookup ON resource_post_tags(normalized_tag, post_key)");
                }
                try (ResultSet ignored = statement.executeQuery("SELECT s.normalized_text,t.normalized_tag FROM resource_post_search s LEFT JOIN resource_post_tags t ON t.post_key=s.post_key LIMIT 0")) { }
                if (oldVersion < 6) {
                    statement.execute("""
                        CREATE TABLE channel_import_runs (
                            account_id TEXT NOT NULL, channel_id TEXT NOT NULL, run_id TEXT NOT NULL,
                            revision INTEGER NOT NULL CHECK(revision>=0), source_cursor TEXT,
                            pending_json TEXT NOT NULL, pending_offset INTEGER NOT NULL CHECK(pending_offset>=0),
                            pending_next_cursor TEXT, pending_exhausted INTEGER NOT NULL CHECK(pending_exhausted IN (0,1)),
                            pending_has_more INTEGER NOT NULL CHECK(pending_has_more IN (0,1)),
                            status TEXT NOT NULL CHECK(status IN ('READY','COMPLETE','BLOCKED')),
                            processed INTEGER NOT NULL CHECK(processed>=0), created INTEGER NOT NULL CHECK(created>=0),
                            updated INTEGER NOT NULL CHECK(updated>=0), incomplete INTEGER NOT NULL CHECK(incomplete>=0),
                            exhausted INTEGER NOT NULL CHECK(exhausted IN (0,1)), warning TEXT,
                            started_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                            PRIMARY KEY(account_id,channel_id), UNIQUE(account_id,channel_id,run_id)
                        )
                        """);
                    statement.execute("""
                        CREATE TABLE channel_import_run_entries (
                            account_id TEXT NOT NULL, channel_id TEXT NOT NULL, run_id TEXT NOT NULL,
                            post_key TEXT NOT NULL REFERENCES resource_posts(post_key),
                            main_complete INTEGER NOT NULL CHECK(main_complete IN (0,1)),
                            PRIMARY KEY(account_id,channel_id,run_id,post_key),
                            FOREIGN KEY(account_id,channel_id,run_id)
                                REFERENCES channel_import_runs(account_id,channel_id,run_id) ON DELETE CASCADE
                        )
                        """);
                }
                if (oldVersion < 7) {
                    statement.execute("""
                        CREATE TABLE download_tasks (
                            task_id TEXT PRIMARY KEY NOT NULL, account_id TEXT NOT NULL, operation_id TEXT NOT NULL,
                            fingerprint TEXT NOT NULL, post_key TEXT NOT NULL, post_json TEXT NOT NULL,
                            target_root TEXT NOT NULL, relative_directory TEXT NOT NULL,
                            state TEXT NOT NULL CHECK(state IN ('QUEUED','RUNNING','PAUSED','FAILED','COMPLETED')),
                            pause_requested INTEGER NOT NULL DEFAULT 0, error_message TEXT,
                            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, UNIQUE(account_id,operation_id))
                        """);
                    statement.execute("""
                        CREATE TABLE download_items (
                            item_id TEXT PRIMARY KEY NOT NULL, task_id TEXT NOT NULL REFERENCES download_tasks(task_id),
                            ordinal INTEGER NOT NULL, media_key TEXT NOT NULL, spec_json TEXT NOT NULL,
                            unique_id TEXT NOT NULL, size INTEGER NOT NULL CHECK(size>0), relative_file TEXT NOT NULL,
                            owner_token TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('QUEUED','DOWNLOADING','WRITING','FINALIZING','COMPLETED')),
                            downloaded_bytes INTEGER NOT NULL DEFAULT 0, written_bytes INTEGER NOT NULL DEFAULT 0,
                            sha256 TEXT, error_message TEXT, UNIQUE(task_id,media_key), UNIQUE(task_id,ordinal))
                        """);
                    statement.execute("""
                        CREATE TABLE download_target_bindings (
                            account_id TEXT NOT NULL, root_key TEXT NOT NULL, post_key TEXT NOT NULL,
                            relative_directory TEXT NOT NULL, PRIMARY KEY(account_id,root_key,post_key),
                            UNIQUE(account_id,root_key,relative_directory))
                        """);
                    statement.execute("""
                        CREATE TABLE download_file_receipts (
                            account_id TEXT NOT NULL, path_key TEXT NOT NULL, unique_id TEXT NOT NULL,
                            size INTEGER NOT NULL, sha256 TEXT NOT NULL, PRIMARY KEY(account_id,path_key))
                        """);
                    statement.execute("""
                        CREATE TABLE download_cache_owned (
                            account_id TEXT NOT NULL, unique_id TEXT NOT NULL, file_id INTEGER NOT NULL,
                            PRIMARY KEY(account_id,unique_id))
                        """);
                    statement.execute("CREATE INDEX download_tasks_pending ON download_tasks(account_id,state,created_at)");
                }
                if (oldVersion < 8) {
                    statement.execute("CREATE TABLE upload_tasks(id TEXT PRIMARY KEY,account_id TEXT NOT NULL,operation_id TEXT NOT NULL,fingerprint TEXT NOT NULL,prepared_json TEXT NOT NULL,state TEXT NOT NULL,stop_requested INTEGER NOT NULL DEFAULT 0,error TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(account_id,operation_id))");
                    statement.execute("CREATE TABLE upload_groups(id INTEGER PRIMARY KEY AUTOINCREMENT,task_id TEXT NOT NULL REFERENCES upload_tasks(id),number INTEGER NOT NULL,media_type TEXT NOT NULL,mode TEXT NOT NULL,state TEXT NOT NULL,owner_run TEXT,started_at INTEGER,error TEXT,UNIQUE(task_id,number))");
                    statement.execute("CREATE TABLE upload_items(id TEXT PRIMARY KEY,task_id TEXT NOT NULL REFERENCES upload_tasks(id),group_id INTEGER NOT NULL REFERENCES upload_groups(id),ordinal INTEGER NOT NULL,file_json TEXT NOT NULL,caption TEXT NOT NULL,state TEXT NOT NULL,temporary_id TEXT,formal_id TEXT,remote_unique_id TEXT,staging_json TEXT,archive_relative TEXT,archive_phase TEXT NOT NULL DEFAULT 'NONE',archive_identity TEXT,error TEXT,UNIQUE(group_id,ordinal))");
                    statement.execute("CREATE TABLE upload_events(group_id INTEGER NOT NULL REFERENCES upload_groups(id),old_id TEXT NOT NULL,event_json TEXT NOT NULL,PRIMARY KEY(group_id,old_id))");
                    statement.execute("CREATE TABLE upload_claims(account_id TEXT NOT NULL,target_key TEXT NOT NULL,media_type TEXT NOT NULL,sha256 TEXT NOT NULL,size INTEGER NOT NULL,task_id TEXT NOT NULL REFERENCES upload_tasks(id),PRIMARY KEY(account_id,target_key,media_type,sha256,size))");
                    statement.execute("CREATE INDEX upload_tasks_pending ON upload_tasks(account_id,state,created_at)");
                    statement.execute("CREATE UNIQUE INDEX upload_formal_identity ON upload_items(task_id,formal_id) WHERE formal_id IS NOT NULL");
                }
                if (oldVersion < 9) {
                    statement.execute("""
                        CREATE TABLE download_history (
                            id TEXT PRIMARY KEY NOT NULL, account_id TEXT NOT NULL, unique_id TEXT NOT NULL,
                            size INTEGER NOT NULL CHECK(size>0), sha256 TEXT NOT NULL
                                CHECK(length(sha256)=64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
                            source_task_id TEXT NOT NULL, original_path TEXT NOT NULL, completed_at INTEGER NOT NULL,
                            UNIQUE(account_id,unique_id,size))
                        """);
                    statement.execute("ALTER TABLE download_items RENAME TO download_items_v8");
                    statement.execute("""
                        CREATE TABLE download_items (
                            item_id TEXT PRIMARY KEY NOT NULL, task_id TEXT NOT NULL REFERENCES download_tasks(task_id),
                            ordinal INTEGER NOT NULL, media_key TEXT NOT NULL, spec_json TEXT NOT NULL,
                            unique_id TEXT NOT NULL, size INTEGER NOT NULL CHECK(size>0), relative_file TEXT NOT NULL,
                            owner_token TEXT NOT NULL, state TEXT NOT NULL
                                CHECK(state IN ('QUEUED','DOWNLOADING','WRITING','FINALIZING','COMPLETED','SKIPPED_HISTORY')),
                            downloaded_bytes INTEGER NOT NULL DEFAULT 0, written_bytes INTEGER NOT NULL DEFAULT 0,
                            sha256 TEXT, error_message TEXT, history_id TEXT REFERENCES download_history(id),
                            UNIQUE(task_id,media_key), UNIQUE(task_id,ordinal),
                            CHECK((state='SKIPPED_HISTORY' AND history_id IS NOT NULL AND downloaded_bytes=0 AND written_bytes=0)
                               OR (state<>'SKIPPED_HISTORY' AND history_id IS NULL)))
                        """);
                    statement.execute("""
                        INSERT INTO download_items(rowid,item_id,task_id,ordinal,media_key,spec_json,unique_id,size,
                            relative_file,owner_token,state,downloaded_bytes,written_bytes,sha256,error_message)
                        SELECT rowid,item_id,task_id,ordinal,media_key,spec_json,unique_id,size,
                            relative_file,owner_token,state,downloaded_bytes,written_bytes,sha256,error_message
                        FROM download_items_v8 ORDER BY rowid
                        """);
                    statement.execute("DROP TABLE download_items_v8");
                    // Backfill only persisted, fully verified successes. No directory or file access.
                    try (var backfill = connection.prepareStatement("""
                        INSERT OR IGNORE INTO download_history
                        SELECT i.item_id,t.account_id,i.unique_id,i.size,lower(i.sha256),t.task_id,
                               t.target_root || ? || t.relative_directory || ? || i.relative_file,t.updated_at
                        FROM download_items i JOIN download_tasks t ON t.task_id=i.task_id
                        WHERE i.state='COMPLETED' AND i.written_bytes=i.size AND i.downloaded_bytes=i.size
                          AND length(i.sha256)=64 AND lower(i.sha256) NOT GLOB '*[^0-9a-f]*'
                          AND length(trim(i.unique_id))>0
                        ORDER BY t.updated_at,i.item_id
                        """)) {
                        backfill.setString(1, java.io.File.separator);backfill.setString(2, java.io.File.separator);backfill.executeUpdate();
                    }
                }
                if (oldVersion < 10) {
                    statement.execute("ALTER TABLE download_tasks ADD COLUMN duplicate_policy TEXT NOT NULL DEFAULT 'HISTORY_SKIP_V1' CHECK(duplicate_policy IN ('HISTORY_SKIP_V1','KEEP_DUPLICATES_V2'))");
                    statement.execute("""
                        CREATE TABLE download_completion_log (
                            item_id TEXT PRIMARY KEY NOT NULL,task_id TEXT NOT NULL,account_id TEXT NOT NULL,
                            source_key TEXT NOT NULL,source_chat_id TEXT,source_message_id TEXT,source_link TEXT,post_link TEXT,
                            unique_id TEXT NOT NULL,size INTEGER NOT NULL CHECK(size>0),
                            sha256 TEXT NOT NULL CHECK(length(sha256)=64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
                            output_path TEXT NOT NULL,completed_at INTEGER NOT NULL,
                            duplicate_of TEXT REFERENCES download_completion_log(item_id),reason TEXT NOT NULL,
                            manifest_path TEXT)
                        """);
                    statement.execute("CREATE INDEX download_log_source ON download_completion_log(account_id,source_key,unique_id,size,sha256)");
                    statement.execute("CREATE INDEX download_log_file ON download_completion_log(account_id,unique_id,size,sha256)");
                    statement.execute("CREATE INDEX download_log_content ON download_completion_log(account_id,size,sha256)");
                    statement.execute("""
                        CREATE TABLE download_output_plans (
                            item_id TEXT PRIMARY KEY NOT NULL REFERENCES download_items(item_id),relative_path TEXT NOT NULL,
                            sha256 TEXT NOT NULL CHECK(length(sha256)=64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
                            duplicate_of TEXT REFERENCES download_completion_log(item_id),reason TEXT NOT NULL,
                            manifest_relative TEXT,planned_at INTEGER NOT NULL,
                            CHECK((duplicate_of IS NULL AND manifest_relative IS NULL AND reason='NEW')
                               OR (duplicate_of IS NOT NULL AND manifest_relative IS NOT NULL AND reason IN ('SOURCE_MESSAGE','FILE_ID','CONTENT_SHA'))))
                        """);
                    try (var backfill=connection.prepareStatement("""
                        INSERT INTO download_completion_log
                        SELECT i.item_id,t.task_id,t.account_id,i.media_key,
                               json_extract(i.spec_json,'$.media.message.chatId'),json_extract(i.spec_json,'$.media.message.messageId'),
                               nullif(json_extract(i.spec_json,'$.media.message.link'),''),
                               (SELECT nullif(json_extract(value,'$.link'),'') FROM json_each(t.post_json,'$.mainMessages')
                                  WHERE length(json_extract(value,'$.link'))>0 LIMIT 1),
                               i.unique_id,i.size,lower(i.sha256),t.target_root || ? || t.relative_directory || ? || i.relative_file,
                               t.updated_at,NULL,'LEGACY_VERIFIED',NULL
                        FROM download_items i JOIN download_tasks t ON t.task_id=i.task_id
                        WHERE i.state='COMPLETED' AND i.downloaded_bytes=i.size AND i.written_bytes=i.size
                          AND length(i.sha256)=64 AND lower(i.sha256) NOT GLOB '*[^0-9a-f]*'
                        ORDER BY t.updated_at,i.item_id
                        """)) {backfill.setString(1,java.io.File.separator);backfill.setString(2,java.io.File.separator);backfill.executeUpdate();}
                }
                if(oldVersion<11){
                    statement.execute("""
                        CREATE TABLE auto_rules(id TEXT PRIMARY KEY NOT NULL,account_id TEXT NOT NULL,create_operation TEXT NOT NULL,
                            create_fingerprint TEXT NOT NULL,current_version INTEGER NOT NULL,revision INTEGER NOT NULL,
                            enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),next_scan_at INTEGER NOT NULL,
                            created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(account_id,create_operation))
                        """);
                    statement.execute("CREATE TABLE auto_rule_versions(rule_id TEXT NOT NULL REFERENCES auto_rules(id),version INTEGER NOT NULL,config_json TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(rule_id,version))");
                    statement.execute("CREATE TABLE auto_rule_observations(rule_id TEXT PRIMARY KEY NOT NULL REFERENCES auto_rules(id),version INTEGER NOT NULL,observation_json TEXT NOT NULL,FOREIGN KEY(rule_id,version) REFERENCES auto_rule_versions(rule_id,version))");
                    statement.execute("CREATE INDEX auto_rules_due ON auto_rules(account_id,enabled,next_scan_at)");
                }
                if(oldVersion<12){
                    statement.execute("CREATE TABLE upload_send_failures(group_id INTEGER NOT NULL REFERENCES upload_groups(id),old_id TEXT NOT NULL,failed_id TEXT NOT NULL,failure_json TEXT NOT NULL,PRIMARY KEY(group_id,old_id))");
                    statement.execute("CREATE TABLE upload_retry_attempts(id TEXT PRIMARY KEY NOT NULL,task_id TEXT NOT NULL REFERENCES upload_tasks(id),group_id INTEGER NOT NULL REFERENCES upload_groups(id),item_id TEXT NOT NULL REFERENCES upload_items(id),operation_id TEXT NOT NULL,check_id TEXT NOT NULL,prior_temporary_id TEXT NOT NULL,failed_id TEXT NOT NULL,new_temporary_id TEXT,owner_run TEXT,state TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,error TEXT,UNIQUE(task_id,operation_id,item_id))");
                    statement.execute("CREATE INDEX upload_retry_pending ON upload_retry_attempts(task_id,state,created_at)");
                }
                if(oldVersion<13){
                    statement.execute("CREATE TABLE auto_rule_authorizations(rule_id TEXT NOT NULL REFERENCES auto_rules(id),version INTEGER NOT NULL,revision INTEGER NOT NULL,target_json TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(rule_id,revision))");
                    statement.execute("CREATE TABLE auto_upload_bindings(task_id TEXT PRIMARY KEY NOT NULL REFERENCES upload_tasks(id),rule_id TEXT NOT NULL REFERENCES auto_rules(id),account_id TEXT NOT NULL,rule_version INTEGER NOT NULL,snapshot_json TEXT NOT NULL,pause_kind TEXT,manual_takeover_at INTEGER,settled_at INTEGER,created_at INTEGER NOT NULL)");
                    statement.execute("CREATE INDEX auto_upload_rule_history ON auto_upload_bindings(rule_id,created_at DESC)");
                    statement.execute("CREATE UNIQUE INDEX auto_upload_one_active ON auto_upload_bindings(rule_id) WHERE settled_at IS NULL AND manual_takeover_at IS NULL");
                }
                if(oldVersion<14){
                    statement.execute("CREATE TABLE download_batches(id TEXT PRIMARY KEY NOT NULL,account_id TEXT NOT NULL,operation_id TEXT NOT NULL,fingerprint TEXT NOT NULL,snapshot_json TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(account_id,operation_id))");
                    statement.execute("CREATE TABLE download_batch_tasks(batch_id TEXT NOT NULL REFERENCES download_batches(id),ordinal INTEGER NOT NULL,task_id TEXT NOT NULL UNIQUE REFERENCES download_tasks(task_id),PRIMARY KEY(batch_id,ordinal))");
                }
                if(oldVersion<15){
                    statement.execute("""
                        CREATE TABLE download_rules(id TEXT PRIMARY KEY NOT NULL,account_id TEXT NOT NULL,create_operation TEXT NOT NULL,
                            create_fingerprint TEXT NOT NULL,current_version INTEGER NOT NULL CHECK(current_version>0),revision INTEGER NOT NULL CHECK(revision>0),
                            enabled INTEGER NOT NULL CHECK(enabled IN(0,1)),next_scan_at INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,
                            UNIQUE(account_id,create_operation))
                        """);
                    statement.execute("CREATE INDEX download_rules_due ON download_rules(account_id,enabled,next_scan_at)");
                    statement.execute("CREATE TABLE download_rule_versions(rule_id TEXT NOT NULL REFERENCES download_rules(id),version INTEGER NOT NULL,config_json TEXT NOT NULL,channel_id TEXT NOT NULL,channel_title TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(rule_id,version))");
                    statement.execute("""
                        CREATE TABLE download_rule_scans(rule_id TEXT NOT NULL,version INTEGER NOT NULL,revision INTEGER NOT NULL,
                            phase TEXT NOT NULL CHECK(phase IN('HISTORY','HEAD')),cursor TEXT,pending_json TEXT,pending_offset INTEGER NOT NULL DEFAULT 0,
                            history_done INTEGER NOT NULL CHECK(history_done IN(0,1)),next_head_at INTEGER NOT NULL,scanned INTEGER NOT NULL CHECK(scanned>=0),
                            error TEXT,updated_at INTEGER NOT NULL,turn INTEGER NOT NULL DEFAULT 0 CHECK(turn>=0),PRIMARY KEY(rule_id,version),
                            FOREIGN KEY(rule_id,version) REFERENCES download_rule_versions(rule_id,version))
                        """);
                    statement.execute("""
                        CREATE TABLE download_rule_posts(rule_id TEXT NOT NULL,version INTEGER NOT NULL,post_key TEXT NOT NULL,message_id TEXT NOT NULL,
                            post_json TEXT,state TEXT NOT NULL CHECK(state IN('MAIN','COMMENTS','DONE','ERROR')),cursor TEXT,pages INTEGER NOT NULL CHECK(pages>=0),
                            matched INTEGER NOT NULL CHECK(matched IN(0,1)),next_review_at INTEGER NOT NULL,error TEXT,revision INTEGER NOT NULL CHECK(revision>=0),
                            PRIMARY KEY(rule_id,version,post_key),FOREIGN KEY(rule_id,version) REFERENCES download_rule_versions(rule_id,version))
                        """);
                    statement.execute("CREATE INDEX download_rule_posts_due ON download_rule_posts(rule_id,version,state,next_review_at,post_key)");
                    statement.execute("""
                        CREATE TABLE download_rule_tasks(task_id TEXT PRIMARY KEY NOT NULL REFERENCES download_tasks(task_id),rule_id TEXT NOT NULL REFERENCES download_rules(id),
                            account_id TEXT NOT NULL,rule_version INTEGER NOT NULL,snapshot_json TEXT NOT NULL,manual INTEGER NOT NULL CHECK(manual IN(0,1)),
                            held INTEGER NOT NULL CHECK(held IN(0,1)),settled_at INTEGER,created_at INTEGER NOT NULL,
                            FOREIGN KEY(rule_id,rule_version) REFERENCES download_rule_versions(rule_id,version))
                        """);
                    statement.execute("CREATE UNIQUE INDEX download_rule_one_active ON download_rule_tasks(rule_id) WHERE manual=0 AND settled_at IS NULL");
                    statement.execute("CREATE INDEX download_rule_task_history ON download_rule_tasks(rule_id,created_at DESC,task_id)");
                    statement.execute("""
                        CREATE TABLE download_rule_media(rule_id TEXT NOT NULL,version INTEGER NOT NULL,post_key TEXT NOT NULL,media_key TEXT NOT NULL,
                            unique_id TEXT NOT NULL,size INTEGER NOT NULL CHECK(size>0),task_id TEXT NOT NULL REFERENCES download_tasks(task_id),
                            item_id TEXT NOT NULL UNIQUE REFERENCES download_items(item_id),created_at INTEGER NOT NULL,
                            PRIMARY KEY(rule_id,version,post_key,media_key,unique_id,size),
                            FOREIGN KEY(rule_id,version,post_key) REFERENCES download_rule_posts(rule_id,version,post_key))
                        """);
                    statement.execute("CREATE INDEX download_rule_media_task ON download_rule_media(task_id,item_id)");
                }
                if (oldVersion < 16) allowDuplicatePlansWithoutManifests(sql);
                statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            }
            return null;
        });
    }

    private static void allowDuplicatePlansWithoutManifests(Connection sql) throws SQLException {
        var schemaObjects = new java.util.ArrayList<String>();
        try (var query = sql.prepareStatement("SELECT sql FROM sqlite_master WHERE tbl_name='download_output_plans' AND type IN ('index','trigger') AND sql IS NOT NULL ORDER BY type,name");
             var rows = query.executeQuery()) {
            while (rows.next()) schemaObjects.add(rows.getString(1));
        }
        try (var statement = sql.createStatement()) {
            statement.execute("""
                CREATE TABLE download_output_plans_v16 (
                    item_id TEXT PRIMARY KEY NOT NULL REFERENCES download_items(item_id),relative_path TEXT NOT NULL,
                    sha256 TEXT NOT NULL CHECK(length(sha256)=64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
                    duplicate_of TEXT REFERENCES download_completion_log(item_id),reason TEXT NOT NULL,
                    manifest_relative TEXT,planned_at INTEGER NOT NULL,
                    CHECK((duplicate_of IS NULL AND manifest_relative IS NULL AND reason='NEW')
                       OR (duplicate_of IS NOT NULL AND reason IN ('SOURCE_MESSAGE','FILE_ID','CONTENT_SHA'))))
                """);
            // Keep row identities as well as every frozen value; existing sidecars remain part of old plans.
            statement.execute("""
                INSERT INTO download_output_plans_v16(rowid,item_id,relative_path,sha256,duplicate_of,reason,manifest_relative,planned_at)
                SELECT rowid,item_id,relative_path,sha256,duplicate_of,reason,manifest_relative,planned_at FROM download_output_plans
                """);
            statement.execute("DROP TABLE download_output_plans");
            statement.execute("ALTER TABLE download_output_plans_v16 RENAME TO download_output_plans");
            for (String object : schemaObjects) statement.execute(object);
            try (var violations = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (violations.next()) throw new SQLException("下载输出计划迁移后外键核对失败，已回滚升级");
            }
        }
    }

    private int intPragma(String pragma) throws SQLException {
        return Integer.parseInt(scalar("PRAGMA " + pragma));
    }

    private String scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Database query returned no rows");
            return result.getString(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("本机数据库已关闭");
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try { connection.close(); }
        catch (SQLException failure) { throw new IllegalStateException("关闭本机数据库失败", failure); }
    }
}
