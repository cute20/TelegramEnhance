package io.github.telegramenhance.telegram;

import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.SettingsService;
import io.github.telegramenhance.storage.SettingsService.ProxyView;
import io.github.telegramenhance.storage.SettingsService.SettingsView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TelegramAutoConnectTest {
    @TempDir Path directory;
    private final SettingsService settings = mock(SettingsService.class);
    private final TelegramService telegram = mock(TelegramService.class);

    private TelegramAutoConnect fixture(boolean configured, boolean savedSession, boolean enabled) throws Exception {
        var paths = new AppPaths(directory);
        when(settings.view()).thenReturn(new SettingsView(configured ? 12345 : 0, configured,
            new ProxyView("DIRECT", "", 0, "", false)));
        if (savedSession) {
            Files.createDirectories(paths.telegram().resolve("database"));
            Files.writeString(paths.telegram().resolve("database/td.binlog"), "isolated session fixture");
        }
        when(telegram.connect()).thenReturn(CompletableFuture.completedFuture(null));
        return new TelegramAutoConnect(settings, telegram, paths, enabled);
    }

    @Test void existingSessionIsRestoredOnlyOncePerBackendLifetime() throws Exception {
        var restore = fixture(true, true, true);
        restore.restore(); restore.restore();
        verify(telegram, times(1)).connect();
    }

    @Test void firstUseDoesNotStartAnAuthorizationFlowWithoutSavedCredentials() throws Exception {
        fixture(false, false, true).restore();
        verifyNoInteractions(telegram);
    }

    @Test void savedCredentialsWithoutSessionStillRequireInitialUserAuthorization() throws Exception {
        fixture(true, false, true).restore();
        verifyNoInteractions(telegram);
    }

    @Test void isolatedServiceChecksCanDisableAutomaticNetworkAccess() throws Exception {
        fixture(true, true, false).restore();
        verifyNoInteractions(telegram);
    }

    @Test void asynchronousConnectionFailureIsNotAutomaticallyRetried() throws Exception {
        var restore = fixture(true, true, true);
        when(telegram.connect()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("fixture")));
        restore.restore(); restore.restore();
        verify(telegram, times(1)).connect();
    }

    @Test void synchronousShutdownRaceDoesNotBreakStartupOrScheduleRetries() throws Exception {
        var restore = fixture(true, true, true);
        when(telegram.connect()).thenThrow(new IllegalStateException("fixture"));
        restore.restore(); restore.restore();
        verify(telegram, times(1)).connect();
    }
}
