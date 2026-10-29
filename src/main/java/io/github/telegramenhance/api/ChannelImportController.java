package io.github.telegramenhance.api;

import io.github.telegramenhance.library.ChannelImportModels.*;
import io.github.telegramenhance.library.ChannelImportService;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/library/import")
public final class ChannelImportController {
    private final ChannelImportService imports;
    public ChannelImportController(ChannelImportService imports) { this.imports = imports; }

    public record PreviewRequest(String channelId) { }
    public record PostRequest(String channelId, String messageId) { }

    @GetMapping("/channels") public CompletableFuture<ManagedChannels> channels() { return imports.channels(); }
    @PostMapping("/preview") public CompletableFuture<Preview> preview(@RequestBody PreviewRequest request) {
        if (request == null) throw new IllegalArgumentException("请选择要预览的频道");
        return imports.preview(request.channelId());
    }
    @PostMapping("/posts") public CompletableFuture<ImportResult> post(@RequestBody PostRequest request) {
        if (request == null) throw new IllegalArgumentException("请选择要导入的频道主帖");
        return imports.importPost(request.channelId(), request.messageId());
    }
}
