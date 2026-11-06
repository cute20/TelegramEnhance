package io.github.telegramenhance.rules;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.telegramenhance.telegram.TelegramService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.ScanTaskStore;

class RulePreparationServiceTest {
    @TempDir Path directory;
    @Test void completedSingleOperationPreparationSurvivesItsEphemeralLookupTtlAsReadOnlyHistory(){try(var f=new Fixture();var db=new Database(new AppPaths(directory))){
        var history=new ScanTaskStore(db,f.telegram);f.service.trackScans(history);String operation=operation();f.service.start(RULE,new RulePreparationService.Request(operation,2,"observe"));var running=history.list().getFirst();assertEquals("RUNNING",running.state());assertTrue(running.actions().isEmpty());assertThrows(IllegalArgumentException.class,()->history.action(running.id(),"pause"));
        f.queue.runNext();f.observation.complete(f.view());assertEquals("COMPLETED",history.list().getFirst().state());f.time.advance(601);assertNull(f.service.lookup(RULE,operation));assertEquals("COMPLETED",history.list().getFirst().state());assertTrue(history.list().getFirst().actions().isEmpty());verify(f.rules,never()).toggle(anyString(),any());
    }}
    static final String RULE = "00000000-0000-4000-8000-000000000001";
    static final String OTHER = "00000000-0000-4000-8000-000000000002";
    static String operation() { return UUID.randomUUID().toString(); }

