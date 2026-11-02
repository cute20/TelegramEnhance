package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real isolated files/database with an explicit metadata probe for synthetic, non-video fixture bytes. */
class UploadVideoStagingTest {
    @TempDir Path root;

    @Test void videoMetadataAndJpegArePersistedFromTheLockedCopyWithoutChangingTheOriginal()throws Exception{
        var observed=new AtomicReference<Path>();var probe=new VideoUploadPreparer(){@Override public PreparedVideo prepare(Path video,Runnable check)throws IOException{
            observed.set(video);assertThrows(IOException.class,()->Files.writeString(video,"changed"));assertThrows(IOException.class,()->Files.delete(video));
            return UploadTestFixture.syntheticVideos().prepare(video,check);
        }};
        try(var f=new UploadTestFixture(root,1,"VIDEO",probe)){
            var original=Files.readAllBytes(f.source.resolve("file1.mp4"));var task=f.fresh();var item=task.groups().getFirst().items().getFirst();
            var staged=f.files.stage(task,item,()->{});var saved=f.fresh().groups().getFirst().items().getFirst().staged();
            assertEquals(Path.of(staged.path()),observed.get());assertNotEquals(f.source.resolve("file1.mp4"),observed.get());assertEquals(staged,saved);
            assertEquals(640,staged.width());assertEquals(360,staged.height());assertEquals(2,staged.video().duration());assertTrue(staged.video().supportsStreaming());
            var thumbnail=staged.video().thumbnail();assertEquals(160,thumbnail.width());assertEquals(90,thumbnail.height());assertEquals(Path.of(staged.path()).getParent(),Path.of(thumbnail.path()).getParent());
            assertTrue(thumbnail.size()<=128*1024);assertEquals(thumbnail.size(),Files.size(Path.of(thumbnail.path())));assertEquals(thumbnail.identity(),DownloadFileStore.fileKey(Path.of(thumbnail.path())));
            assertTrue(UploadFiles.matches(Path.of(thumbnail.path()),thumbnail.size(),thumbnail.sha256(),()->{}));f.files.verifyStaged(staged,()->{});
            assertArrayEquals(original,Files.readAllBytes(f.source.resolve("file1.mp4")));assertArrayEquals(original,Files.readAllBytes(Path.of(staged.path())));
            assertEquals(item.file().sha256(),staged.sha256());assertFalse(Files.exists(f.archive));
            assertFalse(Files.exists(Path.of(staged.path()).getParent().resolve("thumbnail.twb-owner")));
        }
    }

    @Test void sendLeaseHoldsBothTheOriginalCopyAndItsRegisteredThumbnail()throws Exception{
        try(var f=new UploadTestFixture(root,1,"VIDEO")){
            var task=f.fresh();var staged=f.files.stage(task,task.groups().getFirst().items().getFirst(),()->{});Path thumbnail=Path.of(staged.video().thumbnail().path());
            try(var lease=f.files.lease(List.of(staged),()->{})){
                assertThrows(IOException.class,()->Files.writeString(thumbnail,"changed"));assertThrows(IOException.class,()->Files.delete(thumbnail));
                assertThrows(IOException.class,()->Files.writeString(Path.of(staged.path()),"changed"));assertThrows(IOException.class,()->Files.delete(Path.of(staged.path())));
            }
            Files.writeString(thumbnail,"changed");assertThrows(IOException.class,()->f.files.verifyStaged(staged,()->{}));assertThrows(IOException.class,()->f.files.lease(List.of(staged),()->{}));
            Files.delete(Path.of(staged.path()));assertEquals("changed",Files.readString(thumbnail),"a failed thumbnail lease must release the already-open video lease");
        }
    }

    @Test void oldStagingWithoutMetadataStillVerifiesAndLeasesButIsUpgradedBeforeNewIntent()throws Exception{
        try(var f=new UploadTestFixture(root,1,"VIDEO")){
            var task=f.fresh();var item=task.groups().getFirst().items().getFirst();Path dir=Files.createDirectories(f.app.root().resolve("outgoing").resolve(task.id()).resolve(item.id()));Path video=Files.copy(f.source.resolve("file1.mp4"),dir.resolve("file1.mp4"));
            var legacy=new Staged(video.toString(),item.file().size(),item.file().sha256(),DownloadFileStore.fileKey(video),0,0);f.repository.staged(task,item,legacy);
            f.files.verifyStaged(legacy,()->{});try(var ignored=f.files.lease(List.of(legacy),()->{})){assertNull(legacy.video());}
            task=f.fresh();var upgraded=f.files.stage(task,task.groups().getFirst().items().getFirst(),()->{});
            assertNotNull(upgraded.video());assertEquals(legacy.identity(),upgraded.identity());assertEquals(legacy.sha256(),upgraded.sha256());assertEquals(legacy.path(),upgraded.path());
            assertEquals(upgraded,f.fresh().groups().getFirst().items().getFirst().staged());
        }
    }

