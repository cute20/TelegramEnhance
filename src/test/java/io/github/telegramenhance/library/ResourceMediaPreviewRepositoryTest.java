package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.storage.Database;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ResourceMediaPreviewRepositoryTest {
    @TempDir Path directory;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void singleMediaLookupIsScopedByAccountPostAndCompleteChatIdentity() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post a = post("1", "-1001", "-2001"), b = post("1", "-1002", "-2002"), other = post("2", "-1001", "-2001");
            for (Post post : List.of(a, b, other)) {
                repository.savePost(post);
                Media media = media(post, "100", "PHOTO", "COMMENT");
                repository.savePage(page(post, List.of(media)));
            }
            Media first = media(a, "100", "PHOTO", "COMMENT"), second = media(b, "100", "PHOTO", "COMMENT");
            var source = repository.mediaPreviewSource("1", a.key(), first.key());
            assertEquals(a, source.post()); assertEquals(first, source.media());
            assertEquals("-2001", source.media().message().chatId());
            assertEquals(second, repository.mediaPreviewSource("1", b.key(), second.key()).media());
            assertThrows(IllegalArgumentException.class, () -> repository.mediaPreviewSource("1", b.key(), first.key()));
            assertThrows(IllegalArgumentException.class, () -> repository.mediaPreviewSource("1", other.key(), media(other, "100", "PHOTO", "COMMENT").key()));
            assertThrows(IllegalArgumentException.class, () -> repository.mediaPreviewSource("2", a.key(), first.key()));
        }
    }

    @Test
    void onlyCommentPhotoOrVideoRowsWithTrustedMappingAreEligible() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1", "-1001", "-2001"); repository.savePost(post);
            Media photo = media(post, "601", "PHOTO", "COMMENT");
            Media video = media(post, "602", "VIDEO", "COMMENT");
            Media audio = media(post, "603", "AUDIO", "COMMENT");
            Media main = media(post, "604", "PHOTO", "MAIN");
            repository.savePage(page(post, List.of(photo, video, audio, main)));
            assertEquals(photo, repository.mediaPreviewSource("1", post.key(), photo.key()).media());
            assertEquals(video, repository.mediaPreviewSource("1", post.key(), video.key()).media());
            assertThrows(IllegalArgumentException.class, () -> repository.mediaPreviewSource("1", post.key(), audio.key()));
            assertThrows(IllegalArgumentException.class, () -> repository.mediaPreviewSource("1", post.key(), main.key()));
            Post noMapping = new Post(post.key(), post.accountId(), post.channelId(), post.channelTitle(), post.mainMessageId(), post.title(),
                post.text(), "0", post.mainMessages(), post.mainMedia(), null, null, List.of(), false, "UNAVAILABLE", List.of());
            replacePost(database, post.key(), noMapping);
            assertThrows(IllegalArgumentException.class, () -> repository.mediaPreviewSource("1", post.key(), photo.key()));
        }
    }

    @Test
    void staleRootRowsAndMismatchedMediaOrPostJsonCannotBeUsedAsPreviewSources() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1", "-1001", "-2001"); repository.savePost(post);
            Media original = media(post, "601", "PHOTO", "COMMENT"); repository.savePage(page(post, List.of(original)));
            List<Media> invalid = List.of(
                new Media("wrong-key", original.message(), "COMMENT", "PHOTO", "synthetic.jpg", 10, "private-synthetic-caption"),
                new Media(original.key(), new MessageRef("-9999", "601", ""), "COMMENT", "PHOTO", "synthetic.jpg", 10, "private-synthetic-caption"),
                new Media(original.key(), new MessageRef("-2001", "999", ""), "COMMENT", "PHOTO", "synthetic.jpg", 10, "private-synthetic-caption"));
            for (Media corrupt : invalid) {
                database.write(connection -> {
                    try (var statement = connection.prepareStatement("UPDATE resource_comment_media SET media_json=? WHERE post_key=? AND media_key=?")) {
                        statement.setString(1, json.writeValueAsString(corrupt)); statement.setString(2, post.key()); statement.setString(3, original.key()); statement.executeUpdate();
                    }
                    return null;
                });
                var failure = assertThrows(IllegalStateException.class, () -> repository.mediaPreviewSource("1", post.key(), original.key()));
                assertFalse(failure.getMessage().contains("private-synthetic-caption")); assertNull(failure.getCause());
            }
            repository.savePage(page(post, List.of(original)));
            Post root = new Post(post.key(), post.accountId(), post.channelId(), post.channelTitle(), post.mainMessageId(), post.title(),
                post.text(), "0", post.mainMessages(), post.mainMedia(), post.discussionChatId(), "500", List.of(original.message()), true, "PARTIAL", List.of());
            replacePost(database, post.key(), root); // Old indexes may have retained a newly recognized root.
            assertThrows(IllegalArgumentException.class, () -> repository.mediaPreviewSource("1", post.key(), original.key()));
            Post wrongAccount = new Post(post.key(), "2", post.channelId(), post.channelTitle(), post.mainMessageId(), post.title(),
                post.text(), "0", post.mainMessages(), post.mainMedia(), post.discussionChatId(), "500", List.of(), true, "PARTIAL", List.of());
            replacePost(database, post.key(), wrongAccount);
            assertThrows(IllegalStateException.class, () -> repository.mediaPreviewSource("1", post.key(), original.key()));
        }
    }

    @Test
    void lookupReadsOneMediaRowAndKeepsCoverageCountsAndVersionsUnchanged() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1", "-1001", "-2001"); repository.savePost(post);
            Media chosen = media(post, "601", "PHOTO", "COMMENT"), unrelated = media(post, "602", "VIDEO", "COMMENT");
            repository.savePage(page(post, List.of(chosen, unrelated)));
            database.write(connection -> {
                try (var statement = connection.prepareStatement("UPDATE resource_posts SET updated_at=123 WHERE post_key=?")) {
                    statement.setString(1, post.key()); statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("UPDATE resource_scan_state SET updated_at=456 WHERE post_key=?")) {
                    statement.setString(1, post.key()); statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("UPDATE resource_comment_media SET media_json='private-malformed-json{' WHERE post_key=? AND media_key=?")) {
                    statement.setString(1, post.key()); statement.setString(2, unrelated.key()); statement.executeUpdate();
                }
                return null;
            });
            var source = repository.mediaPreviewSource("1", post.key(), chosen.key());
            assertEquals(chosen, source.media()); assertEquals(123L, source.postUpdatedAt()); assertEquals(456L, source.scanUpdatedAt());
            assertEquals(post, source.post());
            assertEquals(source, repository.mediaPreviewSource("1", post.key(), chosen.key()));
        }
    }

    @Test
    void knownMappingMayRemainPreviewableWhenCommentCoverageIsUnavailable() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1", "-1001", "-2001"); repository.savePost(post);
            Media chosen = media(post, "601", "PHOTO", "COMMENT"); repository.savePage(page(post, List.of(chosen)));
            Post denied = new Post(post.key(), post.accountId(), post.channelId(), post.channelTitle(), post.mainMessageId(), post.title(),
                post.text(), "0", post.mainMessages(), post.mainMedia(), post.discussionChatId(), "500", post.discussionRoots(), false, "UNAVAILABLE", List.of("索引范围不可用"));
            repository.savePost(denied);
            var before = repository.detail(post.key(), 20, 0);
            assertFalse(repository.mediaPreviewSource("1", post.key(), chosen.key()).post().canReadComments());
            assertEquals(before, repository.detail(post.key(), 20, 0));
            assertEquals("UNAVAILABLE", before.commentCoverage());
        }
    }

    private Post post(String account, String channel, String discussion) {
        return new Post(account + ":" + channel + ":100", account, channel, "Synthetic channel", "100", "Synthetic post", "body", "0",
            List.of(new MessageRef(channel, "100", "")), List.of(), discussion, "500", List.of(new MessageRef(discussion, "500", "")), true, "PARTIAL", List.of());
    }
    private Media media(Post post, String id, String type, String source) {
        return new Media(post.accountId() + ":" + post.discussionChatId() + ":" + id + ":0", new MessageRef(post.discussionChatId(), id, ""), source, type, "synthetic", 10, "caption");
    }
    private Page page(Post post, List<Media> media) {
        var messages = media.stream().map(Media::message).toList();
        return new Page(post.key(), media, messages, messages.size(), "saved-cursor", false, "PARTIAL", List.of());
    }
    private void replacePost(Database database, String key, Post post) {
        database.write(connection -> {
            try (var statement = connection.prepareStatement("UPDATE resource_posts SET post_json=? WHERE post_key=?")) {
                statement.setString(1, json.writeValueAsString(post)); statement.setString(2, key); statement.executeUpdate();
            }
            return null;
        });
    }
}
