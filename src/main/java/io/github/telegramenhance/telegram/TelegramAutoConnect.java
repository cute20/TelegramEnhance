package io.github.telegramenhance.telegram;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.SettingsService;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Restore an existing local session once per backend startup, never per page load. */
@Component
public final class TelegramAutoConnect {
    private static final Logger LOG = LoggerFactory.getLogger(TelegramAutoConnect.class);
    private final SettingsService settings;
    private final TelegramService telegram;
    private final AppPaths paths;
    private final boolean enabled;
    private final AtomicBoolean attempted = new AtomicBoolean();

    public TelegramAutoConnect(SettingsService settings, TelegramService telegram, AppPaths paths,
                               @Value("${workbench.auto-connect:true}") boolean enabled) {
        this.settings = settings; this.telegram = telegram; this.paths = paths; this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        if (!enabled || attempted.get()) return;
        var saved = settings.view();
        if (saved.apiId() <= 0 || !saved.apiHashStored()
            || !Files.isRegularFile(paths.telegram().resolve("database/td.binlog"), LinkOption.NOFOLLOW_LINKS)) return;
        if (!attempted.compareAndSet(false, true)) return;
        try {
            telegram.connect().exceptionally(cause -> {
                LOG.warn("Telegram 自动连接未完成，请在设置中检查连接。");
                return null;
            });
        } catch (RuntimeException cause) {
            LOG.warn("Telegram 自动连接未完成，请在设置中检查连接。");
        }
    }
}
