package io.github.telegramenhance.library;

import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.library.ResourceModels.Post;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local catalog views. Counts describe indexed metadata, never whole Telegram channels. */
public final class LibraryModels {
    private LibraryModels() {}
    public static final String INDEXED_ONLY = "INDEXED_ONLY";
    public record Channel(String id, String title, long indexedPosts) {}
    public record CatalogEntry(Post post, List<String> tags, long indexedCommentMessages,
                               long indexedCommentMedia, long updatedAt, Long publishedAt,
                               Map<String, Long> mediaTypeCounts) {
        public CatalogEntry { tags = List.copyOf(tags); mediaTypeCounts = Map.copyOf(mediaTypeCounts); }
        public CatalogEntry(Post post, List<String> tags, long indexedCommentMessages, long indexedCommentMedia, long updatedAt) {
            this(post, tags, indexedCommentMessages, indexedCommentMedia, updatedAt, post.publishedAt(), emptyMediaTypeCounts());
        }
    }
    public record TagFacet(String tag, long count) {}
    public record Facets(List<TagFacet> tags) { public Facets { tags = List.copyOf(tags); } }
    public record CatalogPage(List<CatalogEntry> items, int limit, int offset, long total,
                              long indexedPosts, long indexedChannels, boolean hasMore, String scope, Facets facets) {
        public CatalogPage { items = List.copyOf(items); }
        public CatalogPage(List<CatalogEntry> items, int limit, int offset, long total, long indexedPosts,
                           long indexedChannels, boolean hasMore, String scope) {
            this(items, limit, offset, total, indexedPosts, indexedChannels, hasMore, scope, new Facets(List.of()));
        }
    }
    public record MediaPage(List<Media> items, int limit, int offset, long total, boolean hasMore,
                            long mainMediaCount, long commentMediaCount, String scope) {
        public MediaPage { items = List.copyOf(items); }
    }
    static Map<String, Long> emptyMediaTypeCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String type : List.of("VIDEO", "PHOTO", "AUDIO", "DOCUMENT", "OTHER")) result.put(type, 0L);
        return result;
    }
}
