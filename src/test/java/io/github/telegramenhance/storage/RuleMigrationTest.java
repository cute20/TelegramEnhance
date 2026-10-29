package io.github.telegramenhance.storage;
import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import java.nio.file.*;
import java.sql.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class RuleMigrationTest{
    @TempDir Path root;
    @Test void v10IsBackedUpAndExistingSettingsSurvive()throws Exception{
        var paths=new AppPaths(root);try(var db=new Database(paths)){db.write(c->{try(var s=c.createStatement()){s.execute("INSERT INTO app_settings VALUES('test','opaque',1)");}return null;});}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=c.createStatement()){DownloadRuleMigrationFixture.drop(s);for(String t:List.of("download_batch_tasks","download_batches","auto_upload_bindings","auto_rule_authorizations","upload_retry_attempts","upload_send_failures","auto_rule_observations","auto_rule_versions","auto_rules"))s.execute("DROP TABLE "+t);s.execute("PRAGMA user_version=10");}
        try(var db=new Database(paths)){assertEquals(Database.SCHEMA_VERSION,db.diagnostics().get("schemaVersion"));assertEquals("opaque",value(db,"SELECT setting_value FROM app_settings"));assertEquals("0",value(db,"SELECT COUNT(*) FROM auto_rules"));try(var c=DriverManager.getConnection("jdbc:sqlite:"+db.diagnostics().get("lastMigrationBackup"));var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){r.next();assertEquals(10,r.getInt(1));}}
    }
    @Test void claimedCurrentSchemaWithMissingRuleColumnIsRejected()throws Exception{var paths=new AppPaths(root);try(var ignored=new Database(paths)){}try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=c.createStatement()){s.execute("ALTER TABLE auto_rules RENAME COLUMN revision TO missing_revision");}assertThrows(IllegalStateException.class,()->new Database(paths));try(var backups=Files.list(paths.backups())){assertEquals(0,backups.count());}}
    static String value(Database db,String sql){return db.read(c->{try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getString(1);}});}
}
