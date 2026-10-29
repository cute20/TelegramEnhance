package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.telegram.TelegramService;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResourceServiceTest {
    private static final String LEGACY_EOF_NOTICE = "本次可见历史已读至结尾；新增评论需从首轮重新读取，特殊媒体仍以主帖警告为准。";

    @Test void aCompleteOrdinaryPostCanBeReadFromTheStartAndBecomeCompleteAgain() throws Exception {
        var repository = mock(ResourceRepository.class); var reader = mock(ResourceReader.class); var telegram = mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("account");
        var post = post();
        var state = trackSavedPages(repository, new Detail(post, List.of(), null, false, "PARTIAL", List.of()));
        var message = new MessageRef("discussion", "50", "");
        when(reader.comments(any(Post.class), isNull())).thenReturn(new Page("post", List.of(), List.of(message), 1, "50", false, "PARTIAL", List.of()));
        when(reader.comments(any(Post.class), eq("50"))).thenReturn(new Page("post", List.of(), List.of(), 0, "50", true, "COMPLETE", List.of()));
        var service = new ResourceService(reader, repository, telegram);
        try {
            assertFalse(service.comments("post", null).get(3, TimeUnit.SECONDS).exhausted());
            assertEquals("COMPLETE", service.comments("post", "50").get(3, TimeUnit.SECONDS).coverage());
            assertFalse(service.comments("post", null).get(3, TimeUnit.SECONDS).exhausted());
            var result = service.comments("post", "50").get(3, TimeUnit.SECONDS);
            assertTrue(result.exhausted());
            assertEquals("COMPLETE", result.coverage());
            assertTrue(result.warnings().isEmpty());
            assertEquals("COMPLETE", state.get().coverage());
        } finally { service.close(); }
    }

    @Test void theExactPersistedLegacySuccessNoticeDoesNotPoisonARepeatedScan() throws Exception {
        var repository = mock(ResourceRepository.class); var reader = mock(ResourceReader.class); var telegram = mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("account");
        var post = post();
        trackSavedPages(repository, new Detail(post, List.of(), "50", true, "COMPLETE", List.of(LEGACY_EOF_NOTICE)));
        when(reader.comments(post, null)).thenReturn(new Page("post", List.of(), List.of(), 0, "50", false, "PARTIAL", List.of()));
        when(reader.comments(post, "50")).thenReturn(new Page("post", List.of(), List.of(), 0, "50", true, "COMPLETE", List.of()));
        var service = new ResourceService(reader, repository, telegram);
        try {
            assertTrue(service.comments("post", null).get(3, TimeUnit.SECONDS).warnings().isEmpty());
            var result = service.comments("post", "50").get(3, TimeUnit.SECONDS);
            assertEquals("COMPLETE", result.coverage());
            assertTrue(result.exhausted());
            assertTrue(result.warnings().isEmpty());
        } finally { service.close(); }
    }

    @Test void rereadingStillRetainsRealAndUnknownIncompleteWarnings() throws Exception {
        var repository = mock(ResourceRepository.class); var reader = mock(ResourceReader.class); var telegram = mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("account");
        var post = post();
        String missingMedia = "先前页含未展开媒体";
        // Similar wording must not be mistaken for the one known legacy success notice.
        String unknownWarning = LEGACY_EOF_NOTICE + "另有未展开媒体。";
        trackSavedPages(repository, new Detail(post, List.of(), "50", true, "PARTIAL", List.of(LEGACY_EOF_NOTICE, missingMedia, unknownWarning)));
        when(reader.comments(post, null)).thenReturn(new Page("post", List.of(), List.of(), 0, "50", false, "PARTIAL", List.of()));
        when(reader.comments(post, "50")).thenReturn(new Page("post", List.of(), List.of(), 0, "50", true, "COMPLETE", List.of()));
        var service = new ResourceService(reader, repository, telegram);
        try {
            var restart = service.comments("post", null).get(3, TimeUnit.SECONDS);
            assertEquals(List.of(missingMedia, unknownWarning), restart.warnings());
            var result = service.comments("post", "50").get(3, TimeUnit.SECONDS);
            assertTrue(result.exhausted());
            assertEquals("PARTIAL", result.coverage());
            assertEquals(List.of(missingMedia, unknownWarning), result.warnings());
        } finally { service.close(); }
    }

    @Test void anUnprovenMainAlbumStillPreventsCompleteCoverageAtCommentEof() throws Exception {
        var repository = mock(ResourceRepository.class); var reader = mock(ResourceReader.class); var telegram = mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("account");
        String albumWarning = "主帖相册通过有限邻域读取识别，请与 Telegram 核对成员；本片不宣称已证明相册完整。";
        var post = post(List.of(albumWarning));
        trackSavedPages(repository, new Detail(post, List.of(), "50", false, "PARTIAL", List.of(LEGACY_EOF_NOTICE)));
        when(reader.comments(post, "50")).thenReturn(new Page("post", List.of(), List.of(), 0, "50", true, "COMPLETE", List.of()));
        var service = new ResourceService(reader, repository, telegram);
        try {
            var result = service.comments("post", "50").get(3, TimeUnit.SECONDS);
            assertTrue(result.exhausted());
            assertEquals("PARTIAL", result.coverage());
            assertEquals(List.of(albumWarning), result.warnings());
        } finally { service.close(); }
    }

    @Test void exhaustionPreservesEarlierIncompleteMediaWarning() throws Exception {
        var repository = mock(ResourceRepository.class); var reader = mock(ResourceReader.class); var telegram = mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("account");
        var post = post();
        trackSavedPages(repository, new Detail(post, List.of(), "50", false, "PARTIAL", List.of("先前页含未展开媒体")));
        when(reader.comments(post, "50")).thenReturn(new Page("post", List.of(), List.of(), 0, "50", true, "COMPLETE", List.of("历史边界已到达")));
        var service = new ResourceService(reader, repository, telegram);
        try {
            var result = service.comments("post", "50").get(3, TimeUnit.SECONDS);
            assertTrue(result.exhausted()); assertEquals("PARTIAL", result.coverage());
            assertTrue(result.warnings().contains("先前页含未展开媒体"));
            verify(repository).savePage(any(Page.class));
        } finally { service.close(); }
    }

    @Test void mainWarningsDoNotBecomeCommentWarningsOrSurviveARefreshedMainPost() throws Exception {
        var repository = mock(ResourceRepository.class); var reader = mock(ResourceReader.class); var telegram = mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("account");
        String mainWarning = "主帖相册边界尚未核全";
        var partialMain = post(List.of(mainWarning));
        var state = trackSavedPages(repository, new Detail(partialMain, List.of(), "50", false, "PARTIAL", List.of(mainWarning),
                "PARTIAL", "PARTIAL", 0, 0, List.of()));
        when(reader.comments(partialMain, "50")).thenReturn(new Page("post", List.of(), List.of(), 0, "50", true,
                "COMPLETE", List.of(), "COMPLETE", List.of()));
        var service = new ResourceService(reader, repository, telegram);
        try {
            var finishedComments = service.comments("post", "50").get(3, TimeUnit.SECONDS);
            assertEquals("PARTIAL", finishedComments.coverage());
            assertEquals("COMPLETE", finishedComments.commentCoverage());
            assertEquals(List.of(mainWarning), finishedComments.warnings());
            assertTrue(finishedComments.commentWarnings().isEmpty());
            assertTrue(state.get().commentWarnings().isEmpty());

            // A fresh main snapshot replaces main warnings, while the persisted comment checkpoint is retained.
            var completeMain = post().withCoverage("COMPLETE");
            var beforeRefresh = state.get();
            state.set(new Detail(completeMain, beforeRefresh.commentMedia(), beforeRefresh.nextCursor(), beforeRefresh.exhausted(),
                    "COMPLETE", beforeRefresh.commentWarnings(), "COMPLETE", beforeRefresh.commentCoverage(),
                    beforeRefresh.indexedCommentMessages(), beforeRefresh.indexedCommentMedia(), beforeRefresh.commentWarnings()));
            when(reader.comments(completeMain, null)).thenReturn(new Page("post", List.of(), List.of(), 0, "50", true,
                    "COMPLETE", List.of(), "COMPLETE", List.of()));

            var reread = service.comments("post", null).get(3, TimeUnit.SECONDS);
            assertEquals("COMPLETE", reread.coverage());
            assertEquals("COMPLETE", reread.commentCoverage());
            assertTrue(reread.warnings().isEmpty());
            assertTrue(reread.commentWarnings().isEmpty());
            assertEquals("COMPLETE", state.get().coverage());
        } finally { service.close(); }
    }

    @Test void realCommentWarningsPersistAcrossPagesWithoutIncludingMainWarnings() throws Exception {
        var repository = mock(ResourceRepository.class); var reader = mock(ResourceReader.class); var telegram = mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("account");
        String mainWarning = "主帖相册边界尚未核全";
        String commentWarning = "评论页包含尚未展开的媒体";
        var post = post(List.of(mainWarning));
        var state = trackSavedPages(repository, new Detail(post, List.of(), null, false, "PARTIAL", List.of(mainWarning),
                "PARTIAL", "PARTIAL", 0, 0, List.of()));
        when(reader.comments(post, null)).thenReturn(new Page("post", List.of(), List.of(), 0, "50", false,
                "PARTIAL", List.of(commentWarning), "PARTIAL", List.of(commentWarning)));
        when(reader.comments(post, "50")).thenReturn(new Page("post", List.of(), List.of(), 0, "50", true,
                "COMPLETE", List.of(), "COMPLETE", List.of()));
        var service = new ResourceService(reader, repository, telegram);
        try {
            var first = service.comments("post", null).get(3, TimeUnit.SECONDS);
            assertEquals(List.of(commentWarning), first.commentWarnings());
            assertEquals(List.of(mainWarning, commentWarning), first.warnings());
            var last = service.comments("post", "50").get(3, TimeUnit.SECONDS);
            assertTrue(last.exhausted());
            assertEquals("PARTIAL", last.coverage());
            assertEquals("PARTIAL", last.commentCoverage());
            assertEquals(List.of(commentWarning), last.commentWarnings());
            assertEquals(List.of(mainWarning, commentWarning), last.warnings());
            assertEquals(List.of(commentWarning), state.get().commentWarnings());
        } finally { service.close(); }
    }

    @Test void staleCursorCannotOverwriteNewerCheckpoint() throws Exception {
        var repository = mock(ResourceRepository.class); var reader = mock(ResourceReader.class); var telegram = mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("account");
        when(repository.detail("post", 1, 0)).thenReturn(new Detail(post(), List.of(), "50", false, "PARTIAL", List.of()));
        var service = new ResourceService(reader, repository, telegram);
        try {
            assertThrows(ExecutionException.class, () -> service.comments("post", "100").get(3, TimeUnit.SECONDS));
            verifyNoInteractions(reader); verify(repository, never()).savePage(any());
        } finally { service.close(); }
    }
    @Test void aChangedAccountCannotPersistAnotherAccountsProbe() throws Exception {
        var repository = mock(ResourceRepository.class); var reader = mock(ResourceReader.class); var telegram = mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("different-account"); when(reader.probe("link")).thenReturn(post());
        var service = new ResourceService(reader, repository, telegram);
        try {
            assertThrows(ExecutionException.class, () -> service.probe("link").get(3, TimeUnit.SECONDS));
            verifyNoInteractions(repository);
        } finally { service.close(); }
    }
    private AtomicReference<Detail> trackSavedPages(ResourceRepository repository, Detail initial) {
        var state = new AtomicReference<>(initial);
        when(repository.detail("post", 1, 0)).thenAnswer(invocation -> state.get());
        doAnswer(invocation -> {
            Page page = invocation.getArgument(0);
            Detail before = state.get();
            var commentWarnings = ReadCoverage.commentWarnings(page.scanWarnings());
            var warnings = new java.util.LinkedHashSet<>(before.post().warnings()); warnings.addAll(commentWarnings);
            String comments = ReadCoverage.comments(before.post().canReadComments(), page.exhausted(), commentWarnings);
            String overall = ReadCoverage.overall(before.mainCoverage(), comments);
            state.set(new Detail(before.post().withCoverage(overall), before.commentMedia(), page.nextCursor(), page.exhausted(),
                    overall, List.copyOf(warnings), before.post().mainCoverage(), comments,
                    before.indexedCommentMessages(), before.indexedCommentMedia(), commentWarnings, commentWarnings));
            return null;
        }).when(repository).savePage(any(Page.class));
        return state;
    }
    private Post post() { return post(List.of()); }
    private Post post(List<String> warnings) { return new Post("post", "account", "channel", "title", "1", "title", "text", "0", List.of(), List.of(), "discussion", "root", List.of(), true, "PARTIAL", warnings,
            new ResourceModels.AlbumEvidence(warnings.isEmpty(), "SINGLE_MESSAGE", null, null), warnings.isEmpty() ? "COMPLETE" : "PARTIAL"); }
}
