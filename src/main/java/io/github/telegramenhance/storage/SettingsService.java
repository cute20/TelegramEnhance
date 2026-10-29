package io.github.telegramenhance.storage;

import io.github.telegramenhance.security.SecretProtector;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public final class SettingsService {
    private static final String API_ID = "telegram.api_id";
    private static final String API_HASH = "telegram.api_hash.protected";
    private static final String PROXY_TYPE = "proxy.type";
    private static final String PROXY_HOST = "proxy.host";
    private static final String PROXY_PORT = "proxy.port";
    private static final String PROXY_USERNAME = "proxy.username";
    private static final String PROXY_PASSWORD = "proxy.password.protected";
    private static final String TDLIB_KEY = "telegram.tdlib_key.protected";
    private final Database database;
    private final SecretProtector protector;
    private final SecureRandom random = new SecureRandom();

    public SettingsService(Database database, SecretProtector protector) {
        this.database = database;
        this.protector = protector;
    }

    public record SettingsUpdate(int apiId, String apiHash, ProxyInput proxy) {
        @Override public String toString() {
            return "SettingsUpdate[apiId=" + apiId + ", apiHash=[REDACTED], proxy=" + proxy + "]";
        }
    }

    public record ProxyInput(String type, String host, int port, String username, String password) {
        @Override public String toString() {
            return "ProxyInput[type=" + type + ", host=" + host + ", port=" + port
                + ", username=" + username + ", password=[REDACTED]]";
        }
    }

    public record SettingsView(int apiId, boolean apiHashStored, ProxyView proxy) { }
    public record ProxyView(String type, String host, int port, String username, boolean passwordStored) { }

    public record ConnectionConfig(int apiId, String apiHash, ProxyConfig proxy) {
        @Override public String toString() {
            return "ConnectionConfig[apiId=" + apiId + ", apiHash=[REDACTED], proxy=" + proxy + "]";
        }
    }

    public record ProxyConfig(String type, String host, int port, String username, String password) {
        @Override public String toString() {
            return "ProxyConfig[type=" + type + ", host=" + host + ", port=" + port
                + ", username=" + username + ", password=[REDACTED]]";
        }
    }

    public synchronized SettingsView view() { return asView(load()); }

    public synchronized SettingsView save(SettingsUpdate update) {
        if (update == null) throw invalid("缺少设置内容");
        if (update.apiId() < 0) throw invalid("API ID 必须为正整数");
        ProxyInput proxy = validateProxy(update.proxy());
        Map<String, String> values = load();
        int savedId = integer(values, API_ID, 0);
        String apiHash = update.apiHash() == null ? "" : update.apiHash().strip();
        if (!apiHash.isEmpty() && !validHash(apiHash)) throw invalid("API Hash 必须是完整的 32 位十六进制字符串");
        if (update.apiId() == 0 && (savedId > 0 || !apiHash.isEmpty())) throw invalid("保存应用凭据需要有效的 API ID");
        if (update.apiId() > 0 && apiHash.isEmpty() && !has(values, API_HASH)) throw invalid("首次保存应用凭据需要完整的 API Hash");
        if (update.apiId() > 0 && apiHash.isEmpty() && !validHash(unprotect(values.get(API_HASH)))) {
            throw new IllegalStateException("已存储的应用凭据无效，请重新填写 API Hash");
        }
        // Complete validation and DPAPI protection before opening the short write transaction.
        if (!apiHash.isEmpty()) values.put(API_HASH, protect(apiHash));
        if (proxy.password() != null && !proxy.password().isEmpty()) {
            values.put(PROXY_PASSWORD, protect(proxy.password()));
        }
        values.put(API_ID, Integer.toString(update.apiId()));
        values.put(PROXY_TYPE, proxy.type());
        values.put(PROXY_HOST, proxy.host());
        values.put(PROXY_PORT, Integer.toString(proxy.port()));
        values.put(PROXY_USERNAME, proxy.username());
        persist(values);
        return asView(values);
    }

    public synchronized ConnectionConfig credentials() {
        Map<String, String> values = load();
        int apiId = integer(values, API_ID, 0);
        String apiHash = has(values, API_HASH) ? unprotect(values.get(API_HASH)) : "";
        if ((apiId > 0 && !validHash(apiHash)) || (apiId == 0 && !apiHash.isEmpty())) {
            throw new IllegalStateException("已存储的应用凭据无效，请重新填写应用凭据");
        }
        String password = has(values, PROXY_PASSWORD) ? unprotect(values.get(PROXY_PASSWORD)) : "";
        ProxyView proxy = asView(values).proxy();
        return new ConnectionConfig(apiId, apiHash,
            new ProxyConfig(proxy.type(), proxy.host(), proxy.port(), proxy.username(), password));
    }

    public synchronized byte[] tdlibKey() {
        Map<String, String> values = load();
        if (has(values, TDLIB_KEY)) {
            try {
                byte[] key = Base64.getDecoder().decode(unprotect(values.get(TDLIB_KEY)));
                if (key.length != 32) throw new IllegalStateException("本机 Telegram 密钥长度无效");
                return key;
            } catch (IllegalArgumentException failure) {
                throw new IllegalStateException("本机 Telegram 密钥无效；未生成替代密钥");
            }
        }
        byte[] key = new byte[32];
        random.nextBytes(key);
        values.put(TDLIB_KEY, protect(Base64.getEncoder().encodeToString(key)));
        persist(values);
        return key;
    }

    private ProxyInput validateProxy(ProxyInput proxy) {
        if (proxy == null) throw invalid("缺少代理设置");
        String type = proxy.type() == null ? "" : proxy.type().strip().toUpperCase(Locale.ROOT);
        if (!type.equals("DIRECT") && !type.equals("SOCKS5") && !type.equals("HTTP")) {
            throw invalid("代理类型必须为 DIRECT、SOCKS5 或 HTTP");
        }
        String username = proxy.username() == null ? "" : proxy.username();
        if (username.length() > 256 || containsControl(username)) throw invalid("代理用户名格式无效");
        if (proxy.password() != null && (proxy.password().length() > 4096 || containsControl(proxy.password()))) {
            throw invalid("代理密码格式无效");
        }
        if (type.equals("DIRECT")) return new ProxyInput(type, "", 0, "", proxy.password());
        String host = proxy.host() == null ? "" : proxy.host().strip();
        if (host.isEmpty() || host.length() > 253 || host.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                || host.chars().anyMatch(c -> "/\\?#@".indexOf(c) >= 0)) {
            throw invalid("代理主机格式无效，请只填写主机名或 IP 地址");
        }
        if (proxy.port() < 1 || proxy.port() > 65535) throw invalid("代理端口必须在 1 至 65535 之间");
        return new ProxyInput(type, host, proxy.port(), username, proxy.password());
    }

    private Map<String, String> load() {
        return database.read(connection -> {
            Map<String, String> values = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT setting_key, setting_value FROM app_settings");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) values.put(rows.getString(1), rows.getString(2));
            }
            return values;
        });
    }

    private void persist(Map<String, String> values) {
        database.write(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app_settings(setting_key, setting_value, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value, updated_at=excluded.updated_at
                """)) {
                long now = Instant.now().toEpochMilli();
                for (Map.Entry<String, String> value : values.entrySet()) {
                    statement.setString(1, value.getKey());
                    statement.setString(2, value.getValue());
                    statement.setLong(3, now);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    private SettingsView asView(Map<String, String> values) {
        return new SettingsView(integer(values, API_ID, 0), has(values, API_HASH), new ProxyView(
            values.getOrDefault(PROXY_TYPE, "DIRECT"), values.getOrDefault(PROXY_HOST, ""),
            integer(values, PROXY_PORT, 0), values.getOrDefault(PROXY_USERNAME, ""), has(values, PROXY_PASSWORD)));
    }

    private String protect(String secret) {
        try { return protector.protect(secret); }
        catch (RuntimeException failure) { throw new IllegalStateException("Windows 当前用户无法保护秘密，设置未保存"); }
    }

    private String unprotect(String ciphertext) {
        try { return protector.unprotect(ciphertext); }
        catch (RuntimeException failure) { throw new IllegalStateException("Windows 当前用户无法解密已存储的秘密，请检查用户和本机数据"); }
    }

    private static boolean has(Map<String, String> values, String key) {
        return values.containsKey(key) && !values.get(key).isEmpty();
    }

    private static int integer(Map<String, String> values, String key, int fallback) {
        try { return Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback))); }
        catch (NumberFormatException failure) { throw new IllegalStateException("本机设置中的数字格式无效"); }
    }

    private static boolean validHash(String value) { return value != null && value.matches("[0-9a-fA-F]{32}"); }
    private static boolean containsControl(String value) { return value.chars().anyMatch(Character::isISOControl); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
