package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.telegram.PostPreviewReader;
import io.github.telegramenhance.telegram.PostPreviewReader.Preview;
import io.github.telegramenhance.telegram.TelegramService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LibraryPreviewServiceTest {
    private static final Preview IMAGE = new Preview("AVAILABLE", "data:image/jpeg;base64,/9j/2Q==", 32, 24, "THUMBNAIL");
    private static final Preview OTHER = new Preview("AVAILABLE", "data:image/jpeg;base64,/9j/YQ==", 24, 32, "THUMBNAIL");
    private static final Preview MISSING = new Preview("UNAVAILABLE", null, 0, 0);

    @Test
    void successfulPreviewExpiresAfterTenMinutesWithoutSlidingTheExpiryOnHits() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(IMAGE, await(f.service.preview("key")));
            f.time.set(TimeUnit.MINUTES.toNanos(9));
            assertEquals(IMAGE, await(f.service.preview("key")));
            verify(f.reader, times(1)).read(any());
            f.time.set(TimeUnit.MINUTES.toNanos(10));
            assertEquals(IMAGE, await(f.service.preview("key")));
            verify(f.reader, times(2)).read(any());
            verify(f.repository, never()).savePost(any());
            verify(f.repository, never()).savePage(any());
        }
    }

    @Test
    void unavailablePreviewIsCachedOnlyForOneMinute() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.reader.read(any())).thenReturn(MISSING);
            assertEquals(MISSING, await(f.service.preview("key")));
            f.time.set(TimeUnit.SECONDS.toNanos(59));
            assertEquals(MISSING, await(f.service.preview("key")));
            verify(f.reader, times(1)).read(any());
            f.time.set(TimeUnit.SECONDS.toNanos(60));
            assertEquals(MISSING, await(f.service.preview("key")));
            verify(f.reader, times(2)).read(any());
        }
    }

    @Test
    void miniFallbackExpiresAfterOneMinuteSoItCanUpgradeToAThumbnail() throws Exception {
        try (Fixture f = new Fixture()) {
            Preview mini = new Preview("AVAILABLE", "data:image/jpeg;base64,/9j/2Q==", 32, 24, "MINI");
            when(f.reader.read(any())).thenReturn(mini).thenReturn(IMAGE);
            assertEquals(mini, await(f.service.preview("key")));
            f.time.set(TimeUnit.SECONDS.toNanos(59));
            assertEquals(mini, await(f.service.preview("key")));
            verify(f.reader, times(1)).read(any());
            f.time.set(TimeUnit.SECONDS.toNanos(60));
            assertEquals(IMAGE, await(f.service.preview("key")));
            f.time.set(TimeUnit.MINUTES.toNanos(5));
            assertEquals(IMAGE, await(f.service.preview("key")));
            verify(f.reader, times(2)).read(any());
        }
    }

    @Test
    void accountUpdatedAtAndFullPostContentAllParticipateInCacheIdentity() throws Exception {
        try (Fixture f = new Fixture()) {
            await(f.service.preview("key"));
            f.sources.put("1:key", new ResourceRepository.PreviewSource(post("1", "key", "body"), 2));
            await(f.service.preview("key"));
            f.sources.put("1:key", new ResourceRepository.PreviewSource(post("1", "key", "changed in the same millisecond"), 2));
            await(f.service.preview("key"));
            verify(f.reader, times(3)).read(any());
            f.account.set("2");
            when(f.reader.read(any())).thenReturn(OTHER);
            assertEquals(OTHER, await(f.service.preview("key")));
            verify(f.reader, times(4)).read(any());
            f.account.set("1");
            assertEquals(IMAGE, await(f.service.preview("key")));
            verify(f.reader, times(4)).read(any());
        }
    }

    @Test
    void concurrentCallersShareWorkButCancellingOneDoesNotCancelTheOthers() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.reader.read(any())).thenAnswer(invocation -> { started.countDown(); waitFor(release); return IMAGE; });
            CompletableFuture<Preview> first = f.service.preview("key");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            CompletableFuture<Preview> second = f.service.preview("key");
            assertNotSame(first, second);
            assertTrue(first.cancel(true));
            assertFalse(second.isDone());
            release.countDown();
            assertEquals(IMAGE, await(second));
            assertTrue(first.isCancelled());
            verify(f.reader, times(1)).read(any());
        }
    }

    @Test
    void changingAccountDuringReadCannotDeliverOrCacheThePreviousAccountsImage() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.reader.read(any())).thenAnswer(invocation -> { started.countDown(); waitFor(release); return IMAGE; });
            var preview = f.service.preview("key");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            f.account.set("2"); release.countDown();
            var failure = assertThrows(ExecutionException.class, () -> await(preview));
            assertTrue(failure.getCause().getMessage().contains("账号已变化"));
            f.account.set("1");
            assertEquals(IMAGE, await(f.service.preview("key")));
            verify(f.reader, times(2)).read(any());
        }
    }

    @Test
    void revisionChangingInFlightRetriesWithTheNewSnapshotInsteadOfReturningTheStaleImage() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.reader.read(any())).thenAnswer(invocation -> {
                Post post = invocation.getArgument(0);
                if (post.text().equals("body")) { started.countDown(); waitFor(release); return IMAGE; }
                return OTHER;
            });
            var preview = f.service.preview("key");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            f.sources.put("1:key", new ResourceRepository.PreviewSource(post("1", "key", "new body"), 1));
            release.countDown();
            assertEquals(OTHER, await(preview));
            assertEquals(OTHER, await(f.service.preview("key")));
            verify(f.reader, times(2)).read(any());
        }
    }

    @Test
    void errorsAreSanitizedAndNotCachedSoTheNextRequestCanRetry() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.reader.read(any())).thenThrow(new IllegalArgumentException("native-private-message-and-secret"))
                .thenReturn(IMAGE);
            var failure = assertThrows(ExecutionException.class, () -> await(f.service.preview("key")));
            assertFalse(failure.getCause().getMessage().contains("native-private-message-and-secret"));
            assertNull(failure.getCause().getCause());
            assertEquals(IMAGE, await(f.service.preview("key")));
            verify(f.reader, times(2)).read(any());
        }
    }

    @Test
    void cacheKeepsAtMostOneHundredTwentyEightLeastRecentlyUsedEntries() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int index = 0; index < 128; index++) await(f.service.preview("key-" + index));
            await(f.service.preview("key-0")); // Make this entry newer than key-1.
            await(f.service.preview("key-128"));
            await(f.service.preview("key-0"));
            verify(f.reader, times(129)).read(any());
            await(f.service.preview("key-1"));
            verify(f.reader, times(130)).read(any());
        }
    }

    @Test
    void queueIsBoundedAndQueuedRequestsRecheckAccountBeforeReadingTheDatabase() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
            when(f.reader.read(any())).thenAnswer(invocation -> { started.countDown(); waitFor(release); return IMAGE; });
            List<CompletableFuture<Preview>> work = new ArrayList<>();
            work.add(f.service.preview("running-1")); work.add(f.service.preview("running-2"));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 20; index++) work.add(f.service.preview("queued-" + index));
            var full = assertThrows(ExecutionException.class, () -> await(f.service.preview("overflow")));
            assertTrue(full.getCause().getMessage().contains("队列已满"));
            verify(f.repository, never()).previewSource("1", "overflow");
            f.account.set("2"); release.countDown();
            for (var future : work) assertThrows(ExecutionException.class, () -> await(future));
            for (int index = 0; index < 20; index++) verify(f.repository, never()).previewSource("1", "queued-" + index);
            f.account.set("1");
            assertEquals(IMAGE, await(f.service.preview("overflow")));
        }
    }

    @Test
    void closingCompletesRunningAndQueuedRequestsAndRejectsFurtherWork() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
            when(f.reader.read(any())).thenAnswer(invocation -> { started.countDown(); waitFor(release); return IMAGE; });
            var one = f.service.preview("one"); var two = f.service.preview("two");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = f.service.preview("queued");
            f.service.close();
            for (var future : List.of(one, two, queued)) assertThrows(ExecutionException.class, () -> await(future));
            assertThrows(IllegalArgumentException.class, () -> f.service.preview("after-close"));
            verify(f.repository, never()).previewSource("1", "queued");
        }
    }

    @Test
    void unauthenticatedOrInvalidRequestsCannotReadTheRepository() {
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalArgumentException.class, () -> f.service.preview(" "));
            when(f.telegram.accountId()).thenThrow(new IllegalArgumentException("尚未授权"));
            assertThrows(IllegalArgumentException.class, () -> f.service.preview("key"));
            verifyNoInteractions(f.repository, f.reader);
        }
    }

    private static Preview await(CompletableFuture<Preview> future) throws Exception { return future.get(4, TimeUnit.SECONDS); }
    private static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic reader latch timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Synthetic reader interrupted"); }
    }
    private static Post post(String account, String key, String body) {
        return new Post(key, account, "-1001", "Synthetic channel", "100", "Synthetic post", body, "0", List.of(), List.of(),
            null, null, List.of(), false, "UNAVAILABLE", List.of());
    }
    private static final class Fixture implements AutoCloseable {
        final ResourceRepository repository = mock(ResourceRepository.class);
        final PostPreviewReader reader = mock(PostPreviewReader.class);
        final TelegramService telegram = mock(TelegramService.class);
        final AtomicReference<String> account = new AtomicReference<>("1");
        final AtomicLong time = new AtomicLong();
        final Map<String, ResourceRepository.PreviewSource> sources = new ConcurrentHashMap<>();
        final LibraryPreviewService service;
        Fixture() {
            when(telegram.accountId()).thenAnswer(invocation -> account.get());
            when(repository.previewSource(anyString(), anyString())).thenAnswer(invocation -> {
                assertTrue(Thread.currentThread().getName().startsWith("library-preview-"), "DB reads must run on bounded workers");
                String selectedAccount = invocation.getArgument(0), key = invocation.getArgument(1);
                return sources.getOrDefault(selectedAccount + ":" + key, new ResourceRepository.PreviewSource(post(selectedAccount, key, "body"), 1));
            });
            when(reader.read(any())).thenReturn(IMAGE);
            service = new LibraryPreviewService(repository, reader, telegram, time::get);
        }
        @Override public void close() { service.close(); }
    }
}
