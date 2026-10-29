package io.github.telegramenhance.transfer;

import io.github.telegramenhance.library.ResourceModels.Page;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.telegram.TelegramService;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Bounded ephemeral source previews. No repository, file transfer, subscription, or task writes. */
@Service
public final class DownloadSourceService {
    private static final long TTL = TimeUnit.MINUTES.toNanos(10);
    private static final int LIMIT = 8;
    private final ResourceService work;
    private final ResourceReader reader;
    private final TelegramService telegram;
    private final LongSupplier clock;
    // Accessed only on the existing resource-reader worker.
    private final LinkedHashMap<String, Entry> previews = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public DownloadSourceService(ResourceService work, ResourceReader reader, TelegramService telegram) {
        this(work, reader, telegram, System::nanoTime);
    }
    DownloadSourceService(ResourceService work, ResourceReader reader, TelegramService telegram, LongSupplier clock) {
        this.work = work; this.reader = reader; this.telegram = telegram; this.clock = clock;
    }
    public CompletableFuture<DownloadSourcePreview> preview(String link) {
        if (link == null || link.isBlank() || link.length() > 2048) throw new IllegalArgumentException("请填写可访问的频道主帖链接");
        Session session = capture();
        return submit(session, () -> {
            var post = reader.probeDownloadSource(link);
            requireCurrent(session);
            if (post == null || !session.account().equals(post.accountId())) throw new IllegalArgumentException("来源账号已变化，请重新预览");
            Page page = post.canReadComments() ? reader.comments(post, null) : null;
            requireCurrent(session);
            var result = result(UUID.randomUUID().toString(), post, page, page == null ? 0 : 1, 0, null, post.warnings());
            prune(session);
            while (previews.size() >= LIMIT) previews.remove(previews.keySet().iterator().next());
            previews.put(result.id(), new Entry(session, result, clock.getAsLong(), remember(Map.of(), result)));
            return result;
        });
    }
    public CompletableFuture<DownloadSourcePreview> next(String id, String cursor, Integer pageNumber) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !validCursor(cursor) || pageNumber == null || pageNumber < 1) throw new IllegalArgumentException("来源预览位置无效，请重新预览");
        Session session = capture();
        return submit(session, () -> {
            prune(session);
            Entry entry = previews.get(id);
            if (entry == null || !entry.session().equals(session)) throw new IllegalArgumentException("来源预览已过期或会话已变化，请重新预览");
            var previous = entry.value();
            if (!previous.canContinue() || previous.pageNumber() != pageNumber || !cursor.equals(previous.comments().nextCursor())) {
                throw new IllegalArgumentException("预览位置已变化，请重新预览后继续");
            }
            var page = reader.comments(previous.post(), cursor);
            requireCurrent(session);
            var result = result(id, previous.post(), page, pageNumber + 1, previous.observedMessages(), cursor, previous.warnings());
            previews.put(id, new Entry(session, result, clock.getAsLong(), remember(entry.thumbnails(), result)));
            return result;
        });
    }
    /** Resolve only an observed item. Retain compact grants so appended rows survive advancing the feed. */
    public CompletableFuture<ThumbnailSource> thumbnailSource(String id, Integer pageNumber, String mediaKey) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || pageNumber == null || pageNumber < 0 || mediaKey == null || mediaKey.isBlank() || mediaKey.length() > 512) {
            throw new IllegalArgumentException("来源媒体预览身份无效");
        }
        Session session = capture();
        return submit(session, () -> {
            prune(session);
            Entry entry = previews.get(id);
            if (entry == null || !entry.session().equals(session)) {
                throw new IllegalArgumentException("来源预览已过期，请重新预览");
            }
            var value = entry.value(); var post = value.post();
            var mainMatches = post.mainMedia().stream().filter(media -> mediaKey.equals(media.key())).toList();
            var grant = entry.thumbnails().get(mediaKey);
            Media media;
            if (pageNumber == 0 && mainMatches.size() == 1) media = mainMatches.getFirst();
            else if (grant != null && grant.pageNumber() == pageNumber) media = grant.media();
            else throw new IllegalArgumentException("媒体不属于已读取的来源预览范围");
            boolean main = "MAIN".equals(media.source()) && media.message() != null
                    && post.channelId().equals(media.message().chatId()) && post.mainMessages().stream().anyMatch(ref ->
                        ref.chatId().equals(media.message().chatId()) && ref.messageId().equals(media.message().messageId()));
            boolean comment = "COMMENT".equals(media.source()) && grant != null && media.message() != null
                    && Objects.equals(post.discussionChatId(), media.message().chatId());
            if ((!main && !comment) || !("PHOTO".equals(media.type()) || "VIDEO".equals(media.type()))) {
                throw new IllegalArgumentException("当前媒体不支持缩略图或归属无效");
            }
            return new ThumbnailSource(post, media, pageNumber);
        });
    }
    public record ThumbnailSource(Post post, Media media, int pageNumber) {
        @Override public String toString() { return "ThumbnailSource[observed metadata]"; }
    }
    public record Selection(Post post, List<Media> media) { public Selection { media = List.copyOf(media); } }
    public CompletableFuture<Selection> selection(String id, List<String> keys, boolean includeMain) {
        if (id == null || !id.matches("[0-9a-f-]{36}") || keys == null || keys.isEmpty() || keys.size() > 100
                || keys.stream().anyMatch(key -> key == null || key.isBlank() || key.length() > 512)
                || new java.util.HashSet<>(keys).size() != keys.size()) throw new IllegalArgumentException("请选择1至100个已加载媒体");
        Session session = capture();
        return submit(session, () -> {
            prune(session); var entry = previews.get(id);
            if (entry == null || !entry.session().equals(session)) throw new IllegalArgumentException("来源预览已过期，请重新预览后创建任务");
            var post = entry.value().post(); var media = new java.util.ArrayList<Media>();
            for (var key : keys) {
                var main = post.mainMedia().stream().filter(item -> item.key().equals(key)).findFirst().orElse(null);
                if (main != null && includeMain) media.add(main);
                else if (main == null && entry.thumbnails().containsKey(key)) media.add(entry.thumbnails().get(key).media());
                else throw new IllegalArgumentException("选中的媒体不在当前已读范围或已过期，请重新预览");
            }
            return new Selection(post, media);
        });
    }
    private static Map<String, ThumbnailGrant> remember(Map<String, ThumbnailGrant> previous, DownloadSourcePreview value) {
        var grants = new LinkedHashMap<>(previous);
        if (value.comments() != null) for (var media : value.comments().media()) {
            if (!"COMMENT".equals(media.source())
                    || media.message() == null || value.comments().messages().stream().noneMatch(ref ->
                        ref.chatId().equals(media.message().chatId()) && ref.messageId().equals(media.message().messageId()))) continue;
            var ref = new io.github.telegramenhance.library.ResourceModels.MessageRef(media.message().chatId(), media.message().messageId(), "");
            var compact = new Media(media.key(), ref, media.source(), media.type(), "", 0, "");
            grants.remove(media.key()); grants.put(media.key(), new ThumbnailGrant(compact, value.pageNumber()));
        }
        while (grants.size() > 1024) grants.remove(grants.keySet().iterator().next());
        return java.util.Collections.unmodifiableMap(grants);
    }
    private DownloadSourcePreview result(String id, io.github.telegramenhance.library.ResourceModels.Post post,
            Page page, int pageNumber, long seen, String previousCursor, List<String> previousWarnings) {
        var warnings = new LinkedHashSet<>(previousWarnings);
        boolean exhausted = false, canContinue = false;
        if (page != null) {
            if (!post.key().equals(page.postKey()) || page.messages().size() > 50 || page.messageCount() != page.messages().size()
                    || page.messages().stream().anyMatch(message -> !Objects.equals(post.discussionChatId(), message.chatId()))) {
                throw new IllegalArgumentException("来源评论响应身份无效，请重新预览");
            }
            warnings.addAll(page.warnings());
            seen = Math.addExact(seen, page.messageCount());
            exhausted = page.exhausted();
            canContinue = !exhausted && validCursor(page.nextCursor())
                    && (previousCursor == null || Long.parseLong(page.nextCursor()) < Long.parseLong(previousCursor));
            if (!exhausted && !canContinue) warnings.add("本页没有取得可继续的评论位置，预览已暂停；请重新预览来源。");
        }
        return new DownloadSourcePreview(id, post, page, pageNumber, seen, exhausted, canContinue, List.copyOf(warnings));
    }
    private void prune(Session session) {
        long now = clock.getAsLong();
        Iterator<Entry> entries = previews.values().iterator();
        while (entries.hasNext()) {
            Entry value = entries.next();
            if (!value.session().equals(session) || now - value.touchedAt() >= TTL) entries.remove();
        }
    }
    private static boolean validCursor(String value) {
        try { return value != null && Long.parseLong(value) > 0 && Long.toString(Long.parseLong(value)).equals(value); }
        catch (NumberFormatException ignored) { return false; }
    }
    private Session capture() { var session = new Session(telegram.accountId(), telegram.sessionGeneration()); requireCurrent(session); return session; }
    private void requireCurrent(Session session) {
        if (!Objects.equals(session.account(), telegram.accountId()) || session.generation() != telegram.sessionGeneration()) {
            throw new IllegalArgumentException("Telegram 账号或会话已变化，请重新预览来源");
        }
    }
    private <T> CompletableFuture<T> submit(Session session, Supplier<T> action) {
        return work.readWork(() -> { requireCurrent(session); var result = action.get(); requireCurrent(session); return result; });
    }
    private record Session(String account, long generation) { }
    private record Entry(Session session, DownloadSourcePreview value, long touchedAt, Map<String, ThumbnailGrant> thumbnails) { }
    private record ThumbnailGrant(Media media, int pageNumber) { }
}
