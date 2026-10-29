package io.github.telegramenhance.downloadrules;

import io.github.telegramenhance.downloadrules.DownloadRuleModels.*;
import io.github.telegramenhance.library.*;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import io.github.telegramenhance.library.ChannelImportModels.ManagedChannel;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.DownloadModels.FrozenFile;
import java.nio.file.Path;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Each invocation performs one bounded metadata unit on the existing serial read queue. */
@Service
public final class DownloadRuleService {
    private final DownloadRuleRepository repository;
    private final ResourceService work;
    private final ChannelReader channels;
    private final ResourceReader posts;
    private final DownloadFileReader files;
    private final DownloadPaths paths;
    private final DownloadRulePolicy policy;
    private final TelegramService telegram;
    private final LongSupplier clock;
    private final Set<String> busy = new HashSet<>();
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile boolean closed;
    private static final Set<String> TYPES=Set.of("VIDEO","PHOTO","AUDIO","DOCUMENT","OTHER");
    private static final long NEEDS_EXPLICIT_CHECK=253_402_300_799_000L; // JSON-safe epoch in year 9999.

    @org.springframework.beans.factory.annotation.Autowired
    public DownloadRuleService(DownloadRuleRepository repository,ResourceService work,ChannelReader channels,
            ResourceReader posts,DownloadFileReader files,DownloadPaths paths,DownloadRulePolicy policy,TelegramService telegram) {
        this(repository,work,channels,posts,files,paths,policy,telegram,System::currentTimeMillis);
    }
    DownloadRuleService(DownloadRuleRepository repository,ResourceService work,ChannelReader channels,
            ResourceReader posts,DownloadFileReader files,DownloadPaths paths,DownloadRulePolicy policy,TelegramService telegram,LongSupplier clock) {
        this.repository=repository;this.work=work;this.channels=channels;this.posts=posts;this.files=files;
        this.paths=paths;this.policy=policy;this.telegram=telegram;this.clock=clock;
    }
    public record Create(String operationId,Input input) { @Override public String toString(){return "CreateDownloadRule[redacted]";} }
    public record Update(long revision,Input input) { @Override public String toString(){return "UpdateDownloadRule[redacted]";} }
    public record Toggle(long revision,boolean enabled) { }
    public record Position(long revision) { }
    public record Takeover(long revision,String taskId) { }
    private record Session(String account,long generation) { }
    private record Resolved(Input input,ManagedChannel channel,Post post) { }

