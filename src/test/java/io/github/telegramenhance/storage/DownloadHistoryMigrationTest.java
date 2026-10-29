package io.github.telegramenhance.storage;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadHistoryMigrationTest {
    @TempDir Path root;
    static void restoreV9Downloads(Statement sql)throws SQLException {
        DownloadRuleMigrationFixture.drop(sql);
        for(var table:List.of("download_batch_tasks","download_batches","auto_upload_bindings","auto_rule_authorizations","upload_retry_attempts","upload_send_failures","auto_rule_observations","auto_rule_versions","auto_rules"))sql.execute("DROP TABLE IF EXISTS "+table);
        sql.execute("DROP TABLE download_output_plans");sql.execute("DROP TABLE download_completion_log");
        sql.execute("ALTER TABLE download_tasks DROP COLUMN duplicate_policy");
    }
    static void restoreV8Downloads(Statement sql) throws SQLException {
        restoreV9Downloads(sql);
        sql.execute("CREATE TEMP TABLE saved_download_items AS SELECT item_id,task_id,ordinal,media_key,spec_json,unique_id,size,relative_file,owner_token,state,downloaded_bytes,written_bytes,sha256,error_message FROM download_items");
        sql.execute("DROP TABLE download_items");sql.execute("DROP TABLE download_history");
        sql.execute("""
            CREATE TABLE download_items (
                item_id TEXT PRIMARY KEY NOT NULL,task_id TEXT NOT NULL REFERENCES download_tasks(task_id),ordinal INTEGER NOT NULL,
                media_key TEXT NOT NULL,spec_json TEXT NOT NULL,unique_id TEXT NOT NULL,size INTEGER NOT NULL CHECK(size>0),
                relative_file TEXT NOT NULL,owner_token TEXT NOT NULL,state TEXT NOT NULL
                  CHECK(state IN ('QUEUED','DOWNLOADING','WRITING','FINALIZING','COMPLETED')),
                downloaded_bytes INTEGER NOT NULL DEFAULT 0,written_bytes INTEGER NOT NULL DEFAULT 0,sha256 TEXT,error_message TEXT,
                UNIQUE(task_id,media_key),UNIQUE(task_id,ordinal))
            """);
        sql.execute("INSERT INTO download_items SELECT * FROM saved_download_items");sql.execute("DROP TABLE saved_download_items");
    }
    private Post post(){return new Post("1:-1001:100","1","-1001","channel","100","title","body","0",List.of(new MessageRef("-1001","100","")),List.of(),null,null,List.of(),false,"UNAVAILABLE",List.of());}
    private FrozenFile file(long size){return new FrozenFile(new Media("1:-1001:100:0",new MessageRef("-1001","100",""),"MAIN","DOCUMENT","sample.bin",size,""),"stable-version",size,"sample.bin");}
    @Test void v8HistoryBackfillUsesCommittedSuccessEvenAfterItsFileWasMovedAndPreservesTheBackup()throws Exception {
        var paths=new AppPaths(root.resolve("app"));String firstId;String hash;
        try(var db=new Database(paths)){
            var repo=new DownloadRepository(db);var store=new DownloadFileStore(new DownloadPaths(paths),repo);
            var first=repo.create("1",UUID.randomUUID().toString(),"first",post(),root.resolve("old-target"),List.of(file(17)));firstId=first.id();
            store.write(first,first.items().getFirst(),(offset,count)->new byte[count],()->{});repo.finishState("1",firstId,"COMPLETED",null);
            var saved=repo.task("1",firstId);hash=saved.items().getFirst().sha256();
            Files.move(store.directory(first).resolve(saved.items().getFirst().relativeFile()),root.resolve("moved.bin"));
            var partial=repo.create("1",UUID.randomUUID().toString(),"partial",post(),root.resolve("old-target"),List.of(file(19)));
            repo.progress("1",partial.id(),partial.items().getFirst().id(),"WRITING",19,7,"a".repeat(64),partial.items().getFirst().relativeFile());repo.finishState("1",partial.id(),"FAILED","partial");
            db.write(c->{try(var s=c.createStatement()){s.execute("INSERT INTO app_settings VALUES('opaque','protected-value',1)");}return null;});
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=c.createStatement()){restoreV8Downloads(s);s.execute("PRAGMA user_version=8");}
        try(var db=new Database(paths)){
            assertEquals(Database.SCHEMA_VERSION,db.diagnostics().get("schemaVersion"));assertEquals("ok",db.diagnostics().get("integrity"));
            int historyCount=db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM download_history")){r.next();return r.getInt(1);}});assertEquals(1,historyCount);
            var repo=new DownloadRepository(db);var again=repo.create("1",UUID.randomUUID().toString(),"new",post(),root.resolve("never-created"),List.of(file(17)),DownloadModels.HISTORY_SKIP);
            assertEquals("COMPLETED",again.state());assertEquals(0,repo.view(again).completed());assertEquals(1,repo.view(again).skipped());
            assertEquals(firstId,again.items().getFirst().history().taskId());assertEquals(hash,again.items().getFirst().history().sha256());assertFalse(Files.exists(root.resolve("never-created")));
            try(var backup=DriverManager.getConnection("jdbc:sqlite:"+db.diagnostics().get("lastMigrationBackup"));var s=backup.createStatement()){
                try(var r=s.executeQuery("PRAGMA user_version")){r.next();assertEquals(8,r.getInt(1));}
                try(var r=s.executeQuery("SELECT setting_value FROM app_settings WHERE setting_key='opaque'")){r.next();assertEquals("protected-value",r.getString(1));}
                try(var r=s.executeQuery("SELECT COUNT(*) FROM download_items")){r.next();assertEquals(2,r.getInt(1));}
            }
        }
    }
    @Test void incompleteOrMalformedLegacyProofCannotPopulateHistory()throws Exception{
        var paths=new AppPaths(root.resolve("app"));
        try(var db=new Database(paths)){
            var repo=new DownloadRepository(db);var task=repo.create("1",UUID.randomUUID().toString(),"bad",post(),root.resolve("out"),List.of(file(17)));
            db.write(c->{try(var q=c.prepareStatement("UPDATE download_items SET state='COMPLETED',downloaded_bytes=17,written_bytes=17,sha256='partial-checksum' WHERE task_id=?")){q.setString(1,task.id());q.executeUpdate();}return null;});
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=c.createStatement()){restoreV8Downloads(s);s.execute("PRAGMA user_version=8");}
        try(var db=new Database(paths)){
            var repo=new DownloadRepository(db);var task=repo.create("1",UUID.randomUUID().toString(),"new",post(),root.resolve("out"),List.of(file(17)));
            assertEquals("QUEUED",task.items().getFirst().state());assertEquals(0,repo.view(task).skipped());
        }
    }
    @Test void aClaimedCurrentSchemaWithMissingHistoryEvidenceIsRejectedWithoutMigration()throws Exception{
        var paths=new AppPaths(root.resolve("app"));try(var ignored=new Database(paths)){}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=c.createStatement()){s.execute("ALTER TABLE download_history RENAME COLUMN sha256 TO missing_hash");}
        assertThrows(IllegalStateException.class,()->new Database(paths));try(var backups=Files.list(paths.backups())){assertEquals(0,backups.count());}
    }
}
