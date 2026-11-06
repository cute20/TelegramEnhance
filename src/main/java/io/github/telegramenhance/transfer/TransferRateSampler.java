package io.github.telegramenhance.transfer;

import java.util.*;
import java.util.function.LongSupplier;

/** Bounded local observation of native download bytes, separate from target writes and receipts. */
final class TransferRateSampler {
    record Rate(Long bytesPerSecond,Long etaSeconds) { }
    private record Sample(long bytes,long at) { }
    private static final Rate UNKNOWN=new Rate(null,null);
    private final LongSupplier clock;private final LinkedHashMap<String,Sample> samples=new LinkedHashMap<>();
    private String account;private long generation;
    TransferRateSampler(){this(System::currentTimeMillis);}
    TransferRateSampler(LongSupplier clock){this.clock=clock;}
    synchronized Rate observe(String account,long generation,String key,long bytes,Long goal,boolean downloading){
        if(this.account!=null&&generation<this.generation)return UNKNOWN;
        if(!Objects.equals(this.account,account)||this.generation!=generation){samples.clear();this.account=account;this.generation=generation;}
        if(!downloading){samples.remove(key);return UNKNOWN;}
        long now=clock.getAsLong();var before=samples.get(key);
        if(before==null||bytes<before.bytes()||now<before.at()||now-before.at()>10_000){put(key,new Sample(bytes,now));return UNKNOWN;}
        long elapsed=now-before.at();if(elapsed<500)return UNKNOWN;
        put(key,new Sample(bytes,now));if(bytes==before.bytes())return UNKNOWN;
        long rate=(long)((bytes-before.bytes())*1000.0/elapsed);if(rate<1)return UNKNOWN;
        return new Rate(rate,goal!=null&&goal>bytes?(long)Math.ceil((goal-bytes)/(double)rate):null);
    }
    private void put(String key,Sample value){samples.put(key,value);while(samples.size()>1024)samples.remove(samples.keySet().iterator().next());}
}
