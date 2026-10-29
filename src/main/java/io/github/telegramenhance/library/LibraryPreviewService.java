package io.github.telegramenhance.library;

import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.telegram.PostPreviewReader;
import io.github.telegramenhance.telegram.PostPreviewReader.Preview;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadSourceService;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Bounded preview work, entirely separate from native update callbacks and persistent indexes. */
@Service
public final class LibraryPreviewService implements AutoCloseable {
    private static final int CACHE_SIZE = 128;
    private static final long AVAILABLE_TTL = TimeUnit.MINUTES.toNanos(10);
    private static final long UNAVAILABLE_TTL = TimeUnit.MINUTES.toNanos(1);
    private final ResourceRepository repository;
    private final PostPreviewReader reader;
    private final TelegramService telegram;
    private final DownloadSourceService downloads;
    private final ThumbnailCache disk;
    private final JsonMapper json = JsonMapper.builder().build();
    private final LongSupplier ticker;
    private final Object lock = new Object();
    private final Map<Request, CompletableFuture<Preview>> inFlight = new HashMap<>();
    private final LinkedHashMap<Revision, Cached> cache = new LinkedHashMap<>(128, 0.75f, true);
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(20), Thread.ofPlatform().name("library-preview-", 0).factory());
    private volatile boolean closed;

    public LibraryPreviewService(ResourceRepository repository, PostPreviewReader reader, TelegramService telegram) {
        this(repository, reader, telegram, null, null, System::nanoTime);
    }

    public LibraryPreviewService(ResourceRepository repository, PostPreviewReader reader, TelegramService telegram,
            DownloadSourceService downloads) {
        this(repository, reader, telegram, downloads, null, System::nanoTime);
    }

    @Autowired
    public LibraryPreviewService(ResourceRepository repository, PostPreviewReader reader, TelegramService telegram,
            DownloadSourceService downloads, ThumbnailCache disk) {
        this(repository, reader, telegram, downloads, disk, System::nanoTime);
    }

    LibraryPreviewService(ResourceRepository repository, PostPreviewReader reader, TelegramService telegram, LongSupplier ticker) {
        this(repository, reader, telegram, null, null, ticker);
    }

    LibraryPreviewService(ResourceRepository repository, PostPreviewReader reader, TelegramService telegram,
            DownloadSourceService downloads, ThumbnailCache disk, LongSupplier ticker) {
        this.repository = repository; this.reader = reader; this.telegram = telegram; this.downloads = downloads; this.disk = disk; this.ticker = ticker;
    }

    public CompletableFuture<Preview> preview(String key) {
        return submit(key, null);
    }

    public CompletableFuture<Preview> mediaPreview(String key, String mediaKey) {
        if (mediaKey == null || mediaKey.isBlank() || mediaKey.length() > 512) throw new IllegalArgumentException("媒体标识无效");
        return submit(key, mediaKey);
    }

    public CompletableFuture<Preview> downloadMediaPreview(String id, Integer pageNumber, String mediaKey) {
        if (downloads == null || id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || pageNumber == null || pageNumber < 0 || mediaKey == null || mediaKey.isBlank() || mediaKey.length() > 512) {
            throw new IllegalArgumentException("来源媒体预览身份无效");
        }
        return submit(new Request(telegram.accountId(), id, mediaKey, pageNumber, cacheGeneration()));
    }

    private CompletableFuture<Preview> submit(String key, String mediaKey) {
        if (key == null || key.isBlank() || key.length() > 512) throw new IllegalArgumentException("主帖标识无效");
        return submit(new Request(telegram.accountId(), key, mediaKey, null, cacheGeneration()));
    }

    private long cacheGeneration() { return disk == null ? 0 : disk.generation(); }
    public void clearMemory() { synchronized (lock) { cache.clear(); } }

    private CompletableFuture<Preview> submit(Request request) {
        CompletableFuture<Preview> shared;
        synchronized (lock) {
            requireOpen();
            shared = inFlight.get(request);
            if (shared == null) {
                shared = new CompletableFuture<>();
                inFlight.put(request, shared);
                CompletableFuture<Preview> work = shared;
                try { workers.execute(() -> run(request, work)); }
                catch (RejectedExecutionException failure) {
                    inFlight.remove(request, work);
                    work.completeExceptionally(new IllegalArgumentException("预览队列已满，请稍后重试"));
                }
            }
        }
        // A caller only owns its dependent future. Cancellation cannot cancel another caller's
        // shared native read, and account checks also apply to cached and deduplicated results.
        return shared.thenApply(preview -> {
            try { requireCurrent(request.account()); }
            catch (AccountChanged changed) { throw new IllegalArgumentException("Telegram 账号已变化，请刷新后重新读取预览"); }
            return preview;
        });
    }

    private void run(Request request, CompletableFuture<Preview> result) {
        Preview value = null;
        RuntimeException failure = null;
        try { value = load(request); requireCurrent(request.account()); }
        catch (AccountChanged changed) { failure = new IllegalArgumentException("Telegram 账号已变化，请刷新后重新读取预览"); }
        catch (RuntimeException | LinkageError upstream) {
            failure = new IllegalArgumentException(request.downloadPage() != null ? "来源媒体预览读取未完成，请重新预览当前页"
                    : request.mediaKey() == null ? "主帖预览读取未完成，请稍后重试" : "评论媒体预览读取未完成，请刷新后重试");
        }
        synchronized (lock) { inFlight.remove(request, result); }
        if (failure == null) result.complete(value); else result.completeExceptionally(failure);
    }

    private Preview load(Request request) {
        for (int attempt = 0; attempt < 2; attempt++) {
            requireCurrent(request.account());
            Snapshot source = source(request);
            Revision revision = revision(request, source);
            requireCurrent(request.account());
            Preview preview = cached(revision);
            boolean miss = preview == null;
            String diskKey = disk == null ? null : diskKey(source);
            if (miss && disk != null) preview = disk.get(diskKey, request.cacheGeneration());
            boolean nativeRead = preview == null;
            if (nativeRead) preview = checked(request.downloadPage() != null ? reader.readSourceMedia(source.post(), source.media())
                    : source.media() == null ? reader.read(source.post()) : reader.readMedia(source.post(), source.media()));
            requireCurrent(request.account());
            Snapshot after = source(request);
            Revision current = revision(request, after);
            requireCurrent(request.account());
            if (source.media() != null && (!Objects.equals(source.media().message().chatId(), after.media().message().chatId())
                    || !Objects.equals(source.media().message().messageId(), after.media().message().messageId())
                    || !Objects.equals(source.post().discussionChatId(), after.post().discussionChatId())
                    || !Objects.equals(source.post().discussionRootMessageId(), after.post().discussionRootMessageId()))) {
                throw new IllegalArgumentException("评论媒体归属已变化，请刷新后重试");
            }
            if (!revision.equals(current)) continue;
            if (nativeRead && disk != null) disk.put(diskKey, request.cacheGeneration(), preview);
            if (miss) {
                synchronized (lock) {
                    requireOpen();
                    // Discard obsolete revisions of the same post without retaining another
                    // account's post snapshot or returning it under this request identity.
                    cache.keySet().removeIf(old -> old.request().equals(request) && !old.equals(revision));
                    long ttl = "AVAILABLE".equals(preview.status()) && "THUMBNAIL".equals(preview.quality()) ? AVAILABLE_TTL : UNAVAILABLE_TTL;
                    if (request.cacheGeneration() == cacheGeneration()) cache.put(revision, new Cached(preview, ticker.getAsLong(), ttl));
                    while (cache.size() > CACHE_SIZE) cache.remove(cache.keySet().iterator().next());
                }
            }
            return preview;
        }
        throw new IllegalArgumentException("主帖已更新，请刷新后重新读取预览");
    }

    private String diskKey(Snapshot source) {
        try {
            // Never use caller version, a transient preview ID, title alone or a truncated record.toString().
            var identity = new DiskIdentity("thumbnail-v1", source.postUpdatedAt(), source.scanUpdatedAt(), source.post(), source.media());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(identity)));
        } catch (Exception failure) { throw new IllegalStateException("缩略图缓存身份无法核对"); }
    }

    private Preview cached(Revision revision) {
        synchronized (lock) {
            requireOpen();
            Cached cached = cache.get(revision);
            if (cached == null) return null;
            if (ticker.getAsLong() - cached.storedAt() >= cached.ttl()) { cache.remove(revision); return null; }
            return cached.preview();
        }
    }

    private Snapshot source(Request request) {
        if (request.downloadPage() != null) {
            try {
                var source = downloads.thumbnailSource(request.key(), request.downloadPage(), request.mediaKey()).get(12, TimeUnit.SECONDS);
                return source == null ? null : new Snapshot(source.post(), source.media(), source.pageNumber(), 0);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalArgumentException("来源媒体预览读取已中断");
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                throw new IllegalArgumentException("来源预览已变化或暂不可读取");
            }
        }
        if (request.mediaKey() == null) {
            var source = repository.previewSource(request.account(), request.key());
            return source == null ? null : new Snapshot(source.post(), null, source.updatedAt(), 0);
        }
        var source = repository.mediaPreviewSource(request.account(), request.key(), request.mediaKey());
        return source == null ? null : new Snapshot(source.post(), source.media(), source.postUpdatedAt(), source.scanUpdatedAt());
    }

    private Revision revision(Request request, Snapshot source) {
        if (source == null || source.post() == null || !request.account().equals(source.post().accountId())
                || request.downloadPage() == null && !request.key().equals(source.post().key())) throw new AccountChanged();
        if (request.mediaKey() != null) {
            Media media = source.media(); Post post = source.post();
            boolean mainSource = request.downloadPage() != null && media != null && "MAIN".equals(media.source())
                    && media.message() != null && post.mainMedia().contains(media) && post.channelId().equals(media.message().chatId())
                    && post.mainMessages().stream().anyMatch(ref -> ref.chatId().equals(media.message().chatId())
                        && ref.messageId().equals(media.message().messageId()));
            if (media == null || !request.mediaKey().equals(media.key()) || media.message() == null
                    || !("PHOTO".equals(media.type()) || "VIDEO".equals(media.type()))) {
                throw new IllegalArgumentException("媒体不属于当前预览范围");
            }
            if (!mainSource && (!"COMMENT".equals(media.source())
                    || post.discussionChatId() == null || post.discussionChatId().isBlank()
                    || post.discussionRootMessageId() == null || post.discussionRootMessageId().isBlank()
                    || post.discussionChatId().equals(post.channelId()) || !post.discussionChatId().equals(media.message().chatId())
                    || post.discussionRootMessageId().equals(media.message().messageId())
                    || post.discussionRoots().stream().anyMatch(root -> root.chatId().equals(media.message().chatId()) && root.messageId().equals(media.message().messageId()))
                    || post.mainMessages().stream().anyMatch(main -> main.chatId().equals(media.message().chatId()) && main.messageId().equals(media.message().messageId())))) {
                throw new IllegalArgumentException("评论媒体不属于当前主帖预览范围");
            }
        }
        // Post and all its fields are immutable records/lists. Structural equality is an exact
        // content version, including changes made within the same updated_at millisecond.
        return new Revision(request, source.postUpdatedAt(), source.scanUpdatedAt(), source.post(), source.media());
    }

    private Preview checked(Preview value) {
        if (value == null) throw new IllegalStateException("预览结果无效");
        if ("UNAVAILABLE".equals(value.status())) return new Preview("UNAVAILABLE", null, 0, 0);
        if (!"AVAILABLE".equals(value.status()) || value.dataUrl() == null
                || !value.dataUrl().startsWith("data:image/jpeg;base64,") || value.width() <= 0 || value.height() <= 0) {
            throw new IllegalStateException("预览结果无效");
        }
        return value;
    }

    private void requireCurrent(String expected) {
        requireOpen();
        String current;
        try { current = telegram.accountId(); }
        catch (RuntimeException unavailable) { throw new AccountChanged(); }
        if (!Objects.equals(expected, current)) throw new AccountChanged();
    }

    private void requireOpen() { if (closed) throw new IllegalArgumentException("预览服务正在关闭"); }

    @Override @PreDestroy public void close() {
        ArrayList<CompletableFuture<Preview>> pending;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            pending = new ArrayList<>(inFlight.values());
            inFlight.clear(); cache.clear();
        }
        workers.shutdownNow();
        pending.forEach(future -> future.completeExceptionally(new IllegalArgumentException("预览服务正在关闭")));
        try { workers.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private record Request(String account, String key, String mediaKey, Integer downloadPage, long cacheGeneration) { }
    private record DiskIdentity(String format, long postVersion, long commentsVersion, Post post, Media media) { }
    private record Snapshot(Post post, Media media, long postUpdatedAt, long scanUpdatedAt) { }
    private record Revision(Request request, long updatedAt, long scanUpdatedAt, Post contentVersion, Media mediaVersion) { }
    private record Cached(Preview preview, long storedAt, long ttl) { }
    private static final class AccountChanged extends RuntimeException { }
}
