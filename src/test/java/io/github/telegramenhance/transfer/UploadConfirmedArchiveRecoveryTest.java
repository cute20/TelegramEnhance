package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Local fixtures only; native messages and all sending APIs are mocks. */
class UploadConfirmedArchiveRecoveryTest {
    @TempDir Path root;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void aLateCompleteReceiptQueuesOnlyArchiveWorkForTheSameAccount() throws Exception {
        try (var f = new UploadTestFixture(root, 2)) {
            f.prepareAndIntent(); var task = f.fresh(); var group = task.groups().getFirst();
            f.repository.initial(task, group, List.of(new Initial("100", Math.toIntExact(group.id()), true, null), new Initial("101", Math.toIntExact(group.id()), true, null)));
            f.repository.event(group.id(), f.proof(group.items().getFirst(), group, "1000"));
            f.repository.uncertain(group, "receipt deadline"); f.repository.state(task, "NEEDS_REVIEW", "receipt deadline");
            assertNull(f.repository.next("1")); assertEquals("NEEDS_REVIEW", f.fresh().state());
            f.repository.event(group.id(), f.proof(group.items().get(1), group, "1001"));
            assertEquals("CONFIRMED", f.fresh().groups().getFirst().state()); assertEquals("NEEDS_REVIEW", f.fresh().state());
            assertNull(f.repository.next("2")); assertEquals("NEEDS_REVIEW", f.fresh().state());
            var recovered = f.repository.next("1"); assertEquals(task.id(), recovered.id()); assertEquals("RUNNING", recovered.state());
            assertTrue(recovered.stopRequested()); assertEquals(task.prepared(), recovered.prepared());
            assertEquals(2, recovered.groups().getFirst().items().stream().filter(i -> i.formalId() != null).count());
        }
    }

    @Test void fullyConfirmedLateAlbumArchivesAutomaticallyWithoutCallingAnySendOrNativeTargetApi() throws Exception {
        try (var f = new UploadTestFixture(root, 2)) {
            f.confirm(); var before = f.fresh(); var receipts = events(f); var formal = formalIds(before);
            f.repository.state(before, "NEEDS_REVIEW", "deadline exceeded before final receipts arrived");
            try (var engine = new Engine(f)) {
                await(() -> f.fresh().state().equals("COMPLETED"));
                assertEquals(2, f.repository.view(f.fresh()).archived()); assertTrue(f.fresh().stopRequested());
                assertEquals(before.prepared(), f.fresh().prepared()); assertEquals(formal, formalIds(f.fresh())); assertEquals(receipts, events(f));
                assertTrue(Files.exists(f.archive.resolve("file1.bin"))); assertFalse(Files.exists(f.source.resolve("file1.bin")));
                engine.noSendOrNativeReads();
            }
        }
    }

    @Test void recoveredAlbumNeverSendsTheReadyFollowingGroup() throws Exception {
        try (var f = new UploadTestFixture(root, 11)) {
            f.confirm(); var before = f.fresh(); f.repository.state(before, "NEEDS_REVIEW", "late first album");
            try (var engine = new Engine(f)) {
                await(() -> f.fresh().state().equals("PAUSED"));
                assertEquals(10, f.repository.view(f.fresh()).archived()); assertTrue(f.fresh().stopRequested());
                assertEquals("READY", f.fresh().groups().get(1).state()); assertTrue(Files.exists(f.source.resolve("file11.bin")));
                assertEquals(before.prepared(), f.fresh().prepared()); engine.noSendOrNativeReads();
            }
        }
    }

    @Test void aPausedRuleCannotBeEnabledOrUsedToGatePreviouslyConfirmedArchiveWork() throws Exception {
        try (var f = new UploadTestFixture(root, 2)) {
            f.confirm(); f.repository.state(f.fresh(), "NEEDS_REVIEW", "late receipts after rule pause");
            var policy = mock(io.github.telegramenhance.rules.RuleUploadPolicy.class);
            doThrow(new io.github.telegramenhance.rules.RuleUploadPolicy.Blocked("RULE", "rule remains paused"))
                    .when(policy).checkStart(any(), any());
            var repository = new UploadRepository(f.database, policy);
            try (var engine = new Engine(f, f.files, repository)) {
                await(() -> f.fresh().state().equals("COMPLETED")); assertTrue(f.fresh().stopRequested());
                verify(policy, never()).checkStart(any(), any()); verify(policy, never()).clearPause(any(), any());
                engine.noSendOrNativeReads();
            }
        }
    }