    @Test void postReturnsBeforeWorkAndRepeatedOperationOnlyReadsTheSameFrozenJob() {
        try (var f = new Fixture()) {
            String op = operation(); var input = new RulePreparationService.Request(op, 2, "observe");
            var first = f.service.start(RULE, input);
            assertEquals("RUNNING", first.state()); assertEquals(1, first.version()); assertEquals(2, first.revision());
            assertNull(first.finishedAt()); assertNull(first.error()); assertNull(first.result());
            verify(f.rules, never()).observe(anyString(), any(), any());
            assertEquals(first, f.service.start(RULE, input)); assertEquals(1, f.queue.size());
            f.queue.runNext(); f.observation.complete(f.view());
            var done = f.service.lookup(RULE, op); assertEquals("COMPLETED", done.state()); assertEquals(f.view(), done.result());
            assertNotNull(done.finishedAt()); assertNull(done.error()); assertEquals(done, f.service.start(RULE, input));
            verify(f.rules, times(1)).observe(eq(RULE), eq(new RuleService.Position(2)), any());
            verify(f.rules, never()).toggle(anyString(), any()); verify(f.rules, never()).monitorOnce(any());
        }
    }
    @Test void previewOnlyProducesAPreviewReceiptAndNeverEnablesOrCreatesUploads() {
        try (var f = new Fixture()) {
            String op = operation(); f.service.start(RULE, new RulePreparationService.Request(op, 2, "preview"));
            f.queue.runNext(); var result = new RuleModels.Preview(RULE, 1, 2, null); f.preview.complete(result);
            var done = f.service.lookup(RULE, op); assertEquals("COMPLETED", done.state()); assertEquals(result, done.result());
            verify(f.rules).preview(eq(RULE), eq(new RuleService.Position(2)), any());
            verify(f.rules, never()).observe(anyString(), any(), any()); verify(f.rules, never()).toggle(anyString(), any());
            verify(f.rules, never()).monitorOnce(any());
        }
    }
    @Test void malformedAndChangedOperationsAreRejectedBeforeAnyWorkAndUnknownLookupIsNull() {
        try (var f = new Fixture()) {
            assertThrows(IllegalArgumentException.class, () -> f.service.start(RULE, null));
            for (var request : List.of(new RulePreparationService.Request("invalid", 2, "observe"),
                    new RulePreparationService.Request(operation(), 0, "observe"), new RulePreparationService.Request(operation(), 2, "upload"),
                    new RulePreparationService.Request(operation(), 1, "observe")))
                assertThrows(IllegalArgumentException.class, () -> f.service.start(RULE, request));
            assertNull(f.service.lookup(RULE, operation())); assertEquals(0, f.queue.size());
            String op = operation(); f.service.start(RULE, new RulePreparationService.Request(op, 2, "observe"));
            assertThrows(IllegalArgumentException.class, () -> f.service.start(RULE, new RulePreparationService.Request(op, 2, "preview")));
            assertThrows(IllegalArgumentException.class, () -> f.service.start(OTHER, new RulePreparationService.Request(op, 2, "observe")));
            assertThrows(IllegalArgumentException.class, () -> f.service.lookup(OTHER, op)); assertEquals(1, f.queue.size());
        }
    }
    @Test void aSecondOperationForTheSameRuleAndKindCannotQueueAnotherScan() {
        try (var f = new Fixture()) {
            f.service.start(RULE, new RulePreparationService.Request(operation(), 2, "observe"));
            var error = assertThrows(IllegalArgumentException.class, () -> f.service.start(RULE, new RulePreparationService.Request(operation(), 2, "observe")));
            assertTrue(error.getMessage().contains("正在进行"));
            f.service.start(RULE, new RulePreparationService.Request(operation(), 2, "preview")); assertEquals(2, f.queue.size());
        }
    }
    @Test void synchronousAndAsynchronousFailuresRemainQueryableWithoutReplayingTheAction() {
        for (boolean synchronous : List.of(true, false)) try (var f = new Fixture()) {
            if (synchronous) when(f.rules.preview(anyString(), any(), any())).thenThrow(new IllegalArgumentException("目标不可用"));
            String op = operation(); f.service.start(RULE, new RulePreparationService.Request(op, 2, "preview")); f.queue.runNext();
            if (!synchronous) f.preview.completeExceptionally(new CompletionException(new IllegalArgumentException("目标不可用")));
            var failed = f.service.lookup(RULE, op); assertEquals("FAILED", failed.state()); assertEquals("目标不可用", failed.error());
            assertNull(failed.result()); assertNotNull(failed.finishedAt()); assertEquals(failed, f.service.lookup(RULE, op));
            verify(f.rules, times(1)).preview(eq(RULE), eq(new RuleService.Position(2)), any());
            verify(f.rules, never()).toggle(anyString(), any());
        }
    }
    @Test void queueTimeCountsTowardsTheHardBudgetAndLateCompletionCannotTurnAFailedJobIntoSuccess() {
        try (var f = new Fixture()) {
            String queued = operation(); f.service.start(RULE, new RulePreparationService.Request(queued, 2, "preview"));
            f.time.advance(12 * 60); f.queue.runNext();
            var expired = f.service.lookup(RULE, queued); assertEquals("FAILED", expired.state()); assertTrue(expired.error().contains("12分钟"));
            verify(f.rules, never()).preview(anyString(), any(), any());
        }
        try (var f = new Fixture()) {
            String op = operation(); f.service.start(RULE, new RulePreparationService.Request(op, 2, "observe")); f.queue.runNext();
            f.time.advance(12 * 60); var timedOut = f.service.lookup(RULE, op); assertEquals("FAILED", timedOut.state());
            assertThrows(IllegalArgumentException.class, f.guard.get()::run); assertFalse(f.observation.isCancelled());
            assertTrue(f.observation.complete(f.view())); assertEquals(timedOut, f.service.lookup(RULE, op));
            verify(f.rules, times(1)).observe(eq(RULE), any(), any()); verify(f.rules, never()).toggle(anyString(), any());
        }
    }
    @Test void theEightJobLimitAndTenMinuteFinishedTtlAreBoundedWithoutEvictingActiveJobs() {
        try (var f = new Fixture()) {
            var ids = new ArrayList<String>(); String active = operation();
            f.service.start(RULE, new RulePreparationService.Request(active, 2, "preview")); f.queue.runNext();
            for (int n = 0; n < 7; n++) {
                String op = operation(); ids.add(op); f.service.start(RULE, new RulePreparationService.Request(op, 2, "observe"));
                f.queue.runNext(); f.observation.complete(f.view());
            }
            assertThrows(IllegalArgumentException.class, () -> f.service.start(RULE, new RulePreparationService.Request(operation(), 2, "observe")));
            f.time.advance(599); assertNotNull(f.service.lookup(RULE, ids.getFirst())); f.time.advance(1);
            assertNull(f.service.lookup(RULE, ids.getFirst()));
            assertEquals("RUNNING", f.service.lookup(RULE, active).state());
            assertEquals("RUNNING", f.service.start(RULE, new RulePreparationService.Request(operation(), 2, "observe")).state());
        }
    }
    @Test void accountSessionAndRuleChangesRejectQueuedOrInFlightWork() {
        for (String change : List.of("account", "session", "revision", "version")) try (var f = new Fixture()) {
            String op = operation(); f.service.start(RULE, new RulePreparationService.Request(op, 2, "preview")); f.queue.runNext();
            switch (change) {
                case "account" -> when(f.telegram.accountId()).thenReturn("2");
                case "session" -> when(f.telegram.sessionGeneration()).thenReturn(2L);
                case "revision" -> f.current = rule(RULE, 1, 3);
                case "version" -> f.current = rule(RULE, 2, 2);
            }
            f.preview.complete(new RuleModels.Preview(RULE, 1, 2, null));
            if (change.equals("account")) assertNull(f.service.lookup(RULE, op));
            else assertThrows(IllegalArgumentException.class, () -> f.service.lookup(RULE, op));
            verify(f.rules, times(1)).preview(eq(RULE), any(), any()); verify(f.rules, never()).toggle(anyString(), any());
        }
    }
    @Test void closingTheBackendStopsQueuedPreparationsWithoutCancellingOrSending() {
        var f = new Fixture(); String op = operation(); f.service.start(RULE, new RulePreparationService.Request(op, 2, "observe"));
        f.service.close(); f.queue.runNext(); verify(f.rules, never()).observe(anyString(), any(), any());
        assertThrows(IllegalArgumentException.class, () -> f.service.lookup(RULE, op));
    }

