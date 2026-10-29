package io.github.telegramenhance.storage;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import java.nio.file.*;
import java.sql.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadMigrationTest {
    @TempDir Path root;
    @Test void schemaSevenIsConsistentlyBackedUpWithoutReplacingExistingSettingsOrDownloads()throws Exception{
        var paths=new AppPaths(root);try(var db=new Database(paths)){db.write(c->{try(var s=c.createStatement()){s.execute("INSERT INTO app_settings VALUES('opaque','protected-bytes',1)");}return null;});}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=c.createStatement()){for(var table:List.of("upload_events","upload_claims","upload_items","upload_groups","upload_tasks"))s.execute("DROP TABLE "+table);DownloadHistoryMigrationTest.restoreV8Downloads(s);s.execute("PRAGMA user_version=7");}
        try(var db=new Database(paths)){assertEquals(Database.SCHEMA_VERSION,db.diagnostics().get("schemaVersion"));assertEquals("ok",db.diagnostics().get("integrity"));try(var backup=DriverManager.getConnection("jdbc:sqlite:"+db.diagnostics().get("lastMigrationBackup"));var s=backup.createStatement();var r=s.executeQuery("SELECT setting_value FROM app_settings WHERE setting_key='opaque'")){assertTrue(r.next());assertEquals("protected-bytes",r.getString(1));}assertEquals("protected-bytes",db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT setting_value FROM app_settings WHERE setting_key='opaque'")){r.next();return r.getString(1);}}));}
    }
    @Test void aClaimedCurrentSchemaWithMissingReceiptColumnsIsRejected()throws Exception{
        var paths=new AppPaths(root);try(var ignored=new Database(paths)){}try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=c.createStatement()){s.execute("ALTER TABLE upload_items RENAME COLUMN formal_id TO missing_formal_id");}assertThrows(IllegalStateException.class,()->new Database(paths));try(var files=Files.list(paths.backups())){assertEquals(0,files.count());}
    }
}
