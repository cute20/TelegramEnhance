package io.github.telegramenhance.transfer;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.downloadrules.DownloadRulePolicy;
import io.github.telegramenhance.telegram.DownloadFileReader;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadModels.*;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** One durable download worker. Native callbacks never copy files or write task SQL. */
@Service
public final class DownloadEngine implements AutoCloseable {
    private final DownloadRepository repository; private final DownloadFileReader reader;
    private final DownloadFileStore files; private final TelegramService telegram; private final AppPaths paths;
    private final DownloadRulePolicy rules;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("original-download").factory());
    private volatile boolean closed; private volatile String warning;
    public DownloadEngine(DownloadRepository repository, DownloadFileReader reader, DownloadFileStore files, TelegramService telegram, AppPaths paths) {
        this(repository, reader, files, telegram, paths, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DownloadEngine(DownloadRepository repository, DownloadFileReader reader, DownloadFileStore files, TelegramService telegram, AppPaths paths, DownloadRulePolicy rules) {
        this.repository = repository; this.reader = reader; this.files = files; this.telegram = telegram; this.paths = paths;
        this.rules = rules;
        worker.scheduleWithFixedDelay(this::tick, 300, 1000, TimeUnit.MILLISECONDS);
    }
    public String warning() { return warning; }
    private void tick() {
        if (closed) return;
        String account; long generation;
        try { account = telegram.accountId(); generation = telegram.sessionGeneration(); } catch (RuntimeException unavailable) { return; }
        try {
            cleanCaches(account, generation);
            for (int batch = 0; batch < 10 && !closed; batch++) {
                reader.check(account, generation); Task task = repository.next(account); if (task == null) break;
                run(task, generation);
            }
        } catch (RuntimeException failure) { warning = "下载后台未能确认状态，请检查连接和数据目录，必要时重启后核对任务"; }
    }
    private void run(Task task, long generation) {
        Runnable check = () -> {
            if (closed || Thread.currentThread().isInterrupted() || repository.pauseRequested(task.accountId(), task.id())) throw new DownloadFileStore.Stopped();
            reader.check(task.accountId(), generation);
        };
        try {
            for (var item : task.items()) {
                if (item.state().equals("COMPLETED") || item.state().equals("SKIPPED_HISTORY")) continue;
                check.run();
                // Window/enable changes apply to the next media, not its already-started byte stream.
                if (rules != null) rules.beforeItem(task);
                if (repository.skipHistory(task, item)) { cleanCaches(task.accountId(), generation); continue; }
                if (files.recoverPublished(task, item, check)) { cleanCaches(task.accountId(), generation); continue; }
                if (item.writtenBytes() == item.file().size() && item.sha256() != null) {
                    files.write(task, item, (offset, count) -> { throw new IllegalStateException("已验证输出不应再次读取源文件"); }, check);
                    cleanCaches(task.accountId(), generation); continue;
                }
                var source = reader.resolve(task.post(), item.file(), generation); int id = source.fileId();
                if (repository.owned(task.accountId(), item.file().uniqueId())) repository.own(task.accountId(), item.file().uniqueId(), id);
                var state = reader.status(task.accountId(), generation, id, item.file().uniqueId(), item.file().size());
                // Uploaded input paths can disappear after archival while TDLib still reports a
                // completed local location. DownloadFile revalidates that location before reading.
                // An existing cache remains unowned/read-only unless a later missing-cache start
                // is explicitly registered below; uncertain cache ownership is never deleted.
                if (state.complete()) {
                    reader.start(task.accountId(), generation, id, item.file().uniqueId(), item.file().size());
                    state = reader.status(task.accountId(), generation, id, item.file().uniqueId(), item.file().size());
                }
                var directory = files.directory(task);
                long needed = item.file().size() - item.writtenBytes() + 16L * 1024 * 1024;
                if (!state.complete()) {
                    long cacheNeeded = item.file().size() - state.downloadedBytes();
                    if (Files.getFileStore(directory).equals(Files.getFileStore(paths.telegram()))) needed = Math.addExact(needed, cacheNeeded);
                    if (Files.getFileStore(paths.telegram()).getUsableSpace() < cacheNeeded + 16L * 1024 * 1024) throw new IOException("Cache disk full");
                }
                if (Files.getFileStore(directory).getUsableSpace() < needed) throw new IOException("Target disk full");
                if (!state.complete() && !state.active()) {
                    if (!state.canDownload()) throw new IllegalArgumentException("当前原文件不可下载，请核对来源后继续");
                    repository.own(task.accountId(), item.file().uniqueId(), id);
                    reader.start(task.accountId(), generation, id, item.file().uniqueId(), item.file().size());
                }
                long lastProgress = -1, lastAdvance = System.nanoTime();
                while (!state.complete()) {
                    check.run(); state = reader.status(task.accountId(), generation, id, item.file().uniqueId(), item.file().size());
                    if (state.downloadedBytes() != lastProgress) {
                        lastProgress = state.downloadedBytes(); lastAdvance = System.nanoTime();
                        repository.progress(task.accountId(), task.id(), item.id(), "DOWNLOADING", lastProgress, item.writtenBytes(), item.sha256(), item.relativeFile());
                    }
                    if (System.nanoTime() - lastAdvance > TimeUnit.MINUTES.toNanos(2)) throw new IllegalArgumentException("原文件连续两分钟没有下载进展，已暂停自动执行，可检查连接后继续");
                    if (!state.complete()) try { Thread.sleep(350); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new DownloadFileStore.Stopped(); }
                }
                check.run();
                files.write(task, item, (offset, count) -> reader.chunk(task.accountId(), generation, id, item.file().uniqueId(), item.file().size(), offset, count), check);
                cleanCaches(task.accountId(), generation);
            }
            repository.finishState(task.accountId(), task.id(), "COMPLETED", null); warning = null;
        } catch (DownloadRulePolicy.Blocked blocked) {
            repository.finishState(task.accountId(), task.id(), "PAUSED", blocked.reason());
        } catch (DownloadFileStore.Stopped stopped) {
            repository.finishState(task.accountId(), task.id(), "PAUSED", pauseMessage(task, "已保存已确认的位置，可继续未完成项"));
        } catch (IOException failure) {
            repository.finishState(task.accountId(), task.id(), closed || Thread.currentThread().isInterrupted() ? "PAUSED" : "FAILED", pauseMessage(task, "文件写入或校验未完成，请检查磁盘空间、目录权限和临时文件，已有文件未覆盖"));
        } catch (RuntimeException failure) {
            repository.finishState(task.accountId(), task.id(), closed || Thread.currentThread().isInterrupted() ? "PAUSED" : "FAILED",
                    pauseMessage(task, failure instanceof IllegalArgumentException ? failure.getMessage() : "下载未完成，请核对任务后继续；已完成文件保留"));
        }
        if (!closed && !Thread.currentThread().isInterrupted()) cleanCaches(task.accountId(), generation);
    }
    private String pauseMessage(Task task, String fallback) {
        return (closed || Thread.currentThread().isInterrupted()) && rules != null && rules.managed(task.accountId(), task.id())
                ? DownloadRulePolicy.SHUTDOWN_PAUSE : fallback;
    }
    private void cleanCaches(String account, long generation) {
        for (var owner : repository.owners(account, 8)) {
            if (closed || Thread.currentThread().isInterrupted()) return;
            try {
                boolean pending = repository.pendingReference(account, owner.uniqueId());
                boolean released = reader.release(account, generation, owner.fileId(), owner.uniqueId(), !pending);
                if (released && !pending) repository.released(account, owner.uniqueId());
            } catch (RuntimeException unavailable) { /* Keep ownership until a later verified cleanup; never delete by a stale file ID. */ }
        }
    }
    @Override @PreDestroy public void close() {
        closed = true; worker.shutdownNow();
        boolean interrupted = false;
        for (;;) try { if (worker.awaitTermination(1, TimeUnit.SECONDS)) break; }
        catch (InterruptedException failure) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
