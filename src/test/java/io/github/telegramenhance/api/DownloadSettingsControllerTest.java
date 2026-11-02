package io.github.telegramenhance.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.storage.DownloadSettings;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DownloadSettingsControllerTest {
    private static final String ENDPOINT = "/api/settings/downloads";
    @TempDir Path directory;

    @Test void policyCanBeReadAndSavedWithoutConnectionOrCredentialSettings() throws Exception {
        try (var database = new Database(new AppPaths(directory))) {
            var settings = new DownloadSettings(database);
            var mvc = MockMvcBuilders.standaloneSetup(new DownloadSettingsController(settings)).setControllerAdvice(new ApiErrors()).build();
            mvc.perform(get(ENDPOINT)).andExpect(status().isOk()).andExpect(jsonPath("$.duplicatePolicy").value("KEEP_DUPLICATES_V2"));
            assertEquals(0, settingCount(database));
            for (String policy : List.of("HISTORY_SKIP_V1", "KEEP_DUPLICATES_V2")) {
                mvc.perform(put(ENDPOINT).contentType("application/json").content("{\"duplicatePolicy\":\"" + policy + "\"}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.duplicatePolicy").value(policy));
                mvc.perform(get(ENDPOINT)).andExpect(status().isOk()).andExpect(jsonPath("$.duplicatePolicy").value(policy));
                assertEquals(1, settingCount(database));
            }
        }
    }

    @Test void malformedMissingUnsupportedAndExtraPropertiesAreRejectedWithoutWriting() throws Exception {
        try (var database = new Database(new AppPaths(directory))) {
            var mvc = MockMvcBuilders.standaloneSetup(new DownloadSettingsController(new DownloadSettings(database))).setControllerAdvice(new ApiErrors()).build();
            for (String body : List.of("null", "{}", "[]", "{", "{\"duplicatePolicy\":null}", "{\"duplicatePolicy\":true}",
                    "{\"duplicatePolicy\":123}", "{\"duplicatePolicy\":\"UNKNOWN\"}", "{\"duplicatePolicy\":\"history_skip_v1\"}",
                    "{\"duplicatePolicy\":\" HISTORY_SKIP_V1\"}", "{\"duplicatePolicy\":\"KEEP_DUPLICATES_V2\",\"apiId\":123}")) {
                mvc.perform(put(ENDPOINT).contentType("application/json").content(body)).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.message").isString());
                assertEquals(0, settingCount(database));
            }
        }
    }

    private static int settingCount(Database database) {
        return database.read(connection -> {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM app_settings")) {
                rows.next(); return rows.getInt(1);
            }
        });
    }
}
