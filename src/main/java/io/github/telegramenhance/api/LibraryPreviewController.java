package io.github.telegramenhance.api;

import io.github.telegramenhance.library.LibraryPreviewService;
import io.github.telegramenhance.telegram.PostPreviewReader.Preview;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/library/posts")
public final class LibraryPreviewController {
    private final LibraryPreviewService previews;
    public LibraryPreviewController(LibraryPreviewService previews) { this.previews = previews; }

    @GetMapping("/{key}/preview")
    public CompletableFuture<Preview> preview(@PathVariable String key, @RequestParam(required=false) String version) {
        return previews.preview(key); // Client version is never trusted as the server cache identity.
    }

    @GetMapping("/{key}/media-preview")
    public CompletableFuture<Preview> mediaPreview(@PathVariable String key,
            @RequestParam(required=false) String mediaKey, @RequestParam(required=false) String version) {
        return previews.mediaPreview(key, mediaKey);
    }
}
