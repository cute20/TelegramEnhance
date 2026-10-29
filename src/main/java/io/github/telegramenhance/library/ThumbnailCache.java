package io.github.telegramenhance.library;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.PostPreviewReader.Preview;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Optional, bounded JPEG cache. A cache failure never changes the indexed resource or transfer. */
@Service
public final class ThumbnailCache {
    private static final String SETTING = "thumbnail.cache.directory";
    private static final String NAMESPACE = "twb-thumbnails-v1";
    private static final String OWNER = ".twb-thumbnail-cache";
    private static final String MAGIC = "TelegramResourceWorkbench thumbnail cache v1\n";
    private static final String JPEG = "data:image/jpeg;base64,";
    private static final int MAX_FILE = 128 * 1024;
    private static final int MAX_SCAN = 20_000;
    private final Database database;
    private final AppPaths paths;
    private final Path defaultDirectory;
    private final int maximumFiles;
    private final long maximumBytes;
    private Path directory;
    private Object folderIdentity;
    private final Map<String, Entry> index = new HashMap<>();
    private final Map<String, Entry> partials = new HashMap<>();
    private boolean indexed;
    private long generation;
    private String warning;
    private long hits, writes;

    public record View(String directory, String defaultDirectory, int files, long bytes, long maximumBytes, String warning) { }
    public record Update(String directory) { }
    public record Cleared(View cache, int removed) { }
    private record Entry(long size, long touched, Object identity) { }

