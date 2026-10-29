package io.github.telegramenhance.api;

import io.github.telegramenhance.library.LibraryCatalogService;
import io.github.telegramenhance.library.LibraryModels.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/library")
public final class LibraryController {
    private final LibraryCatalogService library;
    public LibraryController(LibraryCatalogService library) { this.library = library; }
    @GetMapping("/channels") public List<Channel> channels() { return library.channels(); }
    @GetMapping("/catalog") public CatalogPage catalog(
            @RequestParam(required=false) List<String> channelIds,
            @RequestParam(defaultValue="") String tags, @RequestParam(defaultValue="") String keywords,
            @RequestParam(defaultValue="20") int limit, @RequestParam(defaultValue="0") int offset) {
        return library.catalog(channelIds, tags, keywords, limit, offset);
    }
    @GetMapping("/posts/{key}/media") public MediaPage media(@PathVariable String key,
            @RequestParam(required=false) List<String> types, @RequestParam(defaultValue="true") boolean includeMain,
            @RequestParam(defaultValue="50") int limit, @RequestParam(defaultValue="0") int offset) {
        return library.media(key, types, includeMain, limit, offset);
    }
}