    @Test void failedPreparationCannotRegisterOrSendAPartialStageAndTheOwnedOutputCanBeRecovered()throws Exception{
        try(var f=new UploadTestFixture(root,1,"VIDEO")){
            var task=f.fresh();var item=task.groups().getFirst().items().getFirst();Path thumbnail=f.app.root().resolve("outgoing").resolve(task.id()).resolve(item.id()).resolve("thumbnail.twb.jpg");
            assertThrows(IllegalStateException.class,()->f.files.stage(task,item,()->{if(Files.exists(thumbnail))throw new IllegalStateException("synthetic stop before registration");}));
            assertNull(f.fresh().groups().getFirst().items().getFirst().staged());assertEquals("READY",f.fresh().state());assertTrue(Files.exists(f.source.resolve("file1.mp4")));assertFalse(Files.exists(f.archive));
            var recovered=f.files.stage(f.fresh(),f.fresh().groups().getFirst().items().getFirst(),()->{});assertNotNull(recovered.video());f.files.verifyStaged(recovered,()->{});
            assertFalse(Files.exists(thumbnail.resolveSibling("thumbnail.twb-owner")));assertFalse(Files.exists(thumbnail.resolveSibling("content.twb-owner")));
        }
    }

    @Test void anUnknownThumbnailIsNeverOverwrittenAndInvalidMetadataIsNeverRegistered()throws Exception{
        try(var f=new UploadTestFixture(root,1,"VIDEO")){
            var task=f.fresh();var item=task.groups().getFirst().items().getFirst();Path dir=Files.createDirectories(f.app.root().resolve("outgoing").resolve(task.id()).resolve(item.id()));Path thumbnail=Files.writeString(dir.resolve("thumbnail.twb.jpg"),"unowned-thumbnail");
            assertThrows(IOException.class,()->f.files.stage(task,item,()->{}));assertEquals("unowned-thumbnail",Files.readString(thumbnail));assertNull(f.fresh().groups().getFirst().items().getFirst().staged());
        }
        var invalid=new VideoUploadPreparer(){@Override public PreparedVideo prepare(Path video,Runnable check){return new PreparedVideo(2,640,360,false,new byte[]{1,2,3},160,90);}};
        try(var f=new UploadTestFixture(root.resolve("invalid"),1,"VIDEO",invalid)){
            var task=f.fresh();assertThrows(IOException.class,()->f.files.stage(task,task.groups().getFirst().items().getFirst(),()->{}));assertNull(f.fresh().groups().getFirst().items().getFirst().staged());assertTrue(Files.exists(f.source.resolve("file1.mp4")));
        }
    }

    @Test void missingToolsOrUnusableVideoReportAnActionableErrorWithoutLeakingPathsOrRegisteringAStage()throws Exception{
        var failed=new VideoUploadPreparer(){@Override public PreparedVideo prepare(Path video,Runnable check)throws IOException{throw new IOException("synthetic private location: "+video);}};
        try(var f=new UploadTestFixture(root,1,"VIDEO",failed)){
            var task=f.fresh();var error=assertThrows(IllegalArgumentException.class,()->f.files.stage(task,task.groups().getFirst().items().getFirst(),()->{}));
            assertTrue(error.getMessage().contains("FFmpeg、FFprobe"));assertTrue(error.getMessage().contains("尚未发送"));assertFalse(error.getMessage().contains(root.toString()));
            assertNull(f.fresh().groups().getFirst().items().getFirst().staged());assertTrue(Files.exists(f.source.resolve("file1.mp4")));assertFalse(Files.exists(f.archive));
        }
    }

    @Test void successfulArchiveCleansOnlyTheMatchingThumbnailAndStage()throws Exception{
        try(var f=new UploadTestFixture(root,1,"VIDEO")){
            f.confirm();var task=f.fresh();var item=task.groups().getFirst().items().getFirst();Path video=Path.of(item.staged().path()),thumbnail=Path.of(item.staged().video().thumbnail().path());
            f.files.archive(task,item,()->{});assertEquals("ARCHIVED",f.fresh().groups().getFirst().items().getFirst().state());assertFalse(Files.exists(video));assertFalse(Files.exists(thumbnail));
            assertTrue(Files.exists(f.archive.resolve("file1.mp4")));assertFalse(Files.exists(f.source.resolve("file1.mp4")));
        }
        try(var f=new UploadTestFixture(root.resolve("altered"),1,"VIDEO")){
            f.confirm();var task=f.fresh();var item=task.groups().getFirst().items().getFirst();Path thumbnail=Path.of(item.staged().video().thumbnail().path());Files.writeString(thumbnail,"changed-after-send");
            f.files.archive(task,item,()->{});assertEquals("ARCHIVED",f.fresh().groups().getFirst().items().getFirst().state());assertEquals("changed-after-send",Files.readString(thumbnail));assertFalse(Files.exists(Path.of(item.staged().path())));
        }
    }
}
