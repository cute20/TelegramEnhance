package io.github.telegramenhance.rules;
import io.github.telegramenhance.rules.RuleModels.*;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.UploadPreviewModels.*;
import io.github.telegramenhance.transfer.UploadPreviewService.Prepared;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Produces at most one frozen homogeneous group, reusing the actual upload queue and claims. */
@Service
public final class RuleUploadBroker {
    private final RuleRepository rules;private final RuleUploadPolicy policy;private final UploadRepository uploads;private final ResourceService reads;private final UploadTargetReader targets;private final TelegramService telegram;private final Clock clock;private final JsonMapper json=JsonMapper.builder().build();
    @org.springframework.beans.factory.annotation.Autowired public RuleUploadBroker(RuleRepository rules,RuleUploadPolicy policy,UploadRepository uploads,ResourceService reads,UploadTargetReader targets,TelegramService telegram){this(rules,policy,uploads,reads,targets,telegram,Clock.systemUTC());}
    public RuleUploadBroker(RuleRepository rules,RuleUploadPolicy policy,UploadRepository uploads,ResourceService reads,UploadTargetReader targets,TelegramService telegram,Clock clock){this.rules=rules;this.policy=policy;this.uploads=uploads;this.reads=reads;this.targets=targets;this.telegram=telegram;this.clock=clock;}
    public boolean resumeActive(Rule rule,Runnable running){String id=policy.activeTask(rule.accountId(),rule.id());if(id==null)return false;running.run();var task=uploads.task(rule.accountId(),id);
        String kind=policy.pauseKind(rule.accountId(),id);if(task.state().equals("READY")||task.state().equals("PAUSED")&&Set.of("WINDOW","RULE","RESTART_READY").contains(kind==null?"":kind))try{current(rule,telegram.sessionGeneration(),running);uploads.start(rule.accountId(),id);}catch(RuleUploadPolicy.Blocked blocked){uploads.pauseByRule(task,blocked);}
        return true;
    }
    public CompletableFuture<Void> consider(Rule rule,Scan scan,Runnable running){
        long generation=telegram.sessionGeneration();current(rule,generation,running);if(resumeActive(rule,running))return CompletableFuture.completedFuture(null);
        var approved=policy.authorization(rule);var claimed=uploads.claimed(rule.accountId(),approved.post(),scan.files());int claimedCount=(int)scan.files().stream().filter(f->claimed.contains(UploadRepository.contentKey(f))).count();
        var available=scan.files().stream().filter(f->!claimed.contains(UploadRepository.contentKey(f))).sorted(Comparator.comparingLong(FileVersion::modifiedMillis).thenComparing(FileVersion::relativePath)).toList();
        if(available.isEmpty()){note(rule,"IDLE",claimedCount>0?"同目标已有上传任务的内容未重复创建或移动；可到任务中心核对":"没有新的稳定媒体",null,claimedCount);return CompletableFuture.completedFuture(null);}
        var first=available.getFirst();long maximumBytes=8L*1024*1024*1024;int maximum=first.mediaType().equals("ANIMATION")?1:10;long bytes=0;var selectedPaths=new HashSet<String>();
        for(var file:available){if(!file.mediaType().equals(first.mediaType())||selectedPaths.size()>=maximum)continue;if(file.size()>maximumBytes){note(rule,"BLOCKED","候选媒体超过单组暂存上限，请拆分文件后处理",null,claimedCount);return CompletableFuture.completedFuture(null);}if(bytes+file.size()>maximumBytes)continue;bytes+=file.size();selectedPaths.add(file.relativePath());}
        var selected=scan.files().stream().filter(f->selectedPaths.contains(f.relativePath())).toList();
        CompletableFuture<Void> preparation;
        try{preparation=reads.readWork(()->{
            current(rule,generation,running);var target=targets.read(rule.input().link(),rule.accountId(),generation,Set.of(first.mediaType()));current(rule,generation,running);
            if(!RuleUploadPolicy.sameTarget(approved,target))throw new IllegalArgumentException("目标讨论映射与启用时不一致，请重新预览并启用规则");
            var frozenScan=new Scan(scan.sourceDirectory(),scan.archiveDirectory(),scan.recursive(),selected,selected,0,0,0,0);var prepared=new Prepared(rule.input().uploadInput(),target,frozenScan,UploadPreviewService.groups(selected,rule.input().captionMode()));
            String fingerprint=DownloadPaths.hash(rule.id()+":"+rule.version()+":"+json.writeValueAsString(rule.input())+":"+UploadRepository.targetKey(target.post())+":"+json.writeValueAsString(selected));String operation=UUID.nameUUIDFromBytes(("auto-upload:"+fingerprint).getBytes(StandardCharsets.UTF_8)).toString();
            current(rule,generation,running);var task=uploads.create(rule.accountId(),operation,fingerprint,prepared,rule);current(rule,generation,running);uploads.start(rule.accountId(),task.id());note(rule,"QUEUED","已创建一组自动上传任务，按保存的时段执行",task.id(),claimedCount);return (Void)null;
        });}catch(RuntimeException rejected){preparation=CompletableFuture.failedFuture(rejected);}
        return preparation.exceptionally(failure->{try{current(rule,generation,running);note(rule,"BLOCKED","自动任务准备未完成，请检查目录、已有任务及目标权限；可用分组预览核对，未自动重试发送",null,claimedCount);}catch(RuntimeException stale){/* Old context cannot publish a new status or task. */}return null;});
    }
    private void note(Rule rule,String state,String message,String task,int claimed){rules.uploadOutcome(rule,new UploadOutcome(state,message,task,claimed,clock.millis()));}
    private void current(Rule rule,long generation,Runnable running){running.run();if(Thread.currentThread().isInterrupted()||!rule.accountId().equals(telegram.accountId())||generation!=telegram.sessionGeneration())throw new IllegalArgumentException("自动上传会话已变化");var current=rules.get(rule.accountId(),rule.id());if(!current.enabled()||current.revision()!=rule.revision()||current.version()!=rule.version()||!current.input().executionMode().equals("AUTO_UPLOAD"))throw new IllegalArgumentException("规则已停止或更新");if(!rule.input().window().allows(clock.instant()))throw new RuleUploadPolicy.Blocked("WINDOW","当前不在规则上传时段内");}
}
