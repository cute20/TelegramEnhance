package io.github.telegramenhance.storage;
import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.transfer.UploadTestFixture;
import java.nio.file.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class UploadRetryMigrationTest{
    @TempDir Path root;
    @Test void v11MigrationKeepsFrozenUploadPlanFormalReceiptsAndSettingsAndHasConsistentBackup()throws Exception{
        AppPaths paths;String prepared,formal;
        try(var f=new UploadTestFixture(root,1)){paths=f.app;f.confirm();prepared=value(f.database,"SELECT prepared_json FROM upload_tasks");formal=value(f.database,"SELECT formal_id FROM upload_items");f.database.write(c->{try(var s=c.createStatement()){s.execute("INSERT INTO app_settings VALUES('test','opaque',1)");}return null;});}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=c.createStatement()){DownloadRuleMigrationFixture.drop(s);s.execute("DROP TABLE download_batch_tasks");s.execute("DROP TABLE download_batches");s.execute("DROP TABLE auto_upload_bindings");s.execute("DROP TABLE auto_rule_authorizations");s.execute("DROP TABLE upload_retry_attempts");s.execute("DROP TABLE upload_send_failures");s.execute("PRAGMA user_version=11");}
        try(var db=new Database(paths)){assertEquals(Database.SCHEMA_VERSION,db.diagnostics().get("schemaVersion"));assertEquals(prepared,value(db,"SELECT prepared_json FROM upload_tasks"));assertEquals(formal,value(db,"SELECT formal_id FROM upload_items"));assertEquals("opaque",value(db,"SELECT setting_value FROM app_settings"));assertEquals("0",value(db,"SELECT COUNT(*) FROM upload_retry_attempts"));try(var c=DriverManager.getConnection("jdbc:sqlite:"+db.diagnostics().get("lastMigrationBackup"));var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){r.next();assertEquals(11,r.getInt(1));}}
    }
    @Test void claimedCurrentSchemaMissingRetryEvidenceIsRejectedWithoutDeletingData()throws Exception{var paths=new AppPaths(root);try(var ignored=new Database(paths)){}try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=c.createStatement()){s.execute("ALTER TABLE upload_retry_attempts RENAME COLUMN prior_temporary_id TO missing_identity");}assertThrows(IllegalStateException.class,()->new Database(paths));}
    static String value(Database db,String sql){return db.read(c->{try(var q=c.createStatement();var r=q.executeQuery(sql)){r.next();return r.getString(1);}});}
}
