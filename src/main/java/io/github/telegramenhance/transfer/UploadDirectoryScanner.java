package io.github.telegramenhance.transfer;

import io.github.telegramenhance.transfer.UploadPreviewModels.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** Read-only, bounded directory preparation. It never creates archive folders or moves files. */
@Component
public final class UploadDirectoryScanner {
    private static final Set<String> VIDEO = Set.of("mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "ts");
    private static final Set<String> PHOTO = Set.of("jpg", "jpeg", "png", "webp", "bmp", "tif", "tiff", "heic", "avif");
    private static final Set<String> AUDIO = Set.of("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac", "wma", "aiff", "alac");
    private static final Set<String> TEMP = Set.of("part", "tmp", "crdownload", "download", "partial", "twb-hold", "twb-part", "twb-owner");
    private final DownloadPaths paths; private final LongSupplier now;
    @org.springframework.beans.factory.annotation.Autowired
    public UploadDirectoryScanner(DownloadPaths paths) { this(paths, System::currentTimeMillis); }
    UploadDirectoryScanner(DownloadPaths paths, LongSupplier now) { this.paths = paths; this.now = now; }
    public record Directories(Path source, Path archive) { }
    /** Rule archives are siblings, derived from the canonical source directory. No creation. */
    public Directories ruleDirectories(String sourceValue) {
        Path source=paths.root(sourceValue);
        if(source.getParent()==null||source.getFileName()==null)throw new IllegalArgumentException("待上传目录请选择磁盘中的文件夹，不能使用盘符根目录");
        return directories(source.toString(),source.resolveSibling(source.getFileName()+"-已上传").toString());
    }
    public Directories directories(String sourceValue, String archiveValue) {
        Path source = paths.root(sourceValue), archive = paths.root(archiveValue);
        if (paths.containsProtectedDirectory(source) || paths.containsProtectedDirectory(archive)) throw new IllegalArgumentException("待上传和归档目录不能包含程序业务或 Telegram 数据目录，请使用独立文件夹");
        if (!Files.isDirectory(source) || !Files.isReadable(source)) throw new IllegalArgumentException("待上传目录不存在或不可读取，请先准备本机目录");
        if (source.startsWith(archive) || archive.startsWith(source)) throw new IllegalArgumentException("待上传与归档目录必须分开，不能相同或互相包含");
        Path ancestor = archive; while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (!Files.isWritable(ancestor)) throw new IllegalArgumentException("归档目录的现有父目录不可写，请更换目录");
        return new Directories(source, archive);
    }
    public Scan scan(String sourceValue, String archiveValue, boolean recursive, Runnable check) {
        var dirs = directories(sourceValue, archiveValue); long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        Runnable bounded = () -> { check.run(); if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new IllegalArgumentException("目录预览达到时间上限，请缩小目录后重新预览"); };
        var candidates = new ArrayList<Path>(); int[] counts = new int[4];
        try {
            Files.walkFileTree(dirs.source(), EnumSet.noneOf(FileVisitOption.class), recursive ? 33 : 1, new SimpleFileVisitor<>() {
                private void entry() { bounded.run(); if (++counts[0] > 10000) throw new IllegalArgumentException("目录超过10000个条目，请缩小预览范围"); }
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    entry(); if (!dir.equals(dirs.source()) && !safePath(dirs.source(), dir)) { counts[3]++; return FileVisitResult.SKIP_SUBTREE; } return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    entry(); if (!safePath(dirs.source(), file) || attrs.isSymbolicLink() || attrs.isOther()) { counts[3]++; return FileVisitResult.CONTINUE; }
                    if (attrs.isDirectory() && recursive) throw new IllegalArgumentException("目录超过32层，请缩小预览范围");
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    String name = file.getFileName().toString(); int dot = name.lastIndexOf('.');
                    if (dot >= 0 && TEMP.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) { counts[1]++; return FileVisitResult.CONTINUE; }
                    if (candidates.size() >= 500) throw new IllegalArgumentException("每次最多预览500个媒体文件，请拆分目录");
                    candidates.add(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException { throw new IOException("Unreadable source entry"); }
            });
            candidates.sort((a, b) -> natural(dirs.source().relativize(a).toString(), dirs.source().relativize(b).toString()));
            var observed = new ArrayList<FileVersion>(); var unique = new ArrayList<FileVersion>(); var hashes = new HashSet<String>(); int deferred = 0, duplicates = 0;
            for (var file : candidates) {
                bounded.run(); BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (before.size() == 0 || before.lastModifiedTime().toMillis() > now.getAsLong() - 3000) { deferred++; continue; }
                String identity = DownloadFileStore.fileKey(file); String hash = hash(file, bounded);
                BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!safePath(dirs.source(), file) || !same(before, after) || !identity.equals(DownloadFileStore.fileKey(file))) throw new IllegalArgumentException("预览期间文件发生变化，请等待复制完成后重新预览");
                String relative = dirs.source().relativize(file).toString(); if (relative.length() > 1024) throw new IllegalArgumentException("视频路径过长，请缩短目录层级");
                var version = new FileVersion(relative, mediaType(file.getFileName().toString()), after.size(), after.lastModifiedTime().toMillis(), identity, hash); observed.add(version);
                if (hashes.add(version.mediaType() + ":" + after.size() + ":" + hash)) unique.add(version); else duplicates++;
            }
            return new Scan(dirs.source().toString(), dirs.archive().toString(), recursive, observed, unique, counts[1], deferred, counts[3], duplicates);
        } catch (IOException failure) { throw new IllegalArgumentException("目录或视频无法完整读取，未生成预览；请检查权限和文件占用"); }
    }
    public void verify(Scan scan, Runnable check) {
        var fresh = scan(scan.sourceDirectory(), scan.archiveDirectory(), scan.recursive(), check);
        if (!scan.equals(fresh)) throw new IllegalArgumentException("目录内容或文件版本已变化，旧分组保留，请重新生成预览");
    }
    private static boolean safePath(Path root, Path path) throws IOException { return !Files.isSymbolicLink(path) && path.toRealPath().equals(path.toAbsolutePath().normalize()) && path.toRealPath().startsWith(root); }
    static String mediaType(String name) { int dot = name.lastIndexOf('.'); String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); return PHOTO.contains(ext) ? "PHOTO" : VIDEO.contains(ext) ? "VIDEO" : AUDIO.contains(ext) ? "AUDIO" : ext.equals("gif") ? "ANIMATION" : "DOCUMENT"; }
    private static boolean same(BasicFileAttributes a, BasicFileAttributes b) { return b.isRegularFile() && a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime()); }
    private static String hash(Path file, Runnable check) throws IOException {
        var hash = DownloadPaths.digest(); ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
        try (var input = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (true) { check.run(); buffer.clear(); int read = input.read(buffer); if (read < 0) break; if (read > 0) hash.update(buffer.array(), 0, read); }
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    static int natural(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT), y = b.toLowerCase(Locale.ROOT); int i = 0, j = 0;
        while (i < x.length() && j < y.length()) {
            char p = x.charAt(i), q = y.charAt(j);
            if (p >= '0' && p <= '9' && q >= '0' && q <= '9') {
                int ie = i, je = j; while (ie < x.length() && Character.isDigit(x.charAt(ie))) ie++; while (je < y.length() && Character.isDigit(y.charAt(je))) je++;
                String nx = x.substring(i, ie).replaceFirst("^0+(?!$)", ""), ny = y.substring(j, je).replaceFirst("^0+(?!$)", "");
                int c = Integer.compare(nx.length(), ny.length()); if (c == 0) c = nx.compareTo(ny); if (c != 0) return c; i = ie; j = je;
            } else { if (p != q) return Character.compare(p, q); i++; j++; }
        }
        int end = Integer.compare(x.length() - i, y.length() - j); return end != 0 ? end : a.compareTo(b);
    }
}
