package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import io.github.telegramenhance.library.ChannelImportModels.*;
import io.github.telegramenhance.library.ChannelImportRunModels.RunView;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.storage.Database;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelImportRunRepositoryTest {
    @TempDir Path directory;
    private static final Runnable GUARD = () -> { };

    @Test void anExhaustedBatchOnlyCompletesAfterEveryPendingPostIsAtomicallySaved() {
        try (Fixture f = fixture()) {
            RunView start = f.start(); assertEquals(0, start.revision()); assertNull(start.warning());
            var batch = f.batch(page(List.of(candidate("100", null), candidate("90", "0")), null, true, false));
            assertEquals("READY", batch.status()); assertFalse(batch.exhausted()); assertEquals(0, batch.processed());
            var first = f.commit(post("100", "0", "COMPLETE", "first"));
            assertEquals(2, first.revision()); assertEquals(1, first.processed()); assertEquals(1, first.created()); assertFalse(first.exhausted());
            var last = f.commit(post("90", "0", "PARTIAL", "second"));
            assertEquals("COMPLETE", last.status()); assertTrue(last.exhausted()); assertEquals(3, last.revision());
            assertEquals(2, last.processed()); assertEquals(2, last.created()); assertEquals(0, last.updated()); assertEquals(1, last.incomplete());
            assertTrue(last.warning().contains("1 条主帖媒体")); assertEquals(2, f.posts.posts("1", 10, 0).size());
            assertTrue(f.state().pending().isEmpty()); assertEquals(2, f.count("channel_import_run_entries"));
            assertEquals(0, f.count("resource_comment_media"));
        }
    }

    @Test void emptyPagesWithOpaqueCursorProgressContinueAndOnlyExplicitEofCompletes() {
        try (Fixture f = fixture()) {
            f.start();
            String first = cursor("1", "-1001", 100, 0), next = cursor("1", "-1001", 90, 0);
            var one = f.batch(page(List.of(), first, false, true));
            assertEquals("READY", one.status()); assertEquals(first, f.state().sourceCursor()); assertEquals(0, one.processed());
            var warned = new HistoryPage(new ManagedChannel("-1001", "title"), List.of(), "CHANNEL_PAGE", 10, next, false, true, List.of("源分页仍需核对"));
            assertEquals("READY", f.batch(warned).status());
            var done = f.batch(page(List.of(), null, true, false));
            assertEquals("COMPLETE", done.status()); assertTrue(done.exhausted()); assertEquals(3, done.revision());
            assertTrue(done.warning().contains("源分页仍需核对")); assertEquals(next, f.state().sourceCursor());
        }
    }

    @Test void opaqueCursorBindingAndRawDirectionCannotBeReplacedByTokenStringInequality() {
        for (String invalid : List.of(cursor("2", "-1001", 90, 0), cursor("1", "-1002", 90, 0),
                cursor("1", "-1001", 100, 42), cursor("1", "-1001", 101, 0), "90", "invalid")) {
            try (Fixture f = new Fixture(new AppPaths(directory.resolve(Integer.toString(invalid.hashCode()))))) {
                f.start(); f.batch(page(List.of(), cursor("1", "-1001", 100, 0), false, true));
                var blocked = f.batch(page(List.of(), invalid, false, true));
                assertEquals("BLOCKED", blocked.status()); assertFalse(blocked.exhausted()); assertNotNull(blocked.warning());
                assertEquals(cursor("1", "-1001", 100, 0), f.state().sourceCursor());
            }
        }
    }

    @Test void safeCandidatesFromANonEofBlockedPageAreImportedBeforeTheRunBlocks() {
        try (Fixture f = fixture()) {
            f.start(); assertEquals("READY", f.batch(page(List.of(candidate("100", null)), null, false, false)).status());
            var blocked = f.commit(post("100", "0", "COMPLETE", "title"));
            assertEquals("BLOCKED", blocked.status()); assertFalse(blocked.exhausted()); assertEquals(1, blocked.processed());
            assertNotNull(blocked.warning()); assertEquals(blocked, f.runs.start("1", "-1001", false, null, null, GUARD));
        }
    }

    @Test void repeatedAlbumKeysDoNotDoubleCountAndALaterCompleteObservationRepairsIncompleteCount() {
        try (Fixture f = fixture()) {
            f.start(); f.batch(page(List.of(candidate("101", "42")), cursor("1", "-1001", 101, 42), false, true));
            var first = f.commit(post("101", "42", "PARTIAL", "partial album"));
            assertEquals(1, first.processed()); assertEquals(1, first.created()); assertEquals(1, first.incomplete());
            f.batch(page(List.of(candidate("100", "42")), null, true, false));
            var repaired = f.commit(post("100", "42", "COMPLETE", "complete album"));
            assertEquals("COMPLETE", repaired.status()); assertEquals(1, repaired.processed()); assertEquals(1, repaired.created());
            assertEquals(0, repaired.updated()); assertEquals(0, repaired.incomplete()); assertNull(repaired.warning());
            assertEquals(1, f.count("channel_import_run_entries")); assertEquals(1, f.posts.posts("1", 10, 0).size());
        }
    }

    @Test void staleCasAndSqlFailureRollBackPostProjectionEntryAndCheckpointTogether() {
        try (Fixture f = fixture()) {
            f.start(); var stale = f.state(); f.batch(page(List.of(candidate("100", null)), null, true, false));
            var before = f.runs.state("1", "-1001");
            assertThrows(IllegalArgumentException.class, () -> f.runs.commit("1", stale, post("100", "0", "COMPLETE", "must rollback"), GUARD));
            assertEquals(before, f.runs.state("1", "-1001")); assertEquals(0, f.count("resource_posts")); assertEquals(0, f.count("resource_post_search"));
            f.database.write(connection -> { try (var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_run_commit BEFORE UPDATE ON channel_import_runs WHEN NEW.processed>0 BEGIN SELECT RAISE(ABORT,'synthetic checkpoint failure'); END");
            } return null; });
            assertThrows(IllegalStateException.class, () -> f.commit(post("100", "0", "COMPLETE", "must rollback")));
            assertEquals(before, f.runs.state("1", "-1001")); assertEquals(0, f.count("resource_posts"));
            assertEquals(0, f.count("resource_post_search")); assertEquals(0, f.count("resource_post_tags")); assertEquals(0, f.count("channel_import_run_entries"));
        }
    }

    @Test void restartingRequiresCasAndOnlyResetsRunRecordsWhileCompleteCommentsStayIntact() {
        try (Fixture f = fixture()) {
            Post post = post("100", "0", "COMPLETE", "existing"); f.posts.savePost(post);
            var message = new MessageRef("-2001", "601", ""); var media = new Media("1:-2001:601:0", message, "COMMENT", "VIDEO", "kept.mp4", 1, "kept");
            f.posts.savePage(new Page(post.key(), List.of(media), List.of(message), 1, "601", true, "COMPLETE", List.of()));
            var detail = f.posts.detail(post.key(), 10, 0);
            f.start(); f.batch(page(List.of(candidate("100", null)), null, true, false)); var done = f.commit(post);
            assertEquals(1, done.updated()); assertEquals(0, done.created());
            assertThrows(IllegalArgumentException.class, () -> f.runs.start("1", "-1001", true, done.runId(), done.revision() - 1, GUARD));
            assertEquals(done, f.runs.state("1", "-1001")); assertEquals(1, f.count("channel_import_run_entries"));
            var restarted = f.runs.start("1", "-1001", true, done.runId(), done.revision(), GUARD);
            assertNotEquals(done.runId(), restarted.runId()); assertEquals(0, restarted.revision()); assertEquals(0, restarted.processed());
            assertEquals("READY", restarted.status()); assertEquals(0, f.count("channel_import_run_entries"));
            assertEquals(detail, f.posts.detail(post.key(), 10, 0)); assertNull(f.runs.state("2", "-1001"));
        }
    }

    @Test void durablePendingBatchResumesAfterReopeningWithoutReadingTheSourceAgain() {
        String run;
        try (Fixture f = fixture()) {
            run = f.start().runId(); f.batch(page(List.of(candidate("100", null), candidate("90", null)), null, true, false));
            f.commit(post("100", "0", "COMPLETE", "first")); assertEquals(1, f.state().offset());
        }
        try (Fixture reopened = fixture()) {
            assertEquals(run, reopened.state().view().runId()); assertEquals("90", reopened.state().pending().get(reopened.state().offset()).messageId());
            var done = reopened.commit(post("90", "0", "COMPLETE", "second")); assertEquals(2, done.processed()); assertTrue(done.exhausted());
        }
    }

    @Test void oversizedOrInvalidBatchesAndSessionGuardFailuresLeaveAllStateUnchanged() {
        try (Fixture f = fixture()) {
            RunView start = f.start();
            var eleven = IntStream.range(1, 12).mapToObj(i -> candidate(Integer.toString(i), null)).toList();
            assertThrows(IllegalArgumentException.class, () -> f.batch(page(eleven, null, true, false)));
            assertThrows(IllegalArgumentException.class, () -> f.batch(page(List.of(candidate("0", null)), null, true, false)));
            assertThrows(IllegalArgumentException.class, () -> f.runs.batch("1", f.state(), page(List.of(), null, true, false), () -> { throw new IllegalArgumentException("session changed"); }));
            assertEquals(start, f.runs.state("1", "-1001")); assertEquals(0, f.count("resource_posts"));
            assertFalse(start.toString().contains("title"));
        }
    }

    private Fixture fixture() { return new Fixture(new AppPaths(directory)); }
    static String cursor(String account, String channel, long raw, long album) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("v1|" + account + "|" + channel + "|" + raw + "|" + album).getBytes(StandardCharsets.UTF_8));
    }
    static Candidate candidate(String id, String album) { return new Candidate(id, album, "private-title"); }
    static HistoryPage page(List<Candidate> candidates, String next, boolean exhausted, boolean hasMore) {
        return new HistoryPage(new ManagedChannel("-1001", "private-channel-title"), candidates, "CHANNEL_PAGE", 10, next, exhausted, hasMore, List.of());
    }
    static Post post(String id, String album, String coverage, String title) {
        return new Post("1:-1001:" + ("0".equals(album) ? id : "album:" + album), "1", "-1001", "Synthetic", id, title, "#kept body", album,
            List.of(new MessageRef("-1001", id, "")), List.of(), "-2001", "500", List.of(new MessageRef("-2001", "500", "")),
            true, "PARTIAL", List.of(), new AlbumEvidence("COMPLETE".equals(coverage), "PARTIAL_SCAN", null, null), coverage);
    }
    private static final class Fixture implements AutoCloseable {
        final Database database; final ResourceRepository posts; final ChannelImportRunRepository runs;
        Fixture(AppPaths paths) { database = new Database(paths); posts = new ResourceRepository(database); runs = new ChannelImportRunRepository(database, posts); }
        RunView start() { return runs.start("1", "-1001", false, null, null, GUARD); }
        ChannelImportRunRepository.State state() { return runs.current("1", "-1001"); }
        RunView batch(HistoryPage page) { return runs.batch("1", state(), page, GUARD); }
        RunView commit(Post post) { return runs.commit("1", state(), post, GUARD); }
        long count(String table) { return database.read(connection -> { try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(rows.next()); return rows.getLong(1); } }); }
        @Override public void close() { database.close(); }
    }
}
