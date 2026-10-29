package io.github.telegramenhance.transfer;

import io.github.telegramenhance.library.ResourceModels.Page;
import io.github.telegramenhance.library.ResourceModels.Post;
import java.util.List;

/** One metadata-only preview page. This is neither a saved task nor a library index. */
public record DownloadSourcePreview(String id, Post post, Page comments, int pageNumber,
                                    long observedMessages, boolean exhausted, boolean canContinue,
                                    List<String> warnings) {
    public DownloadSourcePreview { warnings = List.copyOf(warnings); }
    @Override public String toString() { return "DownloadSourcePreview[metadata only]"; }
}
