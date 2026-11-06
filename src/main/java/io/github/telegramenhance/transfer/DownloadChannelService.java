package io.github.telegramenhance.transfer;

import io.github.telegramenhance.library.*;
import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import io.github.telegramenhance.library.ChannelImportModels.ManagedChannels;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.DownloadChannelModels.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.springframework.stereotype.Service;

/** Independent, ephemeral download scans on the existing serial metadata lane. No repository or transfers. */
@Service
public final class DownloadChannelService {
    private static final long TTL=TimeUnit.MINUTES.toMillis(10);
    private static final int MAX_PREVIEWS=8;
    private final ResourceService work;private final ChannelReader channels;private final ResourceReader posts;
    private final TelegramService telegram;private final LongSupplier clock;
    private final LinkedHashMap<String,Entry> previews=new LinkedHashMap<>();
    private ScanTaskStore scanTasks;
    @org.springframework.beans.factory.annotation.Autowired public void trackScans(ScanTaskStore scanTasks){this.scanTasks=scanTasks;}
    @org.springframework.beans.factory.annotation.Autowired
    public DownloadChannelService(ResourceService work,ChannelReader channels,ResourceReader posts,TelegramService telegram){this(work,channels,posts,telegram,System::currentTimeMillis);}
    DownloadChannelService(ResourceService work,ChannelReader channels,ResourceReader posts,TelegramService telegram,LongSupplier clock){this.work=work;this.channels=channels;this.posts=posts;this.telegram=telegram;this.clock=clock;}
    public CompletableFuture<ManagedChannels> channels(){Session session=capture();return work.readWork(()->{current(session);var found=channels.downloadChannels();current(session);return found;});}
    public CompletableFuture<View> start(Input input){
        if(input==null||input.source()==null||input.source().isBlank()||input.source().length()>2048)throw new IllegalArgumentException("请选择下载频道或填写频道链接、用户名。");
        var query=PostSearch.parse(List.of(),input.tags(),input.keywords());var dates=DownloadDateRange.parse(input.startDate(),input.endDate());Session session=capture();
        return work.readWork(()->{
            current(session);var channel=channels.downloadChannel(input.source());current(session);long now=clock.getAsLong();String id=UUID.randomUUID().toString();
            var view=new View(id,session.account(),channel,query.tags(),query.keywords(),1,0,0,0,List.of(),false,true,false,false,null,
                    List.of("仅预览本轮已读取的频道主帖；评论需选中主帖后展开，不表示频道及评论均已读完。"),now,now+TTL,dates.startDate(),dates.endDate());
            synchronized(previews){prune(session);makeRoom();var entry=new Entry(session,view,query,dates,now);remember(entry,true);previews.put(id,entry);}
            return view;
        });
    }
    public View get(String id){id(id);Session session=capture();synchronized(previews){prune(session);var e=entry(id,session);return copy(e.value,e.busy,e.value.error(),e.touched+TTL);}}
    public List<View> snapshot(){var session=capture();synchronized(previews){prune(session);var result=previews.values().stream().map(e->copy(e.value,e.busy,e.value.error(),e.touched+TTL)).toList();current(session);return result;}}
    /** A batch may use only explicitly selected, verified matching posts from this scan. */
    public View selection(String id,long revision,List<String> keys){
        var value=get(id);
        if(value.busy()||value.revision()!=revision||keys==null||keys.isEmpty()||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("请选择已核全的匹配主帖，并核对扫描位置。");
        var rows=new HashMap<String,Row>();value.rows().forEach(row->rows.put(row.post().key(),row));
        for(var key:keys){var row=rows.get(key);if(row==null)throw new IllegalArgumentException("主帖不属于当前扫描结果。");
            if(!"MATCH".equals(row.match())||!"COMPLETE".equals(row.post().mainCoverage()))throw new IllegalArgumentException("部分主帖范围仍待核实，请先使用单帖预览。");}
        return value;
    }
    public CompletableFuture<View> next(String id,Position position){
        id(id);Session session=capture();Entry entry;Work state;
        synchronized(previews){prune(session);entry=entry(id,session);
            if(position==null||position.revision()!=entry.value.revision())throw new IllegalArgumentException("扫描位置已变化，请先核对当前预览状态。");
            if(entry.busy)throw new IllegalArgumentException("当前扫描尚未完成，请等待或核对状态。");
            if(!entry.value.canContinue())throw new IllegalArgumentException("当前预览已到边界或无法继续，请重新扫描。");
            if(scanTasks!=null)scanTasks.check(session.account(),session.generation(),"channel-scan",id);
            entry.busy=true;state=new Work(entry.value,entry.pending,entry.offset,entry.cursor,Set.copyOf(entry.seen));
        }
        try{return work.readWork(()->{try(var admitted=scanTasks==null?null:scanTasks.begin(session.account(),session.generation(),"channel-scan",id)){return advance(id,entry,state);}}).whenComplete((value,failure)->{
            synchronized(previews){if(previews.get(id)==entry){entry.busy=false;if(failure!=null&&!pausedFailure(failure))entry.value=copy(entry.value,false,"本次主帖读取未完成，原扫描位置保留；请核对状态后显式继续。",entry.value.expiresAt());remember(entry,false);}}
        });}catch(RuntimeException rejected){synchronized(previews){if(previews.get(id)==entry)entry.busy=false;}throw rejected;}
    }
    private View advance(String id,Entry owner,Work before){
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(65);Runnable guard=()->{current(owner.session);if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IllegalArgumentException("本次扫描达到时间上限，原位置保留。");};
        guard.run();var original=before.view();HistoryPage page=before.pending();int offset=before.offset();String cursor=before.cursor();
        var warnings=new LinkedHashSet<>(original.warnings());
        if(page==null){page=channels.downloadHistoryPage(original.channel().id(),cursor);guard.run();
            if(page==null||page.channel()==null||!original.channel().id().equals(page.channel().id())||page.items().size()>10)throw new IllegalArgumentException("频道历史身份或数量无法核对。");
            if(page.exhausted()&&page.hasMore()||page.hasMore()&&!ChannelReader.advancesCursor(cursor,page.nextCursor(),owner.session.account(),original.channel().id()))throw new IllegalArgumentException("频道历史位置没有严格向旧推进，未继续扫描。");
            warnings.addAll(page.warnings());offset=0;
            // Keep a successfully observed batch when assembling one post fails. This does not
            // consume the candidate or advance the client revision/cursor.
            synchronized(previews){if(previews.get(id)!=owner)throw new IllegalArgumentException("预览已被替换。");current(owner.session);owner.pending=page;owner.offset=0;}
        }
        var seen=new LinkedHashSet<>(before.seen());var rows=new ArrayList<>(original.rows());int scanned=original.scanned(),matched=original.matched(),unverified=original.unverified();
        if(offset<page.items().size()){
            var access=channels.downloadChannel(original.channel().id());guard.run();
            if(access==null||!original.channel().id().equals(access.id()))throw new IllegalArgumentException("频道访问身份发生变化，原位置保留。");
            var candidate=page.items().get(offset);var post=posts.downloadChannelPost(original.channel().id(),candidate.messageId(),25_000,guard);guard.run();
            if(post==null||!owner.session.account().equals(post.accountId())||!original.channel().id().equals(post.channelId())||post.key()==null||post.key().isBlank()
                    ||!Objects.equals(candidate.albumId()==null?"0":candidate.albumId(),post.albumId())
                    ||post.mainMessages().stream().noneMatch(ref->post.channelId().equals(ref.chatId())&&candidate.messageId().equals(ref.messageId())))throw new IllegalArgumentException("主帖身份与当前频道扫描不符，原位置保留。");
            if(seen.add(post.key())){scanned=Math.incrementExact(scanned);boolean textMatch=PostSearch.matches(post.text(),owner.query);Boolean dateMatch=owner.dates.matches(post.publishedAt());
                boolean textComplete="0".equals(post.albumId())||post.albumEvidence()!=null&&post.albumEvidence().complete();
                boolean match=textMatch&&Boolean.TRUE.equals(dateMatch);
                if(!Boolean.FALSE.equals(dateMatch)&&(match||!textComplete||textMatch&&dateMatch==null)){
                    if(match)matched=Math.incrementExact(matched);else unverified=Math.incrementExact(unverified);
                    if(dateMatch==null)warnings.add("部分主帖发布时间未知，日期范围仍待核实；对应主帖未计为匹配，不能批量选择。");
                    rows.add(new Row(post,match?"MATCH":"UNVERIFIED",link(post),post.canReadComments()?"NOT_READ":"UNAVAILABLE"));
                }
            }
            offset++;
        }
        boolean pageDone=offset==page.items().size(),exhausted=pageDone&&page.exhausted();String error=null;
        if(pageDone){if(!exhausted&&!page.hasMore())error="本轮没有取得可继续的频道位置，尚未确认历史边界。";cursor=page.nextCursor();page=null;offset=0;}
        long now=clock.getAsLong();var value=new View(original.id(),original.accountId(),original.channel(),original.tags(),original.keywords(),original.revision()+1,scanned,matched,unverified,rows,exhausted,!exhausted&&error==null,false,false,error,warnings.stream().limit(20).toList(),original.createdAt(),now+TTL,original.startDate(),original.endDate());
        guard.run();synchronized(previews){if(previews.get(id)!=owner||owner.value.revision()!=original.revision())throw new IllegalArgumentException("预览已被替换，旧读取结果已丢弃。");
            current(owner.session);owner.value=value;owner.pending=page;owner.offset=offset;owner.cursor=cursor;owner.seen=seen;owner.touched=now;
        }return value;
    }
    private static String link(Post post){return post.mainMessages().stream().map(ref->ref.link()).filter(Objects::nonNull).filter(value->{try{var uri=URI.create(value);return "https".equals(uri.getScheme())&&Set.of("t.me","telegram.me").contains(uri.getHost())&&uri.getUserInfo()==null&&uri.getPort()==-1&&uri.getPath()!=null&&uri.getPath().matches("/(?:c/[0-9]+|[A-Za-z0-9_]+)/[1-9][0-9]*/?");}catch(RuntimeException invalid){return false;}}).findFirst().orElse(null);}
    private static View copy(View v,boolean busy,String error,long expires){return new View(v.id(),v.accountId(),v.channel(),v.tags(),v.keywords(),v.revision(),v.scanned(),v.matched(),v.unverified(),v.rows(),v.exhausted(),v.canContinue(),busy,v.limitReached(),error,v.warnings(),v.createdAt(),expires,v.startDate(),v.endDate());}
    private Entry entry(String id,Session session){var value=previews.get(id);if(value==null||!value.session.equals(session))throw new IllegalArgumentException("频道预览已过期或账号/会话已变化，请重新扫描。");return value;}
    private void prune(Session session){var iterator=previews.values().iterator();while(iterator.hasNext()){var entry=iterator.next();boolean same=entry.session.equals(session);
        if(same&&scanTasks!=null){if(scanTasks.paused(session.account(),session.generation(),"channel-scan",entry.value.id()))continue;entry.touched=Math.max(entry.touched,scanTasks.deadline(session.account(),session.generation(),"channel-scan",entry.value.id())-TTL);}
        if(!same||!entry.busy&&clock.getAsLong()-entry.touched>=TTL){if(scanTasks!=null)scanTasks.expire(entry.session.account(),entry.session.generation(),"channel-scan",entry.value.id(),"原频道预览已过期或会话变化，保留扫描历史");iterator.remove();}
    }}
    private void makeRoom(){while(previews.size()>=MAX_PREVIEWS){var candidate=previews.values().stream().filter(e->!e.busy&&(scanTasks==null||!scanTasks.paused(e.session.account(),e.session.generation(),"channel-scan",e.value.id()))).findFirst().orElseThrow(()->new IllegalArgumentException("8份频道预览均在读取或暂停，请先恢复并完成已有预览"));if(scanTasks!=null)scanTasks.expire(candidate.session.account(),candidate.session.generation(),"channel-scan",candidate.value.id(),"新预览替换了原预览，保留扫描历史");previews.remove(candidate.value.id());}}
    private void remember(Entry entry,boolean claim){if(scanTasks==null)return;var v=entry.value;String state=v.exhausted()?"COMPLETED":entry.busy?"RUNNING":v.error()!=null?"FAILED":"WAITING_STEP";scanTasks.remember(entry.session.account(),entry.session.generation(),new ScanTaskStore.Snapshot(v.id(),"channel-scan",v.channel().title(),"频道主帖扫描 · 已匹配 "+v.matched(),state,v.createdAt(),clock.getAsLong(),0,v.scanned(),0,"SCANNING",v.id(),null,v.error(),v.revision(),entry.touched+TTL,true,v.canContinue()||entry.busy),claim);}
    private static boolean pausedFailure(Throwable failure){while(failure instanceof CompletionException&&failure.getCause()!=null)failure=failure.getCause();return failure instanceof ScanTaskStore.Paused;}
    private Session capture(){var s=new Session(telegram.accountId(),telegram.sessionGeneration());current(s);return s;}
    private void current(Session s){if(!Objects.equals(s.account(),telegram.accountId())||s.generation()!=telegram.sessionGeneration())throw new IllegalArgumentException("账号或会话已变化，旧频道结果已丢弃。");}
    private static void id(String id){if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("频道预览标识无效。");}
    private record Session(String account,long generation){}
    private record Work(View view,HistoryPage pending,int offset,String cursor,Set<String> seen){}
    private static final class Entry{
        final Session session;final PostSearch.Query query;final DownloadDateRange dates;View value;HistoryPage pending;int offset;String cursor;boolean busy;long touched;Set<String> seen=new LinkedHashSet<>();
        Entry(Session session,View value,PostSearch.Query query,DownloadDateRange dates,long now){this.session=session;this.value=value;this.query=query;this.dates=dates;this.touched=now;}
    }
}
