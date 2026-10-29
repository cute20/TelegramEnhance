package io.github.telegramenhance.rules;

import io.github.telegramenhance.transfer.UploadPreviewModels;
import java.time.*;
import java.util.List;

public final class RuleModels {
    private RuleModels(){}
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public record Window(String mode,String start,String end,String timezone){
        public Window {
            if(!"Asia/Shanghai".equals(timezone)||!("ALL_DAY".equals(mode)||"WINDOW".equals(mode)))throw new IllegalArgumentException("请选择全天或指定时段，时区为Asia/Shanghai");
            if(mode.equals("ALL_DAY")){start=null;end=null;}
            else if(start==null||end==null||!start.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")||!end.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")||start.equals(end))throw new IllegalArgumentException("请输入不同的开始和结束时间，例如22:00至08:00");
        }
        public boolean allows(Instant instant){
            if(mode.equals("ALL_DAY"))return true;
            var now=instant.atZone(ZONE).toLocalTime();var from=LocalTime.parse(start);var to=LocalTime.parse(end);
            return from.isBefore(to)?!now.isBefore(from)&&now.isBefore(to):!now.isBefore(from)||now.isBefore(to);
        }
        public long nextAllowed(Instant instant){
            if(allows(instant))return instant.toEpochMilli();var now=instant.atZone(ZONE);var next=now.toLocalDate().atTime(LocalTime.parse(start)).atZone(ZONE);
            if(!next.isAfter(now))next=next.plusDays(1);return next.toInstant().toEpochMilli();
        }
    }
    public record Input(String name,String link,String sourceDirectory,String archiveDirectory,boolean recursive,String captionMode,Window window,String executionMode){
        public Input {if(executionMode==null)executionMode="OBSERVE_ONLY";if(!executionMode.equals("OBSERVE_ONLY")&&!executionMode.equals("AUTO_UPLOAD"))throw new IllegalArgumentException("请选择仅观察或自动上传");}
        public Input(String name,String link,String sourceDirectory,String archiveDirectory,boolean recursive,String captionMode,Window window){this(name,link,sourceDirectory,archiveDirectory,recursive,captionMode,window,"OBSERVE_ONLY");}
        public UploadPreviewModels.Input uploadInput(){return new UploadPreviewModels.Input(link,sourceDirectory,archiveDirectory,recursive,captionMode);}
    }
    public record Rule(String id,String accountId,long version,long revision,boolean enabled,long nextScanAt,long createdAt,long updatedAt,Input input,Observation observation){}
    public record UploadOutcome(String state,String message,String taskId,int claimed,long checkedAt){}
    public record Job(String taskId,long ruleVersion,String state,int total,long confirmed,long archived,boolean manualTakeover,String error){}
    public record Observation(long version,long revision,long checkedAt,long scanAt,String outcome,UploadPreviewModels.Scan scan,int added,int changed,int unobserved,String error,UploadOutcome upload){
        public Observation(long version,long revision,long checkedAt,long scanAt,String outcome,UploadPreviewModels.Scan scan,int added,int changed,int unobserved,String error){this(version,revision,checkedAt,scanAt,outcome,scan,added,changed,unobserved,error,null);}
    }
    public record View(Rule rule,String mode,String state,boolean scanning,long nextAllowedAt,Job job){}
    public record Listing(List<View> items,String mode,int intervalSeconds){}
    public record Version(long version,long createdAt,Input input){}
    public record Preview(String ruleId,long version,long revision,UploadPreviewModels.View preview){}
}