    @Test void missingOrContradictoryDurableProofCannotBeAutoQueuedEvenIfFormalIdsWereAlreadyStored() throws Exception {
        for (String change : List.of("missing", "old-id", "formal-id", "thread", "caption", "type", "failure", "uid", "album", "item-state", "temporary")) {
            try (var f = new UploadTestFixture(root.resolve(change), 2)) {
                f.confirm(); var task = f.fresh(); var group = task.groups().getFirst(); var item = group.items().get(1);
                f.repository.state(task, "NEEDS_REVIEW", "must remain reviewed");
                if (change.equals("missing")) sql(f, "DELETE FROM upload_events WHERE group_id=? AND old_id=?", group.id(), item.temporaryId());
                else if (change.equals("item-state")) sql(f, "UPDATE upload_items SET state='FAILED' WHERE id=?", item.id());
                else if (change.equals("temporary")) sql(f, "UPDATE upload_items SET temporary_id=NULL WHERE id=?", item.id());
                else {
                    var proof = f.repository.receipt(group.id(), item.temporaryId());
                    var altered = new Evidence(change.equals("old-id") ? "999" : proof.oldId(), change.equals("formal-id") ? "9999" : proof.formalId(),
                            proof.chatId(), change.equals("thread") ? "999" : proof.threadId(), proof.replyId(), change.equals("album") ? "78" : proof.albumId(),
                            change.equals("type") ? "VIDEO" : proof.mediaType(), change.equals("caption") ? "changed" : proof.caption(),
                            change.equals("uid") ? "other-uid" : proof.uniqueId(), proof.outgoing(), !change.equals("failure"), proof.date());
                    sql(f, "UPDATE upload_events SET event_json=? WHERE group_id=? AND old_id=?", JSON.writeValueAsString(altered), group.id(), item.temporaryId());
                }
                assertNull(f.repository.next("1"), change); assertEquals("NEEDS_REVIEW", f.fresh().state(), change);
                assertEquals(0, f.repository.view(f.fresh()).archived(), change); assertTrue(Files.exists(f.source.resolve("file1.bin")), change);
            }
        }
    }

    @Test void uncertainGroupsPendingRetriesAndAlreadyArchivedAlbumsDoNotTriggerRecovery() throws Exception {
        for (String blocked : List.of("uncertain", "all-archived", "retry")) try (var f = new UploadTestFixture(root.resolve(blocked), 2)) {
            f.confirm(); var task = f.fresh(); var group = task.groups().getFirst();
            f.repository.state(task, "NEEDS_REVIEW", "must remain reviewed");
            if (blocked.equals("uncertain")) sql(f, "UPDATE upload_groups SET state='UNCERTAIN' WHERE id=?", group.id());
            else if (blocked.equals("all-archived")) sql(f, "UPDATE upload_items SET state='ARCHIVED' WHERE group_id=?", group.id());
            else {
                var item = group.items().getFirst();
                // Only a persisted active retry flag is needed; this test never dispatches it.
                sql(f, "INSERT INTO upload_retry_attempts(id,task_id,group_id,item_id,operation_id,check_id,prior_temporary_id,failed_id,new_temporary_id,state,owner_run,created_at,updated_at,error) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',?,?,?,NULL)",
                        UUID.randomUUID().toString(), task.id(), group.id(), item.id(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), item.temporaryId(), "999", null, "fixture-run", 1L, 1L);
            }
            assertNull(f.repository.next("1"), blocked); assertEquals("NEEDS_REVIEW", f.fresh().state(), blocked);
        }
    }

    @Test void archiveFailureStaysPendingAndIsNeverAutomaticallyQueuedAgain() throws Exception {
        try (var f = new UploadTestFixture(root, 2)) {
            f.confirm(); f.repository.state(f.fresh(), "NEEDS_REVIEW", "late receipts");
            var failingFiles = mock(UploadFiles.class);
            doThrow(new java.io.IOException("synthetic locked source")).when(failingFiles).archive(any(), any(), any());
            try (var engine = new Engine(f, failingFiles)) {
                await(() -> f.fresh().state().equals("SENT_PENDING_ARCHIVE"));
                verify(failingFiles, times(1)).archive(any(), any(), any());
                for (int n = 0; n < 5; n++) assertNull(f.repository.next("1"));
                assertEquals("SENT_PENDING_ARCHIVE", f.fresh().state()); assertTrue(Files.exists(f.source.resolve("file1.bin")));
                engine.noSendOrNativeReads();
            }
        }
    }

    private static List<String> formalIds(Task task) { return task.groups().stream().flatMap(g -> g.items().stream()).map(Item::formalId).toList(); }
    private static List<String> events(UploadTestFixture f) {
        return f.database.read(c -> { var out = new ArrayList<String>(); try (var q = c.prepareStatement("SELECT e.event_json FROM upload_events e JOIN upload_groups g ON g.id=e.group_id WHERE g.task_id=? ORDER BY e.old_id")) {
            q.setString(1, f.task.id()); try (var r = q.executeQuery()) { while (r.next()) out.add(r.getString(1)); }
        } return out; });
    }
    private static void sql(UploadTestFixture f, String statement, Object... args) {
        f.database.write(c -> { try (var q = c.prepareStatement(statement)) { for (int n = 0; n < args.length; n++) q.setObject(n + 1, args[n]); q.executeUpdate(); } return null; });
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean());
    }
    private static final class Engine implements AutoCloseable {
        final TelegramService telegram = mock(TelegramService.class); final UploadTransport transport = mock(UploadTransport.class);
        final UploadTargetReader targets = mock(UploadTargetReader.class); final ResourceService reads = mock(ResourceService.class); final UploadEngine engine;
        Engine(UploadTestFixture store) { this(store, store.files); }
        Engine(UploadTestFixture store, UploadFiles files) { this(store, files, store.repository); }
        Engine(UploadTestFixture store, UploadFiles files, UploadRepository repository) {
            when(telegram.accountId()).thenReturn("1"); when(telegram.sessionGeneration()).thenReturn(1L);
            engine = new UploadEngine(repository, files, transport, telegram, targets, reads);
        }
        void noSendOrNativeReads() { verify(transport, never()).send(any(), any(), anyLong()); verify(transport, never()).resendFailed(any(), any(), any(), any(), anyLong()); verify(transport, never()).recoverPending(any(), anyLong()); verifyNoInteractions(targets, reads); }
        @Override public void close() { engine.close(); }
    }
}
