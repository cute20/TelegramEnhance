package io.github.telegramenhance.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class AppPaths {
    private final Path root;
    public AppPaths() { this(defaultRoot()); }
    static Path defaultRoot() {
        String configured = System.getProperty("workbench.data");
        return configured == null || configured.isBlank()
            ? Path.of(System.getProperty("user.dir"), "data") : Path.of(configured);
    }
    public AppPaths(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try { Files.createDirectories(this.root); Files.createDirectories(telegram()); Files.createDirectories(backups()); }
        catch (IOException e) { throw new IllegalStateException("无法创建本机数据目录", e); }
    }
    public Path root() { return root; }
    public Path database() { return root.resolve("metadata.sqlite"); }
    public Path telegram() { return root.resolve("telegram"); }
    public Path backups() { return root.resolve("backups"); }
}
