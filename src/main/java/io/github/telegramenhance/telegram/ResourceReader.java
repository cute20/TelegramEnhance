package io.github.telegramenhance.telegram;

import io.github.telegramenhance.library.ResourceModels.*;
import it.tdlight.jni.TdApi;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Only this adapter sees TDLib types. It never sends messages, joins chats, or downloads files. */
@Service
public class ResourceReader {
    interface Gateway { <R extends TdApi.Object> CompletableFuture<R> read(TdApi.Function<R> query); }
    private final Gateway gateway;
    private final Supplier<String> account;
    @org.springframework.beans.factory.annotation.Autowired
    public ResourceReader(TelegramService telegram) { this(telegram::read, telegram::accountId); }
    ResourceReader(Gateway gateway, Supplier<String> account) { this.gateway = gateway; this.account = account; }
    private <R extends TdApi.Object> R call(TdApi.Function<R> query) {
        try { return gateway.read(query).get(16, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalArgumentException("读取已中断，请重试"); }
        catch (ExecutionException | TimeoutException e) { throw new IllegalArgumentException("Telegram 读取未完成，请检查连接、访问权限和链接后重试"); }
    }
    public Post probe(String rawLink) {
        return probe(rawLink, true);
    }
    /** External sources are read-only and never enter the library through this adapter. */
    public Post probeDownloadSource(String rawLink) {
        return probe(rawLink, false);
    }
    /** A saved single-post rule has the same bounded/session-guarded read budget as channel scans. */
    public Post probeDownloadSource(String rawLink,long budgetMillis,Runnable guard) {
        return boundedDownloadReader(budgetMillis,guard).probe(rawLink,false);
    }
    private Post probe(String rawLink, boolean requireManagedChannel) {
        String accountId = account.get();
        ensureAccount(accountId);
        String link = validateLink(rawLink);
        var resolved = call(new TdApi.GetMessageLinkInfo(link));
        var main = resolved == null ? null : resolved.message;
        if (main == null || !main.isChannelPost || main.id <= 0 || main.chatId == 0 || main.chatId != resolved.chatId) throw new IllegalArgumentException("请填写频道主帖链接；评论、群组消息和不可访问消息不能作为主帖");
        return assemble(accountId, main, requireManagedChannel);
    }
    /** Resolve an explicitly selected channel post, then reuse the same ownership/album checks. */
    public Post channelPost(String channelId, String messageId) {
        return channelPost(channelId,messageId,true);
    }
    /** Download scans reuse full album text, without granting library management permissions. */
    public Post downloadChannelPost(String channelId,String messageId,long budgetMillis) {
        return downloadChannelPost(channelId,messageId,budgetMillis,()->{});
    }
    public Post downloadChannelPost(String channelId,String messageId,long budgetMillis,Runnable guard) {
        return boundedDownloadReader(budgetMillis,guard).channelPost(channelId,messageId,false);
    }
    public Page downloadComments(Post post,String cursor,long budgetMillis,Runnable guard){return boundedDownloadReader(budgetMillis,guard).comments(post,cursor);}
    private ResourceReader boundedDownloadReader(long budgetMillis,Runnable guard){
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(25_000,Math.max(1,budgetMillis)));
        Gateway bounded=new Gateway(){public <R extends TdApi.Object> CompletableFuture<R> read(TdApi.Function<R> query){
            guard.run();
            long left=deadline-System.nanoTime();
            if(left<=0)return CompletableFuture.failedFuture(new IllegalArgumentException("本次主帖读取达到时间上限"));
            return gateway.read(query).thenApply(value->{guard.run();return value;}).orTimeout(left,TimeUnit.NANOSECONDS);
        }};
        return new ResourceReader(bounded,account);
    }
    private Post channelPost(String channelId, String messageId,boolean managedOnly) {
        long chat, message;
        try {
            if (channelId == null || messageId == null || channelId.length() > 20 || messageId.length() > 19) throw new NumberFormatException();
            chat = Long.parseLong(channelId); message = Long.parseLong(messageId);
            if (chat == 0 || message <= 0 || !Long.toString(chat).equals(channelId) || !Long.toString(message).equals(messageId)) throw new NumberFormatException();
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException("频道或主帖消息标识无效"); }
        String accountId = account.get();
        ensureAccount(accountId);
        var main = call(new TdApi.GetMessage(chat, message));
        ensureAccount(accountId);
        if (main == null || main.chatId != chat || main.id != message || !main.isChannelPost) {
            throw new IllegalArgumentException("未取得所选频道的原主帖，请检查访问权限后重试");
        }
        return assemble(accountId, main,managedOnly);
    }
    /** Reads the saved identity through TDLib; GetMessage may return its current local cache. */
    public Post refresh(Post previous) {
        if (previous == null) throw new IllegalArgumentException("请先选择已保存的主帖");
        ensureAccount(previous.accountId());
        long chatId, messageId;
        try {
            chatId = Long.parseLong(previous.channelId());
            messageId = Long.parseLong(previous.mainMessageId());
            if (chatId == 0 || messageId <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) { throw new IllegalArgumentException("已保存的主帖身份无效，请重新添加主帖"); }
        var main = call(new TdApi.GetMessage(chatId, messageId));
        if (main == null || main.chatId != chatId || main.id != messageId || !main.isChannelPost) throw new IllegalArgumentException("未取得原频道的原主帖，请检查访问权限后重试");
        if (!postKey(previous.accountId(), main.chatId, main.id, main.mediaAlbumId).equals(previous.key())) throw new IllegalArgumentException("主帖的相册身份发生变化，已保留原索引，请重新核对主帖");
        var refreshed = assemble(previous.accountId(), main);
        if (!previous.key().equals(refreshed.key())) throw new IllegalArgumentException("主帖身份发生变化，已保留原索引，请重新核对主帖");
        return refreshed;
    }
    private Post assemble(String accountId, TdApi.Message main) {
        return assemble(accountId, main, true);
    }
    private Post assemble(String accountId, TdApi.Message main, boolean requireManagedChannel) {
        var chat = call(new TdApi.GetChat(main.chatId));
        if (chat == null || chat.id != main.chatId || !(chat.type instanceof TdApi.ChatTypeSupergroup group) || !group.isChannel) throw new IllegalArgumentException("该链接不是频道主帖");
        if (requireManagedChannel) {
            var supergroup = call(new TdApi.GetSupergroup(group.supergroupId));
            if (supergroup == null || (!(supergroup.status instanceof TdApi.ChatMemberStatusCreator) && !(supergroup.status instanceof TdApi.ChatMemberStatusAdministrator))) throw new IllegalArgumentException("我的资源库仅添加自己创建或管理的频道；外部下载来源请在新建传输中填写");
        }
        List<String> discussionWarnings = new ArrayList<>();
        TdApi.MessageThreadInfo thread = null;
        try {
            thread = call(new TdApi.GetMessageThread(main.chatId, main.id));
            if (!validDiscussionThread(thread)) discussionWarnings.add("没有取得可验证的讨论根消息，评论范围尚不可用。");
        } catch (IllegalArgumentException e) { discussionWarnings.add("讨论线程不可读取或未开放评论；主帖已读取，评论范围尚不可用。"); }
        if (!validDiscussionThread(thread)) thread = null;
        var album = new AlbumReader(gateway).read(main, chat, thread);
        List<TdApi.Message> members = album.members();
        long canonical = members.getFirst().id;
        // Channel history starts at the newest album member, but Telegram may expose the
        // discussion only through the oldest (canonical) member found by the album scan.
        if (thread == null && main.mediaAlbumId != 0 && canonical != main.id) {
            try {
                var canonicalThread = call(new TdApi.GetMessageThread(main.chatId, canonical));
                if (validDiscussionThread(canonicalThread)) {
                    album = AlbumReader.validateThreadOrigins(album, main.chatId, canonicalThread);
                    boolean sameChannel = Arrays.stream(canonicalThread.messages).allMatch(message ->
                        message.forwardInfo == null || !(message.forwardInfo.origin instanceof TdApi.MessageOriginChannel origin)
                            || origin.chatId == main.chatId && origin.messageId > 0);
                    if (sameChannel) {
                        thread = canonicalThread;
                        discussionWarnings.clear();
                    }
                }
            } catch (IllegalArgumentException ignored) { /* Keep the original unavailable warning. */ }
        }
        boolean readable = thread != null;
        String discussionChat = readable ? Long.toString(thread.chatId) : null;
        String root = readable ? Long.toString(thread.messageThreadId) : null;
        List<MessageRef> roots = readable ? references(Arrays.asList(thread.messages), true) : List.of();
        List<String> warnings = new ArrayList<>(album.warnings());
        List<MessageRef> mainRefs = references(members, false); List<Media> mainMedia = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) mainMedia.addAll(MediaMapper.map(accountId, members.get(i), "MAIN", mainRefs.get(i), warnings));
        String mainCoverage = album.complete() && warnings.isEmpty() ? "COMPLETE" : "PARTIAL";
        warnings.addAll(discussionWarnings);
        String text = members.stream().map(MediaMapper::text).filter(s -> !s.isBlank()).distinct().collect(java.util.stream.Collectors.joining("\n"));
        String title = text.lines().findFirst().orElse("主帖 " + canonical); if (title.length() > 100) title = title.substring(0, 100);
        ensureAccount(accountId);
        String key = postKey(accountId, main.chatId, canonical, main.mediaAlbumId);
        return new Post(key, accountId, Long.toString(main.chatId), chat.title,
                Long.toString(canonical), title, text, Long.toString(main.mediaAlbumId), mainRefs, mainMedia, discussionChat, root, roots,
                readable, readable ? "PARTIAL" : "UNAVAILABLE", List.copyOf(new LinkedHashSet<>(warnings)),
                new AlbumEvidence(album.complete(), album.method(), album.newerBoundaryId(), album.olderBoundaryId()), mainCoverage);
    }
    private static boolean validDiscussionThread(TdApi.MessageThreadInfo thread) {
        return thread != null && thread.chatId != 0 && thread.messageThreadId > 0
                && thread.messages != null && thread.messages.length > 0
                && Arrays.stream(thread.messages).allMatch(message -> message != null
                    && message.chatId == thread.chatId && message.id > 0);
    }
    public Page comments(Post post, String cursor) {
        ensureAccount(post.accountId());
        if (!post.canReadComments()) throw new IllegalArgumentException("该主帖尚未取得可访问的讨论线程，请先重新读取主帖");
        long from = parseCursor(cursor);
        var freshThread = call(new TdApi.GetMessageThread(Long.parseLong(post.channelId()), Long.parseLong(post.mainMessageId())));
        if (freshThread == null || freshThread.messages == null || !Long.toString(freshThread.chatId).equals(post.discussionChatId()) || !Long.toString(freshThread.messageThreadId).equals(post.discussionRootMessageId())) throw new IllegalArgumentException("讨论映射发生变化或无法验证，请重新读取主帖后再继续");
        List<String> scanWarnings = new ArrayList<>();
        if (freshThread.messages.length == 0) scanWarnings.add("本轮未取得可验证的讨论根消息，评论范围尚未完全验证。");
        Set<String> rootIds = new HashSet<>();
        for (var ref : post.discussionRoots()) rootIds.add(ref.chatId() + ":" + ref.messageId());
        for (var m : freshThread.messages) {
            if (m == null || m.chatId != freshThread.chatId || m.id <= 0) scanWarnings.add("讨论根消息包含缺失或不属于当前讨论组的身份，评论范围尚未完全验证。");
            else rootIds.add(m.chatId + ":" + m.id);
        }
        var result = call(new TdApi.GetMessageThreadHistory(freshThread.chatId, freshThread.messageThreadId, from, 0, 50));
        if (result == null || result.messages == null) {
            scanWarnings.add("评论历史响应缺少消息清单，未标记为完整，请稍后重试。");
            ensureAccount(post.accountId());
            return commentPage(post.key(), List.of(), List.of(), cursor, false, List.of(), scanWarnings);
        }
        // A short page is not EOF. Confirm an empty server page at the same cursor before marking history exhausted.
        if (result.messages.length == 0) result = call(new TdApi.GetMessageThreadHistory(freshThread.chatId, freshThread.messageThreadId, from, 0, 50));
        if (result == null || result.messages == null) {
            scanWarnings.add("评论历史响应缺少消息清单，未标记为完整，请稍后重试。");
            ensureAccount(post.accountId());
            return commentPage(post.key(), List.of(), List.of(), cursor, false, List.of(), scanWarnings);
        }
        if (result.messages.length == 0) {
            ensureAccount(post.accountId());
            return commentPage(post.key(), List.of(), List.of(), cursor, true, List.of(), scanWarnings);
        }
        List<Media> media = new ArrayList<>(); List<TdApi.Message> comments = new ArrayList<>();
        long next = from == 0 ? Long.MAX_VALUE : from;
        Set<String> seen = new HashSet<>();
        for (var m : result.messages) {
            if (m == null || m.chatId != freshThread.chatId || m.id <= 0) { scanWarnings.add("服务返回了不属于当前讨论组或缺失身份的消息，媒体清单不完整。"); continue; }
            if (from != 0 && m.id >= from) continue;
            next = Math.min(next, m.id);
            if (rootIds.contains(m.chatId + ":" + m.id) || !seen.add(m.chatId + ":" + m.id)) continue;
            comments.add(m);
        }
        List<MessageRef> refs = references(comments, true);
        List<CommentObservation> observations = new ArrayList<>();
        for (int i = 0; i < comments.size(); i++) {
            List<String> messageWarnings = new ArrayList<>();
            media.addAll(MediaMapper.map(post.accountId(), comments.get(i), "COMMENT", refs.get(i), messageWarnings));
            observations.add(new CommentObservation(refs.get(i), messageWarnings.isEmpty(), distinct(messageWarnings)));
        }
        boolean advanced = next != Long.MAX_VALUE && next != from;
        if (!advanced) scanWarnings.add("本页游标未前进，未标记为完整；请稍后重试或重新读取主帖。");
        ensureAccount(post.accountId());
        return commentPage(post.key(), media, refs, advanced ? Long.toString(next) : cursor, false, observations, scanWarnings);
    }
    private static Page commentPage(String key, List<Media> media, List<MessageRef> refs, String cursor,
                                    boolean exhausted, List<CommentObservation> observations, List<String> scanWarnings) {
        List<String> warnings = new ArrayList<>(scanWarnings);
        for (var observation : observations) warnings.addAll(observation.warnings());
        String coverage = exhausted && warnings.isEmpty() ? "COMPLETE" : "PARTIAL";
        var allWarnings = distinct(warnings);
        return new Page(key, media, refs, refs.size(), cursor, exhausted, coverage, allWarnings,
                coverage, allWarnings, observations, distinct(scanWarnings));
    }
    private static List<String> distinct(List<String> values) { return List.copyOf(new LinkedHashSet<>(values)); }
    private static String postKey(String accountId, long chatId, long messageId, long albumId) {
        return accountId + ":" + chatId + ":" + (albumId == 0 ? messageId : "album:" + albumId);
    }
    private List<MessageRef> references(List<TdApi.Message> messages, boolean inThread) {
        // Resolve links concurrently for the bounded set of main members or at most 50 comments,
        // with one bounded timeout for the batch; a missing link must not cause N sequential waits.
        List<CompletableFuture<MessageRef>> futures = new ArrayList<>();
        for (var message : messages) {
            var fallback = new MessageRef(Long.toString(message.chatId), Long.toString(message.id), "");
            futures.add(gateway.read(new TdApi.GetMessageLink(message.chatId, message.id, 0, 0, "", false, inThread))
                    .handle((result, failure) -> failure == null && result != null ? new MessageRef(fallback.chatId(), fallback.messageId(), result.link == null ? "" : result.link) : fallback)
                    .completeOnTimeout(fallback, 2, TimeUnit.SECONDS));
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }
    private void ensureAccount(String expected) { if (expected == null || expected.isBlank() || !expected.equals(account.get())) throw new IllegalArgumentException("账号已改变，请刷新页面后重试"); }
    static String validateLink(String raw) {
        try {
            if (raw == null || raw.length() > 2048) throw new IllegalArgumentException();
            var uri = URI.create(raw.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !("t.me".equalsIgnoreCase(uri.getHost()) || "telegram.me".equalsIgnoreCase(uri.getHost())) || uri.getUserInfo() != null || uri.getPort() != -1) throw new IllegalArgumentException();
            // Reject comment/thread links, which resolve to a discussion message rather than the canonical channel post.
            if (uri.getRawQuery() != null && Arrays.stream(uri.getRawQuery().split("&")).anyMatch(p -> p.startsWith("comment=") || p.startsWith("thread="))) throw new IllegalArgumentException();
            return uri.toString();
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("请填写 https://t.me/… 的频道主帖链接，不含 comment/thread 参数"); }
    }
    static long parseCursor(String value) {
        if (value == null || value.isBlank()) return 0;
        try { long parsed = Long.parseLong(value); if (parsed <= 0) throw new NumberFormatException(); return parsed; }
        catch (NumberFormatException e) { throw new IllegalArgumentException("评论分页游标无效，请重新读取首轮"); }
    }
}
