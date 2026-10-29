package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.library.ResourceRepository;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.telegram.TelegramService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadSourceServiceTest {
    @TempDir Path directory;
    private static final String LINK = "https://t.me/synthetic/1";
    @Test void observedCommentThumbnailGrantsSurviveAppendingAnotherPageButCannotBeForged() throws Exception {
        try (var f = new Fixture()) {
            var firstMedia = new Media("observed-first", new MessageRef("-2001", "601", "private-link"), "COMMENT", "VIDEO", "private-name", 123, "private-caption");
            when(f.reader.comments(post(true), null)).thenReturn(new Page(post(true).key(), List.of(firstMedia), List.of(firstMedia.message()), 1, "601", false, "PARTIAL", List.of()));
            var first = await(f.service.preview(LINK));
            var source = await(f.service.thumbnailSource(first.id(), 1, firstMedia.key()));
            assertEquals("", source.media().caption()); assertEquals("", source.media().fileName()); assertEquals("", source.media().message().link());
            when(f.reader.comments(post(true), "601")).thenReturn(page("501", false, "501"));
            await(f.service.next(first.id(), "601", 1));
            assertEquals(source, await(f.service.thumbnailSource(first.id(), 1, firstMedia.key())));
            assertThrows(ExecutionException.class, () -> await(f.service.thumbnailSource(first.id(), 2, firstMedia.key())));
            assertThrows(ExecutionException.class, () -> await(f.service.thumbnailSource(first.id(), 1, "unobserved-key")));
            verifyNoInteractions(f.repository);
        }
    }
    @Test void mainThumbnailUsesItsExactObservedMemberAndItsIndependentZeroPageScope() throws Exception {
        try (var f = new Fixture()) {
            var base = post(false); var main = new Media("main", base.mainMessages().getFirst(), "MAIN", "PHOTO", "image", 100, "");
            var observed = new Post(base.key(), base.accountId(), base.channelId(), base.channelTitle(), base.mainMessageId(), base.title(), base.text(), base.albumId(), base.mainMessages(), List.of(main), base.discussionChatId(), base.discussionRootMessageId(), base.discussionRoots(), false, base.coverage(), base.warnings());
            when(f.reader.probeDownloadSource(LINK)).thenReturn(observed);
            var preview = await(f.service.preview(LINK));
            assertEquals(main, await(f.service.thumbnailSource(preview.id(), 0, "main")).media());
            assertThrows(ExecutionException.class, () -> await(f.service.thumbnailSource(preview.id(), 1, "main")));
        }
    }
    @Test void thumbnailLookupRejectsExpiredAndReplacementSessionsEvenForTheSameAccount() throws Exception {
        for (boolean expired : List.of(true, false)) try (var f = new Fixture()) {
            var media = new Media("observed", new MessageRef("-2001", "601", ""), "COMMENT", "PHOTO", "", 1, "");
            when(f.reader.comments(post(true), null)).thenReturn(new Page(post(true).key(), List.of(media), List.of(media.message()), 1, "601", false, "PARTIAL", List.of()));
            var preview = await(f.service.preview(LINK));
            if (expired) f.clock.addAndGet(TimeUnit.MINUTES.toNanos(10)); else f.generation.incrementAndGet();
            assertThrows(ExecutionException.class, () -> await(f.service.thumbnailSource(preview.id(), 1, "observed")));
        }
    }
    @Test void thumbnailGrantMemoryIsBoundedAndUnsupportedOrUnobservedMediaNeverReceivesAGrant() throws Exception {
        try (var f = new Fixture()) {
            var first = await(f.service.preview(LINK)); var state = first; long edge = Long.parseLong(first.comments().nextCursor());
            String oldest = null, newest = null;
            for (int batch = 0; batch < 21; batch++) {
                var media = new java.util.ArrayList<Media>(); var refs = new java.util.ArrayList<MessageRef>();
                for (int i = 1; i <= 50; i++) { var ref = new MessageRef("-2001", Long.toString(edge - i), ""); refs.add(ref); media.add(new Media("m" + ref.messageId(), ref, "COMMENT", "PHOTO", "", 1, "")); }
                if (oldest == null) oldest = media.getFirst().key(); newest = media.getLast().key();
                var page = new Page(post(true).key(), media, refs, 50, Long.toString(edge - 50), false, "PARTIAL", List.of());
                when(f.reader.comments(post(true), Long.toString(edge))).thenReturn(page);
                state = await(f.service.next(state.id(), Long.toString(edge), state.pageNumber())); edge -= 50;
            }
            var id = first.id(); var oldKey = oldest;
            assertThrows(ExecutionException.class, () -> await(f.service.thumbnailSource(id, 2, oldKey)));
            assertEquals(newest, await(f.service.thumbnailSource(id, state.pageNumber(), newest)).media().key());
        }
    }
    @Test void previewAndPagingUseTheExistingReadQueueWithoutWritingBusinessIndexes() throws Exception {
        try (Database database = new Database(new AppPaths(directory)); Fixture f = new Fixture(new ResourceRepository(database))) {
            when(f.reader.probeDownloadSource(LINK)).thenAnswer(call -> { assertEquals("resource-reader", Thread.currentThread().getName()); return post(true); });
            var first = await(f.service.preview(LINK));
            assertEquals(1, first.pageNumber()); assertEquals(1, first.observedMessages()); assertTrue(first.canContinue()); assertFalse(first.exhausted());
            when(f.reader.comments(post(true), "9007199254740999")).thenReturn(page("9007199254740998", false, "9007199254740998"));
            var second = await(f.service.next(first.id(), first.comments().nextCursor(), first.pageNumber()));
            assertEquals(2, second.pageNumber()); assertEquals(2, second.observedMessages()); assertEquals(first.id(), second.id());
            assertTrue(f.repository.posts("account", 20, 0).isEmpty());
            database.read(connection -> {
                for (var table : List.of("resource_posts", "resource_comment_messages", "resource_comment_media", "resource_scan_state", "resource_post_search", "resource_post_tags")) {
                    try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
                }
                return null;
            });
        }
    }
    @Test void onlyConfirmedEofEndsThePreviewAndRepeatingAnOldPageCannotAdvanceIt() throws Exception {
        try (var f = new Fixture()) {
            var first = await(f.service.preview(LINK)); assertFalse(first.exhausted());
            when(f.reader.comments(post(true), first.comments().nextCursor())).thenReturn(page(first.comments().nextCursor(), true));
            var last = await(f.service.next(first.id(), first.comments().nextCursor(), 1));
            assertTrue(last.exhausted()); assertFalse(last.canContinue()); assertEquals(1, last.observedMessages());
            assertThrows(ExecutionException.class, () -> await(f.service.next(first.id(), first.comments().nextCursor(), 1)));
            verify(f.reader, times(1)).comments(post(true), first.comments().nextCursor());
        }
    }
    @Test void aNonAdvancingCursorPreservesTheCurrentPageAndPausesInsteadOfPretendingEof() throws Exception {
        try (var f = new Fixture()) {
            var first = await(f.service.preview(LINK));
            when(f.reader.comments(post(true), first.comments().nextCursor())).thenReturn(page(first.comments().nextCursor(), false));
            var paused = await(f.service.next(first.id(), first.comments().nextCursor(), 1));
            assertFalse(paused.exhausted()); assertFalse(paused.canContinue()); assertFalse(paused.warnings().isEmpty());
        }
    }
    @Test void anUnavailableDiscussionStillReturnsTheMainPostWithoutReadingComments() throws Exception {
        try (var f = new Fixture()) {
            when(f.reader.probeDownloadSource(LINK)).thenReturn(post(false));
            var result = await(f.service.preview(LINK)); assertNull(result.comments()); assertEquals(0, result.pageNumber());
            assertFalse(result.canContinue()); assertFalse(result.exhausted()); verify(f.reader, never()).comments(any(), any());
        }
    }
    @Test void previewExpiryAndBoundedEvictionRequireARepreviewRatherThanTrustingClientPostData() throws Exception {
        try (var f = new Fixture()) {
            var expired = await(f.service.preview(LINK)); f.clock.addAndGet(TimeUnit.MINUTES.toNanos(10));
            assertThrows(ExecutionException.class, () -> await(f.service.next(expired.id(), expired.comments().nextCursor(), 1)));
            var oldest = await(f.service.preview(LINK));
            for (int i = 0; i < 8; i++) await(f.service.preview(LINK));
            assertThrows(ExecutionException.class, () -> await(f.service.next(oldest.id(), oldest.comments().nextCursor(), 1)));
        }
    }
    @Test void sameAccountNewSessionAndDifferentAccountCannotReuseAnOldSourcePreview() throws Exception {
        for (boolean differentAccount : List.of(false, true)) try (var f = new Fixture()) {
            var first = await(f.service.preview(LINK));
            if (differentAccount) f.account.set("different"); else f.generation.incrementAndGet();
            assertThrows(ExecutionException.class, () -> await(f.service.next(first.id(), first.comments().nextCursor(), 1)));
            verify(f.reader, times(1)).comments(any(), any());
        }
    }
    @Test void queuedPreviewCannotRunInAReplacementSession() throws Exception {
        try (var f = new Fixture()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var blocking = f.work.readWork(() -> { entered.countDown(); try { assertTrue(release.await(3, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); } return true; });
            assertTrue(entered.await(2, TimeUnit.SECONDS)); var queued = f.service.preview(LINK);
            f.generation.incrementAndGet(); release.countDown(); await(blocking);
            assertThrows(ExecutionException.class, () -> await(queued)); verifyNoInteractions(f.reader);
        }
    }
    @Test void anInflightSessionChangeAndAForgedSourceAccountAreNotReturned() throws Exception {
        try (var f = new Fixture()) {
            when(f.reader.probeDownloadSource(LINK)).thenAnswer(call -> { f.generation.incrementAndGet(); return post(true); });
            assertThrows(ExecutionException.class, () -> await(f.service.preview(LINK))); verify(f.reader, never()).comments(any(), any());
            f.account.set("another"); when(f.reader.probeDownloadSource(LINK)).thenReturn(post(true));
            assertThrows(ExecutionException.class, () -> await(f.service.preview(LINK)));
        }
    }
    @Test void malformedCursorAndAStaleRevisionDoNotTriggerAnotherNativeRead() throws Exception {
        try (var f = new Fixture()) {
            for (String cursor : new String[]{null, "", "0", "-1", "+1", "01", "9223372036854775808"}) {
                assertThrows(IllegalArgumentException.class, () -> f.service.next("00000000-0000-0000-0000-000000000000", cursor, 1));
            }
            verifyNoInteractions(f.reader, f.telegram);
            var first = await(f.service.preview(LINK));
            assertThrows(ExecutionException.class, () -> await(f.service.next(first.id(), first.comments().nextCursor(), 2)));
            verify(f.reader, times(1)).comments(any(), any());
        }
    }
    @Test void wrongPostOrDiscussionPageIdentityCannotReplaceTheCurrentSource() throws Exception {
        try (var f = new Fixture()) {
            var first = await(f.service.preview(LINK));
            when(f.reader.comments(post(true), first.comments().nextCursor())).thenReturn(new Page("wrong-key", List.of(), List.of(), 0, "899", false, "PARTIAL", List.of()));
            assertThrows(ExecutionException.class, () -> await(f.service.next(first.id(), first.comments().nextCursor(), 1)));
            when(f.reader.comments(post(true), first.comments().nextCursor())).thenReturn(page("899", true));
            assertTrue(await(f.service.next(first.id(), first.comments().nextCursor(), 1)).exhausted());
        }
    }
    @Test void nativeFailureDoesNotRetryOrCreateALibraryRecordAndDiagnosticsRedactPrivateInputs() throws Exception {
        try (var f = new Fixture()) {
            when(f.reader.probeDownloadSource(LINK)).thenThrow(new IllegalArgumentException("Synthetic unavailable source"));
            assertThrows(ExecutionException.class, () -> await(f.service.preview(LINK)));
            verify(f.reader, times(1)).probeDownloadSource(LINK); verifyNoInteractions(f.repository);
            var dto = new DownloadSourcePreview("id", post(true), null, 0, 0, false, false, List.of("private-warning"));
            assertFalse(dto.toString().contains("private"));
        }
    }
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(4, TimeUnit.SECONDS); }
    private static Post post(boolean readable) { return new Post("account:-1001:100", "account", "-1001", "private-channel", "100", "private-title", "private-body", "0", List.of(new MessageRef("-1001", "100", "")), List.of(), "-2001", "500", List.of(), readable, readable ? "PARTIAL" : "UNAVAILABLE", List.of(), null, "COMPLETE"); }
    private static Page page(String cursor, boolean eof, String... messages) {
        return new Page(post(true).key(), List.of(), java.util.Arrays.stream(messages).map(id -> new MessageRef("-2001", id, "")).toList(), messages.length, cursor, eof, eof ? "COMPLETE" : "PARTIAL", List.of());
    }
    private static final class Fixture implements AutoCloseable {
        final ResourceReader reader = mock(ResourceReader.class); final TelegramService telegram = mock(TelegramService.class);
        final ResourceRepository repository; final ResourceService work; final DownloadSourceService service;
        final AtomicReference<String> account = new AtomicReference<>("account"); final AtomicLong generation = new AtomicLong(1), clock = new AtomicLong(1);
        Fixture() { this(mock(ResourceRepository.class)); }
        Fixture(ResourceRepository repository) {
            this.repository = repository;
            when(telegram.accountId()).thenAnswer(call -> account.get()); when(telegram.sessionGeneration()).thenAnswer(call -> generation.get());
            when(reader.probeDownloadSource(LINK)).thenReturn(post(true)); when(reader.comments(post(true), null)).thenReturn(page("9007199254740999", false, "9007199254740999"));
            work = new ResourceService(reader, repository, telegram); service = new DownloadSourceService(work, reader, telegram, clock::get);
        }
        @Override public void close() { work.close(); }
    }
}
