package io.github.telegramenhance.storage;

import static io.github.telegramenhance.transfer.DownloadModels.HISTORY_SKIP;
import static io.github.telegramenhance.transfer.DownloadModels.KEEP_DUPLICATES;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.security.SecretProtector;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadSettingsTest {
    private static final String KEY = "download.duplicate_policy";
    @TempDir Path directory;

    @Test void absentPolicyDefaultsToKeepingDuplicatesWithoutWritingOrRequiringCredentials() {
        try (var database = new Database(new AppPaths(directory))) {
            var settings = new DownloadSettings(database);
            var before = stored(database);
            assertEquals(new DownloadSettings.Value(KEEP_DUPLICATES), settings.get());
            assertEquals(KEEP_DUPLICATES, database.read(DownloadSettings::readPolicy));
            assertEquals(before, stored(database));
            assertTrue(stored(database).isEmpty());
        }
    }

    @Test void onlyTheDownloadPolicyChangesAndBothChoicesPersistAcrossReopening() {
        var paths = new AppPaths(directory);
        Map<String, Stored> unrelated;
        try (var database = new Database(paths)) {
            put(database, "telegram.api_hash.protected", "synthetic-opaque-credential");
            put(database, "proxy.password.protected", "synthetic-opaque-password");
            put(database, "telegram.tdlib_key.protected", "synthetic-opaque-session-key");
            put(database, "thumbnail.cache_directory", "D:/synthetic-cache");
            unrelated = stored(database);
            assertEquals(new DownloadSettings.Value(HISTORY_SKIP), new DownloadSettings(database).save(new DownloadSettings.Value(HISTORY_SKIP)));
            var after = new LinkedHashMap<>(stored(database));
            assertEquals(HISTORY_SKIP, after.remove(KEY).value());
            assertEquals(unrelated, after);
        }
        try (var reopened = new Database(paths)) {
            var settings = new DownloadSettings(reopened);
            assertEquals(HISTORY_SKIP, settings.get().duplicatePolicy());
            settings.save(new DownloadSettings.Value(KEEP_DUPLICATES));
        }
        try (var reopened = new Database(paths)) {
            assertEquals(KEEP_DUPLICATES, new DownloadSettings(reopened).get().duplicatePolicy());
            var after = new LinkedHashMap<>(stored(reopened)); after.remove(KEY);
            assertEquals(unrelated, after);
        }
    }

    @Test void invalidInputsCannotPartiallyChangeAnySetting() {
        try (var database = new Database(new AppPaths(directory))) {
            var settings = new DownloadSettings(database);
            settings.save(new DownloadSettings.Value(HISTORY_SKIP));
            var before = stored(database);
            assertThrows(IllegalArgumentException.class, () -> settings.save(null));
            for (String value : new String[] {null, "", "UNKNOWN", "history_skip_v1", " KEEP_DUPLICATES_V2", "HISTORY_SKIP_V1\n"}) {
                assertThrows(IllegalArgumentException.class, () -> settings.save(new DownloadSettings.Value(value)));
                assertEquals(before, stored(database));
            }
        }
    }

    @Test void corruptStoredPolicyFailsExplicitlyWithoutReplacingItAndCanBeRepairedBySaving() {
        try (var database = new Database(new AppPaths(directory))) {
            put(database, KEY, "INVALID_POLICY");
            var before = stored(database); var settings = new DownloadSettings(database);
            assertThrows(IllegalStateException.class, settings::get);
            assertThrows(IllegalStateException.class, () -> database.read(DownloadSettings::readPolicy));
            assertEquals(before, stored(database));
            settings.save(new DownloadSettings.Value(HISTORY_SKIP));
            assertEquals(HISTORY_SKIP, settings.get().duplicatePolicy());
        }
    }

    @Test void transactionHelperUsesTheCallersUncommittedStateWithoutCommittingOrWritingDefaults() {
        try (var database = new Database(new AppPaths(directory))) {
            assertThrows(IllegalArgumentException.class, () -> database.write(connection -> {
                assertFalse(connection.getAutoCommit());
                assertEquals(KEEP_DUPLICATES, DownloadSettings.readPolicy(connection));
                try (var statement = connection.prepareStatement("INSERT INTO app_settings VALUES(?,?,?)")) {
                    statement.setString(1, KEY); statement.setString(2, HISTORY_SKIP); statement.setLong(3, 7); statement.executeUpdate();
                }
                assertEquals(HISTORY_SKIP, DownloadSettings.readPolicy(connection));
                assertFalse(connection.getAutoCommit());
                throw new IllegalArgumentException("roll back the caller's transaction");
            }));
            assertTrue(stored(database).isEmpty());
            assertEquals(KEEP_DUPLICATES, new DownloadSettings(database).get().duplicatePolicy());
        }
    }

    @Test void aCredentialSaveCannotOverwriteANewerDownloadPolicyFromItsStaleLoadedSettingsMap() {
        try (var database = new Database(new AppPaths(directory))) {
            var downloads = new DownloadSettings(database);
            downloads.save(new DownloadSettings.Value(KEEP_DUPLICATES));
            put(database, "thumbnail.cache_directory", "D:/synthetic-cache");
            put(database, "unrelated.preference", "unchanged-value");
            var unrelated = stored(database);
            var protector = mock(SecretProtector.class);
            var savedPolicy = new java.util.concurrent.atomic.AtomicReference<Stored>();
            when(protector.protect(anyString())).thenAnswer(call -> {
                // SettingsService has already loaded KEEP; a separate preference is saved before it persists.
                downloads.save(new DownloadSettings.Value(HISTORY_SKIP));
                savedPolicy.set(stored(database).get(KEY));
                return "synthetic-opaque-protected-hash";
            });
            var credentials = new SettingsService(database, protector);
            credentials.save(new SettingsService.SettingsUpdate(12345, "0123456789abcdef0123456789abcdef",
                    new SettingsService.ProxyInput("DIRECT", "", 0, "", "")));
            assertEquals(HISTORY_SKIP, downloads.get().duplicatePolicy());
            assertEquals(savedPolicy.get(), stored(database).get(KEY));
            assertEquals(unrelated.get("thumbnail.cache_directory"), stored(database).get("thumbnail.cache_directory"));
            assertEquals(unrelated.get("unrelated.preference"), stored(database).get("unrelated.preference"));
            assertEquals("12345", stored(database).get("telegram.api_id").value());
            verify(protector).protect("0123456789abcdef0123456789abcdef");
        }
    }

    private record Stored(String value, long updatedAt) { }
    private static Map<String, Stored> stored(Database database) {
        return database.read(connection -> {
            var result = new LinkedHashMap<String, Stored>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT setting_key,setting_value,updated_at FROM app_settings ORDER BY setting_key")) {
                while (rows.next()) result.put(rows.getString(1), new Stored(rows.getString(2), rows.getLong(3)));
            }
            return result;
        });
    }
    private static void put(Database database, String key, String value) {
        database.write(connection -> {
            try (var statement = connection.prepareStatement("INSERT INTO app_settings VALUES(?,?,?)")) {
                statement.setString(1, key); statement.setString(2, value); statement.setLong(3, 123); statement.executeUpdate();
            }
            return null;
        });
    }
}
