package io.github.telegramenhance.storage;

import static io.github.telegramenhance.transfer.DownloadModels.HISTORY_SKIP;
import static io.github.telegramenhance.transfer.DownloadModels.KEEP_DUPLICATES;

import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.stereotype.Service;

/** Local download preferences, independent of Telegram credentials and connection state. */
@Service
public final class DownloadSettings {
    private static final String POLICY_KEY = "download.duplicate_policy";
    private final Database database;

    public DownloadSettings(Database database) { this.database = database; }

    public record Value(String duplicatePolicy) { }

    public Value get() { return database.read(connection -> new Value(readPolicy(connection))); }

    public Value save(Value value) {
        if (value == null || !supported(value.duplicatePolicy()))
            throw new IllegalArgumentException("重复媒体下载策略无效，请重新选择");
        return database.write(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO app_settings(setting_key,setting_value,updated_at) VALUES(?,?,?)
                    ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value,updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, POLICY_KEY);
                statement.setString(2, value.duplicatePolicy());
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return value;
        });
    }

    /** Reads the caller's connection so a new task can freeze its policy in its own SQL transaction. */
    public static String readPolicy(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT setting_value FROM app_settings WHERE setting_key=?")) {
            statement.setString(1, POLICY_KEY);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return KEEP_DUPLICATES;
                String policy = rows.getString(1);
                if (!supported(policy)) throw new IllegalStateException("已保存的重复媒体下载策略无效，请在设置中重新保存");
                return policy;
            }
        }
    }

    private static boolean supported(String policy) {
        return KEEP_DUPLICATES.equals(policy) || HISTORY_SKIP.equals(policy);
    }
}
