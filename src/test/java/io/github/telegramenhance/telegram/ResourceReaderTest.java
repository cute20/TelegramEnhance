package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.library.ResourceModels.Post;
import it.tdlight.jni.TdApi;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ResourceReaderTest {
    private static final String ACCOUNT = "test-account";
    private static final String LINK = "https://t.me/example_channel/1";

    @Test
    void sameMessageIdsInDifferentChannelsKeepDistinctPostAndMediaIdentities() {
        var first = new Scenario(-1001, -2001);
        var second = new Scenario(-1002, -2002);
        first.main.content = document("first.mp4");
        second.main.content = document("second.mp4");

        Post a = first.reader.probe(LINK);
        Post b = second.reader.probe(LINK);

        assertEquals(a.mainMessageId(), b.mainMessageId());
        assertNotEquals(a.key(), b.key());
        assertNotEquals(a.mainMedia().getFirst().key(), b.mainMedia().getFirst().key());
        assertEquals("-1001", a.mainMedia().getFirst().message().chatId());
        assertEquals("-1002", b.mainMedia().getFirst().message().chatId());
    }

    @Test
    void groupMessagesCannotBeImportedAsChannelPosts() {
        var scenario = new Scenario();
        scenario.main.isChannelPost = false;
        assertThrows(IllegalArgumentException.class, () -> scenario.reader.probe(LINK));
    }

    @Test
    void supergroupThatIsNotAChannelIsRejected() {
        var scenario = new Scenario();
        ((TdApi.ChatTypeSupergroup) scenario.chat.type).isChannel = false;
        assertThrows(IllegalArgumentException.class, () -> scenario.reader.probe(LINK));
    }

    @Test
    void subscribedChannelWithoutOwnershipOrAdministrationIsRejected() {
        var scenario = new Scenario();
        scenario.group.status = new TdApi.ChatMemberStatusMember();
        assertThrows(IllegalArgumentException.class, () -> scenario.reader.probe(LINK));
    }

    @Test
    void boundedSinglePostSourceAllowsReadableExternalChannelWithoutAnyDiscoveryOrSendCalls() {
        var scenario=new Scenario();scenario.group.status=new TdApi.ChatMemberStatusMember();
        var guards=new java.util.concurrent.atomic.AtomicInteger();var post=scenario.reader.probeDownloadSource(LINK,25_000,guards::incrementAndGet);
        assertEquals("-1001",post.channelId());assertTrue(guards.get()>=scenario.queries.size()*2);
        assertTrue(scenario.queries.stream().noneMatch(q->q instanceof TdApi.GetChats||q instanceof TdApi.LoadChats||q instanceof TdApi.JoinChat||q instanceof TdApi.SendMessage||q instanceof TdApi.SendMessageAlbum||q instanceof TdApi.GetSupergroup));
        assertThrows(IllegalArgumentException.class,()->scenario.reader.probe(LINK));
    }

    @Test
    void singlePostGuardStopsBeforeAnyNativeRead() {
        var scenario=new Scenario();assertThrows(IllegalArgumentException.class,()->scenario.reader.probeDownloadSource(LINK,25_000,()->{throw new IllegalArgumentException("session changed");}));
        assertTrue(scenario.queries.isEmpty());
    }

    @Test
    void administeredChannelCanBeProbedWithoutSendingOrDownloading() {
        var scenario = new Scenario();
        scenario.group.status = new TdApi.ChatMemberStatusAdministrator();
        assertEquals("-1001", scenario.reader.probe(LINK).channelId());
    }

    @Test
    void refreshReadsTheSavedIdentityAndUpdatesTheMainSnapshotWithoutResolvingAnotherLink() {
        var scenario = new Scenario();
        Post previous = scenario.reader.probe(LINK);
        int before = scenario.queries.size();
        var updated = message(scenario.main.chatId, scenario.main.id, "updated text");
        updated.isChannelPost = true;
        updated.content = document("edited.mp4");
        ((TdApi.MessageDocument) updated.content).caption = new TdApi.FormattedText("updated caption", new TdApi.TextEntity[0]);
        scenario.refreshResponse = updated;

        var refreshed = scenario.reader.refresh(previous);

        assertEquals(previous.key(), refreshed.key());
        assertEquals(previous.channelId(), refreshed.channelId());
        assertEquals(previous.mainMessageId(), refreshed.mainMessageId());
        assertEquals("updated caption", refreshed.text());
        assertEquals("edited.mp4", refreshed.mainMedia().getFirst().fileName());
        assertEquals(1, scenario.refreshRequests.size());
        assertEquals(-1001, scenario.refreshRequests.getFirst().chatId);
        assertEquals(1000, scenario.refreshRequests.getFirst().messageId);
        assertTrue(scenario.queries.subList(before, scenario.queries.size()).stream().noneMatch(q -> q instanceof TdApi.GetMessageLinkInfo));
    }

    @Test
    void refreshRejectsAnAccountChangeBeforeAnyTelegramRead() {
        var scenario = new Scenario();
        Post previous = scenario.reader.probe(LINK);
        int before = scenario.queries.size();
        scenario.currentAccount = "another-account";

        assertThrows(IllegalArgumentException.class, () -> scenario.reader.refresh(previous));
        assertEquals(before, scenario.queries.size());
    }

    @Test
    void refreshRejectsAnotherChatOrMessageBeforeAssemblingThePost() {
        for (boolean anotherChat : List.of(true, false)) {
            var scenario = new Scenario();
            Post previous = scenario.reader.probe(LINK);
            int before = scenario.queries.size();
            var wrong = message(anotherChat ? -9999 : scenario.main.chatId, anotherChat ? scenario.main.id : 2000, "wrong identity");
            wrong.isChannelPost = true;
            scenario.refreshResponse = wrong;

            assertThrows(IllegalArgumentException.class, () -> scenario.reader.refresh(previous));
            assertEquals(before + 1, scenario.queries.size(), "Only the saved GetMessage request may be issued");
        }
    }

    @Test
    void refreshRejectsAChangedAlbumIdentityAndRechecksChannelOwnership() {
        var scenario = new Scenario();
        Post previous = scenario.reader.probe(LINK);
        int before = scenario.queries.size();
        scenario.main.mediaAlbumId = 42;

        assertThrows(IllegalArgumentException.class, () -> scenario.reader.refresh(previous));
        assertEquals(before + 1, scenario.queries.size());
        scenario.main.mediaAlbumId = 0;
        scenario.group.status = new TdApi.ChatMemberStatusMember();
        assertThrows(IllegalArgumentException.class, () -> scenario.reader.refresh(previous));
    }

    @Test
    void refreshCanExpandAnAlbumWithoutChangingItsStableKeyOrItsReadAnchor() {
        var scenario = new Scenario();
        scenario.main.mediaAlbumId = 42;
        Post previous = scenario.reader.probe(LINK);
        var olderMember = message(scenario.main.chatId, 999, "newly observed member"); olderMember.mediaAlbumId = 42;
        scenario.mainPages.add(page(message(scenario.main.chatId, 1100, "newer post"), scenario.main));
        scenario.mainPages.add(page(olderMember, message(scenario.main.chatId, 998, "older post")));

        var refreshed = scenario.reader.refresh(previous);

        assertEquals(previous.key(), refreshed.key());
        assertEquals("test-account:-1001:album:42", refreshed.key());
        assertEquals("999", refreshed.mainMessageId());
        assertEquals(1000, scenario.refreshRequests.getFirst().messageId, "The refresh starts at the saved main identity");
        assertEquals(List.of("999", "1000"), refreshed.mainMessages().stream().map(m -> m.messageId()).toList());
        assertEquals("COMPLETE", refreshed.mainCoverage());
    }

    @Test
    void adjacentBoundariesProveAlbumMembersWhileCommentScanRemainsPending() {
        var scenario = new Scenario();
        scenario.main.mediaAlbumId = 42;
        var second = message(scenario.main.chatId, 1001, "second"); second.mediaAlbumId = 42;
        var third = message(scenario.main.chatId, 1002, "third"); third.mediaAlbumId = 42;
        scenario.mainPages.add(page(message(scenario.main.chatId, 1100, "newer post"), third, second, scenario.main));
        scenario.mainPages.add(page(message(scenario.main.chatId, 999, "older post")));

        var post = scenario.reader.probe(LINK);

        assertEquals(List.of("1000", "1001", "1002"), post.mainMessages().stream().map(m -> m.messageId()).toList());
        assertEquals("COMPLETE", post.mainCoverage());
        assertEquals("PARTIAL", post.coverage(), "Reading the main album does not read comment history");
        assertTrue(post.albumEvidence().complete());
        assertEquals("ADJACENT_BOUNDARIES", post.albumEvidence().method());
        assertEquals("1100", post.albumEvidence().newerBoundaryId());
        assertEquals("999", post.albumEvidence().olderBoundaryId());
        assertTrue(post.warnings().isEmpty());
    }

    @Test
    void unavailableCommentsDoNotDowngradeACompleteOrdinaryMainPost() {
        var scenario = new Scenario();
        scenario.roots = new TdApi.Message[0];

        var post = scenario.reader.probe(LINK);

        assertEquals("COMPLETE", post.mainCoverage());
        assertEquals("SINGLE_MESSAGE", post.albumEvidence().method());
        assertFalse(post.canReadComments());
        assertEquals("UNAVAILABLE", post.coverage());
        assertFalse(post.warnings().isEmpty());
    }

    @Test
    void uncertainAlbumEndpointsStillHaveConcretePartialEvidence() {
        var scenario = new Scenario();
        scenario.main.mediaAlbumId = 42;

        var post = scenario.reader.probe(LINK);

        assertEquals("PARTIAL", post.mainCoverage());
        assertFalse(post.albumEvidence().complete());
        assertEquals("PARTIAL_SCAN", post.albumEvidence().method());
        assertFalse(post.warnings().isEmpty());
    }

    @Test
    void allRootAlbumMembersAreExcludedWhileNestedRepliesRemain() {
        var scenario = new Scenario();
        Post post = scenario.reader.probe(LINK);
        var originalRoot = scenario.roots[0];
        var discoveredRoot = message(scenario.discussionChat, 91, "second root album member");
        scenario.roots = new TdApi.Message[]{discoveredRoot, originalRoot};
        var direct = message(scenario.discussionChat, 110, "direct reply");
        direct.replyTo = reply(scenario.discussionChat, originalRoot.id);
        direct.content = document("direct.mp4");
        var nested = message(scenario.discussionChat, 120, "nested reply");
        nested.replyTo = reply(scenario.discussionChat, direct.id);
        nested.topicId = new TdApi.MessageTopicThread(originalRoot.id);
        nested.content = document("nested.mp4");
        originalRoot.content = document("root-one.mp4");
        discoveredRoot.content = document("root-two.mp4");
        scenario.pages.add(page(nested, direct, direct, discoveredRoot, originalRoot));

        var result = scenario.reader.comments(post, null);

        assertEquals(List.of("120", "110"), result.messages().stream().map(m -> m.messageId()).toList());
        assertEquals(List.of("nested.mp4", "direct.mp4"), result.media().stream().map(m -> m.fileName()).toList());
        assertEquals(2, result.messageCount());
        assertEquals(List.of("120", "110"), result.observations().stream().map(o -> o.message().messageId()).toList());
        assertTrue(result.observations().stream().allMatch(o -> o.mediaComplete() && o.warnings().isEmpty()));
        assertEquals("90", result.nextCursor(), "Filtered roots still advance the history cursor");
        assertFalse(result.exhausted());
        assertEquals("PARTIAL", result.coverage());
    }

    @Test
    void shortPagesContinueUntilAnEmptyPageIsConfirmedAtTheSameCursor() {
        var scenario = new Scenario();
        Post post = scenario.reader.probe(LINK);
        scenario.pages.add(page(message(scenario.discussionChat, 500, "first")));
        scenario.pages.add(page(message(scenario.discussionChat, 400, "second")));
        scenario.pages.add(page());
        scenario.pages.add(page());

        var first = scenario.reader.comments(post, null);
        assertFalse(first.exhausted());
        assertEquals("PARTIAL", first.coverage());
        assertEquals("500", first.nextCursor());
        var second = scenario.reader.comments(post, first.nextCursor());
        assertFalse(second.exhausted());
        assertEquals("400", second.nextCursor());
        var end = scenario.reader.comments(post, second.nextCursor());
        assertTrue(end.exhausted());
        assertEquals("COMPLETE", end.coverage());
        assertTrue(end.warnings().isEmpty(), "Successful EOF is represented by coverage and exhausted, not a warning");
        assertTrue(end.observations().isEmpty());
        assertTrue(end.scanWarnings().isEmpty());
        assertEquals(List.of(0L, 500L, 400L, 400L),
                scenario.history.stream().map(q -> q.fromMessageId).toList());
        assertTrue(scenario.history.stream().allMatch(q -> q.chatId == scenario.discussionChat
                && q.messageId == 90 && q.offset == 0 && q.limit > 0 && q.limit <= 100));
    }

    @Test
    void anInitialEmptyResponseDoesNotHideASecondResponseWithComments() {
        var scenario = new Scenario();
        Post post = scenario.reader.probe(LINK);
        scenario.pages.add(page());
        scenario.pages.add(page(message(scenario.discussionChat, 500, "visible after retry")));

        var result = scenario.reader.comments(post, null);

        assertFalse(result.exhausted());
        assertEquals("PARTIAL", result.coverage());
        assertEquals(1, result.messageCount());
        assertEquals(List.of(0L, 0L), scenario.history.stream().map(q -> q.fromMessageId).toList());
    }

    @Test
    void nonAdvancingAndWrongChatResultsRemainPartial() {
        var scenario = new Scenario();
        Post post = scenario.reader.probe(LINK);
        scenario.pages.add(page(message(scenario.discussionChat, 500, "repeated cursor"),
                message(scenario.discussionChat, 600, "newer than cursor"),
                message(-9999, 400, "unrelated chat"), null));

        var result = scenario.reader.comments(post, "500");

        assertEquals("500", result.nextCursor());
        assertFalse(result.exhausted());
        assertEquals("PARTIAL", result.coverage());
        assertTrue(result.messages().isEmpty());
        assertFalse(result.warnings().isEmpty());
        assertEquals(result.warnings(), result.scanWarnings());
        assertTrue(result.observations().isEmpty());
        assertEquals(1, scenario.history.size(), "A nonempty invalid page must not be mistaken for EOF");
    }

    @Test
    void eachCommentHasItsOwnMediaCompletenessIncludingConfirmedEmptyText() {
        var scenario = new Scenario();
        Post post = scenario.reader.probe(LINK);
        var video = message(scenario.discussionChat, 500, "video"); video.content = document("valid.mp4");
        var text = message(scenario.discussionChat, 400, "now only text");
        var unknown = message(scenario.discussionChat, 300, "unknown"); unknown.content = new TdApi.MessageLocation();
        var broken = message(scenario.discussionChat, 200, "broken"); broken.content = new TdApi.MessageVideo();
        scenario.pages.add(page(video, text, unknown, broken));

        var result = scenario.reader.comments(post, null);

        assertEquals(List.of("500", "400", "300", "200"), result.observations().stream().map(o -> o.message().messageId()).toList());
        assertEquals(List.of(true, true, false, false), result.observations().stream().map(o -> o.mediaComplete()).toList());
        assertTrue(result.observations().get(0).warnings().isEmpty());
        assertTrue(result.observations().get(1).warnings().isEmpty(), "An ordinary text message proves an empty media snapshot");
        assertFalse(result.observations().get(2).warnings().isEmpty());
        assertFalse(result.observations().get(3).warnings().isEmpty());
        assertEquals(List.of("valid.mp4"), result.media().stream().map(m -> m.fileName()).toList());
        assertTrue(result.scanWarnings().isEmpty(), "A malformed attachment is not a history pagination failure");
        assertFalse(result.warnings().isEmpty());
        assertEquals(result.warnings(), result.commentWarnings());
        assertEquals("200", result.nextCursor());
    }

    @Test
    void malformedHistoryPayloadCannotProduceAnEmptyCompleteObservation() {
        var scenario = new Scenario();
        Post post = scenario.reader.probe(LINK);
        scenario.pages.add(new TdApi.Messages(-1, null));

        var result = scenario.reader.comments(post, "500");

        assertFalse(result.exhausted());
        assertEquals("PARTIAL", result.coverage());
        assertEquals("500", result.nextCursor());
        assertTrue(result.observations().isEmpty());
        assertFalse(result.scanWarnings().isEmpty());
        assertEquals(result.warnings(), result.scanWarnings());
    }

    @Test
    void missingCurrentRootsKeepEvenConfirmedHistoryEofPartial() {
        var scenario = new Scenario();
        Post post = scenario.reader.probe(LINK);
        scenario.roots = new TdApi.Message[0];
        scenario.pages.add(page());
        scenario.pages.add(page());

        var result = scenario.reader.comments(post, null);

        assertTrue(result.exhausted());
        assertEquals("PARTIAL", result.coverage());
        assertFalse(result.scanWarnings().isEmpty());
        assertTrue(result.observations().isEmpty());
    }

    @Test
    void changedDiscussionMappingIsRejectedBeforeHistoryIsRead() {
        var scenario = new Scenario();
        Post post = scenario.reader.probe(LINK);
        scenario.discussionRoot = 999;
        assertThrows(IllegalArgumentException.class, () -> scenario.reader.comments(post, null));
        assertTrue(scenario.history.isEmpty());
    }

    private static TdApi.Message message(long chat, long id, String text) {
        var message = new TdApi.Message();
        message.chatId = chat;
        message.id = id;
        message.content = new TdApi.MessageText(new TdApi.FormattedText(text, new TdApi.TextEntity[0]), null, null);
        return message;
    }

    private static TdApi.MessageReplyToMessage reply(long chat, long id) {
        var reply = new TdApi.MessageReplyToMessage();
        reply.chatId = chat;
        reply.messageId = id;
        return reply;
    }

    private static TdApi.MessageDocument document(String name) {
        var file = new TdApi.File();
        file.size = 123;
        var document = new TdApi.Document(name, "video/mp4", null, null, file);
        return new TdApi.MessageDocument(document, new TdApi.FormattedText("", new TdApi.TextEntity[0]));
    }

    private static TdApi.Messages page(TdApi.Message... messages) {
        return new TdApi.Messages(-1, messages);
    }

    private static final class Scenario implements ResourceReader.Gateway {
        final long discussionChat;
        final TdApi.Message main;
        final TdApi.Chat chat = new TdApi.Chat();
        final TdApi.Supergroup group = new TdApi.Supergroup();
        final Deque<TdApi.Messages> pages = new ArrayDeque<>();
        final Deque<TdApi.Messages> mainPages = new ArrayDeque<>();
        final List<TdApi.GetMessageThreadHistory> history = new ArrayList<>();
        final List<TdApi.GetMessage> refreshRequests = new ArrayList<>();
        final List<TdApi.Function<?>> queries = new ArrayList<>();
        String currentAccount = ACCOUNT;
        final ResourceReader reader = new ResourceReader(this, () -> currentAccount);
        TdApi.Message refreshResponse;
        TdApi.Message[] roots;
        long discussionRoot = 90;

        Scenario() { this(-1001, -2001); }

        Scenario(long channel, long discussionChat) {
            this.discussionChat = discussionChat;
            main = message(channel, 1000, "main post");
            main.isChannelPost = true;
            refreshResponse = main;
            chat.id = channel;
            chat.title = "Test channel";
            chat.type = new TdApi.ChatTypeSupergroup(Math.abs(channel), true);
            group.id = Math.abs(channel);
            group.isChannel = true;
            group.status = new TdApi.ChatMemberStatusCreator(false, true);
            roots = new TdApi.Message[]{message(discussionChat, 90, "discussion root")};
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R extends TdApi.Object> CompletableFuture<R> read(TdApi.Function<R> query) {
            queries.add(query);
            TdApi.Object response;
            if (query instanceof TdApi.GetMessageLinkInfo) {
                var resolved = new TdApi.MessageLinkInfo();
                resolved.chatId = main.chatId;
                resolved.message = main;
                response = resolved;
            } else if (query instanceof TdApi.GetMessage request) { refreshRequests.add(request); response = refreshResponse; }
            else if (query instanceof TdApi.GetChat) response = chat;
            else if (query instanceof TdApi.GetSupergroup) response = group;
            else if (query instanceof TdApi.GetMessageProperties) {
                var properties = new TdApi.MessageProperties();
                properties.canGetMessageThread = true;
                response = properties;
            } else if (query instanceof TdApi.GetMessageThread) {
                response = new TdApi.MessageThreadInfo(discussionChat, discussionRoot, null, 0, roots, null);
            } else if (query instanceof TdApi.GetMessageThreadHistory request) {
                history.add(request);
                if (pages.isEmpty()) throw new AssertionError("No fake history page configured");
                response = pages.removeFirst();
            } else if (query instanceof TdApi.GetMessageLink request) {
                response = new TdApi.MessageLink("https://t.me/example/" + request.chatId + "/" + request.messageId, true);
            } else if (query instanceof TdApi.GetChatHistory) response = mainPages.isEmpty() ? page() : mainPages.removeFirst();
            else throw new AssertionError("Unexpected operation; tests permit only explicitly faked reads");
            return CompletableFuture.completedFuture((R) response);
        }
    }
}
