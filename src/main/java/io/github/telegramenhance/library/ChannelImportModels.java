package io.github.telegramenhance.library;

import io.github.telegramenhance.library.ResourceModels.Post;
import java.util.List;

/** Bounded metadata previews; a preview does not declare an entire channel indexed. */
public final class ChannelImportModels {
    private ChannelImportModels() { }

    public record ManagedChannel(String id, String title) {
        @Override public String toString() { return "ManagedChannel[id=" + id + "]"; }
    }
    public record ManagedChannels(List<ManagedChannel> items, String scope, boolean partial, List<String> warnings) {
        public ManagedChannels { items = copy(items); warnings = copy(warnings); }
        @Override public String toString() { return "ManagedChannels[count=" + items.size() + ", scope=" + scope + ", partial=" + partial + ", warnings=" + warnings.size() + "]"; }
    }
    public record Candidate(String messageId, String albumId, String title) {
        @Override public String toString() { return "Candidate[messageId=" + messageId + ", albumId=" + albumId + "]"; }
    }
    public record Preview(ManagedChannel channel, List<Candidate> items, String scope, int limit, List<String> warnings) {
        public Preview { items = copy(items); warnings = copy(warnings); }
        @Override public String toString() { return "Preview[count=" + items.size() + ", scope=" + scope + ", limit=" + limit + ", warnings=" + warnings.size() + "]"; }
    }
    public record ImportResult(Post post, boolean created) {
        @Override public String toString() { return "ImportResult[created=" + created + "]"; }
    }

    private static <T> List<T> copy(List<T> values) { return values == null ? List.of() : List.copyOf(values); }
}
