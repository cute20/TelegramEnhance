package io.github.telegramenhance.downloadrules;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.downloadrules.DownloadRuleModels.Input;
import io.github.telegramenhance.downloadrules.DownloadRuleModels.Rule;
import io.github.telegramenhance.library.ChannelImportModels.ManagedChannel;
import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.library.ResourceModels.MessageRef;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.rules.RuleModels.Window;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.DownloadModels.FrozenFile;
import io.github.telegramenhance.transfer.DownloadModels.Retry;
import io.github.telegramenhance.transfer.DownloadModels.Task;
import io.github.telegramenhance.transfer.DownloadRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DownloadRuleRetryControlTest {
    @TempDir Path directory;
    private static final Runnable GUARD = () -> {};
    private static final ManagedChannel CHANNEL = new ManagedChannel("-1001", "test channel");

    @Test void manualHoldWinsBeforeOrAfterRetryRegistrationAndOtherRulesKeepTheirRetries() {
        for (boolean retryFirst : List.of(false, true)) try (var f = new Fixture()) {
            Rule rule = f.rule("1", allDay());
            Task task = f.running(rule, false);
            List<Waiting> controls = f.otherWaitingTasks();
            if (retryFirst) assertTrue(f.retry(task));

            assertTrue(f.policy.manualPause("1", task.id(), GUARD));
            assertFalse(f.retry(task), "A late failure must not undo a manual hold");
            f.assertPaused(task, null);
            assertEquals(1, f.held(task));
            assertFalse(f.policy.allowed("1", task.id(), f.clock.instant()));
            assertFalse(f.policy.autoResume("1", task.id(), GUARD));

            f.clock.now = f.clock.instant().plusSeconds(3600);
            assertFalse(f.policy.autoResume("1", task.id(), GUARD));
            f.assertPaused(task, null);
            f.assertUnchanged(controls);

            assertTrue(f.policy.manualResume("1", task.id(), GUARD));
            assertEquals(0, f.held(task));
            assertEquals("QUEUED", f.task(task).state());
            assertFalse(f.task(task).pauseRequested());
            assertNull(f.downloads.retry("1", task.id()));
        }
    }

    @Test void disablingTheParentAtomicallyCancelsRetryAndRejectsAnInFlightLateFailure() throws Exception {
        for (boolean retryFirst : List.of(false, true)) try (var f = new Fixture()) {
            Rule rule = f.rule("1", allDay());
            Task task = f.running(rule, false);
            Task manual = f.running(rule, true);
            assertTrue(f.retry(manual));
            Waiting manualBefore = f.waiting(manual);
            List<Waiting> controls = f.otherWaitingTasks();

            if (retryFirst) {
                assertTrue(f.retry(task));
                f.rules.toggle("1", rule.id(), rule.revision(), false, f.clock.millis(), GUARD);
            } else {
                // Keep the parent transaction open while the media worker attempts to register
                // its failure. The retry transaction must observe the committed disabled rule.
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var attempted = new CountDownLatch(1);
                var calls = new AtomicInteger();
                try (var workers = Executors.newFixedThreadPool(2)) {
                    var disabling = workers.submit(() -> f.rules.toggle("1", rule.id(), rule.revision(), false,
                            f.clock.millis(), () -> {
                                if (calls.incrementAndGet() == 1) {
                                    entered.countDown();
                                    await(release);
                                }
                            }));
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS));
                        var registering = workers.submit(() -> {
                            attempted.countDown();
                            return f.retry(task);
                        });
                        assertTrue(attempted.await(5, TimeUnit.SECONDS));
                        release.countDown();
                        assertFalse(disabling.get(5, TimeUnit.SECONDS).enabled());
                        assertFalse(registering.get(5, TimeUnit.SECONDS));
                    } finally {
                        release.countDown();
                    }
                }
            }

            assertFalse(f.retry(task));
            f.assertPaused(task, DownloadRulePolicy.RULE_PAUSE);
            assertEquals(0, f.held(task));
            assertFalse(f.policy.autoResume("1", task.id(), GUARD));
            assertTrue(f.policy.allowed("1", manual.id(), f.clock.instant()));
            f.assertUnchanged(List.of(manualBefore));
            f.assertUnchanged(controls);
        }
    }

    @Test void aNewEnabledRuleVersionCannotRequeueAnOldSnapshotInEitherRegistrationOrder() {
        for (boolean retryFirst : List.of(false, true)) try (var f = new Fixture()) {
            Rule rule = f.rule("1", allDay());
            Task task = f.running(rule, false);
            List<Waiting> controls = f.otherWaitingTasks();
            if (retryFirst) assertTrue(f.retry(task));

            Rule changed = f.rules.update("1", rule.id(), rule.revision(),
                    f.input("revised rule", allDay()), CHANNEL, f.clock.millis(), GUARD);
            Rule enabled = f.rules.toggle("1", changed.id(), changed.revision(), true, f.clock.millis(), GUARD);
            assertEquals(2, enabled.version());
            assertFalse(f.retry(task));
            f.assertPaused(task, DownloadRulePolicy.RULE_PAUSE);
            assertFalse(f.policy.autoResume("1", task.id(), GUARD));
            assertEquals(task.post(), f.task(task).post());
            assertEquals(task.items(), f.task(task).items());
            assertEquals(task.operationId(), f.task(task).operationId());
            assertEquals(1, f.bindingVersion(task));
            f.assertUnchanged(controls);
        }
    }

    @Test void anAdmittedMediaFailureAfterTheWindowEndsWaitsForTheNextAllowedWindow() {
        try (var f = new Fixture()) {
            Rule rule = f.rule("1", new Window("WINDOW", "22:00", "23:00", "Asia/Shanghai"));
            Task task = f.running(rule, false);
            assertTrue(f.policy.allowed("1", task.id(), f.clock.instant()));

            f.clock.now = Instant.parse("2026-10-28T15:00:00Z");
            assertFalse(f.retry(task));
            f.assertPaused(task, DownloadRulePolicy.WINDOW_PAUSE);
            assertEquals(0, f.held(task));
            assertFalse(f.policy.autoResume("1", task.id(), GUARD));
            assertNull(f.downloads.next("1"));

            f.clock.now = Instant.parse("2026-10-29T14:00:00Z");
            assertTrue(f.policy.autoResume("1", task.id(), GUARD));
            assertNull(f.downloads.retry("1", task.id()));
            assertEquals(task.id(), f.downloads.next("1").id());
            assertTrue(f.retry(task));
            assertEquals(1, f.downloads.retry("1", task.id()).attempt());
            assertEquals(task.items(), f.task(task).items());
        }
    }

    @Test void aRejectedControlTransactionRollsBackRetryRemovalAndTheParentOrManualHold() {
        try (var f = new Fixture()) {
            Rule rule = f.rule("1", allDay());
            Task task = f.running(rule, false);
            assertTrue(f.retry(task));
            Waiting before = f.waiting(task);

            assertThrows(IllegalArgumentException.class, () ->
                    f.policy.manualPause("1", task.id(), failSecondGuard()));
            f.assertUnchanged(List.of(before));
            assertEquals(0, f.held(task));

            assertThrows(IllegalArgumentException.class, () ->
                    f.rules.toggle("1", rule.id(), rule.revision(), false, f.clock.millis(), failSecondGuard()));
            assertEquals(rule, f.rules.get("1", rule.id()));
            f.assertUnchanged(List.of(before));

            assertThrows(IllegalArgumentException.class, () -> f.policy.manualPause("2", task.id(), GUARD));
            assertThrows(IllegalArgumentException.class, () ->
                    f.rules.toggle("2", rule.id(), rule.revision(), false, f.clock.millis(), GUARD));
            f.assertUnchanged(List.of(before));
            assertEquals(rule, f.rules.get("1", rule.id()));
        }
    }

    private static Window allDay() { return new Window("ALL_DAY", null, null, "Asia/Shanghai"); }
    private static Runnable failSecondGuard() {
        var calls = new AtomicInteger();
        return () -> { if (calls.incrementAndGet() == 2) throw new IllegalArgumentException("session changed"); };
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while holding the parent transaction", interrupted);
        }
    }

    private record Waiting(Task task, Retry retry) { }

    private final class Fixture implements AutoCloseable {
        final AppPaths paths = new AppPaths(directory.resolve(UUID.randomUUID().toString()));
        final Database db = new Database(paths);
        final MutableClock clock = new MutableClock();
        final DownloadRepository downloads = new DownloadRepository(db, clock);
        final DownloadRuleRepository rules = new DownloadRuleRepository(db, downloads);
        final DownloadRulePolicy policy = new DownloadRulePolicy(db, clock);
        final JsonMapper json = JsonMapper.builder().build();
        final Path root = directory.resolve("output").toAbsolutePath();
        int messageId = 100;

        Input input(String name, Window window) {
            return new Input(name, CHANNEL.id(), "", "", List.of("DOCUMENT"), true, root.toString(), window);
        }
        Rule rule(String account, Window window) {
            Rule rule = rules.create(account, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    input("test rule", window), CHANNEL, clock.millis(), GUARD);
            return rules.toggle(account, rule.id(), rule.revision(), true, clock.millis(), GUARD);
        }
        Task running(Rule rule, boolean manual) {
            String account = rule.accountId();
            String id = String.valueOf(++messageId);
            var ref = new MessageRef(CHANNEL.id(), id, "");
            var post = new Post(account + ":-1001:" + id, account, CHANNEL.id(), CHANNEL.title(), id,
                    "title", "body", "0", List.of(ref), List.of(), null, null, List.of(), false, "UNAVAILABLE", List.of());
            var media = new Media(account + ":-1001:" + id + ":0", ref, "MAIN", "DOCUMENT", "file.bin", 37, "");
            var task = downloads.create(account, UUID.randomUUID().toString(), "frozen", post, root,
                    List.of(new FrozenFile(media, "stable-" + account + "-" + id, 37, "file.bin")));
            db.write(c -> {
                try (var q = c.prepareStatement("INSERT INTO download_rule_tasks(task_id,rule_id,account_id,rule_version,snapshot_json,manual,held,settled_at,created_at) VALUES(?,?,?,?,?,?,0,?,?)")) {
                    q.setString(1, task.id()); q.setString(2, rule.id()); q.setString(3, account);
                    q.setLong(4, rule.version()); q.setString(5, json.writeValueAsString(rule.input()));
                    q.setInt(6, manual ? 1 : 0); q.setObject(7, manual ? clock.millis() : null); q.setLong(8, clock.millis());
                    q.executeUpdate();
                }
                return null;
            });
            assertEquals(task.id(), downloads.next(account).id());
            return downloads.task(account, task.id());
        }
        boolean retry(Task task) { return downloads.scheduleRetry(task.accountId(), task.id(), "连接暂时中断", 0); }
        Task task(Task task) { return downloads.task(task.accountId(), task.id()); }
        Waiting waiting(Task task) {
            Retry retry = downloads.retry(task.accountId(), task.id());
            assertNotNull(retry);
            return new Waiting(task(task), retry);
        }
        List<Waiting> otherWaitingTasks() {
            var other = running(rule("1", allDay()), false);
            assertTrue(retry(other));
            var foreign = running(rule("2", allDay()), false);
            assertTrue(retry(foreign));
            return List.of(waiting(other), waiting(foreign));
        }
        void assertUnchanged(List<Waiting> controls) {
            for (var control : controls) {
                assertEquals(control.task(), task(control.task()));
                assertEquals(control.retry(), downloads.retry(control.task().accountId(), control.task().id()));
            }
        }
        void assertPaused(Task task, String reason) {
            Task current = task(task);
            assertEquals("PAUSED", current.state());
            assertTrue(current.pauseRequested());
            assertNull(downloads.retry(task.accountId(), task.id()));
            if (reason != null) assertEquals(reason, current.error());
        }
        int held(Task task) { return bindingNumber(task, "held"); }
        int bindingVersion(Task task) { return bindingNumber(task, "rule_version"); }
        int bindingNumber(Task task, String column) {
            return db.read(c -> {
                try (var q = c.prepareStatement("SELECT " + column + " FROM download_rule_tasks WHERE task_id=? AND account_id=?")) {
                    q.setString(1, task.id()); q.setString(2, task.accountId());
                    try (var r = q.executeQuery()) { assertTrue(r.next()); return r.getInt(1); }
                }
            });
        }
        @Override public void close() { db.close(); }
    }

    private static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-28T14:59:59Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
