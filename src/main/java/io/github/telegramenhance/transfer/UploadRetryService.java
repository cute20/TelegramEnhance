package io.github.telegramenhance.transfer;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.UploadRetryModels.*;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

@Service
public final class UploadRetryService {
    private final UploadRepository uploads;private final UploadRetryRepository retries;private final UploadTransport transport;private final UploadFiles files;private final ResourceService reads;private final UploadTargetReader targets;private final TelegramService telegram;
    private record Snapshot(Check view,String account,long session,List<Candidate> candidates){}
    private final LinkedHashMap<String,Snapshot> snapshots=new LinkedHashMap<>();
    public UploadRetryService(UploadRepository uploads,UploadRetryRepository retries,UploadTransport transport,UploadFiles files,ResourceService reads,UploadTargetReader targets,TelegramService telegram){this.uploads=uploads;this.retries=retries;this.transport=transport;this.files=files;this.reads=reads;this.targets=targets;this.telegram=telegram;}
    public CompletableFuture<Check> check(String id){String account=telegram.accountId();long session=telegram.sessionGeneration();var task=uploads.task(account,id);if(!task.state().equals("NEEDS_REVIEW"))throw new IllegalArgumentException("当前任务无需核对失败成员，请刷新任务");
        return reads.readWork(()->{
            current(account,session);transport.recoverPending(task,session);var fresh=uploads.task(account,id);
            var group=fresh.groups().stream().filter(g->g.items().stream().anyMatch(i->i.formalId()==null)).findFirst().orElse(fresh.groups().getFirst());
            var rows=new ArrayList<Row>();var candidates=new ArrayList<Candidate>();int confirmed=0,pending=0,unresolved=0;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
            for(var item:group.items()){
                current(account,session);Inspection result;
                if(item.formalId()!=null)result=new Inspection("CONFIRMED","已有正式成功回执，不重发",null);
                else if(item.staged()==null||System.nanoTime()>deadline)result=new Inspection("UNKNOWN","缺少暂存关联或本轮核对达到时间上限",null);
                else try(var lease=files.lease(List.of(item.staged()),()->{current(account,session);if(System.nanoTime()>deadline)throw new IllegalArgumentException("核对时间已到");})){result=transport.inspectRetry(fresh,group,item,session);}
                catch(Exception unavailable){current(account,session);result=new Inspection("UNKNOWN","原消息或暂存副本无法核实，保留原件，不重发",null);}
                rows.add(new Row(item.id(),Path.of(item.file().relativePath()).getFileName().toString(),result.state(),result.reason()));
                if(result.state().equals("RETRYABLE"))candidates.add(new Candidate(item.id(),item.temporaryId(),result.failedId(),item.staged()));else if(result.state().equals("CONFIRMED"))confirmed++;else if(result.state().equals("PENDING"))pending++;else unresolved++;
            }
            current(account,session);String check=UUID.randomUUID().toString();long expires=System.currentTimeMillis()+TimeUnit.MINUTES.toMillis(10);var view=new Check(check,id,Long.toString(group.id()),expires,confirmed,candidates.size(),pending,unresolved,rows);
            synchronized(snapshots){snapshots.values().removeIf(s->!s.account().equals(account)||s.session()!=session||s.view().expiresAt()<=System.currentTimeMillis());snapshots.put(check,new Snapshot(view,account,session,List.copyOf(candidates)));while(snapshots.size()>8)snapshots.remove(snapshots.keySet().iterator().next());}return view;
        });
    }
    public View retry(String id,Retry request){String account=telegram.accountId();long session=telegram.sessionGeneration();if(request==null)throw new IllegalArgumentException("请先核对失败成员");uuid(request.operationId());uuid(request.checkId());
        if(retries.operation(account,id,request.operationId(),request.checkId()))return uploads.view(uploads.task(account,id));Snapshot snapshot;synchronized(snapshots){snapshot=snapshots.get(request.checkId());}
        if(snapshot==null||!snapshot.account().equals(account)||snapshot.session()!=session||!snapshot.view().taskId().equals(id)||snapshot.view().expiresAt()<=System.currentTimeMillis())throw new IllegalArgumentException("失败成员核对已过期，请重新核对");current(account,session);
        return uploads.view(retries.enqueue(account,id,request.operationId(),request.checkId(),Long.parseLong(snapshot.view().groupId()),snapshot.candidates(),transport.owner(session)));
    }
    public UploadTaskService.Lookup lookup(String id,String operation){String account=telegram.accountId();uuid(operation);return new UploadTaskService.Lookup(retries.operation(account,id,operation,null)?uploads.view(uploads.task(account,id)):null);}
    /** Executed exclusively on UploadEngine's existing single worker. */
    public boolean execute(Task original,long session,Runnable running){var attempts=retries.queued(original.id());if(attempts.isEmpty())return false;
        Runnable check=()->{running.run();current(original.accountId(),session);};Group group=original.groups().stream().filter(g->g.id()==attempts.getFirst().groupId()).findFirst().orElseThrow();
        try{
            check.run();var freshTarget=reads.readWork(()->targets.read(original.prepared().input().link(),original.accountId(),session,Set.of(group.mediaType()))).get(100,TimeUnit.SECONDS);
            if(!original.prepared().target().post().equals(freshTarget.post()))throw new IllegalArgumentException("目标主帖或讨论映射发生变化，请重新核对");
            for(var attempt:attempts){check.run();var task=uploads.task(original.accountId(),original.id());var item=find(task,attempt.itemId());if(item.formalId()==null)files.stage(task,item,check);}
            var frozen=uploads.task(original.accountId(),original.id());var staged=attempts.stream().map(a->find(frozen,a.itemId())).filter(i->i.formalId()==null).map(Item::staged).toList();
            try(var lease=files.lease(staged,check)){
                for(var attempt:attempts){check.run();var task=uploads.task(original.accountId(),original.id());var item=find(task,attempt.itemId());if(item.formalId()!=null){retries.state(attempt,"SENT",null);continue;}transport.resendFailed(task,group,item,attempt,session);}
                long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(20);var done=new HashSet<String>();
                while(done.size()<attempts.size()){
                    check.run();transport.expireRecoveries();var task=uploads.task(original.accountId(),original.id());
                    for(var attempt:attempts){if(done.contains(attempt.id()))continue;var item=find(task,attempt.itemId());if(item.formalId()!=null){retries.state(attempt,"SENT",null);done.add(attempt.id());}else {var receipt=uploads.receipt(group.id(),item.temporaryId());if(receipt!=null&&!receipt.success()){retries.state(attempt,"FAILED","本成员重试仍失败，请重新核对");done.add(attempt.id());}}}
                    if(done.size()==attempts.size())break;if(transport.lostEvidence()||System.nanoTime()>deadline)throw new IllegalArgumentException("重试正式回执未齐，保留原件待核对，不自动重试");Thread.sleep(350);
                }
            }
            if(transport.lostEvidence())throw new IllegalArgumentException("原生回执关联未能完整保存，请人工核对");
            var task=uploads.task(original.accountId(),original.id());uploads.reapply(task,group);task=uploads.task(original.accountId(),original.id());var result=task.groups().stream().filter(g->g.id()==group.id()).findFirst().orElseThrow();
            if(!result.state().equals("CONFIRMED")){uploads.state(task,"NEEDS_REVIEW","已成功成员保留；其余成员仍需核对，不整组重发");return true;}
            try{for(var item:result.items())if(!item.state().equals("ARCHIVED")){check.run();files.archive(task,item,check);}}
            catch(Exception archive){uploads.state(task,"SENT_PENDING_ARCHIVE","本组正式回执已齐，仅需重试归档，不重新发送");return true;}
            task=uploads.task(original.accountId(),original.id());boolean all=task.groups().stream().flatMap(g->g.items().stream()).allMatch(i->i.state().equals("ARCHIVED"));uploads.state(task,all?"COMPLETED":"PAUSED",all?null:"本次失败成员已处理，后续未发送组仍暂停");
        }catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();retries.reviewQueued(original.id());uploads.state(original,"NEEDS_REVIEW","本轮失败成员重试未全部确认，请重新核对；成功成员不重发，源文件保留");}
        return true;
    }
    private static Item find(Task task,String item){return task.groups().stream().flatMap(g->g.items().stream()).filter(i->i.id().equals(item)).findFirst().orElseThrow();}
    private void current(String account,long session){if(Thread.currentThread().isInterrupted()||!account.equals(telegram.accountId())||session!=telegram.sessionGeneration())throw new IllegalArgumentException("账号或会话已变化，请重新核对失败成员");}
    private static void uuid(String value){if(value==null||!value.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("重试操作标识无效");}
}
