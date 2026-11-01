package io.github.telegramenhance.transfer;

import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.telegram.BotReader;
import io.github.telegramenhance.telegram.DownloadFileReader;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Bot pagination is explicit, bounded and separate from library/channel authorization. */
@Service
public final class BotDownloadService {
    private static final long TTL = TimeUnit.MINUTES.toNanos(10);
    private final ResourceService work;
    private final BotReader reader;
    private final TelegramService telegram;
    private final DownloadFileReader files;
    private final DownloadRepository repository;
    private final DownloadPaths paths;
    private final LongSupplier clock;
    // Only accessed on ResourceService's existing single bounded worker.
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public BotDownloadService(ResourceService work, BotReader reader, TelegramService telegram,
            DownloadFileReader files, DownloadRepository repository, DownloadPaths paths) {
        this(work, reader, telegram, files, repository, paths, System::nanoTime);
    }
    BotDownloadService(ResourceService work, BotReader reader, TelegramService telegram,
            DownloadFileReader files, DownloadRepository repository, DownloadPaths paths, LongSupplier clock) {
        this.work=work;this.reader=reader;this.telegram=telegram;this.files=files;this.repository=repository;this.paths=paths;this.clock=clock;
    }
    public record Button(String id, String text, String kind, String messageId, int row, int column, boolean supported) { }
    public record Message(String id, String text, List<Media> media, List<Button> buttons) { }
    public record WaitInfo(int waitSeconds,int timeoutSeconds,long elapsedMs,long quietMs,long remainingMs,boolean progressObserved,boolean timedOut) { }
    public record View(String id,long revision,BotReader.Target bot,List<Message> messages,List<Media> media,
            String controlMessageId,String recommendedButtonId,String paginationStatus,Integer currentPage,Integer totalPages,
            String state,String warning,String nextHistoryCursor,boolean historyEnd,@com.fasterxml.jackson.annotation.JsonProperty("wait") WaitInfo waitInfo) { }
    public record Open(String source) { @Override public String toString(){return "BotOpen[redacted]";} }
    public record Position(long revision) { }
    public record Control(long revision,String messageId) { }
    public record Press(String operationId,long revision,String buttonId,Integer waitSeconds,Integer timeoutSeconds) {
        public Press(String operationId,long revision,String buttonId){this(operationId,revision,buttonId,null,null);}
        @Override public String toString(){return "BotPress[redacted]";}
    }
    public record WaitRequest(long revision,Integer waitSeconds,Integer timeoutSeconds) { }
    public record Create(String operationId,String sourceId,long revision,List<String> mediaKeys,String targetDirectory) {
        @Override public String toString(){return "BotDownloadCreate[redacted]";}
    }
    public record Lookup(TaskView task) { }
    private record Session(String account,long generation) { }
    private record Grant(String messageId,BotReader.Button raw,Button view) { }
    private record ReplyBoundary(String control,String controlFingerprint,String newestMessage) { }
    private record WaitSettings(int waitSeconds,int timeoutSeconds) { }
    private static final class Waiting {
        final String control,newestMessage;
        final Map<String,String> seenMedia;
        final Map<String,String> seenEnds=new HashMap<>();
        String controlFingerprint;
        int waitSeconds,timeoutSeconds;
        long startedAt,quietSince;
        boolean progressObserved,timedOut;
        Waiting(String control,String controlFingerprint,Map<String,String> media,String newestMessage,WaitSettings settings,long now){
            this.control=control;this.controlFingerprint=controlFingerprint;this.seenMedia=new HashMap<>(media);this.newestMessage=newestMessage;
            restart(settings,now);
        }
        void restart(WaitSettings settings,long now){waitSeconds=settings.waitSeconds();timeoutSeconds=settings.timeoutSeconds();startedAt=now;quietSince=now;timedOut=false;}
    }
    private static final class Entry {
        final String id=UUID.randomUUID().toString(); final Session session; final BotReader.Target target;
        final LinkedHashMap<String,Media> media=new LinkedHashMap<>();
        final Map<String,String> operations=new HashMap<>();
        final Set<String> clicked=new HashSet<>();
        Map<String,Grant> grants=new HashMap<>();List<Message> messages=List.of();
        Map<String,BotPagination.Result> pagination=new HashMap<>();
        long revision=1,touched;String signature="",windowCursor,historyCursor,control,state="READY",warning,newestMessage="0";
        boolean historyEnd;
        Waiting waiting;
        ReplyBoundary boundary;
        String scopeLower,initialUpper,requestBoundary,openingHead,appendAfter;
        final Set<String> admittedMessages=new HashSet<>();
        Entry(Session session,BotReader.Target target,long now){this.session=session;this.target=target;this.touched=now;}
    }
    private Session capture(){return new Session(telegram.accountId(),telegram.sessionGeneration());}
    private void current(Session session){if(!Objects.equals(session.account(),telegram.accountId())||session.generation()!=telegram.sessionGeneration())throw new IllegalArgumentException("账号或会话已变化，请重新打开机器人预览");}
    private <T> CompletableFuture<T> submit(Session session,Supplier<T> action){return work.readWork(()->{current(session);var result=action.get();current(session);return result;});}
    private static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    private static boolean positive(String value){return value!=null&&value.matches("[1-9][0-9]{0,18}")&&new java.math.BigInteger(value).bitLength()<64;}
    private void prune(){long now=clock.getAsLong();entries.values().removeIf(e->now-e.touched>=TTL);}
    private Entry entry(String id,Session session){prune();Entry e=entries.get(id);if(e==null||!e.session.equals(session))throw new IllegalArgumentException("机器人预览已过期，请重新读取会话");e.touched=clock.getAsLong();return e;}
    private static void revision(Entry e,long expected){if(expected!=e.revision)throw new IllegalArgumentException("预览已变化，请先刷新并核对机器人回复");}
    private static WaitSettings settings(Integer wait,Integer timeout,Waiting previous){
        int quiet=wait==null?(previous==null?5:previous.waitSeconds):wait;
        int limit=timeout==null?(previous==null?120:previous.timeoutSeconds):timeout;
        if(quiet<2||quiet>60||limit<15||limit>300||limit<2*quiet)throw new IllegalArgumentException("缓冲时间应为2至60秒，总等待时间应为15至300秒且至少为缓冲时间的两倍");
        return new WaitSettings(quiet,limit);
    }
    private static long elapsedMillis(long now,long start){return Math.max(0,now-start)/1_000_000;}
    private void timeout(Entry e,long now){
        if(e.waiting!=null&&!e.waiting.timedOut&&elapsedMillis(now,e.waiting.startedAt)>=e.waiting.timeoutSeconds*1000L){
            e.waiting.timedOut=true;e.warning="等待机器人回复已超时，已保留读取结果。可继续等待或手动核对，不会自动重复翻页。";e.revision++;
        }
    }

