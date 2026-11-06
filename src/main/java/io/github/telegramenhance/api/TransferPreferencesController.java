package io.github.telegramenhance.api;

import io.github.telegramenhance.storage.TransferPreferences;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/transfers")
public final class TransferPreferencesController {
    private final TransferPreferences preferences;
    public TransferPreferencesController(TransferPreferences preferences) { this.preferences = preferences; }
    @GetMapping public TransferPreferences.View view() { return preferences.view(); }
    @PutMapping public TransferPreferences.View save(@RequestBody TransferPreferences.View input) { return preferences.save(input); }
}
