package io.github.telegramenhance.api;

import io.github.telegramenhance.transfer.BotDownloadService;
import io.github.telegramenhance.transfer.DownloadModels.TaskView;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

@RestController @RequestMapping("/api/transfers/download/bots")
public final class BotDownloadController {
    private static final long CREATE_TIMEOUT_MS = 135_000;
    private final BotDownloadService bots;
    public BotDownloadController(BotDownloadService bots){this.bots=bots;}
    @PostMapping("/preview") public CompletableFuture<BotDownloadService.View> open(@RequestBody BotDownloadService.Open input){return bots.open(input);}
    @GetMapping("/preview/{id}") public CompletableFuture<BotDownloadService.View> refresh(@PathVariable String id){return bots.refresh(id);}
    @PostMapping("/preview/{id}/older") public CompletableFuture<BotDownloadService.View> older(@PathVariable String id,@RequestBody BotDownloadService.Position input){return bots.older(id,input);}
    @PostMapping("/preview/{id}/control") public CompletableFuture<BotDownloadService.View> control(@PathVariable String id,@RequestBody BotDownloadService.Control input){return bots.control(id,input);}
    @PostMapping("/preview/{id}/press") public CompletableFuture<BotDownloadService.View> press(@PathVariable String id,@RequestBody BotDownloadService.Press input){return bots.press(id,input);}
    @PostMapping("/preview/{id}/wait") public CompletableFuture<BotDownloadService.View> waitForReply(@PathVariable String id,@RequestBody BotDownloadService.WaitRequest input){return bots.waitForReply(id,input);}
    @PostMapping("/tasks") public DeferredResult<TaskView> create(@RequestBody BotDownloadService.Create input){
        var future=bots.create(input);
        var result=new DeferredResult<TaskView>(CREATE_TIMEOUT_MS);
        // An HTTP deadline does not cancel the original operation or prove that no task was saved.
        result.onTimeout(()->result.setErrorResult(ResponseEntity.status(503).body(Map.of("message","创建结果尚未确认，请按原操作号核对，不要重复创建"))));
        future.whenComplete((task,error)->{if(error!=null)result.setErrorResult(error);else result.setResult(task);});
        return result;
    }
    @GetMapping("/tasks/by-operation/{id}") public BotDownloadService.Lookup lookup(@PathVariable String id){return bots.lookup(id);}
    @PostMapping("/preparations") public CompletableFuture<BotDownloadService.Preparation> prepare(@RequestBody BotDownloadService.Create input){return bots.prepare(input);}
    @GetMapping("/preparations/{id}") public ResponseEntity<BotDownloadService.Preparation> preparation(@PathVariable String id){return ResponseEntity.ofNullable(bots.preparation(id));}
}
