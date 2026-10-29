package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.PostPreviewReader;
import io.github.telegramenhance.telegram.PostPreviewReader.Preview;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadSourceService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentThumbnailPreviewTest {
    @TempDir Path temporary;
    private static final String ID1 = "00000000-0000-0000-0000-000000000001", ID2 = "00000000-0000-0000-0000-000000000002";
    private static Post post(String account, String text) {
        return new Post("post", account, "-1001", "channel", "100", "title", text, "0", List.of(), List.of(), "-2001", "500", List.of(), true, "PARTIAL", List.of());
    }
    private static Preview await(CompletableFuture<Preview> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    @Test void restartReadsDiskWithoutCallingTelegramAndSavedRevisionChangesInvalidateIt() throws Exception {
        var paths = new AppPaths(temporary.resolve("data"));
        try (var database = new Database(paths)) {
            var cache = new ThumbnailCache(database, paths, temporary.resolve("cache"), 10, 1024 * 1024);
            var repository = mock(ResourceRepository.class); var reader = mock(PostPreviewReader.class); var telegram = mock(TelegramService.class);
            when(telegram.accountId()).thenReturn("1"); when(reader.read(any())).thenReturn(ThumbnailCacheTest.image());
            when(repository.previewSource("1", "post")).thenReturn(new ResourceRepository.PreviewSource(post("1", "body"), 1));
            try (var service = new LibraryPreviewService(repository, reader, telegram, null, cache)) { await(service.preview("post")); }
            var restarted = new ThumbnailCache(database, paths, temporary.resolve("cache"), 10, 1024 * 1024);
            try (var service = new LibraryPreviewService(repository, reader, telegram, null, restarted)) {
                assertEquals(ThumbnailCacheTest.image(), await(service.preview("post"))); verify(reader, times(1)).read(any()); assertEquals(1, restarted.hits());
                when(repository.previewSource("1", "post")).thenReturn(new ResourceRepository.PreviewSource(post("1", "changed within same millisecond"), 1));
                await(service.preview("post")); verify(reader, times(2)).read(any());
                when(repository.previewSource("1", "post")).thenReturn(new ResourceRepository.PreviewSource(post("1", "changed within same millisecond"), 2));
                await(service.preview("post")); verify(reader, times(3)).read(any());
            }
        }
    }
    @Test void cachedCommentCannotBypassCurrentAccountOrOwnershipChecks() throws Exception {
        var paths = new AppPaths(temporary.resolve("data"));
        try (var database = new Database(paths)) {
            var cache = new ThumbnailCache(database, paths, temporary.resolve("cache"), 10, 1024 * 1024);
            var repository = mock(ResourceRepository.class); var reader = mock(PostPreviewReader.class); var telegram = mock(TelegramService.class);
            var media = new Media("media", new MessageRef("-2001", "601", ""), "COMMENT", "PHOTO", "photo.jpg", 10, "caption");
            when(telegram.accountId()).thenReturn("1"); when(reader.readMedia(any(), any())).thenReturn(ThumbnailCacheTest.image());
            when(repository.mediaPreviewSource("1", "post", "media")).thenReturn(new ResourceRepository.MediaPreviewSource(post("1", "body"), media, 1, 1));
            try (var service = new LibraryPreviewService(repository, reader, telegram, null, cache)) { await(service.mediaPreview("post", "media")); }
            try (var service = new LibraryPreviewService(repository, reader, telegram, null, cache)) {
                await(service.mediaPreview("post", "media")); verify(reader, times(1)).readMedia(any(), any());
                when(telegram.accountId()).thenReturn("2");
                when(repository.mediaPreviewSource("2", "post", "media")).thenReturn(new ResourceRepository.MediaPreviewSource(post("2", "body"), media, 1, 1));
                await(service.mediaPreview("post", "media")); verify(reader, times(2)).readMedia(any(), any());
                when(repository.mediaPreviewSource("2", "post", "media")).thenThrow(new IllegalArgumentException("missing saved ownership"));
                assertThrows(ExecutionException.class, () -> await(service.mediaPreview("post", "media")));
                verify(reader, times(2)).readMedia(any(), any());
            }
        }
    }
    @Test void newTransientDownloadPreviewIdsCanReuseTheSameVerifiedSourceImage() throws Exception {
        var paths = new AppPaths(temporary.resolve("data"));
        try (var database = new Database(paths)) {
            var cache = new ThumbnailCache(database, paths, temporary.resolve("cache"), 10, 1024 * 1024);
            var repository = mock(ResourceRepository.class); var reader = mock(PostPreviewReader.class); var telegram = mock(TelegramService.class); var downloads = mock(DownloadSourceService.class);
            var media = new Media("media", new MessageRef("-2001", "601", ""), "COMMENT", "VIDEO", "test.mp4", 10, "caption");
            when(telegram.accountId()).thenReturn("1"); when(reader.readSourceMedia(any(), any())).thenReturn(ThumbnailCacheTest.image());
            for (var id : List.of(ID1, ID2)) when(downloads.thumbnailSource(id, 1, "media")).thenReturn(CompletableFuture.completedFuture(new DownloadSourceService.ThumbnailSource(post("1", "body"), media, 1)));
            try (var service = new LibraryPreviewService(repository, reader, telegram, downloads, cache)) {
                await(service.downloadMediaPreview(ID1, 1, "media")); await(service.downloadMediaPreview(ID2, 1, "media"));
                verify(reader, times(1)).readSourceMedia(any(), any()); assertEquals(1, cache.view().files());
                when(downloads.thumbnailSource(ID2, 1, "media")).thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException("expired")));
                assertThrows(ExecutionException.class, () -> await(service.downloadMediaPreview(ID2, 1, "media")));
            }
        }
    }
    @Test void clearDuringNativeReadCannotRefillDiskMemoryOrJoinTheNextGeneration() throws Exception {
        var paths = new AppPaths(temporary.resolve("data"));
        try (var database = new Database(paths)) {
            var cache = new ThumbnailCache(database, paths, temporary.resolve("cache"), 10, 1024 * 1024);
            var repository = mock(ResourceRepository.class); var reader = mock(PostPreviewReader.class); var telegram = mock(TelegramService.class);
            when(telegram.accountId()).thenReturn("1"); when(repository.previewSource("1", "post")).thenReturn(new ResourceRepository.PreviewSource(post("1", "body"), 1));
            var started = new CountDownLatch(1); var release = new CountDownLatch(1); var reads = new AtomicInteger();
            when(reader.read(any())).thenAnswer(call -> { if (reads.incrementAndGet() == 1) { started.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS)); } return ThumbnailCacheTest.image(); });
            try (var service = new LibraryPreviewService(repository, reader, telegram, null, cache)) {
                var old = service.preview("post"); assertTrue(started.await(2, TimeUnit.SECONDS));
                cache.clear(); service.clearMemory();
                assertEquals(0, cache.view().files());
                await(service.preview("post")); assertEquals(1, cache.writes());
                release.countDown(); await(old); assertEquals(1, cache.writes());
                await(service.preview("post")); assertEquals(2, reads.get());
            } finally { release.countDown(); }
        }
    }
}
