package io.github.telegramenhance.library;

import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.library.ResourceModels.Post;
import java.util.List;

/** Local catalog views. Counts describe indexed metadata, never whole Telegram channels. */
public final class LibraryModels {
    private LibraryModels() {}
    public static final String INDEXED_ONLY = "INDEXED_ONLY";
    public record Channel(String id, String title, long indexedPosts) {}
    public record CatalogEntry(Post post, List<String> tags, long indexedCommentMessages,
                               long indexedCommentMedia, long updatedAt) {
        public CatalogEntry { tags = List.copyOf(tags); }
    }
    public record CatalogPage(List<CatalogEntry> items, int limit, int offset, long total,
                              long indexedPosts, long indexedChannels, boolean hasMore, String scope) {
        public CatalogPage { items = List.copyOf(items); }
    }
    public record MediaPage(List<Media> items, int limit, int offset, long total, boolean hasMore,
                            long mainMediaCount, long commentMediaCount, String scope) {
        public MediaPage { items = List.copyOf(items); }
    }
}
