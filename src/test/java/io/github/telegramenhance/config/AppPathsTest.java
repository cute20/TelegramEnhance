package io.github.telegramenhance.config;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppPathsTest {
    @Test void defaultsToDataUnderTheWorkingDirectory() {
        withProperty("workbench.data", null, () ->
            assertEquals(Path.of(System.getProperty("user.dir"), "data"), AppPaths.defaultRoot()));
    }

    @Test void explicitDataOverrideRemainsAvailableForIsolatedRuns() {
        withProperty("workbench.data", "isolated-test-data", () ->
            assertEquals(Path.of("isolated-test-data"), AppPaths.defaultRoot()));
    }

    @Test void blankOverrideUsesTheProjectDefault() {
        withProperty("workbench.data", " ", () ->
            assertEquals(Path.of(System.getProperty("user.dir"), "data"), AppPaths.defaultRoot()));
    }

    private static void withProperty(String name, String value, Runnable check) {
        String previous = System.getProperty(name);
        try {
            if (value == null) System.clearProperty(name); else System.setProperty(name, value);
            check.run();
        } finally {
            if (previous == null) System.clearProperty(name); else System.setProperty(name, previous);
        }
    }
}
