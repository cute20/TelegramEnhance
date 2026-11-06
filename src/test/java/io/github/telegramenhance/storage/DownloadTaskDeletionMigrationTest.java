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

class DownloadTaskDeletionMigrationTest {
    @TempDir Path directory;

    @Test void upgradesVersionSeventeenWithoutRemovingTasksOrOperationReceipts() throws Exception {
        var paths=new AppPaths(directory);String operation=UUID.randomUUID().toString();String taskId;
        try(var database=new Database(paths)){
            var repository=new DownloadRepository(database);
            var ref=new MessageRef("-1001","100",null);
            var media=new Media("1:-1001:100:0",ref,"MAIN","DOCUMENT","saved.bin",37,"");
            var post=new Post("source","1","-1001","channel","100","post","",null,List.of(ref),List.of(media),null,null,List.of(),false,"PARTIAL",List.of());
            var task=repository.create("1",operation,"fingerprint",post,directory.resolve("output"),List.of(new FrozenFile(media,"stable",37,"saved.bin")));
            taskId=task.id();repository.pause("1",taskId);
            database.write(connection->{try(var statement=connection.createStatement()){
                statement.execute("DROP TABLE download_task_deletions");
                statement.execute("PRAGMA user_version=17");
            }return null;});
        }
        try(var upgraded=new Database(paths)){
            assertEquals(Database.SCHEMA_VERSION,upgraded.diagnostics().get("schemaVersion"));
            var repository=new DownloadRepository(upgraded);var task=repository.byOperation("1",operation);
            assertNotNull(task);assertEquals(taskId,task.id());assertEquals("PAUSED",task.state());assertEquals(1,task.items().size());
            upgraded.read(connection->{try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM download_task_deletions")){
                assertTrue(rows.next());assertEquals(0,rows.getInt(1));
            }return null;});
            upgraded.write(connection->{try(var statement=connection.createStatement()){
                assertThrows(java.sql.SQLException.class,()->statement.execute("INSERT INTO download_task_deletions VALUES('missing','1',1,1)"));
                try(var insert=connection.prepareStatement("INSERT INTO download_task_deletions VALUES(?,'1',1,1)")){insert.setString(1,taskId);insert.executeUpdate();}
                try(var rows=statement.executeQuery("PRAGMA foreign_key_check")){assertFalse(rows.next());}
            }return null;});
            try(var backup=DriverManager.getConnection("jdbc:sqlite:"+upgraded.diagnostics().get("lastMigrationBackup"));var statement=backup.createStatement()){
                try(var rows=statement.executeQuery("PRAGMA user_version")){assertTrue(rows.next());assertEquals(17,rows.getInt(1));}
                try(var rows=statement.executeQuery("SELECT COUNT(*) FROM download_tasks")){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
            }
        }
        try(var reopened=new Database(paths)){
            reopened.read(connection->{try(var query=connection.prepareStatement("SELECT cleanup_pending FROM download_task_deletions WHERE task_id=?")){
                query.setString(1,taskId);try(var rows=query.executeQuery()){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
            }return null;});
        }
    }
}
