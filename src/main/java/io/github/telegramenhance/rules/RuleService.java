package io.github.telegramenhance.rules;

import io.github.telegramenhance.rules.RuleModels.*;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.UploadDirectoryScanner;
import io.github.telegramenhance.transfer.UploadPreviewService;
import java.net.URI;
import java.nio.file.Path;
import io.github.telegramenhance.transfer.DownloadPaths;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public final class RuleService {
    private final RuleRepository repository;private final UploadDirectoryScanner scanner;private final UploadPreviewService previews;private final TelegramService telegram;private final Clock clock;private final RuleUploadBroker broker;
    private final ConcurrentMap<String,String> active=new ConcurrentHashMap<>();
    private record Activation(String rule,String account,long generation,long version,long revision,long expires){}
    private final LinkedHashMap<String,Activation> activations=new LinkedHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public RuleService(RuleRepository repository,UploadDirectoryScanner scanner,UploadPreviewService previews,TelegramService telegram,RuleUploadBroker broker){this(repository,scanner,previews,telegram,Clock.systemUTC(),broker);}
    public RuleService(RuleRepository repository,UploadDirectoryScanner scanner,UploadPreviewService previews,TelegramService telegram){this(repository,scanner,previews,telegram,Clock.systemUTC(),null);}
    RuleService(RuleRepository repository,UploadDirectoryScanner scanner,UploadPreviewService previews,TelegramService telegram,Clock clock){this(repository,scanner,previews,telegram,clock,null);}
    RuleService(RuleRepository repository,UploadDirectoryScanner scanner,UploadPreviewService previews,TelegramService telegram,Clock clock,RuleUploadBroker broker){this.repository=repository;this.scanner=scanner;this.previews=previews;this.telegram=telegram;this.clock=clock;this.broker=broker;}
    public record Create(String operationId,Input input){}
    public record Update(long revision,Input input){}
    public record Toggle(long revision,boolean enabled,String previewId){public Toggle(long revision,boolean enabled){this(revision,enabled,null);}}
    public record Position(long revision){}
    public View create(Create request){String account=telegram.accountId();long generation=telegram.sessionGeneration();if(request==null)throw new IllegalArgumentException("请填写规则");id(request.operationId());Input input=normalize(request.input());current(account,generation);return view(repository.create(account,request.operationId(),input,clock.millis()));}
    public View update(String id,Update request){String account=telegram.accountId();long generation=telegram.sessionGeneration();id(id);if(request==null)throw new IllegalArgumentException("请填写规则");Input input=normalize(request.input());current(account,generation);return view(repository.update(account,id,request.revision(),input,clock.millis()));}
    public View toggle(String id,Toggle request){String account=telegram.accountId();long generation=telegram.sessionGeneration();id(id);if(request==null)throw new IllegalArgumentException("请选择规则状态");var rule=repository.get(account,id);io.github.telegramenhance.transfer.UploadPreviewModels.Target approved=null;
        if(request.enabled()&&rule.input().executionMode().equals("AUTO_UPLOAD")){
            Activation check;synchronized(activations){check=activations.get(request.previewId());}
            if(check==null||!check.rule().equals(id)||!check.account().equals(account)||check.generation()!=generation||check.version()!=rule.version()||check.revision()!=rule.revision()||check.revision()!=request.revision()||check.expires()<=clock.millis())throw new IllegalArgumentException("请重新预览并确认自动上传目标、目录和时段");
            var preview=previews.get(request.previewId());if(!preview.postLink().equals(rule.input().link())||!DownloadPaths.key(Path.of(preview.sourceDirectory())).equals(DownloadPaths.key(Path.of(rule.input().sourceDirectory())))||!DownloadPaths.key(Path.of(preview.archiveDirectory())).equals(DownloadPaths.key(Path.of(rule.input().archiveDirectory())))||preview.recursive()!=rule.input().recursive()||!preview.captionMode().equals(rule.input().captionMode()))throw new IllegalArgumentException("启用预览与规则参数不符，请重新核对");approved=previews.target(request.previewId());
        }
        current(account,generation);return view(repository.enabled(account,id,request.revision(),request.enabled(),clock.millis(),approved));}
    public Listing list(){String account=telegram.accountId();var out=repository.list(account).stream().map(this::view).toList();currentAccount(account);return new Listing(out,"RULES",30);}
    public View get(String id){String account=telegram.accountId();id(id);var result=view(repository.get(account,id));currentAccount(account);return result;}
    public View lookup(String operation){String account=telegram.accountId();id(operation);var rule=repository.byOperation(account,operation);currentAccount(account);return rule==null?null:view(rule);}
    public List<Version> versions(String id){String account=telegram.accountId();id(id);var out=repository.versions(account,id);currentAccount(account);return out;}
    public CompletableFuture<View> observe(String id,Position position){String account=telegram.accountId();id(id);var rule=repository.get(account,id);position(rule,position);return scan(rule,false,()->{});}
    public CompletableFuture<Preview> preview(String id,Position position){String account=telegram.accountId();long generation=telegram.sessionGeneration();id(id);var rule=repository.get(account,id);position(rule,position);
        return previews.previewRule(rule.input().uploadInput()).thenApply(result->{require(rule,generation,false);if(rule.input().executionMode().equals("AUTO_UPLOAD")){synchronized(activations){activations.values().removeIf(a->a.expires()<=clock.millis()||!a.account().equals(account)||a.generation()!=generation);activations.put(result.id(),new Activation(rule.id(),account,generation,rule.version(),rule.revision(),result.expiresAt()));while(activations.size()>8)activations.remove(activations.keySet().iterator().next());}}return new Preview(rule.id(),rule.version(),rule.revision(),result);});
    }
    public CompletableFuture<?> monitorOnce(Runnable running){String account=telegram.accountId();running.run();var rule=repository.due(account,clock.millis());if(rule==null)return CompletableFuture.completedFuture(null);
        if(!rule.input().window().allows(clock.instant())){repository.defer(rule,rule.input().window().nextAllowed(clock.instant()));return CompletableFuture.completedFuture(null);}
        if(!repository.defer(rule,clock.millis()+30_000))return CompletableFuture.completedFuture(null);
        if(rule.input().executionMode().equals("AUTO_UPLOAD")){
            if(broker==null)return CompletableFuture.failedFuture(new IllegalStateException("自动上传未初始化"));
            if(broker.resumeActive(rule,running))return CompletableFuture.completedFuture(view(repository.get(account,rule.id())));
            return scan(rule,true,running).thenCompose(result->{if(!result.rule().observation().outcome().equals("OBSERVED"))return CompletableFuture.completedFuture(null);return broker.consider(result.rule(),result.rule().observation().scan(),running);});
        }
        return scan(rule,true,running);
    }
    private CompletableFuture<View> scan(Rule rule,boolean automatic,Runnable running){
        long generation=telegram.sessionGeneration();String token=UUID.randomUUID().toString();if(active.putIfAbsent(rule.id(),token)!=null)throw new IllegalArgumentException("这条规则正在检测，请等待本次结果");
        var stopped=new AtomicBoolean();long[] last={0};Runnable check=()->{
            running.run();current(rule.accountId(),generation);if(stopped.get())throw new IllegalArgumentException("本次检测已停止");
            long now=System.nanoTime();if(now-last[0]>TimeUnit.MILLISECONDS.toNanos(250)){require(rule,generation,automatic);last[0]=now;}
        };
        try{return previews.observeDirectories(rule.input().uploadInput(),check).orTimeout(70,TimeUnit.SECONDS).handle((result,failure)->{
            try{running.run();require(rule,generation,automatic);
                String error=failure==null?null:"目录检测未完成，请检查文件是否复制完成、目录权限和预览范围；本轮未执行上传";
                if(!repository.observe(rule,result,error,automatic,clock.millis()))throw new IllegalArgumentException("规则状态已变化，旧检测结果已丢弃");
                active.remove(rule.id(),token);return view(repository.get(rule.accountId(),rule.id()));
            }finally{stopped.set(true);active.remove(rule.id(),token);}
        });}catch(RuntimeException rejected){stopped.set(true);active.remove(rule.id(),token);throw rejected;}
    }
    private void require(Rule expected,long generation,boolean automatic){current(expected.accountId(),generation);var current=repository.get(expected.accountId(),expected.id());if(current.revision()!=expected.revision()||automatic&&!current.enabled())throw new IllegalArgumentException("规则已更新或暂停，旧读取结果已丢弃");}
    private View view(Rule rule){var now=clock.instant();String state=!rule.enabled()?"PAUSED":rule.input().window().allows(now)?"OBSERVING":"WAITING_WINDOW";return new View(rule,rule.input().executionMode(),state,active.containsKey(rule.id()),rule.input().window().nextAllowed(now),repository.latestJob(rule.accountId(),rule.id()));}
    private Input normalize(Input input){
        if(input==null||input.name()==null||input.name().strip().isEmpty()||input.name().strip().length()>80||input.window()==null||!("FILENAME".equals(input.captionMode())||"EMPTY".equals(input.captionMode())))throw new IllegalArgumentException("请填写80字以内规则名称、目录、目标和观察时段");
        String link=checkedLink(input.link());var dirs=scanner.ruleDirectories(input.sourceDirectory());
        return new Input(input.name().strip(),link,dirs.source().toString(),dirs.archive().toString(),input.recursive(),input.captionMode(),input.window(),input.executionMode());
    }
    static String checkedLink(String value){
        if(value==null||value.length()>2048)throw new IllegalArgumentException("请输入有效的Telegram频道主帖链接");String link=value.strip();
        try{var uri=URI.create(link);if(!"https".equals(uri.getScheme())||!Set.of("t.me","telegram.me").contains(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()!=-1||!uri.getPath().matches("/(?:c/[0-9]+|[A-Za-z0-9_]{3,})/[1-9][0-9]*/?"))throw new IllegalArgumentException();}catch(RuntimeException invalid){throw new IllegalArgumentException("请输入有效的Telegram频道主帖链接");}return link;
    }
    private void current(String account,long generation){if(Thread.currentThread().isInterrupted()||!account.equals(telegram.accountId())||generation!=telegram.sessionGeneration())throw new IllegalArgumentException("账号或会话已变化，请刷新规则");}
    private void currentAccount(String account){if(!account.equals(telegram.accountId()))throw new IllegalArgumentException("账号已变化，请刷新规则");}
    private static void position(Rule rule,Position position){if(position==null||position.revision()!=rule.revision())throw new IllegalArgumentException("规则已变化，请刷新后核对版本");}
    private static void id(String id){if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("规则标识无效");}
}
