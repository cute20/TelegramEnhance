package io.github.telegramenhance.transfer;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.TransferPreferences;
import io.github.telegramenhance.downloadrules.DownloadRulePolicy;
import io.github.telegramenhance.telegram.DownloadFileReader;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadModels.*;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/** A serial durable task lane with a bounded number of media workers. */
@Service
public final class DownloadEngine implements AutoCloseable {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(DownloadEngine.class);
    private final DownloadRepository repository; private final DownloadFileReader reader;
    private final DownloadFileStore files; private final TelegramService telegram; private final AppPaths paths;
    private final DownloadRulePolicy rules;
    private final TransferPreferences preferences;
    private final java.util.function.LongSupplier nanoTime;
    private final ExecutorService mediaWorkers = Executors.newFixedThreadPool(4, Thread.ofPlatform().name("download-media-", 0).factory());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("original-download").factory());
    private volatile boolean closed; private volatile String warning;
    public DownloadEngine(DownloadRepository repository, DownloadFileReader reader, DownloadFileStore files, TelegramService telegram, AppPaths paths) {
        this(repository, reader, files, telegram, paths, null);
    }
    public DownloadEngine(DownloadRepository repository, DownloadFileReader reader, DownloadFileStore files, TelegramService telegram, AppPaths paths, DownloadRulePolicy rules) {
        this(repository, reader, files, telegram, paths, rules, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DownloadEngine(DownloadRepository repository, DownloadFileReader reader, DownloadFileStore files, TelegramService telegram, AppPaths paths, DownloadRulePolicy rules, TransferPreferences preferences) {
        this(repository,reader,files,telegram,paths,rules,preferences,System::nanoTime);
    }
    DownloadEngine(DownloadRepository repository, DownloadFileReader reader, DownloadFileStore files, TelegramService telegram, AppPaths paths, DownloadRulePolicy rules, TransferPreferences preferences, java.util.function.LongSupplier nanoTime) {
        this.repository = repository; this.reader = reader; this.files = files; this.telegram = telegram; this.paths = paths;
        this.rules = rules; this.preferences = preferences; this.nanoTime = nanoTime;
        worker.scheduleWithFixedDelay(this::tick, 300, 1000, TimeUnit.MILLISECONDS);
    }
    public String warning() { return warning; }
    private void tick() {
        if (closed) return;
        String account; long generation;
        try { account = telegram.accountId(); generation = telegram.sessionGeneration(); } catch (RuntimeException unavailable) { return; }
        try {
            cleanDeletedTasks(account,generation);
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
            runItems(task, generation, check);
            repository.finishState(task.accountId(), task.id(), "COMPLETED", null); warning = null;
        } catch (DownloadRulePolicy.Blocked blocked) {
            repository.finishState(task.accountId(), task.id(), "PAUSED", blocked.reason());
        } catch (DownloadFileStore.Stopped stopped) {
            repository.finishState(task.accountId(), task.id(), "PAUSED", pauseMessage(task, "已保存已确认的位置，可继续未完成项"));
        } catch (DownloadFileReader.TemporarilyUnavailable unavailable) {
            if (closed || Thread.currentThread().isInterrupted()) {
                repository.finishState(task.accountId(),task.id(),"PAUSED",pauseMessage(task,"已保存已确认的位置，可继续未完成项"));
            } else {
                try {
                    if(rules!=null)rules.beforeItem(task);
                    repository.scheduleRetry(task.accountId(),task.id(),unavailable.getMessage(),unavailable.minimumDelayMillis());
                } catch(DownloadRulePolicy.Blocked blocked) {
                    repository.finishState(task.accountId(),task.id(),"PAUSED",blocked.reason());
                }
            }
        } catch (IOException failure) {
            repository.finishState(task.accountId(), task.id(), closed || Thread.currentThread().isInterrupted() ? "PAUSED" : "FAILED", pauseMessage(task, "文件写入或校验未完成，请检查磁盘空间、目录权限和临时文件，已有文件未覆盖"));
        } catch (RuntimeException failure) {
            repository.finishState(task.accountId(), task.id(), closed || Thread.currentThread().isInterrupted() ? "PAUSED" : "FAILED",
                    pauseMessage(task, failure instanceof IllegalArgumentException ? failure.getMessage() : "下载未完成，请核对任务后继续；已完成文件保留"));
        }
        if (!closed && !Thread.currentThread().isInterrupted()) cleanCaches(task.accountId(), generation);
    }
    private void runItems(Task task, long generation, Runnable check) throws IOException {
        int concurrency = preferences == null ? 1 : preferences.view().downloadConcurrency();
        if (concurrency == 1) {
            DownloadFileReader.TemporarilyUnavailable temporary=null;
            for (var item : task.items()) {
                check.run();
                try{runItem(task,item,generation,check,new ProgressWatch(nanoTime));}
                catch(DownloadFileReader.TemporarilyUnavailable unavailable){
                    if(unavailable.minimumDelayMillis()>0)throw unavailable;
                    temporary=mergeTemporary(temporary,unavailable);
                }
                cleanCaches(task.accountId(),generation);
            }
            if(temporary!=null)throw temporary;
            return;
        }
        var failed = new AtomicReference<Throwable>();
        var temporary = new AtomicReference<DownloadFileReader.TemporarilyUnavailable>();
        var rateLimited = new AtomicReference<DownloadFileReader.TemporarilyUnavailable>();
        var blocked = new AtomicReference<DownloadRulePolicy.Blocked>();
        // Telegram may expose the same physical file in multiple selected messages.
        // Preserve the serial history gate and avoid two consumers restarting the same
        // native cache, while independent file identities still use all media workers.
        var identities = new HashMap<String, Object>();
        for (var item : task.items()) identities.computeIfAbsent(item.file().uniqueId(), ignored -> new Object());
        Runnable guarded = () -> { check.run(); if (failed.get() != null) throw new DownloadFileStore.Stopped(); };
        for (int offset = 0; offset < task.items().size(); offset += concurrency) {
            guarded.run();
            var progress = new ProgressWatch(nanoTime);
            var running = new ArrayList<Future<Void>>();
            try {
                for (int index = offset; index < Math.min(offset + concurrency, task.items().size()); index++) {
                    var item = task.items().get(index);
                    running.add(mediaWorkers.submit(() -> {
                        try {
                            synchronized (identities.get(item.file().uniqueId())) {
                                guarded.run();
                                if(rateLimited.get()!=null)throw rateLimited.get();
                                var admission = blocked.get(); if (admission != null) throw admission;
                                runItem(task, item, generation, guarded, progress); return null;
                            }
                        }
                        // A closed rule window stops new admissions, while media that
                        // already passed beforeItem keep their original byte-stream checks.
                        catch (DownloadRulePolicy.Blocked admission) { blocked.compareAndSet(null, admission); throw admission; }
                        catch (DownloadFileReader.TemporarilyUnavailable unavailable) {
                            temporary.updateAndGet(previous->mergeTemporary(previous,unavailable));
                            if(unavailable.minimumDelayMillis()>0)rateLimited.updateAndGet(previous->mergeTemporary(previous,unavailable));
                            throw unavailable;
                        }
                        catch (IOException | RuntimeException | Error failure) { failed.compareAndSet(null, failure); throw failure; }
                    }));
                }
            } catch (RejectedExecutionException stopped) { failed.compareAndSet(null, new DownloadFileStore.Stopped()); }
            // Settle every active media before publishing the task's state. Do not cancel a
            // shared TDLib cache while another media worker can still be consuming it.
            boolean interrupted = false;
            for (var future : running) for (;;) {
                try { future.get(); break; }
                catch (InterruptedException stopped) { interrupted = true; failed.compareAndSet(null, new DownloadFileStore.Stopped()); }
                catch (ExecutionException failure) {
                    if (failure.getCause() instanceof DownloadFileReader.TemporarilyUnavailable) { /* Collected by its worker; healthy media keep running. */ }
                    else if (failure.getCause() instanceof DownloadRulePolicy.Blocked admission) blocked.compareAndSet(null, admission);
                    else failed.compareAndSet(null, failure.getCause());
                    break;
                }
                catch (CancellationException stopped) { failed.compareAndSet(null, new DownloadFileStore.Stopped()); break; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            Throwable failure = failed.get();
            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure != null) throw new IllegalStateException("并行下载未完成，请核对任务", failure);
            // Real I/O/native errors take priority even when the policy gate closed first.
            if (blocked.get() != null) throw blocked.get();
            if(rateLimited.get()!=null)throw rateLimited.get();
            cleanCaches(task.accountId(),generation);
        }
        if(temporary.get()!=null)throw temporary.get();
    }
    private static DownloadFileReader.TemporarilyUnavailable mergeTemporary(DownloadFileReader.TemporarilyUnavailable first,DownloadFileReader.TemporarilyUnavailable next){
        if(first==null)return next;
        return next.minimumDelayMillis()>first.minimumDelayMillis()
                ?new DownloadFileReader.TemporarilyUnavailable(first.getMessage(),next.minimumDelayMillis()):first;
    }
    private void runItem(Task task, Item item, long generation, Runnable check, ProgressWatch progress) throws IOException {
        if (item.state().equals("COMPLETED")) return;
        if (item.state().equals("SKIPPED_HISTORY")) { check.run();files.cleanupSkipped(task,item);return; }
        check.run();
        // Window/enable changes apply to the next media, not its already-started byte stream.
        if (rules != null) rules.beforeItem(task);
        if (repository.skipHistory(task, item)) {  return; }
        if (files.recoverPublished(task, item, check)) {  return; }
        if (item.writtenBytes() == item.file().size() && item.sha256() != null
                && java.util.List.of("WRITING","FINALIZING").contains(item.state())) {
            files.write(task, item, (offset, count) -> { throw new IllegalStateException("已验证输出不应再次读取源文件"); }, check);
             return;
        }
        var source = reader.resolve(task.post(), item.file(), generation);
        try(var transfer=new FileTransfer(task,item,generation,source.fileId(),check,progress)){
        long nativeOffset=item.writtenBytes()==item.file().size()?0:item.writtenBytes();
        if (repository.owned(task.accountId(), item.file().uniqueId())) repository.own(task.accountId(), item.file().uniqueId(), transfer.id);
        var state = transfer.status();
        // Uploaded input paths can disappear after archival while TDLib still reports a
        // completed local location. DownloadFile revalidates that location before reading.
        // An existing cache remains unowned/read-only unless a later missing-cache start
        // is explicitly registered below; uncertain cache ownership is never deleted.
        if (state.complete()) {
            transfer.start(nativeOffset);
            state = transfer.status();
        }
        var directory = files.directory(task);
        long needed = item.file().size() - item.writtenBytes() + 16L * 1024 * 1024;
        if (!state.complete()) {
            long cacheNeeded = Math.min(item.file().size()-nativeOffset,item.file().size() - state.downloadedBytes());
            if (Files.getFileStore(directory).equals(Files.getFileStore(paths.telegram()))) needed = Math.addExact(needed, cacheNeeded);
            if (Files.getFileStore(paths.telegram()).getUsableSpace() < cacheNeeded + 16L * 1024 * 1024) throw new IOException("Cache disk full");
        }
        if (Files.getFileStore(directory).getUsableSpace() < needed) throw new IOException("Target disk full");
        if (!state.complete()) {
            if (!state.active()) {
                if (!state.canDownload()) throw new IllegalArgumentException("当前原文件不可下载，请核对来源后继续");
                repository.own(task.accountId(), item.file().uniqueId(), transfer.id);
            }
            // An active TDLib flag can survive a stalled or uncertain previous request.
            // Re-apply the verified full-file request on every attempt, without claiming
            // ownership of an already-active shared cache.
            check.run();
            transfer.start(nativeOffset);
        }
        // The writer forces and hashes every contiguous block while the native file is
        // still downloading. Its checkpoint survives loss of the entire native cache.
        files.writeStreaming(task,item,transfer::read,check,transfer::awaitFinal);
        }
    }
    private final class FileTransfer implements AutoCloseable{
        private final Task task;private final Item item;private final long generation;
        private final Runnable check;private final ProgressWatch progress;
        private final java.util.Set<Integer> attempts=new java.util.HashSet<>();
        private int id;private boolean recovered;private long lastReceived=-1,requestedOffset;
        private String progressKey;
        FileTransfer(Task task,Item item,long generation,int id,Runnable check,ProgressWatch progress){
            this.task=task;this.item=item;this.generation=generation;this.id=id;this.check=check;this.progress=progress;
            progressKey=item.file().uniqueId()+":initial";
        }
        DownloadFileReader.Status status(){return reader.status(task.accountId(),generation,id,item.file().uniqueId(),item.file().size());}
        void start(long offset){
            requestedOffset=offset;
            attempts.add(id);id=startFile(task,item,generation,id,offset);attempts.add(id);
            progress.begin(progressKey,status().downloadedBytes());
        }
        byte[] read(long offset,int count){
            for(;;){
                check.run();var state=status();
                long available=state.complete()?item.file().size()-offset:reader.availableBytes(task.accountId(),generation,id,item.file().uniqueId(),item.file().size(),offset);
                long received=Math.max(state.downloadedBytes(),offset+available);
                if(received!=lastReceived){lastReceived=received;repository.networkProgress(task.accountId(),task.id(),item.id(),received);}
                boolean stalled=progress.stalled(progressKey,state.downloadedBytes());
                try{
                    // Drain cached prefixes before inspecting a failed final receipt, so
                    // a file-reference error cannot discard bytes already received.
                    if(available>=count){
                        byte[] data=reader.chunk(task.accountId(),generation,id,item.file().uniqueId(),item.file().size(),offset,count);
                        return data;
                    }
                    reader.checkDownload(task.accountId(),generation,id,item.file().uniqueId(),item.file().size());
                }catch(DownloadFileReader.DownloadFailed failure){
                    check.run();
                    LOG.warn("Download failed: taskId={}, itemId={}, fileId={}, referenceRejected={}",task.id(),item.id(),id,failure.referenceRejected());
                    if(recovered||!"BOT".equals(item.file().media().source())||item.file().botReference()==null)
                        throw new DownloadFileReader.TemporarilyUnavailable(failure.getMessage());
                    recover(offset);
                    continue;
                }
                if(stalled){
                    LOG.warn("Download stalled: taskId={}, itemId={}, fileId={}, active={}, canDownload={}, downloadedBytes={}, size={}",task.id(),item.id(),id,state.active(),state.canDownload(),state.downloadedBytes(),item.file().size());
                    cancelStalled(task,item,generation,id,check);
                    throw new DownloadFileReader.TemporarilyUnavailable("原文件连续两分钟没有下载进展");
                }
                try{Thread.sleep(350);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new DownloadFileStore.Stopped();}
            }
        }
        void awaitFinal(){
            // All received bytes, including the tail, have been forced and hashed by
            // this point. A late receipt error can defer publication without losing them.
            for(;;)try{
                awaitNativeRange();
                reader.awaitDownload(task.accountId(),generation,id,item.file().uniqueId(),item.file().size(),check);return;
            }
            catch(DownloadFileReader.DownloadFailed failure){
                check.run();
                LOG.warn("Download final receipt failed: taskId={}, itemId={}, fileId={}, referenceRejected={}",task.id(),item.id(),id,failure.referenceRejected());
                if(recovered||!"BOT".equals(item.file().media().source())||item.file().botReference()==null)
                    throw new DownloadFileReader.TemporarilyUnavailable(failure.getMessage());
                // A full target checkpoint still needs a valid final native receipt.
                // Revalidate the full native range, without reading target bytes twice.
                recover(0);
            }
        }
        private void awaitNativeRange(){
            // A full target checkpoint may be awaiting a fresh native verification.
            // Genuine native byte growth keeps that transfer alive before the short
            // final-receipt-only budget begins.
            for(;;){
                check.run();var state=status();
                long available=state.complete()?item.file().size()-requestedOffset:reader.availableBytes(task.accountId(),generation,id,item.file().uniqueId(),item.file().size(),requestedOffset);
                if(available>=item.file().size()-requestedOffset)return;
                reader.checkDownload(task.accountId(),generation,id,item.file().uniqueId(),item.file().size());
                if(progress.stalled(progressKey,state.downloadedBytes())){
                    cancelStalled(task,item,generation,id,check);
                    throw new DownloadFileReader.TemporarilyUnavailable("原文件连续两分钟没有下载进展");
                }
                try{Thread.sleep(200);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new DownloadFileStore.Stopped();}
            }
        }
        private void recover(long offset){
            recovered=true;int previous=id;
            int refreshed=reader.refreshObservedBot(task.post(),item.file(),generation,previous);
            check.run();reader.forgetDownload(task.accountId(),generation,previous,item.file().uniqueId(),item.file().size());
            id=refreshed;attempts.add(id);
            if(repository.owned(task.accountId(),item.file().uniqueId()))repository.own(task.accountId(),item.file().uniqueId(),id);
            var freshState=status();
            long cacheNeeded=Math.min(item.file().size()-offset,item.file().size()-freshState.downloadedBytes());
            try{if(Files.getFileStore(paths.telegram()).getUsableSpace()<cacheNeeded+16L*1024*1024)throw new IllegalArgumentException("原文件缓存空间不足，请释放空间后继续；已保存断点保留");}
            catch(IOException unavailable){throw new IllegalArgumentException("无法核对原文件缓存空间，请检查目录后继续；已保存断点保留");}
            requestedOffset=offset;
            reader.startAt(task.accountId(),generation,id,item.file().uniqueId(),item.file().size(),offset);
            progressKey=item.file().uniqueId()+":recovered";progress.begin(progressKey,status().downloadedBytes());lastReceived=-1;
        }
        public void close(){for(int started:attempts)reader.forgetDownload(task.accountId(),generation,started,item.file().uniqueId(),item.file().size());}
    }
    private void cancelStalled(Task task,Item item,long generation,int id,Runnable check){
        if(!repository.owned(task.accountId(),item.file().uniqueId()))return;
        try{check.run();reader.cancel(task.accountId(),generation,id,item.file().uniqueId(),item.file().size());}
        catch(DownloadFileReader.TemporarilyUnavailable unavailable){
            check.run();
            if(unavailable.minimumDelayMillis()>0)throw unavailable;
        }
        catch(RuntimeException uncertain){
            // Keep the original stall reason and retry even if cancellation could not be
            // confirmed. Pause/account/session changes still take priority over retry.
            check.run();
        }
    }
    /** Only genuine byte growth keeps this finite (at most four media) group alive. */
    private static final class ProgressWatch{
        private final java.util.function.LongSupplier clock;
        private final HashMap<String,Long> highWater=new HashMap<>();
        private long lastAdvance;
        ProgressWatch(java.util.function.LongSupplier clock){this.clock=clock;}
        synchronized void begin(String identity,long bytes){
            if(highWater.putIfAbsent(identity,bytes)==null)lastAdvance=clock.getAsLong();
        }
        synchronized boolean stalled(String identity,long bytes){
            long now=clock.getAsLong();Long previous=highWater.get(identity);
            if(previous==null||bytes>previous){highWater.put(identity,bytes);lastAdvance=now;}
            return now-lastAdvance>=TimeUnit.MINUTES.toNanos(2);
        }
    }
    private int startFile(Task task,Item item,long generation,int id,long offset){
        if("BOT".equals(item.file().media().source())&&item.file().botReference()!=null){
            int started=offset==0?reader.startObservedBot(task.post(),item.file(),generation,id):reader.startObservedBotAt(task.post(),item.file(),generation,id,offset);
            if(repository.owned(task.accountId(),item.file().uniqueId()))repository.own(task.accountId(),item.file().uniqueId(),started);
            return started;
        }
        if(offset==0)reader.start(task.accountId(),generation,id,item.file().uniqueId(),item.file().size());
        else reader.startAt(task.accountId(),generation,id,item.file().uniqueId(),item.file().size(),offset);
        return id;
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
                // A deleted task must never cancel another task's shared native cache, even without deleting it.
                if(pending&&repository.deletedReference(account,owner.uniqueId()))continue;
                boolean released = reader.release(account, generation, owner.fileId(), owner.uniqueId(), !pending);
                if (released && !pending) repository.released(account, owner.uniqueId());
            } catch (RuntimeException unavailable) { /* Keep ownership until a later verified cleanup; never delete by a stale file ID. */ }
        }
    }
    private void cleanDeletedTasks(String account,long generation){
        for(var task:repository.deletionCleanup(account,8)){
            if(closed||Thread.currentThread().isInterrupted())return;
            try{reader.check(account,generation);if(files.cleanupDeleted(task)){reader.check(account,generation);repository.deletionCleaned(account,task.id());}}
            catch(IOException|RuntimeException uncertain){/* Preserve unverified paths and retry durable cleanup in a later worker turn. */}
        }
    }
    @Override @PreDestroy public void close() {
        closed = true; worker.shutdownNow();
        for (var queued : mediaWorkers.shutdownNow()) if (queued instanceof Future<?> future) future.cancel(false);
        boolean interrupted = false;
        for (;;) try { if (worker.awaitTermination(1, TimeUnit.SECONDS) && mediaWorkers.awaitTermination(1, TimeUnit.SECONDS)) break; }
        catch (InterruptedException failure) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
