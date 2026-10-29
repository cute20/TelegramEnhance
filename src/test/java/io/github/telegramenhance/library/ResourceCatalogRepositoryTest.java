package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.AlbumEvidence;
import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.library.ResourceModels.MessageRef;
import io.github.telegramenhance.library.ResourceModels.Page;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.storage.Database;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceCatalogRepositoryTest {
    @TempDir Path directory;

    @Test
    void channelsAndCatalogIsolateAccountsAndUseTheLatestChannelSnapshot() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post first = post("1", "-1001", "100", "first", "first");
            Post latest = withText(post("1", "-1001", "200", "latest", "latest"), "latest", "latest", "New channel title");
            Post otherChannel = post("1", "-1002", "100", "other channel", "other channel");
            Post anotherAccount = withText(post("2", "-1001", "100", "private to second account", "private"),
                "private to second account", "private", "Other account channel title");
            for (Post post : List.of(first, latest, otherChannel, anotherAccount)) repository.savePost(post);
            stamp(database, first.key(), 10); stamp(database, latest.key(), 20); stamp(database, anotherAccount.key(), 1000);
            var channels = repository.channels("1");
            assertEquals(2, channels.size());
            var channel = channels.stream().filter(c -> c.id().equals("-1001")).findFirst().orElseThrow();
            assertEquals("New channel title", channel.title());
            assertEquals(2L, channel.indexedPosts());
            var catalog = repository.catalog("1", query(List.of(), "", ""), 20, 0);
            assertEquals(3L, catalog.total());
            assertEquals(3L, catalog.indexedPosts());
            assertEquals(2L, catalog.indexedChannels());
            assertEquals("INDEXED_ONLY", catalog.scope());
            assertTrue(catalog.items().stream().allMatch(item -> item.post().accountId().equals("1")));
            var second = repository.catalog("2", query(List.of(), "", ""), 20, 0);
            assertEquals(1L, second.total());
            assertEquals(anotherAccount.key(), second.items().getFirst().post().key());
            assertThrows(IllegalArgumentException.class, () -> repository.media("1", anotherAccount.key(), List.of(), true, 20, 0));
        }
    }

    @Test
    void normalizedTagsAndKeywordsUseAndMatchingOnlyAgainstMainText() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post match = post("1", "-1001", "100", "#ＢＥＩＪＩＮＧ #旅行 ＣＡＦÉ 北京 日记", "unrelated title");
            Post longerTag = post("1", "-1001", "200", "#beijing #旅行计划 café 北京", "long tag");
            Post missingKeyword = post("1", "-1002", "100", "#beijing #旅行 北京", "missing café");
            Post titleOnly = post("1", "-1001", "300", "普通主帖", "#beijing #旅行 café 北京");
            for (Post post : List.of(match, longerTag, missingKeyword, titleOnly)) repository.savePost(post);
            Media comment = comment(titleOnly, "601", "VIDEO", "#beijing #旅行 café 北京.mp4", "#beijing #旅行 café 北京");
            repository.savePage(page(titleOnly, List.of(comment), false));
            var result = repository.catalog("1", query(List.of("-1001", "-1002"), "#ＢＥＩＪＩＮＧ #旅行", "CAFÉ 北京"), 20, 0);
            assertEquals(1L, result.total());
            assertEquals(match.key(), result.items().getFirst().post().key());
            assertEquals(List.of("beijing", "旅行"), result.items().getFirst().tags());
            assertEquals(0L, repository.catalog("1", query(List.of("-1002"), "beijing 旅行", "café 北京"), 20, 0).total());
            assertEquals(0L, repository.catalog("1", query(List.of(), "", "300"), 20, 0).total(), "Fallback titles and IDs are not text matches");
            assertEquals(result.items().getFirst().post().coverage(), repository.detail(match.key(), 20, 0).coverage());
        }
    }

    @Test
    void filtersRunBeforePaginationAndTotalsDoNotDescribeOnlyTheCurrentPage() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            for (int index = 1; index <= 7; index++) {
                Post post = post("1", "-1001", Integer.toString(index), index % 2 == 0 ? "#trip 100% exact" : "#other 1000 noisy", "title");
                repository.savePost(post); stamp(database, post.key(), index * 10L);
            }
            var search = query(List.of(), "trip", "100%");
            var first = repository.catalog("1", search, 2, 0);
            var next = repository.catalog("1", search, 2, 2);
            assertEquals(3L, first.total());
            assertEquals(7L, first.indexedPosts());
            assertEquals(List.of("6", "4"), first.items().stream().map(i -> i.post().mainMessageId()).toList());
            assertTrue(first.hasMore());
            assertEquals(List.of("2"), next.items().stream().map(i -> i.post().mainMessageId()).toList());
            assertEquals(3L, next.total());
            assertFalse(next.hasMore());
            assertTrue(repository.catalog("1", search, 2, 20).items().isEmpty());
        }
    }

    @Test
    void mediaTypesFilterCommentsBeforePagingAndIncludingMainIsIndependent() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post base = post("1", "-1001", "100", "main", "main");
            MessageRef mainRef = base.mainMessages().getFirst();
            Media mainVideo = new Media("1:-1001:100:0", mainRef, "MAIN", "VIDEO", "main.mp4", 100, "main video");
            Media mainPhoto = new Media("1:-1001:100:1", mainRef, "MAIN", "PHOTO", "main.jpg", 101, "main photo");
            Post post = new Post(base.key(), base.accountId(), base.channelId(), base.channelTitle(), base.mainMessageId(), base.title(),
                base.text(), "0", base.mainMessages(), List.of(mainVideo, mainPhoto), base.discussionChatId(), base.discussionRootMessageId(),
                base.discussionRoots(), true, "PARTIAL", List.of(), base.albumEvidence(), "COMPLETE");
            repository.savePost(post);
            Media other = comment(post, "601", "OTHER", "other.bin", "other");
            Media videoOne = comment(post, "602", "VIDEO", "one.mp4", "first video");
            Media photo = comment(post, "603", "PHOTO", "photo.jpg", "photo");
            Media videoTwo = comment(post, "604", "VIDEO", "two.mp4", "second video");
            Media audio = comment(post, "605", "AUDIO", "audio.mp3", "audio");
            repository.savePage(page(post, List.of(other, videoOne, photo, videoTwo, audio), false));
            var first = repository.media("1", post.key(), List.of("VIDEO"), false, 1, 0);
            var second = repository.media("1", post.key(), List.of("VIDEO"), false, 1, 1);
            assertEquals(List.of(videoOne), first.items());
            assertEquals(List.of(videoTwo), second.items());
            assertEquals(2L, first.total());
            assertEquals(0L, first.mainMediaCount());
            assertEquals(2L, first.commentMediaCount());
            assertTrue(first.hasMore()); assertFalse(second.hasMore());
            var mainAndPhoto = repository.media("1", post.key(), List.of("PHOTO"), true, 2, 1);
            assertEquals(List.of(mainPhoto, photo), mainAndPhoto.items());
            assertEquals(3L, mainAndPhoto.total());
            assertEquals(2L, mainAndPhoto.mainMediaCount());
            assertEquals(1L, mainAndPhoto.commentMediaCount());
            assertFalse(mainAndPhoto.hasMore());
            assertEquals(List.of(mainVideo, mainPhoto), repository.media("1", post.key(), List.of("DOCUMENT"), true, 20, 0).items());
            assertEquals(5L, repository.media("1", post.key(), List.of(), false, 20, 0).total());
            assertEquals("INDEXED_ONLY", mainAndPhoto.scope());
            assertThrows(IllegalArgumentException.class, () -> repository.media("1", post.key(), List.of("UNKNOWN"), false, 20, 0));
        }
    }

    @Test
    void lazyBackfillIsAccountScopedAndRefreshUpdatesTextAndTagsAtomically() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post original = post("1", "-1001", "100", "#old Original", "title");
            Post anotherAccount = post("2", "-1001", "100", "#private hidden", "title");
            repository.savePost(original); repository.savePost(anotherAccount);
            database.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM resource_post_tags"); statement.executeUpdate("DELETE FROM resource_post_search");
                }
                return null;
            });
            assertEquals(1L, repository.catalog("1", query(List.of(), "old", "original"), 20, 0).total());
            assertEquals(0, count(database, "SELECT COUNT(*) FROM resource_post_search s JOIN resource_posts p USING(post_key) WHERE p.account_id='2'"));
            Post refreshed = withText(original, "#new Refreshed", "title", original.channelTitle());
            repository.savePost(refreshed);
            assertEquals(0L, repository.catalog("1", query(List.of(), "old", ""), 20, 0).total());
            assertEquals(1L, repository.catalog("1", query(List.of(), "new", "refreshed"), 20, 0).total());
            database.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TRIGGER reject_projection BEFORE INSERT ON resource_post_search BEGIN SELECT RAISE(ABORT,'projection failure'); END");
                }
                return null;
            });
            assertThrows(IllegalStateException.class, () -> repository.savePost(withText(refreshed, "#wrong must rollback", "wrong", "wrong")));
            assertEquals("#new Refreshed", repository.detail(original.key(), 20, 0).post().text());
            assertEquals(1L, repository.catalog("1", query(List.of(), "new", "refreshed"), 20, 0).total());
            assertEquals(0L, repository.catalog("1", query(List.of(), "wrong", ""), 20, 0).total());
        }
    }

    @Test
    void backfillContinuesPastOneHundredRowsAndCatalogUsesCurrentCoverage() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            for (int index = 1; index <= 205; index++) repository.savePost(post("1", "-1001", Integer.toString(index), "#backfill body", "title"));
            database.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM resource_post_tags"); statement.executeUpdate("DELETE FROM resource_post_search");
                }
                return null;
            });
            var result = repository.catalog("1", query(List.of(), "backfill", "body"), 200, 0);
            assertEquals(205L, result.total()); assertEquals(200, result.items().size()); assertTrue(result.hasMore());
            assertEquals(205, count(database, "SELECT COUNT(*) FROM resource_post_search"));
            Post post = result.items().getFirst().post();
            assertEquals("PARTIAL", post.coverage());
            repository.savePage(page(post, List.of(), true));
            var completed = repository.catalog("1", query(List.of(), "", ""), 200, 0).items().stream()
                .filter(item -> item.post().key().equals(post.key())).findFirst().orElseThrow();
            assertEquals("COMPLETE", completed.post().coverage());
            assertEquals(repository.detail(post.key(), 20, 0).post(), completed.post());
        }
    }

    private Post post(String account, String channel, String id, String text, String title) {
        MessageRef main = new MessageRef(channel, id, "https://t.me/example/" + id);
        String discussion = Long.toString(Long.parseLong(channel) - 1000);
        return new Post(account + ":" + channel + ":" + id, account, channel, "Channel " + channel, id, title, text, "0", List.of(main), List.of(),
            discussion, "500", List.of(new MessageRef(discussion, "500", "")), true, "PARTIAL", List.of(),
            new AlbumEvidence(true, "SINGLE_MESSAGE", null, null), "COMPLETE");
    }
    private Post withText(Post post, String text, String title, String channelTitle) {
        return new Post(post.key(), post.accountId(), post.channelId(), channelTitle, post.mainMessageId(), title, text, post.albumId(),
            post.mainMessages(), post.mainMedia(), post.discussionChatId(), post.discussionRootMessageId(), post.discussionRoots(),
            post.canReadComments(), post.coverage(), post.warnings(), post.albumEvidence(), post.mainCoverage());
    }
    private Media comment(Post post, String id, String type, String name, String caption) {
        return new Media(post.accountId() + ":" + post.discussionChatId() + ":" + id + ":0", new MessageRef(post.discussionChatId(), id, ""),
            "COMMENT", type, name, 1234, caption);
    }
    private Page page(Post post, List<Media> media, boolean exhausted) {
        List<MessageRef> messages = media.stream().map(Media::message).distinct().toList();
        return new Page(post.key(), media, messages, messages.size(), null, exhausted, exhausted ? "COMPLETE" : "PARTIAL", List.of());
    }
    private PostSearch.Query query(List<String> channels, String tags, String keywords) { return PostSearch.parse(channels, tags, keywords); }
    private void stamp(Database database, String key, long stamp) {
        database.write(connection -> {
            try (var statement = connection.prepareStatement("UPDATE resource_posts SET updated_at=? WHERE post_key=?")) {
                statement.setLong(1, stamp); statement.setString(2, key); statement.executeUpdate();
            }
            return null;
        });
    }
    private int count(Database database, String sql) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getInt(1); }
        });
    }
}
