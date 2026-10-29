package io.github.telegramenhance.api;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.storage.SettingsService;
import io.github.telegramenhance.telegram.TelegramService;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class WorkbenchController {
    private final SettingsService settings;
    private final TelegramService telegram;
    private final Database database;
    private final AppPaths paths;
    private final ConfigurableApplicationContext context;
    public WorkbenchController(SettingsService settings, TelegramService telegram, Database database, AppPaths paths, ConfigurableApplicationContext context) {
        this.settings = settings; this.telegram = telegram; this.database = database; this.paths = paths; this.context = context;
    }
    @GetMapping("/bootstrap") public Map<String, Object> bootstrap() { return Map.of("stage", "M5", "settings", settings.view(), "telegram", telegram.status(), "diagnostics", diagnostics()); }
    @GetMapping("/settings") public SettingsService.SettingsView settings() { return settings.view(); }
    @PutMapping("/settings") public SettingsService.SettingsView save(@RequestBody SettingsService.SettingsUpdate update) {
        if (telegram.isActive()) throw new IllegalArgumentException("请先断开 Telegram，再修改连接设置");
        return settings.save(update);
    }
    @GetMapping("/telegram") public TelegramService.Status status() { return telegram.status(); }
    @PostMapping("/telegram/connect") public CompletableFuture<TelegramService.Status> connect() { return telegram.connect(); }
    public record AuthInput(String kind, String value) { @Override public String toString() { return "AuthInput[redacted]"; } }
    @PostMapping("/telegram/auth") public CompletableFuture<TelegramService.Status> auth(@RequestBody AuthInput input) { return telegram.authenticate(input.kind(), input.value()); }
    @PostMapping("/telegram/auth/resend") public CompletableFuture<TelegramService.Status> resend() { return telegram.resendAuthenticationCode(); }
    @PostMapping("/telegram/disconnect") public CompletableFuture<TelegramService.Status> disconnect() { return telegram.disconnect(false); }
    @PostMapping("/telegram/logout") public CompletableFuture<TelegramService.Status> logout() { return telegram.disconnect(true); }
    @PostMapping("/proxy/test") public CompletableFuture<TelegramService.ProxyResult> proxyTest() { return telegram.testProxy(); }
    @GetMapping("/diagnostics") public Map<String, Object> diagnostics() {
        return Map.of("stage", "M5", "javaVersion", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                "architecture", System.getProperty("os.arch"), "dataDirectory", paths.root().toString(), "database", database.diagnostics(), "dpapi", "Windows CurrentUser（API Hash、代理密码、TDLib 密钥）");
    }
    @PostMapping("/app/exit") public Map<String, String> exit() {
        Thread.ofPlatform().name("workbench-exit").start(() -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            context.close();
        });
        return Map.of("message", "后台正在保存并关闭 Telegram 客户端");
    }
}
