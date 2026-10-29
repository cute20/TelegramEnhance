package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.PostPreviewReader.Preview;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThumbnailCacheTest {
    @TempDir Path temporary;
    private static final String A = "a".repeat(64), B = "b".repeat(64), C = "c".repeat(64);
    static Preview image() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "JPEG", output);
        return new Preview("AVAILABLE", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(output.toByteArray()), 32, 24, "THUMBNAIL");
    }
    @Test void defaultIsLazyAndRealJpegsSurviveAServiceRestart() throws Exception {
        var paths = new AppPaths(temporary.resolve("data"));
        try (var database = new Database(paths)) {
            var root = temporary.resolve("project/cache"); var cache = new ThumbnailCache(database, paths, root, 4, 1024 * 1024);
            assertEquals(root.toString(), cache.view().directory()); assertFalse(Files.exists(root));
            var image = image(); cache.put(A, cache.generation(), image);
            assertEquals(1, cache.view().files()); assertEquals(image, cache.get(A, cache.generation()));
            var restarted = new ThumbnailCache(database, paths, root, 4, 1024 * 1024);
            assertEquals(image, restarted.get(A, restarted.generation())); assertEquals(1, restarted.hits()); assertEquals(0, restarted.writes());
        }
    }
    @Test void saveChangesOnlyItsOwnSettingAndDoesNotMoveOrDeleteTheOldCache() throws Exception {
        var paths = new AppPaths(temporary.resolve("data"));
        try (var database = new Database(paths)) {
            database.write(connection -> { try (var statement = connection.prepareStatement("INSERT INTO app_settings VALUES('unrelated','preserve',123)")) { statement.executeUpdate(); } return null; });
            var original = temporary.resolve("original"); var cache = new ThumbnailCache(database, paths, original, 4, 1024 * 1024);
            cache.put(A, cache.generation(), image()); long oldGeneration = cache.generation();
            var other = temporary.resolve("other"); cache.save(new ThumbnailCache.Update(other.toString()));
            cache.put(B, oldGeneration, image()); assertEquals(0, cache.view().files());
            assertTrue(Files.exists(original.resolve("twb-thumbnails-v1/thumb-" + A + ".jpg")));
            var restarted = new ThumbnailCache(database, paths, original, 4, 1024 * 1024);
            assertEquals(other.toString(), restarted.view().directory());
            assertEquals("preserve:123", database.read(connection -> { try (var rows = connection.createStatement().executeQuery("SELECT setting_value||':'||updated_at FROM app_settings WHERE setting_key='unrelated'")) { rows.next(); return rows.getString(1); } }));
        }
    }
    @Test void clearRemovesOnlyOwnedImagesAndBlocksWritesFromEarlierReads() throws Exception {
        var paths = new AppPaths(temporary.resolve("data"));
        try (var database = new Database(paths)) {
            var root = temporary.resolve("cache"); var cache = new ThumbnailCache(database, paths, root, 4, 1024 * 1024);
            cache.put(A, cache.generation(), image()); var epoch = cache.generation();
            Files.writeString(root.resolve("keep.txt"), "source"); Files.writeString(root.resolve("twb-thumbnails-v1/keep.jpg"), "unrelated");
            var partial = root.resolve("twb-thumbnails-v1/.twb-00000000-0000-0000-0000-000000000001.part"); Files.writeString(partial, "unfinished own cache");
            var result = cache.clear(); assertEquals(1, result.removed()); assertEquals(0, result.cache().files());
            assertFalse(Files.exists(partial));
            cache.put(B, epoch, image()); assertEquals(0, cache.view().files());
            assertEquals("source", Files.readString(root.resolve("keep.txt"))); assertEquals("unrelated", Files.readString(root.resolve("twb-thumbnails-v1/keep.jpg")));
            cache.put(B, cache.generation(), image()); assertEquals(1, cache.view().files());
        }
    }
    @Test void invalidCacheBytesAreAMissAndCanBeRebuilt() throws Exception {
        var paths = new AppPaths(temporary.resolve("data"));
        try (var database = new Database(paths)) {
            var root = temporary.resolve("cache"); var cache = new ThumbnailCache(database, paths, root, 4, 1024 * 1024);
            cache.put(A, cache.generation(), image());
            Files.writeString(root.resolve("twb-thumbnails-v1/thumb-" + A + ".jpg"), "corrupt");
            assertNull(cache.get(A, cache.generation())); cache.put(A, cache.generation(), image());
            assertEquals(image(), cache.get(A, cache.generation()));
        }
    }
    @Test void miniUnavailableMalformedOversizedAndMismatchedImagesAreNotPersisted() throws Exception {
        var paths = new AppPaths(temporary.resolve("data"));
        try (var database = new Database(paths)) {
            var cache = new ThumbnailCache(database, paths, temporary.resolve("cache"), 4, 1024 * 1024); var image = image();
            for (var value : new Preview[]{Preview.unavailable(), new Preview("AVAILABLE", image.dataUrl(), 32, 24, "MINI"),
                    new Preview("AVAILABLE", image.dataUrl(), 33, 24, "THUMBNAIL"), new Preview("AVAILABLE", "data:image/jpeg;base64,invalid", 32, 24, "THUMBNAIL"),
                    new Preview("AVAILABLE", "data:image/jpeg;base64," + "A".repeat(180000), 32, 24, "THUMBNAIL")}) cache.put(A, cache.generation(), value);
            assertEquals(0, cache.view().files()); assertFalse(Files.exists(temporary.resolve("cache")));
        }
    }
    @Test void evictionIsBoundedByFileCountAndUsesEarlierAccessFirst() throws Exception {
        var paths = new AppPaths(temporary.resolve("data"));
        try (var database = new Database(paths)) {
            var root = temporary.resolve("cache"); var cache = new ThumbnailCache(database, paths, root, 2, 1024 * 1024);
            cache.put(A, cache.generation(), image()); cache.put(B, cache.generation(), image());
            Files.setLastModifiedTime(root.resolve("twb-thumbnails-v1/thumb-" + A + ".jpg"), FileTime.fromMillis(100)); cache.view();
            cache.put(C, cache.generation(), image());
            assertEquals(2, cache.view().files()); assertNull(cache.get(A, cache.generation())); assertNotNull(cache.get(B, cache.generation())); assertNotNull(cache.get(C, cache.generation()));
        }
    }
    @Test void invalidPathsAndForeignNamespacesCannotBeClaimedOrCleared() throws Exception {
        var paths = new AppPaths(temporary.resolve("data"));
        try (var database = new Database(paths)) {
            var cache = new ThumbnailCache(database, paths, temporary.resolve("cache"), 4, 1024 * 1024);
            for (var path : new String[]{"relative", "\\\\server\\share", temporary.getRoot().toString(), paths.root().toString(), paths.telegram().toString(), temporary.toString()})
                assertThrows(IllegalArgumentException.class, () -> cache.save(new ThumbnailCache.Update(path)));
            var foreign = temporary.resolve("foreign/twb-thumbnails-v1"); Files.createDirectories(foreign); Files.writeString(foreign.resolve("keep.txt"), "preserved");
            assertThrows(IllegalArgumentException.class, () -> cache.save(new ThumbnailCache.Update(foreign.getParent().toString())));
            assertEquals("preserved", Files.readString(foreign.resolve("keep.txt")));
        }
    }
    @Test void byteLimitEvictsBeforePublishingAnotherImage() throws Exception {
        var paths = new AppPaths(temporary.resolve("data"));
        try (var database = new Database(paths)) {
            int bytes = Base64.getDecoder().decode(image().dataUrl().split(",")[1]).length;
            var cache = new ThumbnailCache(database, paths, temporary.resolve("cache"), 10, bytes + 1);
            cache.put(A, cache.generation(), image()); cache.put(B, cache.generation(), image());
            assertEquals(1, cache.view().files()); assertTrue(cache.view().bytes() <= bytes + 1); assertNull(cache.get(A, cache.generation()));
        }
    }
    @Test void windowsJunctionCannotRedirectCacheWritesOrClearAnotherFolder() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var paths = new AppPaths(temporary.resolve("data"));
        try (var database = new Database(paths)) {
            var root = temporary.resolve("cache"); var foreign = temporary.resolve("foreign"); var link = temporary.resolve("junction");
            Files.createDirectory(foreign); Files.writeString(foreign.resolve("keep.txt"), "preserved");
            var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), foreign.toString()).redirectErrorStream(true).start();
            assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes()));
            try {
                var cache = new ThumbnailCache(database, paths, root, 4, 1024 * 1024);
                assertThrows(IllegalArgumentException.class, () -> cache.save(new ThumbnailCache.Update(link.toString())));
                assertThrows(IllegalArgumentException.class, () -> cache.save(new ThumbnailCache.Update(link.resolve("nested").toString())));
                assertEquals("preserved", Files.readString(foreign.resolve("keep.txt")));
                assertFalse(Files.exists(foreign.resolve("twb-thumbnails-v1")));
            } finally { Files.delete(link); }
        }
    }
}
