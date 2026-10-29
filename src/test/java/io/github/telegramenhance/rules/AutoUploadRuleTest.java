package io.github.telegramenhance.rules;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.UploadPreviewModels.*;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import io.github.telegramenhance.rules.RuleModels.*;
import io.github.telegramenhance.rules.RuleModels.Input;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Real files/database; native metadata and successful receipt updates are isolated test doubles. */
class AutoUploadRuleTest{
    @TempDir Path root;
    static final String LINK="https://t.me/synthetic/1";
    static Window all(){return new Window("ALL_DAY",null,null,"Asia/Shanghai");}
    static Window night(){return new Window("WINDOW","22:00","08:00","Asia/Shanghai");}
    @Test void oldJsonDefaultsToObservationAndAutoRequiresARealBoundPreviewBeforeEnable()throws Exception{try(var f=new Fixture(all())){
        String old="{\"name\":\"old\",\"link\":\"https://t.me/synthetic/1\",\"sourceDirectory\":\"source\",\"archiveDirectory\":\"archive\",\"recursive\":true,\"captionMode\":\"FILENAME\",\"window\":{\"mode\":\"ALL_DAY\",\"timezone\":\"Asia/Shanghai\"}}";
        assertEquals("OBSERVE_ONLY",JsonMapper.builder().build().readValue(old,Input.class).executionMode());assertFalse(f.rule().enabled());assertThrows(IllegalArgumentException.class,()->f.service.toggle(f.id,new RuleService.Toggle(f.rule().revision(),true)));assertEquals(0,f.tasks().size());assertFalse(Files.exists(f.archive));
    }}
    @Test void disabledAutoRuleNeverProducesAndManualObserveAndPreviewNeverCreateTasks()throws Exception{try(var f=new Fixture(all())){
        f.file("one.txt","one",0);f.service.monitorOnce(()->{}).get();f.service.observe(f.id,new RuleService.Position(f.rule().revision())).get();f.service.preview(f.id,new RuleService.Position(f.rule().revision())).get();assertTrue(f.tasks().isEmpty());assertFalse(Files.exists(f.archive));assertEquals(0,f.count("auto_rule_authorizations"));
    }}
    @Test void oneHomogeneousGroupIsProducedAndTheSameRuleCannotCreateASecondActiveTask()throws Exception{try(var f=new Fixture(all())){
        for(int n=1;n<=12;n++)f.file("video"+n+".mp4","video-"+n,-20000);f.file("photo.jpg","photo",0);f.file("audio.mp3","audio",0);f.enable();f.tick();
        assertEquals(1,f.tasks().size());var t=f.tasks().getFirst();assertEquals("QUEUED",t.state());assertEquals(10,t.groups().getFirst().items().size());assertEquals("VIDEO",t.groups().getFirst().mediaType());assertEquals(10,t.prepared().scan().files().size());assertEquals("AUTO_UPLOAD",f.service.get(f.id).mode());assertEquals(t.id(),f.service.get(f.id).job().taskId());
        f.clock.advance(31);f.tick();assertEquals(1,f.tasks().size());assertEquals(1,f.count("auto_upload_bindings"));assertFalse(Files.exists(f.archive));
    }}
    @Test void outsideWindowDoesNotScanOrCreateAndCrossMidnightStartsAtTheInclusiveBoundary()throws Exception{try(var f=new Fixture(night())){
        f.file("one.txt","one",0);f.enable();f.clock.at("12:00");f.tick();assertTrue(f.tasks().isEmpty());assertEquals("WAITING_WINDOW",f.service.get(f.id).state());assertNull(f.rule().observation());
        f.clock.at("21:59");f.tick();assertTrue(f.tasks().isEmpty());f.clock.at("22:00");f.tick();assertEquals(1,f.tasks().size());
    }}
    @Test void preparingAcrossWindowEndNeverSendsThenResumesTheSameTaskInTheNextWindow()throws Exception{try(var f=new Fixture(night())){
        f.file("one.txt","one",0);f.enable();f.clock.at("23:00");f.tick();String task=f.tasks().getFirst().id();f.clock.at("07:59",1);var nativeTransport=f.transport();var boundary=new AtomicBoolean(true);
        when(f.targets.read(anyString(),eq("1"),anyLong(),anySet())).thenAnswer(i->{if(boundary.get())f.clock.at("08:00",1);return f.target;});
        try(var engine=f.engine(nativeTransport)){until(()->f.uploads.task("1",task).state().equals("PAUSED"));verify(nativeTransport,never()).send(any(),any(),anyLong());assertEquals("WINDOW",f.policy.pauseKind("1",task));assertFalse(Files.exists(f.archive));}
        boundary.set(false);f.clock.at("22:00",1);f.tick();assertEquals(task,f.tasks().getFirst().id());assertEquals("QUEUED",f.uploads.task("1",task).state());f.succeed(nativeTransport);
        try(var engine=f.engine(nativeTransport)){until(()->f.uploads.task("1",task).state().equals("COMPLETED"));}assertEquals(1,f.tasks().size());assertEquals("one",Files.readString(f.archive.resolve(f.uploads.task("1",task).groups().getFirst().items().getFirst().archiveRelative())));
    }}
    @Test void pausingRuleBeforeIntentStopsSendingAndExplicitReenableCanResume()throws Exception{try(var f=new Fixture(all())){
        f.file("one.txt","one",0);f.enable();f.tick();String task=f.tasks().getFirst().id();f.service.toggle(f.id,new RuleService.Toggle(f.rule().revision(),false));var transport=f.transport();try(var engine=f.engine(transport)){until(()->f.uploads.task("1",task).state().equals("PAUSED"));verify(transport,never()).send(any(),any(),anyLong());}
        f.enable();f.tick();assertEquals("QUEUED",f.uploads.task("1",task).state());assertEquals(1,f.tasks().size());
    }}
    @Test void editsPauseTheRuleAndNeverRewriteOldTaskAndTakeoverDoesNotStartIt()throws Exception{try(var f=new Fixture(all())){
        f.file("old.txt","old",0);f.enable();f.tick();var old=f.tasks().getFirst();var snapshot=old.prepared();var changed=new Input("new name",LINK,f.source.toString(),f.archive.toString(),true,"EMPTY",all(),"AUTO_UPLOAD");f.service.update(f.id,new RuleService.Update(f.rule().revision(),changed));assertFalse(f.rule().enabled());assertEquals(snapshot,f.uploads.task("1",old.id()).prepared());
        var transport=f.transport();try(var engine=f.engine(transport)){until(()->f.uploads.task("1",old.id()).state().equals("PAUSED"));}f.enable();f.tick();assertEquals(1,f.tasks().size());assertEquals("PAUSED",f.uploads.task("1",old.id()).state());
        f.uploads.takeover("1",old.id());assertTrue(f.uploads.view(f.uploads.task("1",old.id())).automation().manualTakeover());assertEquals("PAUSED",f.uploads.task("1",old.id()).state());assertEquals(snapshot,f.uploads.task("1",old.id()).prepared());
        f.file("new.txt","new",0);f.clock.advance(31);f.tick();assertEquals(2,f.tasks().size());assertEquals(1,f.tasks().stream().filter(t->t.state().equals("QUEUED")).count());assertEquals("old name",f.uploads.view(f.uploads.task("1",old.id())).automation().name());
    }}
    @Test void existingContentClaimsAreSkippedWithoutMovingFilesOrMergingRequests()throws Exception{try(var f=new Fixture(all())){
        f.file("old.txt","same",0);var scan=f.scan();var prepared=new UploadPreviewService.Prepared(f.rule().input().uploadInput(),f.target,scan,UploadPreviewService.groups(scan.files(),"FILENAME"));var manual=f.uploads.create("1",UUID.randomUUID().toString(),"manual",prepared);f.file("new.txt","different",0);f.enable();f.tick();assertEquals(2,f.tasks().size());var automatic=f.tasks().stream().filter(t->!t.id().equals(manual.id())).findFirst().orElseThrow();assertEquals(1,automatic.groups().getFirst().items().size());assertEquals("new.txt",automatic.groups().getFirst().items().getFirst().file().relativePath());assertTrue(Files.exists(f.source.resolve("old.txt")));assertEquals(1,f.rule().observation().upload().claimed());assertFalse(Files.exists(f.archive));
        assertTrue(f.uploads.claimed("2",f.target.post(),scan.files()).isEmpty());
    }}
    @Test void unresolvedOrPreparationFailureTasksBlockFurtherAutomaticProduction()throws Exception{
        for(String state:List.of("NEEDS_REVIEW","SENT_PENDING_ARCHIVE","PAUSED"))try(var f=new Fixture(all())){f.file("first.txt","first",0);f.enable();f.tick();var task=f.tasks().getFirst();f.uploads.state(task,state,"requires operator");f.file("second.txt","second",0);f.clock.advance(31);f.tick();assertEquals(1,f.tasks().size(),state);assertTrue(Files.exists(f.source.resolve("second.txt")));}
    }
    @Test void restartResumesOnlyNeverDispatchedAutomaticWorkAndKeepsUnknownSendsForReview()throws Exception{
        try(var f=new Fixture(all())){f.file("one.txt","one",0);f.enable();f.tick();String id=f.tasks().getFirst().id();new UploadRepository(f.db,f.policy);assertEquals("PAUSED",f.uploads.task("1",id).state());assertEquals("RESTART_READY",f.policy.pauseKind("1",id));f.clock.advance(31);f.tick();assertEquals("QUEUED",f.uploads.task("1",id).state());assertEquals(1,f.tasks().size());}
        try(var f=new Fixture(all())){f.file("one.txt","one",0);f.enable();f.tick();var task=f.uploads.next("1");var group=task.groups().getFirst();f.uploads.preparing(task,group);for(var item:group.items())f.files.stage(task,item,()->{});task=f.uploads.task("1",task.id());f.uploads.intent(task,task.groups().getFirst(),"native-old");f.uploads.dispatch(task,task.groups().getFirst(),"native-old");new UploadRepository(f.db,f.policy);assertEquals("NEEDS_REVIEW",f.uploads.task("1",task.id()).state());f.clock.advance(31);f.tick();assertEquals(1,f.tasks().size());assertEquals("NEEDS_REVIEW",f.tasks().getFirst().state());}
    }
    @Test void staleRevisionOrChangedNativeTargetCannotCreateAnyAutomaticTask()throws Exception{try(var f=new Fixture(all())){
        f.file("one.txt","one",0);f.enable();var before=f.rule();var scan=f.scan();f.service.toggle(f.id,new RuleService.Toggle(before.revision(),false));assertThrows(IllegalArgumentException.class,()->f.broker.consider(before,scan,()->{}));assertTrue(f.tasks().isEmpty());f.enable();
        var p=f.target.post();var altered=new Post(p.key(),p.accountId(),p.channelId(),p.channelTitle(),p.mainMessageId(),p.title(),p.text(),p.albumId(),p.mainMessages(),p.mainMedia(),"-9999","9999",p.discussionRoots(),true,p.coverage(),p.warnings(),p.albumEvidence(),p.mainCoverage());when(f.targets.read(anyString(),eq("1"),anyLong(),anySet())).thenReturn(new Target(altered,"other","MEDIA_ALLOWED_AT_CHECK",0,1));f.tick();assertTrue(f.tasks().isEmpty());assertEquals("BLOCKED",f.rule().observation().upload().state());
    }}
    @Test void retryPlanCannotBypassAutomaticWindowButManualTakeoverIsExplicit()throws Exception{try(var f=new Fixture(night())){
        f.file("one.txt","one",0);f.enable();f.clock.at("23:00");f.tick();var task=f.tasks().getFirst();f.uploads.pause("1",task.id());f.clock.at("12:00",1);assertThrows(RuleUploadPolicy.Blocked.class,()->f.uploads.start("1",task.id()));assertEquals("PAUSED",f.uploads.task("1",task.id()).state());f.uploads.takeover("1",task.id());assertEquals("PAUSED",f.uploads.task("1",task.id()).state());f.uploads.start("1",task.id());assertEquals("QUEUED",f.uploads.task("1",task.id()).state());
    }}
    static void until(BooleanSupplier value)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(!value.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(20);assertTrue(value.getAsBoolean());}
    static final class MutableClock extends Clock{Instant now=Instant.now();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}void advance(long seconds){now=now.plusSeconds(seconds);}void at(String value){at(value,0);}void at(String value,int days){now=LocalDate.of(2026,10,28).plusDays(days).atTime(LocalTime.parse(value)).atZone(RuleModels.ZONE).toInstant();}}
    final class Fixture implements AutoCloseable{
        final Path source,archive;final AppPaths paths;final Database db;final MutableClock clock=new MutableClock();final RuleUploadPolicy policy;final RuleRepository rules;final UploadRepository uploads;final UploadDirectoryScanner scanner;final UploadFiles files;final TelegramService telegram=mock(TelegramService.class);final ResourceService reads=mock(ResourceService.class);final UploadTargetReader targets=mock(UploadTargetReader.class);final Target target;final UploadPreviewService previews;final RuleUploadBroker broker;final RuleService service;final String id;
        Fixture(Window window)throws Exception{Path local=Files.createDirectories(root.resolve(UUID.randomUUID().toString()));source=Files.createDirectories(local.resolve("source"));archive=local.resolve("source-已上传");paths=new AppPaths(local.resolve("app"));db=new Database(paths);policy=new RuleUploadPolicy(db,clock);rules=new RuleRepository(db);uploads=new UploadRepository(db,policy);scanner=new UploadDirectoryScanner(new DownloadPaths(paths));files=new UploadFiles(paths,new DownloadPaths(paths),uploads);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);when(reads.readWork(any())).thenAnswer(i->CompletableFuture.supplyAsync(()->((Supplier<?>)i.getArgument(0)).get(),Runnable::run));var post=new Post("1:-1001:100","1","-1001","channel","100","post","text","0",List.of(),List.of(),"-2001","500",List.of(),true,"COMPLETE",List.of(),null,"COMPLETE");target=new Target(post,"discussion","MEDIA_ALLOWED_AT_CHECK",0,1);when(targets.read(anyString(),eq("1"),anyLong(),anySet())).thenReturn(target);previews=new UploadPreviewService(telegram,reads,targets,scanner);broker=new RuleUploadBroker(rules,policy,uploads,reads,targets,telegram,clock);service=new RuleService(rules,scanner,previews,telegram,clock,broker);id=service.create(new RuleService.Create(UUID.randomUUID().toString(),new Input("old name",LINK,source.toString(),archive.toString(),true,"FILENAME",window,"AUTO_UPLOAD"))).rule().id();}
        Rule rule(){return rules.get("1",id);}List<Task> tasks(){return uploads.list("1",50,0);}Scan scan(){return scanner.scan(source.toString(),archive.toString(),true,()->{});}
        void file(String name,String content,long age)throws Exception{Path p=source.resolve(name);Files.writeString(p,content);Files.setLastModifiedTime(p,FileTime.fromMillis(System.currentTimeMillis()-10000+age));}
        void enable()throws Exception{Instant saved=clock.now;clock.now=Instant.now();var checked=service.preview(id,new RuleService.Position(rule().revision())).get(10,TimeUnit.SECONDS);service.toggle(id,new RuleService.Toggle(rule().revision(),true,checked.preview().id()));clock.now=saved;}
        void tick()throws Exception{service.monitorOnce(()->{}).get(10,TimeUnit.SECONDS);}
        int count(String table){return db.read(c->{try(var q=c.createStatement();var r=q.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getInt(1);}});}
        UploadTransport transport(){var transport=mock(UploadTransport.class);when(transport.owner(anyLong())).thenReturn("test-native");return transport;}
        void succeed(UploadTransport transport){doAnswer(i->{Task t=i.getArgument(0);io.github.telegramenhance.transfer.UploadTaskModels.Group g=i.getArgument(1);uploads.dispatch(t,g,"test-native");var initial=new ArrayList<Initial>();for(var item:g.items())initial.add(new Initial(Integer.toString(100+item.ordinal()),(int)g.id(),true,null));uploads.initial(t,g,initial);for(var item:g.items())uploads.event(g.id(),new Evidence(Integer.toString(100+item.ordinal()),Integer.toString(1000+item.ordinal()),"-2001","500",null,g.mode().equals("ALBUM")?"77":"0",item.file().mediaType(),item.caption(),"remote-"+item.id(),true,true,1));return null;}).when(transport).send(any(),any(),anyLong());}
        UploadEngine engine(UploadTransport transport){return new UploadEngine(uploads,files,transport,telegram,targets,reads);}
        public void close(){previews.close();db.close();}
    }
}
