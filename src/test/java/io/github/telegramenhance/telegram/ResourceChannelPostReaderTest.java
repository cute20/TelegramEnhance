package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ResourceChannelPostReaderTest {
    @Test void explicitSelectionReusesChannelOwnershipAndMainPostAssembly() {
        for (TdApi.ChatMemberStatus status : List.of(new TdApi.ChatMemberStatusCreator(false, true), new TdApi.ChatMemberStatusAdministrator())) {
            var scenario = new Scenario(); scenario.group.status = status;
            var post = scenario.reader.channelPost("-1001", "100");
            assertEquals("account:-1001:100", post.key()); assertEquals("100", post.mainMessageId());
            assertEquals("account", post.accountId()); assertEquals("body", post.text()); assertEquals("COMPLETE", post.mainCoverage());
            assertInstanceOf(TdApi.GetMessage.class, scenario.calls.getFirst());
            assertEquals(1, scenario.calls.stream().filter(query -> query instanceof TdApi.GetMessage).count());
            assertTrue(scenario.calls.stream().anyMatch(query -> query instanceof TdApi.GetSupergroup));
            assertTrue(scenario.calls.stream().noneMatch(query -> query instanceof TdApi.GetMessageLinkInfo));
        }
    }

    @Test void malformedOrNonpositiveIdentitiesNeverReachTdlib() {
        var scenario = new Scenario();
        for (String id : new String[]{null, "", "0", "-0", "+1", "01", " 1", "1.0", "9223372036854775808"}) {
            assertThrows(IllegalArgumentException.class, () -> scenario.reader.channelPost(id, "100"));
        }
        for (String id : new String[]{null, "", "0", "-1", "+1", "01", "1e3", "9223372036854775808"}) {
            assertThrows(IllegalArgumentException.class, () -> scenario.reader.channelPost("-1001", id));
        }
        assertTrue(scenario.calls.isEmpty());
    }

    @Test void nullWrongChatWrongMessageAndDiscussionMessagesCannotBecomeMainPosts() {
        for (int kind = 0; kind < 4; kind++) {
            var scenario = new Scenario();
            if (kind == 0) scenario.response = null;
            if (kind == 1) scenario.response.chatId = -9999;
            if (kind == 2) scenario.response.id = 101;
            if (kind == 3) scenario.response.isChannelPost = false;
            assertThrows(IllegalArgumentException.class, () -> scenario.reader.channelPost("-1001", "100"));
            assertEquals(1, scenario.calls.size());
        }
    }

    @Test void nonManagedChannelFailsBeforeReadingItsDiscussionOrSavingAnything() {
        var scenario = new Scenario(); scenario.group.status = new TdApi.ChatMemberStatusMember();
        assertThrows(IllegalArgumentException.class, () -> scenario.reader.channelPost("-1001", "100"));
        assertTrue(scenario.calls.stream().noneMatch(query -> query instanceof TdApi.GetMessageThread || query instanceof TdApi.GetChatHistory));
    }

    @Test void aMismatchedChatOrNonChannelIsRejectedBySharedAssembly() {
        var mismatch = new Scenario(); mismatch.chat.id = -1002;
        assertThrows(IllegalArgumentException.class, () -> mismatch.reader.channelPost("-1001", "100"));
        var groupChat = new Scenario(); groupChat.chat.type = new TdApi.ChatTypeSupergroup(1001, false);
        assertThrows(IllegalArgumentException.class, () -> groupChat.reader.channelPost("-1001", "100"));
        assertTrue(groupChat.calls.stream().noneMatch(query -> query instanceof TdApi.GetSupergroup));
    }

    @Test void anAccountChangeWhileResolvingTheMessageStopsBeforeAssembly() {
        var scenario = new Scenario(); scenario.afterMessage = () -> scenario.account = "another-account";
        assertThrows(IllegalArgumentException.class, () -> scenario.reader.channelPost("-1001", "100"));
        assertEquals(1, scenario.calls.size());
    }

    @Test void differentSelectedAlbumMembersKeepOneStablePostKeyEvenWithIncompleteNeighbors() {
        var first = new Scenario(); first.response.mediaAlbumId = 42;
        var sibling = new Scenario(); sibling.response.mediaAlbumId = 42; sibling.response.id = 101; sibling.requestedMessage = 101;
        var firstPost = first.reader.channelPost("-1001", "100");
        var siblingPost = sibling.reader.channelPost("-1001", "101");
        assertEquals("account:-1001:album:42", firstPost.key()); assertEquals(firstPost.key(), siblingPost.key());
        assertEquals("100", firstPost.mainMessageId()); assertEquals("101", siblingPost.mainMessageId());
        assertEquals("PARTIAL", firstPost.mainCoverage()); assertEquals("PARTIAL", siblingPost.mainCoverage());
    }

    private static final class Scenario implements ResourceReader.Gateway {
        final List<TdApi.Function<?>> calls = new ArrayList<>();
        final TdApi.Chat chat = new TdApi.Chat();
        final TdApi.Supergroup group = new TdApi.Supergroup();
        TdApi.Message response = new TdApi.Message();
        String account = "account";
        long requestedMessage = 100;
        Runnable afterMessage = () -> { };
        final ResourceReader reader = new ResourceReader(this, () -> account);
        Scenario() {
            response.chatId = -1001; response.id = 100; response.isChannelPost = true;
            response.content = new TdApi.MessageText(new TdApi.FormattedText("body", new TdApi.TextEntity[0]), null, null);
            chat.id = -1001; chat.title = "Synthetic channel"; chat.type = new TdApi.ChatTypeSupergroup(1001, true);
            group.id = 1001; group.isChannel = true; group.status = new TdApi.ChatMemberStatusCreator(false, true);
        }
        @Override @SuppressWarnings("unchecked") public <R extends TdApi.Object> CompletableFuture<R> read(TdApi.Function<R> query) {
            calls.add(query);
            TdApi.Object result;
            if (query instanceof TdApi.GetMessage request) {
                assertEquals(-1001, request.chatId); assertEquals(requestedMessage, request.messageId); afterMessage.run(); result = response;
            } else if (query instanceof TdApi.GetChat) result = chat;
            else if (query instanceof TdApi.GetSupergroup) result = group;
            else if (query instanceof TdApi.GetMessageThread) result = null;
            else if (query instanceof TdApi.GetMessageLink) result = new TdApi.MessageLink("", true);
            else if (query instanceof TdApi.GetChatHistory) result = new TdApi.Messages(-1, new TdApi.Message[0]);
            else throw new AssertionError("Only explicitly permitted metadata reads are allowed");
            return CompletableFuture.completedFuture((R) result);
        }
    }
}
