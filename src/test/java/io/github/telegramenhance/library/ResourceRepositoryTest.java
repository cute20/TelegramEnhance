package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.library.ResourceModels.CommentObservation;
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
import tools.jackson.databind.json.JsonMapper;

class ResourceRepositoryTest {
    @TempDir Path directory;

    @Test
    void postAlbumAndLargeStringIdentitiesRoundTripAcrossRestart() {
        AppPaths paths = new AppPaths(directory);
        Post expected = post("9007199254740993", "-1009007199254740999", "-2009007199254740999", "500", true, List.of());
        try (Database database = new Database(paths)) {
            new ResourceRepository(database).savePost(expected);
        }
        try (Database database = new Database(paths)) {
            ResourceRepository repository = new ResourceRepository(database);
            assertEquals(expected, repository.detail(expected.key(), 20, 0).post());
            assertEquals(List.of(expected), repository.posts(20, 0));
            assertEquals(List.of(), repository.posts(20, 1));
            assertEquals("PARTIAL", repository.detail(expected.key(), 20, 0).coverage());
            assertFalse(repository.detail(expected.key(), 20, 0).exhausted());
        }
    }

    @Test
    void sameMessageNumberInDifferentChannelsAndAccountsDoesNotMerge() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post first = post("1001", "-1001", "-2001", "500", true, List.of());
            Post second = post("1001", "-1002", "-2002", "500", true, List.of());
            Post anotherAccount = post("1002", "-1001", "-2001", "500", true, List.of());
            for (Post post : List.of(first, second, anotherAccount)) {
                repository.savePost(post);
                Media media = media(post, "601", "caption for " + post.key());
                repository.savePage(page(post, List.of(media), List.of(media.message()), "cursor-1", false, "PARTIAL"));
            }
            assertEquals(3, repository.posts(20, 0).size());
            assertEquals(2, repository.posts("1001", 20, 0).size());
            assertEquals(List.of(anotherAccount), repository.posts("1002", 20, 0));
            assertTrue(repository.posts("never-connected-account", 20, 0).isEmpty());
            for (Post post : List.of(first, second, anotherAccount)) {
                var detail = repository.detail(post.key(), 20, 0);
                assertEquals(1, detail.commentMedia().size());
                assertEquals("caption for " + post.key(), detail.commentMedia().getFirst().caption());
                assertEquals(post.discussionChatId(), detail.commentMedia().getFirst().message().chatId());
            }
        }
    }

    @Test
    void overlappingPagesDeduplicateMediaAndAllMessagesAndKeepLocalPagination() {
        AppPaths paths = new AppPaths(directory);
        Post post = post("1001", "-1001", "-2001", "500", true, List.of());
        try (Database database = new Database(paths)) {
            ResourceRepository repository = new ResourceRepository(database);
            repository.savePost(post);
            Media first = media(post, "601", "first version");
            MessageRef textOnly = ref(post.discussionChatId(), "602");
            Page firstPage = page(post, List.of(first), List.of(first.message(), textOnly), "cursor-2", false, "PARTIAL");
            repository.savePage(firstPage);
            repository.savePage(firstPage);
            Media updated = media(post, "601", "updated caption");
            repository.savePage(page(post, List.of(updated), List.of(first.message(), textOnly), "cursor-2", false, "PARTIAL"));
            Media last = media(post, "603", "last");
            repository.savePage(page(post, List.of(last), List.of(textOnly, last.message()), null, true, "COMPLETE"));
            assertEquals(3, count(database, "resource_comment_messages"));
            assertEquals(2, count(database, "resource_comment_media"));
            assertEquals(3, countValue(database, "SELECT message_count FROM resource_scan_state"));
            assertEquals(List.of(updated), repository.detail(post.key(), 1, 0).commentMedia());
            assertEquals(List.of(last), repository.detail(post.key(), 1, 1).commentMedia());
            assertTrue(repository.detail(post.key(), 1, 2).commentMedia().isEmpty());
            assertEquals("COMPLETE", repository.detail(post.key(), 1, 0).coverage());
            assertTrue(repository.detail(post.key(), 1, 0).exhausted());
        }
        try (Database reopened = new Database(paths)) {
            var detail = new ResourceRepository(reopened).detail(post.key(), 20, 0);
            assertEquals(2, detail.commentMedia().size());
            assertNull(detail.nextCursor());
            assertEquals("COMPLETE", detail.coverage());
        }
    }

    @Test
    void checkpointFailureRollsBackMessagesAndMediaTogether() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1001", "-1001", "-2001", "500", true, List.of());
            repository.savePost(post);
            repository.savePage(page(post, List.of(), List.of(), "original-cursor", false, "PARTIAL"));
            database.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                        CREATE TRIGGER reject_checkpoint BEFORE UPDATE OF next_cursor ON resource_scan_state
                        BEGIN SELECT RAISE(ABORT, 'checkpoint test rejection'); END
                        """);
                }
                return null;
            });
            Media media = media(post, "601", "must roll back");
            assertThrows(IllegalStateException.class, () -> repository.savePage(
                page(post, List.of(media), List.of(media.message(), ref("-2001", "602")), "new-cursor", false, "PARTIAL")));
            assertEquals(0, count(database, "resource_comment_messages"));
            assertEquals(0, count(database, "resource_comment_media"));
            assertEquals("original-cursor", repository.detail(post.key(), 20, 0).nextCursor());
            assertEquals("PARTIAL", repository.detail(post.key(), 20, 0).coverage());
        }
    }

    @Test
    void changedThreadMappingResetsOnlyTheAffectedPostsCommentData() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post first = post("1001", "-1001", "-2001", "500", true, List.of());
            Post second = post("1001", "-1002", "-2002", "500", true, List.of());
            for (Post post : List.of(first, second)) {
                repository.savePost(post);
                Media media = media(post, "601", "existing");
                repository.savePage(page(post, List.of(media), List.of(media.message()), "saved-cursor", false, "PARTIAL"));
            }
            Post remapped = post("1001", "-1001", "-2001", "700", true, List.of("讨论根相册已变化"));
            repository.savePost(remapped);
            var detail = repository.detail(first.key(), 20, 0);
            assertTrue(detail.commentMedia().isEmpty());
            assertNull(detail.nextCursor());
            assertFalse(detail.exhausted());
            assertEquals("PARTIAL", detail.coverage());
            assertEquals(1, count(database, "resource_comment_messages"));
            assertEquals(1, repository.detail(second.key(), 20, 0).commentMedia().size());
            assertEquals("saved-cursor", repository.detail(second.key(), 20, 0).nextCursor());
        }
    }

    @Test
    void refreshingSameThreadKeepsHistoryButLostPermissionCannotClaimComplete() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post original = post("1001", "-1001", "-2001", "500", true, List.of());
            repository.savePost(original);
            Media media = media(original, "601", "existing");
            repository.savePage(page(original, List.of(media), List.of(media.message()), null, true, "COMPLETE"));
            repository.savePost(original);
            assertEquals("COMPLETE", repository.detail(original.key(), 20, 0).coverage());
            Post denied = post("1001", "-1001", "-2001", "500", false, List.of("当前账号无法读取讨论组"));
            repository.savePost(denied);
            var detail = repository.detail(original.key(), 20, 0);
            assertEquals(List.of(media), detail.commentMedia());
            assertEquals("UNAVAILABLE", detail.coverage());
            assertFalse(detail.exhausted());
            assertTrue(detail.warnings().contains("当前账号无法读取讨论组"));
        }
    }

    @Test
    void everyRootAlbumMemberIsExcludedAndMediaMustHaveEnumeratedIdentity() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1001", "-1001", "-2001", "500", true, List.of());
            repository.savePost(post);
            // The second forwarded album root must be rejected, just like the primary root.
            MessageRef extraRoot = ref(post.discussionChatId(), "501");
            assertThrows(IllegalArgumentException.class, () -> repository.savePage(
                page(post, List.of(), List.of(extraRoot), "wrong-cursor", false, "PARTIAL")));
            Media media = media(post, "601", "example");
            assertThrows(IllegalArgumentException.class, () -> repository.savePage(
                page(post, List.of(media), List.of(), "wrong-cursor", false, "PARTIAL")));
            assertThrows(IllegalArgumentException.class, () -> repository.savePage(
                page(post, List.of(), List.of(ref("-another-chat", "601")), "wrong-cursor", false, "PARTIAL")));
            assertEquals(0, count(database, "resource_comment_messages"));
            assertNull(repository.detail(post.key(), 20, 0).nextCursor());
        }
    }

    @Test
    void malformedStoredJsonProducesGenericErrorWithoutPostBody() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1001", "-1001", "-2001", "500", true, List.of());
            repository.savePost(post);
            database.write(connection -> {
                try (var statement = connection.prepareStatement("UPDATE resource_posts SET post_json=?")) {
                    statement.setString(1, "{\"text\":\"private-body-never-log\",");
                    statement.executeUpdate();
                }
                return null;
            });
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> repository.detail(post.key(), 20, 0));
            assertFalse(failure.getMessage().contains("private-body-never-log"));
            assertNull(failure.getCause());
        }
    }

    @Test
    void stableAlbumKeyAllowsCanonicalMessageToChangeWithoutLosingComments() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            String albumKey = "1001:-1001:album:9999";
            Post original = new Post(albumKey, "1001", "-1001", "频道", "101", "标题", "正文", "9999",
                List.of(ref("-1001", "101")), List.of(), "-2001", "500", List.of(ref("-2001", "500")),
                true, "PARTIAL", List.of("主帖相册范围未证明完整"));
            repository.savePost(original);
            Media media = media(original, "601", "keep comment");
            repository.savePage(page(original, List.of(media), List.of(media.message()), "saved-cursor", false, "PARTIAL"));
            Post discoveredEarlierMember = new Post(albumKey, "1001", "-1001", "频道", "100", "标题", "正文", "9999",
                List.of(ref("-1001", "100"), ref("-1001", "101")), List.of(), "-2001", "500", List.of(ref("-2001", "500")),
                true, "PARTIAL", List.of("主帖相册范围未证明完整"));
            repository.savePost(discoveredEarlierMember);
            var detail = repository.detail(albumKey, 20, 0);
            assertEquals("100", detail.post().mainMessageId());
            assertEquals(albumKey, detail.post().key());
            assertEquals(List.of(media), detail.commentMedia());
            assertEquals("saved-cursor", detail.nextCursor());
            assertEquals(1, repository.posts("1001", 20, 0).size());
            assertEquals(100, countValue(database, "SELECT CAST(main_message_id AS INTEGER) FROM resource_posts"));
        }
    }

    @Test
    void legacyPostJsonKeepsUnknownMainCoverageWhileNewStructuredEvidenceRoundTrips() {
        var json = JsonMapper.builder().build();
        String legacyJson = """
            {"key":"1001:-1001:100","accountId":"1001","channelId":"-1001","channelTitle":"旧频道",
             "mainMessageId":"100","title":"旧标题","text":"旧主帖正文","albumId":"9007199254741993",
             "mainMessages":[{"chatId":"-1001","messageId":"100","link":""}],"mainMedia":[],
             "discussionChatId":"-2001","discussionRootMessageId":"500",
             "discussionRoots":[{"chatId":"-2001","messageId":"500","link":""}],
             "canReadComments":true,"coverage":"COMPLETE","warnings":[]}
            """;
        Post legacy = json.readValue(legacyJson, Post.class);
        assertNull(legacy.albumEvidence());
        assertEquals("PARTIAL", legacy.mainCoverage(), "A legacy COMPLETE value does not prove the main album");
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            repository.savePost(legacy);
            repository.savePage(new Page(legacy.key(), List.of(), List.of(), 0, null, true,
                "PARTIAL", List.of(), "COMPLETE", List.of()));
            var detail = repository.detail(legacy.key(), 20, 0);
            assertEquals("COMPLETE", detail.commentCoverage());
            assertEquals("PARTIAL", detail.mainCoverage());
            assertEquals("PARTIAL", detail.coverage());
            assertEquals(detail.coverage(), repository.posts("1001", 20, 0).getFirst().coverage());
        }
        Post modern = post("9007199254740993", "-1009007199254740999", "-2009007199254740999", "500", true, List.of());
        String modernJson = json.writeValueAsString(modern);
        assertTrue(modernJson.contains("\"albumEvidence\""));
        assertTrue(modernJson.contains("\"mainCoverage\""));
        assertEquals(modern, json.readValue(modernJson, Post.class));
        assertEquals("9007199254740993", json.readValue(modernJson, Post.class).accountId());
    }

    @Test
    void mainAndCommentScopesStayIndependentAndListCoverageMatchesDetailThroughRefreshes() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post base = post("1001", "-1001", "-2001", "500", true, List.of());
            Post partialMain = withMain(base, "PARTIAL", List.of("尚未取得主帖相册新侧边界"));
            repository.savePost(partialMain);
            repository.savePage(new Page(base.key(), List.of(), List.of(), 0, null, true,
                "PARTIAL", partialMain.warnings(), "COMPLETE", List.of()));
            var commentsComplete = repository.detail(base.key(), 20, 0);
            assertEquals("PARTIAL", commentsComplete.mainCoverage());
            assertEquals("COMPLETE", commentsComplete.commentCoverage());
            assertEquals("PARTIAL", commentsComplete.coverage());
            assertTrue(commentsComplete.commentWarnings().isEmpty(), "Main warnings cannot contaminate comment coverage");
            assertEquals(commentsComplete.coverage(), repository.posts("1001", 20, 0).getFirst().coverage());

            repository.savePost(base);
            var allComplete = repository.detail(base.key(), 20, 0);
            assertEquals("COMPLETE", allComplete.mainCoverage());
            assertEquals("COMPLETE", allComplete.commentCoverage());
            assertEquals("COMPLETE", allComplete.coverage());
            assertEquals(allComplete.post(), repository.posts("1001", 20, 0).getFirst());

            String unresolvedComment = "评论包含尚未支持的媒体，评论清单不完整。";
            repository.savePage(new Page(base.key(), List.of(), List.of(), 0, null, true,
                "PARTIAL", List.of(unresolvedComment), "PARTIAL", List.of(unresolvedComment)));
            var partialComments = repository.detail(base.key(), 20, 0);
            assertEquals("COMPLETE", partialComments.mainCoverage());
            assertEquals("PARTIAL", partialComments.commentCoverage());
            assertEquals("PARTIAL", partialComments.coverage());
            assertEquals(List.of(unresolvedComment), partialComments.commentWarnings());
            assertEquals(partialComments.post(), repository.posts("1001", 20, 0).getFirst());

            repository.savePost(post("1001", "-1001", "-2001", "500", false, List.of("当前账号无法访问评论")));
            var unavailable = repository.detail(base.key(), 20, 0);
            assertEquals("COMPLETE", unavailable.mainCoverage());
            assertEquals("UNAVAILABLE", unavailable.commentCoverage());
            assertEquals("UNAVAILABLE", unavailable.coverage());
            assertEquals(unavailable.post(), repository.posts("1001", 20, 0).getFirst());
        }
    }

    @Test
    void indexedTotalsUseActualDeduplicatedTablesAndDoNotEqualTheCurrentLocalPageSize() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1001", "-1001", "-2001", "500", true, List.of());
            repository.savePost(post);
            Media first = media(post, "601", "first");
            Media second = media(post, "602", "second");
            MessageRef textOnly = ref("-2001", "603");
            Page page = page(post, List.of(first, second), List.of(first.message(), second.message(), textOnly), "next-cursor", false, "PARTIAL");
            repository.savePage(page);
            repository.savePage(page);
            database.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    // A legacy counter may be stale; the UI must report real persisted identities.
                    statement.executeUpdate("UPDATE resource_scan_state SET message_count=999999");
                }
                return null;
            });
            for (int offset : List.of(0, 1, 2)) {
                var detail = repository.detail(post.key(), 1, offset);
                assertEquals(3L, detail.indexedCommentMessages());
                assertEquals(2L, detail.indexedCommentMedia());
                assertEquals(offset < 2 ? 1 : 0, detail.commentMedia().size());
                assertEquals("next-cursor", detail.nextCursor());
            }
            Post other = post("1001", "-1002", "-2002", "500", true, List.of());
            repository.savePost(other);
            assertEquals(0L, repository.detail(other.key(), 1, 0).indexedCommentMessages());
            assertEquals(0L, repository.detail(other.key(), 1, 0).indexedCommentMedia());
        }
    }

    @Test
    void refreshedAlbumClearsLegacyMainWarningWithoutErasingUnresolvedCommentWarnings() {
        String oldAlbumWarning = "主帖相册通过有限邻域读取识别，请与 Telegram 核对成员；本片不宣称已证明相册完整。";
        String oldInfo = "本次可见历史已读至结尾；新增评论需从首轮重新读取，特殊媒体仍以主帖警告为准。";
        String commentProblem = "评论中的特殊媒体未展开，评论清单不完整。";
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post base = post("1001", "-1001", "-2001", "500", true, List.of());
            repository.savePost(withMain(base, "PARTIAL", List.of(oldAlbumWarning)));
            repository.savePage(new Page(base.key(), List.of(), List.of(), 0, null, true,
                "PARTIAL", List.of(oldAlbumWarning, oldInfo), "PARTIAL", List.of(oldAlbumWarning, oldInfo)));
            var legacy = repository.detail(base.key(), 20, 0);
            assertEquals("PARTIAL", legacy.mainCoverage());
            assertEquals("COMPLETE", legacy.commentCoverage());
            assertTrue(legacy.commentWarnings().isEmpty());

            repository.savePost(base);
            var refreshed = repository.detail(base.key(), 20, 0);
            assertEquals("COMPLETE", refreshed.coverage());
            assertTrue(refreshed.warnings().isEmpty());
            assertEquals(refreshed.post(), repository.posts("1001", 20, 0).getFirst());

            repository.savePage(new Page(base.key(), List.of(), List.of(), 0, null, true,
                "PARTIAL", List.of(oldAlbumWarning, commentProblem), "PARTIAL", List.of(oldAlbumWarning, commentProblem)));
            repository.savePost(base);
            var unresolved = repository.detail(base.key(), 20, 0);
            assertEquals("COMPLETE", unresolved.mainCoverage());
            assertEquals("PARTIAL", unresolved.commentCoverage());
            assertEquals(List.of(commentProblem), unresolved.commentWarnings());
            assertEquals(List.of(commentProblem), unresolved.warnings());
            assertEquals(unresolved.post(), repository.posts("1001", 20, 0).getFirst());
        }
    }

    @Test
    void temporarilyUnreadableNullMappingAndRecoveryKeepIndexedCommentsAndCursor() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post original = post("1001", "-1001", "-2001", "500", true, List.of());
            repository.savePost(original);
            Media first = media(original, "601", "keep first");
            Media second = media(original, "602", "keep second");
            repository.savePage(page(original, List.of(first, second), List.of(first.message(), second.message()), "resume-here", false, "PARTIAL"));
            Post unavailable = new Post(original.key(), original.accountId(), original.channelId(), original.channelTitle(),
                original.mainMessageId(), original.title(), original.text(), original.albumId(), original.mainMessages(), original.mainMedia(),
                null, null, List.of(), false, "UNAVAILABLE", List.of("讨论组暂时无法读取"), original.albumEvidence(), original.mainCoverage());
            repository.savePost(unavailable);
            var duringFailure = repository.detail(original.key(), 20, 0);
            assertFalse(duringFailure.post().canReadComments());
            assertEquals("UNAVAILABLE", duringFailure.commentCoverage());
            assertEquals(original.discussionChatId(), duringFailure.post().discussionChatId());
            assertEquals(original.discussionRootMessageId(), duringFailure.post().discussionRootMessageId());
            assertEquals(original.discussionRoots(), duringFailure.post().discussionRoots());
            assertEquals(List.of(first, second), duringFailure.commentMedia());
            assertEquals(2L, duringFailure.indexedCommentMessages());
            assertEquals(2L, duringFailure.indexedCommentMedia());
            assertEquals("resume-here", duringFailure.nextCursor());

            repository.savePost(original);
            var recovered = repository.detail(original.key(), 20, 0);
            assertTrue(recovered.post().canReadComments());
            assertEquals("PARTIAL", recovered.commentCoverage());
            assertEquals(List.of(first, second), recovered.commentMedia());
            assertEquals("resume-here", recovered.nextCursor());
            assertEquals(2L, recovered.indexedCommentMessages());
            assertEquals(recovered.post(), repository.posts("1001", 20, 0).getFirst());
        }
    }

    @Test
    void newlyRecognizedRootAlbumMemberRemovesOnlyThatCommentsIdentityAndMedia() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post original = post("1001", "-1001", "-2001", "500", true, List.of());
            repository.savePost(original);
            Media newlyRecognizedRoot = media(original, "601", "forwarded root discovered later");
            Media realComment = media(original, "602", "real comment must remain");
            MessageRef textOnlyComment = ref("-2001", "603");
            repository.savePage(page(original, List.of(newlyRecognizedRoot, realComment),
                List.of(newlyRecognizedRoot.message(), realComment.message(), textOnlyComment), "keep-checkpoint", false, "PARTIAL"));
            Post extendedRoots = new Post(original.key(), original.accountId(), original.channelId(), original.channelTitle(),
                original.mainMessageId(), original.title(), original.text(), original.albumId(), original.mainMessages(), original.mainMedia(),
                original.discussionChatId(), original.discussionRootMessageId(),
                List.of(original.discussionRoots().getFirst(), original.discussionRoots().get(1), newlyRecognizedRoot.message()),
                true, original.coverage(), List.of(), original.albumEvidence(), original.mainCoverage());
            repository.savePost(extendedRoots);
            var corrected = repository.detail(original.key(), 20, 0);
            assertEquals(List.of(realComment), corrected.commentMedia());
            assertEquals("keep-checkpoint", corrected.nextCursor());
            assertEquals(2L, corrected.indexedCommentMessages());
            assertEquals(1L, corrected.indexedCommentMedia());
            assertEquals(2, countValue(database, "SELECT message_count FROM resource_scan_state"));
            assertEquals(0, countValue(database, "SELECT COUNT(*) FROM resource_comment_messages WHERE message_id='601'"));
            assertEquals(1, countValue(database, "SELECT COUNT(*) FROM resource_comment_messages WHERE message_id='603'"));
            assertEquals(corrected.post(), repository.posts("1001", 20, 0).getFirst());
        }
    }

    @Test
    void partialAlbumRefreshKeepsPreviouslyKnownMainMembersMediaAndCanonicalMessage() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            String key = "1001:-1001:album:9999";
            MessageRef first = ref("-1001", "100");
            MessageRef middle = ref("-1001", "101");
            MessageRef last = ref("-1001", "102");
            Media firstMedia = new Media("1001:-1001:100:0", first, "MAIN", "PHOTO", "first.jpg", 100, "first");
            Media middleMedia = new Media("1001:-1001:101:0", middle, "MAIN", "PHOTO", "middle.jpg", 200, "middle");
            Media lastMedia = new Media("1001:-1001:102:0", last, "MAIN", "PHOTO", "last.jpg", 300, "last");
            Post complete = new Post(key, "1001", "-1001", "频道", "100", "三图主帖", "保留正文", "9999",
                List.of(first, middle, last), List.of(firstMedia, middleMedia, lastMedia), "-2001", "500", List.of(ref("-2001", "500")),
                true, "PARTIAL", List.of(), new ResourceModels.AlbumEvidence(true, "ADJACENT_BOUNDARIES", "200", "90"), "COMPLETE");
            repository.savePost(complete);
            Media comment = media(complete, "601", "keep comment");
            repository.savePage(page(complete, List.of(comment), List.of(comment.message()), "resume-comments", false, "PARTIAL"));

            Media refreshedMiddle = new Media(middleMedia.key(), middle, "MAIN", "PHOTO", "middle.jpg", 200, "fresh caption");
            Post partial = new Post(key, "1001", "-1001", "频道", "101", "本次只读到中间成员", "保留正文", "9999",
                List.of(middle), List.of(refreshedMiddle), "-2001", "500", List.of(ref("-2001", "500")),
                true, "PARTIAL", List.of("本次未取得新侧边界"), new ResourceModels.AlbumEvidence(false, "PARTIAL_SCAN", null, null), "PARTIAL");
            repository.savePost(partial);
            var detail = repository.detail(key, 20, 0);
            assertEquals(List.of(first, middle, last), detail.post().mainMessages());
            assertEquals(List.of(firstMedia, refreshedMiddle, lastMedia), detail.post().mainMedia());
            assertEquals("100", detail.post().mainMessageId(), "Canonical identity follows the merged known album members");
            assertEquals("PARTIAL", detail.mainCoverage());
            assertFalse(detail.post().albumEvidence().complete());
            assertTrue(detail.warnings().stream().anyMatch(w -> w.contains("保留此前已知成员")));
            assertEquals(List.of(comment), detail.commentMedia());
            assertEquals("resume-comments", detail.nextCursor());
            assertEquals(1L, detail.indexedCommentMessages());
            assertEquals(1L, detail.indexedCommentMedia());
            assertEquals(detail.post(), repository.posts("1001", 20, 0).getFirst());
        }
    }

    @Test
    void completeObservationReplacesOnlyItsMessageSnapshotAndKeepsExistingPositions() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1001", "-1001", "-2001", "500", true, List.of());
            repository.savePost(post);
            Media first = media(post, "601", "old caption");
            Media removed = slot(first, "1", "removed member");
            Media untouched = media(post, "602", "not observed this time");
            repository.savePage(page(post, List.of(first, removed, untouched), List.of(first.message(), untouched.message()), "old-cursor", false, "PARTIAL"));
            int firstPosition = position(database, first);
            int untouchedPosition = position(database, untouched);
            Media updated = new Media(first.key(), first.message(), first.source(), first.type(), first.fileName(), first.size(), "new caption");
            Media added = slot(first, "2", "new member");
            repository.savePage(observedPage(post, List.of(updated, added), List.of(first.message()),
                List.of(new CommentObservation(first.message(), true, List.of())), List.of(), true, null));
            var detail = repository.detail(post.key(), 20, 0);
            assertEquals(List.of(updated, untouched, added), detail.commentMedia());
            assertEquals(firstPosition, position(database, updated));
            assertEquals(untouchedPosition, position(database, untouched));
            assertEquals(2L, detail.indexedCommentMessages());
            assertEquals(3L, detail.indexedCommentMedia());
            assertEquals("COMPLETE", detail.commentCoverage());
            assertEquals(detail.post(), repository.posts("1001", 20, 0).getFirst());
        }
    }

    @Test
    void completeEmptyObservationClearsOnlyItsMessagesMediaAndKeepsItsIdentity() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1001", "-1001", "-2001", "500", true, List.of());
            repository.savePost(post);
            Media removed = media(post, "601", "now edited to text");
            Media untouched = media(post, "602", "still present");
            repository.savePage(page(post, List.of(removed, untouched), List.of(removed.message(), untouched.message()), "first-cursor", false, "PARTIAL"));
            repository.savePage(observedPage(post, List.of(), List.of(removed.message()),
                List.of(new CommentObservation(removed.message(), true, List.of())), List.of(), true, null));
            var detail = repository.detail(post.key(), 20, 0);
            assertEquals(List.of(untouched), detail.commentMedia());
            assertEquals(2L, detail.indexedCommentMessages());
            assertEquals(1L, detail.indexedCommentMedia());
            assertEquals(1, countValue(database, "SELECT COUNT(*) FROM resource_comment_messages WHERE message_id='601'"));
            assertEquals("COMPLETE", detail.commentCoverage());
        }
    }

    @Test
    void incompleteObservationKeepsOldMediaAndItsWarningSurvivesOtherPagesUntilReobserved() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1001", "-1001", "-2001", "500", true, List.of());
            repository.savePost(post);
            Media old = media(post, "601", "known before partial conversion");
            repository.savePage(page(post, List.of(old), List.of(old.message()), "first", false, "PARTIAL"));
            Media newlyKnown = slot(old, "1", "known new item");
            String defect = "该评论包含未展开的特殊媒体";
            repository.savePage(observedPage(post, List.of(newlyKnown), List.of(old.message()),
                List.of(new CommentObservation(old.message(), false, List.of(defect))), List.of(), false, "next"));
            repository.savePage(observedPage(post, List.of(), List.of(), List.of(), List.of(), true, null));
            var partial = repository.detail(post.key(), 1, 1);
            assertEquals(2L, partial.indexedCommentMedia());
            assertEquals(List.of(newlyKnown), partial.commentMedia());
            assertTrue(partial.commentScanWarnings().isEmpty());
            assertEquals(List.of(defect), partial.commentWarnings());
            assertEquals("PARTIAL", partial.commentCoverage());
            assertEquals("PARTIAL", storedText(database, "SELECT coverage FROM resource_scan_state"));
            assertTrue(partial.exhausted());
            assertEquals(partial.post(), repository.posts("1001", 20, 0).getFirst());

            repository.savePage(observedPage(post, List.of(newlyKnown), List.of(old.message()),
                List.of(new CommentObservation(old.message(), true, List.of())), List.of(), true, null));
            var resolved = repository.detail(post.key(), 20, 0);
            assertEquals(List.of(newlyKnown), resolved.commentMedia());
            assertTrue(resolved.commentWarnings().isEmpty());
            assertEquals("COMPLETE", resolved.commentCoverage());
            assertEquals("COMPLETE", storedText(database, "SELECT coverage FROM resource_scan_state"));
            assertEquals(resolved.post(), repository.posts("1001", 20, 0).getFirst());
        }
    }

    @Test
    void scanAndObservationWarningsAreStoredSeparatelyAndClearIndependently() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1001", "-1001", "-2001", "500", true, List.of());
            repository.savePost(post);
            MessageRef comment = ref("-2001", "601");
            String scan = "历史页未取得连续范围";
            String media = "这条评论的媒体未完整转换";
            repository.savePage(observedPage(post, List.of(), List.of(comment),
                List.of(new CommentObservation(comment, false, List.of(media))), List.of(scan), true, null));
            var both = repository.detail(post.key(), 20, 0);
            assertEquals(List.of(scan), both.commentScanWarnings());
            assertEquals(List.of(scan, media), both.commentWarnings());
            repository.savePage(observedPage(post, List.of(), List.of(comment),
                List.of(new CommentObservation(comment, true, List.of())), List.of(scan), true, null));
            var onlyScan = repository.detail(post.key(), 20, 0);
            assertEquals(List.of(scan), onlyScan.commentScanWarnings());
            assertEquals(List.of(scan), onlyScan.commentWarnings());
            assertEquals("PARTIAL", onlyScan.commentCoverage());
            repository.savePage(observedPage(post, List.of(), List.of(), List.of(), List.of(), true, null));
            assertEquals("COMPLETE", repository.detail(post.key(), 20, 0).commentCoverage());
        }
    }

    @Test
    void invalidObservationSubsetDuplicateOrMissingWarningCannotChangeAnySavedState() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1001", "-1001", "-2001", "500", true, List.of());
            repository.savePost(post);
            Media media = media(post, "601", "keep");
            repository.savePage(observedPage(post, List.of(media), List.of(media.message()),
                List.of(new CommentObservation(media.message(), true, List.of())), List.of(), false, "old"));
            var before = repository.detail(post.key(), 20, 0);
            var valid = new CommentObservation(media.message(), true, List.of());
            List<List<CommentObservation>> invalid = List.of(
                List.of(new CommentObservation(ref("-2001", "602"), true, List.of())),
                List.of(valid, valid),
                List.of(new CommentObservation(media.message(), false, List.of())),
                List.of(new CommentObservation(media.message(), false, List.of(" "))));
            for (List<CommentObservation> observations : invalid) {
                assertThrows(IllegalArgumentException.class, () -> repository.savePage(
                    observedPage(post, List.of(), List.of(media.message()), observations, List.of(), true, "wrong")));
                assertEquals(before, repository.detail(post.key(), 20, 0));
            }
        }
    }

    @Test
    void checkpointFailureRollsBackMediaReplacementObservationWarningsMessagesAndCursor() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            Post post = post("1001", "-1001", "-2001", "500", true, List.of());
            repository.savePost(post);
            Media first = media(post, "601", "old");
            Media oldSlot = slot(first, "1", "must not disappear");
            repository.savePage(observedPage(post, List.of(first, oldSlot), List.of(first.message()),
                List.of(new CommentObservation(first.message(), false, List.of("old unresolved defect"))), List.of("old scan warning"), false, "old"));
            var before = repository.detail(post.key(), 20, 0);
            int beforePosition = position(database, first);
            database.write(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TRIGGER reject_observed_checkpoint BEFORE UPDATE OF next_cursor ON resource_scan_state BEGIN SELECT RAISE(ABORT, 'deliberate checkpoint failure'); END");
                }
                return null;
            });
            Media fresh = slot(first, "2", "must roll back");
            Media newMessage = media(post, "602", "must roll back too");
            assertThrows(IllegalStateException.class, () -> repository.savePage(observedPage(post,
                List.of(first, fresh, newMessage), List.of(first.message(), newMessage.message()),
                List.of(new CommentObservation(first.message(), true, List.of()), new CommentObservation(newMessage.message(), true, List.of())),
                List.of(), true, "wrong")));
            assertEquals(before, repository.detail(post.key(), 20, 0));
            assertEquals(beforePosition, position(database, first));
            assertEquals(1, count(database, "resource_comment_messages"));
            assertEquals(2, count(database, "resource_comment_media"));
        }
    }

    private Page observedPage(Post post, List<Media> media, List<MessageRef> messages, List<CommentObservation> observations,
                              List<String> scanWarnings, boolean exhausted, String cursor) {
        String coverage = exhausted && scanWarnings.isEmpty() && observations.stream().allMatch(CommentObservation::mediaComplete) ? "COMPLETE" : "PARTIAL";
        return new Page(post.key(), media, messages, messages.size(), cursor, exhausted, coverage, List.of(), coverage, List.of(), observations, scanWarnings);
    }

    @Test
    void partialSinglePostRefreshRetainsKnownVideoAndACompleteTextSnapshotCanRemoveIt() {
        try (Database database = new Database(new AppPaths(directory))) {
            ResourceRepository repository = new ResourceRepository(database);
            MessageRef main = ref("-1001", "100");
            Media video = new Media("1001:-1001:100:0", main, "MAIN", "VIDEO", "known.mp4", 4096, "known video");
            Post original = new Post("1001:-1001:100", "1001", "-1001", "频道", "100", "标题", "原正文", "0",
                List.of(main), List.of(video), "-2001", "500", List.of(ref("-2001", "500")), true, "PARTIAL", List.of(),
                new ResourceModels.AlbumEvidence(true, "SINGLE_MESSAGE", null, null), "COMPLETE");
            repository.savePost(original);
            Media comment = media(original, "601", "preserve comment");
            repository.savePage(page(original, List.of(comment), List.of(comment.message()), "keep-cursor", false, "PARTIAL"));
            String freshWarning = "主帖视频缺少可验证的文件信息";
            Post partial = new Post(original.key(), original.accountId(), original.channelId(), original.channelTitle(),
                original.mainMessageId(), original.title(), "本次取得的正文", "0", List.of(main), List.of(),
                original.discussionChatId(), original.discussionRootMessageId(), original.discussionRoots(), true, "PARTIAL", List.of(freshWarning),
                new ResourceModels.AlbumEvidence(true, "SINGLE_MESSAGE", null, null), "PARTIAL");
            repository.savePost(partial);
            var retained = repository.detail(original.key(), 20, 0);
            assertEquals(List.of(video), retained.post().mainMedia());
            assertEquals(List.of(main), retained.post().mainMessages());
            assertEquals("PARTIAL", retained.mainCoverage());
            assertTrue(retained.warnings().contains(freshWarning));
            assertTrue(retained.warnings().stream().anyMatch(w -> w.contains("保留此前已知媒体")));
            assertEquals(List.of(comment), retained.commentMedia());
            assertEquals("keep-cursor", retained.nextCursor());

            Post completeText = new Post(original.key(), original.accountId(), original.channelId(), original.channelTitle(),
                original.mainMessageId(), original.title(), "已完整取得的纯文字正文", "0", List.of(main), List.of(),
                original.discussionChatId(), original.discussionRootMessageId(), original.discussionRoots(), true, "PARTIAL", List.of(),
                new ResourceModels.AlbumEvidence(true, "SINGLE_MESSAGE", null, null), "COMPLETE");
            repository.savePost(completeText);
            var replaced = repository.detail(original.key(), 20, 0);
            assertTrue(replaced.post().mainMedia().isEmpty());
            assertEquals("COMPLETE", replaced.mainCoverage());
            assertTrue(replaced.warnings().isEmpty());
            assertEquals(List.of(comment), replaced.commentMedia());
            assertEquals("keep-cursor", replaced.nextCursor());
            assertEquals(replaced.post(), repository.posts("1001", 20, 0).getFirst());
        }
    }

    private Media slot(Media template, String slot, String caption) {
        String key = template.key().substring(0, template.key().lastIndexOf(':') + 1) + slot;
        return new Media(key, template.message(), template.source(), template.type(), template.fileName(), template.size(), caption);
    }

    private int position(Database database, Media media) {
        return database.read(connection -> {
            try (var statement = connection.prepareStatement("SELECT position FROM resource_comment_media WHERE media_key=?")) {
                statement.setString(1, media.key());
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
            }
        });
    }

    private String storedText(Database database, String query) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
                assertTrue(rows.next()); return rows.getString(1);
            }
        });
    }

    private Post withMain(Post post, String mainCoverage, List<String> warnings) {
        return new Post(post.key(), post.accountId(), post.channelId(), post.channelTitle(), post.mainMessageId(),
            post.title(), post.text(), post.albumId(), post.mainMessages(), post.mainMedia(), post.discussionChatId(),
            post.discussionRootMessageId(), post.discussionRoots(), post.canReadComments(), "PARTIAL", warnings,
            new ResourceModels.AlbumEvidence("COMPLETE".equals(mainCoverage),
                "COMPLETE".equals(mainCoverage) ? "ADJACENT_BOUNDARIES" : "PARTIAL_SCAN", null, null), mainCoverage);
    }

    private Post post(String account, String channel, String discussion, String root, boolean canRead, List<String> warnings) {
        String key = account + ":" + channel + ":100";
        return new Post(key, account, channel, "频道标题", "100", "资源标题", "主帖正文 #标签", "9007199254741993",
            List.of(ref(channel, "100"), ref(channel, "101")),
            List.of(new Media(account + ":" + channel + ":100:0", ref(channel, "100"), "MAIN", "VIDEO", "main.mp4", 1234, "主帖说明")),
            discussion, root, List.of(ref(discussion, root), ref(discussion, Long.toString(Long.parseLong(root) + 1))),
            canRead, canRead ? "PARTIAL" : "UNAVAILABLE", warnings,
            new ResourceModels.AlbumEvidence(true, "ADJACENT_BOUNDARIES", "102", "99"), "COMPLETE");
    }

    private Media media(Post post, String id, String caption) {
        return new Media(post.accountId() + ":" + post.discussionChatId() + ":" + id + ":0",
            ref(post.discussionChatId(), id), "COMMENT", "VIDEO", "video.mp4", 4567, caption);
    }

    private MessageRef ref(String chat, String message) { return new MessageRef(chat, message, "https://t.me/c/1/" + message); }
    private Page page(Post post, List<Media> media, List<MessageRef> messages, String cursor, boolean exhausted, String coverage) {
        return new Page(post.key(), media, messages, messages.size(), cursor, exhausted, coverage, List.of());
    }
    private int count(Database database, String table) { return countValue(database, "SELECT COUNT(*) FROM " + table); }
    private int countValue(Database database, String sql) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        });
    }
}
