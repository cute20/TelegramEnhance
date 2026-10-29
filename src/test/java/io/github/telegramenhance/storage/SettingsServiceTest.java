package io.github.telegramenhance.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.security.SecretProtector;
import io.github.telegramenhance.storage.SettingsService.ProxyInput;
import io.github.telegramenhance.storage.SettingsService.SettingsUpdate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class SettingsServiceTest {
    // Artificial test values. Real Telegram credentials are never used by these tests.
    private static final String HASH = "0123456789abcdef0123456789abcdef";
    private static final String PASSWORD = "proxy-test-password-!91";
    @TempDir Path directory;

    @Test
    void defaultProxyCanBeSavedBeforeCredentialsExist() {
        try (Database database = new Database(new AppPaths(directory))) {
            SettingsService settings = new SettingsService(database, new SecretProtector());
            assertEquals(0, settings.view().apiId());
            assertEquals("DIRECT", settings.view().proxy().type());
            assertFalse(settings.view().apiHashStored());
            settings.save(new SettingsUpdate(0, "", socks("")));
            assertEquals("SOCKS5", settings.view().proxy().type());
            assertEquals("127.0.0.1", settings.view().proxy().host());
            assertEquals(1080, settings.view().proxy().port());
            assertEquals("", settings.credentials().apiHash());
        }
    }

    @Test
    void secretsAndRandomKeySurviveReopeningThroughRealWindowsDpapi() throws Exception {
        AppPaths paths = new AppPaths(directory);
        SecretProtector protector = new SecretProtector();
        byte[] originalKey;
        try (Database database = new Database(paths)) {
            SettingsService settings = new SettingsService(database, protector);
            var saved = settings.save(new SettingsUpdate(12345, HASH, socks(PASSWORD)));
            assertTrue(saved.apiHashStored());
            assertTrue(saved.proxy().passwordStored());
            originalKey = settings.tdlibKey();
            assertEquals(32, originalKey.length);
            assertArrayEquals(originalKey, settings.tdlibKey());
            Map<String, String> values = stored(database);
            assertEquals(HASH, protector.unprotect(values.get("telegram.api_hash.protected")));
            assertEquals(PASSWORD, protector.unprotect(values.get("proxy.password.protected")));
            assertArrayEquals(originalKey, Base64.getDecoder().decode(protector.unprotect(values.get("telegram.tdlib_key.protected"))));
            assertFalse(values.containsValue(HASH));
            assertFalse(values.containsValue(PASSWORD));
            assertFalse(values.containsValue(Base64.getEncoder().encodeToString(originalKey)));
        }
        try (Database reopened = new Database(paths)) {
            SettingsService settings = new SettingsService(reopened, new SecretProtector());
            assertEquals(12345, settings.credentials().apiId());
            assertEquals(HASH, settings.credentials().apiHash());
            assertEquals(PASSWORD, settings.credentials().proxy().password());
            assertArrayEquals(originalKey, settings.tdlibKey());
            settings.save(new SettingsUpdate(12345, "", socks("")));
            assertEquals(HASH, settings.credentials().apiHash());
            assertEquals(PASSWORD, settings.credentials().proxy().password());
            byte[] mutableReturnedKey = settings.tdlibKey();
            mutableReturnedKey[0] ^= 0x7f;
            assertArrayEquals(originalKey, settings.tdlibKey());
        }
        String rawDatabase = new String(Files.readAllBytes(paths.database()), StandardCharsets.ISO_8859_1);
        assertFalse(rawDatabase.contains(HASH));
        assertFalse(rawDatabase.contains(PASSWORD));
    }

    @Test
    void invalidInputCannotPartiallyChangeSettingsOrExposeSubmittedSecrets() {
        try (Database database = new Database(new AppPaths(directory))) {
            SettingsService settings = new SettingsService(database, new SecretProtector());
            settings.save(new SettingsUpdate(12345, HASH, socks(PASSWORD)));
            Map<String, String> before = stored(database);
            SettingsUpdate[] invalid = {
                new SettingsUpdate(-1, HASH, socks(PASSWORD)),
                new SettingsUpdate(0, "", socks(PASSWORD)),
                new SettingsUpdate(12345, "not-a-valid-hash", socks(PASSWORD)),
                new SettingsUpdate(12345, HASH, new ProxyInput("UNKNOWN", "localhost", 1080, "", PASSWORD)),
                new SettingsUpdate(12345, HASH, new ProxyInput("HTTP", "", 8080, "", PASSWORD)),
                new SettingsUpdate(12345, HASH, new ProxyInput("HTTP", "http://localhost", 8080, "", PASSWORD)),
                new SettingsUpdate(12345, HASH, new ProxyInput("HTTP", "local host", 8080, "", PASSWORD)),
                new SettingsUpdate(12345, HASH, new ProxyInput("SOCKS5", "localhost", 0, "", PASSWORD)),
                new SettingsUpdate(12345, HASH, new ProxyInput("SOCKS5", "localhost", 65536, "", PASSWORD)),
                new SettingsUpdate(12345, HASH, null)
            };
            for (SettingsUpdate update : invalid) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> settings.save(update));
                assertFalse(error.getMessage().contains(HASH));
                assertFalse(error.getMessage().contains(PASSWORD));
                assertFalse(error.getMessage().contains("not-a-valid-hash"));
                assertEquals(before, stored(database));
            }
        }
    }

    @Test
    void firstCredentialsRequireACompleteHashAndDirectAcceptsEmptyHostAndZeroPort() {
        try (Database database = new Database(new AppPaths(directory))) {
            SettingsService settings = new SettingsService(database, new SecretProtector());
            ProxyInput direct = new ProxyInput("DIRECT", "", 0, "", "");
            assertThrows(IllegalArgumentException.class, () -> settings.save(new SettingsUpdate(100, "", direct)));
            assertThrows(IllegalArgumentException.class, () -> settings.save(new SettingsUpdate(0, HASH, direct)));
            settings.save(new SettingsUpdate(0, "", direct));
            assertEquals("DIRECT", settings.view().proxy().type());
            assertEquals("", settings.view().proxy().host());
            assertEquals(0, settings.view().proxy().port());
            settings.save(new SettingsUpdate(100, HASH, direct));
            assertEquals(HASH, settings.credentials().apiHash());
        }
    }

    @Test
    void dpapiFailureLeavesAllSettingsUnchangedAndDoesNotFallBack() {
        try (Database database = new Database(new AppPaths(directory))) {
            SettingsService working = new SettingsService(database, new SecretProtector());
            working.save(new SettingsUpdate(12345, HASH, socks(PASSWORD)));
            Map<String, String> before = stored(database);
            SecretProtector brokenProtector = mock(SecretProtector.class);
            when(brokenProtector.protect(anyString())).thenThrow(new IllegalStateException("sensitive upstream failure " + HASH));
            SettingsService broken = new SettingsService(database, brokenProtector);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> broken.save(new SettingsUpdate(54321, HASH, socks("replacement"))));
            assertFalse(error.getMessage().contains(HASH));
            assertNull(error.getCause());
            assertEquals(before, stored(database));
            assertThrows(IllegalStateException.class, broken::tdlibKey);
            assertEquals(before, stored(database));
        }
    }

    @Test
    void corruptProtectedKeyFailsWithoutReplacingTheSessionKey() {
        try (Database database = new Database(new AppPaths(directory))) {
            SettingsService settings = new SettingsService(database, new SecretProtector());
            settings.tdlibKey();
            database.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE app_settings SET setting_value='corrupt-protected-value' WHERE setting_key='telegram.tdlib_key.protected'");
                }
                return null;
            });
            Map<String, String> before = stored(database);
            assertThrows(IllegalStateException.class, settings::tdlibKey);
            assertEquals(before, stored(database));
        }
    }

    @Test
    void secretBearingRecordsRedactTheirStringRepresentations() {
        var input = new SettingsUpdate(12345, HASH, socks(PASSWORD));
        var config = new SettingsService.ConnectionConfig(12345, HASH,
            new SettingsService.ProxyConfig("SOCKS5", "localhost", 1080, "test", PASSWORD));
        for (Object value : new Object[] {input, input.proxy(), config, config.proxy()}) {
            assertFalse(value.toString().contains(HASH));
            assertFalse(value.toString().contains(PASSWORD));
            assertTrue(value.toString().contains("REDACTED"));
        }
    }

    private ProxyInput socks(String password) { return new ProxyInput("SOCKS5", "127.0.0.1", 1080, "proxy-user", password); }

    private Map<String, String> stored(Database database) {
        return database.read(connection -> {
            Map<String, String> result = new java.util.HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT setting_key, setting_value FROM app_settings")) {
                while (rows.next()) result.put(rows.getString(1), rows.getString(2));
            }
            return result;
        });
    }
}
