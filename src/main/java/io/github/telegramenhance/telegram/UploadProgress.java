package io.github.telegramenhance.telegram;

import io.github.telegramenhance.transfer.UploadTaskModels.*;
import it.tdlight.jni.TdApi;
import java.util.*;
import java.util.function.LongSupplier;

/** Ephemeral TDLib byte observations, never used as delivery evidence or resend authority. */
final class UploadProgress {
    private static final int MAX_FILES=4096;
    private static final long FRESH_MS=4000,WINDOW_MS=10_000;
    private final LongSupplier clock;
    private String account;private long generation;
    private record Point(long at,long bytes) { }
    private static final class Sample {
        final int fileId;final String item;long total,bytes,observedAt;boolean active;
        final ArrayDeque<Point> points=new ArrayDeque<>();
        Sample(int fileId,String item){this.fileId=fileId;this.item=item;}
    }
    private final LinkedHashMap<String,Sample> items=new LinkedHashMap<>();
    UploadProgress(){this(System::currentTimeMillis);}
    UploadProgress(LongSupplier clock){this.clock=clock;}
    private void context(String account,long generation){if(!Objects.equals(this.account,account)||this.generation!=generation){items.clear();this.account=account;this.generation=generation;}}
    synchronized void bind(String account,long generation,Item item,TdApi.File file){
        if(this.account!=null&&generation<this.generation)return;
        context(account,generation);if(file==null||file.id<=0||file.remote==null)return;
        // Identity is established by the verified initial/pending message, never by a file path in an update.
        var sample=new Sample(file.id,item.id());observe(sample,file,clock.getAsLong());if(sample.total<1)return;items.put(item.id(),sample);
        while(items.size()>MAX_FILES)items.remove(items.keySet().iterator().next());
    }
    synchronized void update(String account,long generation,TdApi.File file){
        // An old session callback must never clear or update a newer session's observations.
        if(!Objects.equals(this.account,account)||this.generation!=generation||file==null||file.remote==null)return;
        long now=clock.getAsLong();for(var sample:items.values())if(sample.fileId==file.id)observe(sample,file,now);
    }
    private static void observe(Sample sample,TdApi.File file,long now){
        long total=file.size>0?file.size:file.expectedSize;if(total<1||file.remote.uploadedSize<0)return;
        long uploaded=Math.min(total,file.remote.uploadedSize);
        if(uploaded<sample.bytes||total!=sample.total)sample.points.clear();
        sample.total=total;sample.bytes=uploaded;sample.observedAt=now;sample.active=file.remote.isUploadingActive;
        if(sample.points.isEmpty()||sample.points.getLast().bytes()!=uploaded)sample.points.addLast(new Point(now,uploaded));
        while(sample.points.size()>256)sample.points.removeFirst();
        while(sample.points.size()>1&&now-sample.points.getFirst().at()>WINDOW_MS)sample.points.removeFirst();
    }
    synchronized Progress snapshot(Task task,long generation){
        if(this.account!=null&&generation<this.generation)throw new IllegalArgumentException("旧会话的上传进度已失效，请刷新当前会话");
        context(task.accountId(),generation);long now=clock.getAsLong(),total=0,uploaded=0,observedAt=0,rate=0;int observed=0,count=0;boolean fresh=false;
        for(var group:task.groups())for(var item:group.items()){
            count++;var sample=items.get(item.id());if(sample!=null&&sample.total<1)sample=null;long size=sample==null?item.file().size():sample.total;
            total=Math.addExact(total,size);
            if(item.formalId()!=null){uploaded=Math.addExact(uploaded,size);observed++;}
            else if(sample!=null){
                uploaded=Math.addExact(uploaded,sample.bytes);observed++;observedAt=Math.max(observedAt,sample.observedAt);
                if(sample.active&&sample.bytes<sample.total&&now-sample.observedAt<=FRESH_MS&&sample.points.size()>1){
                    var first=sample.points.getFirst();var last=sample.points.getLast();long elapsed=last.at()-first.at();
                    if(elapsed>=500&&now-last.at()<=FRESH_MS&&last.bytes()>first.bytes()){rate=Math.addExact(rate,(long)((last.bytes()-first.bytes())*1000.0/elapsed));fresh=true;}
                }
            }
        }
        Long speed=task.state().equals("RUNNING")&&fresh&&rate>0?rate:null;
        Long eta=speed!=null&&observed==count&&total>uploaded?(long)Math.ceil((total-uploaded)/(double)speed):null;
        return new Progress(total,uploaded,speed,eta,observedAt==0?null:observedAt,observed,count);
    }
}
