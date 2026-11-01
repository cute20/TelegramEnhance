package io.github.telegramenhance.telegram;

import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.library.ResourceModels.MessageRef;
import it.tdlight.jni.TdApi;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Bounded private-bot reads and one explicitly selected callback. Never starts a bot or follows URLs. */
@Service
public class BotReader {
    interface Gateway { <R extends TdApi.Object> CompletableFuture<R> read(long session, TdApi.Function<R> request); }
    private final Gateway gateway;
    private final Supplier<String> account;
    private final LongSupplier generation;

    @org.springframework.beans.factory.annotation.Autowired
    public BotReader(TelegramService telegram) {
        this(new Gateway() { public <R extends TdApi.Object> CompletableFuture<R> read(long session, TdApi.Function<R> request) {
            return telegram.readInSession(session, request);
        } }, telegram::accountId, telegram::sessionGeneration);
    }
    BotReader(Gateway gateway, Supplier<String> account, LongSupplier generation) {
        this.gateway = gateway; this.account = account; this.generation = generation;
    }
    public record Target(String chatId, String botUserId, String title) {
        @Override public String toString() { return "BotTarget[redacted]"; }
    }
    public record Button(int row, int column, String text, String type, byte[] data) {
        public Button { data = data == null ? new byte[0] : data.clone(); }
        @Override public byte[] data() { return data.clone(); }
        @Override public String toString() { return "BotButton[row=" + row + ", column=" + column + ", type=" + type + "]"; }
    }
    public record Message(MessageRef ref, String text, List<Media> media, List<Button> buttons) {
        public Message { media = List.copyOf(media); buttons = List.copyOf(buttons); }
        @Override public String toString() { return "BotMessage[redacted]"; }
    }
    public record Snapshot(Target target, List<Message> messages, boolean historyEnd, String nextCursor, String requestBoundary) {
        public Snapshot { messages = List.copyOf(messages); }
        public Snapshot(Target target, List<Message> messages, boolean historyEnd, String nextCursor) { this(target, messages, historyEnd, nextCursor, null); }
        public Snapshot(Target target, List<Message> messages, boolean historyEnd) { this(target, messages, historyEnd, null, null); }
        @Override public String toString() { return "BotSnapshot[messages=" + messages.size() + "]"; }
    }
    private record Context(String account, long session, long deadline) { }

