package io.github.telegramenhance.telegram;

import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.transfer.DownloadModels.FrozenFile;
import io.github.telegramenhance.transfer.DownloadModels.BotFileReference;
import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Resolves primary files and reads bounded chunks; never opens TDLib's local.path. */
@Service
public final class DownloadFileReader {
    private static final int BOT_MESSAGE_BATCH = 50;
    interface Gateway {
        <R extends TdApi.Object> CompletableFuture<R> read(long generation, TdApi.Function<R> request);
        default <R extends TdApi.Object> CompletableFuture<R> download(long generation,TdApi.Function<R> request){return read(generation,request);}
    }
    private final Gateway gateway; private final Supplier<String> account; private final LongSupplier generation, clock;
    private record DownloadKey(String account,long session,int fileId,String uniqueId,long size){}
    private final ConcurrentHashMap<DownloadKey,CompletableFuture<TdApi.File>> downloads=new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public DownloadFileReader(TelegramService telegram) {
        this(new Gateway() {
            public <R extends TdApi.Object> CompletableFuture<R> read(long gen,TdApi.Function<R> request){return telegram.readInSession(gen,request);}
            public <R extends TdApi.Object> CompletableFuture<R> download(long gen,TdApi.Function<R> request){return telegram.downloadInSession(gen,request);}
        }, telegram::accountId, telegram::sessionGeneration);
    }
    DownloadFileReader(Gateway gateway, Supplier<String> account, LongSupplier generation) { this(gateway,account,generation,System::nanoTime); }
    DownloadFileReader(Gateway gateway, Supplier<String> account, LongSupplier generation, LongSupplier clock) { this.gateway = gateway; this.account = account; this.generation = generation; this.clock=clock; }
    public record Resolved(FrozenFile file, int fileId) { }
    public record Status(long downloadedBytes, boolean complete, boolean active, boolean canDownload) { }
    /** A completed native transfer failed; identity and permissions still require separate checks. */
    public static final class DownloadFailed extends IllegalArgumentException {
        private final boolean referenceRejected;
        public DownloadFailed(boolean referenceRejected){
            super(referenceRejected?"Telegram 拒绝了原文件引用，需要重新核对来源":"Telegram 原文件下载失败，需要重新核对来源");
            this.referenceRejected=referenceRejected;
        }
        public boolean referenceRejected(){return referenceRejected;}
    }
    /** Only failures that can safely retry the same frozen download carry this type. */
    public static final class TemporarilyUnavailable extends IllegalArgumentException {
        private final long minimumDelayMillis;
        public TemporarilyUnavailable(String message) { this(message, 0); }
        public TemporarilyUnavailable(String message, long minimumDelayMillis) {
            super(message); this.minimumDelayMillis = Math.max(0, minimumDelayMillis);
        }
        public long minimumDelayMillis() { return minimumDelayMillis; }
    }
    private record Source(long chatId,long messageId,long botId,boolean bot,boolean main) { }
    public List<FrozenFile> freeze(Post post, List<Media> selected, long session) {
        if(!selected.isEmpty()&&selected.stream().allMatch(media->"BOT".equals(media.source())))return freezeBot(post,selected,session);
        var files = new ArrayList<FrozenFile>();
        for (var media : selected) {
            // Preparation size must not consume the next file's verification budget.
            long deadline = clock.getAsLong() + TimeUnit.SECONDS.toNanos(60);
            check(post.accountId(), session);var frozen=resolve(post,media,session,deadline).file();var current=frozen.media();var ref=current.message();
            if("BOT".equals(media.source())&&(frozen.size()!=media.size()||!Objects.equals(frozen.fileName(),media.fileName())))
                throw new IllegalArgumentException("源文件已变化，请刷新机器人回复并重新核对所选媒体");
            if(!"BOT".equals(media.source())&&(ref.link()==null||ref.link().isBlank())){
                String link=messageLink(post.accountId(),session,ref,deadline);
                if(link!=null)frozen=new FrozenFile(new Media(current.key(),new MessageRef(ref.chatId(),ref.messageId(),link),current.source(),current.type(),current.fileName(),current.size(),current.caption()),frozen.uniqueId(),frozen.size(),frozen.fileName());
            }files.add(frozen);
        }
        return List.copyOf(files);
    }
    /** Uses only file identities captured from this resource's verified incoming bot replies. */
    public List<FrozenFile> freezeObservedBot(Post post,List<Media> selected,Map<String,FrozenFile> observed,long session){
        check(post.accountId(),session);
        if(selected.isEmpty())return List.of();
        var sources=selected.stream().map(media->source(post,media)).toList();
        for(int index=0;index<selected.size();index++){
            var media=selected.get(index);if(!sources.get(index).bot())throw new IllegalArgumentException("所选文件不是本次机器人媒体");
            var captured=observed.get(media.key());if(captured!=null)validateCaptured(media,captured);
        }
        verifyChat(post,sources.getFirst(),session,clock.getAsLong()+TimeUnit.SECONDS.toNanos(45));
        var byKey=new LinkedHashMap<String,FrozenFile>();var legacy=new ArrayList<Media>();
        for(var media:selected){
            var captured=observed.get(media.key());
            if(captured==null){legacy.add(media);continue;}
            retainedFile(post,captured,session);byKey.put(media.key(),captured);
        }
        if(!legacy.isEmpty())for(var frozen:freezeBot(post,legacy,session))byKey.put(frozen.media().key(),frozen);
        verifyChat(post,sources.getFirst(),session,clock.getAsLong()+TimeUnit.SECONDS.toNanos(45));
        check(post.accountId(),session);return selected.stream().map(media->byKey.get(media.key())).toList();
    }
    private static void validateCaptured(Media selected,FrozenFile captured){
        var reference=captured.botReference();
        if(!selected.equals(captured.media())||!"BOT".equals(selected.source())||captured.size()<=0||captured.size()!=selected.size()
                ||!Objects.equals(captured.fileName(),selected.fileName())||captured.uniqueId()==null||captured.uniqueId().isBlank()||captured.uniqueId().length()>1024
                ||reference==null||reference.fileId()<=0||reference.remoteId()!=null&&(reference.remoteId().length()>8192||reference.remoteId().startsWith("http://")||reference.remoteId().startsWith("https://")))
            throw sourceFailure(selected,"已读取的文件身份与本次选择不符，请重新读取机器人资源");
    }
    /** Called while the owned, non-protected bot reply is still available. No native mutation is made. */
    static FrozenFile observeBotFile(String account,BotReader.Target target,TdApi.Message message,Media selected){
        if(message==null||selected==null||selected.message()==null||!"BOT".equals(selected.source())
                ||!target.chatId().equals(Long.toString(message.chatId))||message.isOutgoing||message.isChannelPost
                ||!(message.senderId instanceof TdApi.MessageSenderUser sender)||!target.botUserId().equals(Long.toString(sender.userId))
                ||message.selfDestructType!=null||message.selfDestructIn>0||message.ephemeralContent!=null
                ||!selected.message().chatId().equals(target.chatId())||!selected.message().messageId().equals(Long.toString(message.id))
                ||!selected.key().equals(account+":"+target.chatId()+":"+message.id+":0"))return null;
        var mapped=MediaMapper.map(account,message,"BOT",selected.message(),new ArrayList<>());
        var file=primaryFile(message);
        if(!mapped.contains(selected)||!validFile(file)||file.size!=selected.size())return null;
        String remote=file.remote.id;
        if(remote!=null&&(remote.length()>8192||remote.startsWith("http://")||remote.startsWith("https://")))return null;
        return new FrozenFile(selected,file.remote.uniqueId,file.size,selected.fileName(),new BotFileReference(file.id,remote));
    }
    private List<FrozenFile> freezeBot(Post post,List<Media> selected,long session){
        check(post.accountId(),session);
        // Resolve only explicitly observed IDs. Never expand a batch through chat history.
        var sources=selected.stream().map(media->source(post,media)).toList();
        verifyChat(post,sources.getFirst(),session,clock.getAsLong()+TimeUnit.SECONDS.toNanos(45));
        var files=new ArrayList<FrozenFile>();
        for(int offset=0;offset<selected.size();offset+=BOT_MESSAGE_BATCH){
            int count=Math.min(BOT_MESSAGE_BATCH,selected.size()-offset);
            long[] ids=new long[count];for(int index=0;index<count;index++)ids[index]=sources.get(offset+index).messageId();
            TdApi.Messages batch;
            try{batch=call(post.accountId(),session,new TdApi.GetMessages(sources.getFirst().chatId(),ids),deadline());}
            catch(TemporarilyUnavailable failure){throw failure;}
            catch(RuntimeException failure){check(post.accountId(),session);throw new IllegalArgumentException("核对机器人媒体失败：包含文件“"+safeName(selected.get(offset))+"”的这批消息读取未完成，请稍后重新核对；未创建下载任务");}
            if(batch==null||batch.messages==null||batch.messages.length!=count)
                throw sourceFailure(selected.get(offset),"机器人消息批次回执不完整，请重新核对；未创建下载任务");
            for(int index=0;index<count;index++){
                check(post.accountId(),session);var media=selected.get(offset+index);
                var frozen=resolveMessage(post,media,sources.get(offset+index),batch.messages[index],session,deadline()).file();
                if(frozen.size()!=media.size()||!Objects.equals(frozen.fileName(),media.fileName()))
                    throw sourceFailure(media,"源文件已变化，请刷新机器人回复并重新核对所选媒体");
                files.add(frozen);
            }
        }
        // Scope identity is shared within this preparation only and must still hold at completion.
        verifyChat(post,sources.getFirst(),session,clock.getAsLong()+TimeUnit.SECONDS.toNanos(45));
        check(post.accountId(),session);return List.copyOf(files);
    }
    public String messageLink(String accountId,long session,MessageRef ref){return messageLink(accountId,session,ref,deadline());}
    private String messageLink(String accountId,long session,MessageRef ref,long deadline){
        try{
            var result=call(accountId,session,new TdApi.GetMessageLink(Long.parseLong(ref.chatId()),Long.parseLong(ref.messageId()),0,0,"",false,true),deadline);
            if(result==null||result.link==null)return null;var uri=java.net.URI.create(result.link);
            return "https".equals(uri.getScheme())&&java.util.Set.of("t.me","telegram.me").contains(uri.getHost())?result.link:null;
        }catch(RuntimeException unavailable){check(accountId,session);return null;}
    }
    public Resolved resolve(Post post, FrozenFile expected, long session) {
        if("BOT".equals(expected.media().source())&&expected.botReference()!=null){
            check(post.accountId(),session);var source=source(post,expected.media());validateCaptured(expected.media(),expected);
            verifyChat(post,source,session,clock.getAsLong()+TimeUnit.SECONDS.toNanos(45));
            var result=retainedFile(post,expected,session);check(post.accountId(),session);return result;
        }
        var fresh = resolve(post, expected.media(), session, clock.getAsLong() + TimeUnit.SECONDS.toNanos(45));
        if (!expected.uniqueId().equals(fresh.file().uniqueId()) || expected.size() != fresh.file().size()) throw new IllegalArgumentException("源文件已变化，请重新预览并创建新任务；原任务快照保留");
        return new Resolved(expected, fresh.fileId());
    }
    private Resolved retainedFile(Post post,FrozenFile expected,long session){
        var reference=expected.botReference();RuntimeException referenceFailure=null;
        try{
            var file=call(post.accountId(),session,new TdApi.GetFile(reference.fileId()),deadline());
            if(matches(file,expected)&&file.id==reference.fileId())return new Resolved(expected,file.id);
        }catch(TemporarilyUnavailable failure){throw failure;}
        catch(IllegalArgumentException failure){check(post.accountId(),session);referenceFailure=failure;}
        if(reference.remoteId()!=null&&!reference.remoteId().isBlank())try{
            var file=call(post.accountId(),session,new TdApi.GetRemoteFile(reference.remoteId(),null),deadline());
            if(matches(file,expected))return new Resolved(expected,file.id);
        }catch(TemporarilyUnavailable failure){throw failure;}
        catch(IllegalArgumentException failure){check(post.accountId(),session);referenceFailure=failure;}
        // A reference can expire. Refresh from the original source only when the retained handle fails.
        try{
            var fresh=resolve(post,expected.media(),session,clock.getAsLong()+TimeUnit.SECONDS.toNanos(45));
            if(expected.uniqueId().equals(fresh.file().uniqueId())&&expected.size()==fresh.file().size())return new Resolved(expected,fresh.fileId());
        }catch(TemporarilyUnavailable failure){throw failure;}
        catch(IllegalArgumentException failure){
            check(post.accountId(),session);
            if(referenceFailure instanceof FileReferenceExpired)throw referenceFailure;
            throw sourceFailure(expected.media(),"已读取的文件引用暂不可用，原消息也未能刷新，请重新让机器人发送该文件后重试");
        }
        throw sourceFailure(expected.media(),"原文件身份或大小已变化，请重新读取机器人资源");
    }
    private static boolean matches(TdApi.File file,FrozenFile expected){return validFile(file)&&expected.uniqueId().equals(file.remote.uniqueId)&&expected.size()==file.size;}
    private Resolved resolve(Post post, Media selected, long session, long deadline) {
        check(post.accountId(), session);var source=source(post,selected);
        verifyChat(post,source,session,deadline);
        var message=call(post.accountId(),session,new TdApi.GetMessage(source.chatId(),source.messageId()),deadline);
        return resolveMessage(post,selected,source,message,session,deadline);
    }
    private static Source source(Post post,Media selected){
        var ref = selected.message();
        if (ref == null) throw new IllegalArgumentException("下载媒体身份无效");
        long chatId = Long.parseLong(ref.chatId()), messageId = Long.parseLong(ref.messageId());
        boolean bot = "BOT".equals(selected.source()), main = "MAIN".equals(selected.source());
        long botId = bot ? botIdentity(post, ref) : 0;
        if(bot&&!Objects.equals(selected.key(),post.accountId()+":"+ref.chatId()+":"+ref.messageId()+":0"))throw new IllegalArgumentException("机器人媒体身份与当前账号或消息不符");
        if (!bot && (main ? !post.channelId().equals(ref.chatId()) || post.mainMessages().stream().noneMatch(m -> same(m, ref))
                : !"COMMENT".equals(selected.source()) || !Objects.equals(post.discussionChatId(), ref.chatId())
                    || Objects.equals(post.discussionRootMessageId(), ref.messageId()) || post.discussionRoots().stream().anyMatch(m -> same(m, ref)))) {
            throw new IllegalArgumentException("媒体不属于当前主帖下载范围");
        }
        return new Source(chatId,messageId,botId,bot,main);
    }
    private void verifyChat(Post post,Source source,long session,long deadline){
        var chat = call(post.accountId(), session, new TdApi.GetChat(source.chatId()), deadline);
        if (chat == null || chat.id != source.chatId() || chat.hasProtectedContent) throw new IllegalArgumentException("来源不可访问或限制保存内容");
        if (source.bot()) {
            if (!(chat.type instanceof TdApi.ChatTypePrivate type) || type.userId != source.botId()) throw new IllegalArgumentException("机器人下载来源不是原私聊");
            var user = call(post.accountId(), session, new TdApi.GetUser(source.botId()), deadline);
            if (user == null || user.id != source.botId() || !(user.type instanceof TdApi.UserTypeBot)) throw new IllegalArgumentException("机器人身份已变化，请重新预览");
        }
    }
    private Resolved resolveMessage(Post post,Media selected,Source source,TdApi.Message message,long session,long deadline){
        var ref=selected.message();long chatId=source.chatId(),messageId=source.messageId(),botId=source.botId();boolean bot=source.bot(),main=source.main();
        if (message == null)
            throw sourceFailure(selected, "原消息不可读取，且没有可用的已保存文件引用；请重新让机器人返回该文件后再读取");
        if (message.id != messageId || message.chatId != chatId)
            throw sourceFailure(selected, "机器人消息回执身份不符，请重新读取本次资源");
        if (message.isChannelPost != main || main && !Long.toString(message.mediaAlbumId).equals(post.albumId() == null ? "0" : post.albumId()))
            throw sourceFailure(selected, "所选消息已不属于原机器人私聊，请重新读取本次资源");
        if (message.selfDestructType != null || message.selfDestructIn > 0)
            throw sourceFailure(selected, "该消息为阅后即焚内容，不支持下载");
        if (message.ephemeralContent != null)
            throw sourceFailure(selected, "该消息包含仅当前用户可见的替代内容，当前尚不支持下载");
        // Bot replies use the explicitly observed private-message scope and the original-file transfer result.
        // Keep the existing save-property gate for channel posts and discussion media.
        if (!bot) {
            var properties = call(post.accountId(), session, new TdApi.GetMessageProperties(chatId, messageId), deadline);
            if (properties == null || !properties.canBeSaved)
                throw new IllegalArgumentException("源消息不可保存或身份已变化，请重新核对");
        }
        if (bot && (message.isOutgoing || !(message.senderId instanceof TdApi.MessageSenderUser sender) || sender.userId != botId))
            throw new IllegalArgumentException("源媒体不是原机器人发来的消息");
        if (!bot && !main && message.topicId instanceof TdApi.MessageTopicThread thread
                && !Long.toString(thread.messageThreadId).equals(post.discussionRootMessageId())
                && post.discussionRoots().stream().noneMatch(root -> root.messageId().equals(Long.toString(thread.messageThreadId)))) {
            throw new IllegalArgumentException("源消息不属于原评论线程");
        }
        var mapped = MediaMapper.map(post.accountId(), message, selected.source(), ref, new ArrayList<>());
        var media = mapped.stream().filter(m -> m.key().equals(selected.key()) && m.type().equals(selected.type())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("源媒体类型已变化，请重新预览"));
        TdApi.File file=primaryFile(message);
        if (!validFile(file)) {
            throw new IllegalArgumentException("未取得可校验的原文件大小和稳定身份，未开始下载");
        }
        return new Resolved(new FrozenFile(media, file.remote.uniqueId, file.size, media.fileName()), file.id);
    }
    private static boolean validFile(TdApi.File file){return file!=null&&file.id>0&&file.size>0&&file.remote!=null&&file.remote.uniqueId!=null&&!file.remote.uniqueId.isBlank()&&file.remote.uniqueId.length()<=1024;}
    private static TdApi.File primaryFile(TdApi.Message message){
        if(message==null||message.content==null)return null;
        return switch (message.content) {
            case TdApi.MessagePhoto m -> {
                if (m.isSecret || m.photo == null || m.photo.sizes == null) yield null;
                yield java.util.Arrays.stream(m.photo.sizes).filter(size -> size != null && size.photo != null && size.width > 0 && size.height > 0)
                        .max(Comparator.comparingLong(size -> (long) size.width * size.height)).map(size -> size.photo).orElse(null);
            }
            case TdApi.MessageVideo m -> m.isSecret || m.video == null ? null : m.video.video;
            case TdApi.MessageDocument m -> m.document == null ? null : m.document.document;
            case TdApi.MessageAudio m -> m.audio == null ? null : m.audio.audio;
            case TdApi.MessageVoiceNote m -> m.voiceNote == null ? null : m.voiceNote.voice;
            case TdApi.MessageVideoNote m -> m.videoNote == null ? null : m.videoNote.video;
            case TdApi.MessageAnimation m -> m.isSecret || m.animation == null ? null : m.animation.animation;
            default -> null;
        };
    }
    private static IllegalArgumentException sourceFailure(Media selected, String botReason) {
        if (!"BOT".equals(selected.source())) return new IllegalArgumentException("源消息不可保存或身份已变化，请重新核对");
        return new IllegalArgumentException("文件“" + safeName(selected) + "”：" + botReason);
    }
    private static String safeName(Media selected){
        String name = selected.fileName() == null ? "所选媒体" : selected.fileName().replaceAll("\\p{Cntrl}", " ").strip();
        if (name.isEmpty()) name = "所选媒体";
        if (name.length() > 160) name = name.substring(0, 160) + "…";
        return name;
    }
    private static long botIdentity(Post post, MessageRef ref) {
        String[] key = post.key() == null ? new String[0] : post.key().split(":", -1);
        if (key.length != 4 || !"bot".equals(key[0]) || !positiveId(key[1]) || !positiveId(key[2]) || !positiveId(key[3])
                || !key[2].equals(post.channelId()) || !key[3].equals(post.mainMessageId()) || !post.channelId().equals(ref.chatId())
                || !positiveId(ref.messageId()) || post.mainMessages().stream().noneMatch(message -> same(message, ref)))
            throw new IllegalArgumentException("媒体不属于本次已观察的机器人下载范围");
        try { return Long.parseLong(key[1]); } catch (NumberFormatException invalid) { throw new IllegalArgumentException("机器人身份无效"); }
    }
    private static boolean positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(value) > 0; } catch (NumberFormatException invalid) { return false; }
    }
    public Status status(String accountId, long session, int id, String uniqueId, long size) {
        var file = checked(accountId, session, id, uniqueId, size);
        if (file.local == null) throw new IllegalArgumentException("下载状态暂不可用");
        return new Status(Math.min(size, Math.max(0, file.local.downloadedSize)), file.local.isDownloadingCompleted,
                file.local.isDownloadingActive, file.local.canBeDownloaded);
    }
    public void start(String accountId, long session, int id, String uniqueId, long size) {
        startAt(accountId,session,id,uniqueId,size,0);
    }
    /** Retains the final result without applying a metadata-read timeout or blocking the caller. */
    public void startAt(String accountId,long session,int id,String uniqueId,long size,long offset){
        validateOffset(size,offset);checked(accountId,session,id,uniqueId,size);
        CompletableFuture<TdApi.File> pending;
        try{pending=Objects.requireNonNull(gateway.download(session,new TdApi.DownloadFile(id,1,offset,0,true)));}
        catch(RuntimeException failure){pending=CompletableFuture.failedFuture(failure);}
        check(accountId,session);
        downloads.put(new DownloadKey(accountId,session,id,uniqueId,size),pending);
    }
    /** Pending requests are observed without joining them; final errors never masquerade as a stall. */
    public void checkDownload(String accountId,long session,int id,String uniqueId,long size){
        check(accountId,session);
        var pending=downloads.get(new DownloadKey(accountId,session,id,uniqueId,size));
        if(pending==null||!pending.isDone())return;
        try{var file=pending.join();check(accountId,session);verify(file,id,uniqueId,size);}
        catch(CompletionException failure){
            check(accountId,session);throw classified(failure,new TdApi.DownloadFile(id,1,0,0,true),true);
        }
        catch(java.util.concurrent.CancellationException failure){
            check(accountId,session);throw new TemporarilyUnavailable("Telegram 原文件下载已中断，可稍后继续");
        }
    }
    /** Final publication waits for a real terminal receipt, while pause and session checks stay responsive. */
    public void awaitDownload(String accountId,long session,int id,String uniqueId,long size,Runnable check){
        long began=clock.getAsLong(),budget=TimeUnit.MINUTES.toNanos(2);
        for(;;){
            check.run();check(accountId,session);
            var pending=downloads.get(new DownloadKey(accountId,session,id,uniqueId,size));
            if(pending==null)return;
            if(pending.isDone()){
                checkDownload(accountId,session,id,uniqueId,size);check.run();check(accountId,session);return;
            }
            long elapsed=clock.getAsLong()-began;
            if(elapsed>=budget)throw new TemporarilyUnavailable("Telegram 最终下载回执等待超时，已接收文件字节保留，可稍后继续核对");
            try{pending.get(Math.min(TimeUnit.MILLISECONDS.toNanos(200),budget-Math.max(0,elapsed)),TimeUnit.NANOSECONDS);}
            catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException|java.util.concurrent.CancellationException settledOrWaiting){
                // Re-enter through the same pause/session checks and final error classifier.
            }
            catch(InterruptedException interrupted){
                Thread.currentThread().interrupt();check.run();check(accountId,session);
                throw new IllegalArgumentException("下载回执等待已中断，已接收文件字节保留");
            }
        }
    }
    /** Drops only this reader's bookkeeping; it never cancels a native or shared download. */
    public void forgetDownload(String accountId,long session,int id,String uniqueId,long size){
        downloads.remove(new DownloadKey(accountId,session,id,uniqueId,size));
    }
    /** Checks the known source when available, retaining the verified capture when it is absent. */
    public int startObservedBot(Post post,FrozenFile expected,long session,int fileId){
        return startObservedBotAt(post,expected,session,fileId,0);
    }
    public int startObservedBotAt(Post post,FrozenFile expected,long session,int fileId,long offset){
        validateOffset(expected.size(),offset);
        var source=observedSource(post,expected,session);
        var fresh=observedMessage(post,expected,source,session);
        int id=fresh==null?fileId:fresh.fileId();
        verifyChat(post,source,session,clock.getAsLong()+TimeUnit.SECONDS.toNanos(45));
        startAt(post.accountId(),session,id,expected.uniqueId(),expected.size(),offset);return id;
    }
    /** The engine owns the single recovery allowance; this method neither starts nor retries a file. */
    public int refreshObservedBot(Post post,FrozenFile expected,long session,int fileId){
        var source=observedSource(post,expected,session);
        var fresh=observedMessage(post,expected,source,session);
        if(fresh==null)throw new TemporarilyUnavailable("原文件下载失败，原消息暂不可读取，无法核对新文件引用；请让机器人重新返回该文件后继续");
        verifyChat(post,source,session,clock.getAsLong()+TimeUnit.SECONDS.toNanos(45));
        checked(post.accountId(),session,fresh.fileId(),expected.uniqueId(),expected.size());
        return fresh.fileId();
    }
    private Source observedSource(Post post,FrozenFile expected,long session){
        check(post.accountId(),session);var source=source(post,expected.media());
        if(!source.bot()||expected.botReference()==null)throw new IllegalArgumentException("没有已读取的机器人文件引用");
        validateCaptured(expected.media(),expected);verifyChat(post,source,session,clock.getAsLong()+TimeUnit.SECONDS.toNanos(45));
        return source;
    }
    private Resolved observedMessage(Post post,FrozenFile expected,Source source,long session){
        final TdApi.Message message;
        try{message=call(post.accountId(),session,new TdApi.GetMessage(source.chatId(),source.messageId()),deadline());}
        catch(MessageUnavailable missing){return null;}
        if(message==null)return null;
        var fresh=resolveMessage(post,expected.media(),source,message,session,deadline());
        if(!expected.uniqueId().equals(fresh.file().uniqueId())||expected.size()!=fresh.file().size())
            throw sourceFailure(expected.media(),"原文件身份或大小已变化，请重新读取机器人资源");
        return fresh;
    }
    /** Returns only a continuous, native-confirmed interval beginning exactly at the checkpoint. */
    public long availableBytes(String accountId,long session,int id,String uniqueId,long size,long offset){
        validateOffset(size,offset);var file=checked(accountId,session,id,uniqueId,size);
        if(file.local==null)throw new IllegalArgumentException("下载状态暂不可用");
        if(offset==size)return 0;
        if(file.local.isDownloadingCompleted){
            if(file.local.downloadedSize<size)throw new IllegalArgumentException("原文件完整状态与可读字节不符");
            return size-offset;
        }
        var prefix=call(accountId,session,new TdApi.GetFileDownloadedPrefixSize(id,offset),deadline());
        if(prefix==null||prefix.size<0||prefix.size>size-offset)throw new IllegalArgumentException("原文件连续可读范围无效");
        return prefix.size;
    }
    private static void validateOffset(long size,long offset){
        if(size<=0||offset<0||offset>size)throw new IllegalArgumentException("文件读取范围无效");
    }
    public byte[] chunk(String accountId, long session, int id, String uniqueId, long size, long offset, int count) {
        if (offset < 0 || count < 1 || count > 1024 * 1024 || offset > size - count) throw new IllegalArgumentException("文件读取范围无效");
        if(availableBytes(accountId,session,id,uniqueId,size,offset)<count)throw new IllegalArgumentException("客户端尚未确认所需连续文件区间可读");
        var part = call(accountId, session, new TdApi.ReadFilePart(id, offset, count), deadline());
        if (part == null || part.data == null || part.data.length != count) throw new IllegalArgumentException("原文件读取长度不符，未发布目标文件");
        return part.data;
    }
    public boolean release(String accountId, long session, int id, String uniqueId, boolean delete) {
        var current = call(accountId, session, new TdApi.GetFile(id), deadline());
        if (current == null || current.id != id || current.remote == null || !uniqueId.equals(current.remote.uniqueId)) return false;
        call(accountId, session, new TdApi.CancelDownloadFile(id, false), deadline());
        if (!delete) return true;
        call(accountId, session, new TdApi.DeleteFile(id), deadline());
        var after = call(accountId, session, new TdApi.GetFile(id), deadline());
        return after != null && after.id == id && after.remote != null && uniqueId.equals(after.remote.uniqueId) && after.local != null
                && !after.local.isDownloadingActive && !after.local.isDownloadingCompleted && after.local.downloadedSize == 0;
    }
    /** Caller must authorize ownership. Stop this exact file without deleting partial bytes. */
    public void cancel(String accountId,long session,int id,String uniqueId,long size){
        var current=checked(accountId,session,id,uniqueId,size);
        if(current.local==null)throw new IllegalArgumentException("下载状态暂不可用");
        if(!current.local.isDownloadingCompleted)call(accountId,session,new TdApi.CancelDownloadFile(id,false),deadline());
    }
    private TdApi.File checked(String accountId, long session, int id, String uniqueId, long size) {
        var file = call(accountId, session, new TdApi.GetFile(id), deadline()); verify(file, id, uniqueId, size); return file;
    }
    private static void verify(TdApi.File file, int id, String uniqueId, long size) {
        if (file == null || file.id != id || file.remote == null || !uniqueId.equals(file.remote.uniqueId) || file.size != size) throw new IllegalArgumentException("原文件身份或大小已变化，下载已暂停");
    }
    public void check(String expectedAccount, long session) {
        if (Thread.currentThread().isInterrupted() || !Objects.equals(expectedAccount, account.get()) || session != generation.getAsLong()) throw new IllegalArgumentException("账号或会话已变化，下载已暂停");
    }
    private static boolean same(MessageRef a, MessageRef b) { return a.chatId().equals(b.chatId()) && a.messageId().equals(b.messageId()); }
    private long deadline() { return clock.getAsLong() + TimeUnit.SECONDS.toNanos(16); }
    private static final class FileReferenceExpired extends IllegalArgumentException { FileReferenceExpired(){super("文件引用已失效，请重新让机器人发送该文件");} }
    private static final class MessageUnavailable extends IllegalArgumentException { MessageUnavailable(){super("原消息暂不可读取");} }
    private <R extends TdApi.Object> R call(String accountId, long session, TdApi.Function<R> request, long deadline) {
        check(accountId, session); long remaining = deadline - clock.getAsLong();
        if (remaining <= 0) throw new TemporarilyUnavailable("Telegram 文件读取超时");
        try { R result = gateway.read(session, request).get(Math.min(remaining, TimeUnit.SECONDS.toNanos(16)), TimeUnit.NANOSECONDS); check(accountId, session); return result; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalArgumentException("下载已中断，可在任务中心继续"); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            // A stale session or an explicit disconnect must never be reclassified as a network retry.
            check(accountId, session);
            throw classified(failure,request,false);
        }
    }
    private static IllegalArgumentException classified(Throwable failure,TdApi.Function<?> request,boolean finalDownload){
        Throwable reason=failure;while(reason.getCause()!=null)reason=reason.getCause();
        if(request instanceof TdApi.ReadFilePart&&reason instanceof it.tdlight.client.TelegramError nativeError&&nativeError.getErrorCode()==400&&"File is not inside the cache".equals(nativeError.getErrorMessage()))
            return new IllegalArgumentException("该文件仍引用上传输入副本，暂时无法回读；请先核对对应上传与归档，再继续下载");
        if(reason instanceof it.tdlight.client.TelegramError nativeError){
            int code=nativeError.getErrorCode();String message=nativeError.getErrorMessage();
            if(request instanceof TdApi.GetMessage&&code==404)return new MessageUnavailable();
            if(code==400&&message!=null&&message.contains("FILE_REFERENCE_"))return finalDownload?new DownloadFailed(true):new FileReferenceExpired();
            if(finalDownload&&code==400&&"File download has failed or was canceled".equals(message))return new DownloadFailed(false);
            if(code==429)return new TemporarilyUnavailable("Telegram 暂时限制请求频率",retryAfterMillis(message));
            if(code==408||code>=500&&code<=599)return new TemporarilyUnavailable("Telegram 服务暂时不可用");
        }
        if(reason instanceof java.util.concurrent.TimeoutException||reason instanceof java.io.IOException)
            return new TemporarilyUnavailable("Telegram 文件读取超时或连接暂时中断");
        if(reason instanceof IllegalArgumentException&&!(reason instanceof it.tdlight.client.TelegramError))
            return new IllegalArgumentException("账号、会话或原文件状态已变化，下载已暂停");
        return new IllegalArgumentException("Telegram 文件读取未完成，请检查连接后继续任务");
    }
    private static long retryAfterMillis(String message){
        if(message!=null){var match=java.util.regex.Pattern.compile("(?i)(?:retry after\\s+|FLOOD_WAIT_)([0-9]+)").matcher(message);
            if(match.find())try{return TimeUnit.SECONDS.toMillis(Long.parseLong(match.group(1)));}catch(NumberFormatException excessive){return Long.MAX_VALUE;}}
        return TimeUnit.MINUTES.toMillis(5);
    }
}
