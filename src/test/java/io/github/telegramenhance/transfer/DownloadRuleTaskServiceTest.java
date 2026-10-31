package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.downloadrules.*;
import io.github.telegramenhance.downloadrules.DownloadRuleModels.*;
import io.github.telegramenhance.library.ChannelImportModels.ManagedChannel;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.rules.RuleModels.Window;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.TelegramService;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DownloadRuleTaskServiceTest {
    @TempDir Path directory;
    final class Fixture implements AutoCloseable {
        final Database db=new Database(new AppPaths(directory.resolve("data")));
        final DownloadRepository downloads=new DownloadRepository(db);
        final DownloadRuleRepository rules=new DownloadRuleRepository(db,downloads);
        final TelegramService telegram=mock(TelegramService.class);
        final DownloadRuleTaskService service=new DownloadRuleTaskService(db,rules,downloads,telegram);
        Fixture(){when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);}
        Input input(){return new Input("same name","-1001","","",List.of("DOCUMENT"),true,directory.resolve("out").toString(),new Window("ALL_DAY",null,null,"Asia/Shanghai"));}
        Rule rule(String account){return rules.create(account,UUID.randomUUID().toString(),"fp",input(),new ManagedChannel("-1001","channel"),1,()->{});}
        DownloadModels.Task task(Rule rule,String source){
            if(rule!=null){
                var active=rules.activeJob(rule);
                if(active!=null){
                    var old=downloads.task(rule.accountId(),active.taskId());
                    for(var item:old.items())downloads.complete(rule.accountId(),old.id(),item.id(),item.relativeFile(),directory.resolve("out/"+item.id()),"a".repeat(64));
                    downloads.finishState(rule.accountId(),old.id(),"COMPLETED",null);
                    db.write(c->{try(var q=c.prepareStatement("UPDATE download_rule_tasks SET settled_at=1 WHERE task_id=?")){q.setString(1,old.id());q.executeUpdate();}return null;});
                }
            }
            String account=rule==null?"1":rule.accountId(),mid=UUID.randomUUID().toString();
            var ref=new MessageRef("-1001",mid,null);
            var post=new Post(account+":-1001:"+mid,account,"-1001","channel",mid,"post","text","0",List.of(ref),List.of(),"-2001","500",List.of(),true,"PARTIAL",List.of(),new AlbumEvidence(true,"test",null,null),"COMPLETE");
            var media=new Media(account+":"+mid,ref,source,"DOCUMENT","file.txt",37,"");
            var task=downloads.create(account,UUID.randomUUID().toString(),"fp",post,directory.resolve("out"),List.of(new DownloadModels.FrozenFile(media,mid,37,"file.txt")),DownloadModels.HISTORY_SKIP);
            if(rule!=null)db.write(c->{try(var q=c.prepareStatement("INSERT INTO download_rule_tasks VALUES(?,?,?,?,?,0,0,NULL,?)")){
                q.setString(1,task.id());q.setString(2,rule.id());q.setString(3,account);q.setLong(4,rule.version());q.setString(5,JsonMapper.builder().build().writeValueAsString(rule.input()));q.setLong(6,1);q.executeUpdate();}return null;});
            return task;
        }
        @Override public void close(){db.close();}
    }
    @Test void mainCommentsAndLaterVersionsProduceOneRuleRecordWithAllCounts(){try(var f=new Fixture()){
        var rule=f.rule("1");f.task(rule,"MAIN");f.task(rule,"COMMENT");
        rule=f.rules.update("1",rule.id(),rule.revision(),f.input(),new ManagedChannel("-1001","channel"),2,()->{});f.task(rule,"REPLY");
        var list=f.service.list();assertEquals(1,list.items().size());var group=list.items().getFirst();
        assertEquals(rule.id(),group.id());assertEquals(3,group.taskCount());assertEquals(3,group.total());assertEquals(1,group.mainTotal());assertEquals(2,group.commentTotal());assertEquals(111,group.totalBytes());
        assertEquals(3,f.service.members(rule.id(),20,0).items().size());assertEquals(0,f.downloads.list("1",20,0,true).size());
        assertEquals(3,f.downloads.list("1",20,0).size()); // Existing worker/history API and persisted tasks remain intact.
    }}
    @Test void sameNamesDoNotMergeAndAccountBoundaryAppliesToSummariesAndMembers(){try(var f=new Fixture()){
        var a=f.rule("1");var b=f.rule("1");var other=f.rule("2");f.task(a,"MAIN");f.task(b,"COMMENT");f.task(other,"MAIN");
        assertEquals(2,f.service.list().items().size());assertNotEquals(a.id(),b.id());
        assertThrows(IllegalArgumentException.class,()->f.service.get(other.id()));assertThrows(IllegalArgumentException.class,()->f.service.members(other.id(),10,0));
        assertEquals(1,f.service.members(a.id(),10,0).items().size());
    }}
    @Test void standaloneFilterRunsBeforePaginationAndChildPagingHasNoDuplicates(){try(var f=new Fixture()){
        var rule=f.rule("1");for(int n=0;n<22;n++)f.task(rule,n%2==0?"MAIN":"COMMENT");var manual=f.task(null,"MAIN");
        assertEquals(manual.id(),f.downloads.list("1",1,0,true).getFirst().id());assertTrue(f.downloads.list("1",1,1,true).isEmpty());
        var first=f.service.members(rule.id(),10,0).items();var second=f.service.members(rule.id(),10,10).items();
        var ids=new HashSet<String>();first.forEach(t->assertTrue(ids.add(t.id())));second.forEach(t->assertTrue(ids.add(t.id())));
        assertEquals(2,f.service.members(rule.id(),10,20).items().size());assertEquals(22,f.service.get(rule.id()).total());
    }}
    @Test void fullByteCountersAloneAreNeverReportedAsConfirmedCompletion(){try(var f=new Fixture()){
        var rule=f.rule("1");var task=f.task(rule,"MAIN");
        f.db.write(c->{c.createStatement().execute("UPDATE download_items SET state='COMPLETED',downloaded_bytes=size,written_bytes=size,sha256='"+"a".repeat(64)+"'");return null;});
        f.downloads.finishState("1",task.id(),"COMPLETED",null);
        var group=f.service.get(rule.id());assertEquals(37,group.writtenBytes());assertEquals(0,group.completed());assertEquals("FAILED",group.state());
    }}
    @Test void receiptCountsPersistAcrossRestartAndReadingDoesNotModifyCheckpoints(){
        String id,taskId;try(var f=new Fixture()){
            var rule=f.rule("1");id=rule.id();var task=f.task(rule,"MAIN");taskId=task.id();var item=task.items().getFirst();
            f.downloads.complete("1",task.id(),item.id(),item.relativeFile(),directory.resolve("out/saved.txt"),"a".repeat(64));f.downloads.finishState("1",task.id(),"COMPLETED",null);
            var before=f.downloads.task("1",taskId);assertEquals(1,f.service.get(id).completed());assertEquals(before,f.downloads.task("1",taskId));
        }
        try(var f=new Fixture()){assertEquals(1,f.service.get(id).completed());assertEquals(1,f.service.list().items().size());assertEquals("COMPLETED",f.downloads.task("1",taskId).state());}
    }
    @Test void sessionChangeAndInvalidPaginationDiscardReads(){try(var f=new Fixture()){
        var rule=f.rule("1");when(f.telegram.sessionGeneration()).thenReturn(1L,2L);assertThrows(IllegalArgumentException.class,()->f.service.get(rule.id()));
        for(int limit:List.of(0,21))assertThrows(IllegalArgumentException.class,()->f.service.members(rule.id(),limit,0));
        assertThrows(IllegalArgumentException.class,()->f.service.members(rule.id(),10,-1));assertThrows(IllegalArgumentException.class,()->f.service.get("../other"));
    }}
}
