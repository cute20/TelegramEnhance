package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;

import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AlbumReaderTest {
    private static final long CHANNEL = -1001;
    private static final long ALBUM = 9876;

    @Test
    void singleMessageRequiresNoHistoryReads() {
        var scenario = new Scenario(query -> { throw new AssertionError("Single message must not read history"); });
        TdApi.Message anchor = message(101, 0);
        var result = scenario.reader().read(anchor, scenario.chat, null);
        assertEquals(List.of(anchor), result.members());
        assertTrue(result.complete());
        assertEquals("SINGLE_MESSAGE", result.method());
        assertTrue(result.warnings().isEmpty());
        assertTrue(scenario.requests.isEmpty());
    }

    @Test
    void bothAdjacentNonAlbumMessagesProveTheThreeMemberAlbum() {
        var scenario = new Scenario(query -> query.offset < 0
            ? page(message(200, 0), message(102, ALBUM), message(101, ALBUM), message(100, ALBUM), message(90, 0))
            : page(message(100, ALBUM), message(90, 0)));
        var result = scenario.reader().read(message(101, ALBUM), scenario.chat, null);
        assertEquals(List.of(100L, 101L, 102L), ids(result));
        assertTrue(result.complete());
        assertEquals("ADJACENT_BOUNDARIES", result.method());
        assertEquals("200", result.newerBoundaryId());
        assertEquals("90", result.olderBoundaryId());
        assertTrue(result.warnings().isEmpty());
        assertEquals(2, scenario.requests.size());
        assertTrue(scenario.requests.stream().allMatch(q -> q.chatId == CHANNEL && !q.onlyLocal && q.limit <= 30));
    }

    @Test
    void shortPagesAndDuplicateAnchorsContinueFromTheObservedEdges() {
        var scenario = new Scenario(query -> {
            if (query.offset == 0) {
                return switch ((int) query.fromMessageId) {
                    case 102 -> page(message(101, ALBUM), message(101, ALBUM));
                    case 101 -> page(message(100, ALBUM));
                    case 100 -> page(message(90, 0));
                    default -> throw new AssertionError("Unexpected older cursor");
                };
            }
            if (query.fromMessageId == 102) {
                return query.limit == 2 ? page(message(103, ALBUM), message(102, ALBUM)) : page(message(102, ALBUM));
            }
            if (query.fromMessageId == 103) return page(message(104, 0), message(103, ALBUM));
            throw new AssertionError("Unexpected newer cursor");
        });
        // First new-side batch has only its anchor. The next repeated batch can progress.
        final int[] attempts = {0};
        Function<TdApi.GetChatHistory, TdApi.Messages> original = scenario.responses;
        scenario.responses = query -> query.offset < 0 && query.fromMessageId == 102 && ++attempts[0] > 1
            ? page(message(103, ALBUM), message(102, ALBUM)) : original.apply(query);
        var result = scenario.reader().read(message(102, ALBUM), scenario.chat, null);
        assertTrue(result.complete());
        assertEquals(List.of(100L, 101L, 102L, 103L), ids(result));
        assertEquals(List.of(102L, 101L, 100L), scenario.requests.stream().filter(q -> q.offset == 0).map(q -> q.fromMessageId).toList());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void negativeOffsetBatchWithoutAnchorCannotJumpOverUnknownNeighbors() {
        var scenario = new Scenario(query -> {
            if (query.offset == 0) return page(message(100, ALBUM), message(90, 0));
            if (query.limit == 30) return page(message(900, 0));
            if (query.fromMessageId == 101) return page(message(102, ALBUM), message(101, ALBUM));
            if (query.fromMessageId == 102) return page(message(200, 0), message(102, ALBUM));
            throw new AssertionError("Unexpected cursor");
        });
        var result = scenario.reader().read(message(101, ALBUM), scenario.chat, null);
        assertTrue(result.complete());
        assertEquals(List.of(100L, 101L, 102L), ids(result));
        assertEquals("200", result.newerBoundaryId(), "The distant 900 response cannot certify a boundary");
        assertTrue(scenario.requests.stream().anyMatch(q -> q.offset == -2 && q.limit == 2 && q.fromMessageId == 101));
    }

    @Test
    void emptyRepeatedPagesAndCachedLastMessageDoNotProveAnEndpoint() {
        var scenario = new Scenario(query -> page());
        scenario.chat.lastMessage = message(101, ALBUM);
        var result = scenario.reader().read(message(101, ALBUM), scenario.chat, null);
        assertFalse(result.complete());
        assertEquals("PARTIAL_SCAN", result.method());
        assertEquals(List.of(101L), ids(result));
        assertNull(result.newerBoundaryId());
        assertNull(result.olderBoundaryId());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("新侧")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("旧侧")));
        assertTrue(scenario.requests.size() <= 8);
    }

    @Test
    void pageBudgetKeepsTheMembersAlreadyReadAndDoesNotClaimComplete() {
        var scenario = new Scenario(query -> query.offset == 0
            ? page(message(query.fromMessageId - 1, ALBUM))
            : page(message(query.fromMessageId + 1, ALBUM), message(query.fromMessageId, ALBUM)));
        var result = scenario.reader().read(message(101, ALBUM), scenario.chat, null);
        assertFalse(result.complete());
        assertEquals(8, scenario.requests.size());
        assertEquals(9, result.members().size());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("页数上限")));
    }

    @Test
    void upstreamFailureAndTimeoutReturnPartialWithoutLeakingTheOriginalError() {
        var scenario = new Scenario(query -> { throw new IllegalStateException("private-message-body-or-token"); });
        var failed = scenario.reader().read(message(101, ALBUM), scenario.chat, null);
        assertFalse(failed.complete());
        assertEquals(List.of(101L), ids(failed));
        assertTrue(failed.warnings().stream().noneMatch(w -> w.contains("private-message-body-or-token")));

        ResourceReader.Gateway neverCompletes = new ResourceReader.Gateway() {
            @Override public <R extends TdApi.Object> CompletableFuture<R> read(TdApi.Function<R> query) { return new CompletableFuture<>(); }
        };
        var timedOut = new AlbumReader(neverCompletes, 8, 5).read(message(101, ALBUM), scenario.chat, null);
        assertFalse(timedOut.complete());
        assertEquals(List.of(101L), ids(timedOut));
        assertTrue(timedOut.warnings().stream().anyMatch(w -> w.contains("超时") || w.contains("时间上限")));
    }

    @Test
    void wrongChatOrContradictoryAlbumIdentityCannotBecomeComplete() {
        var scenario = new Scenario(query -> {
            if (query.offset == 0) return page(message(90, 0));
            TdApi.Message wrong = message(200, 0);
            wrong.chatId = -9999;
            return page(wrong, message(101, ALBUM));
        });
        var result = scenario.reader().read(message(101, ALBUM), scenario.chat, null);
        assertFalse(result.complete());
        assertNull(result.newerBoundaryId());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("跨聊天")));
    }

    @Test
    void threadForwardOriginsCrossCheckIdentityButEqualCountsDoNotProveCompleteness() {
        var empty = new Scenario(query -> page());
        TdApi.Message root = root(101, CHANNEL);
        var thread = new TdApi.MessageThreadInfo(-2001, 500, null, 0, new TdApi.Message[]{root}, null);
        var partial = empty.reader().read(message(101, ALBUM), empty.chat, thread);
        assertFalse(partial.complete(), "One channel member and one matching discussion root are not sufficient proof");

        var complete = boundedScenario();
        root.forwardInfo.origin = new TdApi.MessageOriginChannel(CHANNEL, 999, "");
        var missing = complete.reader().read(message(101, ALBUM), complete.chat, thread);
        assertFalse(missing.complete());
        assertTrue(missing.warnings().stream().anyMatch(w -> w.contains("尚未取得")));

        root.forwardInfo.origin = new TdApi.MessageOriginChannel(-9999, 101, "");
        var mismatch = complete.reader().read(message(101, ALBUM), complete.chat, thread);
        assertFalse(mismatch.complete());
        assertTrue(mismatch.warnings().stream().anyMatch(w -> w.contains("原频道身份")));

        root.forwardInfo.origin = new TdApi.MessageOriginChannel(CHANNEL, 101, "");
        assertTrue(complete.reader().read(message(101, ALBUM), complete.chat, thread).complete());
    }

    @Test
    void disconnectedObservedMemberBeyondBoundaryKeepsTheAlbumPartial() {
        var scenario = new Scenario(query -> {
            if (query.offset == 0) return page(message(100, ALBUM), message(90, 0));
            if (query.limit == 30) return page(message(900, ALBUM));
            return page(message(200, 0), message(101, ALBUM));
        });
        var result = scenario.reader().read(message(101, ALBUM), scenario.chat, null);
        assertFalse(result.complete());
        assertTrue(ids(result).contains(900L));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("超出邻接边界")));
    }

    @Test
    void immediateFallbackWithoutAnchorAndOutOfOrderHistoryCannotProveAdjacency() {
        var noAnchor = new Scenario(query -> query.offset == 0
            ? page(message(90, 0)) : page(message(900, 0)));
        var missing = noAnchor.reader().read(message(101, ALBUM), noAnchor.chat, null);
        assertFalse(missing.complete());
        assertNull(missing.newerBoundaryId());
        assertTrue(missing.warnings().stream().anyMatch(w -> w.contains("未包含当前锚点")));

        var unordered = new Scenario(query -> query.offset == 0 ? page(message(90, 0))
            : page(message(101, ALBUM), message(200, 0)));
        var result = unordered.reader().read(message(101, ALBUM), unordered.chat, null);
        assertFalse(result.complete());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("乱序")));
    }

    private static Scenario boundedScenario() {
        return new Scenario(query -> query.offset < 0
            ? page(message(200, 0), message(101, ALBUM)) : page(message(90, 0)));
    }
    private static List<Long> ids(AlbumReader.Result result) { return result.members().stream().map(m -> m.id).toList(); }
    private static TdApi.Message message(long id, long album) {
        var message = new TdApi.Message();
        message.chatId = CHANNEL; message.id = id; message.mediaAlbumId = album; message.isChannelPost = true;
        return message;
    }
    private static TdApi.Message root(long originId, long channel) {
        TdApi.Message root = message(500, 12345);
        root.chatId = -2001;
        root.forwardInfo = new TdApi.MessageForwardInfo(new TdApi.MessageOriginChannel(channel, originId, ""), 0, null, "");
        return root;
    }
    private static TdApi.Messages page(TdApi.Message... messages) { return new TdApi.Messages(-1, messages); }

    private static final class Scenario implements ResourceReader.Gateway {
        final TdApi.Chat chat = new TdApi.Chat();
        final List<TdApi.GetChatHistory> requests = new ArrayList<>();
        Function<TdApi.GetChatHistory, TdApi.Messages> responses;
        Scenario(Function<TdApi.GetChatHistory, TdApi.Messages> responses) { this.responses = responses; chat.id = CHANNEL; }
        AlbumReader reader() { return new AlbumReader(this); }
        @Override @SuppressWarnings("unchecked")
        public <R extends TdApi.Object> CompletableFuture<R> read(TdApi.Function<R> query) {
            if (!(query instanceof TdApi.GetChatHistory history)) throw new AssertionError("Only chat history reads are allowed");
            requests.add(history);
            return CompletableFuture.completedFuture((R) responses.apply(history));
        }
    }
}
