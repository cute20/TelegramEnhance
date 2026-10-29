package io.github.telegramenhance.transfer;
import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.DownloadBatchModels.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class DownloadBatchRepositoryTest{
    @TempDir Path directory;
    Snapshot snapshot(Path root,int count){return new Snapshot(UUID.randomUUID().toString(),3,"channel",List.of(),List.of(),List.of("DOCUMENT"),true,root.toString(),2,count,count*37L);}
    DownloadModels.FrozenFile file(int id){var media=DownloadBatchServiceTest.media(id,true);return new DownloadModels.FrozenFile(media,"uid-"+id,37,media.fileName());}
    @Test void atomicBatchFailureRollsBackEveryChildBindingAndReceipt(){try(var db=new Database(new AppPaths(directory.resolve("data")))){
        var repository=new DownloadRepository(db);var root=directory.resolve("out").toAbsolutePath();var first=DownloadBatchServiceTest.post(100);var second=DownloadBatchServiceTest.post(90);
        db.write(c->{c.createStatement().execute("CREATE TRIGGER stop_second BEFORE INSERT ON download_tasks WHEN NEW.post_key='1:-1001:90' BEGIN SELECT RAISE(ABORT,'test failure'); END");return null;});
        assertThrows(IllegalStateException.class,()->repository.createBatch("1",UUID.randomUUID().toString(),"fp",snapshot(root,2),root,List.of(new Job(first,List.of(file(100))),new Job(second,List.of(file(90))))));
        for(var table:List.of("download_batches","download_batch_tasks","download_tasks","download_items","download_target_bindings")){int rows=db.read(c->{try(var result=c.createStatement().executeQuery("SELECT COUNT(*) FROM "+table)){result.next();return result.getInt(1);}});assertEquals(0,rows);}assertFalse(Files.exists(root));
    }}
    @Test void restartRetainsBatchReceiptAndPausesChildTasksWithoutRecreatingThem(){var paths=new AppPaths(directory.resolve("data"));var root=directory.resolve("out").toAbsolutePath();String operation=UUID.randomUUID().toString();Receipt before;
        try(var db=new Database(paths)){var repository=new DownloadRepository(db);before=repository.createBatch("1",operation,"fp",snapshot(root,2),root,List.of(new Job(DownloadBatchServiceTest.post(100),List.of(file(100))),new Job(DownloadBatchServiceTest.post(90),List.of(file(90)))));}
        try(var db=new Database(paths)){var repository=new DownloadRepository(db);assertEquals(before,repository.batchByOperation("1",operation,"fp"));assertTrue(repository.list("1",50,0).stream().allMatch(t->t.state().equals("PAUSED")));assertEquals(2,repository.list("1",50,0).size());assertFalse(Files.exists(root));}
    }
    @Test void migrationFrom13BacksUpAndPreservesExistingRows(){var paths=new AppPaths(directory.resolve("data"));
        try(var db=new Database(paths)){db.write(c->{var s=c.createStatement();io.github.telegramenhance.storage.DownloadRuleMigrationFixture.drop(s);s.execute("INSERT INTO app_settings VALUES('batch-migration','kept',1)");s.execute("DROP TABLE download_batch_tasks");s.execute("DROP TABLE download_batches");s.execute("PRAGMA user_version=13");return null;});}
        try(var db=new Database(paths)){assertEquals(Database.SCHEMA_VERSION,db.diagnostics().get("schemaVersion"));assertEquals("ok",db.diagnostics().get("integrity"));assertFalse(db.diagnostics().get("lastMigrationBackup").toString().isBlank());assertEquals("kept",db.read(c->{try(var r=c.createStatement().executeQuery("SELECT setting_value FROM app_settings WHERE setting_key='batch-migration'")){r.next();return r.getString(1);}}));}
    }
}
