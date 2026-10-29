package io.github.telegramenhance.api;

import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import io.github.telegramenhance.library.ChannelImportService;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/library/import")
public final class ChannelHistoryController {
    private final ChannelImportService imports;
    public ChannelHistoryController(ChannelImportService imports) { this.imports = imports; }
    public record HistoryRequest(String channelId, String cursor) {
        @Override public String toString() { return "HistoryRequest[local history page]"; }
    }
    @PostMapping("/history") public CompletableFuture<HistoryPage> history(@RequestBody HistoryRequest request) {
        if (request == null) throw new IllegalArgumentException("请选择要浏览的频道");
        return imports.historyPage(request.channelId(), request.cursor());
    }
}
