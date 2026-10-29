package io.github.telegramenhance.transfer;

import io.github.telegramenhance.library.*;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.DownloadBatchModels.*;
import io.github.telegramenhance.transfer.DownloadModels.FrozenFile;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;

/** Explicit bounded metadata preparation, followed by one atomic commit to the existing download worker. */
@Service
public final class DownloadBatchService {
    static final int MAX_MEDIA=1000,MAX_STEPS=2000;
    private static final long TTL=TimeUnit.MINUTES.toMillis(10);
    private static final Set<String> TYPES=Set.of("VIDEO","PHOTO","AUDIO","DOCUMENT","OTHER");
    private final DownloadChannelService scans;private final ResourceService work;private final ResourceReader reader;
    private final DownloadFileReader files;private final DownloadRepository repository;private final DownloadPaths paths;private final TelegramService telegram;private final LongSupplier clock;private final DownloadEngine engine;
    private final LinkedHashMap<String,Entry> previews=new LinkedHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public DownloadBatchService(DownloadChannelService scans,ResourceService work,ResourceReader reader,DownloadFileReader files,DownloadRepository repository,DownloadPaths paths,TelegramService telegram,DownloadEngine engine){this(scans,work,reader,files,repository,paths,telegram,System::currentTimeMillis,engine);}
    DownloadBatchService(DownloadChannelService scans,ResourceService work,ResourceReader reader,DownloadFileReader files,DownloadRepository repository,DownloadPaths paths,TelegramService telegram,LongSupplier clock){this(scans,work,reader,files,repository,paths,telegram,clock,null);}
    private DownloadBatchService(DownloadChannelService scans,ResourceService work,ResourceReader reader,DownloadFileReader files,DownloadRepository repository,DownloadPaths paths,TelegramService telegram,LongSupplier clock,DownloadEngine engine){this.scans=scans;this.work=work;this.reader=reader;this.files=files;this.repository=repository;this.paths=paths;this.telegram=telegram;this.clock=clock;this.engine=engine;}
    public record Listing(List<BatchStatus> items,String workerWarning){public Listing{items=List.copyOf(items);}}
    public Listing list(int limit,int offset){
        if(limit<1||limit>20||offset<0)throw new IllegalArgumentException("批量任务分页参数无效。");
        var session=capture();var items=repository.batchStatuses(session.account(),limit,offset);current(session);return new Listing(items,engine==null?null:engine.warning());
    }
    public BatchStatus status(String id){validateBatchId(id);var session=capture();var result=repository.batchStatus(session.account(),id);current(session);return result;}
    public BatchStatus pause(String id){validateBatchId(id);var session=capture();var result=repository.pauseBatch(session.account(),id,()->current(session));current(session);return result;}
    public BatchStatus resume(String id){validateBatchId(id);var session=capture();var result=repository.resumeBatch(session.account(),id,()->current(session));current(session);return result;}
    private static void validateBatchId(String id){if(!uuid(id))throw new IllegalArgumentException("批量任务标识无效。");}
    public View start(Input input){
        if(input==null||input.types()==null||input.types().isEmpty()||input.types().size()>5||input.types().stream().anyMatch(type->type==null||!TYPES.contains(type))||new HashSet<>(input.types()).size()!=input.types().size())throw new IllegalArgumentException("请选择评论媒体类型。");
        var session=capture();var root=paths.root(input.targetDirectory());var source=scans.selection(input.scanId(),input.revision(),input.postKeys());
        var rows=input.postKeys().stream().map(key->source.rows().stream().filter(r->r.post().key().equals(key)).findFirst().orElseThrow().post()).map(Part::pending).toList();
        long now=clock.getAsLong();String id=UUID.randomUUID().toString();
        var entry=new Entry(session,id,source.channel().title(),source.tags(),source.keywords(),input.types(),Boolean.TRUE.equals(input.includeMain()),root,rows,now);
        synchronized(previews){prune(session);while(previews.size()>=4)previews.remove(previews.keySet().iterator().next());current(session);previews.put(id,entry);return view(entry);}
    }
    public View get(String id){var session=capture();synchronized(previews){prune(session);return view(entry(id,session));}}
    public CompletableFuture<View> next(String id,Position position){
        var session=capture();Entry e;List<Part> before;
        synchronized(previews){prune(session);e=entry(id,session);if(position==null||position.revision()!=e.revision||e.busy||done(e)||e.steps>=MAX_STEPS)throw new IllegalArgumentException("准备位置已变化或无法继续，请先核对清单。");e.busy=true;before=e.parts;}
        try{return work.readWork(()->advance(id,e,before)).whenComplete((v,failure)->{synchronized(previews){if(previews.get(id)==e&&failure!=null){e.busy=false;var cause=failure;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();e.error=cause instanceof IllegalArgumentException?cause.getMessage():"本次准备未完成，原位置保留；请核对后显式继续。";}}});}
        catch(RuntimeException rejected){synchronized(previews){e.busy=false;}throw rejected;}
    }
    private View advance(String id,Entry e,List<Part> before){
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);Runnable guard=()->{current(e.session);if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IllegalArgumentException("本次媒体准备达到时间上限。");};guard.run();
        int index=0;while(before.get(index).done())index++;Part old=before.get(index);Post post=old.post();List<Media> candidates;String cursor=old.cursor();boolean complete;long observed=old.messages();
        if(!old.started()){
            post=reader.downloadChannelPost(post.channelId(),post.mainMessageId(),25_000,guard);guard.run();
            if(post==null||!post.key().equals(old.post().key())||!post.channelId().equals(old.post().channelId())||!post.accountId().equals(e.session.account())||!"COMPLETE".equals(post.mainCoverage())||!PostSearch.matches(post.text(),new PostSearch.Query(List.of(),e.tags,e.keywords)))throw new IllegalArgumentException("主帖文字、身份或范围已变化，请重新扫描选择。");
            if(!post.canReadComments()&&!e.includeMain)throw new IllegalArgumentException("该主帖评论不可读，未把未知范围当成空评论。");
            candidates=e.includeMain?post.mainMedia():List.of();complete=!post.canReadComments();
        }else{
            Page page;
            synchronized(previews){page=e.pending;}
            if(page==null){page=reader.downloadComments(post,cursor,25_000,guard);guard.run();validatePage(post,page,cursor);
                synchronized(previews){current(e.session);if(previews.get(id)!=e)throw new IllegalArgumentException("预览已过期。");e.pending=page;}}
            candidates=page.media().stream().filter(media->e.types.contains(media.type())).toList();cursor=page.nextCursor();complete=page.exhausted();observed=Math.addExact(observed,page.messageCount());
        }
        var used=new HashSet<String>();before.forEach(part->part.files().forEach(file->used.add(file.media().key())));
        var selected=new ArrayList<Media>();for(var media:candidates)if(used.add(media.key()))selected.add(media);
        int count=before.stream().mapToInt(part->part.files().size()).sum();if(count+selected.size()>MAX_MEDIA)throw new IllegalArgumentException("本批超过1000个媒体，请减少主帖或媒体类型后重新准备。");
        var frozen=selected.isEmpty()?List.<FrozenFile>of():files.freeze(post,selected,e.session.generation());guard.run();
        if(frozen.size()!=selected.size()||!frozen.stream().map(file->file.media().key()).toList().equals(selected.stream().map(Media::key).toList())||frozen.stream().anyMatch(file->file.uniqueId()==null||file.uniqueId().isBlank()||file.size()<=0))throw new IllegalArgumentException("原媒体身份未完整核对。");
        for(var file:frozen)if(e.root.resolve(DownloadPaths.directory(post)).resolve(DownloadPaths.file(file)).toString().length()>240)throw new IllegalArgumentException("保存路径过长，请选择更短的根目录后重新准备。");
        var collected=new ArrayList<>(old.files());collected.addAll(frozen);var parts=new ArrayList<>(before);parts.set(index,new Part(post,true,complete,cursor,observed,collected));
        synchronized(previews){guard.run();if(previews.get(id)!=e||e.parts!=before)throw new IllegalArgumentException("准备结果已过期。");e.parts=List.copyOf(parts);e.pending=null;e.revision++;e.steps++;e.touched=clock.getAsLong();e.error=null;e.busy=false;return view(e);}
    }
    private static void validatePage(Post post,Page page,String prior){
        if(page==null||!post.key().equals(page.postKey())||page.messageCount()!=page.messages().size()||page.messages().size()>50||page.media().size()>50||page.exhausted()&&(!page.messages().isEmpty()||!page.media().isEmpty())||!page.warnings().isEmpty()||!page.scanWarnings().isEmpty()||page.observations().stream().anyMatch(observation->!observation.mediaComplete())||page.messages().stream().anyMatch(ref->!Objects.equals(post.discussionChatId(),ref.chatId())||Objects.equals(post.discussionRootMessageId(),ref.messageId())||post.discussionRoots().stream().anyMatch(root->root.chatId().equals(ref.chatId())&&root.messageId().equals(ref.messageId())))||page.media().stream().anyMatch(media->!"COMMENT".equals(media.source())||media.message()==null||page.messages().stream().noneMatch(ref->ref.chatId().equals(media.message().chatId())&&ref.messageId().equals(media.message().messageId()))))throw new IllegalArgumentException("评论身份或媒体范围尚未核全，请先使用单帖预览。");
        if(!page.exhausted()&&(!cursor(page.nextCursor())||prior!=null&&Long.parseLong(page.nextCursor())>=Long.parseLong(prior)))throw new IllegalArgumentException("评论位置没有向旧推进，未当作读完。");
    }
    public Receipt create(Create input){
        if(input==null||!uuid(input.operationId())||!uuid(input.previewId())||input.revision()<1)throw new IllegalArgumentException("批量创建操作无效。");
        var session=capture();String fingerprint=DownloadPaths.hash(input.previewId()+"\n"+input.revision());var existing=repository.batchByOperation(session.account(),input.operationId(),fingerprint);if(existing!=null){current(session);return existing;}
        synchronized(previews){prune(session);var e=entry(input.previewId(),session);if(e.busy||!done(e)||e.revision!=input.revision())throw new IllegalArgumentException("请先完整准备并核对批量清单。");
            var root=paths.root(e.root.toString());if(!DownloadPaths.key(root).equals(DownloadPaths.key(e.root)))throw new IllegalArgumentException("保存位置已变化。");
            var value=view(e);if(value.total()<1)throw new IllegalArgumentException("当前清单没有可下载媒体。");
            var snapshot=new Snapshot(e.id,e.revision,e.channelTitle,e.tags,e.keywords,e.types,e.includeMain,root.toString(),e.parts.size(),value.total(),value.totalBytes());
            var jobs=new ArrayList<Job>();for(var part:e.parts)for(int offset=0;offset<part.files().size();offset+=100)jobs.add(new Job(part.post(),part.files().subList(offset,Math.min(offset+100,part.files().size()))));
            current(session);var result=repository.createBatch(session.account(),input.operationId(),fingerprint,snapshot,root,jobs);current(session);return result;
        }
    }
    public Lookup lookup(String operation){if(!uuid(operation))throw new IllegalArgumentException("操作号无效。");var session=capture();var result=repository.batchByOperation(session.account(),operation,null);current(session);return new Lookup(result);}
    private View view(Entry e){var posts=e.parts.stream().map(part->new PostSummary(part.post().key(),part.post().title(),part.done()?"READY":part.started()?"READING_COMMENTS":"PENDING",part.messages(),(int)part.files().stream().filter(file->"MAIN".equals(file.media().source())).count(),(int)part.files().stream().filter(file->"COMMENT".equals(file.media().source())).count(),part.files().stream().mapToLong(FrozenFile::size).reduce(0,Math::addExact))).toList();
        var warnings=new ArrayList<String>();warnings.add("仅包含本次选定主帖及成功核对的媒体版本；不是全频道下载或持续规则。");if(e.parts.stream().anyMatch(part->part.started()&&!part.post().canReadComments()))warnings.add("部分主帖评论不可读，对应清单仅包含已核对的主帖媒体，不表示评论为空或已读完。");
        return new View(e.id,e.session.account(),e.revision,e.channelTitle,e.tags,e.keywords,e.types,e.includeMain,e.root.toString(),posts,e.parts.stream().mapToInt(part->part.files().size()).sum(),posts.stream().mapToLong(PostSummary::bytes).reduce(0,Math::addExact),done(e),!done(e)&&e.steps<MAX_STEPS,e.busy,e.steps>=MAX_STEPS&&!done(e)?"准备达到2000步上限，请缩小范围重新准备。":e.error,warnings,e.created,e.touched+TTL);}
    private static boolean done(Entry e){return e.parts.stream().allMatch(Part::done);}
    private Session capture(){var session=new Session(telegram.accountId(),telegram.sessionGeneration());current(session);return session;}
    private void current(Session session){if(!Objects.equals(session.account(),telegram.accountId())||session.generation()!=telegram.sessionGeneration())throw new IllegalArgumentException("账号或会话已变化，请重新准备。");}
    private void prune(Session session){previews.values().removeIf(e->!e.session.equals(session)||clock.getAsLong()-e.touched>=TTL);}
    private Entry entry(String id,Session session){if(!uuid(id))throw new IllegalArgumentException("批量预览标识无效。");var e=previews.get(id);if(e==null||!e.session.equals(session))throw new IllegalArgumentException("批量预览已过期，请重新准备。");return e;}
    private static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    private static boolean cursor(String value){try{return value!=null&&Long.parseLong(value)>0&&value.equals(Long.toString(Long.parseLong(value)));}catch(NumberFormatException invalid){return false;}}
    private record Session(String account,long generation){}
    private record Part(Post post,boolean started,boolean done,String cursor,long messages,List<FrozenFile> files){Part{files=List.copyOf(files);}static Part pending(Post post){return new Part(post,false,false,null,0,List.of());}}
    private static final class Entry{
        final Session session;final String id,channelTitle;final List<String> tags,keywords,types;final boolean includeMain;final Path root;final long created;List<Part> parts;Page pending;long revision=1,touched;int steps;boolean busy;String error;
        Entry(Session session,String id,String title,List<String> tags,List<String> keywords,List<String> types,boolean includeMain,Path root,List<Part> parts,long now){this.session=session;this.id=id;channelTitle=title;this.tags=List.copyOf(tags);this.keywords=List.copyOf(keywords);this.types=List.copyOf(types);this.includeMain=includeMain;this.root=root;this.parts=List.copyOf(parts);created=now;touched=now;}
    }
}
