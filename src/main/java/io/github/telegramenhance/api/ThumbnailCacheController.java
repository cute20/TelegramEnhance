package io.github.telegramenhance.api;

import io.github.telegramenhance.library.LibraryPreviewService;
import io.github.telegramenhance.library.ThumbnailCache;
import org.springframework.web.bind.annotation.*;

/** Independent of connection settings: cache controls never disconnect or change Telegram credentials. */
@RestController
@RequestMapping("/api/settings/thumbnail-cache")
public final class ThumbnailCacheController {
    private final ThumbnailCache cache;
    private final LibraryPreviewService previews;
    public ThumbnailCacheController(ThumbnailCache cache, LibraryPreviewService previews) { this.cache = cache; this.previews = previews; }
    @GetMapping public ThumbnailCache.View view() { return cache.view(); }
    @PutMapping public ThumbnailCache.View save(@RequestBody ThumbnailCache.Update update) {
        var result = cache.save(update); previews.clearMemory(); return result;
    }
    @PostMapping("/clear") public ThumbnailCache.Cleared clear() {
        try { return cache.clear(); }
        finally { previews.clearMemory(); }
    }
}
