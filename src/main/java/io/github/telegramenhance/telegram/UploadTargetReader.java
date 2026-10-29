package io.github.telegramenhance.telegram;

import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.transfer.UploadPreviewModels.Target;
import it.tdlight.jni.TdApi;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Read-only permission preflight; never joins a discussion or sends anything. */
@Service
public final class UploadTargetReader {
    interface Gateway { <R extends TdApi.Object> CompletableFuture<R> read(long session, TdApi.Function<R> request); }
    private final ResourceReader posts; private final Gateway gateway; private final Supplier<String> account; private final LongSupplier generation;
    @org.springframework.beans.factory.annotation.Autowired
    public UploadTargetReader(ResourceReader posts, TelegramService telegram) { this(posts, new Gateway() { public <R extends TdApi.Object> CompletableFuture<R> read(long session, TdApi.Function<R> request) { return telegram.readInSession(session, request); } }, telegram::accountId, telegram::sessionGeneration); }
    UploadTargetReader(ResourceReader posts, Gateway gateway, Supplier<String> account, LongSupplier generation) { this.posts = posts; this.gateway = gateway; this.account = account; this.generation = generation; }
    public Target read(String link, String expectedAccount, long session) {
        return read(link, expectedAccount, session, java.util.Set.of("VIDEO"));
    }
    public Target read(String link, String expectedAccount, long session, java.util.Set<String> mediaTypes) {
        check(expectedAccount, session); Post post = posts.probe(link); check(expectedAccount, session);
        if (!expectedAccount.equals(post.accountId()) || !"COMPLETE".equals(post.mainCoverage()) || !post.canReadComments()
                || post.discussionChatId() == null || post.discussionRootMessageId() == null) throw new IllegalArgumentException("目标主帖或讨论映射尚未核全，请先选择已核对的自己频道主帖");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35); long chatId = Long.parseLong(post.discussionChatId()), rootId = Long.parseLong(post.discussionRootMessageId());
        var thread = call(expectedAccount, session, new TdApi.GetMessageThread(Long.parseLong(post.channelId()), Long.parseLong(post.mainMessageId())), deadline);
        if (thread == null || thread.chatId != chatId || thread.messageThreadId != rootId || thread.messages == null
                || java.util.Arrays.stream(thread.messages).noneMatch(m -> m != null && m.chatId == chatId && m.id == rootId)) throw new IllegalArgumentException("讨论线程映射发生变化，请重新预览目标主帖");
        var root = call(expectedAccount, session, new TdApi.GetMessage(chatId, rootId), deadline);
        var properties = call(expectedAccount, session, new TdApi.GetMessageProperties(chatId, rootId), deadline);
        if (root == null || root.chatId != chatId || root.id != rootId || root.isChannelPost || properties == null || !properties.canBeReplied) throw new IllegalArgumentException("目标评论根消息当前不能回复");
        var chat = call(expectedAccount, session, new TdApi.GetChat(chatId), deadline);
        if (chat == null || chat.id != chatId || !(chat.type instanceof TdApi.ChatTypeSupergroup type) || type.isChannel) throw new IllegalArgumentException("未取得可发送的关联讨论组");
        var group = call(expectedAccount, session, new TdApi.GetSupergroup(type.supergroupId), deadline);
        var full = call(expectedAccount, session, new TdApi.GetSupergroupFullInfo(type.supergroupId), deadline);
        if (group == null || group.id != type.supergroupId || group.isChannel || group.isForum || group.isDirectMessagesGroup || full == null) throw new IllegalArgumentException("当前仅支持普通关联讨论组评论，请核对目标类型");
        if (group.paidMessageStarCount > 0 || full.outgoingPaidMessageStarCount > 0) throw new IllegalArgumentException("目标评论需要付费，当前上传准备不支持此目标");
        var member = call(expectedAccount, session, new TdApi.GetChatMember(chatId, new TdApi.MessageSenderUser(Long.parseLong(expectedAccount))), deadline);
        if (member == null || !(member.memberId instanceof TdApi.MessageSenderUser user) || user.userId != Long.parseLong(expectedAccount)) throw new IllegalArgumentException("未能核对当前账号的讨论组身份");
        boolean elevated = member.status instanceof TdApi.ChatMemberStatusAdministrator || member.status instanceof TdApi.ChatMemberStatusCreator creator && creator.isMember;
        boolean ordinary = member.status instanceof TdApi.ChatMemberStatusMember && allowed(chat.permissions, mediaTypes);
        boolean restricted = member.status instanceof TdApi.ChatMemberStatusRestricted status && status.isMember && allowed(status.permissions, mediaTypes) && allowed(chat.permissions, mediaTypes);
        if (!elevated && !ordinary && !restricted) throw new IllegalArgumentException("当前账号未加入讨论组或没有发送所选媒体类型的权限，请在 Telegram 中核对后重试");
        check(expectedAccount, session); return new Target(post, chat.title, "MEDIA_ALLOWED_AT_CHECK", full.slowModeDelay, System.currentTimeMillis());
    }
    private static boolean allowed(TdApi.ChatPermissions permissions, java.util.Set<String> types) {
        return permissions != null && permissions.canSendBasicMessages && types.stream().allMatch(type -> switch(type) {
            case "PHOTO" -> permissions.canSendPhotos; case "VIDEO" -> permissions.canSendVideos; case "AUDIO" -> permissions.canSendAudios;
            case "DOCUMENT" -> permissions.canSendDocuments; case "ANIMATION" -> permissions.canSendOtherMessages; default -> false;
        });
    }
    private void check(String expected, long session) { if (Thread.currentThread().isInterrupted() || !Objects.equals(expected, account.get()) || session != generation.getAsLong()) throw new IllegalArgumentException("账号或会话已变化，请重新预览上传目标"); }
    private <R extends TdApi.Object> R call(String expected, long session, TdApi.Function<R> request, long deadline) {
        check(expected, session); long remaining = Math.min(deadline - System.nanoTime(), TimeUnit.SECONDS.toNanos(16));
        if (remaining <= 0) throw new IllegalArgumentException("目标权限核对超时，请重新预览");
        try { var result = gateway.read(session, request).get(remaining, TimeUnit.NANOSECONDS); check(expected, session); return result; }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalArgumentException("上传目标核对已停止"); }
        catch (ExecutionException | TimeoutException failure) { throw new IllegalArgumentException("上传目标权限未能核实，请检查连接后重试"); }
    }
}
