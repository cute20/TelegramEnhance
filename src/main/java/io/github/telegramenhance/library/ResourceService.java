package io.github.telegramenhance.library;

import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.telegram.TelegramService;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
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
    public CompletableFuture<Detail> probe(String link) { return submit(session -> {
        Post post = reader.probe(link);
        requireAccount(post, session);
        repository.savePost(post);
        return repository.detail(post.key(), 50, 0);
    }); }
    public CompletableFuture<Detail> refresh(String key) { return submit(session -> {
        Post previous = detail(key, 1, 0, session).post();
        Post fresh = reader.refresh(previous);
        requireAccount(fresh, session);
        if (!previous.key().equals(fresh.key()) || !previous.channelId().equals(fresh.channelId())) {
            throw new IllegalArgumentException("主帖身份已发生变化，原索引已保留；请核对原消息后重新填写链接");
        }
        repository.savePost(fresh);
        return repository.detail(key, 50, 0);
    }); }
    public List<Post> posts(int limit, int offset) {
        Session session = capture();
        var posts = repository.posts(session.account(), limit, offset);
        requireCurrent(session); return posts;
    }
    public Detail detail(String key, int limit, int offset) {
        return detail(key, limit, offset, capture());
    }
    private Detail detail(String key, int limit, int offset, Session session) {
        Detail detail = repository.detail(key, limit, offset);
        requireAccount(detail.post(), session);
        return detail;
    }
    public CompletableFuture<Page> comments(String key, String cursor) { return submit(session -> {
        Detail before = detail(key, 1, 0, session);
        if (cursor != null && !Objects.equals(cursor, before.nextCursor())) throw new IllegalArgumentException("分页检查点已变化，请刷新后继续");
        Page page = reader.comments(before.post(), cursor);
        requireCurrent(session);
        if (!key.equals(page.postKey())) throw new IllegalArgumentException("评论读取结果与当前主帖不一致，未保存本页");
        // Unattributed scan issues remain conservative. Per-message mapping warnings are stored
        // separately and replaced only when that exact message is observed successfully again.
        Set<String> scanWarnings = new LinkedHashSet<>(ReadCoverage.commentWarnings(before.commentScanWarnings()));
        scanWarnings.addAll(ReadCoverage.commentWarnings(page.scanWarnings()));
        var stored = new Page(page.postKey(), page.media(), page.messages(), page.messageCount(), page.nextCursor(),
                page.exhausted(), page.coverage(), page.warnings(), page.commentCoverage(), page.commentWarnings(),
                page.observations(), List.copyOf(scanWarnings));
        requireAccount(before.post(), session);
        repository.savePage(stored);
        Detail after = detail(key, 1, 0, session);
        return new Page(page.postKey(), page.media(), page.messages(), page.messageCount(), after.nextCursor(),
                after.exhausted(), after.coverage(), after.warnings(), after.commentCoverage(), after.commentWarnings(),
                page.observations(), after.commentScanWarnings());
    }); }
    private void requireAccount(Post post, Session session) {
        requireCurrent(session);
        if (!session.account().equals(post.accountId())) throw new IllegalArgumentException("当前 Telegram 账号与这条本机索引不一致");
    }
    /** Shared bounded serial queue for metadata reads, including independent transfer previews. */
    public <T> CompletableFuture<T> readWork(Supplier<T> action) { return submit(session -> action.get()); }
    private <T> CompletableFuture<T> submit(Function<Session, T> action) {
        Session session = capture();
        try { return CompletableFuture.supplyAsync(() -> {
            requireCurrent(session);
            T result = action.apply(session);
            requireCurrent(session); return result;
        }, reads); }
        catch (RejectedExecutionException e) { throw new IllegalArgumentException("读取队列已满，请等待当前主帖读取结束"); }
    }
    private Session capture() {
        Session session = new Session(telegram.accountId(), telegram.sessionGeneration());
        requireCurrent(session); return session;
    }
    private void requireCurrent(Session session) {
        if (Thread.currentThread().isInterrupted() || !Objects.equals(session.account(), telegram.accountId())
                || session.generation() != telegram.sessionGeneration()) {
            throw new IllegalArgumentException("Telegram 账号或会话已变化，请重新读取资源");
        }
    }
    private record Session(String account, long generation) { }
    @PreDestroy public void close() {
        reads.shutdownNow();
        try { reads.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
