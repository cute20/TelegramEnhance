package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.rules.*;
import io.github.telegramenhance.downloadrules.*;
import io.github.telegramenhance.transfer.TransferCenterModels.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransferCenterServiceTest {
    @TempDir Path root;
    private final class Fixture implements AutoCloseable {
        final Database db=new Database(new AppPaths(root.resolve(UUID.randomUUID().toString())));
        final TelegramService telegram=mock(TelegramService.class);final DownloadRepository downloads=new DownloadRepository(db);final UploadRepository uploads=new UploadRepository(db);
        final DownloadTaskService downloadTasks=mock(DownloadTaskService.class);final DownloadBatchService batches=mock(DownloadBatchService.class);final UploadTaskService uploadTasks=mock(UploadTaskService.class);
        final DownloadRuleTaskService ruleTasks=mock(DownloadRuleTaskService.class);final RuleService rules=mock(RuleService.class);final DownloadRuleService downloadRules=mock(DownloadRuleService.class);
        final RulePreparationService preparations=mock(RulePreparationService.class);final DownloadChannelService scans=mock(DownloadChannelService.class);final UploadRetryService retries=mock(UploadRetryService.class);
        final TransferCenterService center;
        Fixture(){when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);
            when(ruleTasks.list()).thenReturn(new DownloadRuleTaskService.Listing(List.of()));when(rules.list()).thenReturn(new RuleModels.Listing(List.of(),"ALL",1));
            when(downloadRules.list()).thenReturn(new DownloadRuleModels.Listing(List.of(),2));when(preparations.snapshot()).thenReturn(List.of());when(scans.snapshot()).thenReturn(List.of());when(batches.snapshot()).thenReturn(List.of());
            center=new TransferCenterService(db,telegram,downloads,uploads,downloadTasks,batches,uploadTasks,ruleTasks,rules,downloadRules,preparations,scans,retries);
        }
        DownloadModels.Task download(int id){var post=DownloadBatchServiceTest.post(id);return downloads.create("1",UUID.randomUUID().toString(),"fp",post,root.resolve("output"),List.of(new DownloadModels.FrozenFile(post.mainMedia().getFirst(),"unique"+id,37,"file"+id+".txt")));}
        public void close(){db.close();}
    }
    @Test void waitingRetriesRemainPausableAndPauseAllCancelsTheirAutomaticStart(){try(var f=new Fixture()){
        var task=f.download(1);assertEquals(task.id(),f.downloads.next("1").id());
        assertTrue(f.downloads.scheduleRetry("1",task.id(),"Telegram 文件读取超时",0));
        var page=f.center.list("download","waiting",20,0);assertEquals(1,page.total());
        var row=page.items().getFirst();assertEquals("QUEUED",row.state());assertEquals("RETRY_WAIT",row.phase());
        assertEquals(f.downloads.retry("1",task.id()),row.retry());assertEquals(1,row.retry().attempt());
        var encoded=tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(row);
        assertEquals(row.retry().nextAttemptAt(),encoded.get("retry").get("nextAttemptAt").asLong());
        assertEquals(List.of("pause"),row.actions());assertNull(row.speedBytesPerSecond());assertNull(row.etaSeconds());
        when(f.downloadTasks.pause(task.id())).thenAnswer(call->f.downloads.view(f.downloads.pause("1",task.id())));
        var receipt=f.center.pauseAll(new PauseRequest(UUID.randomUUID().toString()));
        assertEquals("COMPLETED",receipt.state());assertEquals(1,receipt.accepted().size());assertTrue(receipt.failed().isEmpty());
        var paused=f.downloads.view(f.downloads.task("1",task.id()));assertEquals("PAUSED",paused.state());assertNull(paused.retry());
        assertFalse(f.downloads.scheduleRetry("1",task.id(),"迟到的连接错误",0));assertNull(f.downloads.next("1"));
        var stopped=f.center.list("download","waiting",20,0).items().getFirst();
        assertEquals("QUEUED",stopped.phase());assertNull(stopped.retry());
    }}
    @Test void retryScheduleIsShownUntilClaimedAndDoesNotLeakToRunningOrAnotherAccount(){try(var f=new Fixture()){
        var task=f.download(1);f.downloads.next("1");
        assertTrue(f.downloads.scheduleRetry("1",task.id(),"原文件连续两分钟没有下载进展",0));
        var waiting=f.center.list("download","all",20,0).items().getFirst();
        assertEquals("RETRY_WAIT",waiting.phase());assertNotNull(waiting.retry());
        f.db.write(c->{try(var q=c.prepareStatement("UPDATE download_task_retries SET next_attempt_at=1 WHERE task_id=?")){q.setString(1,task.id());q.executeUpdate();}return null;});
        var due=f.center.list("download","all",20,0).items().getFirst();
        assertEquals(1,due.retry().nextAttemptAt());assertEquals("RETRY_WAIT",due.phase());
        assertEquals(task.id(),f.downloads.next("1").id());
        var running=f.center.list("download","all",20,0).items().getFirst();
        assertEquals("RUNNING",running.state());assertNotEquals("RETRY_WAIT",running.phase());assertNull(running.retry());
        when(f.telegram.accountId()).thenReturn("2");assertTrue(f.center.list("download","all",20,0).items().isEmpty());
    }}
    @Test void countsAndPagingApplyToDistinctParentsAcrossAllSavedRows(){try(var f=new Fixture()){
        var standalone=f.download(1);var batchPost=DownloadBatchServiceTest.post(2);var frozen=new DownloadModels.FrozenFile(batchPost.mainMedia().getFirst(),"batch-unique",37,"file2.txt");
        var batch=f.downloads.createBatch("1",UUID.randomUUID().toString(),"batch",new DownloadBatchModels.Snapshot(UUID.randomUUID().toString(),1,"channel",List.of(),List.of(),List.of("DOCUMENT"),true,root.resolve("output").toString(),1,1,37),root.resolve("output"),List.of(new DownloadBatchModels.Job(batchPost,List.of(frozen))));
        f.downloads.pause("1",standalone.id());
        var page=f.center.list("download","waiting",1,0);assertEquals(2,page.total());assertEquals(2,page.counts().waiting());assertEquals(1,page.items().size());
        var second=f.center.list("download","waiting",1,1);assertNotEquals(page.items().getFirst().category()+page.items().getFirst().id(),second.items().getFirst().category()+second.items().getFirst().id());
        var all=f.center.list("all","all",50,0);assertEquals(Set.of(standalone.id(),batch.id()),new HashSet<>(all.items().stream().map(Row::id).toList()));
        assertTrue(all.items().stream().noneMatch(row->batch.taskIds().contains(row.id())));when(f.telegram.accountId()).thenReturn("2");assertEquals(0,f.center.list("all","all",20,0).total());
    }}
    @Test void statusFilteringDoesNotChangeGlobalCountsOrRequireDecodingHiddenTaskPayloads(){try(var f=new Fixture()){
        var waiting=f.download(1);var failed=f.download(2);f.downloads.finishState("1",failed.id(),"FAILED","synthetic");
        var page=f.center.list("download","attention",20,0);assertEquals(1,page.total());assertEquals(failed.id(),page.items().getFirst().id());assertEquals(1,page.counts().attention());assertEquals(1,page.counts().waiting());
        assertThrows(IllegalArgumentException.class,()->f.center.list("all","bad",20,0));assertThrows(IllegalArgumentException.class,()->f.center.list("all","all",51,0));
    }}
    @Test void onlyPausedStandaloneDownloadsOfferDeletionAndDeletedRowsLeaveCountsAndPages()throws Exception{try(var f=new Fixture()){
        var paused=f.download(1);var queued=f.download(2);var failed=f.download(3);var completed=f.download(4);
        f.downloads.pause("1",paused.id());f.downloads.finishState("1",failed.id(),"FAILED","synthetic");f.downloads.finishState("1",completed.id(),"COMPLETED",null);
        var before=f.center.list("download","all",20,0);
        assertEquals(Set.of(paused.id()),new HashSet<>(before.items().stream().filter(row->row.actions().contains("delete")).map(Row::id).toList()));
        when(f.downloadTasks.delete(paused.id())).thenAnswer(call->{
            f.db.write(connection->{try(var insert=connection.prepareStatement("INSERT INTO download_task_deletions VALUES(?,'1',1,1)")){insert.setString(1,paused.id());insert.executeUpdate();}return null;});
            return new DownloadTaskService.Deleted(paused.id(),false);
        });
        var result=f.center.action(new Action("download",paused.id(),"delete")).get();assertNull(result.item());assertNull(result.warning());
        assertEquals(new DeletionReceipt("download",paused.id(),true),result.check());
        var after=f.center.list("download","all",20,0);assertEquals(before.total()-1,after.total());assertEquals(before.counts().waiting()-1,after.counts().waiting());
        assertFalse(after.items().stream().anyMatch(row->row.id().equals(paused.id())));
        assertEquals(Set.of(queued.id(),failed.id(),completed.id()),new HashSet<>(after.items().stream().map(Row::id).toList()));
        assertNotNull(f.downloads.byOperation("1",paused.operationId()));verify(f.downloadTasks,times(1)).delete(paused.id());
        when(f.telegram.accountId()).thenReturn("2");assertEquals(0,f.center.list("download","all",20,0).total());
    }}
    @Test void ruleChildrenAreExcludedFromStandaloneResults(){try(var f=new Fixture()){
        var child=f.download(1);String rule=UUID.randomUUID().toString();
        // A real binding is inserted with the repository schema; the child keeps its immutable task.
        f.db.write(c->{try(var q=c.prepareStatement("INSERT INTO download_rules VALUES(?,?,?,'fp',1,1,0,0,1,1)")){q.setString(1,rule);q.setString(2,"1");q.setString(3,UUID.randomUUID().toString());q.executeUpdate();}
            try(var q=c.prepareStatement("INSERT INTO download_rule_versions VALUES(?,1,'{}','-1001','channel',1)")){q.setString(1,rule);q.executeUpdate();}
            try(var q=c.prepareStatement("INSERT INTO download_rule_tasks(task_id,rule_id,account_id,rule_version,snapshot_json,manual,held,created_at) VALUES(?,?,?,1,'{}',0,0,1)")){q.setString(1,child.id());q.setString(2,rule);q.setString(3,"1");q.executeUpdate();}return null;});
        when(f.ruleTasks.list()).thenReturn(new DownloadRuleTaskService.Listing(List.of(new DownloadRuleTaskService.Group(rule,"rule","channel","out",1,false,new RuleModels.Window("ALL_DAY",null,null,"Asia/Shanghai"),"PAUSED",1,1,0,0,0,1,0,37,0,0,1,0))));
        var page=f.center.list("download","all",20,0);assertEquals(1,page.total());assertEquals("rule-download",page.items().getFirst().category());assertEquals(rule,page.items().getFirst().id());
    }}
    @Test void pauseAllPersistsPartialReceiptsNeverReplaysAndStopsProducersBeforeQueues(){try(var f=new Fixture()){
        var task=f.download(1);var input=new RuleModels.Input("rule","https://t.me/synthetic/1","source","archive",false,"EMPTY",new RuleModels.Window("ALL_DAY",null,null,"Asia/Shanghai"));
        String rule=UUID.randomUUID().toString();var model=new RuleModels.Rule(rule,"1",1,3,true,0,1,2,input,null);when(f.rules.list()).thenReturn(new RuleModels.Listing(List.of(new RuleModels.View(model,"OBSERVE_ONLY","OBSERVING",false,0,null)),"ALL",1));
        when(f.rules.toggle(eq(rule),any())).thenThrow(new IllegalArgumentException("changed revision"));when(f.downloadTasks.pause(task.id())).thenAnswer(call->f.downloads.view(f.downloads.pause("1",task.id())));
        String operation=UUID.randomUUID().toString();var receipt=f.center.pauseAll(new PauseRequest(operation));assertEquals("COMPLETED",receipt.state());assertEquals(1,receipt.accepted().size());assertEquals(1,receipt.failed().size());assertTrue(receipt.warnings().stream().anyMatch(w->w.contains("不可中断")));
        assertEquals(receipt,f.center.receipt(operation));assertEquals(receipt,f.center.pauseAll(new PauseRequest(operation)));verify(f.rules,times(1)).toggle(eq(rule),any());verify(f.downloadTasks,times(1)).pause(task.id());
        var order=inOrder(f.rules,f.downloadTasks);order.verify(f.rules).toggle(eq(rule),any());order.verify(f.downloadTasks).pause(task.id());
        when(f.telegram.accountId()).thenReturn("2");assertNull(f.center.receipt(operation));
    }}
    @Test void interruptedReceiptIsVisibleAsNeedsReviewAndDoesNotReplay(){try(var f=new Fixture()){
        String operation=UUID.randomUUID().toString();var receipt=new PauseReceipt(operation,"RUNNING",List.of(),List.of(),List.of(new Outcome("download",UUID.randomUUID().toString(),"pause",null)),List.of("scan cannot pause"),1,2);
        f.db.write(c->{try(var q=c.prepareStatement("INSERT INTO app_settings VALUES(?,?,?)")){q.setString(1,"transfer-center.pause."+DownloadPaths.hash("1")+"."+operation);q.setString(2,tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(receipt));q.setLong(3,2);q.executeUpdate();}return null;});
        assertEquals("NEEDS_REVIEW",f.center.receipt(operation).state());assertEquals("NEEDS_REVIEW",f.center.pauseAll(new PauseRequest(operation)).state());verifyNoInteractions(f.downloadTasks,f.uploadTasks);
    }}
    @Test void aRepeatedPauseOperationMustStillMatchTheCurrentSessionBeforeReturningItsReceipt(){try(var f=new Fixture()){
        var request=new PauseRequest(UUID.randomUUID().toString());f.center.pauseAll(request);
        when(f.telegram.sessionGeneration()).thenReturn(1L,2L);
        assertThrows(IllegalArgumentException.class,()->f.center.pauseAll(request));verifyNoInteractions(f.downloadTasks,f.uploadTasks);
    }}
    @Test void uploadPrimaryActionsNeverOfferStartForUncertainIntentAndExposeArchiveOnlyRecovery()throws Exception{try(var source=new UploadTestFixture(root.resolve("source-fixture"),1);var f=new Fixture()){
        var task=f.uploads.create("1",UUID.randomUUID().toString(),"upload",source.prepared);long bytes=task.groups().getFirst().items().getFirst().file().size();
        when(f.uploadTasks.progress(task.id())).thenReturn(new UploadTaskModels.Progress(bytes,0,null,null,null,0,1));
        var ready=f.center.list("upload","all",20,0).items().getFirst();assertTrue(ready.actions().contains("start"));assertTrue(ready.actions().contains("cancel"));
        f.db.write(c->{try(var q=c.prepareStatement("UPDATE upload_tasks SET state='PAUSED' WHERE id=?")){q.setString(1,task.id());q.executeUpdate();}try(var q=c.prepareStatement("UPDATE upload_groups SET state='UNCERTAIN' WHERE task_id=?")){q.setString(1,task.id());q.executeUpdate();}return null;});
        var uncertain=f.center.list("upload","all",20,0).items().getFirst();assertFalse(uncertain.actions().contains("start"));assertFalse(uncertain.actions().contains("cancel"));assertTrue(uncertain.actions().contains("reconcile"));assertEquals("WAITING_RECEIPT",uncertain.phase());
        f.db.write(c->{try(var q=c.prepareStatement("UPDATE upload_groups SET state='CONFIRMED' WHERE task_id=?")){q.setString(1,task.id());q.executeUpdate();}try(var q=c.prepareStatement("UPDATE upload_items SET state='SENT',formal_id='123' WHERE task_id=?")){q.setString(1,task.id());q.executeUpdate();}return null;});
        var archive=f.center.list("upload","all",20,0).items().getFirst();assertFalse(archive.actions().contains("start"));assertTrue(archive.actions().contains("reconcile"));assertEquals("ARCHIVING",archive.phase());verifyNoInteractions(f.retries);
    }}
    @Test void scanHistoryReplacesItsLiveProjectionAndGlobalPauseListsSupportedAndNoninterruptibleWork()throws Exception{try(var f=new Fixture()){
        var history=new ScanTaskStore(f.db,f.telegram);f.center.trackScans(history);long now=System.currentTimeMillis();String nativeScan=null;
        for(String category:List.of("channel-scan","preparation","import","rule-preparation")){String source=UUID.randomUUID().toString();if(category.equals("channel-scan"))nativeScan=source;boolean controllable=!category.equals("rule-preparation");history.remember("1",1,new ScanTaskStore.Snapshot(source,category,"Synthetic",category,controllable?"WAITING_STEP":"RUNNING",now,now,0,3,0,"SCANNING",source,null,null,17,controllable?now+600000:0,controllable,true),true);}
        when(f.scans.snapshot()).thenReturn(List.of(new DownloadChannelModels.View(nativeScan,"1",new io.github.telegramenhance.library.ChannelImportModels.ManagedChannel("-1001","Synthetic"),List.of(),List.of(),17,3,0,0,List.of(),false,true,false,false,null,List.of(),now,now+600000)));
        var page=f.center.list("scan","all",20,0);assertEquals(4,page.total(),"native and historical forms of one live scan are not counted twice");assertTrue(page.items().stream().noneMatch(row->row.id().equals(row.detailId())));
        var receipt=f.center.pauseAll(new PauseRequest(UUID.randomUUID().toString()));assertEquals(3,receipt.accepted().size());assertEquals(1,receipt.failed().size());assertEquals("rule-preparation",receipt.failed().getFirst().category());assertTrue(receipt.failed().getFirst().message().contains("不可中断"));
        var paused=f.center.list("scan","all",20,0);assertEquals(3,paused.items().stream().filter(row->row.state().equals("PAUSED")&&row.actions().equals(List.of("resume"))).count());var resume=paused.items().stream().filter(row->row.category().equals("channel-scan")).findFirst().orElseThrow();
        var result=f.center.action(new Action(resume.category(),resume.id(),"resume")).get();var restored=(ScanTaskStore.View)result.item();assertEquals(17,restored.revision());assertEquals("WAITING_STEP",restored.state());verify(f.scans,never()).next(anyString(),any());verify(f.batches,never()).next(anyString(),any());verify(f.preparations,never()).start(anyString(),any());
    }}
}
