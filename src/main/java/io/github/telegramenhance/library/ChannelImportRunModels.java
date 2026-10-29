package io.github.telegramenhance.library;

/** Public progress only; pending message metadata is deliberately not part of this contract. */
public final class ChannelImportRunModels {
    private ChannelImportRunModels() { }

    public record RunView(String channelId, String runId, long revision, String status,
                          long processed, long created, long updated, long incomplete,
                          boolean exhausted, String warning) {
        @Override public String toString() {
            return "RunView[revision=" + revision + ", status=" + status + ", processed=" + processed + "]";
        }
    }
}
