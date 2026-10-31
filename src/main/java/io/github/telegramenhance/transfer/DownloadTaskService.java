package io.github.telegramenhance.transfer;

import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.downloadrules.DownloadRulePolicy;
import io.github.telegramenhance.telegram.DownloadFileReader;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;

@Service
public final class DownloadTaskService {
    private final DownloadSourceService sources; private final ResourceService work; private final DownloadFileReader files;
    private final DownloadRepository repository; private final DownloadPaths paths; private final TelegramService telegram;
    private final DownloadEngine engine;
    private final DownloadRulePolicy rules;
    public DownloadTaskService(DownloadSourceService sources, ResourceService work, DownloadFileReader files,
            DownloadRepository repository, DownloadPaths paths, TelegramService telegram, DownloadEngine engine) {
        this(sources, work, files, repository, paths, telegram, engine, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DownloadTaskService(DownloadSourceService sources, ResourceService work, DownloadFileReader files,
            DownloadRepository repository, DownloadPaths paths, TelegramService telegram, DownloadEngine engine, DownloadRulePolicy rules) {
        this.sources = sources; this.work = work; this.files = files; this.repository = repository; this.paths = paths; this.telegram = telegram; this.engine = engine;
        this.rules = rules;
    }
    public record Create(String operationId, String sourceId, List<String> mediaKeys, Boolean includeMain, String targetDirectory) {
        @Override public String toString() { return "CreateDownloadTask[redacted]"; }
    }
    public CompletableFuture<TaskView> create(Create input) {
        if (input == null || !uuid(input.operationId()) || !uuid(input.sourceId()) || input.mediaKeys() == null
                || input.mediaKeys().isEmpty() || input.mediaKeys().size() > 100 || input.mediaKeys().stream().anyMatch(k -> k == null || k.isBlank() || k.length() > 512)
                || new java.util.HashSet<>(input.mediaKeys()).size() != input.mediaKeys().size()) throw new IllegalArgumentException("请选择1至100个已加载媒体并填写保存目录");
        String account = telegram.accountId(); long generation = telegram.sessionGeneration();
        var root = paths.root(input.targetDirectory());
        String fingerprint = DownloadPaths.hash(input.sourceId() + "\n" + String.join("\n", input.mediaKeys()) + "\n" + Boolean.TRUE.equals(input.includeMain()) + "\n" + DownloadPaths.key(root));
        Task existing = repository.byOperation(account, input.operationId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) throw new IllegalArgumentException("同一操作的参数已变化，请重新创建任务");
            current(account, generation); return CompletableFuture.completedFuture(repository.view(existing));
        }
        return sources.selection(input.sourceId(), List.copyOf(input.mediaKeys()), Boolean.TRUE.equals(input.includeMain())).thenCompose(selection -> work.readWork(() -> {
            current(account, generation);
            if (!account.equals(selection.post().accountId())) throw new IllegalArgumentException("来源账号已变化，请重新预览");
            var frozen = files.freeze(selection.post(), selection.media(), generation); current(account, generation);
            Task task = repository.create(account, input.operationId(), fingerprint, selection.post(), root, frozen);
            current(account, generation); return repository.view(task);
        }));
    }
    public record Listing(List<TaskView> items, String workerWarning) { public Listing { items = List.copyOf(items); } }
    public Listing list(int limit, int offset) {
        if (limit < 1 || limit > 50 || offset < 0) throw new IllegalArgumentException("任务分页参数无效");
        String account = telegram.accountId(); var items = repository.list(account, limit, offset).stream().map(repository::view).toList();
        if (!account.equals(telegram.accountId())) throw new IllegalArgumentException("账号已变化，请刷新任务"); return new Listing(items, engine.warning());
    }
    public Listing standalone(int limit,int offset) {
        if(limit<1||limit>50||offset<0)throw new IllegalArgumentException("任务分页参数无效");
        String account=telegram.accountId();long generation=telegram.sessionGeneration();
        var items=repository.list(account,limit,offset,true).stream().map(repository::view).toList();
        current(account,generation);return new Listing(items,engine.warning());
    }
    public TaskView task(String id) { validateId(id); String account = telegram.accountId(); var task = repository.view(repository.task(account, id)); if (!account.equals(telegram.accountId())) throw new IllegalArgumentException("账号已变化"); return task; }
    public TaskView pause(String id) { validateId(id); String account = telegram.accountId(); long generation=telegram.sessionGeneration(); boolean bound=rules!=null&&rules.manualPause(account,id,()->current(account,generation)); var task = repository.view(bound?repository.task(account,id):repository.pause(account, id)); if (!account.equals(telegram.accountId())) throw new IllegalArgumentException("账号已变化，请刷新任务"); return task; }
    public TaskView resume(String id) { validateId(id); String account = telegram.accountId(); long generation=telegram.sessionGeneration(); boolean bound=rules!=null&&rules.manualResume(account,id,()->current(account,generation)); var task = repository.view(bound?repository.task(account,id):repository.resume(account, id)); if (!account.equals(telegram.accountId())) throw new IllegalArgumentException("账号已变化，请刷新任务"); return task; }
    public record Lookup(TaskView task) { }
    public record RepairedLinks(int processed,int updated,int remaining) { }
    public CompletableFuture<RepairedLinks> repairSourceLinks(){String account=telegram.accountId();long generation=telegram.sessionGeneration();return work.readWork(()->{
        current(account,generation);int processed=0,updated=0;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        for(var ref:repository.missingSourceLinks(account)){
            current(account,generation);if(System.nanoTime()>=deadline)break;String link=files.messageLink(account,generation,ref);current(account,generation);
            updated+=repository.fillSourceLink(account,ref,link);processed++;
        }return new RepairedLinks(processed,updated,repository.missingSourceLinkCount(account));
    });}
    public Lookup lookup(String operationId) { validateId(operationId); String account = telegram.accountId(); Task task = repository.byOperation(account, operationId); if (!account.equals(telegram.accountId())) throw new IllegalArgumentException("账号已变化"); return new Lookup(task == null ? null : repository.view(task)); }
    private void current(String account, long generation) { if (!Objects.equals(account, telegram.accountId()) || generation != telegram.sessionGeneration()) throw new IllegalArgumentException("账号或会话已变化，请先核对任务中心再创建"); }
    private static boolean uuid(String value) { return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
    private static void validateId(String value) { if (!uuid(value)) throw new IllegalArgumentException("任务标识无效"); }
}
