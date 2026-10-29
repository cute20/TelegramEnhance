package io.github.telegramenhance.api;

import io.github.telegramenhance.library.LibraryPreviewService;
import io.github.telegramenhance.telegram.PostPreviewReader.Preview;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/transfers/download/preview")
public final class DownloadThumbnailController {
    private final LibraryPreviewService previews;
    public DownloadThumbnailController(LibraryPreviewService previews) { this.previews = previews; }
    @GetMapping("/{id}/thumbnail") public CompletableFuture<Preview> thumbnail(@PathVariable String id,
            @RequestParam(required=false) Integer pageNumber, @RequestParam(required=false) String mediaKey) {
        return previews.downloadMediaPreview(id, pageNumber, mediaKey);
    }
}
