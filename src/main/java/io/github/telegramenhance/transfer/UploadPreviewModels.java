package io.github.telegramenhance.transfer;

import io.github.telegramenhance.library.ResourceModels.Post;
import java.util.List;

/** Read-only preparation DTOs; execution persists a separate frozen copy. */
public final class UploadPreviewModels {
    private UploadPreviewModels() { }
    public static final int DEFAULT_ALBUM_SIZE = 10;
    public static int normalizeAlbumSize(Integer value) {
        int size = value == null ? DEFAULT_ALBUM_SIZE : value;
        if (size < 1 || size > 10) throw new IllegalArgumentException("每组媒体数量须为1至10，选择1时逐条发送");
        return size;
    }
    public record Input(String link, String sourceDirectory, String archiveDirectory, Boolean recursive, String captionMode, Integer albumSize) {
        public Input { albumSize = normalizeAlbumSize(albumSize); }
        public Input(String link, String sourceDirectory, String archiveDirectory, Boolean recursive, String captionMode) {
            this(link, sourceDirectory, archiveDirectory, recursive, captionMode, DEFAULT_ALBUM_SIZE);
        }
        @Override public String toString() { return "UploadPreviewInput[redacted]"; }
    }
    public record Target(Post post, String discussionTitle, String permission, int slowModeSeconds, long checkedAt) { }
    public record FileVersion(String relativePath, String mediaType, long size, long modifiedMillis, String identity, String sha256) {
        @Override public String toString() { return "UploadFileVersion[size=" + size + "]"; }
    }
    public record Scan(String sourceDirectory, String archiveDirectory, boolean recursive, List<FileVersion> observed,
                       List<FileVersion> files, int otherFiles, int deferredFiles, int linkedEntries, int duplicateFiles) {
        public Scan { observed = List.copyOf(observed); files = List.copyOf(files); }
    }
    public record Member(String relativePath, String fileName, String mediaType, long size, String caption) { }
    public record Group(int number, String mediaType, String mode, List<Member> files) { public Group { files = List.copyOf(files); } }
    public record View(String id, String accountId, String state, String sourceDirectory, String archiveDirectory,
                       boolean recursive, String captionMode, int albumSize, String channelTitle, String postTitle, String postText,
                       String postLink, String discussionTitle, String permission, int slowModeSeconds,
                       int fileCount, long totalBytes, List<Group> groups, int otherFiles, int deferredFiles,
                       int linkedEntries, int duplicateFiles, List<String> warnings, long targetCheckedAt, long createdAt, long expiresAt) {
        public View { groups = List.copyOf(groups); warnings = List.copyOf(warnings); }
    }
}
