package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadEngineTest {
    @TempDir Path root;
    @Test void aSavedReadyTaskNeverSendsUntilAnExplicitStartAndThenArchivesOnlyConfirmedMembers()throws Exception{
        try(var f=new Fixture(2,true)){Thread.sleep(1400);assertEquals(0,f.sends.get());assertEquals("READY",f.store.fresh().state());f.store.repository.start("1",f.store.task.id());waitFor(()->f.store.fresh().state().equals("COMPLETED"));assertEquals(1,f.sends.get());assertEquals(2,f.store.repository.view(f.store.fresh()).archived());assertFalse(Files.exists(f.store.source.resolve("file1.bin")));}
    }
    @Test void aTemporaryOnlyResponsePreservesSourceFilesAndBecomesUncertainOnExit()throws Exception{
        try(var f=new Fixture(1,false)){f.store.repository.start("1",f.store.task.id());waitFor(()->f.store.fresh().groups().getFirst().state().equals("SENDING"));assertEquals(0,f.store.repository.view(f.store.fresh()).confirmed());assertFalse(Files.exists(f.store.archive));f.engine.close();assertEquals("NEEDS_REVIEW",f.store.fresh().state());assertTrue(Files.exists(f.store.source.resolve("file1.bin")));assertEquals(1,f.sends.get());}
    }
    @Test void archiveRetryCannotSendTheNextGroupAndSourceChangesStopTheFileMove()throws Exception{
        try(var f=new Fixture(11,true)){f.changeSource=true;f.store.repository.start("1",f.store.task.id());waitFor(()->f.store.fresh().state().equals("SENT_PENDING_ARCHIVE"));assertEquals(1,f.sends.get());var source=f.store.source.resolve("file1.bin");Files.writeString(source,"synthetic-file-1");Files.setLastModifiedTime(source,FileTime.fromMillis(f.store.task.groups().getFirst().items().getFirst().file().modifiedMillis()));f.changeSource=false;f.store.repository.reconcile("1",f.store.task.id());waitFor(()->f.store.fresh().state().equals("PAUSED"));assertEquals(1,f.sends.get());assertEquals(10,f.store.repository.view(f.store.fresh()).archived());assertTrue(Files.exists(f.store.source.resolve("file11.bin")));}
    }
    @Test void aPausedTwentyThreeFileTaskRestartsWithoutReplayingTheFirstAlbum()throws Exception{
        try(var f=new Fixture(23,true)){
            f.pauseAfterGroup=1;var frozen=f.store.task.prepared();f.store.repository.start("1",f.store.task.id());waitFor(()->f.store.fresh().state().equals("PAUSED"));
            assertEquals(1,f.sends.get());assertEquals(10,f.store.repository.view(f.store.fresh()).archived());assertEquals(List.of(10),f.groupSizes);
            f.engine.close();var restarted=new UploadRepository(f.store.database);
            try(var engine=new UploadEngine(restarted,f.store.files,f.transport,f.telegram,f.targets,f.reads)){
                Thread.sleep(1200);assertEquals(1,f.sends.get());assertEquals("PAUSED",f.store.fresh().state());assertEquals(frozen,f.store.fresh().prepared());
                f.pauseAfterGroup=0;restarted.start("1",f.store.task.id());waitFor(()->f.store.fresh().state().equals("COMPLETED"));
                assertEquals(List.of(10,10,3),f.groupSizes);assertEquals(3,f.sends.get());assertEquals(23,f.store.repository.view(f.store.fresh()).archived());
                assertEquals(23,f.store.fresh().groups().stream().flatMap(g->g.items().stream()).map(Item::formalId).distinct().count());
                try(var source=Files.list(f.store.source);var archive=Files.list(f.store.archive)){assertEquals(0,source.count());assertEquals(23,archive.count());}
            }
        }
    }
    @Test void aWindowsFileLockMidwayThroughTheSecondAlbumRecoversOnlyArchivesAndRequiresExplicitThirdAlbumStart()throws Exception{
        try(var f=new Fixture(23,true)){
            f.pauseAfterGroup=1;f.store.repository.start("1",f.store.task.id());waitFor(()->f.store.fresh().state().equals("PAUSED"));
            var firstReceipts=f.store.fresh().groups().getFirst().items().stream().map(Item::formalId).toList();f.pauseAfterGroup=0;
            try(var held=java.nio.channels.FileChannel.open(f.store.source.resolve("file15.bin"),StandardOpenOption.READ,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)){
                f.store.repository.start("1",f.store.task.id());waitFor(()->f.store.fresh().state().equals("SENT_PENDING_ARCHIVE"));
                assertEquals(2,f.sends.get());assertEquals(20,f.store.repository.view(f.store.fresh()).confirmed());assertEquals(14,f.store.repository.view(f.store.fresh()).archived());
                assertTrue(Files.exists(f.store.source.resolve("file15.bin")));assertEquals("READY",f.store.fresh().groups().get(2).state());
                f.engine.close();
            }
            var restarted=new UploadRepository(f.store.database);assertEquals("SENT_PENDING_ARCHIVE",f.store.fresh().state());
            try(var engine=new UploadEngine(restarted,f.store.files,f.transport,f.telegram,f.targets,f.reads)){
                restarted.reconcile("1",f.store.task.id());waitFor(()->f.store.fresh().state().equals("PAUSED"));
                assertEquals(2,f.sends.get());assertEquals(20,f.store.repository.view(f.store.fresh()).archived());assertTrue(Files.exists(f.store.source.resolve("file21.bin")));
                assertEquals(firstReceipts,f.store.fresh().groups().getFirst().items().stream().map(Item::formalId).toList());
                restarted.start("1",f.store.task.id());waitFor(()->f.store.fresh().state().equals("COMPLETED"));
                assertEquals(List.of(10,10,3),f.groupSizes);assertEquals(3,f.sends.get());assertEquals(23,f.store.repository.view(f.store.fresh()).archived());
            }
        }
    }
    @Test void losingTheSessionAfterFormalReceiptsPreservesTheConfirmedAlbumForArchiveOnlyRecovery()throws Exception{
        try(var f=new Fixture(11,true)){
            f.loseSessionAfterGroup=1;f.store.repository.start("1",f.store.task.id());waitFor(()->f.store.fresh().state().equals("NEEDS_REVIEW"));
            assertEquals(1,f.sends.get());assertEquals("CONFIRMED",f.store.fresh().groups().getFirst().state());assertEquals(10,f.store.repository.view(f.store.fresh()).confirmed());assertEquals(0,f.store.repository.view(f.store.fresh()).archived());
            f.engine.close();var restarted=new UploadRepository(f.store.database);when(f.telegram.sessionGeneration()).thenReturn(1L);
            try(var engine=new UploadEngine(restarted,f.store.files,f.transport,f.telegram,f.targets,f.reads)){
                restarted.reconcile("1",f.store.task.id());waitFor(()->f.store.fresh().state().equals("PAUSED"));
                assertEquals(1,f.sends.get());assertEquals(10,f.store.repository.view(f.store.fresh()).archived());assertTrue(Files.exists(f.store.source.resolve("file11.bin")));
            }
        }
    }
    private static void waitFor(java.util.function.BooleanSupplier ready)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(!ready.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(30);assertTrue(ready.getAsBoolean());}
    private final class Fixture implements AutoCloseable {
        final UploadTestFixture store;final TelegramService telegram=mock(TelegramService.class);final UploadTransport transport=mock(UploadTransport.class);final ResourceService reads=mock(ResourceService.class);final UploadTargetReader targets=mock(UploadTargetReader.class);final AtomicInteger sends=new AtomicInteger();final List<Integer> groupSizes=new CopyOnWriteArrayList<>();final UploadEngine engine;boolean changeSource;volatile int pauseAfterGroup,loseSessionAfterGroup;
        Fixture(int count,boolean confirm)throws Exception{store=new UploadTestFixture(root,count);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);when(transport.owner(1)).thenReturn("test-run");when(reads.readWork(any())).thenAnswer(i->CompletableFuture.completedFuture(((Supplier<?>)i.getArgument(0)).get()));when(targets.read(anyString(),eq("1"),eq(1L),anySet())).thenReturn(store.prepared.target());
            doAnswer(inv->{sends.incrementAndGet();Task task=inv.getArgument(0);Group group=inv.getArgument(1);groupSizes.add(group.items().size());var initial=new ArrayList<Initial>();for(var item:group.items())initial.add(new Initial(Long.toString(group.id()*100+item.ordinal()),(int)group.id(),true,null));store.repository.initial(task,group,initial);if(pauseAfterGroup==group.number())store.repository.pause("1",task.id());if(confirm){for(var item:group.items())store.repository.event(group.id(),new Evidence(Long.toString(group.id()*100+item.ordinal()),Long.toString(10000+group.id()*100+item.ordinal()),"-2001","500",null,group.mode().equals("ALBUM")?Long.toString(77+group.id()):"0",item.file().mediaType(),item.caption(),"remote"+item.id(),true,true,1));if(changeSource)Files.writeString(store.source.resolve("file1.bin"),"modified-during-upload");if(loseSessionAfterGroup==group.number())when(telegram.sessionGeneration()).thenReturn(2L);}return null;}).when(transport).send(any(),any(),eq(1L));engine=new UploadEngine(store.repository,store.files,transport,telegram,targets,reads);
        }
        public void close(){engine.close();store.close();}
    }
}
