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
        return Path.of(safe(post.channelTitle(), 32) + "--" + hash(post.accountId() + ":" + post.channelId()).substring(0, 12),
                safe(post.title(), 32) + "--" + hash(post.key()).substring(0, 12)).toString();
    }
    public static String file(FrozenFile file) {
        String name = file.fileName() == null ? "" : file.fileName(); int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.substring(dot + 1).matches("[A-Za-z0-9]{1,10}") ? name.substring(dot) : "";
        String base = dot > 0 && !extension.isEmpty() ? name.substring(0, dot) : name;
        return safe(base, 40) + "--" + hash(file.media().key()).substring(0, 10) + "-" + hash(file.uniqueId()).substring(0, 12) + extension;
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
