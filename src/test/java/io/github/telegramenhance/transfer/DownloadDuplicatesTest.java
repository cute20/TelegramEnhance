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
    @Test void repeatedSourceIsActuallyReadAndSavedUnderEachChosenRootWithItsSourceRecord()throws Exception{
        try(var f=new Fixture()){
            var first=f.task("1","100","file-a",root.resolve("first"));f.save(first,content);
            Files.move(f.output(first),root.resolve("moved-first.bin"));
            var second=f.task("1","100","file-a",root.resolve("second"));assertEquals("QUEUED",second.state());
            var reads=new AtomicInteger();f.store.write(second,second.items().getFirst(),(offset,count)->{reads.incrementAndGet();return Arrays.copyOfRange(content,(int)offset,(int)offset+count);},()->{});
            var item=f.fresh(second).items().getFirst();assertTrue(reads.get()>0);assertEquals("COMPLETED",item.state());assertEquals("SOURCE_MESSAGE",item.plan().duplicate().reason());
            assertEquals(root.resolve("second/重复媒体"),f.output(second).getParent());assertArrayEquals(content,Files.readAllBytes(f.output(second)));
            var view=f.repo.view(f.fresh(second));assertEquals(1,view.completed());assertEquals(0,view.skipped());assertEquals(1,view.duplicates());
            var record=JsonMapper.builder().build().readTree(Files.readString(Path.of(view.items().getFirst().manifestPath())));
            assertEquals("https://t.me/synthetic/100",record.get("sourceLink").asText());assertEquals(f.output(second).toString(),record.get("savedFile").asText());
            assertEquals(2,f.logCount());assertEquals("https://t.me/synthetic/100",f.log(second,"source_link"));assertEquals("https://t.me/synthetic/1",f.log(second,"post_link"));
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
    @Test void repeatedDuplicateDownloadsNeverOverwriteEarlierCopiesOrManifests()throws Exception{
        try(var f=new Fixture()){
            f.save(f.task("1","100","file-a",root.resolve("out")),content);
            var second=f.task("1","100","file-a",root.resolve("out"));f.save(second,content);var existing=Files.readAllBytes(f.output(second));
            var third=f.task("1","100","file-a",root.resolve("out"));f.save(third,content);
            assertNotEquals(f.output(second),f.output(third));assertArrayEquals(existing,Files.readAllBytes(f.output(second)));assertArrayEquals(content,Files.readAllBytes(f.output(third)));
            assertNotEquals(f.repo.view(f.fresh(second)).items().getFirst().manifestPath(),f.repo.view(f.fresh(third)).items().getFirst().manifestPath());assertEquals(3,f.logCount());
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
    @Test void restartAfterPublishFinishesTheManifestAndLogWithoutAnotherCopy()throws Exception{
        try(var f=new Fixture()){
            f.save(f.task("1","100","file-a",root.resolve("out")),content);var second=f.task("1","101","file-b",root.resolve("out"));
            assertThrows(DownloadFileStore.Stopped.class,()->f.store.write(second,second.items().getFirst(),(offset,count)->Arrays.copyOfRange(content,(int)offset,(int)offset+count),()->{
                var item=f.fresh(second).items().getFirst();if(item.plan()!=null&&Files.exists(f.output(second)))throw new DownloadFileStore.Stopped();
            }));
            assertEquals(1,f.logCount());assertArrayEquals(content,Files.readAllBytes(f.output(second)));new DownloadRepository(f.db);var restored=f.fresh(second);
            assertTrue(f.store.recoverPublished(restored,restored.items().getFirst(),()->{}));assertEquals("COMPLETED",f.fresh(second).items().getFirst().state());assertEquals(2,f.logCount());
            assertTrue(Files.exists(Path.of(f.repo.view(f.fresh(second)).items().getFirst().manifestPath())));
        }
    }
    @Test void conflictingUserSourceRecordIsPreservedAndDoesNotCountAsComplete()throws Exception{
        try(var f=new Fixture()){
            f.save(f.task("1","100","file-a",root.resolve("out")),content);var second=f.task("1","101","file-b",root.resolve("out"));
            assertThrows(IOException.class,()->f.store.write(second,second.items().getFirst(),(offset,count)->Arrays.copyOfRange(content,(int)offset,(int)offset+count),()->{
                var item=f.fresh(second).items().getFirst();if(item.plan()!=null){var p=Path.of(second.targetRoot()).resolve(item.plan().manifestRelative());if(!Files.exists(p))try{Files.writeString(p,"user-owned",StandardOpenOption.CREATE_NEW);}catch(IOException e){throw new RuntimeException(e);}}
            }));
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
            var second=f.task("1","101","file-b",root.resolve("out"));var interrupted=spy(f.repo);
            doThrow(new IllegalStateException("injected after manifest publish")).when(interrupted).complete(anyString(),anyString(),anyString(),anyString(),any(),anyString());
            var store=new DownloadFileStore(new DownloadPaths(f.app),interrupted);
            assertThrows(IllegalStateException.class,()->store.write(second,second.items().getFirst(),(offset,count)->Arrays.copyOfRange(content,(int)offset,(int)offset+count),()->{}));
            var manifest=Path.of(f.repo.view(f.fresh(second)).items().getFirst().manifestPath());var before=Files.readAllBytes(manifest);
            assertEquals(1,f.repo.fillSourceLink("1",first.items().getFirst().file().media().message(),"https://t.me/synthetic/100"));
            var saved=f.fresh(second);assertTrue(f.store.recoverPublished(saved,saved.items().getFirst(),()->{}));assertArrayEquals(before,Files.readAllBytes(manifest));assertEquals(2,f.logCount());
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
        int logCount(){return db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM download_completion_log")){r.next();return r.getInt(1);}});}
        String log(Task task,String column){return db.read(c->{try(var q=c.prepareStatement("SELECT "+column+" FROM download_completion_log WHERE item_id=?")){q.setString(1,task.items().getFirst().id());try(var r=q.executeQuery()){assertTrue(r.next());return r.getString(1);}}});}
        public void close(){db.close();}
    }
}
