package io.github.telegramenhance.api;

import io.github.telegramenhance.transfer.DownloadTaskService;
import io.github.telegramenhance.transfer.DownloadModels.TaskView;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/transfers/download/tasks")
public final class DownloadTaskController {
    private final DownloadTaskService tasks;
    public DownloadTaskController(DownloadTaskService tasks) { this.tasks = tasks; }
    @PostMapping public CompletableFuture<TaskView> create(@RequestBody DownloadTaskService.Create input) { return tasks.create(input); }
    @GetMapping public DownloadTaskService.Listing list(@RequestParam(defaultValue="20") int limit, @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="false") boolean standalone) { return standalone?tasks.standalone(limit,offset):tasks.list(limit, offset); }
    @GetMapping("/by-operation/{operationId}") public DownloadTaskService.Lookup lookup(@PathVariable String operationId) { return tasks.lookup(operationId); }
    @GetMapping("/{id}") public TaskView task(@PathVariable String id) { return tasks.task(id); }
    @PostMapping("/{id}/pause") public TaskView pause(@PathVariable String id) { return tasks.pause(id); }
    @PostMapping("/{id}/resume") public TaskView resume(@PathVariable String id) { return tasks.resume(id); }
    @PostMapping("/repair-source-links") public CompletableFuture<DownloadTaskService.RepairedLinks> repairSourceLinks(){return tasks.repairSourceLinks();}
}
