package io.github.telegramenhance.api;

import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.library.ResourceService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/library")
public class ResourceController {
    private final ResourceService resources;
    public ResourceController(ResourceService resources) { this.resources = resources; }
    public record ProbeInput(String link) { @Override public String toString() { return "ProbeInput[local link]"; } }
    public record CursorInput(String cursor) {}
    @PostMapping("/probe") public CompletableFuture<Detail> probe(@RequestBody ProbeInput input) { return resources.probe(input.link()); }
    @GetMapping("/posts") public List<Post> posts(@RequestParam(defaultValue="20") int limit, @RequestParam(defaultValue="0") int offset) { return resources.posts(limit, offset); }
    @GetMapping("/posts/{key}") public Detail detail(@PathVariable String key, @RequestParam(defaultValue="50") int limit, @RequestParam(defaultValue="0") int offset) { return resources.detail(key, limit, offset); }
    @PostMapping("/posts/{key}/refresh") public CompletableFuture<Detail> refresh(@PathVariable String key) { return resources.refresh(key); }
    @PostMapping("/posts/{key}/comments") public CompletableFuture<Page> comments(@PathVariable String key, @RequestBody CursorInput input) { return resources.comments(key, input.cursor()); }
}