    public Listing list() {
        var session=capture();var result=repository.list(session.account()).stream().map(this::view).toList();current(session);
        return new Listing(result,2);
    }
    public View get(String id) { id(id);var session=capture();var value=view(repository.get(session.account(),id));current(session);return value; }
    public View lookup(String operation) {
        id(operation);var session=capture();var previous=repository.byOperation(session.account(),operation,null);current(session);
        return previous==null?null:view(previous);
    }
    public List<Version> versions(String id) { id(id);var session=capture();var value=repository.versions(session.account(),id);current(session);return value; }
    public CompletableFuture<View> create(Create request) {
        if(request==null)throw new IllegalArgumentException("请填写持续下载规则。");id(request.operationId());
        var session=capture();var input=normalize(request.input());var fingerprint=DownloadPaths.hash(json.writeValueAsString(input));
        var previous=repository.byOperation(session.account(),request.operationId(),fingerprint);
        if(previous!=null){current(session);return CompletableFuture.completedFuture(view(previous));}
        return work.readWork(()->{
            current(session);var target=resolve(input,session);current(session);
            return view(repository.create(session.account(),request.operationId(),fingerprint,target.input(),target.channel(),target.post(),now(),()->current(session)));
        });
    }
    public CompletableFuture<View> update(String id,Update request) {
        id(id);if(request==null||request.revision()<1)throw new IllegalArgumentException("规则版本无效。");
        var session=capture();var input=normalize(request.input());
        return work.readWork(()->{
            current(session);var before=repository.get(session.account(),id);
            if(before.revision()!=request.revision())throw changed();
            var target=resolve(input,session);current(session);
            return view(repository.update(session.account(),id,request.revision(),target.input(),target.channel(),target.post(),now(),()->current(session)));
        });
    }
    public View toggle(String id,Toggle request) {
        id(id);if(request==null||request.revision()<1)throw changed();var session=capture();
        return view(repository.toggle(session.account(),id,request.revision(),request.enabled(),now(),()->current(session)));
    }
    public View takeover(String id,Takeover request) {
        id(id);if(request==null)throw changed();id(request.taskId());var session=capture();var rule=repository.get(session.account(),id);
        if(rule.revision()!=request.revision())throw changed();
        repository.takeover(rule,request.taskId(),now(),()->current(session));
        repository.schedule(rule,now(),null,now(),()->current(session));return view(repository.get(session.account(),id));
    }
    /** Manual check remains governed by the configured window and never enables a paused rule. */
    public CompletableFuture<View> check(String id,Position request) {
        id(id);if(request==null||request.revision()<1)throw changed();var session=capture();var rule=repository.get(session.account(),id);
        if(rule.revision()!=request.revision())throw changed();requireAllowed(rule);
        return step(session,rule,()->{},true);
    }
    public CompletableFuture<Void> monitorOnce(Runnable stop) {
        if(closed)return CompletableFuture.completedFuture(null);
        Session session;try{session=capture();}catch(RuntimeException disconnected){return CompletableFuture.completedFuture(null);}
        var rule=repository.nextDue(session.account(),now());if(rule==null)return CompletableFuture.completedFuture(null);
        if(!rule.input().window().allows(Instant.ofEpochMilli(now()))) {
            repository.schedule(rule,rule.input().window().nextAllowed(Instant.ofEpochMilli(now())),null,now(),()->current(session));
            return CompletableFuture.completedFuture(null);
        }
        return step(session,rule,stop,false).thenApply(ignored->null);
    }
    private CompletableFuture<View> step(Session session,Rule rule,Runnable stop,boolean explicit) {
        synchronized(busy){if(!busy.add(rule.id()))throw new IllegalArgumentException("这条规则正在扫描，请等待或核对状态。");}
        try {
            return work.readWork(()->{
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(95);
                Runnable guard=()->{stop.run();current(session);if(closed||Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw changed();
                    var latest=repository.get(session.account(),rule.id());if(latest.version()!=rule.version()||latest.revision()!=rule.revision())throw changed();requireAllowed(latest);};
                try {
                    guard.run();
                    var job=repository.activeJob(rule);
                    if(job!=null) {
                        // Only safe automatic pauses are resumed. Failures and explicit task pauses require user action.
                        policy.autoResume(session.account(),job.taskId(),Instant.ofEpochMilli(now()),guard);
                        // Discovering metadata can continue while the single file worker is occupied.
                        // This lane cannot publish another file task for this rule.
                        advance(rule,session,guard,false);
                        repository.schedule(rule,now()+2_000,null,now(),guard);
                    } else {
                        advance(rule,session,guard,true);
                        repository.schedule(rule,now()+2_000,null,now(),guard);
                    }
                } catch(RuntimeException failure) {
                    // A changed context cannot write old results. A read failure keeps its durable position.
                    stop.run();if(closed||Thread.currentThread().isInterrupted())throw failure;
                    current(session);var latest=repository.get(session.account(),rule.id());
                    if(latest.version()!=rule.version()||latest.revision()!=rule.revision()||!latest.enabled())throw failure;
                    if(!latest.input().window().allows(Instant.ofEpochMilli(now())))
                        repository.schedule(latest,latest.input().window().nextAllowed(Instant.ofEpochMilli(now())),null,now(),()->current(session));
                    else repository.schedule(latest,NEEDS_EXPLICIT_CHECK,"本次扫描未完成，已保存原位置。请核对连接、来源权限及读取范围后点击立即扫描一步。",now(),()->current(session));
                    if(explicit)throw failure;
                }
                return view(repository.get(session.account(),rule.id()));
            }).whenComplete((value,error)->{synchronized(busy){busy.remove(rule.id());}});
        } catch(RuntimeException rejected){synchronized(busy){busy.remove(rule.id());}throw rejected;}
    }
    private void advance(Rule rule,Session session,Runnable guard,boolean canProduce) {
        var pending=repository.nextPendingPost(rule);
        // A fixed post has no channel history/head lane. It retains exactly one immutable source.
        if(rule.input().postSource()) {
            if(pending!=null&&canProduce){readPost(rule,pending,session,guard);return;}
            var due=repository.duePost(rule,now());if(due!=null)refreshPost(rule,due,guard);
            return;
        }
        var scan=repository.scan(rule);
        boolean channelDue=!scan.historyDone()||scan.pending()!=null||scan.cursor()!=null||scan.nextHeadAt()<=now();
        var due=repository.duePost(rule,now());
        // A durable turn rotates discovery, comments and old-post refreshes even when the
        // wall clock has the same millisecond or one large thread has millions of replies.
        for(int offset=0;offset<3;offset++) {
            int lane=(int)((scan.turn()+offset)%3);
            if(lane==0&&channelDue){readChannel(rule,scan,guard);return;}
            if(lane==1&&pending!=null&&canProduce){readPost(rule,pending,session,guard);return;}
            if(lane==2&&due!=null){refreshPost(rule,due,guard);return;}
        }
    }
    private void readChannel(Rule rule,Scan scan,Runnable guard) {
            if(scan.pending()==null) {
                var page=channels.downloadHistoryPage(rule.channelId(),scan.cursor());guard.run();validateHistory(rule,scan,page);
                repository.saveHistoryPage(rule,scan,page,now(),guard);return;
            }
            var page=scan.pending();
            if(scan.offset()<0||scan.offset()>=page.items().size())throw new IllegalArgumentException("频道候选位置无效，未当作读完。");
            var candidate=page.items().get(scan.offset());
            Post post;
            try {post=posts.downloadChannelPost(rule.channelId(),candidate.messageId(),25_000,guard);guard.run();validatePost(rule,post,candidate.messageId(),candidate.albumId());}
            catch(RuntimeException unavailable){guard.run();repository.observeCandidateError(rule,scan,"该主帖暂不可读取或身份未核全，保留待复查，未认定删除。",now(),guard);return;}
            boolean matched=PostSearch.matches(post.text(),PostSearch.parse(List.of(),rule.input().tags(),rule.input().keywords()));
            repository.observePost(rule,scan,post,matched,now(),guard);return;
    }
    private void refreshPost(Rule rule,PostState due,Runnable guard) {
            try {
                var post=posts.downloadChannelPost(rule.channelId(),due.messageId(),25_000,guard);guard.run();
                validatePost(rule,post,due.messageId(),due.post()==null?null:due.post().albumId());
                if(!post.key().equals(due.key()))throw new IllegalArgumentException("原主帖身份已改变，未将其视为同一条资源。");
                repository.resetPost(rule,due,post,rule.input().postSource()||PostSearch.matches(post.text(),PostSearch.parse(List.of(),rule.input().tags(),rule.input().keywords())),now(),guard);
            } catch(RuntimeException unavailable){guard.run();repository.postError(rule,due,"旧主帖暂时不可读取或范围未核全，未认定删除；稍后复查。",now(),guard);}
    }
    private void readPost(Rule rule,PostState state,Session session,Runnable guard) {
        var post=state.post();
        try {
            guard.run();List<Media> selected;String cursor=state.cursor();boolean eof=false;
            if("MAIN".equals(state.state())) {
                // mainCoverage is computed before discussion warnings are appended. Main-only
                // requests may ignore an unavailable discussion, never an unverified main album.
                if(rule.input().includeMain()&&!"COMPLETE".equals(post.mainCoverage()))throw new IllegalArgumentException("主帖媒体范围尚未核全。");
                if(rule.input().includeMain()&&!rule.input().types().isEmpty()&&!post.warnings().isEmpty())throw new IllegalArgumentException("主帖媒体仍有范围警告。");
                selected=rule.input().includeMain()?post.mainMedia():List.of();
            }
            else {
                var page=posts.downloadComments(post,cursor,25_000,guard);guard.run();validateComments(post,page,cursor);
                selected=page.media().stream().filter(media->rule.input().types().contains(media.type())).toList();
                cursor=page.nextCursor();eof=page.exhausted();
            }
            var unique=new LinkedHashMap<String,Media>();selected.forEach(media->{if(unique.putIfAbsent(media.key(),media)!=null)throw new IllegalArgumentException("媒体页身份重复。");});
            var frozen=selected.isEmpty()?List.<FrozenFile>of():files.freeze(post,List.copyOf(unique.values()),session.generation());guard.run();
            if(frozen.size()!=selected.size()||frozen.size()>100||!frozen.stream().map(f->f.media().key()).toList().equals(List.copyOf(unique.keySet()))||frozen.stream().anyMatch(f->f.uniqueId()==null||f.uniqueId().isBlank()||f.size()<=0))throw new IllegalArgumentException("原媒体身份没有完整核对。");
            Path root=paths.root(rule.input().targetDirectory());
            if(!DownloadPaths.key(root).equals(DownloadPaths.key(Path.of(rule.input().targetDirectory()))))throw new IllegalArgumentException("原保存目录绑定已变化。");
            repository.commitPage(rule,state,cursor,eof,frozen,root,now(),guard);
        } catch(RuntimeException unavailable) {
            guard.run();repository.postError(rule,state,"此帖媒体或评论范围未完整核对，原位置保留，稍后重新读取；其他主帖继续扫描。",now(),guard);
        }
    }
    static void validateHistory(Rule rule,Scan scan,HistoryPage page) {
        if(page==null||page.channel()==null||!rule.channelId().equals(page.channel().id())||page.items().size()>10||!page.warnings().isEmpty()||page.exhausted()&&page.hasMore()
                ||!page.exhausted()&&(!page.hasMore()||!ChannelReader.advancesCursor(scan.cursor(),page.nextCursor(),rule.accountId(),rule.channelId())))throw new IllegalArgumentException("频道历史位置或范围未核全，未当作历史结束。");
    }
    static void validatePost(Rule rule,Post post,String candidate,String album) {
        if(post==null||!rule.accountId().equals(post.accountId())||!rule.channelId().equals(post.channelId())||post.key()==null||post.key().isBlank()
                ||album!=null&&!Objects.equals(album,post.albumId())||post.mainMessages().stream().noneMatch(ref->rule.channelId().equals(ref.chatId())&&candidate.equals(ref.messageId()))
                ||rule.input().postSource()&&(!Objects.equals(rule.input().postKey(),post.key())||!Objects.equals(rule.input().mainMessageId(),post.mainMessageId())))throw new IllegalArgumentException("主帖身份不属于原频道扫描或固定主帖。");
    }
    static void validateComments(Post post,Page page,String prior) {
        if(page==null||!post.key().equals(page.postKey())||page.messageCount()!=page.messages().size()||page.messages().size()>50||page.media().size()>50
                ||page.exhausted()&&(!page.messages().isEmpty()||!page.media().isEmpty())||!page.warnings().isEmpty()||!page.commentWarnings().isEmpty()||!page.scanWarnings().isEmpty()
                ||page.observations().stream().anyMatch(o->!o.mediaComplete())
                ||page.messages().stream().anyMatch(ref->!Objects.equals(post.discussionChatId(),ref.chatId())||Objects.equals(post.discussionRootMessageId(),ref.messageId())||post.discussionRoots().stream().anyMatch(root->root.chatId().equals(ref.chatId())&&root.messageId().equals(ref.messageId())))
                ||page.media().stream().anyMatch(media->!"COMMENT".equals(media.source())||media.message()==null||page.messages().stream().noneMatch(ref->ref.chatId().equals(media.message().chatId())&&ref.messageId().equals(media.message().messageId()))))throw new IllegalArgumentException("评论页范围或身份未核全。");
        if(!page.exhausted()&&(!cursor(page.nextCursor())||prior!=null&&Long.parseLong(page.nextCursor())>=Long.parseLong(prior)))throw new IllegalArgumentException("评论游标没有严格向旧推进。");
    }
    private Input normalize(Input input) {
        if(input==null||input.source()==null||input.source().isBlank()||input.source().length()>2048||input.window()==null||input.types().isEmpty()&&!input.includeMain()||input.types().size()>5
                ||!Set.of("CHANNEL","POST").contains(input.sourceMode())
                ||input.types().stream().anyMatch(type->type==null||!TYPES.contains(type))||new HashSet<>(input.types()).size()!=input.types().size())throw new IllegalArgumentException("请填写来源、保存目录、媒体类型及执行时段。");
        String name=input.name()==null?"":input.name().strip();if(name.length()>80)throw new IllegalArgumentException("规则名称最多80个字符。");
        if(input.postSource()&&((input.tags()!=null&&!input.tags().isBlank())||(input.keywords()!=null&&!input.keywords().isBlank())))throw new IllegalArgumentException("单主帖规则直接读取指定主帖，不使用标签或关键词筛选。");
        var query=PostSearch.parse(List.of(),input.tags(),input.keywords());var root=paths.root(input.targetDirectory());
        return new Input(name,input.postSource()?postLink(input.source()):input.source().strip(),String.join(" ",query.tags()),String.join(" ",query.keywords()),input.types(),input.includeMain(),root.toString(),input.window(),input.sourceMode(),null,null);
    }
    private Resolved resolve(Input input,Session session) {
        if(input.postSource()) {
            var post=posts.probeDownloadSource(input.source(),25_000,()->current(session));current(session);
            if(post==null||!session.account().equals(post.accountId())||post.channelId()==null||!post.channelId().matches("-[1-9][0-9]{0,18}")
                    ||post.key()==null||!post.key().startsWith(session.account()+":"+post.channelId()+":")||!cursor(post.mainMessageId()))throw new IllegalArgumentException("未取得当前账号可读取的频道主帖身份。");
            String canonical=post.mainMessages().stream().filter(ref->post.channelId().equals(ref.chatId())&&post.mainMessageId().equals(ref.messageId())).findFirst()
                .map(MessageRef::link).map(DownloadRuleService::postLink).orElseThrow(()->new IllegalArgumentException("没有取得规范主帖链接，未保存规则。"));
            if(canonical.startsWith("https://t.me/c/")&&!canonical.split("/")[4].equals(post.channelId().substring(4)))throw new IllegalArgumentException("规范链接与主帖频道身份不符。");
            var frozen=new Input(defaultName(input.name(),post.title()),canonical,"","",input.types(),input.includeMain(),input.targetDirectory(),input.window(),"POST",post.key(),post.mainMessageId());
            return new Resolved(frozen,new ManagedChannel(post.channelId(),post.channelTitle()),post);
        }
        var channel=channels.downloadChannel(input.source());current(session);
        return new Resolved(resolved(input,channel.id(),channel.title()),channel,null);
    }
    private Input resolved(Input input,String channel,String title) {
        return new Input(defaultName(input.name(),title),channel,input.tags(),input.keywords(),input.types(),input.includeMain(),input.targetDirectory(),input.window());
    }
    private static String defaultName(String name,String fallback){name=name.isBlank()?(fallback==null||fallback.isBlank()?"主帖下载":fallback):name;if(name.length()>80){name=name.substring(0,80);if(Character.isHighSurrogate(name.charAt(79)))name=name.substring(0,79);}return name;}
    static String postLink(String raw) {
        try {
            var uri=URI.create(raw.strip());String host=uri.getHost();String path=uri.getRawPath();
            if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||!(host.equalsIgnoreCase("t.me")||host.equalsIgnoreCase("telegram.me"))||uri.getUserInfo()!=null||uri.getPort()!=-1||uri.getFragment()!=null
                    ||path==null||!(path.matches("/c/[1-9][0-9]{0,18}/[1-9][0-9]{0,18}")||path.matches("/[A-Za-z][A-Za-z0-9_]{0,63}/[1-9][0-9]{0,18}"))
                    ||uri.getRawQuery()!=null&&!uri.getRawQuery().matches("single(?:=1)?"))throw new IllegalArgumentException();
            String message=path.substring(path.lastIndexOf('/')+1);if(!cursor(message))throw new IllegalArgumentException();
            if(path.startsWith("/c/")){String channel=path.split("/")[2];if(!cursor(channel))throw new IllegalArgumentException();}
            return "https://t.me"+path;
        }catch(RuntimeException invalid){throw new IllegalArgumentException("请填写 Telegram 频道主帖链接，不能使用频道首页、评论或讨论串链接。");}
    }
    private View view(Rule rule) {
        var summary=repository.summary(rule);boolean running;synchronized(busy){running=busy.contains(rule.id());}
        var job=summary.job();boolean attention=summary.scanError()!=null||summary.errors()>0||job!=null&&!job.manual()&&(job.held()||"FAILED".equals(job.state())||job.ruleVersion()!=rule.version());
        String state=!rule.enabled()?"PAUSED":!rule.input().window().allows(Instant.ofEpochMilli(now()))?"WAITING_WINDOW":attention?"ATTENTION":running?"SCANNING":
            job!=null&&!"COMPLETED".equals(job.state())&&!job.manual()?"DOWNLOADING":"WAITING";
        return new View(rule,summary,state,running,rule.input().window().nextAllowed(Instant.ofEpochMilli(now())));
    }
    private Session capture(){var session=new Session(telegram.accountId(),telegram.sessionGeneration());current(session);return session;}
    private void current(Session session){if(!Objects.equals(session.account(),telegram.accountId())||session.generation()!=telegram.sessionGeneration())throw changed();}
    private void requireAllowed(Rule rule){if(!rule.enabled())throw new IllegalArgumentException("规则已暂停，请先明确启用。");if(!rule.input().window().allows(Instant.ofEpochMilli(now())))throw new IllegalArgumentException("当前不在本规则的北京时间执行窗口内。");}
    private long now(){return clock.getAsLong();}
    private static boolean cursor(String value){try{return value!=null&&Long.parseLong(value)>0&&value.equals(Long.toString(Long.parseLong(value)));}catch(NumberFormatException invalid){return false;}}
    private static void id(String value){if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("规则或操作标识无效。");}
    private static IllegalArgumentException changed(){return new IllegalArgumentException("规则、账号、会话或执行时段已变化，旧扫描结果已停止。");}
    public void stop(){closed=true;}
}
