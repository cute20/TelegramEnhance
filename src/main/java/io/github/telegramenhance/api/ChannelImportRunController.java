package io.github.telegramenhance.api;

import io.github.telegramenhance.library.ChannelImportRunModels.RunView;
import io.github.telegramenhance.library.ChannelImportRunService;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/library/import/all")
public final class ChannelImportRunController {
    private final ChannelImportRunService imports;
    public ChannelImportRunController(ChannelImportRunService imports) { this.imports = imports; }
    public record StartRequest(String channelId, Boolean restart, String runId, Long revision) { }
    public record StepRequest(String channelId, String runId, Long revision) { }
    @GetMapping("/state") public ResponseEntity<?> state(@RequestParam(required=false) String channelId) {
        RunView state = imports.state(channelId);
        return state == null ? ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("null") : ResponseEntity.ok(state);
    }
    @PostMapping("/start") public CompletableFuture<RunView> start(@RequestBody StartRequest request) {
        if (request == null) throw new IllegalArgumentException("请选择要导入的频道");
        return imports.start(request.channelId(), Boolean.TRUE.equals(request.restart()), request.runId(), request.revision());
    }
    @PostMapping("/step") public CompletableFuture<RunView> step(@RequestBody StepRequest request) {
        if (request == null) throw new IllegalArgumentException("频道导入检查点参数无效");
        return imports.step(request.channelId(), request.runId(), request.revision());
    }
}
