package io.github.telegramenhance.telegram;

import io.github.telegramenhance.transfer.UploadRepository;
import io.github.telegramenhance.transfer.UploadFiles;
import io.github.telegramenhance.transfer.UploadRetryRepository;
import io.github.telegramenhance.transfer.UploadRetryModels.*;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import it.tdlight.jni.TdApi;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/** One explicit send call per persisted intent; native successes are journalled on a bounded worker. */
@Service
public final class UploadTransport implements AutoCloseable {
    interface Gateway { <R extends TdApi.Object> CompletableFuture<R> call(long session,TdApi.Function<R> query); }
    private final Gateway gateway; private final TelegramService telegram; private final UploadRepository repository;private final UploadFiles files;
    private final UploadRetryRepository retries;
    private final String run=UUID.randomUUID().toString(); private final AutoCloseable subscription;
    private final ThreadPoolExecutor receipts=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(128),Thread.ofPlatform().name("upload-receipts").factory());
    private record Active(String account,long session,long group,String chat) { }
    private final AtomicReference<Active> active=new AtomicReference<>(); private volatile boolean lost;
    private record RecoveryKey(String account,long session,String chat,String temporaryId) { }
    private record RecoveryBinding(long group,long expiresAt,UploadFiles.Lease lease) { }
    private final Map<RecoveryKey,RecoveryBinding> recovered=new LinkedHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public UploadTransport(TelegramService telegram,UploadRepository repository,UploadFiles files,UploadRetryRepository retries){this(telegram,repository,files,retries,new Gateway(){public <R extends TdApi.Object>CompletableFuture<R>call(long session,TdApi.Function<R> query){return telegram.readInSession(session,query);}});}
    public UploadTransport(TelegramService telegram,UploadRepository repository,UploadFiles files){this(telegram,repository,files,null,new Gateway(){public <R extends TdApi.Object>CompletableFuture<R>call(long session,TdApi.Function<R> query){return telegram.readInSession(session,query);}});}
    UploadTransport(TelegramService telegram,UploadRepository repository,UploadFiles files,Gateway gateway){this(telegram,repository,files,null,gateway);}
    UploadTransport(TelegramService telegram,UploadRepository repository,UploadFiles files,UploadRetryRepository retries,Gateway gateway){this.telegram=telegram;this.repository=repository;this.files=files;this.retries=retries;this.gateway=gateway;this.subscription=telegram.observeSends(this::update);}
    public String owner(long generation){return run+":"+generation;}
    public boolean lostEvidence(){return lost;}
    /** Native FAILED + canRetry is required. Missing/pending/successful messages never become retry candidates. */
    public Inspection inspectRetry(Task task,Group group,Item item,long session){
        check(task.accountId(),session);
        if(lost)return new Inspection("BLOCKED","原生回执记录发生过冲突或遗漏，请人工核对",null);
        if(item.formalId()!=null)return new Inspection("CONFIRMED","已有正式成功回执，保留且不重发",null);
        if(item.temporaryId()==null||item.staged()==null)return new Inspection("UNKNOWN","缺少原消息或暂存关联，需人工核对",null);
        var receipt=repository.receipt(group.id(),item.temporaryId());if(receipt!=null&&receipt.success())return new Inspection("BLOCKED","已有送达证据但类型或相册需核对，不能重发",null);
        var failure=retries==null?null:retries.failure(group.id(),item.temporaryId());String nativeId=failure==null?item.temporaryId():failure.failedId();
        var message=call(task.accountId(),session,new TdApi.GetMessage(Long.parseLong(task.prepared().target().post().discussionChatId()),Long.parseLong(nativeId)));
        if(message==null||message.id!=Long.parseLong(nativeId))return new Inspection("UNKNOWN","原消息不可核实，不重发",null);
        if(message.sendingState instanceof TdApi.MessageSendingStatePending)return matchesPending(task,group,item,message)?new Inspection("PENDING","原消息仍在发送，继续核对原请求，不另发",null):new Inspection("UNKNOWN","待发送消息的完整归属未能核实，不另发",null);
        if(!(message.sendingState instanceof TdApi.MessageSendingStateFailed failed))return new Inspection("UNKNOWN","没有取得明确的原生发送失败状态，不重发",null);
        var proof=evidence(message,item.temporaryId(),false);var root=task.prepared().target().post().discussionRootMessageId();
        if(!message.isOutgoing||message.date<=0||!task.prepared().target().post().discussionChatId().equals(proof.chatId())||!item.file().mediaType().equals(proof.mediaType())||!item.caption().equals(proof.caption())||!(root.equals(proof.threadId())||root.equals(proof.replyId()))||proof.threadId()!=null&&!root.equals(proof.threadId())||proof.replyId()!=null&&!root.equals(proof.replyId()))return new Inspection("BLOCKED","原失败消息身份、类型、说明或目标不符，不重试",null);
        var file=nativeFile(message);if(file==null)return new Inspection("BLOCKED","失败消息没有可关联的媒体文件",null);
        if(failure!=null){if(!Objects.equals(failure.evidence().uniqueId(),proof.uniqueId())||failure.nativeSize()!=file.size)return new Inspection("BLOCKED","原失败媒体版本发生变化，不重试",null);}
        if(item.file().mediaType().equals("PHOTO")){if(failure==null||proof.uniqueId()==null||proof.uniqueId().isBlank())return new Inspection("BLOCKED","图片转换后缺少原生失败关联，请人工核对",null);}
        else if(file.size!=item.file().size()||file.local==null||!samePath(file.local.path,item.staged().path()))return new Inspection("BLOCKED","失败媒体与冻结暂存副本不符，不重试",null);
        if(!failed.canRetry||failed.needAnotherSender||failed.needAnotherReplyQuote||failed.needDropReply||failed.requiredPaidMessageStarCount!=0||!Double.isFinite(failed.retryAfter)||failed.retryAfter<0)return new Inspection("BLOCKED","Telegram当前不允许保持原目标和回复关系重试",null);
        if(failed.retryAfter>0)return new Inspection("WAITING","Telegram要求等待，请稍后重新核对",null);
        return new Inspection("RETRYABLE","原生已确认失败，允许仅重试此成员",nativeId);
    }
    /** Called only by the single upload worker, once per persisted member intent. No new SendMessage is created. */
    public void resendFailed(Task task,Group group,Item item,Attempt attempt,long session){
        if(retries==null)throw new IllegalStateException("失败成员重试未初始化");var inspected=inspectRetry(task,group,item,session);
        if(!"RETRYABLE".equals(inspected.state())||!attempt.failedId().equals(inspected.failedId()))throw new IllegalArgumentException("失败状态已变化，请重新核对；本成员未发起重试");
        var context=new Active(task.accountId(),session,group.id(),task.prepared().target().post().discussionChatId());if(!active.compareAndSet(null,context))throw new IllegalArgumentException("已有上传组正在处理");
        try{check(task.accountId(),session);retries.dispatch(attempt,owner(session));
            var result=call(task.accountId(),session,new TdApi.ResendMessages(Long.parseLong(context.chat()),new long[]{Long.parseLong(attempt.failedId())},null,0));
            if(result==null||result.messages==null||result.messages.length!=1||result.messages[0]==null)throw new IllegalArgumentException("未得到失败成员重试回执，请核对，不自动重试");
            var message=result.messages[0];String id=Long.toString(message.id);var proof=evidence(message,id,message.sendingState==null);var root=task.prepared().target().post().discussionRootMessageId();
            if(message.id==0||id.equals(item.temporaryId())||id.equals(attempt.failedId())||!message.isOutgoing||!context.chat().equals(proof.chatId())||!item.file().mediaType().equals(proof.mediaType())||!item.caption().equals(proof.caption())||!(root.equals(proof.threadId())||root.equals(proof.replyId()))||proof.threadId()!=null&&!root.equals(proof.threadId())||proof.replyId()!=null&&!root.equals(proof.replyId()))throw new IllegalArgumentException("重试回执与原成员不符，需核对");
            retries.bind(attempt,owner(session),id);
            if(message.sendingState==null)repository.event(group.id(),proof);
            else if(message.sendingState instanceof TdApi.MessageSendingStateFailed){retries.failure(group.id(),failure(message,id));repository.event(group.id(),proof);}
            else if(!(message.sendingState instanceof TdApi.MessageSendingStatePending))throw new IllegalArgumentException("重试状态无法确认");
            repository.reapply(task,group);
        }finally{active.compareAndSet(context,null);}
    }
    private static boolean samePath(String a,String b){try{return a!=null&&!a.isBlank()&&java.nio.file.Path.of(a).isAbsolute()&&java.nio.file.Path.of(a).normalize().equals(java.nio.file.Path.of(b).normalize());}catch(RuntimeException invalid){return false;}}
    private static TdApi.File nativeFile(TdApi.Message message){return switch(message.content){case TdApi.MessageDocument d->d.document==null?null:d.document.document;case TdApi.MessageVideo v->v.video==null?null:v.video.video;case TdApi.MessageAudio a->a.audio==null?null:a.audio.audio;case TdApi.MessageAnimation a->a.animation==null?null:a.animation.animation;case TdApi.MessagePhoto p->p.photo==null||p.photo.sizes==null?null:Arrays.stream(p.photo.sizes).filter(s->s!=null&&s.photo!=null).max(Comparator.comparingLong(s->(long)s.width*s.height)).map(s->s.photo).orElse(null);default->null;};}
    private static Failure failure(TdApi.Message message,String old){var file=nativeFile(message);return new Failure(Long.toString(message.id),file==null?0:file.size,evidence(message,old,false));}
    /** Explicit read-only recovery. Native pending messages must reference our exact protected sending copies. */
    public int recoverPending(Task task,long generation){
        check(task.accountId(),generation);expireRecoveries();int inspected=0,bound=0;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        for(var group:task.groups())for(var item:group.items()){
            if(item.formalId()!=null||item.temporaryId()==null||item.staged()==null||!List.of("UNCERTAIN","SENDING","DISPATCHING").contains(group.state()))continue;
            if(inspected++>=10||System.nanoTime()>=deadline)return bound;
            var key=new RecoveryKey(task.accountId(),generation,task.prepared().target().post().discussionChatId(),item.temporaryId());
            synchronized(recovered){var previous=recovered.get(key);if(previous!=null){if(previous.group()==group.id())bound++;continue;}if(recovered.size()>=128)return bound;}
            UploadFiles.Lease lease=null;
            try{
                lease=files.lease(List.of(item.staged()),()->{check(task.accountId(),generation);if(System.nanoTime()>=deadline)throw new IllegalArgumentException("核对时间已到，请刷新任务");});
                var message=call(task.accountId(),generation,new TdApi.GetMessage(Long.parseLong(key.chat()),Long.parseLong(item.temporaryId())));
                if(!matchesPending(task,group,item,message))continue;
                check(task.accountId(),generation);
                synchronized(recovered){if(recovered.size()>=128)return bound;var previous=recovered.get(key);if(previous==null){recovered.put(key,new RecoveryBinding(group.id(),System.nanoTime()+TimeUnit.MINUTES.toNanos(20),lease));lease=null;bound++;}else if(previous.group()==group.id())bound++;}
            }catch(java.io.IOException|RuntimeException unavailable){check(task.accountId(),generation);/* Unknown, changed or missing inputs stay unresolved; never resend. */}
            finally{if(lease!=null)lease.close();}
        }
        return bound;
    }
    private static boolean matchesPending(Task task,Group group,Item item,TdApi.Message message){
        if(message==null||!message.isOutgoing||message.date<=0||message.chatId!=Long.parseLong(task.prepared().target().post().discussionChatId())||message.id!=Long.parseLong(item.temporaryId())||!(message.sendingState instanceof TdApi.MessageSendingStatePending pending)||pending.sendingId!=0&&pending.sendingId!=group.id())return false;
        var proof=evidence(message,item.temporaryId(),false);var root=task.prepared().target().post().discussionRootMessageId();
        if(!item.file().mediaType().equals(proof.mediaType())||!item.caption().equals(proof.caption())||!(root.equals(proof.threadId())||root.equals(proof.replyId()))||proof.threadId()!=null&&!root.equals(proof.threadId())||proof.replyId()!=null&&!root.equals(proof.replyId()))return false;
        TdApi.File nativeFile=switch(message.content){case TdApi.MessageDocument d->d.document==null?null:d.document.document;case TdApi.MessageVideo v->v.video==null?null:v.video.video;case TdApi.MessageAudio a->a.audio==null?null:a.audio.audio;case TdApi.MessageAnimation a->a.animation==null?null:a.animation.animation;default->null;};
        if(nativeFile==null||nativeFile.size!=item.file().size()||nativeFile.local==null||nativeFile.local.path==null||nativeFile.local.path.isBlank())return false;
        try{
            // Compare only; never open, resolve links under, or read TDLib's local.path.
            var nativePath=java.nio.file.Path.of(nativeFile.local.path);var ownedPath=java.nio.file.Path.of(item.staged().path());
            return nativePath.isAbsolute()&&nativePath.normalize().equals(ownedPath.normalize());
        }catch(java.nio.file.InvalidPathException invalid){return false;}
    }
    public void expireRecoveries(){
        String account;try{account=telegram.accountId();}catch(RuntimeException disconnected){account=null;}long generation=telegram.sessionGeneration(),now=System.nanoTime();
        synchronized(recovered){var iterator=recovered.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();if(!Objects.equals(account,entry.getKey().account())||generation!=entry.getKey().session()||now>=entry.getValue().expiresAt()){entry.getValue().lease().close();iterator.remove();}}}
    }
    public void send(Task task,Group group,long generation){
        check(task.accountId(),generation);var context=new Active(task.accountId(),generation,group.id(),task.prepared().target().post().discussionChatId());
        if(!active.compareAndSet(null,context))throw new IllegalStateException("Only one upload group may send");
        try {
            var content=group.items().stream().map(this::content).toArray(TdApi.InputMessageContent[]::new);
            if(content.length<1||content.length>10||group.items().stream().anyMatch(i->!i.file().mediaType().equals(group.mediaType())))throw new IllegalArgumentException("媒体分组无效，未发送");
            repository.dispatch(task,group,owner(generation));
            var options=new TdApi.MessageSendOptions();options.sendingId=Math.toIntExact(group.id());options.paidMessageStarCount=0;options.onlyPreview=false;
            long chat=Long.parseLong(context.chat()),root=Long.parseLong(task.prepared().target().post().discussionRootMessageId());
            var topic=new TdApi.MessageTopicThread(root);var reply=new TdApi.InputMessageReplyToMessage(root,null,0,null);
            List<TdApi.Message> messages;
            if(content.length==1){var result=call(task.accountId(),generation,new TdApi.SendMessage(chat,topic,reply,options,null,content[0]));messages=List.of(result);}
            else {var result=call(task.accountId(),generation,new TdApi.SendMessageAlbum(chat,topic,reply,options,content));if(result==null||result.messages==null)throw new IllegalArgumentException("未得到相册初始回执，需核对发送结果");messages=Arrays.asList(result.messages);}
            var initial=new ArrayList<Initial>();for(var message:messages){if(message==null||message.chatId!=chat||!message.isOutgoing)throw new IllegalArgumentException("初始消息身份不符，需核对");boolean pending=message.sendingState instanceof TdApi.MessageSendingStatePending;int sending=pending?((TdApi.MessageSendingStatePending)message.sendingState).sendingId:0;if(!pending&&message.sendingState!=null)throw new IllegalArgumentException("原生发送未确认，请核对");initial.add(new Initial(Long.toString(message.id),sending,pending,evidence(message,Long.toString(message.id),!pending)));}
            repository.initial(task,group,initial);
        } finally {active.compareAndSet(context,null);}
    }
    private TdApi.InputMessageContent content(Item item){
        if(item.staged()==null)throw new IllegalArgumentException("发送文件尚未准备好");var file=new TdApi.InputFileLocal(item.staged().path());var caption=new TdApi.FormattedText(item.caption(),new TdApi.TextEntity[0]);
        return switch(item.file().mediaType()){
            case "PHOTO" -> new TdApi.InputMessagePhoto(new TdApi.InputPhoto(file,null,null,new int[0],item.staged().width(),item.staged().height()),caption,false,null,false);
            case "VIDEO" -> new TdApi.InputMessageVideo(new TdApi.InputVideo(file,null,null,0,new int[0],0,0,0,false),caption,false,null,false);
            case "AUDIO" -> new TdApi.InputMessageAudio(new TdApi.InputAudio(file,null,0,"",""),caption);
            case "DOCUMENT" -> new TdApi.InputMessageDocument(new TdApi.InputDocument(file,null,true),caption);
            case "ANIMATION" -> new TdApi.InputMessageAnimation(new TdApi.InputAnimation(file,null,new int[0],0,item.staged().width(),item.staged().height()),caption,false,false);
            default -> throw new IllegalArgumentException("未知媒体类型，未发送");
        };
    }
    private void update(String account,long generation,TdApi.Update update){
        TdApi.Message message;long old;boolean success;
        if(update instanceof TdApi.UpdateMessageSendSucceeded sent){message=sent.message;old=sent.oldMessageId;success=message!=null&&message.sendingState==null;}
        else if(update instanceof TdApi.UpdateMessageSendFailed failed){message=failed.message;old=failed.oldMessageId;success=false;}else return;
        if(message==null||!message.isOutgoing)return;var proof=evidence(message,Long.toString(old),success);var observed=active.get();
        try {receipts.execute(()->{RecoveryBinding binding=null;var key=new RecoveryKey(account,generation,proof.chatId(),proof.oldId());try {Long gid=repository.pendingGroup(account,owner(generation),proof.chatId(),proof.oldId());if(gid==null&&observed!=null&&observed.account().equals(account)&&observed.session()==generation&&observed.chat().equals(proof.chatId()))gid=observed.group();synchronized(recovered){binding=recovered.remove(key);}if(gid==null&&binding!=null&&System.nanoTime()<binding.expiresAt()&&current(account,generation))gid=binding.group();if(gid!=null){if(!success&&retries!=null&&message.sendingState instanceof TdApi.MessageSendingStateFailed)retries.failure(gid,failure(message,Long.toString(old)));repository.event(gid,proof);}}catch(RuntimeException failure){lost=true;}finally{if(binding!=null)binding.lease().close();}});}catch(RejectedExecutionException full){lost=true;}
    }
    static Evidence evidence(TdApi.Message message,String old,boolean success){
        String type="UNSUPPORTED",caption="",unique=null;TdApi.File file=null;
        if(message.content instanceof TdApi.MessagePhoto photo){type="PHOTO";caption=text(photo.caption);if(photo.photo!=null&&photo.photo.sizes!=null)file=Arrays.stream(photo.photo.sizes).filter(p->p!=null&&p.photo!=null).max(Comparator.comparingLong(p->(long)p.width*p.height)).map(p->p.photo).orElse(null);}
        else if(message.content instanceof TdApi.MessageVideo video){type="VIDEO";caption=text(video.caption);if(video.video!=null)file=video.video.video;}
        else if(message.content instanceof TdApi.MessageAudio audio){type="AUDIO";caption=text(audio.caption);if(audio.audio!=null)file=audio.audio.audio;}
        else if(message.content instanceof TdApi.MessageDocument document){type="DOCUMENT";caption=text(document.caption);if(document.document!=null)file=document.document.document;}
        else if(message.content instanceof TdApi.MessageAnimation animation){type="ANIMATION";caption=text(animation.caption);if(animation.animation!=null)file=animation.animation.animation;}
        if(file!=null&&file.remote!=null)unique=file.remote.uniqueId;
        String thread=message.topicId instanceof TdApi.MessageTopicThread topic?Long.toString(topic.messageThreadId):null;
        String reply=message.replyTo instanceof TdApi.MessageReplyToMessage ref?(ref.chatId==0||ref.chatId==message.chatId?Long.toString(ref.messageId):"INVALID"):null;
        return new Evidence(old,success?Long.toString(message.id):null,Long.toString(message.chatId),thread,reply,Long.toString(message.mediaAlbumId),type,caption,unique,message.isOutgoing,success,message.date);
    }
    private static String text(TdApi.FormattedText text){return text==null||text.text==null?"":text.text;}
    private void check(String account,long generation){if(Thread.currentThread().isInterrupted()||!account.equals(telegram.accountId())||generation!=telegram.sessionGeneration())throw new IllegalArgumentException("账号或会话已变化，发送结果需核对");}
    private boolean current(String account,long generation){try{return account.equals(telegram.accountId())&&generation==telegram.sessionGeneration();}catch(RuntimeException unavailable){return false;}}
    private <R extends TdApi.Object>R call(String account,long generation,TdApi.Function<R>query){check(account,generation);try{var result=gateway.call(generation,query).get(16,TimeUnit.SECONDS);check(account,generation);return result;}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalArgumentException("发送已中断，需核对，不能直接重发");}catch(ExecutionException|TimeoutException failure){throw new IllegalArgumentException("发送请求结果未确认，请先核对，不能直接重发");}}
    @Override @PreDestroy public void close(){try{if(subscription!=null)subscription.close();}catch(Exception ignored){}receipts.shutdown();try{if(!receipts.awaitTermination(5,TimeUnit.SECONDS))receipts.shutdownNow();}catch(InterruptedException stopped){Thread.currentThread().interrupt();receipts.shutdownNow();}synchronized(recovered){recovered.values().forEach(b->b.lease().close());recovered.clear();}}
}
