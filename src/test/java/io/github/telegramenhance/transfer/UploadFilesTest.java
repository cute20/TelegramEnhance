package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadFilesTest {
    @TempDir Path root;
    @Test void stagingUsesAnOwnedCopyAndCannotArchiveWithoutAFormalReceipt()throws Exception{
        try(var f=new UploadTestFixture(root,1)){var item=f.fresh().groups().getFirst().items().getFirst();var staged=f.files.stage(f.fresh(),item,()->{});assertEquals("synthetic-file-1",Files.readString(Path.of(staged.path())));assertTrue(Files.exists(f.source.resolve("file1.bin")));assertFalse(Files.exists(f.archive));assertThrows(java.io.IOException.class,()->f.files.archive(f.fresh(),item,()->{}));}
    }
    @Test void confirmedFilesArchiveWithoutReplacingAnExistingUserFile()throws Exception{
        try(var f=new UploadTestFixture(root,1)){f.confirm();Files.createDirectories(f.archive);Files.writeString(f.archive.resolve("file1.bin"),"keep-user-file");var task=f.fresh();f.files.archive(task,task.groups().getFirst().items().getFirst(),()->{});var item=f.fresh().groups().getFirst().items().getFirst();assertEquals("ARCHIVED",item.state());assertEquals("keep-user-file",Files.readString(f.archive.resolve("file1.bin")));assertEquals("synthetic-file-1",Files.readString(f.archive.resolve(item.archiveRelative())));assertFalse(Files.exists(f.source.resolve("file1.bin")));}
    }
    @Test void sourceModifiedDuringUploadIsPreservedAndNeverMovedAsTheSentVersion()throws Exception{
        try(var f=new UploadTestFixture(root,1)){f.confirm();Path source=f.source.resolve("file1.bin");Files.writeString(source,"replacement-file");var task=f.fresh();assertThrows(java.io.IOException.class,()->f.files.archive(task,task.groups().getFirst().items().getFirst(),()->{}));assertEquals("replacement-file",Files.readString(source));assertNotEquals("ARCHIVED",f.fresh().groups().getFirst().items().getFirst().state());}
    }
    @Test void aChangedOrReplacedStagedFileCannotBeSent()throws Exception{
        try(var f=new UploadTestFixture(root,1)){var item=f.fresh().groups().getFirst().items().getFirst();var staged=f.files.stage(f.fresh(),item,()->{});Path path=Path.of(staged.path());Files.writeString(path,"changed");assertThrows(java.io.IOException.class,()->f.files.verifyStaged(staged,()->{}));}
    }
    @Test void gifDimensionsAreReadFromTheProtectedCopyWithoutChangingItsBytes()throws Exception{
        try(var f=new UploadTestFixture(root,1,"ANIMATION")){
            var original=Files.readAllBytes(f.source.resolve("file1.gif"));var item=f.fresh().groups().getFirst().items().getFirst();
            var staged=f.files.stage(f.fresh(),item,()->{});assertEquals(2,staged.width());assertEquals(1,staged.height());
            assertArrayEquals(original,Files.readAllBytes(Path.of(staged.path())));assertArrayEquals(original,Files.readAllBytes(f.source.resolve("file1.gif")));
            assertFalse(Files.exists(f.archive));
        }
    }
    @Test void invalidGifBytesAreRejectedBeforeAnIntentOrNativeSend()throws Exception{
        try(var f=new UploadTestFixture(root,1,"ANIMATION")){
            f.repository.cancel("1",f.task.id());var source=f.source.resolve("file1.gif");Files.writeString(source,"not-a-GIF");Files.setLastModifiedTime(source,FileTime.fromMillis(System.currentTimeMillis()-10000));
            var scan=new UploadDirectoryScanner(new DownloadPaths(f.app)).scan(f.source.toString(),f.archive.toString(),true,()->{});
            var member=new UploadPreviewModels.Member("file1.gif","file1.gif","ANIMATION",Files.size(source),"file1.gif");
            var prepared=new UploadPreviewService.Prepared(f.prepared.input(),f.prepared.target(),scan,java.util.List.of(new UploadPreviewModels.Group(1,"ANIMATION","SINGLE",java.util.List.of(member))));
            var task=f.repository.create("1",java.util.UUID.randomUUID().toString(),"invalid-gif",prepared);
            assertThrows(java.io.IOException.class,()->f.files.stage(task,task.groups().getFirst().items().getFirst(),()->{}));
            assertEquals("READY",f.repository.task("1",task.id()).state());assertNull(f.repository.task("1",task.id()).groups().getFirst().items().getFirst().staged());
            assertEquals("not-a-GIF",Files.readString(source));assertFalse(Files.exists(f.archive));
        }
    }
    @Test void stagedFilesCannotBeChangedOrReplacedWhileTheSenderHoldsItsReadLease()throws Exception{
        try(var f=new UploadTestFixture(root,1)){var item=f.fresh().groups().getFirst().items().getFirst();var staged=f.files.stage(f.fresh(),item,()->{});Path path=Path.of(staged.path());try(var lease=f.files.lease(java.util.List.of(staged),()->{})){assertThrows(java.io.IOException.class,()->Files.writeString(path,"changed"));assertThrows(java.io.IOException.class,()->Files.delete(path));}assertEquals("synthetic-file-1",Files.readString(path));}
    }
    @Test void publishedArchiveRecoveryDoesNotSendAgainOrDeleteANewFileAtTheOriginalPath()throws Exception{
        try(var f=new UploadTestFixture(root,1)){f.confirm();var task=f.fresh();var item=task.groups().getFirst().items().getFirst();Files.createDirectories(f.archive);Path target=f.archive.resolve("file1.bin");f.repository.archivePlan(task,item,"file1.bin","PUBLISHING",item.file().identity());Files.move(f.source.resolve("file1.bin"),target);Files.writeString(f.source.resolve("file1.bin"),"new-file-at-same-path");task=f.fresh();f.files.archive(task,task.groups().getFirst().items().getFirst(),()->{});assertEquals("ARCHIVED",f.fresh().groups().getFirst().items().getFirst().state());assertEquals("new-file-at-same-path",Files.readString(f.source.resolve("file1.bin")));}
    }
    @Test void realCrossVolumeCopyPublishesVerifiedBytesAndRemovesOnlyTheConfirmedSource()throws Exception{
        Path workspace=Path.of("target").toRealPath();
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.getFileStore(root).equals(Files.getFileStore(workspace)),"Cross-volume verification requires temp and project on different volumes");
        Path archive=Files.createTempDirectory(workspace,"upload-archive-fs-");
        try(var f=new UploadTestFixture(root,1,archive)){assertNotEquals(Files.getFileStore(f.source),Files.getFileStore(archive));f.confirm();var task=f.fresh();f.files.archive(task,task.groups().getFirst().items().getFirst(),()->{});assertEquals("ARCHIVED",f.fresh().groups().getFirst().items().getFirst().state());assertEquals("synthetic-file-1",Files.readString(archive.resolve("file1.bin")));assertFalse(Files.exists(f.source.resolve("file1.bin")));try(var entries=Files.list(archive)){assertEquals(1,entries.count());}}
        finally{assertEquals(workspace,archive.toRealPath().getParent());Files.deleteIfExists(archive.resolve("file1.bin"));Files.delete(archive);}
    }
}
