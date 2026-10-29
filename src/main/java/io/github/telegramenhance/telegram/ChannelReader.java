package io.github.telegramenhance.telegram;

import io.github.telegramenhance.library.ChannelImportModels.*;
import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import io.github.telegramenhance.library.ResourceRepository;
import it.tdlight.client.TelegramError;
import it.tdlight.jni.TdApi;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Bounded metadata discovery. Never joins chats, saves posts, or enumerates comments. */
@Service
public final class ChannelReader {
    interface Gateway { <R extends TdApi.Object> CompletableFuture<R> read(long session, TdApi.Function<R> query); }
    private static final int CHAT_LIMIT = 200;
    private static final int POST_LIMIT = 10;
    private static final int HISTORY_PAGES = 6;
    private static final Set<String> POST_CONTENT = Set.of("MessageText", "MessagePhoto", "MessageVideo",
            "MessageAnimation", "MessageAudio", "MessageDocument", "MessageVoiceNote", "MessageVideoNote",
            "MessageSticker", "MessagePoll", "MessageLocation", "MessageVenue", "MessageContact", "MessageDice",
            "MessageGame", "MessageInvoice", "MessagePaidMedia", "MessageStory", "MessageUnsupported",
            "MessageGiveaway", "MessageGiveawayWinners", "MessageChecklist", "MessageExpiredPhoto",
            "MessageExpiredVideo", "MessageExpiredVideoNote", "MessageExpiredVoiceNote");
    private final Gateway gateway;
    private final Supplier<String> account;
    private final LongSupplier generation;
    private final Supplier<List<String>> indexedChannels;
    private final long budgetMillis;

    @Autowired
    public ChannelReader(TelegramService telegram, ResourceRepository repository) {
        this(new Gateway() {
            public <R extends TdApi.Object> CompletableFuture<R> read(long session, TdApi.Function<R> query) {
                return telegram.readInSession(session, query);
            }
        }, telegram::accountId, telegram::sessionGeneration,
                () -> repository.indexedChannelIds(telegram.accountId(), 101), 35_000);
    }

    ChannelReader(Gateway gateway, Supplier<String> account, LongSupplier generation,
            Supplier<List<String>> indexedChannels, long budgetMillis) {
        this.gateway = gateway; this.account = account; this.generation = generation;
        this.indexedChannels = indexedChannels; this.budgetMillis = budgetMillis;
    }

    public ManagedChannels channels() {
        return channels(true);
    }
    /** Independent download discovery; library callers still always require management. */
    public ManagedChannels downloadChannels() { return channels(false); }
    private ManagedChannels channels(boolean managedOnly) {
        Context context = context();
        Set<Long> ids = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        boolean partial = false;
        // Existing indexed channels remain discoverable even when older than the bounded chat lists.
        List<String> seeds = indexedChannels.get();
        ensure(context);
        if (seeds.size() > 100) partial = true;
        for (String seed : seeds.stream().limit(100).toList()) ids.add(channelId(seed));
        for (TdApi.ChatList list : List.of(new TdApi.ChatListMain(), new TdApi.ChatListArchive())) {
            boolean end = false;
            for (int attempt = 0; attempt < 2; attempt++) {
                try { call(context, new TdApi.LoadChats(list, 100)); }
                catch (ReadFailure failure) {
                    ensure(context);
                    if (failure.code == 404) end = true;
                    else warnings.add("部分聊天列表未能加载，请检查连接后刷新频道。");
                    break;
                }
            }
            partial |= !end;
            try {
                TdApi.Chats chats = call(context, new TdApi.GetChats(list, CHAT_LIMIT));
                if (chats == null || chats.chatIds == null || chats.chatIds.length > CHAT_LIMIT) throw new ReadFailure(-1);
                partial |= chats.chatIds.length >= CHAT_LIMIT;
                for (long id : chats.chatIds) if (id != 0) ids.add(id);
            } catch (ReadFailure failure) {
                ensure(context); partial = true;
                warnings.add("部分聊天列表未能读取，当前频道可能未列全。");
            }
        }
        List<ManagedChannel> items = new ArrayList<>();
        for (long id : ids) {
            if (remaining(context) <= 0) { partial = true; warnings.add("本次频道加载已达到时间上限，可刷新后继续核对。"); break; }
            try {
                TdApi.Chat chat = call(context, new TdApi.GetChat(id));
                if (chat == null || chat.id != id) throw new ReadFailure(-1);
                if (!(chat.type instanceof TdApi.ChatTypeSupergroup type) || !type.isChannel) continue;
                TdApi.Supergroup group = call(context, new TdApi.GetSupergroup(type.supergroupId));
                if (group == null || group.id != type.supergroupId || !group.isChannel) throw new ReadFailure(-1);
                if (managedOnly ? managed(group.status) : readable(group)) items.add(new ManagedChannel(Long.toString(id), title(chat.title, "未命名频道", 200)));
            } catch (ReadFailure failure) {
                ensure(context); partial = true;
                warnings.add(managedOnly ? "部分频道的管理权限未能核对，可刷新后重试。" : "部分频道的访问权限未能核对，可刷新后重试。");
            }
        }
        ensure(context);
        if (partial) warnings.add(managedOnly ? "仅列出本次加载范围中可确认由你创建或管理的频道，可能未包含全部频道。" : "频道列表仅覆盖本次加载范围；未列出的可读频道可填写频道链接或用户名。");
        return new ManagedChannels(items, managedOnly ? "MANAGED_CHANNELS" : "READABLE_CHANNELS", partial, distinct(warnings));
    }

