package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadRepositoryTest {
    @TempDir Path root;
    @Test void creationIsReadyOnlyDurableAndIdempotentButCannotReserveTheSameTargetContentTwice()throws Exception{
        try(var f=new UploadTestFixture(root,2)){assertEquals("READY",f.fresh().state());assertTrue(f.fresh().groups().stream().allMatch(g->g.ownerRun()==null));var duplicate=f.repository.create("1",f.task.operationId(),"fp",f.prepared);assertEquals(f.task.id(),duplicate.id());assertThrows(IllegalArgumentException.class,()->f.repository.create("1",UUID.randomUUID().toString(),"other",f.prepared));assertEquals(1,f.repository.list("1",20,0).size());assertThrows(IllegalArgumentException.class,()->f.repository.task("2",f.task.id()));}
    }
    @Test void anInitialTemporaryMessageAndUploadProgressNeverCountAsFormalSuccess()throws Exception{
        try(var f=new UploadTestFixture(root,2)){f.prepareAndIntent();var t=f.fresh();var g=t.groups().getFirst();f.repository.initial(t,g,List.of(new Initial("100",Math.toIntExact(g.id()),true,null),new Initial("101",Math.toIntExact(g.id()),true,null)));assertEquals(0,f.repository.view(f.fresh()).confirmed());assertEquals("SENDING",f.fresh().groups().getFirst().state());assertEquals(0,f.repository.view(f.fresh()).archived());}
    }
    @Test void earlySuccessUpdatesAreJournalledBeforeTempIdsArriveAndEachMemberIsConfirmedSeparately()throws Exception{
        try(var f=new UploadTestFixture(root,2)){f.prepareAndIntent();var t=f.fresh();var g=t.groups().getFirst();f.repository.event(g.id(),f.proof(g.items().getFirst(),g,"1000"));assertEquals(0,f.repository.view(f.fresh()).confirmed());f.repository.initial(t,g,List.of(new Initial("100",Math.toIntExact(g.id()),true,null),new Initial("101",Math.toIntExact(g.id()),true,null)));assertEquals(1,f.repository.view(f.fresh()).confirmed());assertNotEquals("CONFIRMED",f.fresh().groups().getFirst().state());f.repository.event(g.id(),f.proof(g.items().get(1),g,"1001"));assertEquals("CONFIRMED",f.fresh().groups().getFirst().state());assertEquals(2,f.repository.view(f.fresh()).confirmed());}
    }
    @Test void wrongThreadCaptionTypeOrAlbumDoesNotBecomeAConfirmedGroup()throws Exception{
        try(var f=new UploadTestFixture(root,2)){f.prepareAndIntent();var t=f.fresh();var g=t.groups().getFirst();f.repository.initial(t,g,List.of(new Initial("100",Math.toIntExact(g.id()),true,null),new Initial("101",Math.toIntExact(g.id()),true,null)));var p=f.proof(g.items().getFirst(),g,"1000");f.repository.event(g.id(),new Evidence(p.oldId(),p.formalId(),p.chatId(),"999",null,p.albumId(),p.mediaType(),p.caption(),p.uniqueId(),true,true,1));assertEquals(0,f.repository.view(f.fresh()).confirmed());assertEquals("UNCERTAIN",f.fresh().groups().getFirst().state());assertThrows(IllegalArgumentException.class,()->f.repository.start("1",t.id()));}
    }
    @Test void restartingAnInFlightIntentRequiresReconciliationAndCannotReplayTheSend()throws Exception{
        try(var f=new UploadTestFixture(root,1)){f.prepareAndIntent();new UploadRepository(f.database);assertEquals("NEEDS_REVIEW",f.fresh().state());assertEquals("UNCERTAIN",f.fresh().groups().getFirst().state());assertThrows(IllegalArgumentException.class,()->f.repository.start("1",f.task.id()));f.repository.reconcile("1",f.task.id());assertTrue(f.fresh().stopRequested());}
    }
    @Test void cancellationOnlyBeforeSendingReleasesTheReservationWithoutDeletingFiles()throws Exception{
        try(var f=new UploadTestFixture(root,1)){f.repository.cancel("1",f.task.id());assertEquals("CANCELLED",f.fresh().state());var second=f.repository.create("1",UUID.randomUUID().toString(),"new",f.prepared);assertEquals("READY",second.state());assertTrue(java.nio.file.Files.exists(f.source.resolve("file1.bin")));}
    }
    @Test void acceptingADeliveredGifAsDocumentIsExplicitDurableAndOnlyArchivesWithoutAnotherSend()throws Exception{
        try(var f=new UploadTestFixture(root,1,"ANIMATION")){
            documentReceipt(f,"500");var before=f.fresh();var group=before.groups().getFirst();assertEquals(0,f.repository.view(before).confirmed());assertTrue(f.repository.view(before).groups().getFirst().canAcceptDocument());
            assertThrows(IllegalArgumentException.class,()->f.repository.acceptDocument("2",f.task.id(),group.id()));
            assertThrows(IllegalArgumentException.class,()->f.repository.acceptDocument("1",f.task.id(),group.id()+100));
            var accepted=f.repository.acceptDocument("1",f.task.id(),group.id());assertEquals("QUEUED",accepted.state());assertTrue(accepted.stopRequested());assertEquals(before.prepared(),accepted.prepared());assertEquals(before.fingerprint(),accepted.fingerprint());
            assertEquals("CONFIRMED",accepted.groups().getFirst().state());assertEquals(1,f.repository.view(accepted).confirmed());assertFalse(f.repository.view(accepted).groups().getFirst().canAcceptDocument());
            assertEquals(accepted,f.repository.acceptDocument("1",f.task.id(),group.id()));
            var restarted=new UploadRepository(f.database);assertEquals("PAUSED",f.fresh().state());assertEquals("ANIMATION",f.fresh().groups().getFirst().mediaType());
            restarted.reconcile("1",f.task.id());var telegram=mock(io.github.telegramenhance.telegram.TelegramService.class);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);
            var transport=mock(io.github.telegramenhance.telegram.UploadTransport.class);var targets=mock(io.github.telegramenhance.telegram.UploadTargetReader.class);var reads=mock(io.github.telegramenhance.library.ResourceService.class);
            try(var engine=new UploadEngine(restarted,f.files,transport,telegram,targets,reads)){
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(6);while(!f.fresh().state().equals("COMPLETED")&&System.nanoTime()<until)Thread.sleep(30);
                assertEquals("COMPLETED",f.fresh().state());assertEquals(1,f.repository.view(f.fresh()).archived());assertFalse(java.nio.file.Files.exists(f.source.resolve("file1.gif")));assertTrue(java.nio.file.Files.exists(f.archive.resolve("file1.gif")));
                verify(transport,never()).send(any(),any(),anyLong());verify(transport,never()).recoverPending(any(),anyLong());verifyNoInteractions(targets,reads);
            }
            assertEquals("COMPLETED",f.repository.acceptDocument("1",f.task.id(),group.id()).state());
        }
    }
    @Test void documentAcceptanceCannotBypassWrongThreadOrAnAudioTypeMismatch()throws Exception{
        try(var f=new UploadTestFixture(root.resolve("wrong-thread"),1,"ANIMATION")){
            documentReceipt(f,"999");assertFalse(f.repository.view(f.fresh()).groups().getFirst().canAcceptDocument());assertThrows(IllegalArgumentException.class,()->f.repository.acceptDocument("1",f.task.id(),f.fresh().groups().getFirst().id()));assertEquals(0,f.repository.view(f.fresh()).confirmed());
        }
        try(var f=new UploadTestFixture(root.resolve("audio"),1,"AUDIO")){
            documentReceipt(f,"500");assertFalse(f.repository.view(f.fresh()).groups().getFirst().canAcceptDocument());assertThrows(IllegalArgumentException.class,()->f.repository.acceptDocument("1",f.task.id(),f.fresh().groups().getFirst().id()));assertEquals(0,f.repository.view(f.fresh()).confirmed());
        }
    }
    private static void documentReceipt(UploadTestFixture f,String thread)throws Exception{
        f.prepareAndIntent();var task=f.fresh();var group=task.groups().getFirst();f.repository.initial(task,group,List.of(new Initial("100",Math.toIntExact(group.id()),true,null)));
        var item=group.items().getFirst();f.repository.event(group.id(),new Evidence("100","1000","-2001",thread,null,"0","DOCUMENT",item.caption(),"remote-doc",true,true,1));f.repository.state(task,"NEEDS_REVIEW","type mismatch");
    }
}
