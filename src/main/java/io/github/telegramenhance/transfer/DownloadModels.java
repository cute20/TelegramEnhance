package io.github.telegramenhance.transfer;

import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.library.ResourceModels.Post;
import java.util.List;

public final class DownloadModels {
    public static final String HISTORY_SKIP = "HISTORY_SKIP_V1", KEEP_DUPLICATES = "KEEP_DUPLICATES_V2";
    private DownloadModels() { }
    public record BotFileReference(int fileId, String remoteId) {
        @Override public String toString() { return "BotFileReference[redacted]"; }
    }
    public record FrozenFile(Media media, String uniqueId, long size, String fileName, BotFileReference botReference) {
        public FrozenFile(Media media, String uniqueId, long size, String fileName) { this(media, uniqueId, size, fileName, null); }
        @Override public String toString() { return "FrozenFile[type=" + media.type() + ", size=" + size + "]"; }
    }
    public record Item(String id, int ordinal, FrozenFile file, String relativeFile, String token,
                       String state, long downloadedBytes, long writtenBytes, String sha256, String error, History history, OutputPlan plan) {
        @Override public String toString() { return "DownloadItem[state=" + state + "]"; }
    }
    public record Task(String id, String accountId, String operationId, String fingerprint, Post post,
                       String targetRoot, String relativeDirectory, String state, boolean pauseRequested,
                       String error, long createdAt, long updatedAt, List<Item> items, String duplicatePolicy) {
        public Task { items = List.copyOf(items); }
        @Override public String toString() { return "DownloadTask[state=" + state + ", items=" + items.size() + "]"; }
    }
    public record History(String id, String taskId, String path, String sha256, long completedAt) { }
    public record Duplicate(String itemId, String reason, String sourceLink, String path) { }
    public record OutputPlan(String relativePath, String sha256, Duplicate duplicate, String manifestRelative, long plannedAt) { }
    public record ItemView(String id, String fileName, String type, String state, long size,
                           long downloadedBytes, long writtenBytes, String outputPath, String error, History history,
                           String sourceLink, Duplicate duplicate, String manifestPath) { }
    public record TaskView(String id, String title, String channelTitle, String state, boolean pauseRequested,
                           String targetDirectory, String scope, int total, long completed, long skipped, long totalBytes,
                           String error, long createdAt, long updatedAt, List<ItemView> items, String duplicatePolicy, long duplicates, String duplicateDirectory, Automation automation, Retry retry) {
        public TaskView(String id,String title,String channelTitle,String state,boolean pauseRequested,String targetDirectory,String scope,int total,long completed,long skipped,long totalBytes,
                String error,long createdAt,long updatedAt,List<ItemView> items,String duplicatePolicy,long duplicates,String duplicateDirectory,Automation automation){
            this(id,title,channelTitle,state,pauseRequested,targetDirectory,scope,total,completed,skipped,totalBytes,error,createdAt,updatedAt,items,duplicatePolicy,duplicates,duplicateDirectory,automation,null);
        }
        public TaskView { items = List.copyOf(items); }
    }
    public record Retry(int attempt,long nextAttemptAt) { }
    public record Automation(String ruleId,long ruleVersion,String ruleName,boolean manual,boolean held,boolean enabled,long currentVersion,io.github.telegramenhance.rules.RuleModels.Window window) { }
    public record CacheOwner(String accountId, String uniqueId, int fileId) { }
}
