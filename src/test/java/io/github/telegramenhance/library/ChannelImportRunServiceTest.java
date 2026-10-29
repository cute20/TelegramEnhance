package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.github.telegramenhance.library.ChannelImportRunRepositoryTest.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ChannelImportRunModels.RunView;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.ChannelReader;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.telegram.TelegramService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelImportRunServiceTest {
    @TempDir Path directory;

    @Test void explicitStepsFetchOneBatchThenOnePostAtATimeWithoutStartingABackgroundLoop() throws Exception {
        try (Fixture f = fixture()) {
            var start = await(f.service.start("-1001", false, null, null));
            verifyNoInteractions(f.reader); verify(f.channels).managedChannel("-1001"); verify(f.channels, never()).historyPage(anyString(), nullable(String.class));
            when(f.channels.historyPage("-1001", null)).thenAnswer(call -> {
                assertEquals("resource-reader", Thread.currentThread().getName());
                return page(List.of(candidate("100", null), candidate("90", "0")), null, true, false);
            });
            var fetched = f.step(start); assertEquals(0, fetched.processed()); assertEquals(1, fetched.revision()); verifyNoInteractions(f.reader);
            var first = f.step(fetched); assertEquals(1, first.processed()); assertEquals(2, first.revision()); assertFalse(first.exhausted());
            verify(f.reader).channelPost("-1001", "100"); verify(f.reader, never()).channelPost("-1001", "90");
            var done = f.step(first); assertEquals("COMPLETE", done.status()); assertTrue(done.exhausted()); assertEquals(2, done.processed());
            verify(f.channels, times(1)).historyPage(anyString(), nullable(String.class));
        }
    }

    @Test void uncertainResponsesCanBeRecoveredWithStateAndStaleStepsNeverRunAgain() throws Exception {
        try (Fixture f = fixture()) {
            var start = await(f.service.start("-1001", false, null, null));
            when(f.channels.historyPage("-1001", null)).thenReturn(page(List.of(candidate("100", null)), null, true, false));
            var fetched = f.step(start);
            assertEquals(fetched, f.service.state("-1001"));
            assertThrows(ExecutionException.class, () -> f.step(start)); verifyNoInteractions(f.reader);
            var completed = f.step(fetched);
            assertEquals(completed, f.service.state("-1001"));
            assertThrows(ExecutionException.class, () -> f.step(fetched));
            verify(f.channels, times(1)).historyPage("-1001", null); verify(f.reader, times(1)).channelPost("-1001", "100");
        }
    }

    @Test void nativeFailuresKeepThePendingCandidateAndNeverRetryAutomatically() throws Exception {
        try (Fixture f = fixture()) {
            var start = await(f.service.start("-1001", false, null, null));
            var failure = new IllegalArgumentException("Synthetic metadata read failure");
            when(f.channels.historyPage("-1001", null)).thenThrow(failure);
            assertSame(failure, assertThrows(ExecutionException.class, () -> f.step(start)).getCause()); assertEquals(start, f.service.state("-1001"));
            doReturn(page(List.of(candidate("100", null)), null, true, false)).when(f.channels).historyPage("-1001", null);
            var fetched = f.step(start);
            doThrow(failure).when(f.reader).channelPost("-1001", "100");
            assertSame(failure, assertThrows(ExecutionException.class, () -> f.step(fetched)).getCause());
            assertEquals(fetched, f.service.state("-1001")); assertFalse(f.posts.containsPost("1", "1:-1001:100"));
            assertEquals("100", f.repository.current("1", "-1001").pending().getFirst().messageId());
            verify(f.reader, times(1)).channelPost("-1001", "100");
        }
    }

    @Test void changingAnAlbumsFrozenGroupingRefusesThePostWithoutAdvancingItsPendingCheckpoint() throws Exception {
        try (Fixture f = fixture()) {
            var start = await(f.service.start("-1001", false, null, null));
            when(f.channels.historyPage("-1001", null)).thenReturn(page(List.of(candidate("100", "42")), null, true, false));
            var fetched = f.step(start);
            when(f.reader.channelPost("-1001", "100")).thenReturn(post("100", "43", "COMPLETE", "changed group"));
            assertThrows(ExecutionException.class, () -> f.step(fetched)); assertEquals(fetched, f.service.state("-1001"));
            assertTrue(f.posts.posts("1", 10, 0).isEmpty());
        }
    }

    @Test void queuedStepsCannotReadAfterAccountOrSameAccountSessionChanges() throws Exception {
        for (boolean changeAccount : List.of(true, false)) {
            try (Fixture f = new Fixture(new AppPaths(directory.resolve(Boolean.toString(changeAccount))))) {
                var start = await(f.service.start("-1001", false, null, null));
                clearInvocations(f.channels, f.reader);
                CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
                var blocked = f.work.readWork(() -> { entered.countDown(); waitFor(release); return true; });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var queued = f.service.step("-1001", start.runId(), start.revision());
                if (changeAccount) f.account.set("2"); else f.generation.incrementAndGet();
                release.countDown(); await(blocked); assertThrows(ExecutionException.class, () -> await(queued));
                verifyNoInteractions(f.channels, f.reader); assertEquals(start, f.repository.state("1", "-1001"));
            }
        }
    }

    @Test void aSessionChangeDuringNativeReadsCannotSaveABatchOrPost() throws Exception {
        try (Fixture f = fixture()) {
            var start = await(f.service.start("-1001", false, null, null));
            when(f.channels.historyPage("-1001", null)).thenAnswer(call -> {
                f.generation.incrementAndGet(); return page(List.of(candidate("100", null)), null, true, false);
            });
            assertThrows(ExecutionException.class, () -> f.step(start)); assertEquals(start, f.service.state("-1001"));
            doReturn(page(List.of(candidate("100", null)), null, true, false)).when(f.channels).historyPage("-1001", null);
            var fetched = f.step(start);
            when(f.reader.channelPost("-1001", "100")).thenAnswer(call -> { f.generation.incrementAndGet(); return post("100", "0", "COMPLETE", "post"); });
            assertThrows(ExecutionException.class, () -> f.step(fetched)); assertEquals(fetched, f.service.state("-1001"));
            assertTrue(f.posts.posts("1", 10, 0).isEmpty());
        }
    }

    @Test void stateIsAccountScopedAndNeverCallsTheNativeReaders() throws Exception {
        try (Fixture f = fixture()) {
            assertNull(f.service.state("-1001")); var first = await(f.service.start("-1001", false, null, null));
            assertEquals(first, f.service.state("-1001")); f.account.set("2"); assertNull(f.service.state("-1001"));
            var second = await(f.service.start("-1001", false, null, null)); assertNotEquals(first.runId(), second.runId());
            f.account.set("1"); assertEquals(first, f.service.state("-1001")); verifyNoInteractions(f.reader);
            verify(f.channels, times(2)).managedChannel("-1001"); verify(f.channels, never()).historyPage(anyString(), nullable(String.class));
        }
    }

    @Test void startingANonManagedMissingOrMismatchedChannelCannotCreateARun() throws Exception {
        try (Fixture f = fixture()) {
            doThrow(new IllegalArgumentException("不是当前账号管理的频道")).when(f.channels).managedChannel("-1001");
            assertThrows(ExecutionException.class, () -> await(f.service.start("-1001", false, null, null)));
            assertNull(f.repository.state("1", "-1001"));
            doReturn(null).when(f.channels).managedChannel("-1001");
            assertThrows(ExecutionException.class, () -> await(f.service.start("-1001", false, null, null)));
            assertNull(f.repository.state("1", "-1001"));
            doReturn(new io.github.telegramenhance.library.ChannelImportModels.ManagedChannel("-1002", "Synthetic")).when(f.channels).managedChannel("-1001");
            assertThrows(ExecutionException.class, () -> await(f.service.start("-1001", false, null, null)));
            assertNull(f.repository.state("1", "-1001")); verifyNoInteractions(f.reader);
            verify(f.channels, never()).historyPage(anyString(), nullable(String.class));
        }
    }

    private Fixture fixture() { return new Fixture(new AppPaths(directory)); }
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(4, TimeUnit.SECONDS); }
    private static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic latch timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Synthetic interruption"); }
    }
    private static final class Fixture implements AutoCloseable {
        final Database database; final ResourceRepository posts; final ChannelImportRunRepository repository;
        final ChannelReader channels = mock(ChannelReader.class); final ResourceReader reader = mock(ResourceReader.class);
        final TelegramService telegram = mock(TelegramService.class); final AtomicReference<String> account = new AtomicReference<>("1");
        final AtomicLong generation = new AtomicLong(1); final ResourceService work; final ChannelImportRunService service;
        Fixture(AppPaths paths) {
            database = new Database(paths); posts = new ResourceRepository(database); repository = new ChannelImportRunRepository(database, posts);
            when(telegram.accountId()).thenAnswer(call -> account.get()); when(telegram.sessionGeneration()).thenAnswer(call -> generation.get());
            when(channels.managedChannel(anyString())).thenAnswer(call -> new io.github.telegramenhance.library.ChannelImportModels.ManagedChannel(call.getArgument(0), "Synthetic"));
            when(reader.channelPost(anyString(), anyString())).thenAnswer(call -> post(call.getArgument(1), "0", "COMPLETE", "synthetic"));
            work = new ResourceService(reader, posts, telegram); service = new ChannelImportRunService(work, repository, channels, reader, telegram);
        }
        RunView step(RunView state) throws Exception { return await(service.step("-1001", state.runId(), state.revision())); }
        @Override public void close() { work.close(); database.close(); }
    }
}
