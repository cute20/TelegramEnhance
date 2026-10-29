package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.telegramenhance.telegram.ChannelReaderTest.message;
import static io.github.telegramenhance.telegram.ChannelReaderTest.messages;

import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import it.tdlight.jni.TdApi;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChannelHistoryReaderTest {
    @Test void successivePagesNeverConsumeTheFirstPostOfTheFollowingPage() {
        var s = scenario();
        s.pages.add(range(30, 19)); s.pages.add(range(20, 9)); s.pages.add(range(10, 1));
        var reader = s.reader();
        HistoryPage first = reader.historyPage("-1001", null);
        HistoryPage second = reader.historyPage("-1001", first.nextCursor());
        HistoryPage third = reader.historyPage("-1001", second.nextCursor());
        HistoryPage end = reader.historyPage("-1001", third.nextCursor());
        Set<String> ids = new HashSet<>();
        for (HistoryPage page : List.of(first, second, third)) {
            assertEquals(10, page.items().size()); assertTrue(page.hasMore()); assertFalse(page.exhausted());
            page.items().forEach(item -> assertTrue(ids.add(item.messageId())));
        }
        assertEquals(30, ids.size()); assertTrue(ids.contains("20")); assertTrue(ids.contains("10"));
        assertTrue(end.exhausted()); assertFalse(end.hasMore()); assertNull(end.nextCursor());
        assertEquals(List.of(0L, 21L, 11L, 1L, 1L), s.historyCursors());
        assertEquals("CHANNEL_PAGE", first.scope());
    }

    @Test void theTenthAlbumIsFinishedBeforeReturningTheNextCursor() {
        var s = scenario(); List<TdApi.Message> first = new ArrayList<>();
        for (long id = 100; id > 91; id--) first.add(message(id, 0, "single"));
        first.add(message(91, 8, "newer member"));
        s.pages.add(new TdApi.Messages(-1, first.toArray(TdApi.Message[]::new)));
        s.pages.add(messages(message(90, 8, "oldest caption"), message(89, 0, "next post")));
        var page = s.reader().historyPage("-1001", null);
        assertEquals(10, page.items().size()); assertEquals("oldest caption", page.items().getLast().title());
        assertTrue(ChannelReader.advancesCursor(null, page.nextCursor(), "account", "-1001"));
        s.pages.add(messages(message(89, 0, "next post"), message(88, 0, "older post")));
        var next = s.reader().historyPage("-1001", page.nextCursor());
        assertEquals(List.of("89", "88"), next.items().stream().map(i -> i.messageId()).toList());
        assertEquals(List.of(0L, 91L, 90L, 88L, 88L), s.historyCursors());
    }

    @Test void anAlbumTruncatedByTheBoundIsNotDeliveredAgainOnTheFollowingPage() {
        var s = scenario();
        for (long id = 100; id >= 95; id--) s.pages.add(messages(message(id, -42, "member " + id)));
        var first = s.reader().historyPage("-1001", null);
        assertEquals(1, first.items().size()); assertTrue(first.hasMore()); assertFalse(first.exhausted());
        s.pages.add(messages(message(94, -42, "remaining"), message(93, -42, "remaining"), message(92, 0, "next")));
        var second = s.reader().historyPage("-1001", first.nextCursor());
        assertEquals(1, second.items().size()); assertEquals("92", second.items().getFirst().messageId());
        assertTrue(second.exhausted());
    }

    @Test void serviceOnlyPagesCanAdvanceWithoutBeingMistakenForAnEmptyChannel() {
        var s = scenario();
        for (long id = 100; id >= 95; id--) { var item = message(id, 0, ""); item.content = new TdApi.MessagePinMessage(); s.pages.add(messages(item)); }
        var first = s.reader().historyPage("-1001", null);
        assertTrue(first.items().isEmpty()); assertTrue(first.hasMore()); assertFalse(first.exhausted());
        assertTrue(ChannelReader.advancesCursor(null, first.nextCursor(), "account", "-1001"));
        s.pages.add(messages(message(94, 0, "real post")));
        var next = s.reader().historyPage("-1001", first.nextCursor());
        assertEquals("94", next.items().getFirst().messageId()); assertTrue(next.exhausted());
    }

    @Test void aHistoryEndpointStillRequiresTheSecondEmptyResponse() {
        var s = scenario(); s.pages.add(messages()); s.pages.add(messages(message(42, 0, "available after retry")));
        var page = s.reader().historyPage("-1001", null);
        assertEquals(1, page.items().size()); assertTrue(page.exhausted());
        assertEquals(List.of(0L, 0L, 42L, 42L), s.historyCursors());
    }

    @Test void tokensSurviveSameAccountClientRestartButCannotBeReusedForAnotherAccountOrChannel() {
        var s = scenario(); s.pages.add(range(100, 89));
        var first = s.reader().historyPage("-1001", null);
        int calls = s.queries.size();
        assertThrows(IllegalArgumentException.class, () -> s.reader().historyPage("-1002", first.nextCursor()));
        assertEquals(calls, s.queries.size());
        s.account = "another-account";
        assertThrows(IllegalArgumentException.class, () -> s.reader().historyPage("-1001", first.nextCursor()));
        assertEquals(calls, s.queries.size());
        s.account = "account"; s.generation++;
        s.pages.add(messages(message(90, 0, "older")));
        assertEquals("90", s.reader().historyPage("-1001", first.nextCursor()).items().getFirst().messageId());
    }

    @ParameterizedTest @ValueSource(strings = {"", "invalid!", "djF8YWNjb3VudHwtMTAwMXwwfDA", "v1|account|-1001|40|0"})
    void invalidOpaquePositionsNeverReachNativeHistory(String token) {
        var s = scenario(); assertThrows(IllegalArgumentException.class, () -> s.reader().historyPage("-1001", token));
        assertTrue(s.queries.isEmpty());
    }

    @Test void checkpointValidationComparesRawLongPositionsAndTheirAccountAndChannelBindings() {
        String high = token("account", "-1001", "9223372036854775806", "0");
        String low = token("account", "-1001", "9223372036854775805", "-8");
        assertTrue(ChannelReader.advancesCursor(null, high, "account", "-1001"));
        assertTrue(ChannelReader.advancesCursor(high, low, "account", "-1001"));
        assertFalse(ChannelReader.advancesCursor(low, high, "account", "-1001"));
        assertFalse(ChannelReader.advancesCursor(high, token("account", "-1001", "9223372036854775806", "8"), "account", "-1001"));
        assertFalse(ChannelReader.advancesCursor(high, low, "other", "-1001"));
        assertFalse(ChannelReader.advancesCursor(high, low, "account", "-1002"));
        assertFalse(ChannelReader.advancesCursor(high, null, "account", "-1001"));
    }

    @Test void temporaryUnsentPostsAreSkippedWithAWarningRatherThanImportedAsConfirmedMessages() {
        var s = scenario(); var temporary = message(100, 0, "not sent");
        temporary.sendingState = new TdApi.MessageSendingStatePending();
        s.pages.add(messages(temporary, message(99, 0, "confirmed")));
        var result = s.reader().historyPage("-1001", null);
        assertEquals(List.of("99"), result.items().stream().map(c -> c.messageId()).toList());
        assertFalse(result.warnings().isEmpty());
    }

    private static ChannelReaderTest.Scenario scenario() {
        var s = new ChannelReaderTest.Scenario(); s.add(-1001, new TdApi.ChatMemberStatusCreator()); return s;
    }
    private static TdApi.Messages range(long high, long low) {
        List<TdApi.Message> values = new ArrayList<>();
        for (long id = high; id >= low; id--) values.add(message(id, 0, "synthetic"));
        return new TdApi.Messages(-1, values.toArray(TdApi.Message[]::new));
    }
    private static String token(String account, String channel, String from, String album) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("v1|" + account + "|" + channel + "|" + from + "|" + album).getBytes(StandardCharsets.UTF_8));
    }
}
