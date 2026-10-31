package io.github.telegramenhance.storage;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadRuleMigrationTest {
    @TempDir Path directory;
    AppPaths v14()throws Exception {
        var paths=new AppPaths(directory);
        try(var db=new Database(paths)){db.write(c->{try(var s=c.createStatement()){s.execute("INSERT INTO app_settings VALUES('protected-key','opaque-current-user-ciphertext',7)");DownloadRuleMigrationFixture.drop(s);s.execute("PRAGMA user_version=14");}return null;});}
        return paths;
    }
    @Test void schema14IsConsistentlyBackedUpBeforeAddingSixEmptyDownloadRuleTables()throws Exception {
        var paths=v14();
        try(var original=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=original.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA wal_autocheckpoint=0");s.execute("INSERT INTO app_settings VALUES('latest-wal','preserved',8)");
            assertTrue(Files.size(Path.of(paths.database()+"-wal"))>0);
            try(var db=new Database(paths)){
                assertEquals(Database.SCHEMA_VERSION,db.diagnostics().get("schemaVersion"));assertEquals("ok",db.diagnostics().get("integrity"));assertEquals("opaque-current-user-ciphertext",value(db,"SELECT setting_value FROM app_settings WHERE setting_key='protected-key'"));assertEquals("preserved",value(db,"SELECT setting_value FROM app_settings WHERE setting_key='latest-wal'"));
                for(String table:java.util.List.of("download_rules","download_rule_versions","download_rule_scans","download_rule_posts","download_rule_media","download_rule_tasks"))assertEquals("0",value(db,"SELECT COUNT(*) FROM "+table));
                try(var backup=DriverManager.getConnection("jdbc:sqlite:"+db.diagnostics().get("lastMigrationBackup"))){assertEquals("14",scalar(backup,"PRAGMA user_version"));assertEquals("ok",scalar(backup,"PRAGMA quick_check"));assertEquals("preserved",scalar(backup,"SELECT setting_value FROM app_settings WHERE setting_key='latest-wal'"));assertEquals("0",scalar(backup,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='download_rules'"));}
            }
        }
        try(var db=new Database(paths)){assertEquals("",db.diagnostics().get("lastMigrationBackup"));assertEquals("opaque-current-user-ciphertext",value(db,"SELECT setting_value FROM app_settings WHERE setting_key='protected-key'"));}
    }
    @Test void conflictingNewTableRollsBackMigrationAndKeepsTheOriginalAndBackup()throws Exception {
        var paths=v14();try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=c.createStatement()){s.execute("CREATE TABLE download_rules(legacy TEXT)");s.execute("INSERT INTO download_rules VALUES('keep')");}
        assertThrows(IllegalStateException.class,()->new Database(paths));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database())){assertEquals("14",scalar(c,"PRAGMA user_version"));assertEquals("keep",scalar(c,"SELECT legacy FROM download_rules"));assertEquals("opaque-current-user-ciphertext",scalar(c,"SELECT setting_value FROM app_settings WHERE setting_key='protected-key'"));assertEquals("0",scalar(c,"SELECT COUNT(*) FROM sqlite_master WHERE name='download_rule_versions'"));}
        try(var backups=Files.list(paths.backups())){var files=backups.toList();assertEquals(1,files.size());try(var c=DriverManager.getConnection("jdbc:sqlite:"+files.getFirst())){assertEquals("14",scalar(c,"PRAGMA user_version"));assertEquals("keep",scalar(c,"SELECT legacy FROM download_rules"));}}
    }
    @Test void claimedCurrentSchemaWithMissingScanOrBindingColumnsIsRejectedWithoutMigration()throws Exception {
        var paths=new AppPaths(directory);try(var ignored=new Database(paths)){}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=c.createStatement()){s.execute("ALTER TABLE download_rule_tasks RENAME COLUMN held TO missing_hold");}
        assertThrows(IllegalStateException.class,()->new Database(paths));try(var files=Files.list(paths.backups())){assertEquals(0,files.count());}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database())){assertEquals(Integer.toString(Database.SCHEMA_VERSION),scalar(c,"PRAGMA user_version"));}
    }
    static String value(Database db,String sql){return db.read(c->scalar(c,sql));}
    static String scalar(Connection c,String sql)throws java.sql.SQLException{try(var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
}
