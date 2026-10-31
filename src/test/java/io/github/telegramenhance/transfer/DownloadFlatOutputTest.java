package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadFlatOutputTest {
    @TempDir Path directory;

    @Test void validOriginalNamePublishesDirectlyInTheChosenRootWithoutHierarchyOrHashSuffix() throws Exception {
        try (var f = new Fixture()) {
            byte[] content = "original media".getBytes(StandardCharsets.UTF_8);
            String name = "旅行记录 第一期 (高清).mp4";
            var task = f.task("101", "uid-original", name, content.length, "VIDEO");
            assertEquals("", task.relativeDirectory());
            assertEquals(name, task.items().getFirst().relativeFile());
            f.save(task, content);
            assertEquals(f.root.resolve(name), f.output(task));
            assertArrayEquals(content, Files.readAllBytes(f.output(task)));
            try (var entries = Files.list(f.root)) {
                var saved = entries.toList();
                assertEquals(List.of(f.root.resolve(name)), saved);
            }
        }
    }

    @Test void differentContentWithTheSameOriginalNameUsesACollisionSuffixAndNeverOverwrites() throws Exception {
        try (var f = new Fixture()) {
            byte[] firstBytes = new byte[]{1, 2, 3, 4}, secondBytes = new byte[]{4, 3, 2, 1};
            var first = f.task("101", "first-file", "report.final.bin", firstBytes.length, "DOCUMENT");
            f.save(first, firstBytes);
            var second = f.task("102", "second-file", "report.final.bin", secondBytes.length, "DOCUMENT");
            f.save(second, secondBytes);
            assertEquals(f.root.resolve("report.final.bin"), f.output(first));
            assertEquals(f.root.resolve("report.final (2).bin"), f.output(second));
            assertArrayEquals(firstBytes, Files.readAllBytes(f.output(first)));
            assertArrayEquals(secondBytes, Files.readAllBytes(f.output(second)));
            assertNull(f.fresh(second).items().getFirst().plan().duplicate());
            assertEquals(2, f.logCount());
        }
    }

    @Test void repeatedContentGoesToTheDuplicateFolderWithoutASidecarAndKeepsDatabaseEvidence() throws Exception {
        try (var f = new Fixture()) {
            byte[] content = "same media bytes".getBytes(StandardCharsets.UTF_8);
            var first = f.task("101", "first-file", "original.txt", content.length, "DOCUMENT");
            f.save(first, content);
            var duplicate = f.task("102", "second-file", "renamed.txt", content.length, "DOCUMENT");
            f.save(duplicate, content);
            assertEquals(f.root.resolve("original.txt"), f.output(first));
            assertEquals(f.root.resolve("重复媒体/renamed.txt"), f.output(duplicate));
            var item = f.repo.view(f.fresh(duplicate)).items().getFirst();
            assertEquals("CONTENT_SHA", item.duplicate().reason());
            assertArrayEquals(content, Files.readAllBytes(f.output(duplicate)));
            assertNull(item.manifestPath());
            try (var entries = Files.list(f.root.resolve("重复媒体"))) { assertEquals(List.of(f.output(duplicate)), entries.toList()); }
            f.db.read(c -> { try (var q = c.prepareStatement("SELECT source_link,size,sha256,duplicate_of,manifest_path,output_path FROM download_completion_log WHERE item_id=?")) {
                q.setString(1, item.id()); try (var r = q.executeQuery()) {
                    assertTrue(r.next()); assertEquals("https://t.me/synthetic/102", r.getString("source_link"));
                    assertEquals(content.length, r.getLong("size")); assertEquals(DownloadPaths.hash(new String(content, StandardCharsets.UTF_8)), r.getString("sha256"));
                    assertEquals(first.items().getFirst().id(), r.getString("duplicate_of")); assertNull(r.getString("manifest_path"));
                    assertEquals(f.output(duplicate).toString(), r.getString("output_path"));
                }
            } return null; });
            assertEquals(2, f.logCount());
        }
    }

    @Test void oldDirectoryBindingCannotRelocateNewTasksOrRewriteTheExistingFrozenTask() throws Exception {
        try (var f = new Fixture()) {
            byte[] oldContent = new byte[]{1, 1, 1}, newContent = new byte[]{2, 2, 2};
            var created = f.task("101", "old-file", "old.mp4", oldContent.length, "VIDEO");
            String oldDirectory = Path.of("legacy-channel--a", "legacy-post--b").toString();
            var legacy = f.makeLegacy(created, oldDirectory, "old-generated--012345.bin", true);
            var frozen = f.fresh(legacy);
            var next = f.task("102", "new-file", "new.mp4", newContent.length, "VIDEO");
            assertEquals("", next.relativeDirectory());
            assertEquals("new.mp4", next.items().getFirst().relativeFile());
            assertEquals(frozen, f.fresh(legacy));
            assertEquals(oldDirectory, f.boundDirectory());
            f.save(legacy, oldContent);
            f.save(next, newContent);
            assertEquals(f.root.resolve(oldDirectory).resolve("old-generated--012345.bin"), f.output(legacy));
            assertEquals(f.root.resolve("new.mp4"), f.output(next));
            assertArrayEquals(oldContent, Files.readAllBytes(f.output(legacy)));
            assertArrayEquals(newContent, Files.readAllBytes(f.output(next)));
        }
    }

    @Test void oldHierarchyAndOwnedPartialCheckpointStillResumeAfterDatabaseReopen() throws Exception {
        byte[] content = new byte[1024 * 1024 + 17]; Arrays.fill(content, (byte) 7);
        String taskId, frozenDirectory, frozenName; long savedBytes;
        try (var f = new Fixture()) {
            var task = f.makeLegacy(f.task("101", "partial-legacy", "source.bin", content.length, "DOCUMENT"),
                    Path.of("old-channel", "old-post").toString(), "legacy-frozen-name.bin", true);
            var reads = new AtomicInteger();
            assertThrows(DownloadFileStore.Stopped.class, () -> f.store.write(task, task.items().getFirst(), (offset, count) -> {
                if (reads.incrementAndGet() == 2) throw new DownloadFileStore.Stopped();
                return Arrays.copyOfRange(content, (int) offset, (int) offset + count);
            }, () -> {}));
            var checkpoint = f.fresh(task);
            taskId = task.id(); frozenDirectory = task.relativeDirectory(); frozenName = task.items().getFirst().relativeFile();
            savedBytes = checkpoint.items().getFirst().writtenBytes();
            assertEquals(1024 * 1024, savedBytes);
            var next = f.task("102", "new-after-partial", "new.bin", 3, "DOCUMENT");
            assertEquals("", next.relativeDirectory());
            assertEquals(checkpoint, f.fresh(task));
        }
        try (var f = new Fixture()) {
            var restored = f.repo.task("1", taskId);
            assertEquals("PAUSED", restored.state());
            assertEquals(frozenDirectory, restored.relativeDirectory());
            assertEquals(frozenName, restored.items().getFirst().relativeFile());
            f.store.write(restored, restored.items().getFirst(), (offset, count) -> {
                assertEquals(savedBytes, offset);
                return Arrays.copyOfRange(content, (int) offset, (int) offset + count);
            }, () -> {});
            assertEquals(f.root.resolve(frozenDirectory).resolve(frozenName), f.output(restored));
            assertArrayEquals(content, Files.readAllBytes(f.output(restored)));
            assertEquals(1, f.logCount());
        }
    }

    @Test void rootLevelFinalizingOutputRecoversAfterPublishWithoutAnotherReadOrCopy() throws Exception {
        byte[] content = "published before interruption".getBytes(StandardCharsets.UTF_8);
        String taskId; Path output;
        try (var f = new Fixture()) {
            var task = f.task("101", "publish-crash", "final.bin", content.length, "DOCUMENT"); taskId = task.id();
            var interruptedRepository = spy(f.repo);
            doThrow(new IllegalStateException("injected after publish")).when(interruptedRepository)
                    .complete(anyString(), anyString(), anyString(), anyString(), any(), anyString());
            var interrupted = new DownloadFileStore(new DownloadPaths(f.app), interruptedRepository);
            assertThrows(IllegalStateException.class, () -> interrupted.write(task, task.items().getFirst(),
                    (offset, count) -> Arrays.copyOfRange(content, (int) offset, (int) offset + count), () -> {}));
            var checkpoint = f.fresh(task).items().getFirst();
            assertEquals("FINALIZING", checkpoint.state());
            assertEquals("final.bin", checkpoint.plan().relativePath());
            output = f.root.resolve("final.bin");
            assertArrayEquals(content, Files.readAllBytes(output));
            assertEquals(0, f.logCount());
        }
        try (var f = new Fixture()) {
            var restored = f.repo.task("1", taskId);
            assertTrue(f.store.recoverPublished(restored, restored.items().getFirst(), () -> {}));
            assertEquals("COMPLETED", f.fresh(restored).items().getFirst().state());
            assertEquals(output, f.output(restored));
            assertArrayEquals(content, Files.readAllBytes(output));
            assertEquals(1, f.logCount());
            try (var entries = Files.list(f.root)) { assertEquals(1, entries.count()); }
        }
    }

    @Test void missingAndWindowsInvalidNamesGetSafeStableFallbacksWithoutEscapingTheRoot() throws Exception {
        try (var f = new Fixture()) {
            var missing = f.task("101", "unnamed-video", "", 3, "VIDEO");
            var missingName = missing.items().getFirst().relativeFile();
            assertFalse(missingName.isBlank());
            assertEquals(missingName, DownloadPaths.file(missing.items().getFirst().file()));
            f.save(missing, new byte[]{1, 2, 3});
            assertEquals(f.root, f.output(missing).getParent());
            var reserved = f.task("102", "reserved-name", "CON.txt", 4, "DOCUMENT");
            assertNotEquals("CON.txt", reserved.items().getFirst().relativeFile());
            f.save(reserved, new byte[]{1, 2, 3, 4});
            assertEquals(f.root, f.output(reserved).getParent());
            var unsafe = f.task("103", "unsafe-name", "../unsafe:video?.mp4", 5, "VIDEO");
            String safe = unsafe.items().getFirst().relativeFile();
            assertFalse(safe.matches(".*[<>:\"/\\\\|?*].*"));
            f.save(unsafe, new byte[]{1, 2, 3, 4, 5});
            assertEquals(f.root, f.output(unsafe).getParent());
        }
    }

    @Test void anOriginalNameThatFitsTheCompletePathBudgetIsNotPreemptivelyShortened() {
        Path root = rootWithLength(200);
        String original = "a".repeat(35) + ".mp4";
        assertEquals(240, root.resolve(original).toString().length());
        assertEquals(original, DownloadPaths.file(namedFile(original), root));
    }

    @Test void truncationThatExposesAWindowsDeviceNameAddsASafePrefixWithinBudget() {
        Path root = rootWithLength(200);
        String extension = ".abcdefghijklmnopqrs";
        String original = "CON" + " ".repeat(32) + "X" + extension;
        assertFalse(original.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$"));
        String name = DownloadPaths.file(namedFile(original), root);
        assertTrue(name.startsWith("_CON."));
        assertTrue(name.endsWith(extension));
        assertFalse(name.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$"));
        assertTrue(root.resolve(name).toString().length() <= 240);
    }

    @Test void truncatingAnEmojiFilenameNeverSplitsASurrogatePairOrLosesItsExtension() {
        Path root = rootWithLength(200);
        String original = "a".repeat(34) + "😀".repeat(20) + ".mp4";
        String name = DownloadPaths.file(namedFile(original), root);
        assertEquals("a".repeat(34) + ".mp4", name);
        assertTrue(root.resolve(name).toString().length() <= 240);
        assertFalse(name.codePoints().anyMatch(point -> point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE));
    }

    private Path rootWithLength(int length) {
        Path filesystemRoot = directory.toAbsolutePath().getRoot();
        Path result = filesystemRoot.resolve("r".repeat(length - filesystemRoot.toString().length()));
        assertEquals(length, result.toString().length());
        return result;
    }
    private static FrozenFile namedFile(String name) {
        return new FrozenFile(new Media("1:-1001:101:0", new MessageRef("-1001", "101", ""),
                "MAIN", "DOCUMENT", name, 3, ""), "name-boundary", 3, name);
    }

    private final class Fixture implements AutoCloseable {
        final AppPaths app = new AppPaths(directory.resolve("app"));
        final Database db = new Database(app);
        final DownloadRepository repo = new DownloadRepository(db);
        final DownloadFileStore store = new DownloadFileStore(new DownloadPaths(app), repo);
        final Path root = directory.resolve("chosen-output").toAbsolutePath();
        final Post post = new Post("1:-1001:1", "1", "-1001", "channel", "1", "post", "body", "0",
                List.of(new MessageRef("-1001", "1", "https://t.me/synthetic/1")), List.of(), null, null, List.of(), false, "UNAVAILABLE", List.of());
        Task task(String message, String uniqueId, String name, int size, String type) {
            var file = new FrozenFile(new Media("1:-1001:" + message + ":0", new MessageRef("-1001", message, "https://t.me/synthetic/" + message),
                    "MAIN", type, name, size, ""), uniqueId, size, name);
            return repo.create("1", UUID.randomUUID().toString(), UUID.randomUUID().toString(), post, root, List.of(file));
        }
        Task makeLegacy(Task task, String relativeDirectory, String name, boolean bind) {
            db.write(c -> {
                try (var q = c.prepareStatement("UPDATE download_tasks SET relative_directory=? WHERE task_id=?")) {
                    q.setString(1, relativeDirectory); q.setString(2, task.id()); q.executeUpdate();
                }
                try (var q = c.prepareStatement("UPDATE download_items SET relative_file=? WHERE task_id=?")) {
                    q.setString(1, name); q.setString(2, task.id()); q.executeUpdate();
                }
                if (bind) try (var q = c.prepareStatement("INSERT INTO download_target_bindings VALUES(?,?,?,?) ON CONFLICT(account_id,root_key,post_key) DO UPDATE SET relative_directory=excluded.relative_directory")) {
                    q.setString(1, "1"); q.setString(2, DownloadPaths.key(root)); q.setString(3, post.key()); q.setString(4, relativeDirectory); q.executeUpdate();
                }
                return null;
            });
            return fresh(task);
        }
        String boundDirectory() {
            return db.read(c -> { try (var q = c.prepareStatement("SELECT relative_directory FROM download_target_bindings WHERE account_id='1' AND root_key=? AND post_key=?")) {
                q.setString(1, DownloadPaths.key(root)); q.setString(2, post.key()); try (var r = q.executeQuery()) { assertTrue(r.next()); return r.getString(1); }
            }});
        }
        Task fresh(Task task) { return repo.task("1", task.id()); }
        Path output(Task task) { return Path.of(repo.view(fresh(task)).items().getFirst().outputPath()); }
        void save(Task task, byte[] content) throws IOException {
            store.write(task, task.items().getFirst(), (offset, count) -> Arrays.copyOfRange(content, (int) offset, (int) offset + count), () -> {});
        }
        int logCount() { return db.read(c -> { try (var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM download_completion_log")) { r.next(); return r.getInt(1); }}); }
        @Override public void close() { db.close(); }
    }
}
