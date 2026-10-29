package io.github.telegramenhance.transfer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import io.github.telegramenhance.library.*;
import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import io.github.telegramenhance.library.ChannelImportModels.*;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.DownloadChannelModels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DownloadChannelServiceTest {
    @Test void batchSelectionRejectsStalePositionsForeignKeysAndPartialAlbums()throws Exception{try(var f=new Fixture()){
        f.pages.add(page(List.of(candidate(100)),null,true));var v=f.start("","");v=await(f.service.next(v.id(),new Position(1)));assertEquals(v,f.service.selection(v.id(),v.revision(),List.of(v.rows().getFirst().post().key())));String id=v.id();long revision=v.revision();assertThrows(IllegalArgumentException.class,()->f.service.selection(id,revision-1,List.of("1:-1001:100")));assertThrows(IllegalArgumentException.class,()->f.service.selection(id,revision,List.of("foreign")));assertThrows(IllegalArgumentException.class,()->f.service.selection(id,revision,List.of("1:-1001:100","1:-1001:100")));
    }try(var f=new Fixture()){
        f.pages.add(page(List.of(new Candidate("100","8","partial")),null,true));f.values.put("100",album(100,"#别的",false));var v=f.start("旅行","");v=await(f.service.next(v.id(),new Position(1)));String id=v.id();long revision=v.revision();assertThrows(IllegalArgumentException.class,()->f.service.selection(id,revision,List.of("1:-1001:album:8")));
    }}
    static final ManagedChannel CHANNEL=new ManagedChannel("-1001","Synthetic source");
    static String cursor(long id){return Base64.getUrlEncoder().withoutPadding().encodeToString(("v1|1|-1001|"+id+"|0").getBytes(StandardCharsets.UTF_8));}
    static Candidate candidate(long id){return new Candidate(Long.toString(id),null,"preview title without filter text");}
    static Post post(long id,String text){return new Post("1:-1001:"+id,"1","-1001","source",Long.toString(id),"title",text,"0",List.of(new MessageRef("-1001",Long.toString(id),"https://t.me/synthetic/"+id)),List.of(),"-2001","50",List.of(),true,"PARTIAL",List.of(),new AlbumEvidence(true,"SINGLE",null,null),"COMPLETE");}
    static Post album(long id,String text,boolean complete){return new Post("1:-1001:album:8","1","-1001","source","99","title",text,"8",List.of(new MessageRef("-1001","100","https://t.me/synthetic/100"),new MessageRef("-1001","99","https://t.me/synthetic/99")),List.of(),null,null,List.of(),false,"UNAVAILABLE",List.of("album warning"),new AlbumEvidence(complete,"test",null,null),complete?"COMPLETE":"PARTIAL");}
    static HistoryPage page(List<Candidate> items,String next,boolean eof){return new HistoryPage(CHANNEL,items,"CHANNEL_PAGE",10,next,eof,!eof,List.of());}
    static <T>T await(CompletableFuture<T> f)throws Exception{return f.get(5,TimeUnit.SECONDS);}
    @Test void independentDiscoveryAndStartNeverWriteIndexesOrReadCommentsOrTransferFiles()throws Exception{try(var f=new Fixture()){
        when(f.channels.downloadChannels()).thenReturn(new ManagedChannels(List.of(CHANNEL),"READABLE_CHANNELS",true,List.of("partial")));
        assertEquals("READABLE_CHANNELS",await(f.service.channels()).scope());var v=f.start("#旅行","周末");assertEquals(0,v.scanned());assertTrue(v.canContinue());assertFalse(v.exhausted());verifyNoInteractions(f.repository,f.posts);verify(f.channels,never()).channels();
    }}
    @Test void pendingLogicalBatchIsConsumedBeforeHistoryEofAndOnlyMainTextMatchesAllTerms()throws Exception{try(var f=new Fixture()){
        f.pages.add(page(List.of(candidate(100),candidate(99)),null,true));f.values.put("100",post(100,"#旅行 #摄影 周末 散步"));f.values.put("99",post(99,"#旅行 #摄影 工作日 散步"));
        var v=f.start("旅行 摄影","周末 散步");v=await(f.service.next(v.id(),new Position(v.revision())));assertEquals(1,v.scanned());assertEquals(1,v.matched());assertFalse(v.exhausted());assertEquals("NOT_READ",v.rows().getFirst().comments());
        v=await(f.service.next(v.id(),new Position(v.revision())));assertEquals(2,v.scanned());assertEquals(1,v.matched());assertTrue(v.exhausted());assertFalse(v.canContinue());verify(f.channels,times(1)).downloadHistoryPage("-1001",null);verify(f.posts,never()).comments(any(),any());verifyNoInteractions(f.repository);
    }}
    @Test void filtersUseWholePostBeyondTheShortHistoryTitleAndDoNotReadCommentBodies()throws Exception{try(var f=new Fixture()){
        f.pages.add(page(List.of(candidate(100)),null,true));f.values.put("100",post(100,"prefix "+"x".repeat(200)+" #旅行 周末"));var v=f.start("旅行","周末");v=await(f.service.next(v.id(),new Position(1)));assertEquals(1,v.matched());verify(f.posts,never()).comments(any(),any());
    }}
    @Test void incompleteAlbumNegativeMatchIsUnverifiedWhilePositiveAndCompleteNegativeAreHonest()throws Exception{for(boolean complete:List.of(true,false))try(var f=new Fixture()){
        f.pages.add(page(List.of(new Candidate("100","8","title")),null,true));f.values.put("100",album(100,"#别的",complete));var v=f.start("旅行","");v=await(f.service.next(v.id(),new Position(1)));assertEquals(0,v.matched());assertEquals(complete?0:1,v.unverified());if(!complete)assertEquals("UNVERIFIED",v.rows().getFirst().match());assertTrue(v.exhausted());
    }}
    @Test void duplicateAlbumKeysNeverIncreaseTheLogicalCountTwice()throws Exception{try(var f=new Fixture()){
        f.pages.add(page(List.of(new Candidate("100","8","title"),new Candidate("99","8","title")),null,true));f.values.put("100",album(100,"#旅行",true));f.values.put("99",album(99,"#旅行",true));var v=f.start("旅行","");v=await(f.service.next(v.id(),new Position(1)));v=await(f.service.next(v.id(),new Position(2)));assertEquals(1,v.scanned());assertEquals(1,v.rows().size());
    }}
    @Test void emptyServiceBatchAdvancesWithoutPretendingTheChannelIsEmpty()throws Exception{try(var f=new Fixture()){
        f.pages.add(page(List.of(),cursor(9007199254740999L),false));f.pages.add(page(List.of(candidate(100)),null,true));var v=f.start("","");v=await(f.service.next(v.id(),new Position(1)));assertEquals(0,v.scanned());assertTrue(v.canContinue());assertFalse(v.exhausted());v=await(f.service.next(v.id(),new Position(2)));assertTrue(v.exhausted());verify(f.channels).downloadHistoryPage("-1001",cursor(9007199254740999L));
    }}
    @Test void nonAdvancingCursorAndForeignPostDoNotCommitPositions()throws Exception{try(var f=new Fixture()){
        f.pages.add(page(List.of(),cursor(100),false));f.pages.add(page(List.of(candidate(99)),cursor(100),false));var v=f.start("","");v=await(f.service.next(v.id(),new Position(1)));String id=v.id();assertThrows(ExecutionException.class,()->await(f.service.next(id,new Position(2))));assertEquals(2,f.service.get(id).revision());assertNotNull(f.service.get(id).error());
    }try(var f=new Fixture()){
        f.pages.add(page(List.of(candidate(100)),null,true));var p=post(100,"wrong");f.values.put("100",new Post(p.key(),"2",p.channelId(),p.channelTitle(),p.mainMessageId(),p.title(),p.text(),p.albumId(),p.mainMessages(),p.mainMedia(),null,null,List.of(),false,"PARTIAL",List.of()));var v=f.start("","");assertThrows(ExecutionException.class,()->await(f.service.next(v.id(),new Position(1))));assertEquals(0,f.service.get(v.id()).scanned());assertEquals(1,f.service.get(v.id()).revision());
    }}
    @Test void failedPostReadKeepsItsCandidateAndExplicitRetryUsesTheSameExpectedRevision()throws Exception{try(var f=new Fixture()){
        f.pages.add(page(List.of(candidate(100)),null,true));var v=f.start("","");String id=v.id();f.failPost=true;assertThrows(ExecutionException.class,()->await(f.service.next(id,new Position(1))));assertFalse(f.service.get(id).busy());assertEquals(1,f.service.get(id).revision());f.failPost=false;v=await(f.service.next(id,new Position(1)));assertTrue(v.exhausted());assertEquals(1,v.scanned());
    }}
    @Test void aPendingStepIsExclusiveAndUnknownResponseCanBeResolvedByGet()throws Exception{try(var f=new Fixture()){
        f.pages.add(page(List.of(candidate(100)),null,true));var started=new CountDownLatch(1);var release=new CountDownLatch(1);f.duringPost=()->{started.countDown();try{assertTrue(release.await(4,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}};var v=f.start("","");var next=f.service.next(v.id(),new Position(1));assertTrue(started.await(2,TimeUnit.SECONDS));assertTrue(f.service.get(v.id()).busy());assertThrows(IllegalArgumentException.class,()->f.service.next(v.id(),new Position(1)));release.countDown();await(next);assertEquals(2,f.service.get(v.id()).revision());assertThrows(IllegalArgumentException.class,()->f.service.next(v.id(),new Position(1)));
    }}
    @Test void sessionOrAccountChangeDuringNativeWorkCannotCommitLateResults()throws Exception{for(boolean changeAccount:List.of(false,true))try(var f=new Fixture()){
        f.pages.add(page(List.of(candidate(100)),null,true));var v=f.start("","");f.duringPost=()->{if(changeAccount)f.account.set("2");else f.generation.incrementAndGet();};assertThrows(ExecutionException.class,()->await(f.service.next(v.id(),new Position(1))));assertThrows(IllegalArgumentException.class,()->f.service.get(v.id()));verifyNoInteractions(f.repository);
    }}
    @Test void accessIsRecheckedForEachPostAndRevocationDoesNotReadMoreMetadata()throws Exception{try(var f=new Fixture()){
        f.pages.add(page(List.of(candidate(100)),null,true));var v=f.start("","");when(f.channels.downloadChannel("-1001")).thenThrow(new IllegalArgumentException("revoked"));assertThrows(ExecutionException.class,()->await(f.service.next(v.id(),new Position(1))));verifyNoInteractions(f.posts);assertEquals(0,f.service.get(v.id()).scanned());
    }}
    @Test void invalidFiltersNeverReachNativeAndIdlePreviewsExpireAndEvictBoundedly()throws Exception{try(var f=new Fixture()){
        assertThrows(IllegalArgumentException.class,()->f.service.start(new Input("-1001","tag!","")));verifyNoInteractions(f.channels);var first=f.start("","");for(int i=0;i<8;i++)f.start("","");assertThrows(IllegalArgumentException.class,()->f.service.get(first.id()));var last=f.start("","");f.clock.addAndGet(600000);assertThrows(IllegalArgumentException.class,()->f.service.get(last.id()));
    }}
    @Test void previewResultLimitPausesRatherThanInventingHistoryEof()throws Exception{try(var f=new Fixture()){
        AtomicInteger batch=new AtomicInteger();when(f.channels.downloadHistoryPage(anyString(),nullable(String.class))).thenAnswer(i->{int n=batch.getAndIncrement();var list=new ArrayList<Candidate>();for(int j=0;j<10;j++)list.add(candidate(10000-n*10-j));return page(list,cursor(9991-n*10),false);});var v=f.start("","");for(int i=0;i<200;i++)v=await(f.service.next(v.id(),new Position(v.revision())));assertEquals(200,v.rows().size());assertTrue(v.limitReached());assertFalse(v.exhausted());assertFalse(v.canContinue());
    }}
    @Test void queueRejectionClearsBusyAndDoesNotAdvanceTheCheckpoint()throws Exception{try(var f=new Fixture()){
        var work=mock(ResourceService.class);when(work.readWork(any())).thenAnswer(i->CompletableFuture.supplyAsync((Supplier<?>)i.getArgument(0),Runnable::run));var service=new DownloadChannelService(work,f.channels,f.posts,f.telegram,f.clock::get);var v=await(service.start(new Input("-1001","","")));doThrow(new IllegalArgumentException("queue full")).when(work).readWork(any());assertThrows(IllegalArgumentException.class,()->service.next(v.id(),new Position(1)));assertFalse(service.get(v.id()).busy());assertEquals(1,service.get(v.id()).revision());
    }}
    @Test void longEmptyHistoryStopsAtTheStepBudgetWithoutFalseEof()throws Exception{try(var f=new Fixture()){
        AtomicInteger batch=new AtomicInteger();when(f.channels.downloadHistoryPage(anyString(),nullable(String.class))).thenAnswer(i->page(List.of(),cursor(10000-batch.getAndIncrement()),false));var v=f.start("","");for(int i=0;i<2000;i++)v=await(f.service.next(v.id(),new Position(v.revision())));assertEquals(0,v.scanned());assertTrue(v.limitReached());assertFalse(v.exhausted());assertFalse(v.canContinue());verifyNoInteractions(f.posts);
    }}
    static final class Fixture implements AutoCloseable{
        final TelegramService telegram=mock(TelegramService.class);final ResourceRepository repository=mock(ResourceRepository.class);final ResourceReader posts=mock(ResourceReader.class);final ChannelReader channels=mock(ChannelReader.class);final AtomicReference<String> account=new AtomicReference<>("1");final AtomicLong generation=new AtomicLong(1),clock=new AtomicLong(1000000);final ResourceService work;final DownloadChannelService service;final Queue<HistoryPage> pages=new ArrayDeque<>();final Map<String,Post> values=new HashMap<>();boolean failPost;Runnable duringPost=()->{};
        Fixture(){when(telegram.accountId()).thenAnswer(i->account.get());when(telegram.sessionGeneration()).thenAnswer(i->generation.get());when(channels.downloadChannel(anyString())).thenReturn(CHANNEL);when(channels.downloadHistoryPage(anyString(),nullable(String.class))).thenAnswer(i->pages.isEmpty()?page(List.of(),null,true):pages.remove());when(posts.downloadChannelPost(anyString(),anyString(),eq(25000L),any(Runnable.class))).thenAnswer(i->{((Runnable)i.getArgument(3)).run();duringPost.run();if(failPost)throw new IllegalArgumentException("read failed");return values.getOrDefault(i.getArgument(1),post(Long.parseLong(i.getArgument(1)),"text"));});work=new ResourceService(posts,repository,telegram);service=new DownloadChannelService(work,channels,posts,telegram,clock::get);}
        View start(String tags,String keywords)throws Exception{return await(service.start(new Input("-1001",tags,keywords)));}
        public void close(){work.close();}
    }
}
