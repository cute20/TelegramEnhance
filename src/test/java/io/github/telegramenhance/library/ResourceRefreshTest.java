package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.telegram.TelegramService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verify the service response against committed SQLite, including warning provenance. */
class ResourceRefreshTest {
    @TempDir Path directory;

    @Test void successfulObservationResolvesOnlyThatMessagesWarning() throws Exception {
        try (var f = new Fixture()) {
            var ref = new MessageRef("discussion", "50", "");
            f.answer(null, observed(ref, false, List.of("媒体文件信息缺失")));
            assertEquals(List.of("媒体文件信息缺失"), f.read(null).commentWarnings());
            assertTrue(f.detail().commentScanWarnings().isEmpty());
            f.answer(null, observed(ref, true, List.of()));
            assertTrue(f.read(null).commentWarnings().isEmpty());
            f.answer("50", end());
            var finished = f.read("50");
            assertEquals("COMPLETE", finished.commentCoverage()); assertTrue(finished.warnings().isEmpty());
            assertEquals(1, f.detail().indexedCommentMessages());
        }
    }

    @Test void successfulObservationDoesNotClearUnattributedScanProblems() throws Exception {
        try (var f = new Fixture()) {
            f.repository.savePage(new Page("post", List.of(), List.of(), 0, "50", false, "PARTIAL", List.of("分页身份不一致")));
            f.answer(null, observed(new MessageRef("discussion", "50", ""), true, List.of()));
            f.read(null); f.answer("50", end());
            var finished = f.read("50");
            assertTrue(finished.exhausted()); assertEquals("PARTIAL", finished.commentCoverage());
            assertEquals(List.of("分页身份不一致"), finished.commentWarnings());
        }
    }

    @Test void refreshUpdatesMainTextAndRetainsCommentsCheckpoint() throws Exception {
        try (var f = new Fixture()) {
            f.repository.savePage(end());
            when(f.reader.refresh(any(Post.class))).thenReturn(post("post", "修改后的标题"));
            var result = f.service.refresh("post").get(3, TimeUnit.SECONDS);
            assertEquals("修改后的标题", result.post().title());
            assertEquals("50", result.nextCursor()); assertTrue(result.exhausted());
            assertEquals("COMPLETE", result.coverage());
        }
    }

    @Test void refreshCannotSilentlyImportAnotherPost() throws Exception {
        try (var f = new Fixture()) {
            var before = f.detail();
            when(f.reader.refresh(any(Post.class))).thenReturn(post("other-post", "other"));
            assertThrows(ExecutionException.class, () -> f.service.refresh("post").get(3, TimeUnit.SECONDS));
            assertEquals(before, f.detail()); assertEquals(1, f.repository.posts(20, 0).size());
        }
    }

    @Test void failedRefreshDoesNotAlterTheSnapshotOrCursor() throws Exception {
        try (var f = new Fixture()) {
            f.repository.savePage(end()); var before = f.detail();
            when(f.reader.refresh(any(Post.class))).thenThrow(new IllegalArgumentException("主帖暂不可读取"));
            assertThrows(ExecutionException.class, () -> f.service.refresh("post").get(3, TimeUnit.SECONDS));
            assertEquals(before, f.detail());
        }
    }

    @Test void commentPageForAnotherPostCannotOverwriteThisCheckpoint() throws Exception {
        try (var f = new Fixture()) {
            var before = f.detail();
            f.answer(null, new Page("other-post", List.of(), List.of(), 0, "50", true, "COMPLETE", List.of()));
            assertThrows(ExecutionException.class, () -> f.read(null));
            assertEquals(before, f.detail());
        }
    }

    @Test void accountChangeDuringReadCannotOverwriteStoredPost() throws Exception {
        try (var f = new Fixture()) {
            when(f.reader.refresh(any(Post.class))).thenAnswer(invocation -> {
                when(f.telegram.accountId()).thenReturn("different-account"); return post("post", "unexpected");
            });
            assertThrows(ExecutionException.class, () -> f.service.refresh("post").get(3, TimeUnit.SECONDS));
            assertEquals("title", f.detail().post().title());
        }
    }

    private static Page observed(MessageRef ref, boolean complete, List<String> warnings) {
        return new Page("post", List.of(), List.of(ref), 1, "50", false, "PARTIAL", warnings, "PARTIAL", warnings,
                List.of(new CommentObservation(ref, complete, warnings)), List.of());
    }
    private static Page end() { return new Page("post", List.of(), List.of(), 0, "50", true, "COMPLETE", List.of()); }
    private static Post post(String key, String title) {
        return new Post(key, "account", "channel", "channel title", "1", title, "text", "0", List.of(), List.of(),
                "discussion", "root", List.of(), true, "PARTIAL", List.of(), new AlbumEvidence(true, "SINGLE_MESSAGE", null, null), "COMPLETE");
    }
    private class Fixture implements AutoCloseable {
        final Database database = new Database(new AppPaths(directory));
        final ResourceRepository repository = new ResourceRepository(database);
        final ResourceReader reader = mock(ResourceReader.class);
        final TelegramService telegram = mock(TelegramService.class);
        final ResourceService service = new ResourceService(reader, repository, telegram);
        Fixture() { when(telegram.accountId()).thenReturn("account"); repository.savePost(post("post", "title")); }
        Detail detail() { return repository.detail("post", 50, 0); }
        void answer(String cursor, Page page) { when(reader.comments(any(Post.class), eq(cursor))).thenReturn(page); }
        Page read(String cursor) throws Exception { return service.comments("post", cursor).get(3, TimeUnit.SECONDS); }
        @Override public void close() { service.close(); database.close(); }
    }
}
