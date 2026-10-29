package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.telegram.PostPreviewReader;
import io.github.telegramenhance.telegram.PostPreviewReader.Preview;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadSourceService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DownloadThumbnailServiceTest {
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private static final Preview IMAGE = new Preview("AVAILABLE", "data:image/jpeg;base64,/9j/2Q==", 1, 1, "MINI");
    @Test void downloadsUseTheSamePreviewCacheAndNeverReadOrWriteTheLibraryRepository() throws Exception {
        try (var f = new Fixture()) {
            assertEquals(IMAGE, await(f.service.downloadMediaPreview(ID, 1, "comment")));
            assertEquals(IMAGE, await(f.service.downloadMediaPreview(ID, 1, "comment")));
            assertEquals(IMAGE, await(f.service.downloadMediaPreview(ID, 0, "main")));
            verify(f.reader, times(1)).readSourceMedia(f.post, f.comment); verify(f.reader, times(1)).readSourceMedia(f.post, f.main);
            verifyNoInteractions(f.repository);
        }
    }
    @Test void aCachedThumbnailStillRequiresAnActiveSourceGrantAndIsNotReturnedAfterExpiry() throws Exception {
        try (var f = new Fixture()) {
            await(f.service.downloadMediaPreview(ID, 1, "comment"));
            when(f.downloads.thumbnailSource(ID, 1, "comment")).thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException("expired")));
            assertThrows(ExecutionException.class, () -> await(f.service.downloadMediaPreview(ID, 1, "comment")));
            verify(f.reader, times(1)).readSourceMedia(any(), any());
        }
    }
    @Test void sourceExpiryWhileReadingCannotReturnOrCacheTheLateImage() throws Exception {
        try (var f = new Fixture()) {
            when(f.reader.readSourceMedia(f.post, f.comment)).thenAnswer(call -> {
                when(f.downloads.thumbnailSource(ID, 1, "comment")).thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException("expired"))); return IMAGE;
            });
            assertThrows(ExecutionException.class, () -> await(f.service.downloadMediaPreview(ID, 1, "comment")));
            verifyNoInteractions(f.repository);
        }
    }
    @Test void crossAccountRootAndForeignChatSnapshotsAreRejectedBeforeAnyNativeRead() throws Exception {
        try (var f = new Fixture()) {
            for (var invalid : List.of(new Media("comment", new MessageRef("-2001", "500", ""), "COMMENT", "PHOTO", "", 1, ""),
                    new Media("comment", new MessageRef("-9999", "601", ""), "COMMENT", "PHOTO", "", 1, ""),
                    new Media("comment", new MessageRef("-2001", "601", ""), "COMMENT", "AUDIO", "", 1, ""))) {
                when(f.downloads.thumbnailSource(ID, 1, "comment")).thenReturn(CompletableFuture.completedFuture(new DownloadSourceService.ThumbnailSource(f.post, invalid, 1)));
                assertThrows(ExecutionException.class, () -> await(f.service.downloadMediaPreview(ID, 1, "comment")));
            }
            when(f.telegram.accountId()).thenReturn("different-account");
            assertThrows(ExecutionException.class, () -> await(f.service.downloadMediaPreview(ID, 0, "main")));
            verifyNoInteractions(f.reader, f.repository);
        }
    }
    @Test void libraryAndDownloadPreviewsShareExactlyTwoWorkersAndDuplicateSourceRequestsCoalesce() throws Exception {
        try (var f = new Fixture()) {
            var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
            when(f.repository.previewSource("1", "post")).thenReturn(new ResourceRepository.PreviewSource(f.post, 1));
            when(f.reader.read(f.post)).thenAnswer(call -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return IMAGE; });
            when(f.reader.readSourceMedia(any(), any())).thenAnswer(call -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return IMAGE; });
            var library = f.service.preview("post"); var download = f.service.downloadMediaPreview(ID, 1, "comment");
            assertTrue(entered.await(2, TimeUnit.SECONDS)); var repeated = f.service.downloadMediaPreview(ID, 1, "comment");
            var third = f.service.downloadMediaPreview(ID, 0, "main");
            verify(f.reader, never()).readSourceMedia(f.post, f.main); release.countDown();
            assertEquals(IMAGE, await(library)); assertEquals(IMAGE, await(download)); assertEquals(IMAGE, await(repeated)); assertEquals(IMAGE, await(third));
            verify(f.reader, times(1)).readSourceMedia(f.post, f.comment);
        }
    }
    @Test void malformedSourceParametersFailBeforeLookingUpAnySource() {
        try (var f = new Fixture()) {
            assertThrows(IllegalArgumentException.class, () -> f.service.downloadMediaPreview("invalid", 1, "media"));
            assertThrows(IllegalArgumentException.class, () -> f.service.downloadMediaPreview(ID, -1, "media"));
            assertThrows(IllegalArgumentException.class, () -> f.service.downloadMediaPreview(ID, 1, ""));
            verifyNoInteractions(f.downloads, f.repository, f.reader, f.telegram);
        }
    }
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(4, TimeUnit.SECONDS); }
    private static final class Fixture implements AutoCloseable {
        final ResourceRepository repository = mock(ResourceRepository.class); final PostPreviewReader reader = mock(PostPreviewReader.class);
        final TelegramService telegram = mock(TelegramService.class); final DownloadSourceService downloads = mock(DownloadSourceService.class);
        final Media main = new Media("main", new MessageRef("-1001", "100", ""), "MAIN", "PHOTO", "", 1, "");
        final Media comment = new Media("comment", new MessageRef("-2001", "601", ""), "COMMENT", "PHOTO", "", 1, "");
        final Post post = new Post("post", "1", "-1001", "channel", "100", "title", "text", "0", List.of(main.message()), List.of(main), "-2001", "500", List.of(), true, "PARTIAL", List.of());
        final LibraryPreviewService service;
        Fixture() {
            when(telegram.accountId()).thenReturn("1");
            when(downloads.thumbnailSource(ID, 1, "comment")).thenReturn(CompletableFuture.completedFuture(new DownloadSourceService.ThumbnailSource(post, comment, 1)));
            when(downloads.thumbnailSource(ID, 0, "main")).thenReturn(CompletableFuture.completedFuture(new DownloadSourceService.ThumbnailSource(post, main, 0)));
            when(reader.readSourceMedia(any(), any())).thenReturn(IMAGE);
            service = new LibraryPreviewService(repository, reader, telegram, downloads);
        }
        @Override public void close() { service.close(); }
    }
}
