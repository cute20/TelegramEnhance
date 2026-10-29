package io.github.telegramenhance.transfer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.*;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.library.ChannelImportModels.ManagedChannel;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.DownloadBatchModels.*;
import io.github.telegramenhance.transfer.DownloadModels.FrozenFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadBatchServiceTest{
    @TempDir Path directory;
    static Post post(int id){return new Post("1:-1001:"+id,"1","-1001","channel",String.valueOf(id),"post "+id,"#旅行 body","0",List.of(new MessageRef("-1001",String.valueOf(id),"https://t.me/synthetic/"+id)),List.of(media(id,true)),"-2001","5"+id,List.of(new MessageRef("-2001","5"+id,"")),true,"PARTIAL",List.of(),new AlbumEvidence(true,"test","1","2"),"COMPLETE");}
    static Media media(int id,boolean main){String chat=main?"-1001":"-2001";return new Media("1:"+chat+":"+id+":0",new MessageRef(chat,String.valueOf(id),"https://t.me/synthetic/"+id),main?"MAIN":"COMMENT","DOCUMENT","file-"+id+".txt",37,"");}
    static Page page(Post p,int id,boolean eof){var media=eof?List.<Media>of():List.of(media(id,false));var refs=media.stream().map(Media::message).toList();return new Page(p.key(),media,refs,refs.size(),String.valueOf(id),eof,eof?"COMPLETE":"PARTIAL",List.of());}
    private final class Fixture implements AutoCloseable{
        final AppPaths paths=new AppPaths(directory.resolve(UUID.randomUUID().toString()));final Database db=new Database(paths);final DownloadRepository repository=new DownloadRepository(db);
        final DownloadChannelService scans=mock(DownloadChannelService.class);final ResourceService work=mock(ResourceService.class);final ResourceReader reader=mock(ResourceReader.class);final DownloadFileReader files=mock(DownloadFileReader.class);final TelegramService telegram=mock(TelegramService.class);final AtomicLong generation=new AtomicLong(1),clock=new AtomicLong(1000000);
        final List<Post> posts=List.of(post(100),post(90));final Map<String,Deque<Page>> pages=new HashMap<>();final DownloadBatchService service;final String scanId=UUID.randomUUID().toString();final Path output=directory.resolve("output").toAbsolutePath();
        Fixture(){when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenAnswer(i->generation.get());
            when(work.readWork(any())).thenAnswer(i->{try{return CompletableFuture.completedFuture(((Supplier<?>)i.getArgument(0)).get());}catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}});
            var rows=posts.stream().map(p->new DownloadChannelModels.Row(p,"MATCH",p.mainMessages().getFirst().link(),"NOT_READ")).toList();
            when(scans.selection(anyString(),anyLong(),anyList())).thenReturn(new DownloadChannelModels.View(scanId,"1",new ManagedChannel("-1001","channel"),List.of("旅行"),List.of(),3,2,2,0,rows,true,false,false,false,null,List.of(),1,600001));
            when(reader.downloadChannelPost(anyString(),anyString(),anyLong(),any())).thenAnswer(i->{((Runnable)i.getArgument(3)).run();return posts.stream().filter(p->p.mainMessageId().equals(i.getArgument(1))).findFirst().orElseThrow();});
            for(var p:posts)pages.put(p.key(),new ArrayDeque<>(List.of(page(p,10000,false),page(p,9999,true))));
            when(reader.downloadComments(any(),nullable(String.class),anyLong(),any())).thenAnswer(i->{((Runnable)i.getArgument(3)).run();return pages.get(((Post)i.getArgument(0)).key()).remove();});
            when(files.freeze(any(),anyList(),anyLong())).thenAnswer(i->((List<Media>)i.getArgument(1)).stream().map(m->new FrozenFile(m,"uid-"+m.key(),m.size(),m.fileName())).toList());
            service=new DownloadBatchService(scans,work,reader,files,repository,new DownloadPaths(paths),telegram,clock::get);
        }
        Input input(boolean includeMain){return new Input(scanId,3,posts.stream().map(Post::key).toList(),List.of("DOCUMENT"),includeMain,output.toString());}
        View start(){return service.start(input(false));}
        View step(View value)throws Exception{return service.next(value.id(),new Position(value.revision())).get(5,TimeUnit.SECONDS);}
        View finish(View value)throws Exception{int steps=0;while(value.canContinue()&&steps++<20)value=step(value);assertTrue(value.exhausted());return value;}
        public void close(){db.close();}
    }
    @Test void preparationReadsMetadataOnlyAndAtomicCreationIsIdempotentAfterPreviewExpiry()throws Exception{try(var f=new Fixture()){
        // Distinct posts normally have different threads; use distinct media identities to verify two children.
        var second=f.posts.get(1);f.pages.put(second.key(),new ArrayDeque<>(List.of(page(second,9000,false),page(second,8999,true))));
        var v=f.finish(f.start());assertEquals(2,v.total());assertEquals(74,v.totalBytes());assertTrue(f.repository.list("1",50,0).isEmpty());assertFalse(Files.exists(f.output));
        var request=new Create(UUID.randomUUID().toString(),v.id(),v.revision());var receipt=f.service.create(request);assertEquals(2,receipt.taskIds().size());assertEquals(2,f.repository.list("1",50,0).size());assertFalse(Files.exists(f.output));
        f.clock.addAndGet(600001);assertEquals(receipt,f.service.create(request));assertEquals(receipt,f.service.lookup(request.operationId()).batch());assertNull(f.repository.batchByOperation("2",request.operationId(),null));
        assertThrows(IllegalArgumentException.class,()->f.service.create(new Create(request.operationId(),request.previewId(),request.revision()+1)));
    }}
    @Test void emptyNonEofPagesAndMainAttachmentsDoNotFinishTheCommentRange()throws Exception{try(var f=new Fixture()){
        var p=f.posts.getFirst();f.pages.put(p.key(),new ArrayDeque<>(List.of(new Page(p.key(),List.of(),List.of(),0,"9000",false,"PARTIAL",List.of()),page(p,8999,true))));
        var v=f.service.start(f.input(true));v=f.step(v);assertEquals(1,v.total());assertFalse(v.exhausted());v=f.step(v);assertEquals(0,v.posts().getFirst().commentMessages());assertFalse(v.exhausted());v=f.finish(v);assertEquals(3,v.total());
    }}
    @Test void commentTypeFilteringDoesNotRemoveIncludedMainMedia()throws Exception{try(var f=new Fixture()){
        var input=f.input(true);var v=f.finish(f.service.start(new Input(input.scanId(),input.revision(),input.postKeys(),List.of("VIDEO"),true,input.targetDirectory())));assertEquals(2,v.total());assertEquals(0,v.posts().stream().mapToInt(PostSummary::commentMedia).sum());assertEquals(2,v.posts().stream().mapToInt(PostSummary::mainMedia).sum());
    }}
    @Test void shortPagesAndRepeatedMediaKeysAreNotMistakenForNewFilesOrEof()throws Exception{try(var f=new Fixture()){
        var v=f.start();v=f.step(v);v=f.step(v);assertEquals(1,v.total());assertFalse(v.exhausted());v=f.finish(v);assertEquals(1,v.total());assertTrue(v.exhausted());
    }}
    @Test void failedFreezeRetainsTheObservedPageAndRequiresExplicitRetry()throws Exception{try(var f=new Fixture()){
        var v=f.step(f.start());doThrow(new IllegalArgumentException("temporary")).when(f.files).freeze(any(),anyList(),anyLong());long revision=v.revision();String id=v.id();assertThrows(ExecutionException.class,()->f.service.next(id,new Position(revision)).get());assertEquals(revision,f.service.get(id).revision());assertFalse(f.service.get(id).busy());
        doAnswer(i->((List<Media>)i.getArgument(1)).stream().map(m->new FrozenFile(m,"uid-"+m.key(),m.size(),m.fileName())).toList()).when(f.files).freeze(any(),anyList(),anyLong());
        v=f.service.next(id,new Position(revision)).get();assertEquals(1,v.total());verify(f.reader,times(1)).downloadComments(any(),nullable(String.class),anyLong(),any());
    }}
    @Test void malformedCommentIdentityOrNonAdvancingCursorCannotBecomeReady()throws Exception{try(var f=new Fixture()){
        var p=f.posts.getFirst();f.pages.put(p.key(),new ArrayDeque<>(List.of(new Page("foreign",List.of(),List.of(),0,"1",true,"COMPLETE",List.of()))));var v=f.step(f.start());String id=v.id();long revision=v.revision();assertThrows(ExecutionException.class,()->f.service.next(id,new Position(revision)).get());assertFalse(f.service.get(id).exhausted());assertTrue(f.repository.list("1",50,0).isEmpty());
    }}
    @Test void nativePermissionFailureOrSessionChangeLeavesNoJobs()throws Exception{try(var f=new Fixture()){
        var v=f.step(f.start());doAnswer(i->{f.generation.incrementAndGet();return List.of(new FrozenFile(media(10000,false),"x",37,"x"));}).when(f.files).freeze(any(),anyList(),anyLong());String id=v.id();long revision=v.revision();assertThrows(ExecutionException.class,()->f.service.next(id,new Position(revision)).get());assertThrows(IllegalArgumentException.class,()->f.service.get(id));assertTrue(f.repository.list("1",50,0).isEmpty());
    }}
    @Test void invalidTypesAndProtectedRootAreRejectedBeforeNativeCalls(){try(var f=new Fixture()){
        var input=f.input(false);assertThrows(IllegalArgumentException.class,()->f.service.start(new Input(input.scanId(),3,input.postKeys(),List.of(),false,input.targetDirectory())));assertThrows(IllegalArgumentException.class,()->f.service.start(new Input(input.scanId(),3,input.postKeys(),List.of("VIDEO"),false,f.paths.root().toString())));verifyNoInteractions(f.reader,f.files);
    }}
    @Test void notReadyEmptyAndStalePlansCannotCreateTasks()throws Exception{try(var f=new Fixture()){
        var v=f.start();var request=new Create(UUID.randomUUID().toString(),v.id(),v.revision());assertThrows(IllegalArgumentException.class,()->f.service.create(request));
        var input=f.input(false);v=f.finish(f.service.start(new Input(input.scanId(),3,input.postKeys(),List.of("VIDEO"),false,input.targetDirectory())));assertEquals(0,v.total());var empty=new Create(UUID.randomUUID().toString(),v.id(),v.revision());assertThrows(IllegalArgumentException.class,()->f.service.create(empty));assertTrue(f.repository.list("1",50,0).isEmpty());
    }}
    @Test void inFlightStepsAreExclusiveAndGetCanResolveAnUnknownResponse()throws Exception{try(var f=new Fixture()){
        var v=f.start();var pending=new CompletableFuture<View>();doReturn(pending).when(f.work).readWork(any());var result=f.service.next(v.id(),new Position(1));assertTrue(f.service.get(v.id()).busy());assertThrows(IllegalArgumentException.class,()->f.service.next(v.id(),new Position(1)));pending.completeExceptionally(new IllegalArgumentException("stopped"));assertThrows(ExecutionException.class,result::get);assertFalse(f.service.get(v.id()).busy());assertEquals(1,f.service.get(v.id()).revision());
    }}
    @Test void expiredPreviewsAndChangedPostTextAreNotReused()throws Exception{try(var f=new Fixture()){
        var v=f.start();f.clock.addAndGet(600001);assertThrows(IllegalArgumentException.class,()->f.service.get(v.id()));
        var changed=post(100);changed=new Post(changed.key(),changed.accountId(),changed.channelId(),changed.channelTitle(),changed.mainMessageId(),changed.title(),"no matching tags",changed.albumId(),changed.mainMessages(),changed.mainMedia(),changed.discussionChatId(),changed.discussionRootMessageId(),changed.discussionRoots(),true,"PARTIAL",List.of(),changed.albumEvidence(),"COMPLETE");doReturn(changed).when(f.reader).downloadChannelPost(anyString(),anyString(),anyLong(),any());var current=f.start();assertThrows(ExecutionException.class,()->f.service.next(current.id(),new Position(1)).get());verifyNoInteractions(f.files);
    }}
    static Page many(Post post,int newest,int count){var media=java.util.stream.IntStream.range(0,count).mapToObj(i->media(newest-i,false)).toList();return new Page(post.key(),media,media.stream().map(Media::message).toList(),media.size(),String.valueOf(newest-count+1),false,"PARTIAL",List.of());}
    @Test void moreThan100FilesSplitIntoBoundedTasksAndKeepOneImmutableBatchReceipt()throws Exception{try(var f=new Fixture()){
        var p=f.posts.getFirst();f.pages.put(p.key(),new ArrayDeque<>(List.of(many(p,10000,50),many(p,9950,50),many(p,9900,1),page(p,9899,true))));var second=f.posts.get(1);f.pages.put(second.key(),new ArrayDeque<>(List.of(page(second,8999,true))));
        var v=f.finish(f.start());assertEquals(101,v.total());var receipt=f.service.create(new Create(UUID.randomUUID().toString(),v.id(),v.revision()));assertEquals(2,receipt.taskIds().size());assertEquals(List.of(100,1),receipt.taskIds().stream().map(id->f.repository.task("1",id).items().size()).toList());assertTrue(receipt.taskIds().stream().allMatch(id->f.repository.task("1",id).duplicatePolicy().equals(DownloadModels.KEEP_DUPLICATES)));assertFalse(Files.exists(f.output));
    }}
    @Test void reaching1000MediaDoesNotSilentlyTruncateOrPermitAnIncompleteBatch()throws Exception{try(var f=new Fixture()){
        var p=f.posts.getFirst();var pages=new ArrayDeque<Page>();for(int n=0;n<21;n++)pages.add(many(p,10000-50*n,50));f.pages.put(p.key(),pages);var v=f.step(f.start());for(int n=0;n<20;n++)v=f.step(v);assertEquals(1000,v.total());String id=v.id();long revision=v.revision();assertThrows(ExecutionException.class,()->f.service.next(id,new Position(revision)).get());assertEquals(1000,f.service.get(id).total());assertFalse(f.service.get(id).exhausted());assertThrows(IllegalArgumentException.class,()->f.service.create(new Create(UUID.randomUUID().toString(),id,revision)));assertTrue(f.repository.list("1",50,0).isEmpty());
    }}
    @Test void previewCapacityAndQueueRejectionNeverLeaveTheBusyFlagStuck(){try(var f=new Fixture()){
        var oldest=f.start();for(int i=0;i<4;i++)f.start();assertThrows(IllegalArgumentException.class,()->f.service.get(oldest.id()));var current=f.start();doThrow(new IllegalArgumentException("queue full")).when(f.work).readWork(any());assertThrows(IllegalArgumentException.class,()->f.service.next(current.id(),new Position(1)));assertFalse(f.service.get(current.id()).busy());verifyNoInteractions(f.reader,f.files);
    }}
}
