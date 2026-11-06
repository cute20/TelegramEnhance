package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.DownloadFileReader;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadTaskDeletionTest {
    @TempDir Path directory;

    @Test void deletionIsDurableIdempotentAndRetainsTheOriginalOperationWithoutResumingIt(){
        try(var f=new Fixture()){
            var task=f.paused();var deleted=f.service.delete(task.id());assertEquals(task.id(),deleted.id());assertFalse(deleted.alreadyDeleted());
            assertTrue(f.service.delete(task.id()).alreadyDeleted());assertTrue(f.repository.list("1",50,0).isEmpty());assertTrue(f.repository.list("1",50,0,true).isEmpty());
            assertTrue(f.service.list(50,0).items().isEmpty());assertTrue(f.service.standalone(50,0).items().isEmpty());
            assertThrows(IllegalArgumentException.class,()->f.service.task(task.id()));assertThrows(IllegalArgumentException.class,()->f.service.resume(task.id()));assertThrows(IllegalArgumentException.class,()->f.service.pause(task.id()));
            assertNull(f.repository.next("1"));assertEquals(task.id(),f.repository.byOperation("1",task.operationId()).id());
            var same=f.repository.create("1",task.operationId(),task.fingerprint(),task.post(),f.output,List.of(task.items().getFirst().file()));assertEquals(task.id(),same.id());
            var restored=new DownloadRepository(f.database);assertTrue(restored.list("1",50,0).isEmpty());assertNull(restored.next("1"));
            assertEquals(task.id(),restored.byOperation("1",task.operationId()).id());assertEquals(1,f.count("download_task_deletions"));
            verifyNoInteractions(f.reader);assertFalse(Files.exists(f.output));
        }
    }
    @Test void onlyAStoppedPausedTaskCanBeDeleted(){
        for(String state:List.of("QUEUED","RUNNING","FAILED","COMPLETED"))try(var f=new Fixture()){
            var task=f.task(List.of(file(100,37,"file.bin","unique")));
            if(state.equals("RUNNING"))assertEquals(task.id(),f.repository.next("1").id());
            else if(!state.equals("QUEUED"))f.repository.finishState("1",task.id(),state,null);
            var failure=assertThrows(IllegalArgumentException.class,()->f.service.delete(task.id()));assertTrue(failure.getMessage().contains("已暂停"));
            assertEquals(0,f.count("download_task_deletions"));assertEquals(state,f.repository.task("1",task.id()).state());
        }
    }
    @Test void managedBatchAndRuleChildrenCannotBeDeletedOutsideTheirParent(){
        for(String kind:List.of("batch","rule"))try(var f=new Fixture()){
            var task=f.paused();String parent=UUID.randomUUID().toString();
            f.database.write(c->{
                if(kind.equals("batch")){
                    try(var q=c.prepareStatement("INSERT INTO download_batches VALUES(?,'1',?,'fingerprint','{}',1)")){q.setString(1,parent);q.setString(2,UUID.randomUUID().toString());q.executeUpdate();}
                    try(var q=c.prepareStatement("INSERT INTO download_batch_tasks VALUES(?,0,?)")){q.setString(1,parent);q.setString(2,task.id());q.executeUpdate();}
                }else{
                    try(var q=c.prepareStatement("INSERT INTO download_rules VALUES(?,'1',?,'fingerprint',1,1,0,0,1,1)")){q.setString(1,parent);q.setString(2,UUID.randomUUID().toString());q.executeUpdate();}
                    try(var q=c.prepareStatement("INSERT INTO download_rule_versions VALUES(?,1,'{}','-1001','channel',1)")){q.setString(1,parent);q.executeUpdate();}
                    try(var q=c.prepareStatement("INSERT INTO download_rule_tasks VALUES(?,?,'1',1,'{}',1,1,NULL,1)")){q.setString(1,task.id());q.setString(2,parent);q.executeUpdate();}
                }return null;
            });
            var failure=assertThrows(IllegalArgumentException.class,()->f.service.delete(task.id()));assertTrue(failure.getMessage().contains("子任务"));
            assertEquals(0,f.count("download_task_deletions"));assertEquals("PAUSED",f.repository.task("1",task.id()).state());
        }
    }
    @Test void deletionGuardsAccountOwnershipAndRollsBackAChangedSessionBeforeCommit(){
        try(var f=new Fixture()){
            var task=f.paused();f.account="2";assertThrows(IllegalArgumentException.class,()->f.service.delete(task.id()));assertEquals(0,f.count("download_task_deletions"));
            f.account="1";var guards=new AtomicInteger();
            assertThrows(IllegalArgumentException.class,()->f.repository.deletePausedStandalone("1",task.id(),()->{if(guards.incrementAndGet()==2)throw new IllegalArgumentException("session changed");}));
            assertEquals(0,f.count("download_task_deletions"));assertEquals("PAUSED",f.repository.task("1",task.id()).state());
        }
    }
    @Test void cleanupRemovesOnlyOwnedPartialFilesAndPreservesPublishedBytesReceiptsAndDeduplication()throws Exception{
        try(var f=new Fixture()){
            var done=file(100,37,"published.bin","published-unique");var unfinished=file(101,1024*1024+7,"unfinished.bin","unfinished-unique");
            var task=f.task(List.of(done,unfinished));f.store.write(task,task.items().getFirst(),(offset,count)->new byte[count],()->{});
            writePartial(f,task,task.items().getLast());task=f.repository.pause("1",task.id());
            Path published=f.output.resolve("published.bin"),part=part(f,task.items().getLast()),owner=owner(f,task.items().getLast());
            Files.writeString(f.output.resolve("user-notes.txt"),"keep");assertTrue(Files.exists(part));assertTrue(Files.exists(owner));
            f.service.delete(task.id());assertTrue(Files.exists(part),"HTTP deletion only commits a durable cleanup request");
            var cleanup=f.repository.deletionCleanup("1",8).getFirst();assertTrue(f.store.cleanupDeleted(cleanup));f.repository.deletionCleaned("1",task.id());
            assertFalse(Files.exists(part));assertFalse(Files.exists(owner));assertArrayEquals(new byte[37],Files.readAllBytes(published));assertEquals("keep",Files.readString(f.output.resolve("user-notes.txt")));
            assertEquals(1,f.count("download_history"));assertEquals(1,f.count("download_completion_log"));assertEquals(1,f.count("download_file_receipts"));assertTrue(f.repository.deletionCleanup("1",8).isEmpty());
            var duplicate=f.repository.create("1",UUID.randomUUID().toString(),"duplicate",task.post(),f.output,List.of(done),DownloadModels.HISTORY_SKIP);
            assertEquals("SKIPPED_HISTORY",duplicate.items().getFirst().state());assertEquals(task.id(),duplicate.items().getFirst().history().taskId());
            assertEquals(1,f.repository.view(duplicate).skipped());assertArrayEquals(new byte[37],Files.readAllBytes(published));
        }
    }
    @Test void changedOrUnauthenticatedTemporaryFilesArePreservedForManualReview()throws Exception{
        for(boolean changedOwner:List.of(false,true))try(var f=new Fixture()){
            var task=f.task(List.of(file(100,1024*1024+7,"file.bin","unique")));writePartial(f,task,task.items().getFirst());task=f.repository.pause("1",task.id());
            Path part=part(f,task.items().getFirst()),marker=owner(f,task.items().getFirst());
            if(changedOwner)Files.writeString(marker,"not-this-task\nunknown");else Files.write(part,new byte[]{9},StandardOpenOption.APPEND);
            f.service.delete(task.id());var cleanup=f.repository.deletionCleanup("1",8).getFirst();
            if(changedOwner)assertThrows(IOException.class,()->f.store.cleanupDeleted(cleanup));else assertFalse(f.store.cleanupDeleted(cleanup));
            assertTrue(Files.exists(part));assertTrue(Files.exists(marker));assertEquals(1,f.repository.deletionCleanup("1",8).size());assertTrue(f.repository.list("1",50,0).isEmpty());
        }
    }
    @Test void orphanFilesWithoutAnAuthenticatedOwnerMarkerAreNeverDeleted()throws Exception{
        try(var f=new Fixture()){
            var task=f.paused();Files.createDirectories(f.output);var orphan=part(f,task.items().getFirst());Files.writeString(orphan,"user-owned or unknown");
            f.service.delete(task.id());assertTrue(f.store.cleanupDeleted(f.repository.deletionCleanup("1",8).getFirst()));assertEquals("user-owned or unknown",Files.readString(orphan));
        }
    }
    @Test void theSerialWorkerCleansDeletedTaskFilesAndReleasesOnlyItsUnsharedOwnedNativeCache()throws Exception{
        try(var f=new Fixture()){
            var task=f.paused();f.repository.own("1","unique",7);f.service.delete(task.id());assertFalse(f.repository.pendingReference("1","unique"));
            when(f.reader.release("1",1,7,"unique",true)).thenReturn(true);
            try(var engine=new DownloadEngine(f.repository,f.reader,f.store,f.telegram,f.paths)){
                await(()->f.repository.deletionCleanup("1",8).isEmpty()&&f.repository.owners("1",8).isEmpty());
                verify(f.reader).release("1",1,7,"unique",true);verify(f.reader,never()).resolve(any(),any(),anyLong());
            }
        }
    }
    @Test void aDeletedTaskNeverCancelsOrDeletesAnOwnedCacheStillReferencedByAnotherTask()throws Exception{
        try(var f=new Fixture()){
            var deleted=f.paused();f.paused();f.repository.own("1","unique",7);f.service.delete(deleted.id());
            assertTrue(f.repository.pendingReference("1","unique"));assertTrue(f.repository.deletedReference("1","unique"));
            var checks=new AtomicInteger();var completedTick=new CountDownLatch(1);
            doAnswer(call->{if(checks.incrementAndGet()>=3)completedTick.countDown();return null;}).when(f.reader).check("1",1);
            try(var engine=new DownloadEngine(f.repository,f.reader,f.store,f.telegram,f.paths)){
                assertTrue(completedTick.await(3,TimeUnit.SECONDS));assertTrue(f.repository.deletionCleanup("1",8).isEmpty());assertEquals(1,f.repository.owners("1",8).size());
                verify(f.reader,never()).release(anyString(),anyLong(),anyInt(),anyString(),anyBoolean());verify(f.reader,never()).resolve(any(),any(),anyLong());
            }
        }
    }
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(20);assertTrue(condition.getAsBoolean());}
    private static Path part(Fixture f,Item item){return f.output.resolve("."+item.id()+".part");}
    private static Path owner(Fixture f,Item item){return f.output.resolve("."+item.id()+".owner");}
    private static void writePartial(Fixture f,Task task,Item item){assertThrows(DownloadFileStore.Stopped.class,()->f.store.write(task,item,(offset,count)->{if(offset>0)throw new DownloadFileStore.Stopped();return new byte[count];},()->{}));}
    private static FrozenFile file(int id,int size,String name,String unique){return new FrozenFile(new Media("1:-1001:"+id+":0",new MessageRef("-1001",Integer.toString(id),""),"MAIN","DOCUMENT",name,size,""),unique,size,name);}
    private final class Fixture implements AutoCloseable{
        final AppPaths paths=new AppPaths(directory.resolve(UUID.randomUUID().toString()));final Database database=new Database(paths);final DownloadRepository repository=new DownloadRepository(database);
        final DownloadFileReader reader=mock(DownloadFileReader.class);final TelegramService telegram=mock(TelegramService.class);final Path output=directory.resolve(UUID.randomUUID().toString()).toAbsolutePath();
        final DownloadFileStore store=new DownloadFileStore(new DownloadPaths(paths),repository);final DownloadTaskService service;String account="1";
        Fixture(){when(telegram.accountId()).thenAnswer(call->account);when(telegram.sessionGeneration()).thenReturn(1L);service=new DownloadTaskService(mock(DownloadSourceService.class),mock(ResourceService.class),reader,repository,new DownloadPaths(paths),telegram,mock(DownloadEngine.class));}
        Task task(List<FrozenFile> files){return repository.create("1",UUID.randomUUID().toString(),UUID.randomUUID().toString(),DownloadPersistenceTest.post("title"),output,files,DownloadModels.KEEP_DUPLICATES);}
        Task paused(){var task=task(List.of(file(100,37,"file.bin","unique")));return repository.pause("1",task.id());}
        long count(String table){return database.read(c->{try(var rows=c.createStatement().executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();return rows.getLong(1);}});}
        public void close(){database.close();}
    }
}
