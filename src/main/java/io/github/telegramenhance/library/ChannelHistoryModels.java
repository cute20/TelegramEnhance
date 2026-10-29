package io.github.telegramenhance.library;

import io.github.telegramenhance.library.ChannelImportModels.Candidate;
import io.github.telegramenhance.library.ChannelImportModels.ManagedChannel;
import java.util.List;

/** One bounded history page; only a confirmed empty boundary sets exhausted. */
public final class ChannelHistoryModels {
    private ChannelHistoryModels() { }
    public record HistoryPage(ManagedChannel channel, List<Candidate> items, String scope, int limit,
            String nextCursor, boolean exhausted, boolean hasMore, List<String> warnings) {
        public HistoryPage { items = List.copyOf(items); warnings = List.copyOf(warnings); }
        @Override public String toString() { return "HistoryPage[count=" + items.size() + ", exhausted=" + exhausted + ", hasMore=" + hasMore + ", warnings=" + warnings.size() + "]"; }
    }
}
