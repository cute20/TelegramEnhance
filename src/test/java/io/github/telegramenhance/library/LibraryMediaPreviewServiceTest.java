package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.telegram.PostPreviewReader;
import io.github.telegramenhance.telegram.PostPreviewReader.Preview;
import io.github.telegramenhance.telegram.TelegramService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LibraryMediaPreviewServiceTest {
    private static final Preview COMMENT = new Preview("AVAILABLE", "data:image/jpeg;base64,/9j/YQ==", 32, 24, "THUMBNAIL");
    private static final Preview MAIN = new Preview("AVAILABLE", "data:image/jpeg;base64,/9j/Yg==", 24, 32, "THUMBNAIL");

    @Test
    void commentAndMainPreviewsHaveDifferentRequestIdentitiesButUseTheSameService() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(MAIN, await(f.service.preview("post")));
            assertEquals(COMMENT, await(f.service.mediaPreview("post", "media")));
            assertEquals(MAIN, await(f.service.preview("post")));
            assertEquals(COMMENT, await(f.service.mediaPreview("post", "media")));
            verify(f.reader, times(1)).read(any()); verify(f.reader, times(1)).readMedia(any(), any());
            verify(f.repository, never()).savePage(any()); verify(f.repository, never()).savePost(any());
        }
    }

    @Test
    void scanPostAndMediaContentRevisionsInvalidateCommentCacheEvenWithinTheSameMillisecond() throws Exception {
        try (Fixture f = new Fixture()) {
            var original = f.source("1", "post", "media");
            await(f.service.mediaPreview("post", "media"));
            f.sources.put("1:post:media", new ResourceRepository.MediaPreviewSource(original.post(), original.media(), 1, 2));
            await(f.service.mediaPreview("post", "media"));
            Media edited = new Media("media", original.media().message(), "COMMENT", "PHOTO", "new-name.jpg", 100, "edited caption");
            f.sources.put("1:post:media", new ResourceRepository.MediaPreviewSource(original.post(), edited, 1, 2));
            await(f.service.mediaPreview("post", "media"));
            f.sources.put("1:post:media", new ResourceRepository.MediaPreviewSource(original.post(), edited, 2, 2));
            await(f.service.mediaPreview("post", "media"));
            Post refreshed = post("1", "post", "new main text");
            f.sources.put("1:post:media", new ResourceRepository.MediaPreviewSource(refreshed, edited, 2, 2));
            await(f.service.mediaPreview("post", "media"));
            verify(f.reader, times(5)).readMedia(any(), any()); verify(f.reader, never()).read(any());
        }
    }

    @Test
    void invalidIndexedScopeCannotReachTheNativeReader() {
        try (Fixture f = new Fixture()) {
            var original = f.source("1", "post", "media");
            Post noMapping = new Post("post", "1", "-1001", "Synthetic", "100", "title", "body", "0", List.of(), List.of(),
                null, null, List.of(), false, "UNAVAILABLE", List.of());
            List<ResourceRepository.MediaPreviewSource> invalid = List.of(
                new ResourceRepository.MediaPreviewSource(post("2", "post", "body"), original.media(), 1, 1),
                new ResourceRepository.MediaPreviewSource(post("1", "another-post", "body"), original.media(), 1, 1),
                new ResourceRepository.MediaPreviewSource(noMapping, original.media(), 1, 1),
                new ResourceRepository.MediaPreviewSource(original.post(), media("wrong-key", "-2001", "601", "PHOTO", "COMMENT"), 1, 1),
                new ResourceRepository.MediaPreviewSource(original.post(), media("media", "-9999", "601", "PHOTO", "COMMENT"), 1, 1),
                new ResourceRepository.MediaPreviewSource(original.post(), media("media", "-2001", "500", "PHOTO", "COMMENT"), 1, 1),
                new ResourceRepository.MediaPreviewSource(original.post(), media("media", "-2001", "601", "AUDIO", "COMMENT"), 1, 1),
                new ResourceRepository.MediaPreviewSource(original.post(), media("media", "-2001", "601", "PHOTO", "MAIN"), 1, 1));
            for (var source : invalid) {
                f.sources.put("1:post:media", source);
                assertThrows(ExecutionException.class, () -> await(f.service.mediaPreview("post", "media")));
            }
            verifyNoInteractions(f.reader);
        }
    }

    @Test
    void inFlightDeletionOrReassignmentRejectsTheOldImageAndDoesNotCacheIt() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.reader.readMedia(any(), any())).thenAnswer(invocation -> { started.countDown(); waitFor(release); return COMMENT; });
            var preview = f.service.mediaPreview("post", "media"); assertTrue(started.await(2, TimeUnit.SECONDS));
            f.missing.add("1:post:media"); release.countDown();
            assertThrows(ExecutionException.class, () -> await(preview));
            f.missing.clear();
            assertEquals(COMMENT, await(f.service.mediaPreview("post", "media")));
            verify(f.reader, times(2)).readMedia(any(), any());
        }
        try (Fixture f = new Fixture()) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.reader.readMedia(any(), any())).thenAnswer(invocation -> { started.countDown(); waitFor(release); return COMMENT; });
            var preview = f.service.mediaPreview("post", "media"); assertTrue(started.await(2, TimeUnit.SECONDS));
            var source = f.source("1", "post", "media");
            f.sources.put("1:post:media", new ResourceRepository.MediaPreviewSource(source.post(), media("media", "-2001", "602", "PHOTO", "COMMENT"), 1, 2));
            release.countDown();
            assertThrows(ExecutionException.class, () -> await(preview));
            verify(f.reader, times(1)).readMedia(any(), any());
        }
    }

    @Test
    void accountChangingDuringCommentReadCannotReturnThePreviousAccountsImage() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.reader.readMedia(any(), any())).thenAnswer(invocation -> { started.countDown(); waitFor(release); return COMMENT; });
            var preview = f.service.mediaPreview("post", "media"); assertTrue(started.await(2, TimeUnit.SECONDS));
            f.account.set("2"); release.countDown();
            assertThrows(ExecutionException.class, () -> await(preview));
            assertEquals(COMMENT, await(f.service.mediaPreview("post", "media")));
            verify(f.reader, times(2)).readMedia(any(), any());
        }
    }

    @Test
    void commentCallersShareWorkAndCancellingOneDoesNotCancelTheOther() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.reader.readMedia(any(), any())).thenAnswer(invocation -> { started.countDown(); waitFor(release); return COMMENT; });
            var cancelled = f.service.mediaPreview("post", "media"); assertTrue(started.await(2, TimeUnit.SECONDS));
            var remaining = f.service.mediaPreview("post", "media"); cancelled.cancel(true); release.countDown();
            assertEquals(COMMENT, await(remaining)); assertTrue(cancelled.isCancelled());
            verify(f.reader, times(1)).readMedia(any(), any());
        }
    }

    @Test
    void mainAndCommentPreviewsShareOneGlobalLruCapacity() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int index = 0; index < 64; index++) await(f.service.preview("post-" + index));
            for (int index = 0; index < 64; index++) await(f.service.mediaPreview("post", "media-" + index));
            await(f.service.mediaPreview("post", "media-64"));
            await(f.service.preview("post-0"));
            verify(f.reader, times(65)).read(any()); verify(f.reader, times(65)).readMedia(any(), any());
        }
    }

    @Test
    void mainAndCommentPreviewsShareTheTwoWorkersAndTwentyQueuedRequests() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
            when(f.reader.read(any())).thenAnswer(invocation -> { started.countDown(); waitFor(release); return MAIN; });
            when(f.reader.readMedia(any(), any())).thenAnswer(invocation -> { started.countDown(); waitFor(release); return COMMENT; });
            List<CompletableFuture<Preview>> requests = new ArrayList<>();
            requests.add(f.service.preview("main-running")); requests.add(f.service.mediaPreview("post", "comment-running"));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 10; index++) {
                requests.add(f.service.preview("main-queued-" + index)); requests.add(f.service.mediaPreview("post", "comment-queued-" + index));
            }
            var failure = assertThrows(ExecutionException.class, () -> await(f.service.mediaPreview("post", "overflow")));
            assertTrue(failure.getCause().getMessage().contains("队列已满"));
            release.countDown();
            for (var request : requests) assertNotNull(await(request));
        }
    }

    private static Preview await(CompletableFuture<Preview> future) throws Exception { return future.get(4, TimeUnit.SECONDS); }
    private static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic latch timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Synthetic interruption"); }
    }
    private static Post post(String account, String key, String body) {
        return new Post(key, account, "-1001", "Synthetic", "100", "title", body, "0", List.of(new MessageRef("-1001", "100", "")), List.of(),
            "-2001", "500", List.of(new MessageRef("-2001", "500", "")), true, "PARTIAL", List.of());
    }
    private static Media media(String key, String chat, String id, String type, String source) {
        return new Media(key, new MessageRef(chat, id, ""), source, type, "synthetic.jpg", 100, "caption");
    }
    private static final class Fixture implements AutoCloseable {
        final ResourceRepository repository = mock(ResourceRepository.class);
        final PostPreviewReader reader = mock(PostPreviewReader.class);
        final TelegramService telegram = mock(TelegramService.class);
        final AtomicReference<String> account = new AtomicReference<>("1");
        final Map<String, ResourceRepository.MediaPreviewSource> sources = new ConcurrentHashMap<>();
        final Set<String> missing = ConcurrentHashMap.newKeySet();
        final LibraryPreviewService service;
        Fixture() {
            when(telegram.accountId()).thenAnswer(invocation -> account.get());
            when(repository.previewSource(anyString(), anyString())).thenAnswer(invocation -> {
                assertTrue(Thread.currentThread().getName().startsWith("library-preview-"));
                return new ResourceRepository.PreviewSource(post(invocation.getArgument(0), invocation.getArgument(1), "body"), 1);
            });
            when(repository.mediaPreviewSource(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
                assertTrue(Thread.currentThread().getName().startsWith("library-preview-"));
                return source(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            });
            when(reader.read(any())).thenReturn(MAIN); when(reader.readMedia(any(), any())).thenReturn(COMMENT);
            service = new LibraryPreviewService(repository, reader, telegram);
        }
        ResourceRepository.MediaPreviewSource source(String account, String postKey, String mediaKey) {
            String identity = account + ":" + postKey + ":" + mediaKey;
            if (missing.contains(identity)) throw new IllegalArgumentException("Comment media no longer indexed");
            return sources.getOrDefault(identity, new ResourceRepository.MediaPreviewSource(post(account, postKey, "body"), media(mediaKey, "-2001", "601", "PHOTO", "COMMENT"), 1, 1));
        }
        @Override public void close() { service.close(); }
    }
}
