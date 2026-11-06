package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.telegram.TelegramService;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResourceSessionIsolationTest {
    @Test void queuedProbeRefreshCommentsAndSharedReadsKeepTheirSubmittingAccountAndSession() throws Exception {
        for (boolean switchAccount : List.of(true, false)) {
            for (String operation : List.of("probe", "refresh", "comments", "shared")) {
                try (var f = new Fixture()) {
                    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                    var blocker = f.service.readWork(() -> {
                        entered.countDown();
                        try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("reader not released"); }
                        catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                        return true;
                    });
                    assertTrue(entered.await(2, TimeUnit.SECONDS));
                    try {
                        CompletableFuture<?> queued = switch (operation) {
                            case "probe" -> f.service.probe("link");
                            case "refresh" -> f.service.refresh("post");
                            case "comments" -> f.service.comments("post", null);
                            default -> f.service.readWork(() -> { fail("obsolete shared read executed"); return true; });
                        };
                        f.change(switchAccount); release.countDown();
                        assertThrows(ExecutionException.class, () -> await(blocker));
                        assertThrows(ExecutionException.class, () -> await(queued));
                        verifyNoInteractions(f.reader, f.repository);
                    } finally { release.countDown(); }
                }
            }
        }
    }

    @Test void anInFlightAccountOrSameAccountSessionChangeCannotPersistMetadata() throws Exception {
        for (boolean switchAccount : List.of(true, false)) {
            for (String operation : List.of("probe", "refresh", "comments")) {
                try (var f = new Fixture()) {
                    when(f.repository.detail("post", 1, 0)).thenReturn(f.detail());
                    when(f.reader.probe("link")).thenAnswer(call -> { f.change(switchAccount); return f.post; });
                    when(f.reader.refresh(f.post)).thenAnswer(call -> { f.change(switchAccount); return f.post; });
                    when(f.reader.comments(f.post, null)).thenAnswer(call -> {
                        f.change(switchAccount); return new Page("post", List.of(), List.of(), 0, null, true, "COMPLETE", List.of());
                    });
                    var result = switch (operation) {
                        case "probe" -> f.service.probe("link");
                        case "refresh" -> f.service.refresh("post");
                        default -> f.service.comments("post", null);
                    };
                    assertThrows(ExecutionException.class, () -> await(result));
                    verify(f.repository, never()).savePost(any());
                    verify(f.repository, never()).savePage(any());
                }
            }
        }
    }

    @Test void synchronousCatalogAndDetailReadsCannotReturnAnObsoleteSessionSnapshot() {
        for (boolean list : List.of(true, false)) try (var f = new Fixture()) {
            when(f.repository.posts("account", 20, 0)).thenAnswer(call -> { f.generation.incrementAndGet(); return List.of(f.post); });
            when(f.repository.detail("post", 1, 0)).thenAnswer(call -> { f.generation.incrementAndGet(); return f.detail(); });
            assertThrows(IllegalArgumentException.class, () -> {
                if (list) f.service.posts(20, 0); else f.service.detail("post", 1, 0);
            });
        }
    }

    private static <T> T await(CompletableFuture<T> work) throws Exception { return work.get(3, TimeUnit.SECONDS); }
    private static final class Fixture implements AutoCloseable {
        final ResourceRepository repository = mock(ResourceRepository.class);
        final ResourceReader reader = mock(ResourceReader.class);
        final TelegramService telegram = mock(TelegramService.class);
        final AtomicReference<String> account = new AtomicReference<>("account");
        final AtomicLong generation = new AtomicLong(1);
        final Post post = new Post("post", "account", "channel", "channel", "1", "title", "text", "0",
                List.of(), List.of(), "discussion", "50", List.of(), true, "PARTIAL", List.of(),
                new AlbumEvidence(true, "SINGLE_MESSAGE", null, null), "COMPLETE");
        final ResourceService service;
        Fixture() {
            when(telegram.accountId()).thenAnswer(call -> account.get());
            when(telegram.sessionGeneration()).thenAnswer(call -> generation.get());
            service = new ResourceService(reader, repository, telegram);
        }
        Detail detail() { return new Detail(post, List.of(), null, false, "PARTIAL", List.of()); }
        void change(boolean switchAccount) { if (switchAccount) account.set("other-account"); generation.incrementAndGet(); }
        @Override public void close() { service.close(); }
    }
}
