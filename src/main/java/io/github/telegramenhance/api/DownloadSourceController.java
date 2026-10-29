package io.github.telegramenhance.api;

import io.github.telegramenhance.transfer.DownloadSourcePreview;
import io.github.telegramenhance.transfer.DownloadSourceService;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/transfers/download")
public final class DownloadSourceController {
    private final DownloadSourceService sources;
    public DownloadSourceController(DownloadSourceService sources) { this.sources = sources; }
    public record PreviewRequest(String link) {
        @Override public String toString() { return "PreviewRequest[redacted]"; }
    }
    public record NextRequest(String id, String cursor, Integer pageNumber) {
        @Override public String toString() { return "NextRequest[redacted]"; }
    }
    @PostMapping("/preview") public CompletableFuture<DownloadSourcePreview> preview(@RequestBody PreviewRequest request) {
        if (request == null) throw new IllegalArgumentException("请填写频道主帖链接");
        return sources.preview(request.link());
    }
    @PostMapping("/preview/next") public CompletableFuture<DownloadSourcePreview> next(@RequestBody NextRequest request) {
        if (request == null) throw new IllegalArgumentException("请先预览来源");
        return sources.next(request.id(), request.cursor(), request.pageNumber());
    }
}
