package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.telegram.UploadTargetReader;
import io.github.telegramenhance.transfer.UploadPreviewModels.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadPreviewServiceTest {
    @TempDir Path directory;
    @Test void previewFreezesTwentyThreeMembersAsTenTenThreeAndVerificationKeepsTheSameSnapshot() throws Exception {
        try (var f = new Fixture()) { for (int i = 1; i <= 23; i++) f.file("clip" + i + ".mp4", "synthetic-" + i);
            var result = f.service.preview(f.input()).get(10, TimeUnit.SECONDS); assertEquals("PREVIEW_ONLY", result.state()); assertEquals(List.of(10,10,3), result.groups().stream().map(g -> g.files().size()).toList());
            assertEquals("clip10.mp4", result.groups().getFirst().files().get(9).caption()); var checked = f.service.verify(result.id()).get(10, TimeUnit.SECONDS);
            assertEquals(result.id(), checked.id()); assertEquals(result.groups(), checked.groups()); assertFalse(Files.exists(f.archive)); verify(f.targets, times(2)).read("link", "1", 1, java.util.Set.of("VIDEO"));
        }
    }
    @Test void oneTailMemberUsesSingleAndEmptyCaptionsStayEmpty() throws Exception {
        try (var f = new Fixture()) { for (int i = 1; i <= 21; i++) f.file(i + ".mp4", "unique-" + i);
            var result = f.service.preview(new Input("link", f.source.toString(), f.archive.toString(), true, "EMPTY")).get();
            assertEquals("SINGLE", result.groups().getLast().mode()); assertEquals(1, result.groups().getLast().files().size()); assertTrue(result.groups().stream().flatMap(g -> g.files().stream()).allMatch(m -> m.caption().isEmpty()));
        }
    }
    @Test void picturesAndUnrecognizedFilesAreIncludedWithTheirOwnType() throws Exception {
        try (var f = new Fixture()) { f.file("image.png", "picture"); f.file("unknown.xyz", "document"); var result = f.service.preview(f.input()).get(); assertEquals(2, result.fileCount()); assertEquals(List.of("PHOTO","DOCUMENT"), result.groups().stream().map(Group::mediaType).toList()); assertEquals(0, result.otherFiles()); }
    }
    @Test void additionsAreRejectedWithoutRewritingOldGroups() throws Exception {
        try (var f = new Fixture()) { f.file("first.mp4", "first"); var result = f.service.preview(f.input()).get(); f.file("new.mp4", "new");
            assertThrows(ExecutionException.class, () -> f.service.verify(result.id()).get()); assertEquals(result.groups(), f.service.get(result.id()).groups()); assertEquals(1, f.service.get(result.id()).fileCount());
        }
    }
    @Test void permissionLossAndSessionAbaCannotReuseAnEarlierPreview() throws Exception {
        try (var f = new Fixture()) { f.file("clip.mp4", "clip"); var result = f.service.preview(f.input()).get(); when(f.targets.read("link", "1", 1, java.util.Set.of("VIDEO"))).thenThrow(new IllegalArgumentException("lost permission"));
            assertThrows(RuntimeException.class, () -> f.service.verify(result.id()));
            when(f.telegram.sessionGeneration()).thenReturn(2L); assertThrows(IllegalArgumentException.class, () -> f.service.get(result.id()));
            when(f.telegram.sessionGeneration()).thenReturn(1L); assertThrows(IllegalArgumentException.class, () -> f.service.get(result.id()));
        }
    }
    @Test void onlyEightInMemoryPreviewsAreRetainedAndNoPreviewCanBecomeASendCommand() throws Exception {
        try (var f = new Fixture()) { String first = null; for (int i = 0; i < 9; i++) { var view = f.service.preview(f.input()).get(); if (i == 0) first = view.id(); assertEquals("PREVIEW_ONLY", view.state()); }
            String evicted = first; assertThrows(IllegalArgumentException.class, () -> f.service.get(evicted)); assertFalse(Files.exists(f.archive)); assertThrows(IllegalArgumentException.class, () -> f.service.preview(null));
        }
    }
    @Test void differentMediaNeverShareAnAlbumAndAnimationsAreAlwaysSingle() throws Exception {
        try (var f = new Fixture()) {
            for (int i = 1; i <= 23; i++) f.file("v" + i + ".mp4", "video" + i);
            for (String name : List.of("p1.png","p2.jpg","p3.webp","a1.mp3","a2.flac","d1.pdf","d2.zip","g1.gif","g2.gif")) f.file(name, name);
            var view = f.service.preview(f.input()).get(); assertEquals(32, view.fileCount());
            assertEquals(List.of("PHOTO","VIDEO","VIDEO","VIDEO","AUDIO","DOCUMENT","ANIMATION","ANIMATION"), view.groups().stream().map(Group::mediaType).toList());
            for (var group : view.groups()) { assertTrue(group.files().stream().allMatch(file -> file.mediaType().equals(group.mediaType()))); if (group.mediaType().equals("ANIMATION")) { assertEquals("SINGLE", group.mode()); assertEquals(1, group.files().size()); } }
            verify(f.targets).read("link", "1", 1, java.util.Set.of("PHOTO","VIDEO","AUDIO","DOCUMENT","ANIMATION"));
        }
    }
    private final class Fixture implements AutoCloseable {
        final Path source = Files.createDirectories(directory.resolve("source")), archive = directory.resolve("archive");
        final TelegramService telegram = mock(TelegramService.class); final ResourceService reads = mock(ResourceService.class); final UploadTargetReader targets = mock(UploadTargetReader.class);
        final UploadPreviewService service;
        Fixture() throws Exception {
            when(telegram.accountId()).thenReturn("1"); when(telegram.sessionGeneration()).thenReturn(1L);
            when(reads.readWork(any())).thenAnswer(invocation -> CompletableFuture.completedFuture(((Supplier<?>) invocation.getArgument(0)).get()));
            var post = new Post("p", "1", "-1001", "channel", "100", "title", "text", "0", List.of(), List.of(), "-2001", "500", List.of(), true, "PARTIAL", List.of(), null, "COMPLETE");
            when(targets.read(eq("link"), eq("1"), eq(1L), anySet())).thenReturn(new Target(post, "discussion", "MEDIA_ALLOWED_AT_CHECK", 0, 1));
            service = new UploadPreviewService(telegram, reads, targets, new UploadDirectoryScanner(new DownloadPaths(new AppPaths(directory.resolve("data")))));
        }
        Input input() { return new Input("link", source.toString(), archive.toString(), true, "FILENAME"); }
        void file(String name, String data) throws Exception { Path path = source.resolve(name); Files.writeString(path, data); Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() - 10000)); }
        public void close() { service.close(); }
    }
}
