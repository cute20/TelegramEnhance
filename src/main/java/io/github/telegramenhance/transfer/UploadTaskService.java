package io.github.telegramenhance.transfer;

import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.telegram.UploadTransport;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.transfer.UploadTaskModels.View;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;

@Service
public final class UploadTaskService {
    private final UploadPreviewService previews;private final UploadRepository repository;private final TelegramService telegram;private final UploadEngine engine;private final UploadTransport transport;private final ResourceService reads;
    public UploadTaskService(UploadPreviewService previews,UploadRepository repository,TelegramService telegram,UploadEngine engine,UploadTransport transport,ResourceService reads){this.previews=previews;this.repository=repository;this.telegram=telegram;this.engine=engine;this.transport=transport;this.reads=reads;}
    public record Create(String operationId,String previewId){}
    public CompletableFuture<View> create(Create input){if(input==null)throw new IllegalArgumentException("上传创建参数无效");id(input.operationId());id(input.previewId());String account=telegram.accountId();long session=telegram.sessionGeneration();String fingerprint=DownloadPaths.hash(input.previewId());var previous=repository.byOperation(account,input.operationId());if(previous!=null){if(!previous.fingerprint().equals(fingerprint))throw new IllegalArgumentException("同一创建操作的预览发生变化");current(account);return CompletableFuture.completedFuture(repository.view(previous));}
        return previews.freeze(input.previewId()).thenApply(prepared->{if(!account.equals(telegram.accountId())||session!=telegram.sessionGeneration())throw new IllegalArgumentException("账号或会话已变化，未创建上传任务");var task=repository.create(account,input.operationId(),fingerprint,prepared);current(account);if(session!=telegram.sessionGeneration())throw new IllegalArgumentException("会话已变化，请先核对任务中心");return repository.view(task);});}
    public record Listing(List<View> items,String workerWarning){}public record Lookup(View task){}
    public Listing list(int limit,int offset){if(limit<1||limit>50||offset<0)throw new IllegalArgumentException("任务分页参数无效");String account=telegram.accountId();var out=repository.list(account,limit,offset).stream().map(repository::view).toList();current(account);return new Listing(out,engine.warning());}
    public View task(String value){id(value);String account=telegram.accountId();var out=repository.view(repository.task(account,value));current(account);return out;}
    public Lookup lookup(String value){id(value);String account=telegram.accountId();var task=repository.byOperation(account,value);current(account);return new Lookup(task==null?null:repository.view(task));}
    public View action(String value,String action){id(value);String account=telegram.accountId();var task=switch(action){case "start"->repository.start(account,value);case "pause"->repository.pause(account,value);case "reconcile"->repository.reconcile(account,value);case "cancel"->repository.cancel(account,value);case "takeover"->repository.takeover(account,value);default->throw new IllegalArgumentException("未知上传任务操作");};current(account);return repository.view(task);}
    public View acceptDocument(String value,String group){id(value);if(group==null||!group.matches("[1-9][0-9]{0,9}"))throw new IllegalArgumentException("上传组标识无效");String account=telegram.accountId();var task=repository.acceptDocument(account,value,Long.parseLong(group));current(account);return repository.view(task);}
    /** Read pending native messages on the existing bounded queue; never grant another send. */
    public CompletableFuture<View> recoverPending(String value){
        id(value);String account=telegram.accountId();long session=telegram.sessionGeneration();
        if(!repository.task(account,value).state().equals("NEEDS_REVIEW"))throw new IllegalArgumentException("当前任务无需核对待发送消息，请刷新任务");
        return reads.readWork(()->{
            current(account);if(session!=telegram.sessionGeneration())throw new IllegalArgumentException("会话已变化，请刷新任务");
            var task=repository.task(account,value);if(!task.state().equals("NEEDS_REVIEW"))throw new IllegalArgumentException("任务状态已变化，请刷新任务");
            transport.recoverPending(task,session);
            current(account);if(session!=telegram.sessionGeneration())throw new IllegalArgumentException("会话已变化，请刷新任务");
            return repository.view(repository.reconcile(account,value));
        });
    }
    private void current(String account){if(!account.equals(telegram.accountId()))throw new IllegalArgumentException("账号已变化，请刷新任务");}
    private static void id(String value){if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("上传任务标识无效");}
}
