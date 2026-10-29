package io.github.telegramenhance.telegram;

import io.github.telegramenhance.config.AppPaths;
import it.tdlight.jni.TdApi;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Only accepts static thumbnail candidates selected by PostPreviewReader. Never opens local.path. */
@Service
public final class ThumbnailFileReader {
    static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_OWNED = 128;
    private static final int MAX_LEDGER_BYTES = 64 * 1024;
    private static final long READ_BUDGET = TimeUnit.SECONDS.toNanos(4);
    private static final long FINAL_CLEANUP_BUDGET = TimeUnit.MILLISECONDS.toNanos(500);
    interface SessionGateway { <R extends TdApi.Object> CompletableFuture<R> read(long generation, TdApi.Function<R> query); }
    private final SessionGateway gateway;
    private final Supplier<String> account;
    private final LongSupplier generation;
    private final Path ledgerPath;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Set<Owned> owned = new LinkedHashSet<>();
    private final boolean ledgerReadable;
    private long operationGeneration;

    @Autowired public ThumbnailFileReader(TelegramService telegram, AppPaths paths) {
        this(new SessionGateway() {
            @Override public <R extends TdApi.Object> CompletableFuture<R> read(long generation, TdApi.Function<R> query) {
                return telegram.readInSession(generation, query);
            }
        }, telegram::accountId, paths.root().resolve("thumbnail-download-ledger.json"), telegram::sessionGeneration);
    }

    ThumbnailFileReader(ResourceReader.Gateway gateway, Supplier<String> account, Path ledgerPath) {
        this(adapt(gateway), account, ledgerPath, () -> 1L);
    }

    ThumbnailFileReader(SessionGateway gateway, Supplier<String> account, Path ledgerPath, LongSupplier generation) {
        this.gateway = gateway; this.account = account; this.generation = generation; this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
        ledgerReadable = loadLedger();
    }

    private static SessionGateway adapt(ResourceReader.Gateway gateway) {
        return new SessionGateway() {
            @Override public <R extends TdApi.Object> CompletableFuture<R> read(long generation, TdApi.Function<R> query) { return gateway.read(query); }
        };
    }

