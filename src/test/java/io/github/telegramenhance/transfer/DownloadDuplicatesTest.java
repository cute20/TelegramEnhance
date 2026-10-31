package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DownloadDuplicatesTest {
    @TempDir Path root;
    private final byte[] content="same verified media bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @Test void repeatedSourceIsActuallyReadAndSavedUnderEachChosenRootWithDatabaseEvidenceOnly()throws Exception{
        try(var f=new Fixture()){
            var first=f.task("1","100","file-a",root.resolve("first"));f.save(first,content);
            Files.move(f.output(first),root.resolve("moved-first.bin"));
            var second=f.task("1","100","file-a",root.resolve("second"));assertEquals("QUEUED",second.state());
            var reads=new AtomicInteger();f.store.write(second,second.items().getFirst(),(offset,count)->{reads.incrementAndGet();return Arrays.copyOfRange(content,(int)offset,(int)offset+count);},()->{});
            var item=f.fresh(second).items().getFirst();assertTrue(reads.get()>0);assertEquals("COMPLETED",item.state());assertEquals("SOURCE_MESSAGE",item.plan().duplicate().reason());
            assertEquals(root.resolve("second/重复媒体"),f.output(second).getParent());assertArrayEquals(content,Files.readAllBytes(f.output(second)));
            var view=f.repo.view(f.fresh(second));assertEquals(1,view.completed());assertEquals(0,view.skipped());assertEquals(1,view.duplicates());
            assertNull(item.plan().manifestRelative());assertNull(view.items().getFirst().manifestPath());
            try(var entries=Files.list(f.output(second).getParent())){assertEquals(List.of(f.output(second)),entries.toList());}
            assertEquals(2,f.logCount());assertEquals("https://t.me/synthetic/100",f.log(second,"source_link"));assertEquals("https://t.me/synthetic/1",f.log(second,"post_link"));
            assertEquals(Long.toString(content.length),f.log(second,"size"));assertEquals(DownloadPaths.hash(new String(content,java.nio.charset.StandardCharsets.UTF_8)),f.log(second,"sha256"));
            assertEquals(first.items().getFirst().id(),f.log(second,"duplicate_of"));assertNull(f.log(second,"manifest_path"));assertEquals(f.output(second).toString(),f.log(second,"output_path"));
        }
    }
    @Test void anotherFileIdAndAnotherMessageWithIdenticalBytesUsesSizeAndShaFallback()throws Exception{
        try(var f=new Fixture()){
            var first=f.task("1","100","file-a",root.resolve("out"));f.save(first,content);
            var second=f.task("1","101","file-b",root.resolve("out"));f.save(second,content);
            assertEquals("CONTENT_SHA",f.fresh(second).items().getFirst().plan().duplicate().reason());
            assertEquals(first.items().getFirst().id(),f.log(second,"duplicate_of"));assertEquals("https://t.me/synthetic/101",f.log(second,"source_link"));
        }
    }
    @Test void matchingFileIdentityAcrossOtherMessagesStillRequiresTheFreshHash()throws Exception{
        try(var f=new Fixture()){
            var first=f.task("1","100","file-a",root.resolve("out"));f.save(first,content);
            var second=f.task("1","101","file-a",root.resolve("out"));f.save(second,content);
            assertEquals("FILE_ID",f.fresh(second).items().getFirst().plan().duplicate().reason());
        }
    }
    @Test void sameLinkAndSizeWithDifferentBytesDoesNotBecomeADuplicate()throws Exception{
        try(var f=new Fixture()){
            var first=f.task("1","100","file-a",root.resolve("out"));f.save(first,content);
            byte[] changed=content.clone();changed[0]++;
            var second=f.task("1","100","file-b",root.resolve("out"));f.save(second,changed);
            assertNull(f.fresh(second).items().getFirst().plan().duplicate());assertNotEquals("重复媒体",f.output(second).getParent().getFileName().toString());
            var third=f.task("1","100","file-a",root.resolve("out"));f.save(third,changed);assertEquals("CONTENT_SHA",f.fresh(third).items().getFirst().plan().duplicate().reason());
        }
    }
    @Test void identicalContentInAnotherAccountDoesNotShareDuplicateEvidence()throws Exception{
        try(var f=new Fixture()){
            f.save(f.task("1","100","file-a",root.resolve("out")),content);
            var other=f.task("2","100","file-a",root.resolve("out"));f.save(other,content);
            assertNull(f.fresh(other).items().getFirst().plan().duplicate());assertEquals("NEW",f.log(other,"reason"));
        }
    }
    @Test void repeatedDuplicateDownloadsNeverOverwriteEarlierCopiesAndDoNotCreateSidecars()throws Exception{
        try(var f=new Fixture()){
            f.save(f.task("1","100","file-a",root.resolve("out")),content);
            var second=f.task("1","100","file-a",root.resolve("out"));f.save(second,content);var existing=Files.readAllBytes(f.output(second));
            var third=f.task("1","100","file-a",root.resolve("out"));f.save(third,content);
            assertNotEquals(f.output(second),f.output(third));assertArrayEquals(existing,Files.readAllBytes(f.output(second)));assertArrayEquals(content,Files.readAllBytes(f.output(third)));
            assertNull(f.repo.view(f.fresh(second)).items().getFirst().manifestPath());assertNull(f.repo.view(f.fresh(third)).items().getFirst().manifestPath());assertEquals(3,f.logCount());
            try(var entries=Files.list(f.output(second).getParent())){var paths=entries.toList();assertEquals(2,paths.size());assertTrue(paths.containsAll(List.of(f.output(second),f.output(third))));}
        }
    }
    @Test void restartBeforePublishResumesTheFullTemporaryFileWithoutReadingNativeAgain()throws Exception{
        try(var f=new Fixture()){
            f.save(f.task("1","100","file-a",root.resolve("out")),content);var second=f.task("1","101","file-b",root.resolve("out"));
            assertThrows(DownloadFileStore.Stopped.class,()->f.store.write(second,second.items().getFirst(),(offset,count)->Arrays.copyOfRange(content,(int)offset,(int)offset+count),()->{
                var item=f.fresh(second).items().getFirst();if(item.state().equals("FINALIZING"))throw new DownloadFileStore.Stopped();
            }));
            var before=f.fresh(second).items().getFirst();assertNotNull(before.plan());assertFalse(Files.exists(f.output(second)));new DownloadRepository(f.db);
            var restored=f.fresh(second);f.store.write(restored,restored.items().getFirst(),(offset,count)->{throw new AssertionError("Unexpected native reread");},()->{});
            assertEquals(before.plan().sha256(),f.fresh(second).items().getFirst().plan().sha256());assertArrayEquals(content,Files.readAllBytes(f.output(second)));assertEquals(2,f.logCount());
        }
    }
    @Test void restartAfterPublishFinishesTheLogWithoutAnotherCopyOrSidecar()throws Exception{
        try(var f=new Fixture()){
            f.save(f.task("1","100","file-a",root.resolve("out")),content);var second=f.task("1","101","file-b",root.resolve("out"));
            var interrupted=spy(f.repo);doThrow(new IllegalStateException("injected after media publish")).when(interrupted).complete(anyString(),anyString(),anyString(),anyString(),any(),anyString());
            var store=new DownloadFileStore(new DownloadPaths(f.app),interrupted);
            assertThrows(IllegalStateException.class,()->store.write(second,second.items().getFirst(),(offset,count)->Arrays.copyOfRange(content,(int)offset,(int)offset+count),()->{}));
            assertEquals(1,f.logCount());assertArrayEquals(content,Files.readAllBytes(f.output(second)));new DownloadRepository(f.db);var restored=f.fresh(second);
            assertTrue(f.store.recoverPublished(restored,restored.items().getFirst(),()->{}));assertEquals("COMPLETED",f.fresh(second).items().getFirst().state());assertEquals(2,f.logCount());
            assertNull(f.repo.view(f.fresh(second)).items().getFirst().manifestPath());
            try(var entries=Files.list(f.output(second).getParent())){assertEquals(List.of(f.output(second)),entries.toList());}
        }
    }
    @Test void aConflictingSourceRecordForAnOldFrozenManifestPlanIsPreservedAndNotCountedComplete()throws Exception{
        try(var f=new Fixture()){
            f.save(f.task("1","100","file-a",root.resolve("out")),content);var second=f.task("1","101","file-b",root.resolve("out"));
            second=f.legacyFinalizing(second);var frozen=second;var manifest=Path.of(second.targetRoot()).resolve(second.items().getFirst().plan().manifestRelative());
            Files.writeString(manifest,"user-owned",StandardOpenOption.CREATE_NEW);
            assertThrows(IOException.class,()->f.store.write(frozen,frozen.items().getFirst(),(offset,count)->{throw new AssertionError("Unexpected native reread");},()->{}));
            var item=f.fresh(second).items().getFirst();assertEquals("FINALIZING",item.state());assertEquals(1,f.logCount());assertEquals("user-owned",Files.readString(Path.of(second.targetRoot()).resolve(item.plan().manifestRelative())));
        }
    }
    @Test void existingOperationsKeepTheirFrozenLegacyPolicy()throws Exception{
        try(var f=new Fixture()){
            var file=f.file("1","100","file-a");String operation=UUID.randomUUID().toString();
            var old=f.repo.create("1",operation,"fixed",f.post("1"),root.resolve("out"),List.of(file),DownloadModels.HISTORY_SKIP);
            var retry=f.repo.create("1",operation,"fixed",f.post("1"),root.resolve("out"),List.of(file));
            assertEquals(old, retry);assertEquals(DownloadModels.HISTORY_SKIP,retry.duplicatePolicy());
        }
    }
    @Test void fillingAnOldSourceLinkCannotChangeAnAlreadyPublishedManifestOnRecovery()throws Exception{
        try(var f=new Fixture()){
            var first=f.task("1","100","file-a",root.resolve("out"));f.save(first,content);
            f.db.write(c->{try(var s=c.createStatement()){s.execute("UPDATE download_completion_log SET source_link=NULL");}return null;});
            var second=f.legacyFinalizing(f.task("1","101","file-b",root.resolve("out")));var interrupted=spy(f.repo);
            doThrow(new IllegalStateException("injected after manifest publish")).when(interrupted).complete(anyString(),anyString(),anyString(),anyString(),any(),anyString());
            var store=new DownloadFileStore(new DownloadPaths(f.app),interrupted);
            assertThrows(IllegalStateException.class,()->store.write(second,second.items().getFirst(),(offset,count)->{throw new AssertionError("Unexpected native reread");},()->{}));
            var manifest=Path.of(f.repo.view(f.fresh(second)).items().getFirst().manifestPath());var before=Files.readAllBytes(manifest);
            assertEquals(1,f.repo.fillSourceLink("1",first.items().getFirst().file().media().message(),"https://t.me/synthetic/100"));
            var saved=f.fresh(second);assertTrue(f.store.recoverPublished(saved,saved.items().getFirst(),()->{}));assertArrayEquals(before,Files.readAllBytes(manifest));assertEquals(2,f.logCount());
        }
    }
    @Test void databaseReopenKeepsAnOldNonNullManifestPlanAndRecoversItsPublishedMediaAndEvidence()throws Exception{
        String id,relativeManifest;AppPaths paths;Path published;
        try(var f=new Fixture()){
            paths=f.app;f.save(f.task("1","100","file-a",root.resolve("out")),content);
            var task=f.legacyFinalizing(f.task("1","101","file-b",root.resolve("out")));id=task.id();relativeManifest=task.items().getFirst().plan().manifestRelative();
            published=f.output(task);Files.move(f.store.directory(task).resolve("."+task.items().getFirst().id()+".part"),published);
            assertEquals("FINALIZING",task.items().getFirst().state());assertEquals(1,f.logCount());assertFalse(Files.exists(Path.of(task.targetRoot()).resolve(relativeManifest)));
        }
        try(var db=new Database(paths)){
            var repository=new DownloadRepository(db);var store=new DownloadFileStore(new DownloadPaths(paths),repository);var restored=repository.task("1",id);
            assertEquals(relativeManifest,restored.items().getFirst().plan().manifestRelative());assertEquals("FINALIZING",restored.items().getFirst().state());
            assertTrue(store.recoverPublished(restored,restored.items().getFirst(),()->{}));
            var done=repository.task("1",id);assertEquals("COMPLETED",done.items().getFirst().state());assertArrayEquals(content,Files.readAllBytes(published));
            var manifest=Path.of(repository.view(done).items().getFirst().manifestPath());assertTrue(Files.exists(manifest));
            var evidence=JsonMapper.builder().build().readTree(Files.readString(manifest));assertEquals(published.toString(),evidence.get("savedFile").asText());assertEquals("https://t.me/synthetic/101",evidence.get("sourceLink").asText());
            int logs=db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM download_completion_log")){r.next();return r.getInt(1);}});assertEquals(2,logs);
        }
    }
    private final class Fixture implements AutoCloseable{
        final AppPaths app=new AppPaths(root.resolve("app"));final Database db=new Database(app);final DownloadRepository repo=new DownloadRepository(db);final DownloadFileStore store=new DownloadFileStore(new DownloadPaths(app),repo);
        Post post(String account){return new Post(account+":-1001:1",account,"-1001","channel","1","title","body","0",List.of(new MessageRef("-1001","1","https://t.me/synthetic/1")),List.of(),null,null,List.of(),false,"UNAVAILABLE",List.of());}
        FrozenFile file(String account,String message,String uid){return new FrozenFile(new Media(account+":-1001:"+message+":0",new MessageRef("-1001",message,"https://t.me/synthetic/"+message),"MAIN","DOCUMENT","sample.bin",content.length,""),uid,content.length,"sample.bin");}
        Task task(String account,String message,String uid,Path output){return repo.create(account,UUID.randomUUID().toString(),UUID.randomUUID().toString(),post(account),output,List.of(file(account,message,uid)));}
        Task fresh(Task task){return repo.task(task.accountId(),task.id());}
        Path output(Task task){return Path.of(repo.view(fresh(task)).items().getFirst().outputPath());}
        void save(Task task,byte[] data)throws IOException{store.write(task,task.items().getFirst(),(offset,count)->Arrays.copyOfRange(data,(int)offset,(int)offset+count),()->{});}
        Task legacyFinalizing(Task task)throws IOException{
            assertThrows(DownloadFileStore.Stopped.class,()->store.write(task,task.items().getFirst(),(offset,count)->Arrays.copyOfRange(content,(int)offset,(int)offset+count),()->{if("FINALIZING".equals(fresh(task).items().getFirst().state()))throw new DownloadFileStore.Stopped();}));
            var item=fresh(task).items().getFirst();String manifest=Path.of("重复媒体","来源-legacy-"+item.id()+".json").toString();
            db.write(c->{try(var q=c.prepareStatement("UPDATE download_output_plans SET manifest_relative=? WHERE item_id=?")){q.setString(1,manifest);q.setString(2,item.id());assertEquals(1,q.executeUpdate());}return null;});
            return fresh(task);
        }
        int logCount(){return db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM download_completion_log")){r.next();return r.getInt(1);}});}
        String log(Task task,String column){return db.read(c->{try(var q=c.prepareStatement("SELECT "+column+" FROM download_completion_log WHERE item_id=?")){q.setString(1,task.items().getFirst().id());try(var r=q.executeQuery()){assertTrue(r.next());return r.getString(1);}}});}
        public void close(){db.close();}
    }
}