    @Autowired public ThumbnailCache(Database database, AppPaths paths) {
        this(database, paths, Path.of(System.getProperty("user.dir")).resolve("thumbnail-cache"), 4096, 256L * 1024 * 1024);
    }
    ThumbnailCache(Database database, AppPaths paths, Path defaultDirectory, int maximumFiles, long maximumBytes) {
        this.database = database; this.paths = paths; this.defaultDirectory = defaultDirectory.toAbsolutePath().normalize();
        this.maximumFiles = maximumFiles; this.maximumBytes = maximumBytes;
        String saved = database.read(connection -> {
            try (var query = connection.prepareStatement("SELECT setting_value FROM app_settings WHERE setting_key=?")) {
                query.setString(1, SETTING);
                try (var rows = query.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
            }
        });
        directory = saved == null ? this.defaultDirectory : Path.of(saved).toAbsolutePath().normalize();
    }

    public synchronized long generation() { return generation; }
    // Process-only counters for validation; no message, credential or image data is logged.
    synchronized long hits() { return hits; }
    synchronized long writes() { return writes; }

    public synchronized View view() {
        try { refresh(false); warning = null; }
        catch (IOException | RuntimeException failure) { warning = "缓存目录暂不可用；可修改保存位置，缩略图仍可按需读取。"; }
        return snapshot();
    }

    public synchronized View save(Update update) {
        if (update == null) throw new IllegalArgumentException("请选择缩略图缓存目录");
        Path selected = validate(update.directory());
        try { ensureFolder(selected, true); }
        catch (IOException failure) { throw new IllegalArgumentException("无法使用此缩略图缓存目录，请检查路径和写入权限"); }
        database.write(connection -> {
            try (var statement = connection.prepareStatement("""
                INSERT INTO app_settings(setting_key,setting_value,updated_at) VALUES(?,?,?)
                ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value,updated_at=excluded.updated_at
                """)) {
                statement.setString(1, SETTING); statement.setString(2, selected.toString());
                statement.setLong(3, Instant.now().toEpochMilli()); statement.executeUpdate();
            }
            return null;
        });
        directory = selected; generation++; indexed = false; index.clear(); folderIdentity = null;
        return view();
    }

    public synchronized Cleared clear() {
        // Change generation even if disk deletion fails: old reads may not refill either cache.
        generation++; indexed = false;
        int removed = 0;
        try {
            refresh(false);
            for (var name : List.copyOf(index.keySet())) {
                deleteOwned(name, index.get(name)); index.remove(name); removed++;
            }
            for (var name : List.copyOf(partials.keySet())) {
                deleteOwned(name, partials.get(name)); partials.remove(name);
            }
            warning = null;
            return new Cleared(snapshot(), removed);
        } catch (IOException failure) {
            warning = "部分缩略图未能清理，请检查目录权限后重试。";
            throw new IllegalArgumentException(warning);
        }
    }

    public synchronized Preview get(String key, long expectedGeneration) {
        if (expectedGeneration != generation || !validKey(key)) return null;
        try {
            if (!indexed) refresh(false);
            Entry entry = index.get(file(key));
            if (entry == null) return null;
            Path image = ownedFile(file(key), entry);
            byte[] data;
            try (var channel = FileChannel.open(image, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                long size = channel.size();
                if (size < 4 || size > MAX_FILE) return null;
                var buffer = ByteBuffer.allocate((int) size);
                while (buffer.hasRemaining()) if (channel.read(buffer) < 0) return null;
                data = buffer.array();
            }
            Preview value = decode(data);
            if (value == null) { deleteOwned(file(key), entry); index.remove(file(key)); return null; }
            // Only touch our own file, after rechecking the directory and file identity.
            long now = System.currentTimeMillis();
            Files.setLastModifiedTime(ownedFile(file(key), entry), FileTime.fromMillis(now));
            index.put(file(key), new Entry(entry.size(), now, entry.identity())); hits++;
            return value;
        } catch (IOException | RuntimeException failure) { indexed = false; return null; }
    }

    public synchronized void put(String key, long expectedGeneration, Preview value) {
        // MINI and unavailable results remain short-lived memory entries, so failed clear reads can upgrade.
        if (expectedGeneration != generation || !validKey(key) || value == null || !"THUMBNAIL".equals(value.quality())
                || !"AVAILABLE".equals(value.status()) || value.dataUrl() == null || !value.dataUrl().startsWith(JPEG)
                || value.dataUrl().length() > JPEG.length() + 4 * ((MAX_FILE + 2) / 3)) return;
        Path temporary = null;
        try {
            byte[] data = Base64.getDecoder().decode(value.dataUrl().substring(JPEG.length()));
            Preview decoded = decode(data);
            if (decoded == null || decoded.width() != value.width() || decoded.height() != value.height()) return;
            if (!indexed) refresh(true);
            Path folder = currentFolder(true);
            Entry previous = index.get(file(key));
            if (previous != null) { ownedFile(file(key), previous); }
            else if (Files.exists(folder.resolve(file(key)), LinkOption.NOFOLLOW_LINKS)) return;
            long bytes = bytes() - (previous == null ? 0 : previous.size());
            while (index.size() - (previous == null ? 0 : 1) >= maximumFiles || bytes + data.length > maximumBytes) {
                var oldest = index.entrySet().stream().filter(entry -> !entry.getKey().equals(file(key)))
                    .min(Comparator.comparingLong(entry -> entry.getValue().touched())).orElse(null);
                if (oldest == null) return;
                deleteOwned(oldest.getKey(), oldest.getValue()); index.remove(oldest.getKey()); bytes -= oldest.getValue().size();
            }
            temporary = folder.resolve(".twb-" + UUID.randomUUID() + ".part");
            try (var output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(data); while (buffer.hasRemaining()) output.write(buffer); output.force(true);
            }
            currentFolder(false);
            if (previous != null) ownedFile(file(key), previous);
            Files.move(temporary, folder.resolve(file(key)), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            var attrs = attributes(folder.resolve(file(key)));
            index.put(file(key), new Entry(attrs.size(), attrs.lastModifiedTime().toMillis(), identity(folder.resolve(file(key))))); writes++;
        } catch (IOException | RuntimeException failure) { warning = "缩略图缓存未写入，可检查保存目录；浏览仍可继续。"; }
        finally {
            if (temporary != null) try { currentFolder(false); Files.deleteIfExists(temporary); } catch (IOException | RuntimeException ignored) { }
        }
    }

    private View snapshot() { return new View(directory.toString(), defaultDirectory.toString(), index.size(), bytes(), maximumBytes, warning); }
    private long bytes() { return index.values().stream().mapToLong(Entry::size).sum(); }
    private static boolean validKey(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String file(String key) { return "thumb-" + key + ".jpg"; }
    private static boolean ownedName(String value) { return value.matches("thumb-[0-9a-f]{64}\\.jpg"); }
    private static boolean temporaryName(String value) { return value.matches("\\.twb-[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\.part"); }

    private void refresh(boolean create) throws IOException {
        index.clear(); partials.clear(); indexed = false;
        Path folder = ensureFolder(directory, create);
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) { folderIdentity = null; indexed = true; return; }
        folderIdentity = identity(folder);
        int inspected = 0;
        try (var entries = Files.newDirectoryStream(folder)) {
            for (var path : entries) {
                if (++inspected > MAX_SCAN) throw new IOException("Cache directory limit");
                String name = path.getFileName().toString();
                if (!ownedName(name) && !temporaryName(name)) continue;
                var attrs = attributes(path);
                if (attrs.isRegularFile() && !attrs.isOther() && !attrs.isSymbolicLink())
                    (ownedName(name) ? index : partials).put(name, new Entry(attrs.size(), attrs.lastModifiedTime().toMillis(), identity(path)));
            }
        }
        indexed = true;
    }

    private Path ownedFile(String name, Entry expected) throws IOException {
        if (!ownedName(name) && !temporaryName(name)) throw new IOException("Not a cache file");
        Path file = currentFolder(false).resolve(name);
        var attrs = attributes(file);
        if (!attrs.isRegularFile() || attrs.isOther() || attrs.isSymbolicLink()
                || !Objects.equals(expected.identity(), identity(file)) || attrs.size() != expected.size()) throw new IOException("Cache file changed");
        return file;
    }
    private void deleteOwned(String name, Entry entry) throws IOException { Files.delete(ownedFile(name, entry)); }
    private Path currentFolder(boolean create) throws IOException {
        Path folder = ensureFolder(directory, create);
        Object actual = identity(folder);
        if (folderIdentity != null && !Objects.equals(folderIdentity, actual)) throw new IOException("Cache directory changed");
        folderIdentity = actual;
        return folder;
    }

    private Path validate(String value) {
        try {
            if (value == null || value.isBlank() || value.length() > 200 || value.startsWith("\\\\") || value.chars().anyMatch(Character::isISOControl)) throw new IOException();
            Path selected = Path.of(value.strip());
            if (!selected.isAbsolute() || selected.getRoot().toString().startsWith("\\\\") || selected.normalize().equals(selected.getRoot())) throw new IOException();
            selected = selected.normalize();
            // NTFS alternate streams are not directories. Reject other colons after the drive prefix.
            if (System.getProperty("os.name").startsWith("Windows") && selected.toString().substring(2).contains(":")) throw new IOException();
            checkPath(selected);
            Path protectedRoot = paths.root().toRealPath();
            if (selected.startsWith(protectedRoot) || protectedRoot.startsWith(selected)) throw new IOException();
            return selected;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("请选择本机绝对目录，不能使用磁盘根目录、链接目录或应用数据目录");
        }
    }
    private Path ensureFolder(Path root, boolean create) throws IOException {
        validate(root.toString()); checkPath(root);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return root.resolve(NAMESPACE);
            Files.createDirectories(root); checkPath(root);
        }
        Path folder = root.resolve(NAMESPACE);
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return folder;
            Files.createDirectory(folder);
        }
        checkPath(folder);
        Path owner = folder.resolve(OWNER);
        if (!Files.exists(owner, LinkOption.NOFOLLOW_LINKS) && create) {
            try (var entries = Files.newDirectoryStream(folder)) { if (entries.iterator().hasNext()) throw new IOException("Unknown existing directory"); }
            Files.writeString(owner, MAGIC, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
        var marker = attributes(owner);
        if (!marker.isRegularFile() || marker.isSymbolicLink() || marker.isOther() || marker.size() != MAGIC.length() || !Files.readString(owner).equals(MAGIC)) throw new IOException("Unknown cache owner");
        return folder;
    }
    private static void checkPath(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue;
            var attrs = attributes(current);
            if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther() || !current.toRealPath().equals(current.toAbsolutePath().normalize())) throw new IOException("Linked directory");
        }
    }
    private static BasicFileAttributes attributes(Path path) throws IOException { return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
    private static String identity(Path path) throws IOException {
        Object key = attributes(path).fileKey();
        if (key != null) return key.toString();
        if (!Platform.isWindows()) throw new IOException("Filesystem identity unavailable");
        var handle = Kernel32.INSTANCE.CreateFile(path.toString(), WinNT.FILE_READ_ATTRIBUTES,
            WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE, null, WinNT.OPEN_EXISTING,
            WinNT.FILE_FLAG_OPEN_REPARSE_POINT | WinNT.FILE_FLAG_BACKUP_SEMANTICS, null);
        if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw new IOException("Cannot identify cache path");
        try {
            var info = new WinBase.FILE_ID_INFO();
            if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, WinBase.FileIdInfo, info.getPointer(), new WinDef.DWORD(info.size()))) throw new IOException("Cannot identify cache path");
            info.read(); byte[] identifier = new byte[info.FileId.Identifier.length];
            for (int i = 0; i < identifier.length; i++) identifier[i] = info.FileId.Identifier[i].byteValue();
            return Long.toUnsignedString(info.VolumeSerialNumber, 16) + ":" + HexFormat.of().formatHex(identifier);
        } finally { Kernel32.INSTANCE.CloseHandle(handle); }
    }
    private static Preview decode(byte[] data) throws IOException {
        if (data.length < 4 || data.length > MAX_FILE || data[0] != (byte) 0xff || data[1] != (byte) 0xd8
                || data[data.length - 2] != (byte) 0xff || data[data.length - 1] != (byte) 0xd9) return null;
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (!"JPEG".equalsIgnoreCase(reader.getFormatName()) || width < 1 || height < 1 || width > 480 || height > 480) return null;
                var image = reader.read(0); if (image == null || image.getWidth() != width || image.getHeight() != height) return null;
                return new Preview("AVAILABLE", JPEG + Base64.getEncoder().encodeToString(data), width, height, "THUMBNAIL");
            } finally { reader.dispose(); }
        }
    }
}
