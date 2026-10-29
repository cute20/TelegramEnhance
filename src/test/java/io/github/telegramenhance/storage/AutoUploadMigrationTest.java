package io.github.telegramenhance.storage;
import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.rules.*;
import io.github.telegramenhance.transfer.UploadTestFixture;
import java.nio.file.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class AutoUploadMigrationTest{
    @TempDir Path root;
    @Test void v12KeepsLegacyObservationJsonFrozenTasksReceiptsAndSettingsAndMakesAConsistentBackup()throws Exception{
        AppPaths paths;String prepared,formal,legacy;String rule;
        try(var f=new UploadTestFixture(root,1)){
            paths=f.app;f.confirm();prepared=value(f.database,"SELECT prepared_json FROM upload_tasks");formal=value(f.database,"SELECT formal_id FROM upload_items");
            var input=new RuleModels.Input("old observer","https://t.me/synthetic/1",f.source.toString(),f.archive.toString(),true,"FILENAME",new RuleModels.Window("ALL_DAY",null,null,"Asia/Shanghai"));
            var created=new RuleRepository(f.database).create("1",java.util.UUID.randomUUID().toString(),input,1);rule=created.id();
            f.database.write(c->{try(var s=c.createStatement()){s.execute("UPDATE auto_rule_versions SET config_json=json_remove(config_json,'$.executionMode')");s.execute("INSERT INTO app_settings VALUES('test','opaque',1)");}return null;});legacy=value(f.database,"SELECT config_json FROM auto_rule_versions");assertFalse(legacy.contains("executionMode"));
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=c.createStatement()){DownloadRuleMigrationFixture.drop(s);s.execute("DROP TABLE download_batch_tasks");s.execute("DROP TABLE download_batches");s.execute("DROP TABLE auto_upload_bindings");s.execute("DROP TABLE auto_rule_authorizations");s.execute("PRAGMA user_version=12");}
        try(var db=new Database(paths)){
            assertEquals(Database.SCHEMA_VERSION,db.diagnostics().get("schemaVersion"));assertEquals(prepared,value(db,"SELECT prepared_json FROM upload_tasks"));assertEquals(formal,value(db,"SELECT formal_id FROM upload_items"));assertEquals(legacy,value(db,"SELECT config_json FROM auto_rule_versions"));assertEquals("opaque",value(db,"SELECT setting_value FROM app_settings"));
            assertEquals("OBSERVE_ONLY",new RuleRepository(db).get("1",rule).input().executionMode());assertEquals("0",value(db,"SELECT COUNT(*) FROM auto_upload_bindings"));assertEquals("0",value(db,"SELECT COUNT(*) FROM auto_rule_authorizations"));
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+db.diagnostics().get("lastMigrationBackup"));var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){r.next();assertEquals(12,r.getInt(1));}
        }
    }
    @Test void claimedCurrentSchemaWithoutFrozenRuleProofIsRejected()throws Exception{var paths=new AppPaths(root);try(var ignored=new Database(paths)){}try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=c.createStatement()){s.execute("ALTER TABLE auto_upload_bindings RENAME COLUMN snapshot_json TO missing_snapshot");}assertThrows(IllegalStateException.class,()->new Database(paths));}
    static String value(Database db,String sql){return db.read(c->{try(var q=c.createStatement();var r=q.executeQuery(sql)){r.next();return r.getString(1);}});}
}
