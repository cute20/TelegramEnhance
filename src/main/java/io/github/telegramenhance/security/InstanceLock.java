package io.github.telegramenhance.security;

import io.github.telegramenhance.config.AppPaths;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

public final class InstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final AppPaths paths;
    public InstanceLock(AppPaths paths) {
        this.paths = paths;
        try {
            channel = FileChannel.open(paths.root().resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            var acquired = channel.tryLock();
            if (acquired == null) { channel.close(); throw new IllegalStateException("资源台已经运行，请打开已有窗口"); }
            lock = acquired;
        } catch (Exception e) { throw new IllegalStateException("资源台已经运行，或数据目录无法锁定", e); }
    }
    @Override public synchronized void close() {
        try { if (lock.isValid()) { Files.deleteIfExists(paths.root().resolve("runtime.json")); lock.release(); } channel.close(); }
        catch (Exception ignored) { /* OS will release the process lock on termination. */ }
    }
}
