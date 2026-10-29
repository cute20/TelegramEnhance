package io.github.telegramenhance.api;

import io.github.telegramenhance.transfer.UploadTaskService;
import io.github.telegramenhance.transfer.UploadTaskModels.View;
import io.github.telegramenhance.transfer.UploadRetryService;
import io.github.telegramenhance.transfer.UploadRetryModels;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/transfers/upload/tasks")
public final class UploadTaskController {
    private final UploadTaskService tasks;private final UploadRetryService retries;
    public UploadTaskController(UploadTaskService tasks,UploadRetryService retries){this.tasks=tasks;this.retries=retries;}
    @PostMapping("/{id}/retry-check")public CompletableFuture<UploadRetryModels.Check> retryCheck(@PathVariable String id){return retries.check(id);}
    @PostMapping("/{id}/retry-failed")public View retryFailed(@PathVariable String id,@RequestBody UploadRetryModels.Retry input){return retries.retry(id,input);}
    @GetMapping("/{id}/retry-operations/{operation}")public UploadTaskService.Lookup retryOperation(@PathVariable String id,@PathVariable String operation){return retries.lookup(id,operation);}
    @PostMapping public CompletableFuture<View>create(@RequestBody UploadTaskService.Create input){return tasks.create(input);}
    @GetMapping public UploadTaskService.Listing list(@RequestParam(defaultValue="20")int limit,@RequestParam(defaultValue="0")int offset){return tasks.list(limit,offset);}
    @GetMapping("/by-operation/{operation}")public UploadTaskService.Lookup lookup(@PathVariable String operation){return tasks.lookup(operation);}
    @GetMapping("/{id}")public View task(@PathVariable String id){return tasks.task(id);}
    @PostMapping("/{id}/{action}")public View action(@PathVariable String id,@PathVariable String action){return tasks.action(id,action);}
    @PostMapping("/{id}/recover-pending")public CompletableFuture<View> recoverPending(@PathVariable String id){return tasks.recoverPending(id);}
    @PostMapping("/{id}/groups/{groupId}/accept-document")public View acceptDocument(@PathVariable String id,@PathVariable String groupId){return tasks.acceptDocument(id,groupId);}
}
