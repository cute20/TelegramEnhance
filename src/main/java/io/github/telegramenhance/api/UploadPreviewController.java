package io.github.telegramenhance.api;

import io.github.telegramenhance.transfer.UploadPreviewModels.*;
import io.github.telegramenhance.transfer.UploadPreviewService;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

@RestController @RequestMapping("/api/transfers/upload/preview")
public final class UploadPreviewController {
    private final UploadPreviewService previews;
    public UploadPreviewController(UploadPreviewService previews) { this.previews = previews; }
    @PostMapping public DeferredResult<View> preview(@RequestBody Input input) {
        return bounded(previews.preview(input),"上传预览结果尚未确认，请核对连接和目录后手动重新预览；不会自动重试、创建任务或发送文件");
    }
    @GetMapping("/{id}") public View get(@PathVariable String id) { return previews.get(id); }
    @PostMapping("/{id}/verify") public DeferredResult<View> verify(@PathVariable String id) {
        return bounded(previews.verify(id),"上传预览核对结果尚未确认，请读取当前预览后再手动核对；不会自动重试、创建任务或发送文件");
    }
    private static <T> DeferredResult<T> bounded(CompletableFuture<T> future,String message){
        var result=new DeferredResult<T>(135_000L);
        // Only the HTTP response expires. No cancellation, replay, upload task, or send is introduced here.
        result.onTimeout(()->result.setErrorResult(ResponseEntity.status(503).body(Map.of("message",message))));
        future.whenComplete((value,error)->{if(error!=null)result.setErrorResult(error);else result.setResult(value);});
        return result;
    }
}
