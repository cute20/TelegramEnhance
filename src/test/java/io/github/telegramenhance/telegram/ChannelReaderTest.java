package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.library.ChannelImportModels.*;
import it.tdlight.client.TelegramError;
import it.tdlight.jni.TdApi;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ChannelReaderTest {
    @Test void mainArchiveAndIndexedSeedsAreDeduplicatedAndOnlyManagedBroadcastChannelsRemain() {
        Scenario s = new Scenario();
        s.add(-1001, new TdApi.ChatMemberStatusCreator());
        s.add(-1002, new TdApi.ChatMemberStatusAdministrator());
        s.add(-1003, new TdApi.ChatMemberStatusMember());
        s.add(-1004, new TdApi.ChatMemberStatusAdministrator());
        ((TdApi.ChatTypeSupergroup) s.chats.get(-1004L).type).isChannel = false;
        s.add(-1005, new TdApi.ChatMemberStatusAdministrator());
        s.seeds = List.of("-1005", "-1001");
        s.main = new long[]{-1001, -1003, -1004};
        s.archive = new long[]{-1002, -1001};
        var result = s.reader().channels();
        assertEquals(Set.of("-1001", "-1002", "-1005"), new HashSet<>(result.items().stream().map(ManagedChannel::id).toList()));
        assertFalse(result.partial());
        assertEquals("MANAGED_CHANNELS", result.scope());
        assertEquals(1, s.queries.stream().filter(q -> q instanceof TdApi.GetChat c && c.chatId == -1001).count());
    }

    @Test void successfulLoadsWithoutEndConfirmationAreExplicitlyPartial() {
        Scenario s = new Scenario(); s.loadComplete = false;
        var result = s.reader().channels();
        assertTrue(result.partial()); assertFalse(result.warnings().isEmpty());
        assertEquals(4, s.queries.stream().filter(q -> q instanceof TdApi.LoadChats).count());
        assertTrue(s.queries.stream().filter(q -> q instanceof TdApi.GetChats).allMatch(q -> ((TdApi.GetChats) q).limit == 200));
    }

    @Test void listFailureIsNotMisrepresentedAsAnEmptyCompleteAccount() {
        Scenario s = new Scenario(); s.loadFailure = 429;
        var result = s.reader().channels();
        assertTrue(result.partial()); assertFalse(result.warnings().isEmpty());
        assertFalse(result.warnings().toString().contains("private native failure"));
    }

    @Test void chatAndSupergroupMismatchesNeverBecomeManagedChannels() {
        Scenario s = new Scenario(); s.add(-1001, new TdApi.ChatMemberStatusCreator()); s.main = new long[]{-1001};
        s.groups.get(1001L).id = 9001;
        var result = s.reader().channels();
        assertTrue(result.items().isEmpty()); assertTrue(result.partial());
        s.groups.get(1001L).id = 1001; s.chats.get(-1001L).id = -9999;
        assertTrue(s.reader().channels().items().isEmpty());
    }

    @Test void previewGroupsAlbumMembersAndFindsTheCaptionWithoutSavingAnyMetadata() {
        Scenario s = new Scenario(); s.add(-1001, new TdApi.ChatMemberStatusAdministrator());
        s.pages.add(messages(message(30, 7, ""), message(29, 7, "#album caption"), message(28, 0, "ordinary post")));
        var result = s.reader().preview("-1001");
        assertEquals(2, result.items().size());
        assertEquals("30", result.items().getFirst().messageId());
        assertEquals("7", result.items().getFirst().albumId());
        assertEquals("#album caption", result.items().getFirst().title());
        assertNull(result.items().getLast().albumId());
        assertEquals("RECENT_BATCH", result.scope()); assertEquals(10, result.limit());
        assertEquals(List.of(0L, 28L, 28L), s.historyCursors());
    }

    @Test void shortPagesAndServiceOnlyPagesContinueWhileTheirCursorAdvances() {
        Scenario s = new Scenario(); s.add(-1001, new TdApi.ChatMemberStatusCreator());
        var service = message(50, 0, ""); service.content = new TdApi.MessagePinMessage();
        s.pages.add(messages(service)); s.pages.add(messages(message(40, 0, "resource")));
        var result = s.reader().preview("-1001");
        assertEquals(1, result.items().size()); assertEquals("40", result.items().getFirst().messageId());
        assertEquals(List.of(0L, 50L, 40L, 40L), s.historyCursors());
    }

    @Test void fewerThanTenPostsRequireAConfirmedEmptyPageRatherThanAShortPage() {
        Scenario s = new Scenario(); s.add(-1001, new TdApi.ChatMemberStatusCreator());
        s.pages.add(messages()); s.pages.add(messages(message(40, 0, "arrived after first empty")));
        var result = s.reader().preview("-1001");
        assertEquals(1, result.items().size()); assertEquals(List.of(0L, 0L, 40L, 40L), s.historyCursors());
    }

    @Test void atMostTenLogicalPostsAreReturnedAndTheTenthAlbumCaptionCanBeCompleted() {
        Scenario s = new Scenario(); s.add(-1001, new TdApi.ChatMemberStatusCreator());
        List<TdApi.Message> page = new ArrayList<>();
        for (long i = 100; i > 91; i--) page.add(message(i, 0, "post " + i));
        page.add(message(91, 8, "")); page.add(message(90, 8, "tenth album")); page.add(message(89, 0, "eleventh"));
        s.pages.add(new TdApi.Messages(12, page.toArray(TdApi.Message[]::new)));
        var result = s.reader().preview("-1001");
        assertEquals(10, result.items().size()); assertEquals("tenth album", result.items().getLast().title());
        assertEquals(1, s.historyCursors().size());
    }

    @Test void albumPreviewUsesTheOldestNonBlankCaptionLikeTheImportedPost() {
        Scenario s = new Scenario(); s.add(-1001, new TdApi.ChatMemberStatusCreator());
        s.pages.add(messages(message(40, 8, "newer member"), message(39, 8, "first caption")));
        assertEquals("first caption", s.reader().preview("-1001").items().getFirst().title());
    }

    @ParameterizedTest @ValueSource(strings = {"MessageGiveaway", "MessageGiveawayWinners", "MessageChecklist",
            "MessageExpiredPhoto", "MessageExpiredVideo", "MessageExpiredVideoNote", "MessageExpiredVoiceNote"})
    void ordinaryPostsOutsideTheFirstMediaTypesAreNotSilentlySkipped(String name) throws Exception {
        Scenario s = new Scenario(); s.add(-1001, new TdApi.ChatMemberStatusCreator());
        var m = message(40, 0, "");
        m.content = (TdApi.MessageContent) Class.forName("it.tdlight.jni.TdApi$" + name).getConstructor().newInstance();
        s.pages.add(messages(m));
        assertEquals(1, s.reader().preview("-1001").items().size());
    }

    @Test void historyIsBoundedEvenWhenEveryMessageIsAServiceMessage() {
        Scenario s = new Scenario(); s.add(-1001, new TdApi.ChatMemberStatusCreator());
        for (long i = 100; i > 94; i--) { var m = message(i, 0, ""); m.content = new TdApi.MessagePinMessage(); s.pages.add(messages(m)); }
        var result = s.reader().preview("-1001");
        assertTrue(result.items().isEmpty()); assertFalse(result.warnings().isEmpty());
        assertEquals(6, s.historyCursors().size());
    }

    @Test void aRepeatedCursorPausesWithAnHonestPreviewWarning() {
        Scenario s = new Scenario(); s.add(-1001, new TdApi.ChatMemberStatusCreator());
        s.pages.add(messages(message(40, 0, "one"))); s.pages.add(messages(message(40, 0, "repeated anchor")));
        var result = s.reader().preview("-1001");
        assertEquals(1, result.items().size()); assertFalse(result.warnings().isEmpty()); assertEquals(2, s.historyCursors().size());
    }

    @ParameterizedTest @ValueSource(strings = {"foreign-chat", "ascending", "duplicate", "missing", "over-limit"})
    void malformedHistoryCannotBePresentedAsAValidPreview(String failure) {
        Scenario s = new Scenario(); s.add(-1001, new TdApi.ChatMemberStatusCreator());
        if (failure.equals("foreign-chat")) { var m = message(40, 0, "private"); m.chatId = -1002; s.pages.add(messages(m)); }
        if (failure.equals("ascending")) s.pages.add(messages(message(40, 0, "a"), message(41, 0, "b")));
        if (failure.equals("duplicate")) s.pages.add(messages(message(40, 0, "a"), message(40, 2, "b")));
        if (failure.equals("missing")) s.pages.add(new TdApi.Messages(0, null));
        if (failure.equals("over-limit")) s.pages.add(new TdApi.Messages(51, new TdApi.Message[51]));
        assertThrows(IllegalArgumentException.class, () -> s.reader().preview("-1001"));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"0", "01", "+1", "-0", " 1", "1 ", "9223372036854775808"})
    void invalidChannelIdsDoNotCallNative(String value) {
        Scenario s = new Scenario(); assertThrows(IllegalArgumentException.class, () -> s.reader().preview(value)); assertTrue(s.queries.isEmpty());
    }

    @Test void permissionLossRejectsThePreviewBeforeAnyHistoryRequest() {
        Scenario s = new Scenario(); s.add(-1001, new TdApi.ChatMemberStatusMember());
        assertThrows(IllegalArgumentException.class, () -> s.reader().preview("-1001")); assertTrue(s.historyCursors().isEmpty());
    }

    @Test void accountAndSameAccountSessionChangesRejectLateDiscoveryAndPreviewResponses() {
        for (boolean preview : List.of(false, true)) for (boolean session : List.of(false, true)) {
            Scenario s = new Scenario(); s.add(-1001, new TdApi.ChatMemberStatusCreator()); s.main = new long[]{-1001};
            s.after = query -> { if (session) s.generation++; else s.account = "different-account"; };
            assertThrows(IllegalArgumentException.class, () -> { if (preview) s.reader().preview("-1001"); else s.reader().channels(); });
            assertEquals(1, s.queries.size());
        }
    }

    @Test void nativeFailureIsSanitizedAndDoesNotAutomaticallyRetryPreview() {
        Scenario s = new Scenario(); s.fail = true;
        var error = assertThrows(IllegalArgumentException.class, () -> s.reader().preview("-1001"));
        assertFalse(error.toString().contains("private native failure")); assertEquals(1, s.queries.size());
    }

    @Test void zeroBudgetDoesNotStartAnyNativeRequests() {
        Scenario s = new Scenario();
        assertThrows(IllegalArgumentException.class, () -> s.reader(0).preview("-1001")); assertTrue(s.queries.isEmpty());
        assertTrue(s.reader(0).channels().partial()); assertTrue(s.queries.isEmpty());
    }

    static TdApi.Messages messages(TdApi.Message... values) { return new TdApi.Messages(values.length, values); }
    static TdApi.Message message(long id, long album, String caption) {
        TdApi.Message result = new TdApi.Message(); result.chatId = -1001; result.id = id;
        result.isChannelPost = true; result.mediaAlbumId = album;
        if (album == 0) result.content = new TdApi.MessageText(new TdApi.FormattedText(caption, new TdApi.TextEntity[0]), null, null);
        else { var photo = new TdApi.MessagePhoto(); photo.caption = new TdApi.FormattedText(caption, new TdApi.TextEntity[0]); result.content = photo; }
        return result;
    }

    static final class Scenario implements ChannelReader.Gateway {
        String account = "account"; long generation = 7; List<String> seeds = List.of();
        long[] main = new long[0], archive = new long[0]; boolean loadComplete = true, fail; int loadFailure;
        final Map<Long, TdApi.Chat> chats = new HashMap<>(); final Map<Long, TdApi.Supergroup> groups = new HashMap<>();
        final List<TdApi.Function<?>> queries = new ArrayList<>(); final Deque<TdApi.Messages> pages = new ArrayDeque<>();
        Consumer<TdApi.Function<?>> after = query -> {};
        ChannelReader reader() { return reader(35_000); }
        ChannelReader reader(long budget) { return new ChannelReader(this, () -> account, () -> generation, () -> seeds, budget); }
        void add(long id, TdApi.ChatMemberStatus status) {
            TdApi.Chat chat = new TdApi.Chat(); chat.id = id; chat.title = "synthetic channel";
            chat.type = new TdApi.ChatTypeSupergroup(-id, true); chats.put(id, chat);
            TdApi.Supergroup group = new TdApi.Supergroup(); group.id = -id; group.isChannel = true; group.status = status; groups.put(-id, group);
        }
        List<Long> historyCursors() { return queries.stream().filter(q -> q instanceof TdApi.GetChatHistory).map(q -> ((TdApi.GetChatHistory) q).fromMessageId).toList(); }
        @SuppressWarnings("unchecked") public <R extends TdApi.Object> CompletableFuture<R> read(long session, TdApi.Function<R> query) {
            assertEquals(generation, session); queries.add(query);
            if (fail) return CompletableFuture.failedFuture(new IllegalStateException("private native failure"));
            TdApi.Object result;
            if (query instanceof TdApi.LoadChats) {
                after.accept(query);
                return loadFailure != 0 || loadComplete ? CompletableFuture.failedFuture(new TelegramError(new TdApi.Error(loadFailure != 0 ? loadFailure : 404, "private native failure"))) : (CompletableFuture<R>) CompletableFuture.completedFuture(new TdApi.Ok());
            }
            if (query instanceof TdApi.GetChats c) result = new TdApi.Chats(0, c.chatList instanceof TdApi.ChatListMain ? main : archive);
            else if (query instanceof TdApi.GetChat c) result = chats.get(c.chatId);
            else if (query instanceof TdApi.GetSupergroup g) result = groups.get(g.supergroupId);
            else if (query instanceof TdApi.GetChatHistory h) { assertFalse(h.onlyLocal); assertEquals(50, h.limit); result = pages.isEmpty() ? messages() : pages.removeFirst(); }
            else throw new AssertionError("Not a metadata-only request");
            after.accept(query); return CompletableFuture.completedFuture((R) result);
        }
    }
}
