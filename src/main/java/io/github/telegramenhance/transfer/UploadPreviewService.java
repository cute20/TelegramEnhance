package io.github.telegramenhance.transfer;

import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.telegram.UploadTargetReader;
import io.github.telegramenhance.transfer.UploadPreviewModels.*;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

@Service
public final class UploadPreviewService implements AutoCloseable {
    private static final long TTL = TimeUnit.MINUTES.toMillis(10);
    private final TelegramService telegram; private final ResourceService reads; private final UploadTargetReader targets; private final UploadDirectoryScanner scanner;
    private final ExecutorService local = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), Thread.ofPlatform().name("upload-preparation").factory());
    private final LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<>();
    private record Session(String account, long generation) { }
    private record Snapshot(String id, Session session, Input input, Target target, Scan scan, long createdAt, long expiresAt) { }
    public UploadPreviewService(TelegramService telegram, ResourceService reads, UploadTargetReader targets, UploadDirectoryScanner scanner) { this.telegram = telegram; this.reads = reads; this.targets = targets; this.scanner = scanner; }
    public CompletableFuture<View> preview(Input input) { return preview(input, false); }
    public CompletableFuture<View> previewRule(Input input) { return preview(input, true); }
    private CompletableFuture<View> preview(Input input, boolean bound) {
        if (input == null || input.link() == null || input.link().isBlank() || input.link().length() > 2048
                || !("FILENAME".equals(input.captionMode()) || "EMPTY".equals(input.captionMode()))) throw new IllegalArgumentException("请填写目标主帖、待上传及归档目录，并选择说明方式");
        Session session = capture(); scanner.directories(input.sourceDirectory(), input.archiveDirectory());
        return submit(() -> { current(session); if(bound)boundDirectories(input);var scan=scanner.scan(input.sourceDirectory(), input.archiveDirectory(), !Boolean.FALSE.equals(input.recursive()), () -> current(session));if(bound)boundDirectories(input);return scan; })
            .thenCompose(scan -> reads.readWork(() -> {
                current(session); var target = targets.read(input.link(), session.account(), session.generation(), types(scan)); current(session);
                long stamp = System.currentTimeMillis(); var snapshot = new Snapshot(UUID.randomUUID().toString(), session, input, target, scan, stamp, stamp + TTL);
                remember(snapshot); return view(snapshot, target.checkedAt());
            }));
    }
    public View get(String id) { var snapshot = snapshot(id, capture()); return view(snapshot, snapshot.target().checkedAt()); }
    public Target target(String id){return snapshot(id,capture()).target();}
    /** Shares the single bounded directory lane with upload preparation; no Telegram or file mutations. */
    public CompletableFuture<Scan> observeDirectories(Input input,Runnable check){return submit(()->{
        check.run();boundDirectories(input);
        return scanner.scan(input.sourceDirectory(),input.archiveDirectory(),!Boolean.FALSE.equals(input.recursive()),check);
    });}
    private void boundDirectories(Input input){var dirs=scanner.directories(input.sourceDirectory(),input.archiveDirectory());
        if(!DownloadPaths.key(dirs.source()).equals(DownloadPaths.key(Path.of(input.sourceDirectory())))||!DownloadPaths.key(dirs.archive()).equals(DownloadPaths.key(Path.of(input.archiveDirectory()))))throw new IllegalArgumentException("绑定目录位置发生变化，请重新核对规则");
    }
    public record Prepared(Input input, Target target, Scan scan, List<Group> groups) { public Prepared { groups = List.copyOf(groups); } }
    public CompletableFuture<Prepared> freeze(String id) {
        Session session = capture(); var original = snapshot(id, session);
        return verify(id).thenApply(verified -> { current(session); snapshot(id, session); if (verified.fileCount() == 0) throw new IllegalArgumentException("没有可上传的媒体"); return new Prepared(original.input(), original.target(), original.scan(), verified.groups()); });
    }
    public CompletableFuture<View> verify(String id) {
        Session session = capture(); var snapshot = snapshot(id, session);
        return reads.readWork(() -> { current(session); var fresh = targets.read(snapshot.input().link(), session.account(), session.generation(), types(snapshot.scan())); current(session);
            if (!snapshot.target().post().equals(fresh.post())) throw new IllegalArgumentException("目标主帖或讨论映射已变化，旧分组保留，请重新生成预览"); return fresh; })
            .thenCompose(fresh -> submit(() -> { current(session); scanner.verify(snapshot.scan(), () -> current(session)); snapshot(id, session); return view(snapshot, fresh.checkedAt()); }));
    }
    private synchronized void remember(Snapshot snapshot) { current(snapshot.session()); prune(snapshot.session()); snapshots.put(snapshot.id(), snapshot); while (snapshots.size() > 8) snapshots.remove(snapshots.keySet().iterator().next()); }
    private synchronized Snapshot snapshot(String id, Session session) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IllegalArgumentException("上传预览标识无效");
        current(session); prune(session); var result = snapshots.get(id); if (result == null) throw new IllegalArgumentException("上传预览已过期或不属于当前会话，请重新生成预览"); return result;
    }
    private void prune(Session session) { long now = System.currentTimeMillis(); snapshots.values().removeIf(s -> !s.session().equals(session) || s.expiresAt() <= now); }
    private Session capture() { return new Session(telegram.accountId(), telegram.sessionGeneration()); }
    private static Set<String> types(Scan scan) { return scan.files().stream().map(FileVersion::mediaType).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    private void current(Session session) { if (Thread.currentThread().isInterrupted() || !session.equals(capture())) throw new IllegalArgumentException("账号或会话已变化，请重新生成上传预览"); }
    private <T> CompletableFuture<T> submit(java.util.function.Supplier<T> work) {
        try { return CompletableFuture.supplyAsync(work, local); } catch (RejectedExecutionException failure) { throw new IllegalArgumentException("上传预览队列已满，请等待当前目录读取完成"); }
    }
    static View view(Snapshot snapshot, long checkedAt) {
        var scan = snapshot.scan();var files=scan.files();var groups=groups(files,snapshot.input().captionMode());
        var warnings = new ArrayList<String>(); warnings.add("预览不发送或移动文件；保存任务后仍需到任务中心确认开始。预览保留10分钟，已保存任务独立持久化。");
        warnings.add("媒体按扩展名识别，图片、视频、音频、文档分别分组，GIF逐条；其他文件按文档处理。格式编码和发送权限需在执行前再次核对。");
        if (files.isEmpty()) warnings.add("目录中没有可预览的稳定媒体，请放入文件并等待复制完成后重新预览。");
        if (scan.deferredFiles() > 0) warnings.add("有" + scan.deferredFiles() + "个空文件或最近3秒内变动的媒体暂未纳入。");
        if (scan.linkedEntries() > 0) warnings.add("已跳过" + scan.linkedEntries() + "个链接或重解析条目，不读取目录外内容。");
        if (scan.duplicateFiles() > 0) warnings.add("已排除" + scan.duplicateFiles() + "个同类型相同内容文件，保留自然顺序中的第一份；不会删除文件。");
        if (snapshot.target().slowModeSeconds() > 0) warnings.add("讨论组启用了慢速模式，后续执行需要遵守等待时间。");
        var post = snapshot.target().post(); return new View(snapshot.id(), snapshot.session().account(), "PREVIEW_ONLY", scan.sourceDirectory(), scan.archiveDirectory(), scan.recursive(), snapshot.input().captionMode(),
                post.channelTitle(), post.title(), post.text(), snapshot.input().link().strip(), snapshot.target().discussionTitle(), snapshot.target().permission(), snapshot.target().slowModeSeconds(),
                files.size(), files.stream().mapToLong(FileVersion::size).sum(), groups, scan.otherFiles(), scan.deferredFiles(), scan.linkedEntries(), scan.duplicateFiles(), warnings, checkedAt, snapshot.createdAt(), snapshot.expiresAt());
    }
    public static List<Group> groups(List<FileVersion> files,String captionMode){
        var groups=new ArrayList<Group>();
        for (String type : List.of("PHOTO", "VIDEO", "AUDIO", "DOCUMENT", "ANIMATION")) {
            var batch = files.stream().filter(file -> file.mediaType().equals(type)).toList(); int maximum = type.equals("ANIMATION") ? 1 : 10;
            for (int from = 0; from < batch.size(); from += maximum) {
                var members = batch.subList(from, Math.min(from + maximum, batch.size())).stream().map(file -> {
                    String name = Path.of(file.relativePath()).getFileName().toString(); return new Member(file.relativePath(), name, type, file.size(), "FILENAME".equals(captionMode) ? name : "");
                }).toList(); groups.add(new Group(groups.size() + 1, type, members.size() == 1 ? "SINGLE" : "ALBUM", members));
            }
        }
        return List.copyOf(groups);
    }
    @Override @PreDestroy public void close() { local.shutdownNow(); try { local.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); } synchronized (this) { snapshots.clear(); } }
}
