package io.github.telegramenhance.transfer;

import io.github.telegramenhance.transfer.UploadPreviewModels.FileVersion;
import io.github.telegramenhance.transfer.UploadPreviewService.Prepared;
import java.util.List;

public final class UploadTaskModels {
    private UploadTaskModels() { }
    public record Staged(String path, long size, String sha256, String identity, int width, int height) { }
    public record Item(String id, int ordinal, FileVersion file, String caption, String state, String temporaryId,
                       String formalId, String remoteUniqueId, Staged staged, String archiveRelative, String archivePhase, String archiveIdentity, String error) { }
    public record Group(long id, int number, String mediaType, String mode, String state, String ownerRun, Long startedAt, String error, List<Item> items) { public Group { items = List.copyOf(items); } }
    public record Task(String id, String accountId, String operationId, String fingerprint, Prepared prepared, String state, boolean stopRequested, String error, long createdAt, long updatedAt, List<Group> groups) { public Task { groups = List.copyOf(groups); } }
    /** Minimal native evidence, without local paths or authorization data. */
    public record Evidence(String oldId, String formalId, String chatId, String threadId, String replyId, String albumId,
                           String mediaType, String caption, String uniqueId, boolean outgoing, boolean success, int date) { }
    public record Initial(String id, int sendingId, boolean pending, Evidence evidence) { }
    public record ItemView(String id, String fileName, String mediaType, long size, String state, String formalMessageId, String archivePath, String error) { }
    public record GroupView(String id, int number, String mediaType, String state, String error, boolean canAcceptDocument, boolean hasRetries, List<ItemView> items) { }
    public record View(String id, String state, String channelTitle, String postTitle, String postLink, String discussionTitle,
                       String sourceDirectory, String archiveDirectory, boolean stopRequested, int total, long confirmed, long archived,
                       String error, long createdAt, long updatedAt, List<GroupView> groups,io.github.telegramenhance.rules.RuleUploadPolicy.Info automation) { }
}
