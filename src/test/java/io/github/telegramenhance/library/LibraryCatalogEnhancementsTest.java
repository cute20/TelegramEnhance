package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.storage.Database;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryCatalogEnhancementsTest {
    @TempDir Path directory;

    @Test void publicationSortPrecedesPaginationAndKeepsUnknownDatesLast() {
        try (Database db = new Database(new AppPaths(directory))) {
            var repository = new ResourceRepository(db);
            var oldest = post("a", "-1001", "10", "zulu", "#trip", 1000L);
            var newest = post("a", "-1001", "20", "Beta", "#trip", 3000L);
            var tied = post("a", "-1001", "30", "alpha", "#trip", 3000L);
            var unknown = post("a", "-1001", "40", "Alpha", "#trip", null);
            for (var post : List.of(oldest, newest, tied, unknown)) repository.savePost(post);
            stamp(db, oldest, 9000); stamp(db, newest, 5000); stamp(db, tied, 5000); stamp(db, unknown, 10000);
            assertEquals(List.of("20", "30"), ids(repository.catalog("a", query(), 2, 0)));
            var second = repository.catalog("a", query(), 2, 2);
            assertEquals(List.of("10", "40"), ids(second));
            assertEquals(4, second.total()); assertFalse(second.hasMore());
            assertNull(second.items().getLast().publishedAt());
            assertEquals(1000L, second.items().getFirst().publishedAt());
            assertEquals(List.of("40", "10"), ids(repository.catalog("a", query(), 2, 0, "updated-desc")));
            assertEquals(List.of("30", "40"), ids(repository.catalog("a", query(), 2, 0, "title-asc")));
            assertEquals(List.of("20", "10"), ids(repository.catalog("a", query(), 2, 2, "title-asc")));
            assertThrows(IllegalArgumentException.class, () -> repository.catalog("a", query(), 20, 0, "title; DROP TABLE resource_posts"));
            assertEquals(4, repository.catalog("a", query(), 20, 0).total());
        }
    }

    @Test void facetsUseAllSelectedChannelPostsWithoutRelaxingAndSearch() {
        try (Database db = new Database(new AppPaths(directory))) {
            var repository = new ResourceRepository(db);
            repository.savePost(post("a", "-1001", "10", "one", "#trip #trip #photo mountains", null));
            repository.savePost(post("a", "-1001", "20", "two", "#trip #food mountains", null));
            repository.savePost(post("a", "-1001", "30", "three", "#new sea", null));
            repository.savePost(post("a", "-1002", "40", "other channel", "#elsewhere", null));
            repository.savePost(post("b", "-1001", "50", "other account", "#private", null));
            var query = PostSearch.parse(List.of("-1001"), "trip photo", "mountains");
            var page = repository.catalog("a", query, 1, 0);
            assertEquals(List.of("10"), ids(page));
            assertEquals(1, page.total());
            assertEquals(List.of(new LibraryModels.TagFacet("trip", 2), new LibraryModels.TagFacet("food", 1),
                    new LibraryModels.TagFacet("new", 1), new LibraryModels.TagFacet("photo", 1)), page.facets().tags());
            var noMatches = repository.catalog("a", PostSearch.parse(List.of("-1001"), "trip photo", "absent"), 1, 30);
            assertEquals(0, noMatches.total());
            assertEquals(page.facets(), noMatches.facets());
            assertTrue(repository.catalog("a", query(), 1, 0).facets().tags().stream().anyMatch(t -> t.tag().equals("elsewhere")));
            assertTrue(repository.catalog("a", query(), 1, 0).facets().tags().stream().noneMatch(t -> t.tag().equals("private")));
        }
    }

    @Test void mediaCountsIncludeMainAndEveryIndexedCommentSlotButNotTextMessages() {
        try (Database db = new Database(new AppPaths(directory))) {
            var repository = new ResourceRepository(db);
            var base = post("a", "-1001", "10", "media", "body", 1000L);
            var mainVideo = media(base, base.mainMessages().getFirst(), "0", "MAIN", "VIDEO");
            var mainAudio = media(base, base.mainMessages().getFirst(), "1", "MAIN", "AUDIO");
            var post = withMedia(base, List.of(mainVideo, mainAudio));
            repository.savePost(post);
            var comment = new MessageRef(post.discussionChatId(), "601", "");
            var textOnly = new MessageRef(post.discussionChatId(), "602", "");
            var media = List.of(media(post, comment, "0", "COMMENT", "VIDEO"),
                    media(post, comment, "1", "COMMENT", "VIDEO"), media(post, comment, "2", "COMMENT", "PHOTO"),
                    media(post, comment, "3", "COMMENT", "DOCUMENT"), media(post, comment, "4", "COMMENT", "OTHER"));
            var page = new Page(post.key(), media, List.of(comment, textOnly), 2, "600", false, "PARTIAL", List.of());
            repository.savePage(page); repository.savePage(page);
            var empty = post("a", "-1001", "20", "empty", "body", 2000L);
            repository.savePost(empty);
            var entries = repository.catalog("a", query(), 20, 0).items();
            assertEquals(Map.of("VIDEO", 0L, "PHOTO", 0L, "AUDIO", 0L, "DOCUMENT", 0L, "OTHER", 0L), entries.getFirst().mediaTypeCounts());
            var card = entries.getLast();
            assertEquals(Map.of("VIDEO", 3L, "PHOTO", 1L, "AUDIO", 1L, "DOCUMENT", 1L, "OTHER", 1L), card.mediaTypeCounts());
            assertEquals(2, card.indexedCommentMessages()); assertEquals(5, card.indexedCommentMedia());
            assertEquals(7, card.mediaTypeCounts().values().stream().mapToLong(Long::longValue).sum());
        }
    }

    @Test void legacyDateIsUnknownUntilRefreshAndDoesNotUseLocalIndexTimestamp() {
        try (Database db = new Database(new AppPaths(directory))) {
            var repository = new ResourceRepository(db);
            var post = post("a", "-1001", "10", "old", "body", null);
            repository.savePost(post);
            db.write(c -> { try (var q = c.prepareStatement("UPDATE resource_posts SET post_json=json_remove(post_json,'$.publishedAt'),updated_at=999999 WHERE post_key=?")) {
                q.setString(1, post.key()); q.executeUpdate();
            } return null; });
            assertNull(repository.detail(post.key(), 20, 0).post().publishedAt());
            assertNull(repository.catalog("a", query(), 20, 0).items().getFirst().publishedAt());
            repository.savePost(post.withPublishedAt(123000L));
            assertEquals(123000L, repository.detail(post.key(), 20, 0).post().publishedAt());
            repository.savePost(post);
            assertEquals(123000L, repository.detail(post.key(), 20, 0).post().publishedAt(), "An unknown refresh must preserve a known source date");
        }
        try (Database db = new Database(new AppPaths(directory))) {
            assertEquals(123000L, new ResourceRepository(db).catalog("a", query(), 20, 0).items().getFirst().publishedAt());
        }
    }

    @Test void publicationBackfillPreservesFrozenContentChecksButRealTargetChangesDoNot() {
        var old = post("a", "-1001", "10", "old", "body", null);
        var refreshed = old.withPublishedAt(123000L);
        assertNotEquals(old, refreshed);
        assertTrue(old.sameContent(refreshed)); assertTrue(refreshed.sameContent(old));
        assertFalse(old.sameContent(post("a", "-1001", "10", "changed", "body", 123000L)));
        assertFalse(old.sameContent(post("b", "-1001", "10", "old", "body", 123000L)));
        assertFalse(old.sameContent(null));
        assertEquals(123000L, refreshed.withCoverage("COMPLETE").publishedAt());
    }

    @Test void partialAlbumRefreshKeepsThePublicationDateOfTheRetainedCanonicalMember() {
        try (Database db = new Database(new AppPaths(directory))) {
            var repository = new ResourceRepository(db);
            var original = album("100", List.of("100", "200"), "COMPLETE", 1000L);
            repository.savePost(original);
            repository.savePost(album("200", List.of("200"), "PARTIAL", 2000L));
            var retained = repository.detail(original.key(), 20, 0).post();
            assertEquals("100", retained.mainMessageId()); assertEquals(1000L, retained.publishedAt());
            repository.savePost(album("50", List.of("50", "100", "200"), "COMPLETE", 500L));
            var expanded = repository.detail(original.key(), 20, 0).post();
            assertEquals("50", expanded.mainMessageId()); assertEquals(500L, expanded.publishedAt());
        }
    }

    private Post post(String account, String channel, String id, String title, String text, Long publishedAt) {
        var main = new MessageRef(channel, id, "");
        return new Post(account + ":" + channel + ":" + id, account, channel, "Channel", id, title, text, "0", List.of(main), List.of(),
                "-2001", "500", List.of(new MessageRef("-2001", "500", "")), true, "PARTIAL", List.of(),
                new AlbumEvidence(true, "SINGLE_MESSAGE", null, null), "COMPLETE", publishedAt);
    }
    private Post withMedia(Post p, List<Media> media) {
        return new Post(p.key(), p.accountId(), p.channelId(), p.channelTitle(), p.mainMessageId(), p.title(), p.text(), p.albumId(), p.mainMessages(),
                media, p.discussionChatId(), p.discussionRootMessageId(), p.discussionRoots(), p.canReadComments(), p.coverage(), p.warnings(),
                p.albumEvidence(), p.mainCoverage(), p.publishedAt());
    }
    private Post album(String canonical, List<String> ids, String coverage, Long publishedAt) {
        var base = post("a", "-1001", canonical, "album", "body", publishedAt);
        return new Post("a:-1001:album:42", base.accountId(), base.channelId(), base.channelTitle(), canonical, base.title(), base.text(), "42",
                ids.stream().map(id -> new MessageRef("-1001", id, "")).toList(), List.of(), base.discussionChatId(), base.discussionRootMessageId(),
                base.discussionRoots(), true, "PARTIAL", List.of(), new AlbumEvidence("COMPLETE".equals(coverage), "TEST", null, null), coverage, publishedAt);
    }
    private Media media(Post post, MessageRef ref, String slot, String source, String type) {
        return new Media(post.accountId() + ":" + ref.chatId() + ":" + ref.messageId() + ":" + slot, ref, source, type, "file", 10, "");
    }
    private PostSearch.Query query() { return PostSearch.parse(List.of(), "", ""); }
    private List<String> ids(LibraryModels.CatalogPage page) { return page.items().stream().map(e -> e.post().mainMessageId()).toList(); }
    private void stamp(Database db, Post post, long value) {
        db.write(c -> { try (var q = c.prepareStatement("UPDATE resource_posts SET updated_at=? WHERE post_key=?")) {
            q.setLong(1, value); q.setString(2, post.key()); q.executeUpdate();
        } return null; });
    }
}
