package io.github.telegramenhance.transfer;

import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.rules.RuleUploadPolicy;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

@Service
public final class UploadEngine implements AutoCloseable,org.springframework.context.ApplicationListener<org.springframework.context.event.ContextClosedEvent> {
    private final UploadRepository repository;private final UploadFiles files;private final UploadTransport transport;private final TelegramService telegram;private final UploadTargetReader targets;private final ResourceService reads;
    private final UploadRetryService retries;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("upload-worker").factory());private volatile boolean closed;private volatile String warning;
    public UploadEngine(UploadRepository repository,UploadFiles files,UploadTransport transport,TelegramService telegram,UploadTargetReader targets,ResourceService reads){this(repository,files,transport,telegram,targets,reads,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public UploadEngine(UploadRepository repository,UploadFiles files,UploadTransport transport,TelegramService telegram,UploadTargetReader targets,ResourceService reads,UploadRetryService retries){this.repository=repository;this.files=files;this.transport=transport;this.telegram=telegram;this.targets=targets;this.reads=reads;this.retries=retries;worker.scheduleWithFixedDelay(this::tick,500,1000,TimeUnit.MILLISECONDS);}
    public String warning(){return warning;}
    private void tick(){if(closed)return;transport.expireRecoveries();String account;long session;try{account=telegram.accountId();session=telegram.sessionGeneration();}catch(RuntimeException unavailable){return;}try{Task task=repository.next(account);if(task!=null)run(task,session);}catch(RuntimeException failed){warning="上传后台未能确认状态，请先核对任务；不会自动重发";}}
    private void run(Task original,long session){
        Task task=original;Group active=null;boolean intended=false;
        Runnable check=()->{if(closed||Thread.currentThread().isInterrupted()||!original.accountId().equals(telegram.accountId())||session!=telegram.sessionGeneration())throw new Stopped();};
        try {
            if(retries!=null&&retries.execute(original,session,check))return;
            for(var initial:task.groups()){
                check.run();task=repository.task(task.accountId(),task.id());active=task.groups().stream().filter(g->g.id()==initial.id()).findFirst().orElseThrow();intended=!active.state().equals("READY")&&!active.state().equals("PREPARING");
                repository.reapply(task,active);task=repository.task(task.accountId(),task.id());active=task.groups().stream().filter(g->g.id()==initial.id()).findFirst().orElseThrow();
                if(active.state().equals("CONFIRMED")){archive(task,active,check);continue;}
                if(java.util.List.of("UNCERTAIN","INTENT","DISPATCHING","SENDING").contains(active.state())){repository.state(task,"NEEDS_REVIEW","有发送结果无法证明，已保留源文件；核对操作不会重新发送");return;}
                if(task.stopRequested()){repository.state(task,"PAUSED",null);return;}
                if(active.items().stream().mapToLong(i->i.file().size()).sum()>8L*1024*1024*1024)throw new IllegalArgumentException("单组暂存超过8GiB，请先拆分目录重新预览");
                repository.preparing(task,active);
                for(var item:active.items()){check.run();if(repository.task(task.accountId(),task.id()).stopRequested())throw new Stopped();files.stage(task,item,check);}
                task=repository.task(task.accountId(),task.id());active=task.groups().stream().filter(g->g.id()==initial.id()).findFirst().orElseThrow();
                var fixedTask=task;var media=active.items().stream().map(i->i.file().mediaType()).collect(java.util.stream.Collectors.toSet());
                var fresh=reads.readWork(()->targets.read(fixedTask.prepared().input().link(),fixedTask.accountId(),session,media)).get(100,TimeUnit.SECONDS);
                if(!task.prepared().target().post().equals(fresh.post()))throw new IllegalArgumentException("目标主帖或讨论映射已变化，请核对任务");
                try(var lease=files.lease(active.items().stream().map(Item::staged).toList(),check)){
                    check.run();repository.intent(task,active,transport.owner(session));intended=true;transport.send(task,active,session);
                    long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(20);
                    while(true){check.run();transport.expireRecoveries();task=repository.task(task.accountId(),task.id());active=task.groups().stream().filter(g->g.id()==initial.id()).findFirst().orElseThrow();
                        if(active.state().equals("CONFIRMED"))break;
                        if(active.state().equals("UNCERTAIN")||transport.lostEvidence()||System.nanoTime()>deadline)throw new IllegalArgumentException("未能确认全部正式回执，需核对；不会重发");
                        Thread.sleep(350);
                    }
                }
                archive(task,active,check);intended=false;
            }
            task=repository.task(task.accountId(),task.id());boolean all=task.groups().stream().flatMap(g->g.items().stream()).allMatch(i->i.state().equals("ARCHIVED"));repository.state(task,all?"COMPLETED":"PAUSED",null);warning=null;
        }catch(RuleUploadPolicy.Blocked blocked){if(active!=null&&!intended)repository.unprepared(active);repository.pauseByRule(task,blocked);}
        catch(ArchiveFailure failure){repository.state(task,"SENT_PENDING_ARCHIVE","发送已有正式回执，归档未完成；重试只处理文件，不会重新发送");}
        catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();String receiptReason="发送结果需核对，源文件保留，禁止直接重发";if(active!=null){if(intended){repository.uncertain(active,"发送结果未全部确认，请核对");long groupId=active.id();var saved=repository.task(task.accountId(),task.id()).groups().stream().filter(g->g.id()==groupId).findFirst().orElseThrow();if(saved.error()!=null)receiptReason=saved.error();}else repository.unprepared(active);}
            if(closed&&!intended&&failure instanceof Stopped)repository.markAutomaticRestart(task);
            repository.state(task,intended?"NEEDS_REVIEW":"PAUSED",intended?receiptReason:failure instanceof IllegalArgumentException?failure.getMessage():"文件准备或目标核对未完成，请检查源版本、格式、空间和权限后继续");}
    }
    private void archive(Task task,Group group,Runnable check){for(var item:group.items()){if(item.state().equals("ARCHIVED"))continue;try{check.run();files.archive(task,item,check);}catch(IOException|RuntimeException failure){repository.itemError(item,"已发送待移动：请检查源版本、归档空间、占用或已有文件");throw new ArchiveFailure();}}}
    private static final class Stopped extends RuntimeException{}private static final class ArchiveFailure extends RuntimeException{}
    @Override public void onApplicationEvent(org.springframework.context.event.ContextClosedEvent event){closed=true;worker.shutdownNow();}
    @Override @PreDestroy public void close(){closed=true;worker.shutdownNow();boolean interrupted=false;for(;;)try{if(worker.awaitTermination(1,TimeUnit.SECONDS))break;}catch(InterruptedException stopped){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
}
