package io.github.telegramenhance.transfer;
import java.util.List;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
public final class UploadRetryModels {
    private UploadRetryModels(){}
    public record Failure(String failedId,long nativeSize,Evidence evidence){}
    public record Inspection(String state,String reason,String failedId){}
    public record Candidate(String itemId,String temporaryId,String failedId,Staged staged){}
    public record Attempt(String id,String taskId,long groupId,String itemId,String operationId,String checkId,String priorTemporaryId,String failedId,String newTemporaryId,String ownerRun,String state){}
    public record Row(String itemId,String fileName,String state,String reason){}
    public record Check(String id,String taskId,String groupId,long expiresAt,int confirmed,int retryable,int pending,int unresolved,List<Row> items){public Check{items=List.copyOf(items);}}
    public record Retry(String operationId,String checkId){}
}
