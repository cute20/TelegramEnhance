package io.github.telegramenhance.downloadrules;

import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import io.github.telegramenhance.library.ChannelImportModels.ManagedChannel;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.rules.RuleModels.Window;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Durable download configuration and bounded scan state; Telegram identifiers remain strings. */
public final class DownloadRuleModels {
    private DownloadRuleModels() { }
    public record Input(String name,String source,String tags,String keywords,List<String> types,boolean includeMain,String targetDirectory,Window window,
            @JsonInclude(value=JsonInclude.Include.CUSTOM,valueFilter=ChannelMode.class) String sourceMode,
            @JsonInclude(JsonInclude.Include.NON_NULL) String postKey,
            @JsonInclude(JsonInclude.Include.NON_NULL) String mainMessageId) {
        public Input { types=types==null?List.of():List.copyOf(types);sourceMode=sourceMode==null||sourceMode.isBlank()?"CHANNEL":sourceMode; }
        public Input(String name,String source,String tags,String keywords,List<String> types,boolean includeMain,String targetDirectory,Window window){this(name,source,tags,keywords,types,includeMain,targetDirectory,window,"CHANNEL",null,null);}
        public Input(String name,String source,String tags,String keywords,List<String> types,boolean includeMain,String targetDirectory,Window window,String sourceMode){this(name,source,tags,keywords,types,includeMain,targetDirectory,window,sourceMode,null,null);}
        public boolean postSource(){return "POST".equals(sourceMode);}
        @Override public String toString(){return "DownloadRuleInput[redacted]";}
    }
    /** Omit the legacy default so old immutable configuration/task JSON still compares byte for byte. */
    public static final class ChannelMode {
        @Override public boolean equals(Object value){return value==null||"CHANNEL".equals(value);}
        @Override public int hashCode(){return 0;}
    }
    public record Rule(String id,String accountId,long version,long revision,boolean enabled,long nextScanAt,long createdAt,long updatedAt,Input input,String channelId,String channelTitle) { }
    public record Version(long version,long createdAt,Input input,String channelId,String channelTitle) { }
    public record Scan(String ruleId,long version,long revision,String phase,String cursor,HistoryPage pending,int offset,boolean historyDone,long nextHeadAt,long scanned,String error,long updatedAt,long turn) {
        public Scan(String ruleId,long version,long revision,String phase,String cursor,HistoryPage pending,int offset,boolean historyDone,long nextHeadAt,long scanned,String error,long updatedAt){this(ruleId,version,revision,phase,cursor,pending,offset,historyDone,nextHeadAt,scanned,error,updatedAt,0);}
    }
    public record PostState(String ruleId,long version,String key,String messageId,Post post,String state,String cursor,long pages,boolean matched,long nextReviewAt,String error,long revision) { }
    public record Job(String taskId,long ruleVersion,String state,int total,long completed,String error,boolean manual,boolean held) { }
    public record Summary(boolean historyDone,long scanned,long matched,long unresolved,long commentPages,long media,long completed,long nextScanAt,String scanError,Job job,long errors) { }
    public record View(Rule rule,Summary summary,String state,boolean busy,long nextAllowedAt) { }
    public record Listing(List<View> items,int intervalSeconds) { public Listing {items=List.copyOf(items);} }
    public record Commit(PostState post,Job job,int createdMedia) { }
}
