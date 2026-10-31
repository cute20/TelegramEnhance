package io.github.telegramenhance.transfer;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.transfer.DownloadModels.FrozenFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public final class DownloadPaths {
    private final Path dataRoot;
    public DownloadPaths(AppPaths paths) { dataRoot = paths.root(); }
    public Path root(String value) {
        try {
            if (value == null || value.isBlank() || value.length() > 200 || value.startsWith("\\\\")) throw new IllegalArgumentException();
            Path path = Path.of(value.strip());
            if (!path.isAbsolute() || path.getRoot().toString().startsWith("\\\\")) throw new IllegalArgumentException();
            path = path.normalize(); Path existing = path;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing == null || !Files.isDirectory(existing)) throw new IllegalArgumentException();
            Path canonical = existing.toRealPath().resolve(existing.relativize(path)).normalize();
            Path protectedRoot = Files.exists(dataRoot) ? dataRoot.toRealPath() : dataRoot;
            if (canonical.startsWith(protectedRoot)) throw new IllegalArgumentException();
            return canonical;
        } catch (Exception failure) { throw new IllegalArgumentException("请选择本机磁盘上的绝对保存目录，且不能放在程序业务或 Telegram 数据目录内"); }
    }
    boolean containsProtectedDirectory(Path path) {
        try { return dataRoot.toRealPath().startsWith(path); }
        catch (Exception failure) { throw new IllegalArgumentException("无法核对程序数据目录，请稍后重试"); }
    }
    public static String key(Path path) { return path.toAbsolutePath().normalize().toString().toUpperCase(Locale.ROOT); }
    public static String directory(Post post) {
        // New tasks use exactly the selected directory. Existing tasks keep their persisted relativeDirectory.
        return "";
    }
    public static String file(FrozenFile file) {
        return file(file, 240);
    }
    public static String file(FrozenFile file, Path root) {
        // Keep the initial path within 240 UTF-16 units; the Windows path limit still has room for duplicate routing and a suffix.
        int available = 240 - root.toString().length() - 1;
        if (available < 24) throw new IllegalArgumentException("保存路径过长，请选择更短的根目录");
        return file(file, Math.min(240, available));
    }
    private static String file(FrozenFile file, int max) {
        String name = file.fileName();
        if (name == null || name.isBlank()) name = file.media().fileName();
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")) {
            String type = switch (file.media().type()) { case "PHOTO" -> "图片"; case "VIDEO" -> "视频"; case "AUDIO" -> "音频"; default -> "文件"; };
            name = type + "_" + file.media().message().messageId() + "_" + hash(file.uniqueId()).substring(0, 12);
        }
        String clean = fileName(name);
        if (clean.length() <= max) return clean;
        int dot = clean.lastIndexOf('.');
        String extension = dot > 0 && clean.length() - dot <= 20 ? clean.substring(dot) : "";
        String base = extension.isEmpty() ? clean : clean.substring(0, dot);
        String candidate = fileName(clip(base, max - extension.length()).replaceAll("[. ]+$", "") + extension);
        // Truncation can expose a reserved device name that was not reserved in the original name.
        return candidate.length() <= max ? candidate : clip(candidate.substring(0, candidate.length() - extension.length()), max - extension.length()) + extension;
    }
    private static String clip(String value, int max) {
        int end = Math.min(value.length(), max);
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
    private static String fileName(String value) {
        // Preserve valid source spelling, including Unicode composition and leading spaces.
        String clean = value.replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_").replaceAll("[. ]+$", "");
        if (clean.isBlank() || clean.equals(".") || clean.equals("..")) clean = "未命名";
        return clean.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$") ? "_" + clean : clean;
    }
    public static String safe(String value, int max) {
        String clean = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC).replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_").strip().replaceAll("[. ]+$", "");
        if (clean.isBlank() || clean.equals(".") || clean.equals("..")) clean = "未命名";
        if (clean.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")) clean = "_" + clean;
        int length = clean.codePointCount(0, clean.length());
        return length > max ? clean.substring(0, clean.offsetByCodePoints(0, max)) : clean;
    }
    public static String hash(String value) { return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8))); }
    public static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (Exception impossible) { throw new IllegalStateException("本机摘要算法不可用"); }
    }
}
