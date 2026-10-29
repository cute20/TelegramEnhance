package io.github.telegramenhance.telegram;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import io.github.telegramenhance.transfer.UploadRetryModels.*;
import it.tdlight.jni.TdApi;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite/Windows files and a deterministic native gateway, never a real Telegram send. */
class UploadRetryTest {
    @TempDir Path root;
    @Test void partialSuccessSurvivesRestartAndOnlyFailedMembersAreRetriedThenWholeGroupIsArchived()throws Exception{
        try(var f=new Fixture(6,"DOCUMENT")){
            for(int n=0;n<4;n++)f.success(n);f.fail(4);f.fail(5);f.review();f.restart();var before=f.store.fresh();var originals=before.groups().getFirst().items().subList(0,4).stream().map(Item::formalId).toList();
            var checked=f.service.check(before.id()).get();assertEquals(4,checked.confirmed());assertEquals(2,checked.retryable());assertEquals(0,f.resends.size());
            var op=new Retry(UUID.randomUUID().toString(),checked.id());f.service.retry(before.id(),op);f.service.retry(before.id(),op);assertEquals(2,f.attemptCount());
            f.execute();assertEquals("COMPLETED",f.store.fresh().state());assertEquals(6,f.store.repository.view(f.store.fresh()).archived());assertEquals(originals,f.store.fresh().groups().getFirst().items().subList(0,4).stream().map(Item::formalId).toList());
            assertEquals(Set.of(204L,205L),new HashSet<>(f.resends));assertEquals(0,f.newSends);assertTrue(f.store.repository.view(f.store.fresh()).groups().getFirst().hasRetries());
            f.service.retry(before.id(),op);assertEquals(2,f.resends.size());assertEquals(2,f.attemptCount());assertFalse(f.service.execute(f.store.fresh(),f.generation,()->{}));
            try(var files=Files.list(f.store.source)){assertEquals(0,files.count());}
        }
    }
    @Test void pendingAndUnknownMembersAreNotRetriedAndPreventPrematureGroupArchive()throws Exception{
        try(var f=new Fixture(6,"DOCUMENT")){f.success(0);f.success(1);f.fail(2);f.fail(3);f.nativeMessages.remove(105L);f.review();
            var checked=f.service.check(f.store.task.id()).get();assertEquals(2,checked.confirmed());assertEquals(2,checked.retryable());assertEquals(1,checked.pending());assertEquals(1,checked.unresolved());
            f.service.retry(f.store.task.id(),new Retry(UUID.randomUUID().toString(),checked.id()));f.execute();assertEquals("NEEDS_REVIEW",f.store.fresh().state());assertEquals(4,f.store.repository.view(f.store.fresh()).confirmed());assertEquals(0,f.store.repository.view(f.store.fresh()).archived());assertEquals(Set.of(202L,203L),new HashSet<>(f.resends));assertFalse(Files.exists(f.store.archive));
        }
    }
    @Test void explicitNativeFailureWithoutRetryPermissionOrWithChangedReplyRequirementsNeverSends()throws Exception{
        for(Consumer<TdApi.MessageSendingStateFailed> change:List.<Consumer<TdApi.MessageSendingStateFailed>>of(s->s.canRetry=false,s->s.needDropReply=true,s->s.needAnotherReplyQuote=true,s->s.needAnotherSender=true,s->s.requiredPaidMessageStarCount=1,s->s.retryAfter=12)){
            try(var f=new Fixture(1,"DOCUMENT")){f.fail(0);change.accept((TdApi.MessageSendingStateFailed)f.nativeMessages.get(200L).sendingState);f.review();var checked=f.service.check(f.store.task.id()).get();assertEquals(0,checked.retryable());assertEquals(1,checked.unresolved());assertThrows(IllegalArgumentException.class,()->f.service.retry(f.store.task.id(),new Retry(UUID.randomUUID().toString(),checked.id())));assertTrue(f.resends.isEmpty());}
        }
    }
    @Test void missingMessageOrWrongThreadCaptionFileIdentityCannotBeRetried()throws Exception{
        for(Consumer<TdApi.Message> change:List.<Consumer<TdApi.Message>>of(m->m.topicId=new TdApi.MessageTopicThread(501),m->((TdApi.MessageDocument)m.content).caption.text="wrong",m->((TdApi.MessageDocument)m.content).document.document.size++,m->((TdApi.MessageDocument)m.content).document.document.local.path=root.resolve("unowned").toString(),m->((TdApi.MessageDocument)m.content).document.document.remote.uniqueId="changed")){
            try(var f=new Fixture(1,"DOCUMENT")){f.fail(0);change.accept(f.nativeMessages.get(200L));f.review();assertEquals(0,f.service.check(f.store.task.id()).get().retryable());assertTrue(f.resends.isEmpty());}
        }
    }
    @Test void sourceOrStagingChangesAfterCheckStopBeforeResend()throws Exception{
        for(boolean source:List.of(true,false))try(var f=new Fixture(1,"DOCUMENT")){f.fail(0);f.review();var checked=f.service.check(f.store.task.id()).get();Files.writeString(source?f.store.source.resolve("file1.bin"):Path.of(f.item(0).staged().path()),"changed");f.service.retry(f.store.task.id(),new Retry(UUID.randomUUID().toString(),checked.id()));f.execute();assertEquals("NEEDS_REVIEW",f.store.fresh().state());assertTrue(f.resends.isEmpty());assertFalse(Files.exists(f.store.archive));}
    }
    @Test void crashAfterDispatchWithoutInitialMappingNeverAutomaticallyReplaysOrGuessesByFilename()throws Exception{
        try(var f=new Fixture(1,"DOCUMENT")){f.fail(0);f.review();var checked=f.service.check(f.store.task.id()).get();f.service.retry(f.store.task.id(),new Retry(UUID.randomUUID().toString(),checked.id()));f.dropReply=true;f.execute();assertEquals(1,f.resends.size());assertEquals("NEEDS_REVIEW",f.store.fresh().state());f.restart();var next=f.service.check(f.store.task.id()).get();assertEquals(0,next.retryable());assertEquals(1,next.unresolved());assertEquals(1,f.resends.size());assertFalse(f.service.execute(f.store.fresh(),f.generation,()->{}));}
    }
    @Test void queuedRetryDoesNotAutoResumeAfterRestartAndOldOperationRemainsIdempotent()throws Exception{
        try(var f=new Fixture(1,"DOCUMENT")){f.fail(0);f.review();var checked=f.service.check(f.store.task.id()).get();var op=new Retry(UUID.randomUUID().toString(),checked.id());f.service.retry(f.store.task.id(),op);f.restart();assertEquals("NEEDS_REVIEW",f.store.fresh().state());assertFalse(f.service.execute(f.store.fresh(),f.generation,()->{}));f.service.retry(f.store.task.id(),op);assertTrue(f.resends.isEmpty());assertEquals(1,f.attemptCount());var next=f.service.check(f.store.task.id()).get();f.service.retry(f.store.task.id(),new Retry(UUID.randomUUID().toString(),next.id()));f.execute();assertEquals(1,f.resends.size());assertEquals("COMPLETED",f.store.fresh().state());}
    }
    @Test void aRetryFailureCanBeExplicitlyCheckedAgainWithoutRewritingOldEvidence()throws Exception{
        try(var f=new Fixture(1,"DOCUMENT")){f.fail(0);f.review();var checked=f.service.check(f.store.task.id()).get();f.service.retry(f.store.task.id(),new Retry(UUID.randomUUID().toString(),checked.id()));f.failAgain=true;f.execute();assertEquals("NEEDS_REVIEW",f.store.fresh().state());assertEquals(0,f.store.repository.view(f.store.fresh()).confirmed());f.failAgain=false;var next=f.service.check(f.store.task.id()).get();assertEquals(1,next.retryable());f.service.retry(f.store.task.id(),new Retry(UUID.randomUUID().toString(),next.id()));f.execute();assertEquals(2,f.resends.size());assertEquals("COMPLETED",f.store.fresh().state());assertEquals(3,f.eventCount());}
    }
    @Test void sessionChangeAndOldCheckCannotQueueAResend()throws Exception{
        try(var f=new Fixture(1,"DOCUMENT")){f.fail(0);f.review();var checked=f.service.check(f.store.task.id()).get();when(f.telegram.sessionGeneration()).thenReturn(2L);assertThrows(IllegalArgumentException.class,()->f.service.retry(f.store.task.id(),new Retry(UUID.randomUUID().toString(),checked.id())));assertEquals(0,f.attemptCount());assertTrue(f.resends.isEmpty());}
    }
    @Test void pausingQueuedRetryConsumesNoSendAndCanBeCheckedAgain()throws Exception{
        try(var f=new Fixture(1,"DOCUMENT")){f.fail(0);f.review();var checked=f.service.check(f.store.task.id()).get();f.service.retry(f.store.task.id(),new Retry(UUID.randomUUID().toString(),checked.id()));f.store.repository.pause("1",f.store.task.id());assertEquals("NEEDS_REVIEW",f.store.fresh().state());assertNull(f.store.repository.next("1"));assertTrue(f.resends.isEmpty());assertEquals(1,f.service.check(f.store.task.id()).get().retryable());}
    }
    @Test void sessionChangeAfterQueueingStopsBeforeDispatch()throws Exception{
        try(var f=new Fixture(1,"DOCUMENT")){f.fail(0);f.review();var checked=f.service.check(f.store.task.id()).get();f.service.retry(f.store.task.id(),new Retry(UUID.randomUUID().toString(),checked.id()));f.generation++;f.execute();assertEquals("NEEDS_REVIEW",f.store.fresh().state());assertTrue(f.resends.isEmpty());}
    }
    @Test void allSupportedTypesUseOnlyNativeFailedMessageRetryIncludingConvertedPhotos()throws Exception{
        for(String type:List.of("PHOTO","VIDEO","AUDIO","DOCUMENT","ANIMATION"))try(var f=new Fixture(1,type)){f.fail(0);f.review();var checked=f.service.check(f.store.task.id()).get();assertEquals(1,checked.retryable(),type);f.service.retry(f.store.task.id(),new Retry(UUID.randomUUID().toString(),checked.id()));f.execute();assertEquals("COMPLETED",f.store.fresh().state(),type);assertEquals(1,f.resends.size());assertEquals(0,f.newSends);}
    }
    @Test void sourceMoveAfterSuccessfulRetryOnlyRetriesArchiveAndNeverSendsNextGroup()throws Exception{
        try(var f=new Fixture(11,"DOCUMENT")){for(int n=0;n<9;n++)f.success(n);f.fail(9);f.review();var checked=f.service.check(f.store.task.id()).get();f.service.retry(f.store.task.id(),new Retry(UUID.randomUUID().toString(),checked.id()));f.archiveChange=true;f.execute();assertEquals("SENT_PENDING_ARCHIVE",f.store.fresh().state());assertEquals(1,f.resends.size());assertEquals("READY",f.store.fresh().groups().get(1).state());assertEquals(10,f.store.repository.view(f.store.fresh()).confirmed());}
    }
    private static void until(BooleanSupplier ready)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!ready.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(10);assertTrue(ready.getAsBoolean());}
    final class Fixture implements AutoCloseable,UploadTransport.Gateway{
        final UploadTestFixture store;final TelegramService telegram=mock(TelegramService.class);final ResourceService reads=mock(ResourceService.class);final UploadTargetReader targets=mock(UploadTargetReader.class);final AtomicReference<TelegramService.SendUpdateListener> listener=new AtomicReference<>();
        final Map<Long,TdApi.Message> nativeMessages=new ConcurrentHashMap<>();final List<Long>resends=new ArrayList<>();UploadRetryRepository retryRepo;UploadTransport transport;UploadRetryService service;long generation=1;int newSends,counter;boolean dropReply,failAgain,archiveChange;
        Fixture(int count,String type)throws Exception{store=new UploadTestFixture(root.resolve(UUID.randomUUID().toString()),count,type);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenAnswer(i->generation);doAnswer(i->{listener.set(i.getArgument(0));return (AutoCloseable)()->listener.set(null);}).when(telegram).observeSends(any());when(reads.readWork(any())).thenAnswer(i->CompletableFuture.completedFuture(((Supplier<?>)i.getArgument(0)).get()));when(targets.read(anyString(),eq("1"),anyLong(),anySet())).thenReturn(store.prepared.target());initialize();var task=store.repository.start("1",store.task.id());task=store.repository.next("1");var group=task.groups().getFirst();store.repository.preparing(task,group);for(var item:group.items())store.files.stage(task,item,()->{});task=store.fresh();group=task.groups().getFirst();store.repository.intent(task,group,transport.owner(generation));store.repository.dispatch(task,group,transport.owner(generation));var initial=new ArrayList<Initial>();for(var item:group.items()){var m=message(item,100+item.ordinal(),false);nativeMessages.put(m.id,m);initial.add(new Initial(Long.toString(m.id),(int)group.id(),true,null));}store.repository.initial(task,group,initial);}
        void initialize(){retryRepo=new UploadRetryRepository(store.database,store.repository);transport=new UploadTransport(telegram,store.repository,store.files,retryRepo,this);service=new UploadRetryService(store.repository,retryRepo,transport,store.files,reads,targets,telegram);}
        void restart(){transport.close();new UploadRepository(store.database);generation++;initialize();}
        Item item(int n){return store.fresh().groups().getFirst().items().get(n);}
        void success(int n)throws Exception{var old=100+n;var m=message(item(n),1000+n,true);nativeMessages.remove((long)old);listener.get().accept("1",generation,new TdApi.UpdateMessageSendSucceeded(m,old));until(()->item(n).formalId()!=null);}
        void fail(int n)throws Exception{var old=100+n;var m=message(item(n),200+n,false);m.sendingState=failed();if(item(n).file().mediaType().equals("PHOTO")){var file=((TdApi.MessagePhoto)m.content).photo.sizes[0].photo;file.size+=7;file.local.path="converted-in-native-cache";}nativeMessages.remove((long)old);nativeMessages.put(m.id,m);listener.get().accept("1",generation,new TdApi.UpdateMessageSendFailed(m,old,new TdApi.Error(500,"synthetic")));until(()->retryRepo.failure(store.fresh().groups().getFirst().id(),Integer.toString(old))!=null);}
        void review(){store.repository.state(store.fresh(),"NEEDS_REVIEW","synthetic partial failure");}
        void execute(){var task=store.repository.next("1");assertNotNull(task);assertTrue(service.execute(task,generation,()->{}));}
        int attemptCount(){return count("upload_retry_attempts");}int eventCount(){return count("upload_events");}int count(String t){return store.database.read(c->{try(var q=c.createStatement();var r=q.executeQuery("SELECT COUNT(*) FROM "+t)){r.next();return r.getInt(1);}});}
        TdApi.MessageSendingStateFailed failed(){return new TdApi.MessageSendingStateFailed(new TdApi.Error(500,"synthetic"),true,false,false,false,0,0);}
        @SuppressWarnings("unchecked")public <R extends TdApi.Object>CompletableFuture<R>call(long session,TdApi.Function<R>query){
            if(query instanceof TdApi.GetMessage get)return CompletableFuture.completedFuture((R)nativeMessages.get(get.messageId));
            if(!(query instanceof TdApi.ResendMessages retry)){newSends++;return CompletableFuture.failedFuture(new IllegalArgumentException("Unexpected new send"));}
            assertEquals(1,retry.messageIds.length);assertEquals(0,retry.paidMessageStarCount);var old=retry.messageIds[0];var failed=nativeMessages.remove(old);assertNotNull(failed);assertTrue(failed.sendingState instanceof TdApi.MessageSendingStateFailed);resends.add(old);
            var caption=failed.content instanceof TdApi.MessageDocument d?d.caption.text:failed.content instanceof TdApi.MessagePhoto p?p.caption.text:failed.content instanceof TdApi.MessageVideo v?v.caption.text:failed.content instanceof TdApi.MessageAudio a?a.caption.text:((TdApi.MessageAnimation)failed.content).caption.text;
            var item=store.fresh().groups().getFirst().items().stream().filter(i->i.caption().equals(caption)).findFirst().orElseThrow();long temporary=3000+(++counter);var pending=message(item,temporary,false);pending.mediaAlbumId=0;nativeMessages.put(temporary,pending);
            if(dropReply)return CompletableFuture.failedFuture(new TimeoutException("lost initial reply"));
            if(failAgain){var again=message(item,4000+counter,false);again.mediaAlbumId=0;again.sendingState=failed();nativeMessages.remove(temporary);nativeMessages.put(again.id,again);listener.get().accept("1",generation,new TdApi.UpdateMessageSendFailed(again,temporary,new TdApi.Error(500,"synthetic")));}
            else{var sent=message(item,9000+counter,true);sent.mediaAlbumId=0;nativeMessages.remove(temporary);listener.get().accept("1",generation,new TdApi.UpdateMessageSendSucceeded(sent,temporary));}
            if(archiveChange)try{Files.writeString(store.source.resolve(item.file().relativePath()),"new source version");}catch(Exception e){throw new RuntimeException(e);}
            return CompletableFuture.completedFuture((R)new TdApi.Messages(1,new TdApi.Message[]{pending}));
        }
        TdApi.Message message(Item item,long id,boolean formal){var m=new TdApi.Message();m.id=id;m.chatId=-2001;m.isOutgoing=true;m.date=1;m.topicId=new TdApi.MessageTopicThread(500);m.mediaAlbumId=store.fresh().groups().getFirst().mode().equals("ALBUM")?77:0;m.sendingState=formal?null:new TdApi.MessageSendingStatePending((int)store.fresh().groups().getFirst().id());var file=new TdApi.File();file.size=item.file().size();file.local=new TdApi.LocalFile();file.local.path=item.staged().path();file.remote=new TdApi.RemoteFile();file.remote.uniqueId="native-"+item.id();var caption=new TdApi.FormattedText(item.caption(),new TdApi.TextEntity[0]);
            switch(item.file().mediaType()){
                case "PHOTO"->{var c=new TdApi.MessagePhoto();c.caption=caption;c.photo=new TdApi.Photo();var s=new TdApi.PhotoSize();s.photo=file;s.width=2;s.height=1;c.photo.sizes=new TdApi.PhotoSize[]{s};m.content=c;}
                case "VIDEO"->{var c=new TdApi.MessageVideo();c.caption=caption;c.video=new TdApi.Video();c.video.video=file;m.content=c;}
                case "AUDIO"->{var c=new TdApi.MessageAudio();c.caption=caption;c.audio=new TdApi.Audio();c.audio.audio=file;m.content=c;}
                case "ANIMATION"->{var c=new TdApi.MessageAnimation();c.caption=caption;c.animation=new TdApi.Animation();c.animation.animation=file;m.content=c;}
                default->{var c=new TdApi.MessageDocument();c.caption=caption;c.document=new TdApi.Document();c.document.document=file;m.content=c;}
            }return m;
        }
        public void close(){transport.close();store.close();}
    }
}
