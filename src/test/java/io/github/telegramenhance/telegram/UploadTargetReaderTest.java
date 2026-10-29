package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.library.ResourceModels.*;
import it.tdlight.jni.TdApi;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class UploadTargetReaderTest {
    @Test void managedTargetAndReadableDiscussionStillRequireVideoPermissionAndNeverSendOrJoin() {
        var f = new Fixture(); var result = f.reader.read("link", "1", 4);
        assertEquals("MEDIA_ALLOWED_AT_CHECK", result.permission()); assertEquals("-2001", result.post().discussionChatId());
        assertTrue(f.calls.stream().allMatch(q -> q instanceof TdApi.GetMessageThread || q instanceof TdApi.GetMessage || q instanceof TdApi.GetMessageProperties || q instanceof TdApi.GetChat || q instanceof TdApi.GetSupergroup || q instanceof TdApi.GetSupergroupFullInfo || q instanceof TdApi.GetChatMember));
        f.chat.permissions.canSendVideos = false; assertThrows(IllegalArgumentException.class, () -> f.reader.read("link", "1", 4));
    }
    @Test void leftBannedAndRestrictedMembersCannotUseReadableCommentsAsSendingPermission() {
        var f = new Fixture(); f.member.status = new TdApi.ChatMemberStatusLeft(); assertThrows(IllegalArgumentException.class, () -> f.reader.read("link", "1", 4));
        var restricted = new TdApi.ChatMemberStatusRestricted(); restricted.isMember = true; restricted.permissions = new TdApi.ChatPermissions(); f.member.status = restricted;
        assertThrows(IllegalArgumentException.class, () -> f.reader.read("link", "1", 4));
        restricted.permissions.canSendBasicMessages = true; restricted.permissions.canSendVideos = true; assertNotNull(f.reader.read("link", "1", 4));
    }
    @Test void administratorsMaySendDespiteDefaultMemberRestrictionsButPaidTargetsAreRejected() {
        var f = new Fixture(); f.chat.permissions.canSendVideos = false; f.member.status = new TdApi.ChatMemberStatusAdministrator(); assertNotNull(f.reader.read("link", "1", 4));
        f.full.outgoingPaidMessageStarCount = 1; assertThrows(IllegalArgumentException.class, () -> f.reader.read("link", "1", 4));
    }
    @Test void changedDiscussionWrongMemberAndNonReplyableRootAreRejected() {
        var f = new Fixture(); f.thread.messageThreadId = 501; assertThrows(IllegalArgumentException.class, () -> f.reader.read("link", "1", 4)); f.thread.messageThreadId = 500;
        f.properties.canBeReplied = false; assertThrows(IllegalArgumentException.class, () -> f.reader.read("link", "1", 4)); f.properties.canBeReplied = true;
        f.member.memberId = new TdApi.MessageSenderUser(2); assertThrows(IllegalArgumentException.class, () -> f.reader.read("link", "1", 4));
    }
    @Test void unmanagedOrUnprovedMainPostsCannotBecomeUploadTargets() {
        var f = new Fixture(); when(f.posts.probe("link")).thenThrow(new IllegalArgumentException("not managed")); assertThrows(IllegalArgumentException.class, () -> f.reader.read("link", "1", 4)); assertTrue(f.calls.isEmpty());
        reset(f.posts); when(f.posts.probe("link")).thenReturn(f.post.withCoverage("PARTIAL")); assertNotNull(f.reader.read("link", "1", 4));
        when(f.posts.probe("link")).thenReturn(new Post("p", "1", "-1001", "channel", "100", "title", "", "0", List.of(), List.of(), "-2001", "500", List.of(), true, "PARTIAL", List.of()));
        assertThrows(IllegalArgumentException.class, () -> f.reader.read("link", "1", 4));
    }
    @Test void sessionChangesDuringAReadInvalidateThePermissionResult() {
        var f = new Fixture(); f.changeDuringRead = true; assertThrows(IllegalArgumentException.class, () -> f.reader.read("link", "1", 4));
    }
    @Test void everyPresentMediaTypeRequiresItsOwnPermission() {
        var f = new Fixture(); var types = Set.of("PHOTO","VIDEO","AUDIO","DOCUMENT","ANIMATION");
        assertThrows(IllegalArgumentException.class, () -> f.reader.read("link", "1", 4, types));
        f.chat.permissions.canSendPhotos = true; f.chat.permissions.canSendAudios = true; f.chat.permissions.canSendDocuments = true; f.chat.permissions.canSendOtherMessages = true;
        assertNotNull(f.reader.read("link", "1", 4, types)); f.chat.permissions.canSendAudios = false; assertThrows(IllegalArgumentException.class, () -> f.reader.read("link", "1", 4, types));
        assertNotNull(f.reader.read("link", "1", 4, Set.of("PHOTO","VIDEO")));
    }
    private static final class Fixture implements UploadTargetReader.Gateway {
        final ResourceReader posts = mock(ResourceReader.class); final List<TdApi.Function<?>> calls = new ArrayList<>(); long session = 4; boolean changeDuringRead;
        final TdApi.Message root = new TdApi.Message(); final TdApi.MessageProperties properties = new TdApi.MessageProperties(); final TdApi.Chat chat = new TdApi.Chat(); final TdApi.Supergroup group = new TdApi.Supergroup(); final TdApi.SupergroupFullInfo full = new TdApi.SupergroupFullInfo(); final TdApi.ChatMember member = new TdApi.ChatMember(); final TdApi.MessageThreadInfo thread = new TdApi.MessageThreadInfo();
        final Post post = new Post("p", "1", "-1001", "channel", "100", "title", "body", "0", List.of(new MessageRef("-1001", "100", "link")), List.of(), "-2001", "500", List.of(new MessageRef("-2001", "500", "root")), true, "PARTIAL", List.of(), null, "COMPLETE");
        final UploadTargetReader reader = new UploadTargetReader(posts, this, () -> "1", () -> session);
        Fixture() {
            when(posts.probe("link")).thenReturn(post); root.chatId = -2001; root.id = 500; thread.chatId = -2001; thread.messageThreadId = 500; thread.messages = new TdApi.Message[]{root}; properties.canBeReplied = true;
            chat.id = -2001; chat.title = "discussion"; chat.type = new TdApi.ChatTypeSupergroup(9, false); chat.permissions = new TdApi.ChatPermissions(); chat.permissions.canSendBasicMessages = true; chat.permissions.canSendVideos = true;
            group.id = 9; member.memberId = new TdApi.MessageSenderUser(1); member.status = new TdApi.ChatMemberStatusMember();
        }
        @SuppressWarnings("unchecked") public <R extends TdApi.Object> CompletableFuture<R> read(long expected, TdApi.Function<R> query) {
            calls.add(query); if (changeDuringRead) session++;
            Object result = query instanceof TdApi.GetMessageThread ? thread : query instanceof TdApi.GetMessage ? root : query instanceof TdApi.GetMessageProperties ? properties : query instanceof TdApi.GetChat ? chat : query instanceof TdApi.GetSupergroup ? group : query instanceof TdApi.GetSupergroupFullInfo ? full : query instanceof TdApi.GetChatMember ? member : null;
            if (result == null) throw new AssertionError("Unexpected native mutation"); return CompletableFuture.completedFuture((R) result);
        }
    }
}
