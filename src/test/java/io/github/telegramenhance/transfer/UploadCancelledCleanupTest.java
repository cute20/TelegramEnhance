package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadCancelledCleanupTest {
    @TempDir Path root;

    @Test void cancelledPersistedCopiesAreCollectedWithoutTouchingSourceOrTheReplacementTask()throws Exception{
        try(var f=new UploadTestFixture(root,2)){
            var task=stageAndPause(f);var old=task.groups().getFirst().items().stream().map(i->Path.of(i.staged().path())).toList();
            var cancelled=f.repository.cancel("1",task.id());assertEquals(cancelled,f.repository.cancel("1",task.id()));
            var replacement=f.repository.create("1",UUID.randomUUID().toString(),"replacement",f.prepared);
            var next=f.files.stage(replacement,replacement.groups().getFirst().items().getFirst(),()->{});
            f.files.cleanupCancelled(cancelled,()->{});
            assertTrue(old.stream().noneMatch(Files::exists));assertTrue(Files.exists(Path.of(next.path())));
            for(int n=1;n<=2;n++)assertEquals("synthetic-file-"+n,Files.readString(f.source.resolve("file"+n+".bin")));
            assertFalse(Files.exists(f.archive));assertEquals("CANCELLED",f.fresh().state());
        }
    }

    @Test void videoPreparationFailureBeforeStageRegistrationStillReleasesItsOwnedMainCopy()throws Exception{
        var unavailable=new VideoUploadPreparer(){@Override public PreparedVideo prepare(Path file,Runnable check)throws IOException{throw new IOException("missing tools");}};
        try(var f=new UploadTestFixture(root,1,"VIDEO",unavailable)){
            var task=f.fresh();var item=task.groups().getFirst().items().getFirst();
            assertThrows(IllegalArgumentException.class,()->f.files.stage(task,item,()->{}));assertNull(f.fresh().groups().getFirst().items().getFirst().staged());
            Path dir=cache(f,task,item);assertTrue(Files.exists(dir.resolve("file1.mp4")));assertTrue(Files.exists(dir.resolve("content.twb-owner")));
            f.files.cleanupCancelled(f.repository.cancel("1",task.id()),()->{});
            assertFalse(Files.exists(dir));assertEquals("synthetic-file-1",Files.readString(f.source.resolve("file1.mp4")));
        }
    }

    @Test void anUnregisteredVideoThumbnailAndMainAreCollectedFromTheirContentCheckpoints()throws Exception{
        try(var f=new UploadTestFixture(root,1,"VIDEO")){
            var task=f.fresh();var item=task.groups().getFirst().items().getFirst();Path dir=cache(f,task,item);
            assertThrows(IllegalStateException.class,()->f.files.stage(task,item,()->{if(Files.exists(dir.resolve("thumbnail.twb.jpg")))throw new IllegalStateException("stop before registration");}));
            assertNull(f.fresh().groups().getFirst().items().getFirst().staged());assertTrue(Files.exists(dir.resolve("thumbnail.twb.jpg")));
            f.files.cleanupCancelled(f.repository.cancel("1",task.id()),()->{});
            assertFalse(Files.exists(dir));assertTrue(Files.exists(f.source.resolve("file1.mp4")));
        }
    }

    @Test void interruptedCopiesHaveAContentCheckpointAndCanBeCollectedAfterCancellation()throws Exception{
        try(var f=new UploadTestFixture(root,1)){
            f.repository.cancel("1",f.task.id());Path source=f.source.resolve("file1.bin");Files.write(source,new byte[1024*1024+7]);Files.setLastModifiedTime(source,FileTime.fromMillis(System.currentTimeMillis()-10000));
            var scan=new UploadDirectoryScanner(new DownloadPaths(f.app)).scan(f.source.toString(),f.archive.toString(),true,()->{});
            var prepared=new UploadPreviewService.Prepared(f.prepared.input(),f.prepared.target(),scan,UploadPreviewService.groups(scan.files(),"FILENAME"));
            var task=f.repository.create("1",UUID.randomUUID().toString(),"large-fixture",prepared);var item=task.groups().getFirst().items().getFirst();Path dir=cache(f,task,item),part=dir.resolve("content.twb-part");
            assertThrows(IllegalStateException.class,()->f.files.stage(task,item,()->{try{if(Files.exists(part)&&Files.size(part)>0)throw new IllegalStateException("stop copying");}catch(IOException failure){throw new RuntimeException(failure);}}));
            assertEquals(1024*1024,Files.size(part));assertEquals(4,Files.readString(dir.resolve("content.twb-owner")).split("\n",-1).length);
            f.files.cleanupCancelled(f.repository.cancel("1",task.id()),()->{});
            assertFalse(Files.exists(dir));assertEquals(1024*1024+7,Files.size(source));
        }
    }

    @Test void aPartialWriteFollowedByAnIoFailureRecordsTheActualPrefixForSafeCancellation()throws Exception{
        try(var f=new UploadTestFixture(root,1)){
            var files=new UploadFiles(f.app,new DownloadPaths(f.app),f.repository,UploadTestFixture.syntheticVideos(),(output,bytes)->{
                int limit=bytes.limit();bytes.limit(bytes.position()+7);output.write(bytes);bytes.limit(limit);throw new IOException("synthetic disk full after partial write");
            });
            var task=f.fresh();var item=task.groups().getFirst().items().getFirst();Path dir=cache(f,task,item),part=dir.resolve("content.twb-part");
            assertThrows(IOException.class,()->files.stage(task,item,()->{}));assertEquals(7,Files.size(part));
            String[] marker=Files.readString(dir.resolve("content.twb-owner")).split("\n",-1);assertEquals("7",marker[2]);
            assertTrue(UploadFiles.matches(part,7,marker[3],()->{}));
            files.cleanupCancelled(f.repository.cancel("1",task.id()),()->{});assertFalse(Files.exists(dir));
            assertEquals("synthetic-file-1",Files.readString(f.source.resolve("file1.bin")));
        }
    }

    @Test void losingTheWriteLeaseCannotJournalAnExternalRewriteAsProgramOwnedContent()throws Exception{
        try(var f=new UploadTestFixture(root,1)){
            var task=f.fresh();var item=task.groups().getFirst().items().getFirst();Path dir=cache(f,task,item),part=dir.resolve("content.twb-part");
            var files=new UploadFiles(f.app,new DownloadPaths(f.app),f.repository,UploadTestFixture.syntheticVideos(),(output,bytes)->{
                output.close();Files.writeString(part,"external rewrite after channel closed");throw new IOException("write channel lost");
            });
            assertThrows(IOException.class,()->files.stage(task,item,()->{}));
            files.cleanupCancelled(f.repository.cancel("1",task.id()),()->{});
            assertEquals("external rewrite after channel closed",Files.readString(part));assertTrue(Files.exists(f.source.resolve("file1.bin")));
        }
    }

    @Test void changedReplacedAndUnownedContentsArePreservedAndSourcePathsCannotBeBorrowed()throws Exception{
        for(String mode:List.of("changed","replaced","unknown","source-path"))try(var f=new UploadTestFixture(root.resolve(mode),1)){
            var task=stageAndPause(f);var item=task.groups().getFirst().items().getFirst();Path output=Path.of(item.staged().path()),dir=output.getParent();
            if(mode.equals("changed"))Files.writeString(output,"synthetic-file-X");
            if(mode.equals("replaced")){Files.move(output,dir.resolve("keep-original"));Files.writeString(output,"synthetic-file-1");}
            if(mode.equals("unknown"))Files.writeString(dir.resolve("unknown.bin"),"keep user file");
            if(mode.equals("source-path")){
                Path source=f.source.resolve("file1.bin");f.repository.staged(task,item,new Staged(source.toString(),item.file().size(),item.file().sha256(),DownloadFileStore.fileKey(source),0,0));
            }
            f.files.cleanupCancelled(f.repository.cancel("1",task.id()),()->{});
            assertEquals("synthetic-file-1",Files.readString(f.source.resolve("file1.bin")));
            if(mode.equals("unknown")){assertFalse(Files.exists(output));assertEquals("keep user file",Files.readString(dir.resolve("unknown.bin")));}
            else assertTrue(Files.exists(output),mode);
        }
    }

    @Test void changedUnregisteredThumbnailAndLegacyThumbnailWithoutADigestAreNeverDeleted()throws Exception{
        for(boolean legacy:List.of(false,true))try(var f=new UploadTestFixture(root.resolve("thumbnail-"+legacy),1,"VIDEO")){
            var task=f.fresh();var item=task.groups().getFirst().items().getFirst();Path dir=cache(f,task,item),thumbnail=dir.resolve("thumbnail.twb.jpg");
            assertThrows(IllegalStateException.class,()->f.files.stage(task,item,()->{if(Files.exists(thumbnail))throw new IllegalStateException("stop");}));
            if(legacy)Files.writeString(dir.resolve("thumbnail.twb-owner"),item.id()+"\n"+DownloadFileStore.fileKey(thumbnail));
            else Files.writeString(thumbnail,"changed thumbnail");
            f.files.cleanupCancelled(f.repository.cancel("1",task.id()),()->{});
            assertTrue(Files.exists(thumbnail));assertFalse(Files.exists(dir.resolve("file1.mp4")));assertTrue(Files.exists(f.source.resolve("file1.mp4")));
        }
    }

    @Test void legacyMainMarkerCanBeCollectedOnlyWhenItMatchesTheFrozenFile()throws Exception{
        var unavailable=new VideoUploadPreparer(){@Override public PreparedVideo prepare(Path file,Runnable check)throws IOException{throw new IOException("tools unavailable");}};
        try(var f=new UploadTestFixture(root,1,"VIDEO",unavailable)){
            var task=f.fresh();var item=task.groups().getFirst().items().getFirst();Path dir=cache(f,task,item);
            assertThrows(IllegalArgumentException.class,()->f.files.stage(task,item,()->{}));
            Files.writeString(dir.resolve("content.twb-owner"),item.id()+"\n"+DownloadFileStore.fileKey(dir.resolve("file1.mp4")));
            f.files.cleanupCancelled(f.repository.cancel("1",task.id()),()->{});assertFalse(Files.exists(dir));
        }
    }

    @Test void sendIntentAndEvenAForgedCancelledStateCannotAuthorizeCleanup()throws Exception{
        try(var f=new UploadTestFixture(root,1)){
            f.prepareAndIntent();var task=f.fresh();Path output=Path.of(task.groups().getFirst().items().getFirst().staged().path());
            assertThrows(IllegalArgumentException.class,()->f.repository.cancel("1",task.id()));f.files.cleanupCancelled(task,()->{});assertTrue(Files.exists(output));
            f.database.write(c->{try(var q=c.prepareStatement("UPDATE upload_tasks SET state='CANCELLED' WHERE id=?")){q.setString(1,task.id());q.executeUpdate();}return null;});
            f.files.cleanupCancelled(f.fresh(),()->{});assertTrue(Files.exists(output));assertTrue(Files.exists(f.source.resolve("file1.bin")));
        }
    }

    @Test void startupCollectsCancelledFilesWithoutAnAuthorizedTelegramAccount()throws Exception{
        try(var f=new UploadTestFixture(root,1)){
            var task=stageAndPause(f);Path output=Path.of(task.groups().getFirst().items().getFirst().staged().path());f.repository.cancel("1",task.id());
            var restarted=new UploadRepository(f.database);var telegram=mock(TelegramService.class);when(telegram.accountId()).thenThrow(new IllegalArgumentException("offline"));
            var transport=mock(UploadTransport.class);var targets=mock(UploadTargetReader.class);var reads=mock(ResourceService.class);
            try(var engine=new UploadEngine(restarted,f.files,transport,telegram,targets,reads)){
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(Files.exists(output)&&System.nanoTime()<deadline)Thread.sleep(20);
                assertFalse(Files.exists(output));assertTrue(Files.exists(f.source.resolve("file1.bin")));verify(transport,never()).send(any(),any(),anyLong());verifyNoInteractions(targets,reads);
            }
        }
    }

    private static Task stageAndPause(UploadTestFixture f)throws Exception{
        var task=f.repository.start("1",f.task.id());task=f.repository.next("1");var group=task.groups().getFirst();f.repository.preparing(task,group);
        for(var item:group.items())f.files.stage(task,item,()->{});f.repository.unprepared(group);f.repository.state(task,"PAUSED","preflight stopped before intent");return f.fresh();
    }
    private static Path cache(UploadTestFixture f,Task task,Item item){return f.app.root().resolve("outgoing").resolve(task.id()).resolve(item.id());}
}