    public Target open(String source, long session) {
        var context = context(session);
        String parsed = source(source);
        TdApi.Chat chat = parsed.matches("[1-9][0-9]{0,18}")
                ? call(context, new TdApi.GetChat(id(parsed))) : call(context, new TdApi.SearchPublicChat(parsed));
        if (parsed.matches("[1-9][0-9]{0,18}") && (chat == null || chat.id != id(parsed))) throw new IllegalArgumentException("机器人会话ID与读取回执不符");
        return target(context, chat, null);
    }
    public Snapshot history(Target expected, String beforeMessageId, long session) {
        var context = context(session);
        long chatId = id(expected == null ? null : expected.chatId());
        Target current = target(context, call(context, new TdApi.GetChat(chatId)), expected);
        long cursor = beforeMessageId == null ? 0 : id(beforeMessageId);
        var history = call(context, new TdApi.GetChatHistory(chatId, cursor, 0, 50, false));
        if (history == null || history.messages == null || history.messages.length > 50) throw new IllegalArgumentException("机器人历史回执无法确认，请重新读取");
        long oldest = Long.MAX_VALUE, boundary = 0;
        for (var message : history.messages) {
            check(context);
            if (message == null || message.chatId != chatId || message.id <= 0) throw new IllegalArgumentException("机器人历史消息身份无法确认");
            if (cursor != 0 && message.id >= cursor) continue;
            oldest = Math.min(oldest, message.id);
            // Keep only an opaque ID; outgoing resource/deep-link text never enters the preview DTO.
            if (message.isOutgoing && !message.isChannelPost && message.sendingState == null
                    && message.senderId instanceof TdApi.MessageSenderUser sender && context.account().equals(Long.toString(sender.userId)))
                boundary = Math.max(boundary, message.id);
        }
        var messages = new LinkedHashMap<Long, Message>(); int buttonBudget = 100;
        for (var message : history.messages) {
            check(context);
            if (cursor != 0 && message.id >= cursor || message.id <= boundary) continue;
            if (!owned(message, current) || messages.containsKey(message.id)) continue;
            var ref = new MessageRef(current.chatId(), Long.toString(message.id), null);
            // Protected and self-destructing content is never offered as a downloadable item.
            var media = message.selfDestructType != null || message.selfDestructIn > 0 || message.ephemeralContent != null
                    ? List.<Media>of() : MediaMapper.map(context.account(), message, "BOT", ref, new ArrayList<>());
            var keyboard = buttonBudget == 0 ? List.<Button>of() : buttons(message);
            if (keyboard.size() > buttonBudget) keyboard = List.copyOf(keyboard.subList(0, buttonBudget));
            buttonBudget -= keyboard.size();
            messages.put(message.id, new Message(ref, MediaMapper.text(message), media, keyboard));
        }
        if (history.messages.length > 0 && oldest == Long.MAX_VALUE) throw new IllegalArgumentException("机器人历史游标未前进，请重新读取");
        check(context);
        return new Snapshot(current, List.copyOf(messages.values()), history.messages.length == 0,
                oldest == Long.MAX_VALUE ? null : Long.toString(oldest), boundary == 0 ? null : Long.toString(boundary));
    }
    public void press(Target expected, String messageId, Button expectedButton, long session) {
        var context = context(session);
        long chatId = id(expected == null ? null : expected.chatId()), message = id(messageId);
        if (expectedButton == null || !"CALLBACK".equals(expectedButton.type()) || expectedButton.row() < 0 || expectedButton.column() < 0
                || expectedButton.data().length < 1 || expectedButton.data().length > 1024) throw new IllegalArgumentException("此按钮不是可核对的机器人分页回调");
        Target current = target(context, call(context, new TdApi.GetChat(chatId)), expected);
        var fresh = call(context, new TdApi.GetMessage(chatId, message));
        if (fresh == null || fresh.id != message || !owned(fresh, current)) throw new IllegalArgumentException("机器人按钮消息身份已变化，请重新读取");
        var matches = buttons(fresh).stream().filter(button -> button.row() == expectedButton.row() && button.column() == expectedButton.column()).toList();
        if (matches.size() != 1 || !sameButton(matches.getFirst(), expectedButton)) throw new IllegalArgumentException("机器人按钮已变化，请刷新后重新选择");
        // This is the sole mutation in this adapter. A failed/unknown answer must never be replayed here.
        var answer = call(context, new TdApi.GetCallbackQueryAnswer(chatId, message, new TdApi.CallbackQueryPayloadData(expectedButton.data())));
        if (answer == null) throw new IllegalArgumentException("机器人翻页回执尚未确认，请刷新核对");
        check(context);
    }
    private Target target(Context context, TdApi.Chat chat, Target expected) {
        if (chat == null || chat.id <= 0 || chat.hasProtectedContent || !(chat.type instanceof TdApi.ChatTypePrivate type) || type.userId <= 0)
            throw new IllegalArgumentException("请选择可访问且允许保存内容的机器人私聊");
        if (expected != null && (!Long.toString(chat.id).equals(expected.chatId()) || !Long.toString(type.userId).equals(expected.botUserId())))
            throw new IllegalArgumentException("机器人会话身份已变化，请重新打开");
        var user = call(context, new TdApi.GetUser(type.userId));
        if (user == null || user.id != type.userId || !(user.type instanceof TdApi.UserTypeBot))
            throw new IllegalArgumentException("所选会话不是机器人私聊");
        // A title rename is not an identity change; retain the preview's already displayed label.
        return expected != null ? expected : new Target(Long.toString(chat.id), Long.toString(type.userId), chat.title == null ? "机器人" : chat.title);
    }
    private static boolean owned(TdApi.Message message, Target target) {
        return message != null && Long.toString(message.chatId).equals(target.chatId()) && message.id > 0
                && !message.isOutgoing && !message.isChannelPost
                && message.senderId instanceof TdApi.MessageSenderUser sender && Long.toString(sender.userId).equals(target.botUserId());
    }
    private static List<Button> buttons(TdApi.Message message) {
        if (!(message.replyMarkup instanceof TdApi.ReplyMarkupInlineKeyboard keyboard) || keyboard.rows == null) return List.of();
        var buttons = new ArrayList<Button>();
        if (keyboard.rows.length > 100) throw new IllegalArgumentException("机器人按钮过多，请在 Telegram 中缩小资源范围");
        for (int row = 0; row < keyboard.rows.length; row++) {
            var columns = keyboard.rows[row]; if (columns == null) continue;
            if (columns.length > 100 || buttons.size() + columns.length > 100) throw new IllegalArgumentException("机器人按钮过多，请在 Telegram 中缩小资源范围");
            for (int column = 0; column < columns.length; column++) {
                var button = columns[column]; if (button == null) continue;
                String text = button.text == null ? "" : button.text;
                boolean callback = button.type instanceof TdApi.InlineKeyboardButtonTypeCallback data && data.data != null && data.data.length > 0 && data.data.length <= 1024;
                buttons.add(new Button(row, column, text, callback ? "CALLBACK" : "UNSUPPORTED",
                        callback ? ((TdApi.InlineKeyboardButtonTypeCallback) button.type).data : null));
            }
        }
        return List.copyOf(buttons);
    }
    private static boolean sameButton(Button current, Button expected) {
        return Objects.equals(current.text(), expected.text()) && "CALLBACK".equals(current.type()) && Arrays.equals(current.data(), expected.data());
    }
    private static String source(String source) {
        if (source == null || source.isBlank() || source.length() > 2048) throw new IllegalArgumentException("请填写机器人 @用户名、链接或已有私聊ID");
        String value = source.trim();
        if (value.matches("[1-9][0-9]{0,18}")) { id(value); return value; }
        if (value.startsWith("@")) value = value.substring(1);
        else if (value.startsWith("https://") || value.startsWith("http://")) {
            URI uri;
            try { uri = URI.create(value); } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("机器人链接格式无效"); }
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !List.of("t.me", "telegram.me").contains(uri.getHost()) || uri.getUserInfo() != null || uri.getPort() != -1
                    || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().matches("/[A-Za-z][A-Za-z0-9_]{3,31}/?"))
                throw new IllegalArgumentException("请先在 Telegram 中打开资源，再填写不带启动参数的机器人用户名链接");
            value = uri.getPath().replace("/", "");
        }
        if (!value.matches("[A-Za-z][A-Za-z0-9_]{3,31}")) throw new IllegalArgumentException("机器人用户名格式无效");
        return value;
    }
    private static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("机器人消息或会话ID无效");
        try { return Long.parseLong(value); } catch (NumberFormatException invalid) { throw new IllegalArgumentException("机器人消息或会话ID超出范围"); }
    }
    private Context context(long session) {
        var context = new Context(account.get(), session, System.nanoTime() + TimeUnit.SECONDS.toNanos(45)); check(context); return context;
    }
    private void check(Context context) {
        if (Thread.currentThread().isInterrupted() || !Objects.equals(context.account(), account.get()) || context.session() != generation.getAsLong())
            throw new IllegalArgumentException("账号或会话已变化，请重新打开机器人来源");
    }
    private <R extends TdApi.Object> R call(Context context, TdApi.Function<R> request) {
        check(context); long remaining = context.deadline() - System.nanoTime();
        if (remaining <= 0) throw new IllegalArgumentException("机器人读取已达到本次时间限制，请手动刷新核对");
        try { R result = gateway.read(context.session(), request).get(Math.min(remaining, TimeUnit.SECONDS.toNanos(16)), TimeUnit.NANOSECONDS); check(context); return result; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalArgumentException("机器人操作已中断，请刷新核对，不会自动重复翻页"); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) { throw new IllegalArgumentException("机器人操作结果尚未确认，请刷新核对，不会自动重复翻页"); }
    }
}
