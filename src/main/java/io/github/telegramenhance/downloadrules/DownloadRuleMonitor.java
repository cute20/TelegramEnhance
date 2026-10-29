package io.github.telegramenhance.downloadrules;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

/** Bounded producer; no second original-file worker and no work while the app is closed. */
@Service
public final class DownloadRuleMonitor implements AutoCloseable,org.springframework.context.ApplicationListener<org.springframework.context.event.ContextClosedEvent> {
    private final DownloadRuleService rules;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("download-rule-monitor").factory());
    private volatile boolean closed;
    public DownloadRuleMonitor(DownloadRuleService rules){this.rules=rules;worker.scheduleWithFixedDelay(this::tick,2,2,TimeUnit.SECONDS);}
    private void tick(){if(closed)return;try{rules.monitorOnce(()->{if(closed||Thread.currentThread().isInterrupted())throw new IllegalArgumentException("持续下载已停止。");}).get(110,TimeUnit.SECONDS);}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();}catch(RuntimeException|ExecutionException|TimeoutException ignored){/* Durable cursors and claims, rather than replaying an uncertain create. */}}
    @Override public void onApplicationEvent(org.springframework.context.event.ContextClosedEvent event){closed=true;rules.stop();worker.shutdownNow();}
    @Override @PreDestroy public void close(){closed=true;rules.stop();worker.shutdownNow();try{worker.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}
}
