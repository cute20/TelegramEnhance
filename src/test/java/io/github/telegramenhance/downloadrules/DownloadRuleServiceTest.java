package io.github.telegramenhance.downloadrules;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.downloadrules.DownloadRuleModels.*;
import io.github.telegramenhance.library.*;
import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import io.github.telegramenhance.library.ChannelImportModels.*;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.rules.RuleModels.Window;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.DownloadModels.FrozenFile;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadRuleServiceTest {
    @TempDir Path directory;
    static final Window ALL=new Window("ALL_DAY",null,null,"Asia/Shanghai");
    static Post post(int id,String text) {
        return new Post("1:-1001:"+id,"1","-1001","channel",""+id,"post "+id,text,"0",List.of(new MessageRef("-1001",""+id,"https://t.me/synthetic/"+id)),List.of(),"-2001","50",List.of(new MessageRef("-2001","50",null)),true,"PARTIAL",List.of(),new AlbumEvidence(true,"synthetic","1","2"),"COMPLETE");
    }
    static Media media(int id,String type){return new Media("1:-2001:"+id+":0",new MessageRef("-2001",""+id,"https://t.me/synthetic/"+id),"COMMENT",type,"sample"+id+".bin",3,"caption");}
    static Page page(Post post,List<Media> media,String cursor,boolean eof){return new Page(post.key(),media,media.stream().map(Media::message).toList(),media.size(),cursor,eof,eof?"COMPLETE":"PARTIAL",List.of());}
    private final class Fixture implements AutoCloseable {
        final AppPaths paths=new AppPaths(directory.resolve(UUID.randomUUID().toString()));
        final Database db=new Database(paths);final DownloadRepository downloads=new DownloadRepository(db);
        final DownloadRuleRepository repository=new DownloadRuleRepository(db,downloads);
        final ResourceService work=mock(ResourceService.class);final ChannelReader channels=mock(ChannelReader.class);
        final ResourceReader posts=mock(ResourceReader.class);final DownloadFileReader files=mock(DownloadFileReader.class);
        final TelegramService telegram=mock(TelegramService.class);final AtomicLong generation=new AtomicLong(1),clock=new AtomicLong(1_000_000);
        final AtomicReference<String> account=new AtomicReference<>("1");final ManagedChannel channel=new ManagedChannel("-1001","channel");
        final Path root=directory.resolve("out").toAbsolutePath();final Map<String,Post> values=new HashMap<>();
        final Deque<HistoryPage> history=new ArrayDeque<>();final Map<String,Deque<Page>> comments=new HashMap<>();
        final DownloadRuleService service;
        Fixture(){
            when(telegram.accountId()).thenAnswer(i->account.get());when(telegram.sessionGeneration()).thenAnswer(i->generation.get());
            when(work.readWork(any())).thenAnswer(i->{try{return CompletableFuture.completedFuture(((Supplier<?>)i.getArgument(0)).get());}catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}});
            when(channels.downloadChannel(anyString())).thenReturn(channel);
            when(channels.downloadHistoryPage(anyString(),nullable(String.class))).thenAnswer(i->history.remove());
            when(posts.downloadChannelPost(anyString(),anyString(),anyLong(),any())).thenAnswer(i->{((Runnable)i.getArgument(3)).run();return values.get(i.getArgument(1));});
            when(posts.probeDownloadSource(anyString(),anyLong(),any())).thenAnswer(i->{((Runnable)i.getArgument(2)).run();return values.get("selected");});
            when(posts.downloadComments(any(),nullable(String.class),anyLong(),any())).thenAnswer(i->{((Runnable)i.getArgument(3)).run();var p=(Post)i.getArgument(0);var queue=comments.get(p.key());return queue==null||queue.isEmpty()?page(p,List.of(),null,true):queue.remove();});
            when(files.freeze(any(),anyList(),anyLong())).thenAnswer(i->((List<Media>)i.getArgument(1)).stream().map(m->new FrozenFile(m,"uid-"+m.key(),3,m.fileName())).toList());
            var policy=new DownloadRulePolicy(db,new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(clock.get());}});
            service=new DownloadRuleService(repository,work,channels,posts,files,new DownloadPaths(paths),policy,telegram,clock::get);
        }
        Input input(String tags,String keywords,Window window){return new Input("sample","@synthetic",tags,keywords,List.of("DOCUMENT"),false,root.toString(),window);}
        Input postInput(List<String> types,boolean main){return new Input("","https://telegram.me/synthetic/100?single","","",types,main,root.toString(),ALL,"POST","untrusted-key","999");}
        View create(Input input)throws Exception{return service.create(new DownloadRuleService.Create(UUID.randomUUID().toString(),input)).get();}
        View enable(View v){return service.toggle(v.rule().id(),new DownloadRuleService.Toggle(v.rule().revision(),true));}
        View step(View v)throws Exception{return service.check(v.rule().id(),new DownloadRuleService.Position(v.rule().revision())).get(5,TimeUnit.SECONDS);}
        View until(View v,java.util.function.Predicate<View> done,boolean complete)throws Exception {
            for(int n=0;n<30&&!done.test(v);n++){v=step(v);if(complete)completeOutputs();v=service.get(v.rule().id());}
            assertTrue(done.test(v),"bounded scan did not reach the expected observed state");return v;
        }
        View commentsReady(View v,Post post)throws Exception{return until(v,value->{var state=repository.getPost(value.rule(),post.key());return state!=null&&"COMMENTS".equals(state.state());},false);}
        void queue(Post... posts){for(var p:posts)values.put(p.mainMessageId(),p);history.add(new HistoryPage(channel,Arrays.stream(posts).map(p->new Candidate(p.mainMessageId(),p.albumId(),p.title())).toList(),"CHANNEL_PAGE",10,null,true,false,List.of()));}
        void completeOutputs()throws Exception {
            var store=new DownloadFileStore(new DownloadPaths(paths),downloads);
            for(var task:downloads.list("1",50,0))if(!task.state().equals("COMPLETED")){
                for(var item:task.items())if(!item.state().equals("COMPLETED"))store.write(task,item,(offset,count)->Arrays.copyOfRange(new byte[]{1,2,3},(int)offset,(int)offset+count),()->{});
                downloads.finishState("1",task.id(),"COMPLETED",null);
            }
        }
        public void close(){db.close();}
    }
    @Test void savingIsPausedIdempotentAndDoesNotReadHistoryOrDownload()throws Exception {try(var f=new Fixture()){
        String op=UUID.randomUUID().toString();var request=new DownloadRuleService.Create(op,f.input("旅行","BODY",ALL));
        var v=f.service.create(request).get();assertFalse(v.rule().enabled());assertEquals("body",v.rule().input().keywords());assertEquals("-1001",v.rule().channelId());
        assertEquals(v.rule().id(),f.service.create(request).get().rule().id());assertEquals(v.rule().id(),f.service.lookup(op).rule().id());
        assertThrows(IllegalArgumentException.class,()->f.step(v));verify(f.channels,never()).downloadHistoryPage(anyString(),nullable(String.class));verifyNoInteractions(f.files);assertFalse(Files.exists(f.root));
    }}
    @Test void historyCursorTasksAndClaimsProgressThenHeadAndOldCommentsFindOnlyNewVersions()throws Exception {try(var f=new Fixture()){
        var a=post(100,"#旅行 body");var b=post(90,"#其他 body");f.queue(a,b);f.comments.put(a.key(),new ArrayDeque<>(List.of(page(a,List.of(media(1000,"DOCUMENT"),media(999,"PHOTO")),"999",false))));
        var v=f.enable(f.create(f.input("旅行","body",ALL)));v=f.step(v);assertNotNull(f.repository.scan(v.rule()).pending());
        v=f.until(v,value->value.summary().media()==1,false);assertEquals(1,f.downloads.list("1",50,0).size());
        f.completeOutputs();v=f.until(v,value->value.summary().historyDone()&&value.summary().unresolved()==0,true);assertEquals(2,v.summary().scanned());assertEquals(1,v.summary().matched());assertEquals(1,v.summary().completed());
        assertEquals(0,v.summary().unresolved());
        // A later head cycle sees a new post before the persisted known boundary.
        var newest=post(110,"#旅行 body");f.queue(newest,a);f.comments.put(newest.key(),new ArrayDeque<>(List.of(page(newest,List.of(media(1100,"DOCUMENT")),"1100",false))));f.clock.addAndGet(61_000);
        v=f.until(v,value->value.summary().media()==2,true);v=f.until(v,value->value.summary().unresolved()==0,true);assertEquals(3,f.repository.summary(v.rule()).scanned());
        // After the next head reaches a known post, an old post is re-read from its newest comments.
        f.clock.addAndGet(300_001);f.queue(newest);f.comments.put(a.key(),new ArrayDeque<>(List.of(page(a,List.of(media(1001,"DOCUMENT"),media(1000,"DOCUMENT")),"1000",false))));
        v=f.until(v,value->value.summary().media()==3,true);
        assertEquals(3,f.repository.summary(v.rule()).media());assertEquals(3,f.downloads.list("1",50,0).size());
        assertTrue(f.downloads.list("1",50,0).stream().allMatch(t->t.duplicatePolicy().equals("KEEP_DUPLICATES_V2")));
    }}
    @Test void malformedOrNonAdvancingCommentPageIsNotEofAndDoesNotLoseCheckpoint()throws Exception {try(var f=new Fixture()){
        var a=post(100,"body");f.queue(a);f.comments.put(a.key(),new ArrayDeque<>(List.of(page(a,List.of(media(1000,"DOCUMENT")),"1000",false),page(a,List.of(media(1000,"DOCUMENT")),"1000",false))));
        var v=f.enable(f.create(f.input("","",ALL)));v=f.until(v,value->value.summary().media()==1,false);f.completeOutputs();v=f.until(v,value->value.summary().errors()==1,false);
        var state=f.repository.getPost(v.rule(),a.key());assertEquals("ERROR",state.state());assertEquals("1000",state.cursor());assertEquals(1,v.summary().media());assertEquals(1,v.summary().errors());
    }}
    @Test void changedGenerationAfterFreezeCannotPublishTaskOrAdvanceSourcePosition()throws Exception {try(var f=new Fixture()){
        var a=post(100,"body");f.queue(a);f.comments.put(a.key(),new ArrayDeque<>(List.of(page(a,List.of(media(1000,"DOCUMENT")),"1000",false))));
        var v=f.enable(f.create(f.input("","",ALL)));v=f.commentsReady(v,a);var before=f.repository.getPost(v.rule(),a.key());
        when(f.files.freeze(any(),anyList(),anyLong())).thenAnswer(i->{f.generation.incrementAndGet();return List.of(new FrozenFile(media(1000,"DOCUMENT"),"new",3,"test.bin"));});
        var original=v;assertThrows(ExecutionException.class,()->f.step(original));assertEquals(before,f.repository.getPost(v.rule(),a.key()));assertTrue(f.downloads.list("1",50,0).isEmpty());
    }}
    @Test void windowEndsAfterMetadataFreezeWithoutCreatingTaskAndNextWindowCanContinue()throws Exception {try(var f=new Fixture()){
        var window=new Window("WINDOW","10:00","10:01","Asia/Shanghai");f.clock.set(Instant.parse("2026-10-28T02:00:59Z").toEpochMilli());
        var a=post(100,"body");f.queue(a);f.comments.put(a.key(),new ArrayDeque<>(List.of(page(a,List.of(media(1000,"DOCUMENT")),"1000",false))));
        var v=f.enable(f.create(f.input("","",window)));v=f.commentsReady(v,a);var before=f.repository.getPost(v.rule(),a.key());
        when(f.files.freeze(any(),anyList(),anyLong())).thenAnswer(i->{f.clock.addAndGet(2000);return List.of(new FrozenFile(media(1000,"DOCUMENT"),"new",3,"test.bin"));});
        var original=v;assertThrows(ExecutionException.class,()->f.step(original));assertEquals(before,f.repository.getPost(v.rule(),a.key()));assertTrue(f.downloads.list("1",50,0).isEmpty());assertEquals("WAITING_WINDOW",f.service.get(v.rule().id()).state());
    }}
    @Test void partialPostDoesNotBlockOtherHistoricalCandidatesOrPretendCommentEof()throws Exception {try(var f=new Fixture()){
        var a=post(100,"body");a=new Post(a.key(),a.accountId(),a.channelId(),a.channelTitle(),a.mainMessageId(),a.title(),a.text(),a.albumId(),a.mainMessages(),a.mainMedia(),a.discussionChatId(),a.discussionRootMessageId(),a.discussionRoots(),true,"PARTIAL",List.of(),null,"PARTIAL");
        var b=post(90,"body");f.queue(a,b);var v=f.enable(f.create(f.input("","",ALL)));v=f.until(v,value->value.summary().historyDone(),false);
        assertTrue(v.summary().historyDone());assertEquals(1,v.summary().errors());assertEquals("ERROR",f.repository.getPost(v.rule(),a.key()).state());assertNotNull(f.repository.getPost(v.rule(),b.key()));
    }}
    @Test void failedOrUserHeldDownloadBlocksNewProductionUntilExplicitlyHandled()throws Exception {try(var f=new Fixture()){
        var a=post(100,"body");f.queue(a);f.comments.put(a.key(),new ArrayDeque<>(List.of(page(a,List.of(media(1000,"DOCUMENT")),"1000",false))));var v=f.enable(f.create(f.input("","",ALL)));v=f.until(v,value->value.summary().media()==1,false);
        var job=f.repository.activeJob(v.rule());f.downloads.finishState("1",job.taskId(),"FAILED","synthetic disk failure");var failed=f.repository.getPost(v.rule(),a.key());v=f.step(v);
        assertEquals(failed,f.repository.getPost(v.rule(),a.key()));assertEquals("FAILED",f.repository.activeJob(v.rule()).state());assertEquals("ATTENTION",f.service.get(v.rule().id()).state());
        v=f.service.takeover(v.rule().id(),new DownloadRuleService.Takeover(v.rule().revision(),job.taskId()));assertNull(f.repository.activeJob(v.rule()));assertEquals("FAILED",f.downloads.task("1",job.taskId()).state());assertTrue(v.summary().job().manual());
    }}
    @Test void uncertainHistoryHasAJsonSafeVisibleErrorAndExplicitCheckCanRecoverIt()throws Exception {try(var f=new Fixture()){
        f.history.add(new HistoryPage(f.channel,List.of(),"CHANNEL_PAGE",10,null,true,false,List.of("unverified skipped messages")));
        var v=f.enable(f.create(f.input("","",ALL)));var initial=v;
        assertThrows(ExecutionException.class,()->f.step(initial));v=f.service.get(v.rule().id());
        assertFalse(v.summary().historyDone());assertNotNull(v.summary().scanError());assertTrue(v.rule().nextScanAt()<=9_007_199_254_740_991L);
        assertNull(f.repository.scan(v.rule()).cursor());f.queue();v=f.step(v);assertTrue(v.summary().historyDone());assertNull(v.summary().scanError());
    }}
    @Test void unreadableCandidateDoesNotBlockLaterPostsAndCanRecoverOnOldPostReview()throws Exception {try(var f=new Fixture()){
        var missing=post(100,"#旅行 body");var other=post(90,"#其他 body");f.queue(missing,other);f.values.remove("100");
        var v=f.enable(f.create(f.input("旅行","",ALL)));v=f.until(v,value->value.summary().historyDone(),false);
        assertEquals(1,v.summary().errors());assertNull(f.repository.getPost(v.rule(),missing.key()).post());assertNotNull(f.repository.getPost(v.rule(),other.key()));
        f.values.put("100",missing);f.comments.put(missing.key(),new ArrayDeque<>(List.of(page(missing,List.of(media(1000,"DOCUMENT")),"1000",false))));
        f.clock.addAndGet(300_001);f.queue(other);v=f.until(v,value->value.summary().media()==1,false);
        assertNotNull(f.repository.getPost(v.rule(),missing.key()).post());assertEquals(0,v.summary().errors());assertEquals(1,f.downloads.list("1",50,0).size());
    }}
    @Test void fixedPostNormalizesAndFreezesNativeIdentityWithoutReadingAnyChannelDirectory()throws Exception {try(var f=new Fixture()){
        var target=post(100,"body");f.values.put("selected",target);var request=new DownloadRuleService.Create(UUID.randomUUID().toString(),f.postInput(List.of("DOCUMENT"),false));
        var saved=f.service.create(request).get();assertFalse(saved.rule().enabled());assertEquals("POST",saved.rule().input().sourceMode());
        assertEquals("https://t.me/synthetic/100",saved.rule().input().source());assertEquals(target.key(),saved.rule().input().postKey());assertEquals("100",saved.rule().input().mainMessageId());assertEquals(target.title(),saved.rule().input().name());
        assertEquals("HEAD",f.repository.scan(saved.rule()).phase());assertEquals(1,saved.summary().scanned());assertEquals(1,saved.summary().matched());assertNotNull(f.repository.getPost(saved.rule(),target.key()));
        assertEquals(saved.rule().id(),f.service.create(request).get().rule().id());verify(f.posts,times(1)).probeDownloadSource(anyString(),eq(25_000L),any());
        verifyNoInteractions(f.channels,f.files);assertTrue(f.downloads.list("1",50,0).isEmpty());assertFalse(Files.exists(f.root));
    }}
    @Test void fixedPostDownloadsOnlyItsCommentsAndRechecksOnlyItsFrozenNativeMessage()throws Exception {try(var f=new Fixture()){
        var target=post(100,"body");var other=post(110,"same channel");f.values.put("selected",target);f.values.put("100",target);f.values.put("110",other);
        f.comments.put(target.key(),new ArrayDeque<>(List.of(page(target,List.of(media(1000,"DOCUMENT"),media(999,"PHOTO")),"999",false))));
        var v=f.enable(f.create(f.postInput(List.of("DOCUMENT"),false)));v=f.until(v,value->value.summary().media()==1,false);f.completeOutputs();v=f.until(v,value->value.summary().unresolved()==0,true);
        f.clock.addAndGet(300_001);f.comments.put(target.key(),new ArrayDeque<>(List.of(page(target,List.of(media(1001,"DOCUMENT"),media(1000,"DOCUMENT")),"1000",false))));
        v=f.until(v,value->value.summary().media()==2,true);assertEquals(2,f.downloads.list("1",50,0).size());assertEquals(1,v.summary().scanned());assertNull(f.repository.getPost(v.rule(),other.key()));
        verify(f.posts,atLeastOnce()).downloadChannelPost(eq("-1001"),eq("100"),eq(25_000L),any());
        verify(f.posts,never()).downloadChannelPost(anyString(),eq("110"),anyLong(),any());verifyNoInteractions(f.channels);
    }}
    @Test void changedFixedPostOrAlbumIdentityCannotBecomeANewSourceOnReview()throws Exception {try(var f=new Fixture()){
        var target=post(100,"body");f.values.put("selected",target);var v=f.enable(f.create(f.postInput(List.of("DOCUMENT"),false)));
        v=f.until(v,value->value.summary().unresolved()==0,false);f.clock.addAndGet(300_001);f.values.put("100",post(110,"replacement"));v=f.step(v);
        assertEquals(1,v.summary().errors());assertEquals(target,f.repository.getPost(v.rule(),target.key()).post());assertEquals("100",v.rule().input().mainMessageId());assertTrue(f.downloads.list("1",50,0).isEmpty());verifyNoInteractions(f.channels,f.files);
    }}
    @Test void explicitEmptyCommentTypesCanDownloadOnlyMainEvenWhenDiscussionIsUnavailable()throws Exception {try(var f=new Fixture()){
        var p=post(100,"body");var main=new Media("1:-1001:100:0",p.mainMessages().getFirst(),"MAIN","DOCUMENT","main.bin",3,"");
        var target=new Post(p.key(),p.accountId(),p.channelId(),p.channelTitle(),p.mainMessageId(),p.title(),p.text(),p.albumId(),p.mainMessages(),List.of(main),null,null,List.of(),false,"UNAVAILABLE",List.of("讨论线程不可读取"),p.albumEvidence(),"COMPLETE");
        f.values.put("selected",target);var v=f.enable(f.create(f.postInput(List.of(),true)));v=f.step(v);
        assertEquals(1,v.summary().media());assertEquals(0,v.summary().errors());assertEquals("DONE",f.repository.getPost(v.rule(),target.key()).state());
        assertEquals(List.of(),v.rule().input().types());verify(f.posts,never()).downloadComments(any(),nullable(String.class),anyLong(),any());verifyNoInteractions(f.channels);
    }}
    @Test void singlePostFilterOrEmptyRangeIsRejectedBeforeAnyNativeCall()throws Exception {try(var f=new Fixture()){
        var i=f.postInput(List.of("DOCUMENT"),false);
        assertThrows(IllegalArgumentException.class,()->f.create(new Input("",i.source(),"tag","",i.types(),false,i.targetDirectory(),ALL,"POST")));
        assertThrows(IllegalArgumentException.class,()->f.create(f.postInput(List.of(),false)));
        for(String source:List.of("https://t.me/synthetic","https://t.me/synthetic/100?comment=23","https://t.me/synthetic/100?thread=23","https://evil.invalid/synthetic/100","https://t.me/c/123/100#comment"))
            assertThrows(IllegalArgumentException.class,()->f.create(new Input("",source,"","",i.types(),false,i.targetDirectory(),ALL,"POST")));
        verifyNoInteractions(f.channels,f.posts,f.files);
    }}
    @Test void changedSessionDuringSinglePostResolutionCannotSaveAConfiguration()throws Exception {try(var f=new Fixture()){
        doAnswer(i->{f.generation.incrementAndGet();((Runnable)i.getArgument(2)).run();return post(100,"body");}).when(f.posts).probeDownloadSource(anyString(),anyLong(),any());
        assertThrows(ExecutionException.class,()->f.create(f.postInput(List.of("DOCUMENT"),false)));assertTrue(f.repository.list("1").isEmpty());verifyNoInteractions(f.channels,f.files);
    }}
    @Test void mainOnlySelectionDoesNotPromoteAnUnsupportedOrPartialMainRangeToComplete()throws Exception {try(var f=new Fixture()){
        var p=post(100,"body");var partial=new Post(p.key(),p.accountId(),p.channelId(),p.channelTitle(),p.mainMessageId(),p.title(),p.text(),p.albumId(),p.mainMessages(),p.mainMedia(),p.discussionChatId(),p.discussionRootMessageId(),p.discussionRoots(),true,"PARTIAL",List.of("主帖存在无法展开的媒体"),null,"PARTIAL");
        f.values.put("selected",partial);f.values.put("100",partial);var v=f.enable(f.create(f.postInput(List.of(),true)));v=f.step(v);assertEquals(1,v.summary().errors());assertEquals(0,v.summary().media());
        f.clock.addAndGet(300_001);v=f.step(v);assertEquals("ERROR",f.repository.getPost(v.rule(),partial.key()).state());assertEquals(0,v.summary().media());verifyNoInteractions(f.channels,f.files);
    }}
    @Test void albumLinkCanonicalizesToTheFrozenFirstMemberAndCannotLaterSwitchMembers()throws Exception {try(var f=new Fixture()){
        var p=post(100,"album");var target=new Post("1:-1001:album:900",p.accountId(),p.channelId(),p.channelTitle(),"100",p.title(),p.text(),"900",List.of(p.mainMessages().getFirst(),new MessageRef("-1001","110","https://t.me/synthetic/110")),List.of(),p.discussionChatId(),p.discussionRootMessageId(),p.discussionRoots(),true,"PARTIAL",List.of(),p.albumEvidence(),"COMPLETE");
        f.values.put("selected",target);var i=f.postInput(List.of("DOCUMENT"),false);var v=f.enable(f.create(new Input("","https://t.me/synthetic/110?single","","",i.types(),false,i.targetDirectory(),ALL,"POST")));
        assertEquals(target.key(),v.rule().input().postKey());assertEquals("100",v.rule().input().mainMessageId());assertEquals("https://t.me/synthetic/100",v.rule().input().source());v=f.until(v,value->value.summary().unresolved()==0,false);
        var changedMember=new Post(target.key(),target.accountId(),target.channelId(),target.channelTitle(),"110",target.title(),target.text(),target.albumId(),target.mainMessages(),target.mainMedia(),target.discussionChatId(),target.discussionRootMessageId(),target.discussionRoots(),true,target.coverage(),target.warnings(),target.albumEvidence(),target.mainCoverage());
        f.values.put("100",changedMember);f.clock.addAndGet(300_001);v=f.step(v);assertEquals(1,v.summary().errors());assertEquals(target,f.repository.getPost(v.rule(),target.key()).post());verifyNoInteractions(f.channels,f.files);
    }}
    @Test void canonicalPrivateUrlMustBelongToTheResolvedChannel()throws Exception {try(var f=new Fixture()){
        var p=post(100,"body");var wrongLink=new Post(p.key(),p.accountId(),p.channelId(),p.channelTitle(),p.mainMessageId(),p.title(),p.text(),p.albumId(),List.of(new MessageRef(p.channelId(),p.mainMessageId(),"https://t.me/c/999/100")),p.mainMedia(),p.discussionChatId(),p.discussionRootMessageId(),p.discussionRoots(),p.canReadComments(),p.coverage(),p.warnings(),p.albumEvidence(),p.mainCoverage());
        f.values.put("selected",wrongLink);assertThrows(ExecutionException.class,()->f.create(f.postInput(List.of("DOCUMENT"),false)));assertTrue(f.repository.list("1").isEmpty());verifyNoInteractions(f.channels,f.files);
    }}
}
