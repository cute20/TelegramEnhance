package io.github.telegramenhance.storage;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Defaults are applied only to new forms; existing tasks keep their frozen paths. */
@Service
public final class TransferPreferences {
    private static final String DIRECTORY = "transfers.default_download_directory";
    private static final String CONCURRENCY = "transfers.download_concurrency";
    private final Database database;
    public TransferPreferences(Database database) { this.database = database; }
    public record View(String defaultDownloadDirectory, int downloadConcurrency) { }
    public View view() {
        return database.read(c -> {
            String directory = ""; int concurrency = 1;
            try (var q = c.prepareStatement("SELECT setting_key,setting_value FROM app_settings WHERE setting_key IN (?,?)")) {
                q.setString(1, DIRECTORY); q.setString(2, CONCURRENCY);
                try (var rows = q.executeQuery()) { while (rows.next()) {
                    if (rows.getString(1).equals(DIRECTORY)) directory = rows.getString(2);
                    else try { concurrency = Integer.parseInt(rows.getString(2)); }
                    catch (NumberFormatException corrupt) { throw new IllegalStateException("已保存的下载并发数无效，请重新保存传输偏好"); }
                } }
            }
            if (concurrency < 1 || concurrency > 4) throw new IllegalStateException("已保存的下载并发数无效，请重新保存传输偏好");
            return new View(directory, concurrency);
        });
    }
    public View save(View input) {
        if (input == null || input.downloadConcurrency() < 1 || input.downloadConcurrency() > 4)
            throw new IllegalArgumentException("同时下载文件数必须在 1 到 4 之间");
        String directory = input.defaultDownloadDirectory() == null ? "" : input.defaultDownloadDirectory().strip();
        if (!directory.isEmpty()) {
            try {
                if (directory.length() > 2048 || directory.chars().anyMatch(Character::isISOControl) || !Path.of(directory).isAbsolute())
                    throw new IllegalArgumentException("默认下载目录必须是本机文件夹的完整路径，也可留空");
                directory = Path.of(directory).normalize().toString();
            } catch (InvalidPathException invalid) { throw new IllegalArgumentException("默认下载目录格式无效"); }
        }
        var result = new View(directory, input.downloadConcurrency());
        return database.write(c -> {
            for (var entry : Map.of(DIRECTORY, result.defaultDownloadDirectory(), CONCURRENCY, Integer.toString(result.downloadConcurrency())).entrySet()) {
                try (var q = c.prepareStatement("INSERT INTO app_settings(setting_key,setting_value,updated_at) VALUES(?,?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value,updated_at=excluded.updated_at")) {
                    q.setString(1, entry.getKey()); q.setString(2, entry.getValue()); q.setLong(3, System.currentTimeMillis()); q.executeUpdate();
                }
            }
            return result;
        });
    }
}
