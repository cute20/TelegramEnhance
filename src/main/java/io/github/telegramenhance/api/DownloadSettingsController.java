package io.github.telegramenhance.api;

import io.github.telegramenhance.storage.DownloadSettings;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/downloads")
public final class DownloadSettingsController {
    private final DownloadSettings settings;

    public DownloadSettingsController(DownloadSettings settings) { this.settings = settings; }

    @GetMapping public DownloadSettings.Value get() { return settings.get(); }

    @PutMapping public DownloadSettings.Value save(@RequestBody Map<String, Object> input) {
        if (input == null || input.size() != 1 || !(input.get("duplicatePolicy") instanceof String policy))
            throw new IllegalArgumentException("请仅提交有效的重复媒体下载策略");
        return settings.save(new DownloadSettings.Value(policy));
    }
}
