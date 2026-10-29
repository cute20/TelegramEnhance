package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.DownloadBatchModels.*;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadBatchAggregateRepositoryTest {
    @TempDir Path directory;

    @Test void mixedStatesAggregateOnlyCommittedSuccessesAndExactSqlByteCounters() {
        try (Fixture f = fixture()) {
            var batch = f.mixed(); var status = f.repository.batchStatus("1", batch.id());
            assertEquals(batch.snapshot(), status.snapshot()); assertEquals("RUNNING", status.state());
            assertEquals(5, status.totalTasks()); assertEquals(1, status.completedTasks()); assertEquals(1, status.runningTasks());
            assertEquals(1, status.queuedTasks()); assertEquals(1, status.pausedTasks()); assertEquals(1, status.failedTasks());
            assertEquals(2, status.completed()); assertEquals(1, status.skipped()); assertEquals(1, status.duplicates());
            assertEquals(500, status.downloadedBytes()); assertEquals(275, status.writtenBytes());
            assertEquals(8, status.snapshot().total()); assertEquals(800, status.snapshot().totalBytes());
            assertEquals(2000, status.createdAt()); assertEquals(2050, status.updatedAt()); assertTrue(status.canPause()); assertTrue(status.canResume());
            assertEquals(batch.taskIds(), status.tasks().stream().map(TaskSummary::id).toList());
            var first = status.tasks().getFirst(); assertEquals("post-100", first.title()); assertEquals(3, first.total());
            assertEquals(250, first.downloadedBytes()); assertEquals(140, first.writtenBytes()); assertEquals(300, first.totalBytes());
            assertThrows(UnsupportedOperationException.class, () -> status.tasks().clear());
            assertFalse(status.toString().contains(f.output.toString())); assertFalse(first.toString().contains(first.title()));
            assertFalse(Files.exists(f.output));
        }
    }

    @Test void pausingABatchOnlyChangesQueuedAndRunningTasksAndKeepsTheRunningWorkerOwnedState() {
        try (Fixture f = fixture()) {
            var batch = f.mixed(); var items = f.rows("SELECT * FROM download_items ORDER BY item_id");
            var plans = f.rows("SELECT * FROM download_output_plans ORDER BY item_id"); var logs = f.rows("SELECT * FROM download_completion_log ORDER BY item_id");
            var failed = f.taskRow(batch.taskIds().get(2)); var completed = f.taskRow(batch.taskIds().get(3)); var paused = f.taskRow(batch.taskIds().get(4));
            var status = f.repository.pauseBatch("1", batch.id());
            assertEquals("PAUSING", status.state()); assertFalse(status.canPause()); assertFalse(status.canResume());
            assertEquals("RUNNING", status.tasks().getFirst().state()); assertTrue(status.tasks().getFirst().pauseRequested());
            assertEquals("PAUSED", status.tasks().get(1).state()); assertTrue(status.tasks().get(1).pauseRequested());
            assertEquals(failed, f.taskRow(batch.taskIds().get(2))); assertEquals(completed, f.taskRow(batch.taskIds().get(3))); assertEquals(paused, f.taskRow(batch.taskIds().get(4)));
            assertEquals(items, f.rows("SELECT * FROM download_items ORDER BY item_id")); assertEquals(plans, f.rows("SELECT * FROM download_output_plans ORDER BY item_id"));
            assertEquals(logs, f.rows("SELECT * FROM download_completion_log ORDER BY item_id"));
            var taskRows = f.rows("SELECT * FROM download_tasks ORDER BY task_id");
            assertEquals(status, f.repository.pauseBatch("1", batch.id())); assertEquals(taskRows, f.rows("SELECT * FROM download_tasks ORDER BY task_id"));
        }
    }

    @Test void aPauseInFlightRejectsResumeBeforeClearingAnyPausedOrFailedRows() {
        try (Fixture f = fixture()) {
            var batch = f.mixed(); f.repository.pauseBatch("1", batch.id());
            var tasks = f.rows("SELECT * FROM download_tasks ORDER BY task_id"); var items = f.rows("SELECT * FROM download_items ORDER BY item_id");
            assertThrows(IllegalArgumentException.class, () -> f.repository.resumeBatch("1", batch.id()));
            assertEquals(tasks, f.rows("SELECT * FROM download_tasks ORDER BY task_id")); assertEquals(items, f.rows("SELECT * FROM download_items ORDER BY item_id"));
        }
    }

    @Test void resumingOnlyQueuesPausedOrFailedChildrenWithoutResettingEvidenceOrCompletedItems() {
        try (Fixture f = fixture()) {
            var batch = f.mixed(); f.setTask(batch.taskIds().get(1), "PAUSED", true, "paused error", 2010);
            var running = f.taskRow(batch.taskIds().getFirst()); var completeTask = f.taskRow(batch.taskIds().get(3));
            var evidence = f.rows("SELECT * FROM download_output_plans ORDER BY item_id");
            var receipts = f.rows("SELECT * FROM download_completion_log ORDER BY item_id");
            var progress = f.rows("SELECT item_id,state,downloaded_bytes,written_bytes,sha256,relative_file,owner_token,history_id,spec_json FROM download_items ORDER BY item_id");
            f.sql("UPDATE download_items SET error_message='success sentinel' WHERE state IN ('COMPLETED','SKIPPED_HISTORY')");
            var completedItems = f.rows("SELECT * FROM download_items WHERE state IN ('COMPLETED','SKIPPED_HISTORY') ORDER BY item_id");
            var status = f.repository.resumeBatch("1", batch.id());
            assertEquals("RUNNING", status.state()); assertEquals(3, status.queuedTasks()); assertEquals(0, status.failedTasks()); assertEquals(0, status.pausedTasks());
            for (int index : List.of(1, 2, 4)) { assertEquals("QUEUED", status.tasks().get(index).state()); assertFalse(status.tasks().get(index).pauseRequested()); assertNull(status.tasks().get(index).error()); }
            assertEquals(running, f.taskRow(batch.taskIds().getFirst())); assertEquals(completeTask, f.taskRow(batch.taskIds().get(3)));
            assertEquals(progress, f.rows("SELECT item_id,state,downloaded_bytes,written_bytes,sha256,relative_file,owner_token,history_id,spec_json FROM download_items ORDER BY item_id"));
            assertEquals(completedItems, f.rows("SELECT * FROM download_items WHERE state IN ('COMPLETED','SKIPPED_HISTORY') ORDER BY item_id"));
            assertEquals(0, f.number("SELECT COUNT(*) FROM download_items WHERE state NOT IN ('COMPLETED','SKIPPED_HISTORY') AND task_id IN (?,?,?) AND error_message IS NOT NULL",batch.taskIds().get(1),batch.taskIds().get(2),batch.taskIds().get(4)));
            assertEquals(evidence, f.rows("SELECT * FROM download_output_plans ORDER BY item_id")); assertEquals(receipts, f.rows("SELECT * FROM download_completion_log ORDER BY item_id"));
        }
    }

    @Test void aSecondChildUpdateFailureRollsBackBothPauseAndResumeTransactions() {
        try (Fixture f = fixture()) {
            var batch = f.batch("1", 1, 1);
            var before = f.rows("SELECT * FROM download_tasks ORDER BY task_id");
            f.sql("CREATE TRIGGER reject_batch_pause BEFORE UPDATE ON download_tasks WHEN NEW.task_id='" + batch.taskIds().get(1) + "' BEGIN SELECT RAISE(ABORT,'synthetic pause failure'); END");
            assertThrows(IllegalStateException.class, () -> f.repository.pauseBatch("1", batch.id())); assertEquals(before, f.rows("SELECT * FROM download_tasks ORDER BY task_id"));
            f.sql("DROP TRIGGER reject_batch_pause"); f.repository.pauseBatch("1", batch.id());
            f.sql("UPDATE download_items SET error_message='keep on rollback'");
            before = f.rows("SELECT * FROM download_tasks ORDER BY task_id"); var items = f.rows("SELECT * FROM download_items ORDER BY item_id");
            f.sql("CREATE TRIGGER reject_batch_resume BEFORE UPDATE ON download_tasks WHEN NEW.task_id='" + batch.taskIds().get(1) + "' BEGIN SELECT RAISE(ABORT,'synthetic resume failure'); END");
            assertThrows(IllegalStateException.class, () -> f.repository.resumeBatch("1", batch.id()));
            assertEquals(before, f.rows("SELECT * FROM download_tasks ORDER BY task_id")); assertEquals(items, f.rows("SELECT * FROM download_items ORDER BY item_id"));
        }
    }

    @Test void aSessionGuardFailureBeforeCommitRollsBackAllTasksAndItemErrorChanges() {
        try (Fixture f = fixture()) {
            var batch = f.batch("1", 1, 1); var before = f.rows("SELECT * FROM download_tasks ORDER BY task_id");
            var checks = new AtomicInteger();
            assertThrows(IllegalArgumentException.class, () -> f.repository.pauseBatch("1", batch.id(), () -> { if(checks.incrementAndGet()==2)throw new IllegalArgumentException("session changed"); }));
            assertEquals(2, checks.get()); assertEquals(before, f.rows("SELECT * FROM download_tasks ORDER BY task_id"));
            f.repository.pauseBatch("1", batch.id()); f.sql("UPDATE download_items SET error_message='keep guard errors'");
            before = f.rows("SELECT * FROM download_tasks ORDER BY task_id"); var items = f.rows("SELECT * FROM download_items ORDER BY item_id"); checks.set(0);
            assertThrows(IllegalArgumentException.class, () -> f.repository.resumeBatch("1", batch.id(), () -> { if(checks.incrementAndGet()==2)throw new IllegalArgumentException("session changed"); }));
            assertEquals(2, checks.get()); assertEquals(before, f.rows("SELECT * FROM download_tasks ORDER BY task_id")); assertEquals(items, f.rows("SELECT * FROM download_items ORDER BY item_id"));
        }
    }

    @Test void allCompletedControlsAreStrictNoOpsIncludingEveryTimestamp() {
        try (Fixture f = fixture()) {
            var batch = f.batch("1", 1, 1); for (String id : batch.taskIds()) { f.complete("1", id, 0, id.hashCode()%2==0?'a':'b'); f.setTask(id,"COMPLETED",false,null,123); }
            var done = f.repository.batchStatus("1", batch.id()); assertEquals("COMPLETED", done.state()); assertFalse(done.canPause()); assertFalse(done.canResume());
            var tasks = f.rows("SELECT * FROM download_tasks ORDER BY task_id"); var items = f.rows("SELECT * FROM download_items ORDER BY item_id");
            f.sql("CREATE TRIGGER no_finished_task_updates BEFORE UPDATE ON download_tasks BEGIN SELECT RAISE(ABORT,'completed tasks must not change'); END");
            f.sql("CREATE TRIGGER no_finished_item_updates BEFORE UPDATE ON download_items BEGIN SELECT RAISE(ABORT,'completed items must not change'); END");
            assertEquals(done, f.repository.pauseBatch("1", batch.id())); assertEquals(done, f.repository.resumeBatch("1", batch.id()));
            assertEquals(tasks, f.rows("SELECT * FROM download_tasks ORDER BY task_id")); assertEquals(items, f.rows("SELECT * FROM download_items ORDER BY item_id"));
        }
    }

    @Test void controlsWithoutAnyApplicableChildLeaveFailedPausedOrQueuedRowsUntouched() {
        try (Fixture f = fixture()) {
            var batch = f.batch("1", 1); String id = batch.taskIds().getFirst();
            f.setTask(id,"FAILED",false,"keep failure",123); var failed = f.repository.batchStatus("1",batch.id());
            assertEquals(failed, f.repository.pauseBatch("1",batch.id())); assertEquals(123, f.repository.batchStatus("1",batch.id()).tasks().getFirst().updatedAt());
            f.setTask(id,"QUEUED",false,null,456); var queued = f.repository.batchStatus("1",batch.id());
            assertEquals(queued, f.repository.resumeBatch("1",batch.id())); assertEquals(456, f.repository.batchStatus("1",batch.id()).tasks().getFirst().updatedAt());
        }
    }

    @Test void fullByteCountersAndFinalizingDoNotMeanCompleteUntilTheFormalReceiptIsCommitted() {
        try (Fixture f = fixture()) {
            var batch = f.batch("1", 1); String id = batch.taskIds().getFirst(); var task = f.repository.task("1",id); var item = task.items().getFirst();
            f.repository.progress("1",id,item.id(),"WRITING",100,100,"a".repeat(64),item.relativeFile());
            var plan = f.repository.outputPlan(f.repository.task("1",id),f.repository.task("1",id).items().getFirst(),"a".repeat(64));
            f.repository.finalizing(f.repository.task("1",id),f.repository.task("1",id).items().getFirst(),plan,plan.relativePath());
            f.setTask(id,"RUNNING",false,null,123); var writing = f.repository.batchStatus("1",batch.id());
            assertEquals("RUNNING",writing.state()); assertEquals(100,writing.downloadedBytes()); assertEquals(100,writing.writtenBytes()); assertEquals(0,writing.completed());
            assertEquals("PAUSING",f.repository.pauseBatch("1",batch.id()).state());
            // The old worker may validly publish its already verified final file before it sees
            // the pause request. The batch becomes complete only after that receipt and state.
            f.repository.complete("1",id,item.id(),item.relativeFile(),Path.of(task.targetRoot()).resolve(plan.relativePath()),"a".repeat(64));
            f.repository.finishState("1",id,"COMPLETED",null);
            var done = f.repository.batchStatus("1",batch.id()); assertEquals("COMPLETED",done.state()); assertEquals(1,done.completed());
            assertFalse(done.canPause()); assertFalse(done.canResume()); assertFalse(done.tasks().getFirst().pauseRequested());
        }
    }

    @Test void aCompletedTaskWithoutTerminalItemsOrItsOwnMatchingReceiptCannotClaimSuccess() {
        for(int kind=0;kind<4;kind++)try(Fixture f=new Fixture(directory.resolve("evidence-"+kind))){
            var batch=f.batch("1",1);String id=batch.taskIds().getFirst();
            if(kind!=0)f.complete("1",id,0,'a');
            f.setTask(id,"COMPLETED",false,null,123);
            if(kind==1)f.sql("DELETE FROM download_completion_log WHERE task_id=?",id);
            if(kind==2)f.sql("UPDATE download_completion_log SET account_id='other' WHERE task_id=?",id);
            if(kind==3)f.sql("DELETE FROM download_output_plans WHERE item_id IN(SELECT item_id FROM download_items WHERE task_id=?)",id);
            assertThrows(IllegalStateException.class,()->f.repository.batchStatus("1",batch.id()));
            assertThrows(IllegalStateException.class,()->f.repository.pauseBatch("1",batch.id()));
        }
    }

    @Test void missingMembersWrongAccountsAndFrozenTotalMismatchesFailInsteadOfReturningComplete() {
        for(int kind=0;kind<5;kind++)try(Fixture f=new Fixture(directory.resolve("membership-"+kind))){
            var batch=f.batch("1",1,1);
            if(kind==0)f.sql("DELETE FROM download_batch_tasks WHERE batch_id=? AND ordinal=1",batch.id());
            if(kind==1)f.sql("UPDATE download_tasks SET account_id='2' WHERE task_id=?",batch.taskIds().get(1));
            if(kind==2)f.sql("DELETE FROM download_items WHERE task_id=?",batch.taskIds().get(1));
            if(kind==3)f.sql("UPDATE download_batches SET snapshot_json=json_set(snapshot_json,'$.totalBytes',999) WHERE id=?",batch.id());
            if(kind==4)f.sql("UPDATE download_batches SET snapshot_json=json_set(snapshot_json,'$.total',3) WHERE id=?",batch.id());
            var before=f.rows("SELECT * FROM download_tasks ORDER BY task_id");
            assertThrows(IllegalStateException.class,()->f.repository.batchStatus("1",batch.id()));
            assertThrows(IllegalStateException.class,()->f.repository.resumeBatch("1",batch.id()));
            assertEquals(before,f.rows("SELECT * FROM download_tasks ORDER BY task_id"));
        }
    }

    @Test void accountScopingAppliesToHistoryAndEveryControlJoin() {
        try(Fixture f=fixture()){
            var first=f.batch("1",1);var second=f.batch("2",1);var before=f.rows("SELECT * FROM download_tasks ORDER BY task_id");
            assertEquals(List.of(first.id()),f.repository.batchStatuses("1",20,0).stream().map(BatchStatus::id).toList());
            assertEquals(List.of(second.id()),f.repository.batchStatuses("2",20,0).stream().map(BatchStatus::id).toList());
            assertThrows(IllegalArgumentException.class,()->f.repository.batchStatus("2",first.id()));
            assertThrows(IllegalArgumentException.class,()->f.repository.pauseBatch("2",first.id()));
            assertThrows(IllegalArgumentException.class,()->f.repository.resumeBatch("2",first.id()));
            assertEquals(before,f.rows("SELECT * FROM download_tasks ORDER BY task_id"));
            f.repository.pauseBatch("1",first.id());assertEquals("QUEUED",f.repository.batchStatus("2",second.id()).state());
        }
    }

    @Test void boundedHistoryUsesStablePaginationAndOnlyExtractsPostTitleWithoutDecodingItemPayloads() {
        try(Fixture f=fixture()){
            var batches=new ArrayList<Receipt>();for(int index=0;index<5;index++)batches.add(f.batch("1",1));
            f.sql("UPDATE download_batches SET created_at=1000 WHERE account_id='1'");
            f.sql("UPDATE download_items SET spec_json='invalid-private-file-json{' WHERE task_id IN(SELECT task_id FROM download_tasks WHERE account_id='1')");
            f.sql("UPDATE download_tasks SET post_json='{\"title\":\"frozen-only-title\",\"mainMessages\":\"not-a-Post-list\"}' WHERE account_id='1'");
            var expected=batches.stream().map(Receipt::id).sorted().toList();
            var first=f.repository.batchStatuses("1",2,0);var second=f.repository.batchStatuses("1",2,2);var last=f.repository.batchStatuses("1",2,4);
            var actual=new ArrayList<String>();for(var page:List.of(first,second,last))page.forEach(batch->actual.add(batch.id()));assertEquals(expected,actual);
            assertEquals("frozen-only-title",first.getFirst().tasks().getFirst().title());assertTrue(f.repository.batchStatuses("1",2,99).isEmpty());
            assertThrows(UnsupportedOperationException.class,()->first.clear());
            for(int limit:new int[]{0,-1,21,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->f.repository.batchStatuses("1",limit,0));
            assertThrows(IllegalArgumentException.class,()->f.repository.batchStatuses("1",10,-1));
            // A damaged later snapshot does not make an earlier bounded page decode the rest.
            f.sql("UPDATE download_batches SET snapshot_json='invalid-later-snapshot{' WHERE id=?",expected.getLast());
            assertEquals(2,f.repository.batchStatuses("1",2,0).size());
        }
    }

    @Test void reopeningOnlyPausesExistingChildrenAndAggregateStatusIsDerivedFromTheirPersistedRows() {
        AppPaths paths=new AppPaths(directory.resolve("restart-data"));Receipt batch;List<List<Object>> items;
        try(Database database=new Database(paths)){
            var repository=new DownloadRepository(database);batch=create(repository,"1",directory.resolve("out").toAbsolutePath(),2);
            var task=repository.next("1");var item=task.items().getFirst();repository.progress("1",task.id(),item.id(),"WRITING",100,37,"a".repeat(64),item.relativeFile());
            items=rows(database,"SELECT * FROM download_items ORDER BY item_id");
        }
        try(Database database=new Database(paths)){
            var repository=new DownloadRepository(database);var status=repository.batchStatus("1",batch.id());
            assertEquals("PAUSED",status.state());assertEquals(1,status.pausedTasks());assertEquals(37,status.writtenBytes());assertTrue(status.canResume());
            assertEquals(items,rows(database,"SELECT * FROM download_items ORDER BY item_id"));assertEquals(batch,repository.batchByOperation("1",operation(database,batch.id()),null));
        }
    }

    private Fixture fixture(){return new Fixture(directory);}
    private static Receipt create(DownloadRepository repository,String account,Path root,int... counts){
        var jobs=new ArrayList<Job>();int total=0;
        for(int index=0;index<counts.length;index++){
            String message=Integer.toString(100+index);var ref=new MessageRef("-1001",message,"");
            var post=new Post(account+":-1001:"+message,account,"-1001","channel",message,"post-"+message,"body","0",List.of(ref),List.of(),null,null,List.of(),false,"UNAVAILABLE",List.of());
            var files=new ArrayList<FrozenFile>();for(int slot=0;slot<counts[index];slot++){
                var media=new Media(account+":-1001:"+message+":"+slot,ref,"MAIN","DOCUMENT","file-"+slot+".bin",100,"");
                files.add(new FrozenFile(media,"uid-"+media.key(),100,media.fileName()));total++;
            }jobs.add(new Job(post,files));
        }
        var snapshot=new Snapshot(UUID.randomUUID().toString(),3,"channel",List.of(),List.of(),List.of("DOCUMENT"),true,root.toString(),counts.length,total,total*100L);
        return repository.createBatch(account,UUID.randomUUID().toString(),"fingerprint-"+UUID.randomUUID(),snapshot,root,jobs);
    }
    private static String operation(Database database,String batch){return database.read(c->{try(var q=c.prepareStatement("SELECT operation_id FROM download_batches WHERE id=?")){q.setString(1,batch);try(var r=q.executeQuery()){assertTrue(r.next());return r.getString(1);}}});}
    private static List<List<Object>> rows(Database database,String sql,Object... parameters){return database.read(c->{
        var result=new ArrayList<List<Object>>();try(var q=c.prepareStatement(sql)){for(int index=0;index<parameters.length;index++)q.setObject(index+1,parameters[index]);try(var r=q.executeQuery()){while(r.next()){var row=new ArrayList<Object>();for(int column=1;column<=r.getMetaData().getColumnCount();column++)row.add(r.getObject(column));result.add(row);}}}return result;
    });}
    private static final class Fixture implements AutoCloseable{
        final Database database;final DownloadRepository repository;final Path output;
        Fixture(Path directory){database=new Database(new AppPaths(directory.resolve("data")));repository=new DownloadRepository(database);output=directory.resolve("out").toAbsolutePath();}
        Receipt batch(String account,int... counts){return create(repository,account,output,counts);}
        void complete(String account,String id,int ordinal,char hashCharacter){
            var task=repository.task(account,id);var item=task.items().get(ordinal);String hash=String.valueOf(hashCharacter).repeat(64);
            repository.progress(account,id,item.id(),"WRITING",100,100,hash,item.relativeFile());task=repository.task(account,id);item=task.items().get(ordinal);
            var plan=repository.outputPlan(task,item,hash);repository.complete(account,id,item.id(),item.relativeFile(),Path.of(task.targetRoot()).resolve(plan.relativePath()),hash);
        }
        Receipt mixed(){
            var batch=batch("1",3,1,1,2,1);var ids=batch.taskIds();complete("1",ids.getFirst(),0,'a');complete("1",ids.get(3),0,'a');
            var skip=repository.task("1",ids.get(3)).items().get(1);String history=UUID.randomUUID().toString();
            sql("INSERT INTO download_history VALUES(?,?,?,?,?,?,?,?)",history,"1",skip.file().uniqueId(),100,"a".repeat(64),ids.getFirst(),"historic-file",1);
            sql("UPDATE download_items SET state='SKIPPED_HISTORY',history_id=? WHERE item_id=?",history,skip.id());
            item(ids.getFirst(),1,"DOWNLOADING",50,0);item(ids.getFirst(),2,"WRITING",100,40);item(ids.get(2),0,"WRITING",100,25);item(ids.get(4),0,"WRITING",50,10);
            setTask(ids.getFirst(),"RUNNING",false,"running error",2010);setTask(ids.get(1),"QUEUED",false,"queued error",2020);
            setTask(ids.get(2),"FAILED",false,"failed error",2030);setTask(ids.get(3),"COMPLETED",false,null,2040);setTask(ids.get(4),"PAUSED",true,"paused error",2050);
            sql("UPDATE download_batches SET created_at=2000 WHERE id=?",batch.id());return batch;
        }
        void item(String task,int ordinal,String state,long downloaded,long written){sql("UPDATE download_items SET state=?,downloaded_bytes=?,written_bytes=?,sha256=?,error_message='item error' WHERE task_id=? AND ordinal=?",state,downloaded,written,"b".repeat(64),task,ordinal);}
        void setTask(String id,String state,boolean pause,String error,long stamp){sql("UPDATE download_tasks SET state=?,pause_requested=?,error_message=?,updated_at=? WHERE task_id=?",state,pause,error,stamp,id);}
        void sql(String sql,Object... parameters){database.write(c->{try(var q=c.prepareStatement(sql)){for(int index=0;index<parameters.length;index++)q.setObject(index+1,parameters[index]);q.executeUpdate();}return null;});}
        List<List<Object>> taskRow(String id){return rows("SELECT * FROM download_tasks WHERE task_id=?",id);}
        List<List<Object>> rows(String sql,Object... parameters){return DownloadBatchAggregateRepositoryTest.rows(database,sql,parameters);}
        long number(String sql,Object... parameters){return ((Number)rows(sql,parameters).getFirst().getFirst()).longValue();}
        @Override public void close(){database.close();}
    }
}
