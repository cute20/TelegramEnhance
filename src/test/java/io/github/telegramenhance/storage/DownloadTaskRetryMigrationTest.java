package io.github.telegramenhance.storage;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.transfer.DownloadModels.FrozenFile;
import io.github.telegramenhance.transfer.DownloadRepository;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadTaskRetryMigrationTest {
    @TempDir Path directory;

    @Test void upgradesVersionEighteenWithABackupAndPreservesTaskCheckpointsAndDeletionReceipts()throws Exception{
        var paths=new AppPaths(directory.resolve("data"));String operation=UUID.randomUUID().toString();String id,item;
        try(var db=new Database(paths)){
            var repo=new DownloadRepository(db);var ref=new MessageRef("-1001","100",null);var media=new Media("1:-1001:100:0",ref,"MAIN","DOCUMENT","saved.bin",37,"");
            var post=new Post("source","1","-1001","channel","100","post","","0",List.of(ref),List.of(media),null,null,List.of(),false,"PARTIAL",List.of());
            var task=repo.create("1",operation,"fingerprint",post,directory.resolve("output"),List.of(new FrozenFile(media,"stable",37,"saved.bin")));
            id=task.id();item=task.items().getFirst().id();repo.progress("1",id,item,"WRITING",37,11,"saved-prefix","saved.bin");repo.pause("1",id);
            repo.deletePausedStandalone("1",id,()->{});
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TABLE download_task_retries");s.execute("PRAGMA user_version=18");}return null;});
        }
        try(var db=new Database(paths)){
            assertEquals(Database.SCHEMA_VERSION,db.diagnostics().get("schemaVersion"));assertEquals("ok",db.diagnostics().get("integrity"));
            var repo=new DownloadRepository(db);var task=repo.byOperation("1",operation);assertNotNull(task);assertEquals(id,task.id());
            assertEquals(item,task.items().getFirst().id());assertEquals(11,task.items().getFirst().writtenBytes());assertEquals("saved-prefix",task.items().getFirst().sha256());
            assertTrue(repo.list("1",50,0).isEmpty());
            db.read(c->{try(var s=c.createStatement();var rows=s.executeQuery("SELECT COUNT(*) FROM download_task_retries")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}return null;});
            db.write(c->{try(var s=c.createStatement()){
                assertThrows(java.sql.SQLException.class,()->s.execute("INSERT INTO download_task_retries VALUES('missing','1',1,1)"));
                try(var q=c.prepareStatement("INSERT INTO download_task_retries VALUES(?,'1',?,?)")){
                    q.setString(1,id);q.setInt(2,0);q.setLong(3,1);assertThrows(java.sql.SQLException.class,q::executeUpdate);
                    q.setInt(2,1);q.setLong(3,-1);assertThrows(java.sql.SQLException.class,q::executeUpdate);
                    q.setLong(3,8_640_000_000_000_001L);assertThrows(java.sql.SQLException.class,q::executeUpdate);
                    q.setLong(3,12345);q.executeUpdate();
                }
                try(var rows=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(rows.next());}
            }return null;});
            try(var backup=DriverManager.getConnection("jdbc:sqlite:"+db.diagnostics().get("lastMigrationBackup"));var s=backup.createStatement()){
                try(var rows=s.executeQuery("PRAGMA user_version")){assertTrue(rows.next());assertEquals(18,rows.getInt(1));}
                try(var rows=s.executeQuery("SELECT COUNT(*) FROM download_tasks")){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
                try(var rows=s.executeQuery("SELECT COUNT(*) FROM download_task_deletions")){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
                try(var rows=s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='download_task_retries'")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
            }
        }
        try(var db=new Database(paths)){
            db.read(c->{try(var q=c.prepareStatement("SELECT attempt,next_attempt_at FROM download_task_retries WHERE task_id=?")){q.setString(1,id);try(var rows=q.executeQuery()){assertTrue(rows.next());assertEquals(1,rows.getInt(1));assertEquals(12345,rows.getLong(2));}}return null;});
        }
    }

    @Test void aDatabaseClaimingTheNewVersionWithoutItsRetryTableIsRejectedWithoutResettingExistingData()throws Exception{
        var paths=new AppPaths(directory.resolve("damaged"));
        try(var db=new Database(paths)){db.write(c->{try(var s=c.createStatement()){s.execute("INSERT INTO app_settings VALUES('preserved','yes',1)");s.execute("DROP TABLE download_task_retries");}return null;});}
        assertThrows(IllegalStateException.class,()->new Database(paths));
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=connection.createStatement();var rows=s.executeQuery("SELECT setting_value FROM app_settings WHERE setting_key='preserved'")){
            assertTrue(rows.next());assertEquals("yes",rows.getString(1));
        }
    }
}
