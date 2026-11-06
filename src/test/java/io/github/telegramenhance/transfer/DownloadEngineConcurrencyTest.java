package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.github.telegramenhance.transfer.DownloadModels.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.downloadrules.DownloadRuleModels.Input;
import io.github.telegramenhance.downloadrules.DownloadRulePolicy;
import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.library.ResourceModels.MessageRef;
import io.github.telegramenhance.rules.RuleModels.Window;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.storage.DownloadSettings;
import io.github.telegramenhance.storage.TransferPreferences;
import io.github.telegramenhance.telegram.DownloadFileReader;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DownloadEngineConcurrencyTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void windowClosingAtAConcurrentAdmissionLetsStartedMediaSettleAndKeepsLaterIoFailures(boolean failAfterWindowCloses) throws Exception {
        try (var f = new Fixture(2)) {
            var clock = new MutableClock(); var policy = spy(new DownloadRulePolicy(f.database, clock));
            var started = new CountDownLatch(1); var refused = new CountDownLatch(1); var admissions = new AtomicInteger();
            var task = f.task(3, KEEP_DUPLICATES, false); f.bind(task);
            f.hook = id -> { started.countDown(); f.gate.acquireUninterruptibly(); };
            doAnswer(call -> {
                if (admissions.incrementAndGet() == 2) {
                    assertTrue(started.await(5, TimeUnit.SECONDS)); clock.now = Instant.parse("2026-10-28T15:00:00Z");
                }
                try { return call.callRealMethod(); }
                catch (DownloadRulePolicy.Blocked blocked) { refused.countDown(); throw blocked; }
            }).when(policy).beforeItem(any());
            f.start(policy); assertTrue(refused.await(5, TimeUnit.SECONDS));
            assertEquals(1, f.active.get()); assertEquals("RUNNING", f.fresh(task).state());
            verify(f.reader, never()).release(anyString(), anyLong(), anyInt(), anyString(), anyBoolean());
            f.invalidChunk = failAfterWindowCloses; f.gate.release();
            var stopped = f.await(task, failAfterWindowCloses ? "FAILED" : "PAUSED");
            assertEquals(2, admissions.get()); assertEquals(1, f.chunks.get());
            verify(f.reader, times(1)).resolve(any(), any(), anyLong());
            assertEquals(2, stopped.items().stream().filter(item -> item.state().equals("QUEUED")).count());
            assertFalse(f.earlyCleanup.get());
            if (failAfterWindowCloses) {
                assertNotEquals(DownloadRulePolicy.WINDOW_PAUSE, stopped.error());
                assertTrue(stopped.error().contains("文件写入或校验未完成")); assertEquals(0, f.logCount());
                assertEquals(0, f.repository.view(stopped).completed());
            } else {
                assertEquals(DownloadRulePolicy.WINDOW_PAUSE, stopped.error()); assertEquals(1, f.logCount());
                assertEquals(1, f.repository.view(stopped).completed());
                clock.now = Instant.parse("2026-10-29T14:00:00Z");
                assertTrue(policy.autoResume("1", task.id(), () -> {})); f.gate.release(3);
                assertEquals(3, f.repository.view(f.await(task, "COMPLETED")).completed());
                assertEquals(3, f.chunks.get(), "The already published media must not be replayed");
            }
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4})
    void configuredWorkersOverlapWithoutExceedingTheLimitAndCollidingNamesKeepEveryReceipt(int concurrency) throws Exception {
        try (var f = new Fixture(concurrency)) {
            var task = f.task(concurrency * 2 + 1, KEEP_DUPLICATES, false);
            f.start(); await(() -> f.active.get() == concurrency);
            assertEquals(concurrency, f.maximum.get()); assertEquals("RUNNING", f.fresh(task).state());
            f.gate.release(task.items().size());
            var done = f.await(task, "COMPLETED");
            assertEquals(task.items().size(), f.repository.view(done).completed());
            assertEquals(task.items().size(), f.logCount()); assertFalse(f.earlyCleanup.get());
            assertTrue(f.maximum.get() <= concurrency);
            assertEquals(task.items().size(), f.repository.view(done).items().stream().map(ItemView::outputPath).distinct().count());
            for (var item : done.items()) {
                Path output = Path.of(f.repository.view(done).items().get(item.ordinal()).outputPath());
                assertArrayEquals(f.bytes(Integer.parseInt(item.file().media().message().messageId()), 37), Files.readAllBytes(output));
            }
            assertEquals("keep", Files.readString(f.root.resolve("keep.txt")));
        }
    }

    @Test void repeatedNativeIdentityUsesCommittedHistoryBeforeStartingTheSecondConsumer() throws Exception {
        try (var f = new Fixture(2)) {
            var task = f.task(2, HISTORY_SKIP, true);
            f.start(); await(() -> f.active.get() == 1); f.gate.release(2);
            var done = f.await(task, "COMPLETED");
            assertEquals(1, f.repository.view(done).completed()); assertEquals(1, f.repository.view(done).skipped());
            assertEquals(1, f.chunks.get()); assertEquals(1, f.maximum.get()); assertEquals(1, f.logCount());
            verify(f.reader, times(1)).resolve(any(), any(), anyLong()); assertFalse(f.earlyCleanup.get());
        }
    }

    @Test void simultaneousEqualContentsReceiveOneOriginalAndTwoDuplicateReceipts() throws Exception {
        try (var f = new Fixture(3)) {
            f.sameContent = true; var task = f.task(3, KEEP_DUPLICATES, false);
            f.start(); await(() -> f.active.get() == 3); f.gate.release(3);
            var done = f.await(task, "COMPLETED"); var view = f.repository.view(done);
            assertEquals(3, view.completed()); assertEquals(2, view.duplicates()); assertEquals(3, f.logCount());
            assertEquals(3, view.items().stream().map(ItemView::outputPath).distinct().count());
            assertEquals(2, view.items().stream().filter(i -> Path.of(i.outputPath()).getParent().equals(f.root.resolve("重复媒体"))).count());
            for (var item : view.items()) assertArrayEquals(f.bytes(1, 37), Files.readAllBytes(Path.of(item.outputPath())));
            assertFalse(f.earlyCleanup.get());
        }
    }

    @Test void simultaneousUnknownContentDuplicatesHonorTheFrozenHistorySkipPreference() throws Exception {
        try (var f = new Fixture(3)) {
            new DownloadSettings(f.database).save(new DownloadSettings.Value(HISTORY_SKIP)); f.sameContent = true;
            var task = f.task(3, null, false);
            f.start(); await(() -> f.active.get() == 3); f.gate.release(3);
            var done = f.await(task, "COMPLETED");
            assertEquals(1, f.repository.view(done).completed()); assertEquals(2, f.repository.view(done).skipped());
            assertEquals(1, f.logCount()); assertFalse(f.earlyCleanup.get());
            try (var entries = Files.list(f.root)) { assertEquals(2, entries.count(), "Only the saved media and unrelated keep.txt remain"); }
        }
    }

    @Test void pauseWaitsForEveryActiveMediaAndResumesOnlyTheUnfinishedCheckpoints() throws Exception {
        try (var f = new Fixture(3)) {
            var task = f.task(5, KEEP_DUPLICATES, false); f.start(); await(() -> f.active.get() == 3);
            f.repository.pause("1", task.id());
            assertEquals("RUNNING", f.fresh(task).state()); assertTrue(f.fresh(task).pauseRequested());
            verify(f.reader, never()).release(anyString(), anyLong(), anyInt(), anyString(), anyBoolean());
            f.gate.release(3); var paused = f.await(task, "PAUSED");
            assertEquals(3, f.chunks.get()); assertEquals(0, f.active.get()); assertEquals(0, f.logCount());
            assertEquals("QUEUED", paused.items().getLast().state());
            assertTrue(paused.items().stream().allMatch(item->item.writtenBytes()==0),"Bytes returned after pause are never committed to a checkpoint");
            int readsBeforeResume=f.chunks.get();
            f.gate.release(5); f.repository.resume("1", task.id());
            var done = f.await(task, "COMPLETED");
            assertEquals(5, f.repository.view(done).completed()); assertEquals(readsBeforeResume+5, f.chunks.get(), "Only uncommitted reads are repeated after pause");
            assertEquals(5, f.logCount()); assertFalse(f.earlyCleanup.get());
        }
    }

    @Test void failureDoesNotFinalizeTheTaskOrCancelCachesUntilBlockedCompanionsHaveSettled() throws Exception {
        try (var f = new Fixture(3)) {
            var fail = new Semaphore(0); var failureThrown = new CountDownLatch(1);
            f.extraGates.add(fail);
            f.hook = id -> {
                if (id == 1) { fail.acquireUninterruptibly(); failureThrown.countDown(); throw new IllegalStateException("synthetic-private-failure"); }
                f.gate.acquireUninterruptibly();
            };
            var task = f.task(5, KEEP_DUPLICATES, false); f.start(); await(() -> f.active.get() == 3);
            fail.release(); assertTrue(failureThrown.await(5, TimeUnit.SECONDS)); await(() -> f.active.get() == 2);
            assertEquals("RUNNING", f.fresh(task).state()); assertEquals(0, f.logCount());
            verify(f.reader, never()).release(anyString(), anyLong(), anyInt(), anyString(), anyBoolean());
            f.gate.release(2); var failed = f.await(task, "FAILED");
            assertEquals(0, f.active.get()); assertEquals(3, f.chunks.get()); assertEquals(0, f.logCount());
            assertFalse(failed.error().contains("private")); assertFalse(f.earlyCleanup.get());
            assertEquals("keep", Files.readString(f.root.resolve("keep.txt")));
        }
    }

    @Test void closingWaitsForAllActiveMediaAndLeavesTheNextBatchUnstarted() throws Exception {
        try (var f = new Fixture(3)) {
            var interrupted = new CountDownLatch(3);
            f.hook = id -> {
                boolean restore = false;
                for (;;) try { f.gate.acquire(); break; }
                catch (InterruptedException stopping) { restore = true; interrupted.countDown(); }
                if (restore) Thread.currentThread().interrupt();
            };
            var task = f.task(5, KEEP_DUPLICATES, false); f.start(); await(() -> f.active.get() == 3);
            var closing = CompletableFuture.runAsync(f.engine::close);
            try {
                assertTrue(interrupted.await(5, TimeUnit.SECONDS)); assertFalse(closing.isDone());
                assertEquals("RUNNING", f.fresh(task).state());
                verify(f.reader, never()).release(anyString(), anyLong(), anyInt(), anyString(), anyBoolean());
            } finally { f.gate.release(3); }
            closing.get(5, TimeUnit.SECONDS);
            assertEquals("PAUSED", f.fresh(task).state()); assertEquals(0, f.active.get()); assertEquals(3, f.chunks.get());
            assertEquals("QUEUED", f.fresh(task).items().getLast().state()); assertEquals(0, f.logCount());
            verify(f.reader, never()).release(anyString(), anyLong(), anyInt(), anyString(), anyBoolean());
            assertEquals("keep", Files.readString(f.root.resolve("keep.txt")));
        }
    }

    @Test void aChangedPreferenceAppliesToTheNextTaskWithoutResizingTheRunningOne() throws Exception {
        try (var f = new Fixture(2)) {
            var first = f.task(2, KEEP_DUPLICATES, false); var next = f.task(4, KEEP_DUPLICATES, false);
            // Make task order deterministic even when SQLite timestamps have millisecond ties.
            f.database.write(c -> { try (var q = c.prepareStatement("UPDATE download_tasks SET created_at=? WHERE task_id=?")) {
                q.setLong(1, 1); q.setString(2, first.id()); q.executeUpdate(); q.setLong(1, 2); q.setString(2, next.id()); q.executeUpdate();
            } return null; });
            f.start(); await(() -> f.active.get() == 2); f.preferences.save(new TransferPreferences.View("", 4));
            assertEquals("QUEUED", f.fresh(next).state()); assertEquals(2, f.maximum.get());
            f.gate.release(2); f.await(first, "COMPLETED"); await(() -> f.active.get() == 4);
            f.gate.release(4); f.await(next, "COMPLETED"); assertEquals(4, f.maximum.get());
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Download workers did not reach the expected bounded state");
    }
    @FunctionalInterface private interface ChunkHook { void run(int id); }
    private final class Fixture implements AutoCloseable {
        final AppPaths paths = new AppPaths(directory.resolve("data-" + UUID.randomUUID()));
        final Database database = new Database(paths);
        final DownloadRepository repository = new DownloadRepository(database);
        final DownloadFileReader reader = mock(DownloadFileReader.class);
        final TelegramService telegram = mock(TelegramService.class);
        final DownloadFileStore store = new DownloadFileStore(new DownloadPaths(paths), repository);
        final TransferPreferences preferences = new TransferPreferences(database);
        final Path root = directory.resolve("out-" + UUID.randomUUID()).toAbsolutePath();
        final Semaphore gate = new Semaphore(0);
        final List<Semaphore> extraGates = new CopyOnWriteArrayList<>();
        final AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger(), chunks = new AtomicInteger();
        final AtomicBoolean earlyCleanup = new AtomicBoolean();
        final ConcurrentMap<Integer, Boolean> cached = new ConcurrentHashMap<>();
        volatile boolean sameContent, invalidChunk; volatile ChunkHook hook = id -> gate.acquireUninterruptibly(); DownloadEngine engine;
        Fixture(int concurrency) throws Exception {
            Files.createDirectories(root); Files.writeString(root.resolve("keep.txt"), "keep");
            preferences.save(new TransferPreferences.View("", concurrency));
            when(telegram.accountId()).thenReturn("1"); when(telegram.sessionGeneration()).thenReturn(1L);
            when(reader.resolve(any(), any(), anyLong())).thenAnswer(call -> {
                FrozenFile file = call.getArgument(1); return new DownloadFileReader.Resolved(file, Integer.parseInt(file.media().message().messageId()));
            });
            when(reader.status(anyString(), anyLong(), anyInt(), anyString(), anyLong())).thenAnswer(call -> {
                boolean complete = cached.getOrDefault(call.getArgument(2), false);
                return new DownloadFileReader.Status(complete ? 37 : 0, complete, false, true);
            });
            doAnswer(call -> { cached.put(call.getArgument(2), true); return null; }).when(reader).start(anyString(), anyLong(), anyInt(), anyString(), anyLong());
            when(reader.chunk(anyString(), anyLong(), anyInt(), anyString(), anyLong(), anyLong(), anyInt())).thenAnswer(call -> {
                int id = call.getArgument(2), count = call.getArgument(6); chunks.incrementAndGet(); maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                try { hook.run(id); return invalidChunk ? null : bytes(id, count); } finally { active.decrementAndGet(); }
            });
            when(reader.release(anyString(), anyLong(), anyInt(), anyString(), anyBoolean())).thenAnswer(call -> {
                if (active.get() != 0) earlyCleanup.set(true); return true;
            });
        }
        byte[] bytes(int id, int length) { var data = new byte[length]; Arrays.fill(data, (byte) (sameContent ? 7 : id)); return data; }
        Task task(int count, String policy, boolean sameIdentity) {
            var files = java.util.stream.IntStream.rangeClosed(1, count).mapToObj(id -> new FrozenFile(
                    new Media("1:-2001:" + id + ":0", new MessageRef("-2001", Integer.toString(id), "https://t.me/c/2/" + id), "COMMENT", "DOCUMENT", "same.bin", 37, ""),
                    "unique-" + (sameIdentity ? 1 : id), 37, "same.bin")).toList();
            String operation = UUID.randomUUID().toString();
            return policy == null ? repository.create("1", operation, operation, DownloadPersistenceTest.post("title"), root, files)
                    : repository.create("1", operation, operation, DownloadPersistenceTest.post("title"), root, files, policy);
        }
        void bind(Task task) {
            String rule = UUID.randomUUID().toString();
            String config = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(new Input("window", "-1001", "", "", List.of("DOCUMENT"), true,
                    task.targetRoot(), new Window("WINDOW", "22:00", "23:00", "Asia/Shanghai")));
            database.write(c -> {
                try (var q = c.prepareStatement("INSERT INTO download_rules VALUES(?,'1',?,'fingerprint',1,1,1,0,1,1)")) {
                    q.setString(1, rule); q.setString(2, UUID.randomUUID().toString()); q.executeUpdate();
                }
                try (var q = c.prepareStatement("INSERT INTO download_rule_versions VALUES(?,1,?,'-1001','channel',1)")) {
                    q.setString(1, rule); q.setString(2, config); q.executeUpdate();
                }
                try (var q = c.prepareStatement("INSERT INTO download_rule_tasks VALUES(?,?,'1',1,?,0,0,NULL,1)")) {
                    q.setString(1, task.id()); q.setString(2, rule); q.setString(3, config); q.executeUpdate();
                }
                return null;
            });
        }
        void start() { start(null); }
        void start(DownloadRulePolicy policy) { engine = new DownloadEngine(repository, reader, store, telegram, paths, policy, preferences); }
        Task fresh(Task task) { return repository.task("1", task.id()); }
        Task await(Task task, String state) throws Exception {
            DownloadEngineConcurrencyTest.await(() -> fresh(task).state().equals(state)); return fresh(task);
        }
        long logCount() { return database.read(c -> { try (var r = c.createStatement().executeQuery("SELECT COUNT(*) FROM download_completion_log")) { r.next(); return r.getLong(1); } }); }
        @Override public void close() { gate.release(100); extraGates.forEach(g -> g.release(100)); if (engine != null) engine.close(); database.close(); }
    }
    private static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-28T14:59:59Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
