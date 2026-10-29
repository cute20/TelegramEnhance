package io.github.telegramenhance.transfer;

import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.transfer.DownloadModels.FrozenFile;
import java.util.List;

public final class DownloadBatchModels {
    private DownloadBatchModels(){}
    public record Input(String scanId,long revision,List<String> postKeys,List<String> types,Boolean includeMain,String targetDirectory){
        @Override public String toString(){return "DownloadBatchInput[redacted]";}
    }
    public record Position(long revision){}
    public record Create(String operationId,String previewId,long revision){}
    public record PostSummary(String key,String title,String state,long commentMessages,int mainMedia,int commentMedia,long bytes){}
    public record View(String id,String accountId,long revision,String channelTitle,List<String> tags,List<String> keywords,
            List<String> types,boolean includeMain,String targetDirectory,List<PostSummary> posts,int total,long totalBytes,
            boolean exhausted,boolean canContinue,boolean busy,String error,List<String> warnings,long createdAt,long expiresAt){
        public View{tags=List.copyOf(tags);keywords=List.copyOf(keywords);types=List.copyOf(types);posts=List.copyOf(posts);warnings=List.copyOf(warnings);}
        @Override public String toString(){return "DownloadBatchPreview[posts="+posts.size()+", media="+total+"]";}
    }
    public record Job(Post post,List<FrozenFile> files){public Job{files=List.copyOf(files);}}
    public record Snapshot(String previewId,long revision,String channelTitle,List<String> tags,List<String> keywords,
            List<String> types,boolean includeMain,String targetDirectory,int postCount,int total,long totalBytes){
        public Snapshot{tags=List.copyOf(tags);keywords=List.copyOf(keywords);types=List.copyOf(types);}
    }
    public record Receipt(String id,Snapshot snapshot,List<String> taskIds,long createdAt){public Receipt{taskIds=List.copyOf(taskIds);}}
    public record Lookup(Receipt batch){}
    public record TaskSummary(String id,String title,String state,boolean pauseRequested,int total,long completed,long skipped,
            long duplicates,long totalBytes,long downloadedBytes,long writtenBytes,long updatedAt,String error){
        @Override public String toString(){return "DownloadBatchTask[state="+state+", total="+total+"]";}
    }
    public record BatchStatus(String id,Snapshot snapshot,String state,int totalTasks,int completedTasks,int runningTasks,
            int queuedTasks,int pausedTasks,int failedTasks,long completed,long skipped,long duplicates,long downloadedBytes,
            long writtenBytes,long createdAt,long updatedAt,boolean canPause,boolean canResume,List<TaskSummary> tasks){
        public BatchStatus{tasks=List.copyOf(tasks);}
        @Override public String toString(){return "DownloadBatchStatus[state="+state+", tasks="+totalTasks+"]";}
    }
}
