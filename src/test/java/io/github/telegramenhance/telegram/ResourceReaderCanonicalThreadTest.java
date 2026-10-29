package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.library.ResourceRepository;
import io.github.telegramenhance.storage.Database;
import it.tdlight.jni.TdApi;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceReaderCanonicalThreadTest {
    @TempDir Path directory;
    private static final long CHANNEL = -1001, DISCUSSION = -2001;
    private static final String PRIVATE_ERROR = "synthetic-private-native-message";

    @Test void failingNonCanonicalThreadFallsBackOnceWithoutRepeatingTheAlbumScan() {
        var scenario = new Scenario(101, 42);
        Post post = scenario.read();
        assertReadableComplete(post);
        assertEquals(List.of(101L, 100L), scenario.threadRequests);
        assertEquals(2, scenario.historyRequests.size());
        assertTrue(post.warnings().isEmpty());
        assertEquals(List.of("500", "501"), post.discussionRoots().stream().map(MessageRef::messageId).toList());
    }

    @Test void nullOrEmptyInitialThreadRootsAlsoFallBackToTheCanonicalMember() {
        for (int kind = 0; kind < 4; kind++) {
            var scenario = new Scenario(101, 42);
            var thread = thread(root(500, 100), root(501, 101));
            if (kind == 0) thread = null;
            if (kind == 1) thread.messages = null;
            if (kind == 2) thread.messages = new TdApi.Message[0];
            if (kind == 3) thread.messages = new TdApi.Message[]{null};
            scenario.initial = CompletableFuture.completedFuture(thread);
            assertReadableComplete(scenario.read());
            assertEquals(List.of(101L, 100L), scenario.threadRequests); assertEquals(2, scenario.historyRequests.size());
        }
    }

    @Test void aFailedCanonicalFallbackKeepsMainMetadataAndReportsCommentsUnavailableWithoutNativeErrorText() {
        var scenario = new Scenario(101, 42);
        scenario.fallback = CompletableFuture.failedFuture(new IllegalStateException(PRIVATE_ERROR));
        Post post = scenario.read();
        assertFalse(post.canReadComments()); assertEquals("UNAVAILABLE", post.coverage());
        assertTrue(post.discussionRoots().isEmpty()); assertFalse(post.warnings().isEmpty());
        assertTrue(post.warnings().stream().noneMatch(warning -> warning.contains(PRIVATE_ERROR)));
        assertEquals("account:-1001:album:42", post.key()); assertEquals(List.of(101L, 100L), scenario.threadRequests);
        assertEquals(2, scenario.historyRequests.size());
    }

    @Test void aValidInitialThreadDoesNotIssueAnotherThreadRequest() {
        var scenario = new Scenario(101, 42); scenario.initial = CompletableFuture.completedFuture(thread(root(500, 100), root(501, 101)));
        assertReadableComplete(scenario.read());
        assertEquals(List.of(101L), scenario.threadRequests); assertEquals(2, scenario.historyRequests.size());
    }

    @Test void singleMessagesAndAnAlreadyCanonicalAlbumAnchorDoNotRetryTheSameThreadIdentity() {
        var single = new Scenario(101, 0);
        var post = single.read();
        assertFalse(post.canReadComments()); assertEquals("101", post.mainMessageId());
        assertEquals(List.of(101L), single.threadRequests); assertTrue(single.historyRequests.isEmpty());
        var canonical = new Scenario(100, 42);
        assertFalse(canonical.read().canReadComments()); assertEquals(List.of(100L), canonical.threadRequests);
        assertEquals(2, canonical.historyRequests.size());
    }

    @Test void invalidCanonicalRootChatOrIdentityCannotCreateAReadableDiscussionMapping() {
        for (int kind = 0; kind < 3; kind++) {
            var scenario = new Scenario(101, 42); var root = root(500, 100);
            if (kind == 0) root.chatId = -9999;
            if (kind == 1) root.id = 0;
            var thread = thread(root);
            if (kind == 2) thread.messageThreadId = 0;
            scenario.fallback = CompletableFuture.completedFuture(thread);
            Post post = scenario.read();
            assertFalse(post.canReadComments()); assertTrue(post.discussionRoots().isEmpty());
            assertEquals(List.of(101L, 100L), scenario.threadRequests);
        }
    }

    @Test void canonicalFallbackForwardOriginsFromAnotherChannelInvalidateOtherwiseCompleteAlbumEvidence() {
        for (boolean wrongChannel : List.of(true, false)) {
            var scenario = new Scenario(101, 42); var wrong = root(500, 100);
            var origin = (TdApi.MessageOriginChannel) wrong.forwardInfo.origin;
            if (wrongChannel) origin.chatId = -9999; else origin.messageId = 0;
            scenario.fallback = CompletableFuture.completedFuture(thread(wrong, root(501, 101)));
            Post post = scenario.read();
            assertFalse(post.canReadComments()); assertFalse(post.albumEvidence().complete()); assertEquals("PARTIAL", post.mainCoverage());
            assertFalse(post.warnings().isEmpty()); assertEquals(2, scenario.historyRequests.size());
        }
    }

    @Test void canonicalFallbackOriginsPointingToAnUncollectedMemberCannotProveTheAlbumComplete() {
        var scenario = new Scenario(101, 42);
        scenario.fallback = CompletableFuture.completedFuture(thread(root(500, 100), root(501, 103)));
        Post post = scenario.read();
        assertTrue(post.canReadComments()); assertFalse(post.albumEvidence().complete()); assertEquals("PARTIAL", post.mainCoverage());
        assertEquals(List.of("100", "101"), post.mainMessages().stream().map(MessageRef::messageId).toList());
        assertFalse(post.warnings().isEmpty()); assertEquals(2, scenario.historyRequests.size());
    }

    @Test void reimportingViaCanonicalFallbackPreservesCompletePersistedCommentsAndTheirCheckpoint() {
        var original = new Scenario(100, 42); original.initial = CompletableFuture.completedFuture(thread(root(500, 100), root(501, 101)));
        Post first = original.read(); assertReadableComplete(first);
        try (Database database = new Database(new AppPaths(directory))) {
            var repository = new ResourceRepository(database); repository.savePost(first);
            var comment = new MessageRef(Long.toString(DISCUSSION), "601", "saved-comment-link");
            var media = new Media("account:-2001:601:0", comment, "COMMENT", "VIDEO", "saved.mp4", 100, "saved caption");
            repository.savePage(new Page(first.key(), List.of(media), List.of(comment), 1, "601", true, "COMPLETE", List.of()));
            var before = repository.detail(first.key(), 10, 0);
            assertEquals("COMPLETE", before.commentCoverage()); assertEquals("COMPLETE", before.coverage());

            Post fresh = new Scenario(101, 42).read(); assertReadableComplete(fresh);
            assertEquals(first.key(), fresh.key()); repository.savePost(fresh);
            var after = repository.detail(first.key(), 10, 0);
            assertTrue(after.post().canReadComments()); assertEquals(before.commentMedia(), after.commentMedia());
            assertEquals(before.nextCursor(), after.nextCursor()); assertEquals(before.exhausted(), after.exhausted());
            assertEquals(1, after.indexedCommentMessages()); assertEquals(1, after.indexedCommentMedia());
            assertEquals("COMPLETE", after.commentCoverage()); assertEquals("COMPLETE", after.coverage());
            assertEquals(1, repository.posts("account", 10, 0).size());
        }
    }

    @Test void contradictoryFallbackMappingCannotErasePreviousCommentsOrTheirPersistedEofCheckpoint() {
        var original = new Scenario(100, 42); original.initial = CompletableFuture.completedFuture(thread(root(500, 100), root(501, 101)));
        Post first = original.read();
        try (Database database = new Database(new AppPaths(directory))) {
            var repository = new ResourceRepository(database); repository.savePost(first);
            var comment = new MessageRef(Long.toString(DISCUSSION), "601", "kept-link");
            var media = new Media("account:-2001:601:0", comment, "COMMENT", "VIDEO", "saved.mp4", 100, "kept caption");
            repository.savePage(new Page(first.key(), List.of(media), List.of(comment), 1, "601", true, "COMPLETE", List.of()));
            var before = repository.detail(first.key(), 10, 0);

            var conflicting = new Scenario(101, 42); var wrong = root(700, 100); wrong.chatId = -3001;
            ((TdApi.MessageOriginChannel) wrong.forwardInfo.origin).chatId = -9999;
            conflicting.fallback = CompletableFuture.completedFuture(new TdApi.MessageThreadInfo(-3001, 700, null, 0, new TdApi.Message[]{wrong}, null));
            Post invalid = conflicting.read(); assertFalse(invalid.canReadComments()); repository.savePost(invalid);
            var guarded = repository.detail(first.key(), 10, 0);
            assertEquals(Long.toString(DISCUSSION), guarded.post().discussionChatId()); assertFalse(guarded.post().canReadComments());
            assertEquals(before.commentMedia(), guarded.commentMedia()); assertEquals("601", guarded.nextCursor());
            assertEquals(1, guarded.indexedCommentMessages()); assertEquals(1, guarded.indexedCommentMedia());
            database.read(connection -> {
                try (var statement = connection.prepareStatement("SELECT next_cursor,exhausted FROM resource_scan_state WHERE post_key=?")) {
                    statement.setString(1, first.key());
                    try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals("601", rows.getString(1)); assertTrue(rows.getBoolean(2)); }
                }
                return null;
            });
            repository.savePost(new Scenario(101, 42).read());
            var restored = repository.detail(first.key(), 10, 0);
            assertEquals(before.commentMedia(), restored.commentMedia()); assertEquals("COMPLETE", restored.commentCoverage());
            assertEquals("COMPLETE", restored.coverage()); assertTrue(restored.exhausted());
            assertEquals(1, repository.posts("account", 10, 0).size());
        }
    }

    private static void assertReadableComplete(Post post) {
        assertTrue(post.canReadComments()); assertEquals("100", post.mainMessageId()); assertEquals(Long.toString(DISCUSSION), post.discussionChatId());
        assertEquals("500", post.discussionRootMessageId()); assertEquals("COMPLETE", post.mainCoverage()); assertTrue(post.albumEvidence().complete());
        assertEquals("account:-1001:album:42", post.key());
    }
    private static TdApi.MessageThreadInfo thread(TdApi.Message... roots) { return new TdApi.MessageThreadInfo(DISCUSSION, 500, null, 0, roots, null); }
    private static TdApi.Message root(long id, long originId) {
        var root = message(DISCUSSION, id, 0); root.isChannelPost = false;
        var origin = new TdApi.MessageOriginChannel(); origin.chatId = CHANNEL; origin.messageId = originId;
        root.forwardInfo = new TdApi.MessageForwardInfo(); root.forwardInfo.origin = origin; return root;
    }
    private static TdApi.Message message(long chat, long id, long album) {
        var message = new TdApi.Message(); message.chatId = chat; message.id = id; message.mediaAlbumId = album; message.isChannelPost = true;
        message.content = new TdApi.MessageText(new TdApi.FormattedText("member " + id, new TdApi.TextEntity[0]), null, null); return message;
    }

    private static final class Scenario implements ResourceReader.Gateway {
        final long anchorId, album;
        final TdApi.Chat chat = new TdApi.Chat(); final TdApi.Supergroup group = new TdApi.Supergroup();
        final TdApi.Message anchor;
        final List<Long> threadRequests = new ArrayList<>(); final List<TdApi.GetChatHistory> historyRequests = new ArrayList<>();
        CompletableFuture<TdApi.MessageThreadInfo> initial = CompletableFuture.failedFuture(new IllegalStateException(PRIVATE_ERROR));
        CompletableFuture<TdApi.MessageThreadInfo> fallback = CompletableFuture.completedFuture(thread(root(500, 100), root(501, 101)));
        final ResourceReader reader = new ResourceReader(this, () -> "account");
        Scenario(long anchorId, long album) {
            this.anchorId = anchorId; this.album = album; anchor = message(CHANNEL, anchorId, album);
            chat.id = CHANNEL; chat.title = "Synthetic channel"; chat.type = new TdApi.ChatTypeSupergroup(1001, true);
            group.id = 1001; group.isChannel = true; group.status = new TdApi.ChatMemberStatusCreator(false, true);
        }
        Post read() { return reader.channelPost(Long.toString(CHANNEL), Long.toString(anchorId)); }
        @Override @SuppressWarnings("unchecked") public <R extends TdApi.Object> CompletableFuture<R> read(TdApi.Function<R> query) {
            TdApi.Object response;
            if (query instanceof TdApi.GetMessage request) { assertEquals(CHANNEL, request.chatId); assertEquals(anchorId, request.messageId); response = anchor; }
            else if (query instanceof TdApi.GetChat) response = chat;
            else if (query instanceof TdApi.GetSupergroup) response = group;
            else if (query instanceof TdApi.GetMessageThread request) {
                assertEquals(CHANNEL, request.chatId); threadRequests.add(request.messageId);
                if (request.messageId == anchorId) return (CompletableFuture<R>) (CompletableFuture<?>) initial;
                assertEquals(100, request.messageId); return (CompletableFuture<R>) (CompletableFuture<?>) fallback;
            } else if (query instanceof TdApi.GetChatHistory request) {
                historyRequests.add(request); assertEquals(CHANNEL, request.chatId);
                var all = List.of(message(CHANNEL, 102, 0), message(CHANNEL, 101, album), message(CHANNEL, 100, album), message(CHANNEL, 99, 0));
                var selected = all.stream().filter(m -> request.offset < 0 ? m.id >= request.fromMessageId : m.id < request.fromMessageId).toArray(TdApi.Message[]::new);
                response = new TdApi.Messages(-1, selected);
            } else if (query instanceof TdApi.GetMessageLink request) {
                response = new TdApi.MessageLink("https://t.me/synthetic/" + request.chatId + "/" + request.messageId, true);
            } else throw new AssertionError("Only explicitly permitted metadata reads are allowed");
            return CompletableFuture.completedFuture((R) response);
        }
    }
}
