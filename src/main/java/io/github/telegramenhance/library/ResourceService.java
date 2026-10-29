package io.github.telegramenhance.library;

import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.telegram.TelegramService;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class ResourceService {
    private final ResourceReader reader;
    private final ResourceRepository repository;
    private final TelegramService telegram;
    // Bounded, serial read work; native callbacks and the UI remain responsive during scans.
    private final ExecutorService reads = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), Thread.ofPlatform().name("resource-reader").factory());
    public ResourceService(ResourceReader reader, ResourceRepository repository, TelegramService telegram) {
        this.reader = reader; this.repository = repository; this.telegram = telegram;
    }
    public CompletableFuture<Detail> probe(String link) { return submit(() -> {
        Post post = reader.probe(link);
        requireAccount(post);
        repository.savePost(post);
        return repository.detail(post.key(), 50, 0);
    }); }
    public CompletableFuture<Detail> refresh(String key) { return submit(() -> {
        Post previous = detail(key, 1, 0).post();
        Post fresh = reader.refresh(previous);
        requireAccount(fresh);
        if (!previous.key().equals(fresh.key()) || !previous.channelId().equals(fresh.channelId())) {
            throw new IllegalArgumentException("主帖身份已发生变化，原索引已保留；请核对原消息后重新填写链接");
        }
        repository.savePost(fresh);
        return repository.detail(key, 50, 0);
    }); }
    public List<Post> posts(int limit, int offset) { return repository.posts(telegram.accountId(), limit, offset); }
    public Detail detail(String key, int limit, int offset) {
        Detail detail = repository.detail(key, limit, offset);
        requireAccount(detail.post());
        return detail;
    }
    public CompletableFuture<Page> comments(String key, String cursor) { return submit(() -> {
        Detail before = detail(key, 1, 0);
        if (cursor != null && !Objects.equals(cursor, before.nextCursor())) throw new IllegalArgumentException("分页检查点已变化，请刷新后继续");
        Page page = reader.comments(before.post(), cursor);
        if (!key.equals(page.postKey())) throw new IllegalArgumentException("评论读取结果与当前主帖不一致，未保存本页");
        // Unattributed scan issues remain conservative. Per-message mapping warnings are stored
        // separately and replaced only when that exact message is observed successfully again.
        Set<String> scanWarnings = new LinkedHashSet<>(ReadCoverage.commentWarnings(before.commentScanWarnings()));
        scanWarnings.addAll(ReadCoverage.commentWarnings(page.scanWarnings()));
        var stored = new Page(page.postKey(), page.media(), page.messages(), page.messageCount(), page.nextCursor(),
                page.exhausted(), page.coverage(), page.warnings(), page.commentCoverage(), page.commentWarnings(),
                page.observations(), List.copyOf(scanWarnings));
        requireAccount(before.post());
        repository.savePage(stored);
        Detail after = detail(key, 1, 0);
        return new Page(page.postKey(), page.media(), page.messages(), page.messageCount(), after.nextCursor(),
                after.exhausted(), after.coverage(), after.warnings(), after.commentCoverage(), after.commentWarnings(),
                page.observations(), after.commentScanWarnings());
    }); }
    private void requireAccount(Post post) {
        if (!telegram.accountId().equals(post.accountId())) throw new IllegalArgumentException("当前 Telegram 账号与这条本机索引不一致");
    }
    /** Shared bounded serial queue for metadata reads, including independent transfer previews. */
    public <T> CompletableFuture<T> readWork(Supplier<T> action) { return submit(action); }
    private <T> CompletableFuture<T> submit(Supplier<T> action) {
        telegram.accountId();
        try { return CompletableFuture.supplyAsync(action, reads); }
        catch (RejectedExecutionException e) { throw new IllegalArgumentException("读取队列已满，请等待当前主帖读取结束"); }
    }
    @PreDestroy public void close() {
        reads.shutdownNow();
        try { reads.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
