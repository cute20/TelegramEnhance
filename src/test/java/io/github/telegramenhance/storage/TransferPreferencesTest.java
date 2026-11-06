package io.github.telegramenhance.storage;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransferPreferencesTest {
    @TempDir Path root;
    @Test void savesDefaultsAcrossRestartWithoutCreatingOrMovingMedia() {
        try (var db = new Database(new AppPaths(root.resolve("app")))) {
            var preferences = new TransferPreferences(db);
            assertEquals(new TransferPreferences.View("", 1), preferences.view());
            var directory = root.resolve("future-downloads");
            var saved = preferences.save(new TransferPreferences.View(directory.toString(), 3));
            assertEquals(saved, new TransferPreferences(db).view());
            assertFalse(java.nio.file.Files.exists(directory));
            assertThrows(IllegalArgumentException.class, () -> preferences.save(new TransferPreferences.View("relative", 2)));
            assertThrows(IllegalArgumentException.class, () -> preferences.save(new TransferPreferences.View(directory.toString(), 5)));
            assertEquals(saved, preferences.view());
        }
    }
}