    /** Serializes ownership and cleanup, including two posts referencing the same TDLib file. */
    public synchronized byte[] read(TdApi.File candidate, String expectedAccount, long deadlineNanos) {
        operationGeneration = generation.getAsLong();
        ensureAccount(expectedAccount);
        if (candidate == null || candidate.id <= 0 || Thread.currentThread().isInterrupted()) return null;
        Owned target = owned.stream().filter(entry -> entry.account().equals(expectedAccount) && entry.fileId() == candidate.id).findFirst().orElse(null);
        long deadline = Math.min(deadlineNanos, System.nanoTime() + READ_BUDGET);
        try {
            if (candidate.size > MAX_BYTES || candidate.expectedSize > MAX_BYTES || !validUnique(uniqueId(candidate))) return null;
            TdApi.File current = call(new TdApi.GetFile(candidate.id), expectedAccount, deadline);
            if (!bounded(current, candidate.id) || !uniqueId(candidate).equals(uniqueId(current))) return null;
            // Retry a few previous owned leftovers first, with a small budget. Their failure never
            // authorizes deleting another account's cache or taking over someone else's transfer.
            cleanupPrevious(expectedAccount, target, Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)));
            if (current.local.isDownloadingCompleted) {
                return complete(current) ? readPart(current, expectedAccount, deadline) : null;
            }
            if (current.local == null || current.local.isDownloadingActive || !current.local.canBeDownloaded) return null;
            if (!ledgerReadable) return null;
            if (target != null && owned.contains(target)) {
                cleanup(target, Math.min(deadline, System.nanoTime() + FINAL_CLEANUP_BUDGET));
                if (owned.contains(target)) return null;
            }
            target = new Owned(expectedAccount, candidate.id, uniqueId(current));
            if (System.nanoTime() >= deadline || !register(target)) return null;
            TdApi.File downloaded = call(new TdApi.DownloadFile(candidate.id, 1, 0, MAX_BYTES + 1L, true), expectedAccount, deadline);
            if (!bounded(downloaded, candidate.id) || !target.uniqueId().equals(uniqueId(downloaded))
                    || downloaded.size != current.size || !complete(downloaded)) return null;
            return readPart(downloaded, expectedAccount, deadline);
        } catch (Unavailable ignored) {
            return null;
        } finally {
            if (target != null && owned.contains(target)) {
                // Preserve interruption for the caller while still making a bounded attempt to
                // release the file we registered before starting DownloadFile.
                boolean interrupted = Thread.interrupted();
                try { cleanup(target, Math.min(deadlineNanos, System.nanoTime() + FINAL_CLEANUP_BUDGET)); }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
    }

    private byte[] readPart(TdApi.File file, String expected, long deadline) {
        TdApi.Data data = call(new TdApi.ReadFilePart(file.id, 0, file.size), expected, deadline);
        if (data == null || data.data == null || data.data.length != file.size || data.data.length > MAX_BYTES) return null;
        ensureAccount(expected);
        return data.data.clone();
    }

    private boolean bounded(TdApi.File file, int expectedId) {
        return file != null && file.id == expectedId && file.size > 0 && file.size <= MAX_BYTES
            && file.expectedSize >= 0 && file.expectedSize <= MAX_BYTES && file.local != null;
    }

    private boolean complete(TdApi.File file) {
        var local = file.local;
        return local.isDownloadingCompleted && !local.isDownloadingActive && local.downloadOffset == 0
            && local.downloadedPrefixSize >= file.size && local.downloadedPrefixSize <= MAX_BYTES
            && local.downloadedSize >= file.size && local.downloadedSize <= MAX_BYTES;
    }

    private <R extends TdApi.Object> R call(TdApi.Function<R> query, String expected, long deadline) {
        ensureAccount(expected);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new Unavailable();
        try {
            R result = gateway.read(operationGeneration, query).get(remaining, TimeUnit.NANOSECONDS);
            ensureAccount(expected);
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new Unavailable();
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            ensureAccount(expected); throw new Unavailable();
        }
    }

    private void cleanupPrevious(String expected, Owned except, long deadline) {
        int attempted = 0;
        for (Owned entry : new ArrayList<>(owned)) {
            if (!entry.account().equals(expected) || entry.equals(except)) continue;
            if (attempted++ >= 4 || System.nanoTime() >= deadline) break;
            cleanup(entry, deadline);
            if (owned.remove(entry)) owned.add(entry); // Rotate failures rather than starving later entries.
        }
    }

    private void cleanup(Owned entry, long deadline) {
        // Check the account before EACH command; a switch leaves the ledger entry for that
        // account's next session instead of deleting a coincidentally equal file ID elsewhere.
        TdApi.File current = cleanupRead(new TdApi.GetFile(entry.fileId()), entry.account(), deadline);
        if (current == null || current.id != entry.fileId() || !entry.uniqueId().equals(uniqueId(current))) return;
        boolean cancelled = cleanupRead(new TdApi.CancelDownloadFile(entry.fileId(), false), entry.account(), deadline) != null;
        boolean deleted = cleanupRead(new TdApi.DeleteFile(entry.fileId()), entry.account(), deadline) != null;
        if (cancelled && deleted) {
            Set<Owned> remaining = new LinkedHashSet<>(owned); remaining.remove(entry);
            if (persist(remaining)) { owned.clear(); owned.addAll(remaining); }
        }
    }

    private <R extends TdApi.Object> R cleanupRead(TdApi.Function<R> query, String expected, long deadline) {
        ensureAccount(expected);
        try {
            CompletableFuture<R> future = gateway.read(operationGeneration, query);
            long remaining = deadline - System.nanoTime();
            R result = remaining > 0 ? future.get(remaining, TimeUnit.NANOSECONDS) : future.getNow(null);
            ensureAccount(expected);
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return null;
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            ensureAccount(expected); return null;
        }
    }

    private boolean register(Owned entry) {
        if (!ledgerReadable || !valid(entry) || owned.size() >= MAX_OWNED) return false;
        Set<Owned> next = new LinkedHashSet<>(owned); next.add(entry);
        if (!persist(next)) return false;
        owned.clear(); owned.addAll(next); return true;
    }

    private boolean loadLedger() {
        try {
            if (!Files.exists(ledgerPath)) return true;
            byte[] bytes;
            try (var input = Files.newInputStream(ledgerPath)) { bytes = input.readNBytes(MAX_LEDGER_BYTES + 1); }
            if (bytes.length > MAX_LEDGER_BYTES) return false;
            Owned[] entries = json.readValue(bytes, Owned[].class);
            if (entries == null || entries.length > MAX_OWNED) return false;
            Set<String> identities = new LinkedHashSet<>();
            for (Owned entry : entries) if (!valid(entry) || !identities.add(entry.account() + ":" + entry.fileId())) return false;
            owned.addAll(java.util.List.of(entries));
            return true;
        } catch (IOException | RuntimeException failure) { return false; }
    }

    private boolean persist(Set<Owned> entries) {
        Path temporary = ledgerPath.resolveSibling(ledgerPath.getFileName() + ".tmp");
        try {
            if (entries.size() > MAX_OWNED) return false;
            byte[] bytes = json.writeValueAsBytes(entries);
            if (bytes.length > MAX_LEDGER_BYTES) return false;
            Files.createDirectories(ledgerPath.getParent());
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.move(temporary, ledgerPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException failure) { return false; }
        finally { try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }

    private static boolean valid(Owned entry) {
        return entry != null && entry.fileId() > 0 && entry.account() != null && !entry.account().isBlank()
            && entry.account().length() <= 64 && entry.account().chars().noneMatch(Character::isISOControl) && validUnique(entry.uniqueId());
    }

    private static String uniqueId(TdApi.File file) { return file == null || file.remote == null ? null : file.remote.uniqueId; }
    private static boolean validUnique(String uniqueId) {
        return uniqueId != null && !uniqueId.isBlank() && uniqueId.length() <= 512 && uniqueId.chars().noneMatch(Character::isISOControl);
    }

    private void ensureAccount(String expected) {
        String current;
        try { current = account.get(); }
        catch (RuntimeException unavailable) { throw new IllegalArgumentException("Telegram账号已改变，请刷新资源库后重试"); }
        if (expected == null || expected.isBlank() || !expected.equals(current) || operationGeneration != generation.getAsLong()) {
            throw new IllegalArgumentException("Telegram账号或会话已改变，请刷新资源库后重试");
        }
    }

    private record Owned(String account, int fileId, String uniqueId) { }
    private static final class Unavailable extends RuntimeException { }
}
