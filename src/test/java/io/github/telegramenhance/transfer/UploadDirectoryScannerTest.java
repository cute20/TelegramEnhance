package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.config.AppPaths;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadDirectoryScannerTest {
    @TempDir Path directory;
    Path source, archive; long now = System.currentTimeMillis();
    UploadDirectoryScanner scanner() throws Exception { source = Files.createDirectories(directory.resolve("source")); archive = directory.resolve("archive"); return new UploadDirectoryScanner(new DownloadPaths(new AppPaths(directory.resolve("data"))), () -> now); }
    Path file(String name, String content) throws Exception { Path path = source.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, content); Files.setLastModifiedTime(path, FileTime.fromMillis(now - 10000)); return path; }
    @Test void twentyThreeDistinctCandidatesUseNaturalOrderAndDoNotCreateOrModifyAnyOutput() throws Exception {
        var scanner = scanner(); for (int i = 23; i >= 1; i--) file("clip" + i + ".mp4", "synthetic-fixture-" + i);
        var scan = scanner.scan(source.toString(), archive.toString(), true, () -> {});
        assertEquals(23, scan.files().size()); assertEquals("clip2.mp4", scan.files().get(1).relativePath()); assertEquals("clip10.mp4", scan.files().get(9).relativePath());
        assertFalse(Files.exists(archive)); assertEquals("synthetic-fixture-1", Files.readString(source.resolve("clip1.mp4")));
    }
    @Test void identicalContentsAreExcludedButSameNamesInOtherDirectoriesWithDifferentContentsRemain() throws Exception {
        var scanner = scanner(); file("clip2.mp4", "one"); file("clip10.mp4", "one"); file("child/clip2.mp4", "two");
        var scan = scanner.scan(source.toString(), archive.toString(), true, () -> {});
        assertEquals(3, scan.observed().size()); assertEquals(2, scan.files().size()); assertEquals(1, scan.duplicateFiles());
        assertTrue(Files.exists(source.resolve("clip10.mp4")));
    }
    @Test void emptyRecentAndNonVideoFilesAreExplicitlyCountedAndRecursionIsIndependent() throws Exception {
        var scanner = scanner(); file("ready.mp4", "ready"); file("empty.mp4", ""); file("recent.mp4", "busy"); Files.setLastModifiedTime(source.resolve("recent.mp4"), FileTime.fromMillis(now)); file("image.png", "picture"); file("copy.tmp", "temporary"); file("child/other.mp4", "other");
        var scan = scanner.scan(source.toString(), archive.toString(), false, () -> {});
        assertEquals(2, scan.files().size()); assertEquals(2, scan.deferredFiles()); assertEquals(1, scan.otherFiles());
        assertEquals(3, scanner.scan(source.toString(), archive.toString(), true, () -> {}).files().size());
    }
    @Test void verificationRejectsSameLengthContentChangesEvenWhenTheTimestampIsRestored() throws Exception {
        var scanner = scanner(); Path file = file("clip.mp4", "first"); var scan = scanner.scan(source.toString(), archive.toString(), true, () -> {});
        Files.writeString(file, "other"); Files.setLastModifiedTime(file, FileTime.fromMillis(now - 10000));
        assertThrows(IllegalArgumentException.class, () -> scanner.verify(scan, () -> {}));
        assertEquals("first".length(), scan.files().getFirst().size());
    }
    @Test void newVideosDoNotSilentlyJoinAFrozenPreview() throws Exception {
        var scanner = scanner(); file("first.mp4", "first"); var scan = scanner.scan(source.toString(), archive.toString(), true, () -> {}); file("new.mp4", "new");
        assertThrows(IllegalArgumentException.class, () -> scanner.verify(scan, () -> {})); assertEquals(1, scan.files().size());
    }
    @Test void overlappingBusinessMissingAndUncDirectoriesAreRejected() throws Exception {
        var scanner = scanner();
        for (String target : List.of(source.toString(), source.resolve("archive").toString(), directory.toString())) assertThrows(IllegalArgumentException.class, () -> scanner.directories(source.toString(), target));
        assertThrows(IllegalArgumentException.class, () -> scanner.directories(directory.resolve("data").toString(), archive.toString()));
        assertThrows(IllegalArgumentException.class, () -> scanner.directories(directory.resolve("missing").toString(), archive.toString()));
        assertThrows(IllegalArgumentException.class, () -> scanner.directories("\\\\server\\share", archive.toString()));
        assertThrows(IllegalArgumentException.class, () -> scanner.directories("//server/share", archive.toString()));
        assertThrows(IllegalArgumentException.class, () -> scanner.directories(directory.toString(), directory.resolveSibling("archive-validation").toString()));
    }
    @Test void candidateAndCancellationLimitsDoNotReturnAnApparentlyCompletePartialManifest() throws Exception {
        var scanner = scanner(); for (int i = 0; i < 501; i++) file("clip" + i + ".mp4", "x" + i);
        assertThrows(IllegalArgumentException.class, () -> scanner.scan(source.toString(), archive.toString(), true, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> scanner.scan(source.toString(), archive.toString(), true, () -> { throw new IllegalArgumentException("account changed"); }));
        assertFalse(Files.exists(archive));
    }
}
