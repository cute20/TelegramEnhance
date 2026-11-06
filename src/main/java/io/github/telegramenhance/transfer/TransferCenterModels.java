package io.github.telegramenhance.transfer;

import java.util.List;

/** Account-scoped projections. Completion still comes exclusively from existing transfer receipts. */
public final class TransferCenterModels {
    private TransferCenterModels() { }
    public record Counts(long active,long attention,long waiting,long completed) { }
    public record Row(String id,String kind,String category,String title,String subtitle,String state,String statusGroup,
                      long createdAt,long updatedAt,long totalFiles,long resolvedFiles,long totalBytes,long transferredBytes,
                      long writtenBytes,Long speedBytesPerSecond,Long etaSeconds,String phase,String detailId,List<String> actions,
                      String error,String targetDirectory,long skippedFiles,DownloadModels.Retry retry) {
        public Row { actions=List.copyOf(actions); }
        public Row(String id,String kind,String category,String title,String subtitle,String state,String statusGroup,
                   long createdAt,long updatedAt,long totalFiles,long resolvedFiles,long totalBytes,long transferredBytes,
                   long writtenBytes,Long speedBytesPerSecond,Long etaSeconds,String phase,String detailId,List<String> actions,
                   String error,String targetDirectory,long skippedFiles) {
            this(id,kind,category,title,subtitle,state,statusGroup,createdAt,updatedAt,totalFiles,resolvedFiles,totalBytes,
                 transferredBytes,writtenBytes,speedBytesPerSecond,etaSeconds,phase,detailId,actions,error,targetDirectory,skippedFiles,null);
        }
    }
    public record Page(List<Row> items,long total,Counts counts,List<String> warnings) {
        public Page { items=List.copyOf(items);warnings=List.copyOf(warnings); }
    }
    public record Action(String category,String id,String action) { }
    public record ActionResult(Object item,Object check,String warning) { }
    public record DeletionReceipt(String category,String id,boolean deleted) { }
    public record PauseRequest(String operationId) { }
    public record Outcome(String category,String id,String action,String message) { }
    public record PauseReceipt(String operationId,String state,List<Outcome> accepted,List<Outcome> failed,
                               List<Outcome> pending,List<String> warnings,long createdAt,long updatedAt) {
        public PauseReceipt {accepted=List.copyOf(accepted);failed=List.copyOf(failed);pending=List.copyOf(pending);warnings=List.copyOf(warnings);}
    }
}