    public Preview preview(String rawChannelId) {
        HistoryPage page = historyPage(rawChannelId, null);
        List<String> warnings = new ArrayList<>(page.warnings());
        if (page.hasMore() && page.items().size() < POST_LIMIT) warnings.add("本次预览范围有限，可在主帖分页中继续浏览。");
        return new Preview(page.channel(), page.items(), "RECENT_BATCH", POST_LIMIT, distinct(warnings));
    }

    public HistoryPage historyPage(String rawChannelId, String rawCursor) {
        return historyPage(rawChannelId, rawCursor, true);
    }
    public HistoryPage downloadHistoryPage(String rawChannelId, String rawCursor) {
        return historyPage(rawChannelId, rawCursor, false);
    }
    private HistoryPage historyPage(String rawChannelId, String rawCursor, boolean managedOnly) {
        long id = channelId(rawChannelId);
        Context context = context();
        HistoryCursor start = decodeCursor(rawCursor, context.account(), id);
        try {
            ManagedChannel channel = requireChannel(context, id, managedOnly);
            Map<String, Candidate> candidates = new LinkedHashMap<>();
            List<String> warnings = new ArrayList<>();
            long cursor = start.from();
            long consumed = cursor;
            long skipAlbum = start.skipAlbum();
            long tailAlbum = skipAlbum;
            for (int page = 0; page < HISTORY_PAGES; page++) {
                TdApi.Messages response = history(context, id, cursor);
                if (response.messages.length == 0) response = history(context, id, cursor);
                if (response.messages.length == 0) return historyPage(context, id, channel, candidates, consumed, tailAlbum, true, false, warnings);
                long next = cursor == 0 ? Long.MAX_VALUE : cursor;
                long previous = Long.MAX_VALUE;
                for (TdApi.Message message : response.messages) {
                    if (message == null || message.chatId != id || message.id <= 0 || message.id >= previous) {
                        throw new IllegalArgumentException("频道历史的消息身份或顺序无法核对，请重新预览。");
                    }
                    previous = message.id;
                    if (cursor != 0 && message.id >= cursor) continue;
                    boolean ordinary = message.isChannelPost && message.content != null && POST_CONTENT.contains(message.content.getClass().getSimpleName());
                    if (ordinary && (message.sendingState != null || message.schedulingState != null || message.ephemeralContent != null)) {
                        warnings.add("存在尚未正式发送或临时消息，已跳过；本轮范围需结合提示核对。");
                        ordinary = false;
                    }
                    if (!ordinary || (skipAlbum != 0 && message.mediaAlbumId == skipAlbum)) {
                        next = Math.min(next, message.id); consumed = message.id;
                        continue;
                    }
                    skipAlbum = 0;
                    String key = message.mediaAlbumId == 0 ? "message:" + message.id : "album:" + message.mediaAlbumId;
                    // The first member of the next logical post has NOT been consumed.
                    if (!candidates.containsKey(key) && candidates.size() == POST_LIMIT) {
                        return historyPage(context, id, channel, candidates, consumed, tailAlbum, false, true, warnings);
                    }
                    next = Math.min(next, message.id); consumed = message.id; tailAlbum = message.mediaAlbumId;
                    if (message.content.getClass().getSimpleName().startsWith("MessageExpired")) warnings.add("存在已过期媒体主帖，导入后媒体可能无法展开。");
                    String text = MediaMapper.text(message).strip();
                    Candidate existing = candidates.get(key);
                    if (existing == null) candidates.put(key, new Candidate(Long.toString(message.id),
                            message.mediaAlbumId == 0 ? null : Long.toString(message.mediaAlbumId), title(text, "无文字主帖", 100)));
                    else if (!text.isBlank()) candidates.put(key,
                            new Candidate(existing.messageId(), existing.albumId(), title(text, "无文字主帖", 100)));
                }
                if (next == Long.MAX_VALUE || next == cursor) {
                    warnings.add("频道历史本次没有继续推进，请刷新后重试；尚未确认历史边界。");
                    return historyPage(context, id, channel, candidates, consumed, tailAlbum, false, false, warnings);
                }
                cursor = next;
                if (candidates.size() == POST_LIMIT && candidates.values().stream().reduce((a, b) -> b).orElseThrow().albumId() == null) {
                    return historyPage(context, id, channel, candidates, consumed, tailAlbum, false, true, warnings);
                }
            }
            return historyPage(context, id, channel, candidates, consumed, tailAlbum, false, true, warnings);
        } catch (ReadFailure failure) {
            ensure(context);
            throw new IllegalArgumentException("频道预览未完成，请检查连接和频道权限后重试。");
        }
    }

