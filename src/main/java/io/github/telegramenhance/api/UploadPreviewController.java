package io.github.telegramenhance.api;

import io.github.telegramenhance.transfer.UploadPreviewModels.*;
import io.github.telegramenhance.transfer.UploadPreviewService;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/transfers/upload/preview")
public final class UploadPreviewController {
    private final UploadPreviewService previews;
    public UploadPreviewController(UploadPreviewService previews) { this.previews = previews; }
    @PostMapping public CompletableFuture<View> preview(@RequestBody Input input) { return previews.preview(input); }
    @GetMapping("/{id}") public View get(@PathVariable String id) { return previews.get(id); }
    @PostMapping("/{id}/verify") public CompletableFuture<View> verify(@PathVariable String id) { return previews.verify(id); }
}