    public CompletableFuture<View> open(Open input){
        if(input==null||input.source()==null||input.source().isBlank()||input.source().length()>2048)throw new IllegalArgumentException("请填写机器人用户名、链接或会话ID");
        Session session=capture();return submit(session,()->{
            BotReader.Target target=reader.open(input.source(),session.generation());current(session);
            var snapshot=reader.history(target,null,session.generation());current(session);
            var scope=BotReplyScope.initial(snapshot.messages().stream().map(message->new BotReplyScope.Message(message.ref().messageId(),message.text(),
                    message.buttons().stream().map(button->new BotPagination.InputButton(button.row()+":"+button.column(),button.text(),"CALLBACK".equals(button.type()))).toList(),!message.media().isEmpty())).toList(),snapshot.requestBoundary());
            if(!"FIRST_PAGE".equals(scope.status()))throw new IllegalArgumentException(scope.reason());
            Entry e=new Entry(session,target,clock.getAsLong());e.scopeLower=scope.lowerExclusiveId();e.initialUpper=scope.upperInclusiveId();e.requestBoundary=snapshot.requestBoundary();e.openingHead=head(snapshot);
            var initial=snapshot.messages().stream().filter(message->after(message.ref().messageId(),scope.lowerExclusiveId())&&!after(message.ref().messageId(),scope.upperInclusiveId())).toList();
            initial.forEach(message->e.admittedMessages.add(message.ref().messageId()));
            applyScoped(e,new BotReader.Snapshot(snapshot.target(),initial,true,null,snapshot.requestBoundary()));
            prune();while(entries.size()>=4)entries.remove(entries.keySet().iterator().next());entries.put(e.id,e);return view(e);
        });
    }
    public CompletableFuture<View> refresh(String id){Session session=capture();return submit(session,()->{
        Entry e=entry(id,session);
        long now=clock.getAsLong();timeout(e,now);
        if(e.waiting!=null&&elapsedMillis(now,e.waiting.startedAt)<e.waiting.waitSeconds*1000L)return view(e);
        var snapshot=reader.history(e.target,null,session.generation());current(session);applyScoped(e,snapshot);
        now=clock.getAsLong();timeout(e,now);
        if(!"READY".equals(e.state)&&e.waiting!=null){
            observeProgress(e,now);
            if(!e.waiting.timedOut&&e.waiting.progressObserved&&elapsedMillis(now,e.waiting.quietSince)>=e.waiting.waitSeconds*1000L){
                e.state="READY";e.waiting=null;e.warning="已读取机器人回复并经过稳定缓冲，请核对媒体和分页位置。";e.revision++;
            }
        }
        return view(e);
    });}
    /** Reopens only the observation window. It never invokes or replays a bot callback. */
    public CompletableFuture<View> waitForReply(String id,WaitRequest input){
        if(input==null)throw new IllegalArgumentException("等待设置无效");
        Session session=capture();return submit(session,()->{
            Entry e=entry(id,session);revision(e,input.revision());
            if(!Set.of("WAITING","NEEDS_REVIEW").contains(e.state)||e.waiting==null)throw new IllegalArgumentException("当前没有需要继续等待的机器人翻页");
            var settings=settings(input.waitSeconds(),input.timeoutSeconds(),e.waiting);
            e.waiting.restart(settings,clock.getAsLong());e.warning="已重新开始等待机器人回复，不会再次点击翻页按钮。";e.revision++;return view(e);
        });
    }
    public CompletableFuture<View> older(String id,Position input){
        throw new IllegalArgumentException("机器人下载只读取本次资源，不读取以前的聊天历史");
    }
    public CompletableFuture<View> control(String id,Control input){
        throw new IllegalArgumentException("分页消息由本次资源绑定，请重新打开资源第一页后读取");
    }
    public CompletableFuture<View> press(String id,Press input){
        if(input==null||!uuid(input.operationId())||!uuid(input.buttonId()))throw new IllegalArgumentException("翻页操作标识无效");
        var settings=settings(input.waitSeconds(),input.timeoutSeconds(),null);
        Session session=capture();return submit(session,()->{
            Entry e=entry(id,session);String identity=input.revision()+":"+input.buttonId()+":"+settings.waitSeconds()+":"+settings.timeoutSeconds();String old=e.operations.get(input.operationId());
            if(old!=null){if(!old.equals(identity))throw new IllegalArgumentException("相同翻页操作的参数已变化");return view(e);}
            revision(e,input.revision());Grant grant=e.grants.get(input.buttonId());
            if(!"READY".equals(e.state)||grant==null||!grant.view().supported()||!Objects.equals(e.control,grant.messageId()))throw new IllegalArgumentException("请选择当前分页消息中可用的按钮，待核对时不能重复点击");
            // Freeze a latest-message barrier without admitting older or unrequested replies as this resource.
            var barrier=reader.history(e.target,null,session.generation());current(session);
            var scopedBarrier=scoped(e,barrier);
            Entry baseline=new Entry(session,e.target,clock.getAsLong());apply(baseline,scopedBarrier);
            if(baseline.messages.stream().anyMatch(message->message.id().equals(e.control))&&!controlFingerprint(e,e.control).equals(controlFingerprint(baseline,e.control))){
                applyScoped(e,barrier);throw new IllegalArgumentException("所选机器人分页位置已经变化，请刷新回复并重新选择按钮");
            }
            var seenMedia=versions(e);seenMedia.putAll(versions(baseline));
            String newest=max(e.newestMessage,head(barrier));
            String action=controlFingerprint(e,e.control)+":"+DownloadPaths.hash(new TreeMap<>(seenMedia).toString())+":"+grant.raw().row()+":"+grant.raw().column();
            if(e.clicked.contains(action))throw new IllegalArgumentException("本轮回复的这个按钮已经点过，请读取新回复后继续");
            if(e.operations.size()>=100)throw new IllegalArgumentException("本次已操作100次，请核对位置后重新打开预览");
            // Intent precedes the only callback invocation. Unknown outcomes are never automatically replayed.
            String fingerprint=baseline.messages.stream().anyMatch(m->m.id().equals(e.control))?controlFingerprint(baseline,e.control):controlFingerprint(e,e.control);
            e.operations.put(input.operationId(),identity);e.clicked.add(action);e.boundary=new ReplyBoundary(e.control,fingerprint,newest);e.waiting=new Waiting(e.control,fingerprint,seenMedia,newest,settings,clock.getAsLong());e.state="NEEDS_REVIEW";e.warning="翻页结果待核对，请读取机器人回复，不会自动重复点击。";e.revision++;
            e.appendAfter=newest;
            try{
                reader.press(e.target,grant.messageId(),grant.raw(),session.generation());current(session);
                e.waiting.restart(settings,clock.getAsLong());e.state="WAITING";e.warning="已请求翻页，正在等待机器人回复及媒体稳定。";
            }catch(RuntimeException cause){current(session);e.waiting.restart(settings,clock.getAsLong());e.state="NEEDS_REVIEW";e.warning="机器人翻页结果未确认，已开始等待回复核对，不会自动重试。";}
            return view(e);
        });
    }
    private static boolean after(String id,String lower){return new java.math.BigInteger(id).compareTo(new java.math.BigInteger(lower))>0;}
    private static String max(String first,String second){return after(first,second)?first:second;}
    private static String head(BotReader.Snapshot snapshot){
        String head="0";for(var message:snapshot.messages())head=max(head,message.ref().messageId());return head;
    }
    private BotReader.Snapshot scoped(Entry e,BotReader.Snapshot snapshot){
        if(snapshot==null||!e.target.equals(snapshot.target()))throw new IllegalArgumentException("机器人回复身份无法确认");
        String request=snapshot.requestBoundary();
        if(request!=null&&(!positive(request)||after(request,e.requestBoundary==null?e.openingHead:e.requestBoundary))){
            entries.remove(e.id);
            throw new IllegalArgumentException("机器人已收到另一次请求，请重新打开本次资源第一页再读取");
        }
        var messages=snapshot.messages().stream().filter(message->after(message.ref().messageId(),e.scopeLower)
                &&(e.admittedMessages.contains(message.ref().messageId())||e.appendAfter==null&&!after(message.ref().messageId(),e.initialUpper)
                ||e.appendAfter!=null&&after(message.ref().messageId(),e.appendAfter))).toList();
        return new BotReader.Snapshot(snapshot.target(),messages,true,null,request);
    }
    private void applyScoped(Entry e,BotReader.Snapshot snapshot){
        var projected=scoped(e,snapshot);apply(e,projected);
        projected.messages().forEach(message->e.admittedMessages.add(message.ref().messageId()));
    }
    private static Set<String> ends(Entry e){return e.pagination.entrySet().stream().filter(v->"END".equals(v.getValue().status())).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    private static String controlFingerprint(Entry e,String control){
        var body=new StringBuilder(control==null?"":control);var pagination=e.pagination.get(control);
        if(pagination!=null)body.append(':').append(pagination.status()).append(':').append(pagination.current()).append(':').append(pagination.total());
        var navigation=new ArrayList<String>();
        e.messages.stream().filter(m->m.id().equals(control)).findFirst().ifPresent(m->{for(var button:m.buttons()){
            if(!Set.of("NEXT","WINDOW","PAGE","PREVIOUS").contains(button.kind()))continue;
            var grant=e.grants.get(button.id());
            navigation.add(button.kind()+":"+button.text().length()+":"+button.text()+":"+(grant==null?"":Base64.getEncoder().encodeToString(grant.raw().data())));
        }});
        // Advertising rows may move the navigation without changing its meaning. Native still verifies exact positions.
        Collections.sort(navigation);for(String button:navigation)body.append('|').append(button);
        return DownloadPaths.hash(body.toString());
    }
    private static Map<String,String> versions(Entry e){
        var versions=new HashMap<String,String>();
        for(var media:e.media.values())versions.put(media.key(),mediaVersion(media));
        return versions;
    }
    private static String mediaVersion(Media media){return DownloadPaths.hash(media.type()+"\n"+media.size()+"\n"+media.fileName());}
    private static void observeProgress(Entry e,long now){
        Waiting waiting=e.waiting;boolean changed=false;
        // Only this successfully read window proves a version observation. Older retained grants may be stale.
        for(var message:e.messages)for(var media:message.media()){
            String version=mediaVersion(media);
            if(!Objects.equals(waiting.seenMedia.put(media.key(),version),version))changed=true;
        }
        var control=e.pagination.get(waiting.control);
        boolean paginationVisible=control!=null&&(!"UNKNOWN".equals(control.status())||control.buttons().stream().anyMatch(button->Set.of("NEXT","WINDOW","PAGE","PREVIOUS").contains(button.kind())));
        if(paginationVisible&&e.messages.stream().anyMatch(m->m.id().equals(waiting.control))){
            String fingerprint=controlFingerprint(e,waiting.control);
            if(!fingerprint.equals(waiting.controlFingerprint)){changed=true;waiting.controlFingerprint=fingerprint;}
        }
        for(String id:ends(e))if(new java.math.BigInteger(id).compareTo(new java.math.BigInteger(waiting.newestMessage))>0){
            String fingerprint=controlFingerprint(e,id);
            if(!Objects.equals(waiting.seenEnds.put(id,fingerprint),fingerprint))changed=true;
        }
        // Missing old messages in a short/newer window do not count as changes or deletion evidence.
        if(changed){waiting.progressObserved=true;waiting.quietSince=now;}
    }
    private static boolean eligibleController(Entry e,String id){
        if(e.boundary==null||!"END".equals(e.pagination.get(id).status()))return true;
        return new java.math.BigInteger(id).compareTo(new java.math.BigInteger(e.boundary.newestMessage()))>0
                ||id.equals(e.boundary.control())&&!controlFingerprint(e,id).equals(e.boundary.controlFingerprint());
    }
    private static String signature(BotReader.Snapshot snapshot){
        var text=new StringBuilder(snapshot.target().chatId());
        for(var message:snapshot.messages()){
            text.append('\n').append(message.ref().messageId()).append(':').append(message.text());
            for(var media:message.media())text.append('|').append(media.key()).append(':').append(media.type()).append(':').append(media.size()).append(':').append(media.fileName()).append(':').append(media.caption());
            for(var button:message.buttons())text.append('|').append(button.row()).append(':').append(button.column()).append(':').append(button.text()).append(':').append(button.type()).append(':').append(Base64.getEncoder().encodeToString(button.data()==null?new byte[0]:button.data()));
        }
        return DownloadPaths.hash(text.toString());
    }
    private void apply(Entry e,BotReader.Snapshot snapshot){
        if(snapshot==null||!e.target.equals(snapshot.target())||snapshot.messages()==null||snapshot.messages().size()>50)throw new IllegalArgumentException("机器人回复身份无法确认");
        if(snapshot.nextCursor()!=null&&!positive(snapshot.nextCursor()))throw new IllegalArgumentException("机器人历史游标无效");
        var observed=new LinkedHashMap<>(e.media);var messages=new ArrayList<Message>();var grants=new HashMap<String,Grant>();var pagination=new HashMap<String,BotPagination.Result>();
        var messageIds=new HashSet<String>();int buttons=0;
        for(var raw:snapshot.messages()){
            if(raw.ref()==null||!e.target.chatId().equals(raw.ref().chatId())||!positive(raw.ref().messageId())||!messageIds.add(raw.ref().messageId())||raw.text()==null||raw.text().length()>8192||raw.buttons().size()>100)throw new IllegalArgumentException("机器人消息范围无法确认");
            var rawById=new LinkedHashMap<String,BotReader.Button>();var candidates=new ArrayList<BotPagination.InputButton>();
            for(var button:raw.buttons()){
                if(++buttons>100||button.text()==null||button.text().length()>512)throw new IllegalArgumentException("本页机器人按钮过多，请选择更小的会话范围");
                String key=UUID.randomUUID().toString();rawById.put(key,button);candidates.add(new BotPagination.InputButton(key,button.text(),"CALLBACK".equals(button.type())));
            }
            var result=BotPagination.analyze(raw.text(),candidates);pagination.put(raw.ref().messageId(),result);
            var projected=new ArrayList<Button>();
            for(var classification:result.buttons()){
                var button=rawById.get(classification.id());boolean supported="CALLBACK".equals(button.type())&&!"UNSUPPORTED".equals(classification.kind());
                var dto=new Button(classification.id(),button.text(),classification.kind(),raw.ref().messageId(),button.row(),button.column(),supported);
                projected.add(dto);grants.put(dto.id(),new Grant(raw.ref().messageId(),button,dto));
            }
            for(var media:raw.media()){
                if(media==null||media.message()==null||!"BOT".equals(media.source())||!e.target.chatId().equals(media.message().chatId())||!raw.ref().messageId().equals(media.message().messageId())||media.key()==null||media.key().isBlank())throw new IllegalArgumentException("机器人媒体归属无法确认");
                observed.put(media.key(),media);
            }
            messages.add(new Message(raw.ref().messageId(),raw.text(),List.copyOf(raw.media()),List.copyOf(projected)));
        }
        if(observed.size()>500)throw new IllegalArgumentException("本次已读取500个媒体，请先下载已选媒体，再重新打开会话");
        String nextSignature=signature(snapshot);
        if(!nextSignature.equals(e.signature)||!Objects.equals(e.historyCursor,snapshot.nextCursor())||e.historyEnd!=snapshot.historyEnd()){
            e.media.clear();e.media.putAll(observed);e.messages=List.copyOf(messages);e.grants=grants;e.pagination=pagination;
            e.signature=nextSignature;e.historyCursor=snapshot.nextCursor();e.historyEnd=snapshot.historyEnd();e.revision++;
            for(var message:messages)if(new java.math.BigInteger(message.id()).compareTo(new java.math.BigInteger(e.newestMessage))>0)e.newestMessage=message.id();
            // Newest explicit terminal/progress message wins; unrelated advert/menu messages are not the controller.
            e.control=messages.stream().filter(m->eligibleController(e,m.id())&&!"UNKNOWN".equals(pagination.get(m.id()).status())).max(Comparator.comparing(m->new java.math.BigInteger(m.id()))).map(Message::id)
                .orElseGet(()->messages.stream().filter(m->eligibleController(e,m.id())&&m.buttons().stream().anyMatch(Button::supported)).max(Comparator.comparing(m->new java.math.BigInteger(m.id()))).map(Message::id).orElse(null));
        }
    }
    private View view(Entry e){
        long now=clock.getAsLong();timeout(e,now);Waiting waiting=e.waiting;
        WaitInfo wait=waiting==null?null:new WaitInfo(waiting.waitSeconds,waiting.timeoutSeconds,elapsedMillis(now,waiting.startedAt),
                waiting.progressObserved?elapsedMillis(now,waiting.quietSince):0,Math.max(0,waiting.timeoutSeconds*1000L-elapsedMillis(now,waiting.startedAt)),waiting.progressObserved,waiting.timedOut);
        var page=e.control==null?null:e.pagination.get(e.control);
        Integer at=page==null?null:page.current(),total=page==null?null:page.total();
        if(at!=null&&total!=null&&at>total){at=null;total=null;}
        String status=page==null?"UNKNOWN":page.status();if(waiting!=null&&"END".equals(status))status="UNKNOWN";
        return new View(e.id,e.revision,e.target,e.messages,List.copyOf(e.media.values()),e.control,page==null||waiting!=null?null:page.nextButtonId(),status,at,total,e.state,e.warning,e.historyCursor,e.historyEnd,wait);
    }
    public CompletableFuture<TaskView> create(Create input){
        if(input==null||!uuid(input.operationId())||!uuid(input.sourceId())||input.mediaKeys()==null||input.mediaKeys().isEmpty()||input.mediaKeys().size()>100||new HashSet<>(input.mediaKeys()).size()!=input.mediaKeys().size()||input.mediaKeys().stream().anyMatch(k->k==null||k.isBlank()||k.length()>512))throw new IllegalArgumentException("请选择1至100个已读取的机器人媒体");
        Session session=capture();Path root=paths.root(input.targetDirectory());
        String fingerprint=DownloadPaths.hash("BOT\n"+input.sourceId()+"\n"+input.revision()+"\n"+String.join("\n",input.mediaKeys())+"\n"+DownloadPaths.key(root));
        var previous=repository.byOperation(session.account(),input.operationId());
        if(previous!=null){if(!fingerprint.equals(previous.fingerprint()))throw new IllegalArgumentException("相同操作的下载参数已变化");current(session);return CompletableFuture.completedFuture(repository.view(previous));}
        return work.readWork(()->{
            current(session);
            Entry e=entry(input.sourceId(),session);revision(e,input.revision());
            var selected=input.mediaKeys().stream().map(k->{Media media=e.media.get(k);if(media==null)throw new IllegalArgumentException("媒体不属于本次已观察范围");return media;}).toList();
            String anchor=e.control==null?selected.getFirst().message().messageId():e.control;
            String title=e.control==null?e.target.title():e.messages.stream().filter(m->m.id().equals(e.control)).map(Message::text).findFirst().orElse(e.target.title());
            if(title.isBlank())title=e.target.title();String shortTitle=title.lines().findFirst().orElse(title);if(shortTitle.length()>160)shortTitle=shortTitle.substring(0,160);
            var post=new Post("bot:"+e.target.botUserId()+":"+e.target.chatId()+":"+anchor,session.account(),e.target.chatId(),e.target.title(),anchor,shortTitle,title,null,selected.stream().map(Media::message).distinct().toList(),selected,null,null,List.of(),false,"PARTIAL",List.of("仅下载本次明确选择的机器人媒体，不代表机器人资源已经读完。"));
            var frozen=files.freeze(post,selected,session.generation());current(session);
            var task=repository.create(session.account(),input.operationId(),fingerprint,post,root,frozen);
            try{current(session);return repository.view(task);}
            catch(RuntimeException uncertain){throw new IllegalStateException("下载任务可能已保存，请按原操作号核对结果，不要重新创建",uncertain);}
        });
    }
    public Lookup lookup(String operation){if(!uuid(operation))throw new IllegalArgumentException("下载操作标识无效");Session session=capture();var task=repository.byOperation(session.account(),operation);current(session);if(task!=null&&!task.post().key().startsWith("bot:"))throw new IllegalArgumentException("这个操作不属于机器人下载");return new Lookup(task==null?null:repository.view(task));}
}
