package io.github.telegramenhance.api;

import io.github.telegramenhance.transfer.DownloadBatchModels.*;
import io.github.telegramenhance.transfer.DownloadBatchService;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/transfers/download/batches")
public final class DownloadBatchController{
    private final DownloadBatchService service;
    public DownloadBatchController(DownloadBatchService service){this.service=service;}
    @GetMapping public DownloadBatchService.Listing list(@RequestParam(defaultValue="10") int limit,@RequestParam(defaultValue="0") int offset){return service.list(limit,offset);}
    @GetMapping("/{id}") public BatchStatus status(@PathVariable String id){return service.status(id);}
    @PostMapping("/{id}/pause") public BatchStatus pause(@PathVariable String id){return service.pause(id);}
    @PostMapping("/{id}/resume") public BatchStatus resume(@PathVariable String id){return service.resume(id);}
    @PostMapping("/preview") public View preview(@RequestBody Input input){return service.start(input);}
    @GetMapping("/preview/{id}") public View preview(@PathVariable String id){return service.get(id);}
    @PostMapping("/preview/{id}/next") public CompletableFuture<View> next(@PathVariable String id,@RequestBody Position position){return service.next(id,position);}
    @PostMapping public Receipt create(@RequestBody Create input){return service.create(input);}
    @GetMapping("/by-operation/{id}") public Lookup lookup(@PathVariable String id){return service.lookup(id);}
}
