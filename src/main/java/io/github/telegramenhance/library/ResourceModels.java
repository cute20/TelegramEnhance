package io.github.telegramenhance.library;

import java.util.List;

/** Stable boundary DTOs. Telegram's 64-bit identities are always strings. */
public final class ResourceModels {
    private ResourceModels() { }

    public record MessageRef(String chatId, String messageId, String link) { }
    public record AlbumEvidence(boolean complete, String method, String newerBoundaryId, String olderBoundaryId) { }
    public record CommentObservation(MessageRef message, boolean mediaComplete, List<String> warnings) {
        public CommentObservation { warnings = immutable(warnings); }
    }

    public record Media(String key, MessageRef message, String source, String type,
                        String fileName, long size, String caption) {
        @Override public String toString() { return "Media[key=" + key + ", type=" + type + "]"; }
    }

    public record Post(String key, String accountId, String channelId, String channelTitle,
                       String mainMessageId, String title, String text, String albumId,
                       List<MessageRef> mainMessages, List<Media> mainMedia,
                       String discussionChatId, String discussionRootMessageId,
                       List<MessageRef> discussionRoots, boolean canReadComments,
                       String coverage, List<String> warnings, AlbumEvidence albumEvidence, String mainCoverage) {
        public Post {
            mainMessages = immutable(mainMessages);
            mainMedia = immutable(mainMedia);
            discussionRoots = immutable(discussionRoots);
            warnings = immutable(warnings);
            mainCoverage = mainCoverage == null ? "PARTIAL" : mainCoverage;
        }
        public Post(String key, String accountId, String channelId, String channelTitle, String mainMessageId,
                    String title, String text, String albumId, List<MessageRef> mainMessages, List<Media> mainMedia,
                    String discussionChatId, String discussionRootMessageId, List<MessageRef> discussionRoots,
                    boolean canReadComments, String coverage, List<String> warnings) {
            this(key, accountId, channelId, channelTitle, mainMessageId, title, text, albumId, mainMessages, mainMedia,
                    discussionChatId, discussionRootMessageId, discussionRoots, canReadComments, coverage, warnings, null, "PARTIAL");
        }
        public Post withCoverage(String value) {
            return new Post(key, accountId, channelId, channelTitle, mainMessageId, title, text, albumId, mainMessages,
                    mainMedia, discussionChatId, discussionRootMessageId, discussionRoots, canReadComments, value, warnings, albumEvidence, mainCoverage);
        }
        @Override public String toString() { return "Post[key=" + key + ", coverage=" + coverage + "]"; }
    }

    public record Page(String postKey, List<Media> media, List<MessageRef> messages, int messageCount,
                       String nextCursor, boolean exhausted, String coverage, List<String> warnings,
                       String commentCoverage, List<String> commentWarnings,
                       List<CommentObservation> observations, List<String> scanWarnings) {
        public Page {
            media = immutable(media);
            messages = immutable(messages);
            warnings = immutable(warnings);
            commentCoverage = commentCoverage == null ? coverage : commentCoverage;
            commentWarnings = immutable(commentWarnings);
            observations = immutable(observations);
            scanWarnings = immutable(scanWarnings);
        }
        public Page(String postKey, List<Media> media, List<MessageRef> messages, int messageCount,
                    String nextCursor, boolean exhausted, String coverage, List<String> warnings,
                    String commentCoverage, List<String> commentWarnings) {
            this(postKey, media, messages, messageCount, nextCursor, exhausted, coverage, warnings,
                    commentCoverage, commentWarnings, List.of(), commentWarnings);
        }
        public Page(String postKey, List<Media> media, List<MessageRef> messages, int messageCount,
                    String nextCursor, boolean exhausted, String coverage, List<String> warnings) {
            this(postKey, media, messages, messageCount, nextCursor, exhausted, coverage, warnings, coverage, warnings);
        }
    }

    public record Detail(Post post, List<Media> commentMedia, String nextCursor,
                         boolean exhausted, String coverage, List<String> warnings,
                         String mainCoverage, String commentCoverage, long indexedCommentMessages, long indexedCommentMedia,
                         List<String> commentWarnings, List<String> commentScanWarnings) {
        public Detail {
            commentMedia = immutable(commentMedia);
            warnings = immutable(warnings);
            commentWarnings = immutable(commentWarnings);
            commentScanWarnings = immutable(commentScanWarnings);
        }
        public Detail(Post post, List<Media> commentMedia, String nextCursor, boolean exhausted, String coverage,
                      List<String> warnings, String mainCoverage, String commentCoverage,
                      long indexedCommentMessages, long indexedCommentMedia, List<String> commentWarnings) {
            this(post, commentMedia, nextCursor, exhausted, coverage, warnings, mainCoverage, commentCoverage,
                    indexedCommentMessages, indexedCommentMedia, commentWarnings, commentWarnings);
        }
        public Detail(Post post, List<Media> commentMedia, String nextCursor, boolean exhausted, String coverage, List<String> warnings) {
            this(post, commentMedia, nextCursor, exhausted, coverage, warnings, post.mainCoverage(), coverage, -1, -1, warnings);
        }
    }

    private static <T> List<T> immutable(List<T> values) { return values == null ? List.of() : List.copyOf(values); }
}