    static RuleModels.Rule rule(String id, long version, long revision) {
        var input = new RuleModels.Input("Synthetic", "https://t.me/synthetic/1", "D:/synthetic-source", "D:/synthetic-source-已上传", true,
                "FILENAME", new RuleModels.Window("ALL_DAY", null, null, "Asia/Shanghai"), "AUTO_UPLOAD");
        return new RuleModels.Rule(id, "1", version, revision, false, 0, 1, 1, input, null);
    }
    static final class Time extends Clock implements LongSupplier {
        long seconds;
        void advance(long amount) { seconds += amount; }
        @Override public long getAsLong() { return TimeUnit.SECONDS.toNanos(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(1_000 + seconds); }
    }
    static final class Queue implements Executor {
        final ArrayDeque<Runnable> work = new ArrayDeque<>();
        @Override public void execute(Runnable action) { work.add(action); }
        int size() { return work.size(); }
        void runNext() { work.remove().run(); }
    }
    static final class Fixture implements AutoCloseable {
        final RuleService rules = mock(RuleService.class); final TelegramService telegram = mock(TelegramService.class);
        final Time time = new Time(); final Queue queue = new Queue(); final AtomicReference<Runnable> guard = new AtomicReference<>();
        final CompletableFuture<RuleModels.View> observation = new CompletableFuture<>();
        final CompletableFuture<RuleModels.Preview> preview = new CompletableFuture<>();
        RuleModels.Rule current = rule(RULE, 1, 2);
        final RulePreparationService service;
        Fixture() {
            when(telegram.accountId()).thenReturn("1"); when(telegram.sessionGeneration()).thenReturn(1L);
            when(rules.get(anyString())).thenAnswer(i -> i.getArgument(0).equals(RULE) ? view()
                    : new RuleModels.View(rule(OTHER, 1, 2), "AUTO_UPLOAD", "PAUSED", false, 0, null));
            when(rules.observe(anyString(), any(), any())).thenAnswer(i -> { guard.set(i.getArgument(2)); return observation; });
            when(rules.preview(anyString(), any(), any())).thenAnswer(i -> { guard.set(i.getArgument(2)); return preview; });
            service = new RulePreparationService(rules, telegram, time, time, queue);
        }
        RuleModels.View view() { return new RuleModels.View(current, "AUTO_UPLOAD", "PAUSED", false, 0, null); }
        @Override public void close() { service.close(); }
    }
}
