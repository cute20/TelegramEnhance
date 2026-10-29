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

class DownloadCompletionMigrationTest {
    @TempDir Path root;
    @Test void v9MigrationPreservesLegacyPolicyAndBackfillsSourceLinksWithoutLookingForMovedFiles()throws Exception{
        var paths=new AppPaths(root.resolve("app"));String oldId;
        var post=new Post("1:-1001:1","1","-1001","channel","1","title","body","0",List.of(new MessageRef("-1001","1","https://t.me/synthetic/1")),List.of(),null,null,List.of(),false,"UNAVAILABLE",List.of());
        var file=new FrozenFile(new Media("1:-1001:100:0",new MessageRef("-1001","100","https://t.me/synthetic/100"),"MAIN","DOCUMENT","sample.bin",17,""),"uid",17,"sample.bin");
        try(var db=new Database(paths)){
            var repo=new DownloadRepository(db);var store=new DownloadFileStore(new DownloadPaths(paths),repo);
            var old=repo.create("1",UUID.randomUUID().toString(),"old",post,root.resolve("out"),List.of(file),DownloadModels.HISTORY_SKIP);oldId=old.id();
            store.write(old,old.items().getFirst(),(offset,count)->new byte[count],()->{});repo.finishState("1",oldId,"COMPLETED",null);
            Files.move(Path.of(repo.view(repo.task("1",oldId)).items().getFirst().outputPath()),root.resolve("moved.bin"));
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+paths.database());var s=c.createStatement()){DownloadHistoryMigrationTest.restoreV9Downloads(s);s.execute("PRAGMA user_version=9");}
        try(var db=new Database(paths)){
            var repo=new DownloadRepository(db);assertEquals(DownloadModels.HISTORY_SKIP,repo.task("1",oldId).duplicatePolicy());
            db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT source_link,post_link,reason FROM download_completion_log")){assertTrue(r.next());assertEquals("https://t.me/synthetic/100",r.getString(1));assertEquals("https://t.me/synthetic/1",r.getString(2));assertEquals("LEGACY_VERIFIED",r.getString(3));assertFalse(r.next());}return null;});
            try(var backup=DriverManager.getConnection("jdbc:sqlite:"+db.diagnostics().get("lastMigrationBackup"));var s=backup.createStatement();var r=s.executeQuery("PRAGMA user_version")){r.next();assertEquals(9,r.getInt(1));}
            var next=repo.create("1",UUID.randomUUID().toString(),"next",post,root.resolve("new-target"),List.of(file));assertEquals(DownloadModels.KEEP_DUPLICATES,next.duplicatePolicy());assertEquals("QUEUED",next.state());
            new DownloadFileStore(new DownloadPaths(paths),repo).write(next,next.items().getFirst(),(offset,count)->new byte[count],()->{});
            var view=repo.view(repo.task("1",next.id()));assertEquals(1,view.completed());assertEquals(1,view.duplicates());assertEquals(0,view.skipped());assertTrue(Path.of(view.items().getFirst().outputPath()).startsWith(root.resolve("new-target/重复媒体")));
            assertEquals("ok",db.diagnostics().get("integrity"));
        }
    }
}
