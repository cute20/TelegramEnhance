package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import it.tdlight.jni.TdApi;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadTransportTest {
    @TempDir Path root;
    @Test void formalUpdatesBeforeInitialReplyAreCorrelatedAndPersistedWithoutResending()throws Exception{
        try(var f=new Fixture(2,true)){f.send();waitFor(()->f.store.fresh().groups().getFirst().state().equals("CONFIRMED"));assertEquals(2,f.store.repository.view(f.store.fresh()).confirmed());assertEquals(1,f.requests.size());assertTrue(Files.exists(f.store.source.resolve("file1.bin")));assertThrows(IllegalArgumentException.class,f::send);assertEquals(1,f.requests.size());
            var call=(TdApi.SendMessageAlbum)f.requests.getFirst();assertEquals(-2001,call.chatId);assertEquals(500,((TdApi.MessageTopicThread)call.topicId).messageThreadId);assertTrue(Arrays.stream(call.inputMessageContents).allMatch(c->c instanceof TdApi.InputMessageDocument doc&&doc.document.disableContentTypeDetection));}
    }
    @Test void returningTemporaryMessagesAloneNeverConfirmsOrArchives()throws Exception{
        try(var f=new Fixture(1,false)){f.send();assertEquals("SENDING",f.store.fresh().groups().getFirst().state());assertEquals(0,f.store.repository.view(f.store.fresh()).confirmed());assertTrue(f.requests.getFirst() instanceof TdApi.SendMessage);assertFalse(Files.exists(f.store.archive));}
    }
    @Test void aNativeTimeoutConsumesTheIntentAndNeverPermitsAutomaticReplay()throws Exception{
        try(var f=new Fixture(1,false)){f.fail=true;assertThrows(IllegalArgumentException.class,f::send);assertThrows(IllegalArgumentException.class,f::send);assertEquals(1,f.requests.size());assertTrue(Files.exists(f.store.source.resolve("file1.bin")));}
    }
    @Test void aChangedAccountCannotDispatchEvenAnAlreadyPreparedIntent()throws Exception{
        try(var f=new Fixture(1,true)){when(f.telegram.accountId()).thenReturn("2");assertThrows(IllegalArgumentException.class,f::send);assertTrue(f.requests.isEmpty());}
    }
    @Test void anAudioAlbumWaitsForEveryFormalMemberAndKeepsOriginalFiles()throws Exception{
        try(var f=new Fixture(2,false,"AUDIO")){
            f.send();var group=f.store.fresh().groups().getFirst();
            f.succeed(0,null);waitFor(()->f.store.repository.view(f.store.fresh()).confirmed()==1);
            assertEquals("SENDING",f.store.fresh().groups().getFirst().state());
            assertEquals(0,f.store.repository.view(f.store.fresh()).archived());
            assertTrue(Files.exists(f.store.source.resolve("file1.mp3")));assertTrue(Files.exists(f.store.source.resolve("file2.mp3")));
            f.succeed(1,null);waitFor(()->f.store.fresh().groups().getFirst().state().equals("CONFIRMED"));
            assertEquals(2,f.store.repository.view(f.store.fresh()).confirmed());assertEquals(1,f.requests.size());
            assertTrue(f.requests.getFirst() instanceof TdApi.SendMessageAlbum);
            assertEquals(group.id(),f.store.fresh().groups().getFirst().id());
        }
    }
    @Test void anAudioReceiptReclassifiedAsDocumentCannotConfirmTheFrozenAudioGroup()throws Exception{
        try(var f=new Fixture(2,false,"AUDIO")){
            f.send();var original=f.store.fresh().groups().getFirst().items().getFirst();
            var wrong=new TdApi.MessageDocument();wrong.caption=new TdApi.FormattedText(original.caption(),new TdApi.TextEntity[0]);
            f.succeed(0,wrong);waitFor(()->f.store.fresh().groups().getFirst().state().equals("UNCERTAIN"));
            assertEquals(0,f.store.repository.view(f.store.fresh()).confirmed());assertEquals(0,f.store.repository.view(f.store.fresh()).archived());
            assertTrue(Files.exists(f.store.source.resolve("file1.mp3")));assertEquals(1,f.requests.size());
        }
    }
    @Test void aGifConvertedToMpeg4UsesItsFormalAnimationIdentityAndRemainsASingleMessage()throws Exception{
        try(var f=new Fixture(1,false,"ANIMATION")){
            f.send();f.succeed(0,null);waitFor(()->f.store.fresh().groups().getFirst().state().equals("CONFIRMED"));
            var item=f.store.fresh().groups().getFirst().items().getFirst();
            assertEquals("remote"+item.id(),item.remoteUniqueId());assertEquals("1000",item.formalId());
            assertTrue(f.requests.getFirst() instanceof TdApi.SendMessage);assertEquals(1,f.requests.size());
            var sent=(TdApi.InputMessageAnimation)((TdApi.SendMessage)f.requests.getFirst()).inputMessageContent;
            assertEquals(2,sent.animation.width);assertEquals(1,sent.animation.height);
            assertTrue(Files.exists(f.store.source.resolve("file1.gif")));assertEquals(0,f.store.repository.view(f.store.fresh()).archived());
        }
    }
    @Test void aGifReturnedAsDocumentShowsItsActualDeliveryWithoutCountingItAsAnAnimationOrAllowingResend()throws Exception{
        try(var f=new Fixture(1,false,"ANIMATION")){
            f.send();var item=f.store.fresh().groups().getFirst().items().getFirst();
            var document=new TdApi.MessageDocument();document.caption=new TdApi.FormattedText(item.caption(),new TdApi.TextEntity[0]);
            f.succeed(0,document);waitFor(()->f.store.fresh().groups().getFirst().state().equals("UNCERTAIN"));
            var group=f.store.fresh().groups().getFirst();assertTrue(group.error().contains("文件已送达"));assertTrue(group.error().contains("文档"));assertTrue(group.error().contains("动图"));
            assertTrue(group.items().getFirst().error().contains("1000"));assertEquals(0,f.store.repository.view(f.store.fresh()).confirmed());
            f.store.repository.uncertain(group,"generic error");assertEquals(group.error(),f.store.fresh().groups().getFirst().error());
            f.store.repository.state(f.store.fresh(),"NEEDS_REVIEW",group.error());assertThrows(IllegalArgumentException.class,()->f.store.repository.start("1",f.store.task.id()));
            assertTrue(Files.exists(f.store.source.resolve("file1.gif")));assertEquals(1,f.requests.size());
        }
    }
    @Test void aFailedAnimationUpdateNeverConfirmsOrRemovesTheSource()throws Exception{
        try(var f=new Fixture(1,false,"ANIMATION")){
            f.send();var group=f.store.fresh().groups().getFirst();var message=f.message(group.items().getFirst(),100,group.id(),false);
            var failed=new TdApi.UpdateMessageSendFailed();failed.message=message;failed.oldMessageId=100;
            f.listener.get().accept("1",1,failed);waitFor(()->f.store.fresh().groups().getFirst().state().equals("UNCERTAIN"));
            assertEquals(0,f.store.repository.view(f.store.fresh()).confirmed());assertTrue(Files.exists(f.store.source.resolve("file1.gif")));assertFalse(Files.exists(f.store.archive));
        }
    }
    @Test void explicitPendingRecoveryAfterRestartAcceptsOnlyFreshNativeReceiptsWithoutSendingAgain()throws Exception{
        try(var f=new Fixture(2,false)){
            f.send();String owner=f.store.fresh().groups().getFirst().ownerRun();f.restart();
            f.pendingChange=m->m.sendingState=new TdApi.MessageSendingStatePending(0);
            assertEquals(2,f.transport.recoverPending(f.store.fresh(),2));assertEquals("NEEDS_REVIEW",f.store.fresh().state());
            var staged=Path.of(f.store.fresh().groups().getFirst().items().getFirst().staged().path());
            assertThrows(java.io.IOException.class,()->Files.writeString(staged,"tamper"));
            f.succeed(0,null);waitFor(()->f.store.repository.view(f.store.fresh()).confirmed()==1);
            assertEquals("UNCERTAIN",f.store.fresh().groups().getFirst().state());assertEquals(0,f.store.repository.view(f.store.fresh()).archived());
            f.succeed(1,null);waitFor(()->f.store.fresh().groups().getFirst().state().equals("CONFIRMED"));
            assertEquals(owner,f.store.fresh().groups().getFirst().ownerRun());assertEquals(1,f.sendCalls());
            assertTrue(Files.exists(f.store.source.resolve("file1.bin")));assertFalse(Files.exists(f.store.archive));
            f.transport.close();Files.writeString(staged,"lease released");
        }
    }
    @Test void pendingRecoveryRejectsOtherFilesThreadsCaptionsAndSendingIds()throws Exception{
        try(var f=new Fixture(1,false)){
            f.send();f.restart();
            for(var change:List.<java.util.function.Consumer<TdApi.Message>>of(
                    m->((TdApi.MessageDocument)m.content).document.document.local.path=root.resolve("unowned.bin").toString(),
                    m->m.topicId=new TdApi.MessageTopicThread(501),
                    m->((TdApi.MessageDocument)m.content).caption.text="another caption",
                    m->m.sendingState=new TdApi.MessageSendingStatePending(999),
                    m->((TdApi.MessageDocument)m.content).document.document.size++)){
                f.pendingChange=change;assertEquals(0,f.transport.recoverPending(f.store.fresh(),2));
            }
            f.succeed(0,null);f.transport.close();assertEquals(0,f.store.repository.view(f.store.fresh()).confirmed());
            assertEquals(1,f.sendCalls());assertTrue(Files.exists(f.store.source.resolve("file1.bin")));
        }
    }
    @Test void aStaleSessionCannotUseVerifiedPendingBindingsAndExpiryReleasesTheFiles()throws Exception{
        try(var f=new Fixture(1,false)){
            f.send();f.restart();assertEquals(1,f.transport.recoverPending(f.store.fresh(),2));
            var staged=Path.of(f.store.fresh().groups().getFirst().items().getFirst().staged().path());
            when(f.telegram.sessionGeneration()).thenReturn(3L);f.transport.expireRecoveries();Files.writeString(staged,"released after session ended");f.succeed(0,null);f.transport.close();
            assertEquals(0,f.store.repository.view(f.store.fresh()).confirmed());
            assertEquals(1,f.sendCalls());
        }
    }
    @Test void aMissingInitialAssociationOrChangedStagingCopyCannotBeRecoveredByFilename()throws Exception{
        try(var f=new Fixture(1,false)){
            f.fail=true;assertThrows(IllegalArgumentException.class,f::send);f.fail=false;f.restart();
            int calls=f.requests.size();assertEquals(0,f.transport.recoverPending(f.store.fresh(),2));assertEquals(calls,f.requests.size());
            assertEquals(0,f.store.repository.view(f.store.fresh()).confirmed());assertEquals(1,f.sendCalls());
        }
    }
    @Test void changedOwnedStagingCannotCreateARecoveryBinding()throws Exception{
        try(var f=new Fixture(1,false)){
            f.send();f.restart();var staged=Path.of(f.store.fresh().groups().getFirst().items().getFirst().staged().path());
            Files.writeString(staged,"changed copy");assertEquals(0,f.transport.recoverPending(f.store.fresh(),2));
            f.succeed(0,null);f.transport.close();assertEquals(0,f.store.repository.view(f.store.fresh()).confirmed());assertEquals(1,f.sendCalls());
        }
    }
    private static void waitFor(java.util.function.BooleanSupplier ready)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!ready.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(20);assertTrue(ready.getAsBoolean());}
    private final class Fixture implements AutoCloseable,UploadTransport.Gateway {
        final UploadTestFixture store;final TelegramService telegram=mock(TelegramService.class);final AtomicReference<TelegramService.SendUpdateListener>listener=new AtomicReference<>();final List<TdApi.Function<?>>requests=new ArrayList<>();UploadTransport transport;final boolean early;boolean fail;long generation=1;java.util.function.Consumer<TdApi.Message>pendingChange=m->{};
        Fixture(int count,boolean early)throws Exception{this(count,early,"DOCUMENT");}
        Fixture(int count,boolean early,String mediaType)throws Exception{this.early=early;store=new UploadTestFixture(root,count,mediaType);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);doAnswer(i->{listener.set(i.getArgument(0));return (AutoCloseable)()->listener.set(null);}).when(telegram).observeSends(any());transport=new UploadTransport(telegram,store.repository,store.files,this);
            store.repository.start("1",store.task.id());var task=store.repository.next("1");var group=task.groups().getFirst();store.repository.preparing(task,group);for(var item:group.items())store.files.stage(task,item,()->{});task=store.fresh();store.repository.intent(task,task.groups().getFirst(),transport.owner(1));}
        void send(){var task=store.fresh();transport.send(task,task.groups().getFirst(),1);}
        void restart(){transport.close();new UploadRepository(store.database);generation=2;when(telegram.sessionGeneration()).thenReturn(generation);transport=new UploadTransport(telegram,store.repository,store.files,this);}
        long sendCalls(){return requests.stream().filter(q->q instanceof TdApi.SendMessage||q instanceof TdApi.SendMessageAlbum).count();}
        @SuppressWarnings("unchecked")public <R extends TdApi.Object>CompletableFuture<R>call(long session,TdApi.Function<R>query){requests.add(query);if(fail)return CompletableFuture.failedFuture(new TimeoutException("synthetic timeout"));var group=store.fresh().groups().getFirst();if(query instanceof TdApi.GetMessage get){var item=group.items().stream().filter(i->Long.toString(get.messageId).equals(i.temporaryId())).findFirst().orElseThrow();var message=message(item,get.messageId,group.id(),false);pendingChange.accept(message);return CompletableFuture.completedFuture((R)message);}var result=new ArrayList<TdApi.Message>();for(var item:group.items()){var temp=message(item,100+item.ordinal(),group.id(),false);result.add(temp);if(early)listener.get().accept("1",1,new TdApi.UpdateMessageSendSucceeded(message(item,1000+item.ordinal(),group.id(),true),temp.id));}TdApi.Object response=query instanceof TdApi.SendMessage?result.getFirst():new TdApi.Messages(result.size(),result.toArray(TdApi.Message[]::new));return CompletableFuture.completedFuture((R)response);}
        void succeed(int ordinal,TdApi.MessageContent replacement){var group=store.fresh().groups().getFirst();var message=message(group.items().get(ordinal),1000+ordinal,group.id(),true);if(replacement!=null)message.content=replacement;listener.get().accept("1",generation,new TdApi.UpdateMessageSendSucceeded(message,100+ordinal));}
        TdApi.Message message(Item item,long id,long group,boolean formal){var m=new TdApi.Message();m.id=id;m.chatId=-2001;m.isOutgoing=true;m.date=1;m.topicId=new TdApi.MessageTopicThread(500);m.mediaAlbumId=store.task.groups().getFirst().items().size()>1?77:0;m.sendingState=formal?null:new TdApi.MessageSendingStatePending((int)group);
            var caption=new TdApi.FormattedText(item.caption(),new TdApi.TextEntity[0]);var file=new TdApi.File();file.remote=new TdApi.RemoteFile();file.remote.uniqueId="remote"+item.id();file.size=item.file().size();file.local=new TdApi.LocalFile();file.local.path=item.staged()==null?"":item.staged().path();
            switch(item.file().mediaType()){
                case "AUDIO"->{var c=new TdApi.MessageAudio();c.caption=caption;c.audio=new TdApi.Audio();c.audio.audio=file;c.audio.mimeType="audio/mpeg";c.audio.fileName="file.mp3";m.content=c;}
                case "ANIMATION"->{var c=new TdApi.MessageAnimation();c.caption=caption;c.animation=new TdApi.Animation();c.animation.animation=file;c.animation.mimeType="video/mp4";c.animation.fileName="converted.mp4";m.content=c;}
                default->{var c=new TdApi.MessageDocument();c.caption=caption;c.document=new TdApi.Document();c.document.document=file;m.content=c;}
            }return m;}
        public void close(){transport.close();store.close();}
    }
}
