package io.github.telegramenhance.rules;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

/** Serial rule producer. Only explicitly enabled AUTO_UPLOAD rules may produce bound upload tasks. */
@Service
public final class RuleMonitor implements AutoCloseable,org.springframework.context.ApplicationListener<org.springframework.context.event.ContextClosedEvent> {
    private final RuleService rules;private volatile boolean closed;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("rule-observer").factory());
    public RuleMonitor(RuleService rules){this.rules=rules;worker.scheduleWithFixedDelay(this::tick,2,2,TimeUnit.SECONDS);}
    private void tick(){if(closed)return;try{rules.monitorOnce(()->{if(closed||Thread.currentThread().isInterrupted())throw new IllegalArgumentException("规则调度已停止");}).get(160,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}catch(RuntimeException|ExecutionException|TimeoutException unavailable){/* Paused/disconnected/changed context never replays an uncertain native send. */}}
    @Override public void onApplicationEvent(org.springframework.context.event.ContextClosedEvent event){closed=true;worker.shutdownNow();}
    @Override @PreDestroy public void close(){closed=true;worker.shutdownNow();try{worker.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}
}