    public ManagedChannel managedChannel(String rawChannelId) {
        long id = channelId(rawChannelId);
        Context context = context();
        try { return requireManaged(context, id); }
        catch (ReadFailure failure) {
            ensure(context);
            throw new IllegalArgumentException("频道管理权限未能确认，请检查连接后重试。");
        }
    }

    private ManagedChannel requireManaged(Context context, long id) {
        return requireChannel(context, id, true);
    }
    private ManagedChannel requireChannel(Context context, long id, boolean managedOnly) {
        TdApi.Chat chat = call(context, new TdApi.GetChat(id));
        if (chat == null || chat.id != id || !(chat.type instanceof TdApi.ChatTypeSupergroup type) || !type.isChannel) {
            throw new IllegalArgumentException(managedOnly ? "请选择自己的频道。" : "请选择当前账号可读取的频道，群组不是频道来源。");
        }
        TdApi.Supergroup group = call(context, new TdApi.GetSupergroup(type.supergroupId));
        if (group == null || group.id != type.supergroupId || !group.isChannel || !(managedOnly ? managed(group.status) : readable(group))) {
            throw new IllegalArgumentException(managedOnly ? "只能预览自己创建或管理的频道。" : "当前账号无权读取这个频道；不会自动加入频道。");
        }
        return new ManagedChannel(Long.toString(id), title(chat.title, "未命名频道", 200));
    }

