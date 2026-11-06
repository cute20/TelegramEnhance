package io.github.telegramenhance.api;

import io.github.telegramenhance.desktop.WorkbenchTray;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/desktop")
public final class DesktopSettingsController {
    private final WorkbenchTray tray;
    public DesktopSettingsController(WorkbenchTray tray) { this.tray = tray; }
    @GetMapping public WorkbenchTray.View view() { return tray.view(); }
    @PutMapping public WorkbenchTray.View save(@RequestBody WorkbenchTray.Update input) { return tray.save(input); }
}