    /** Resolves only explicit IDs, public names, or a real post link. Invite links never join. */
    public ManagedChannel downloadChannel(String source) {
        if (source == null || source.isBlank() || source.length() > 2048) throw new IllegalArgumentException("请选择频道或填写频道链接、用户名；私有频道可填写一条主帖链接。");
        String value=source.strip(); Context context=context();
        try {
            long id;
            if(value.matches("-?[1-9][0-9]{0,19}")) id=channelId(value);
            else {
                String username=value.startsWith("@")?value.substring(1):null;
                if(username==null){
                    URI uri;try{uri=URI.create(value);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("请填写有效的Telegram频道链接。");}
                    if(!"https".equals(uri.getScheme())||!Set.of("t.me","telegram.me").contains(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()!=-1||uri.getFragment()!=null)throw new IllegalArgumentException("请填写有效的Telegram频道链接。");
                    String path=uri.getPath();
                    if(path!=null&&path.matches("/(?:c/[1-9][0-9]*|[A-Za-z0-9_]{3,64})/[1-9][0-9]*/?")){
                        var resolved=call(context,new TdApi.GetMessageLinkInfo(value));
                        if(resolved==null||resolved.message==null||!resolved.message.isChannelPost||resolved.message.id<=0||resolved.message.chatId!=resolved.chatId)throw new IllegalArgumentException("链接未指向可访问的频道主帖。");
                        return requireChannel(context,resolved.chatId,false);
                    }
                    if(uri.getQuery()!=null||path==null||!path.matches("/[A-Za-z0-9_]{3,64}/?"))throw new IllegalArgumentException("私有频道请从列表选择或填写一条可访问主帖的链接；不接受邀请链接。");
                    username=path.substring(1).replaceFirst("/$","");
                }
                if(!username.matches("[A-Za-z0-9_]{3,64}")||Set.of("joinchat","addlist").contains(username.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("请填写频道用户名；不会自动加入邀请链接。");
                var chat=call(context,new TdApi.SearchPublicChat(username));
                if(chat==null||chat.id==0)throw new IllegalArgumentException("未找到可访问的频道。");id=chat.id;
            }
            return requireChannel(context,id,false);
        }catch(ReadFailure failure){ensure(context);throw new IllegalArgumentException("频道来源未能读取，请检查连接和访问权限。");}
    }
    private static boolean readable(TdApi.Supergroup group){
        if(group.status instanceof TdApi.ChatMemberStatusBanned||group.status==null)return false;
        if(managed(group.status)||group.status instanceof TdApi.ChatMemberStatusMember||group.status instanceof TdApi.ChatMemberStatusRestricted restricted&&restricted.isMember)return true;
        return group.usernames!=null&&group.usernames.activeUsernames!=null&&Arrays.stream(group.usernames.activeUsernames).anyMatch(s->s!=null&&!s.isBlank());
    }

    private TdApi.Messages history(Context context, long id, long cursor) {
        TdApi.Messages result = call(context, new TdApi.GetChatHistory(id, cursor, 0, 50, false));
        if (result == null || result.messages == null || result.messages.length > 50) throw new ReadFailure(-1);
        return result;
    }
    private HistoryPage historyPage(Context context, long id, ManagedChannel channel, Map<String, Candidate> items,
            long consumed, long tailAlbum, boolean exhausted, boolean hasMore, List<String> warnings) {
        ensure(context);
        String next = hasMore && consumed > 0 ? encodeCursor(context.account(), id, consumed, tailAlbum) : null;
        return new HistoryPage(channel, List.copyOf(items.values()), "CHANNEL_PAGE", POST_LIMIT, next, exhausted, hasMore && next != null, distinct(warnings));
    }
    private static String encodeCursor(String account, long channel, long from, long skipAlbum) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("v1|" + account + "|" + channel + "|" + from + "|" + skipAlbum).getBytes(StandardCharsets.UTF_8));
    }
    private static HistoryCursor decodeCursor(String token, String account, long channel) {
        if (token == null) return new HistoryCursor(0, 0);
        try {
            if (token.length() > 512 || token.isBlank()) throw new IllegalArgumentException();
            String[] fields = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 5 || !"v1".equals(fields[0]) || !account.equals(fields[1]) || !Long.toString(channel).equals(fields[2])) throw new IllegalArgumentException();
            long from = Long.parseLong(fields[3]), album = Long.parseLong(fields[4]);
            if (from <= 0 || !Long.toString(from).equals(fields[3]) || !Long.toString(album).equals(fields[4])) throw new IllegalArgumentException();
            return new HistoryCursor(from, album);
        } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("频道分页位置无效或不属于当前账号/频道，请从第一页重新浏览。"); }
    }
    private record HistoryCursor(long from, long skipAlbum) { }
    /** Pure cursor validation shared with the durable import checkpoint writer. */
    public static boolean advancesCursor(String previous, String next, String account, String channel) {
        try {
            long id = channelId(channel);
            HistoryCursor before = decodeCursor(previous, account, id), after = decodeCursor(next, account, id);
            return after.from() > 0 && (before.from() == 0 || after.from() < before.from());
        } catch (IllegalArgumentException | NullPointerException invalid) { return false; }
    }
    private static boolean managed(TdApi.ChatMemberStatus status) {
        return status instanceof TdApi.ChatMemberStatusCreator || status instanceof TdApi.ChatMemberStatusAdministrator;
    }
    private static long channelId(String value) {
        try {
            if (value == null || value.length() > 20) throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id == 0 || !Long.toString(id).equals(value)) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException failure) { throw new IllegalArgumentException("频道标识无效，请重新选择频道。"); }
    }
    private static String title(String text, String fallback, int limit) {
        String first = text == null ? "" : text.strip().lines().filter(line -> !line.isBlank()).findFirst().orElse("");
        if (first.isBlank()) return fallback;
        return first.substring(0, first.offsetByCodePoints(0, Math.min(limit, first.codePointCount(0, first.length()))));
    }
    private static List<String> distinct(List<String> values) { return List.copyOf(new LinkedHashSet<>(values)); }
    private Context context() {
        Context context = new Context(account.get(), generation.getAsLong(), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis));
        ensure(context); return context;
    }
    private long remaining(Context context) { return context.deadline() - System.nanoTime(); }
    private void ensure(Context expected) {
        if (expected.account() == null || expected.account().isBlank() || !expected.account().equals(account.get()) || expected.session() != generation.getAsLong()) {
            throw new IllegalArgumentException("Telegram账号或会话已变化，请刷新后重试。");
        }
    }
    private <R extends TdApi.Object> R call(Context context, TdApi.Function<R> query) {
        ensure(context);
        long nanos = Math.min(remaining(context), TimeUnit.SECONDS.toNanos(16));
        if (nanos <= 0) throw new ReadFailure(-1);
        try {
            R result = gateway.read(context.session(), query).get(nanos, TimeUnit.NANOSECONDS);
            ensure(context); return result;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalArgumentException("频道读取已停止，请重新预览。");
        } catch (ExecutionException | TimeoutException failure) {
            ensure(context);
            Throwable cause = failure.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            throw new ReadFailure(cause instanceof TelegramError error ? error.getErrorCode() : -1);
        }
    }
    private record Context(String account, long session, long deadline) { }
    private static final class ReadFailure extends RuntimeException {
        final int code;
        ReadFailure(int code) { super("频道读取未完成"); this.code = code; }
    }
}
