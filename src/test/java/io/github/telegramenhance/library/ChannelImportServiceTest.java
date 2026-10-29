package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ChannelImportModels.*;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.ChannelReader;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.telegram.TelegramService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelImportServiceTest {
    @TempDir Path directory;

    @Test void channelsPreviewAndImportShareTheExistingSerialReadQueue() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var order = new ArrayList<String>();
            when(f.channels.channels()).thenAnswer(call -> {
                assertEquals("resource-reader", Thread.currentThread().getName());
                order.add("channels"); entered.countDown(); waitFor(release); return managed();
            });
            when(f.channels.preview("-1001")).thenAnswer(call -> { order.add("preview"); return preview(); });
            when(f.reader.channelPost("-1001", "100")).thenAnswer(call -> { order.add("post"); return post("account", "-1001"); });
            var channels = f.service.channels(); assertTrue(entered.await(2, TimeUnit.SECONDS));
            var preview = f.service.preview("-1001"); var imported = f.service.importPost("-1001", "100");
            verify(f.channels, never()).preview(anyString()); verifyNoInteractions(f.reader);
            release.countDown(); assertEquals(managed(), await(channels)); assertEquals(preview(), await(preview));
            assertTrue(await(imported).created()); assertEquals(List.of("channels", "preview", "post"), order);
        }
    }

    @Test void queuedImportsCannotRunAfterAnAccountOrSameAccountSessionChange() throws Exception {
        for (boolean changeAccount : List.of(true, false)) {
            try (Fixture f = new Fixture()) {
                CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
                var blocking = f.resources.readWork(() -> { entered.countDown(); waitFor(release); return true; });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var queued = f.service.importPost("-1001", "100");
                if (changeAccount) f.account.set("another-account"); else f.generation.incrementAndGet();
                release.countDown(); await(blocking); assertThrows(ExecutionException.class, () -> await(queued));
                verifyNoInteractions(f.reader, f.channels, f.repository);
            }
        }
    }

    @Test void sessionChangeWhileReadingCannotPersistTheReturnedPost() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.reader.channelPost("-1001", "100")).thenAnswer(call -> {
                f.generation.incrementAndGet(); return post("account", "-1001");
            });
            assertThrows(ExecutionException.class, () -> await(f.service.importPost("-1001", "100")));
            verifyNoInteractions(f.repository);
        }
    }

    @Test void sessionIsCheckedAgainImmediatelyBeforeSaving() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.repository.containsPost(anyString(), anyString())).thenAnswer(call -> { f.generation.incrementAndGet(); return false; });
            assertThrows(ExecutionException.class, () -> await(f.service.importPost("-1001", "100")));
            verify(f.repository, never()).savePost(any());
        }
    }

    @Test void channelsAndPreviewCannotReturnResultsAcrossAnInFlightSessionChange() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.channels.channels()).thenAnswer(call -> { f.generation.incrementAndGet(); return managed(); });
            assertThrows(ExecutionException.class, () -> await(f.service.channels()));
            when(f.channels.preview("-1001")).thenAnswer(call -> { f.account.set("another-account"); return preview(); });
            assertThrows(ExecutionException.class, () -> await(f.service.preview("-1001")));
            verifyNoInteractions(f.repository);
        }
    }

    @Test void finalSavedViewIsNotReturnedAfterTheSessionChanges() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.repository.detail(anyString(), eq(1), eq(0))).thenAnswer(call -> {
                f.generation.incrementAndGet(); return detail(post("account", "-1001"));
            });
            assertThrows(ExecutionException.class, () -> await(f.service.importPost("-1001", "100")));
            verify(f.repository).savePost(any());
        }
    }

    @Test void queueRemainsBoundedWithoutCreatingAnImportExecutor() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var blocking = f.resources.readWork(() -> { entered.countDown(); waitFor(release); return true; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var waiting = new ArrayList<CompletableFuture<ManagedChannels>>();
            for (int i = 0; i < 4; i++) waiting.add(f.service.channels());
            assertThrows(IllegalArgumentException.class, f.service::channels);
            verifyNoInteractions(f.channels);
            release.countDown(); await(blocking); for (var request : waiting) await(request);
        }
    }

    @Test void malformedIdentifiersFailBeforeEnqueueOrNativeRead() {
        try (Fixture f = new Fixture()) {
            for (String id : new String[]{null, "", "0", "-0", "+1", "01", " 1", "1.0", "9223372036854775808"}) {
                assertThrows(IllegalArgumentException.class, () -> f.service.preview(id));
                assertThrows(IllegalArgumentException.class, () -> f.service.importPost(id, "100"));
            }
            for (String id : new String[]{null, "", "0", "-1", "+1", "01", "1e3", "9223372036854775808"}) {
                assertThrows(IllegalArgumentException.class, () -> f.service.importPost("-1001", id));
            }
            verifyNoInteractions(f.reader, f.channels, f.repository, f.telegram);
        }
    }

    @Test void forgedPostOrChannelPreviewIdentitiesAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            for (Post forged : List.of(post("another-account", "-1001"), post("account", "-1002"))) {
                when(f.reader.channelPost("-1001", "100")).thenReturn(forged);
                assertThrows(ExecutionException.class, () -> await(f.service.importPost("-1001", "100")));
            }
            when(f.channels.preview("-1001")).thenReturn(new Preview(new ManagedChannel("-1002", "private"), List.of(), "RECENT_ONLY", 30, List.of()));
            assertThrows(ExecutionException.class, () -> await(f.service.preview("-1001")));
            verifyNoInteractions(f.repository);
        }
    }

    @Test void failedOwnershipReadAndDatabaseFailuresAreNotClassifiedAsMissingPosts() throws Exception {
        try (Fixture f = new Fixture()) {
            var ownership = new IllegalArgumentException("该频道不是当前账号管理的频道");
            when(f.reader.channelPost("-1001", "100")).thenThrow(ownership);
            assertSame(ownership, assertThrows(ExecutionException.class, () -> await(f.service.importPost("-1001", "100"))).getCause());
            verifyNoInteractions(f.repository);
            doReturn(post("account", "-1001")).when(f.reader).channelPost("-1001", "100");
            var broken = new IllegalStateException("Synthetic storage failure");
            when(f.repository.containsPost(anyString(), anyString())).thenThrow(broken);
            assertSame(broken, assertThrows(ExecutionException.class, () -> await(f.service.importPost("-1001", "100"))).getCause());
            verify(f.repository, never()).savePost(any());
        }
    }

    @Test void repeatedAlbumImportsPreserveCompleteCommentMediaAndCheckpoint() throws Exception {
        try (Database database = new Database(new AppPaths(directory))) {
            var repository = new ResourceRepository(database);
            try (Fixture f = new Fixture(repository)) {
                Post first = albumPost("100", "first title"), sibling = albumPost("100", "updated title");
                when(f.reader.channelPost("-1001", "100")).thenReturn(first);
                when(f.reader.channelPost("-1001", "101")).thenReturn(sibling);
                assertTrue(await(f.service.importPost("-1001", "100")).created());
                var message = new MessageRef("-2001", "601", "");
                var media = new Media("account:-2001:601:0", message, "COMMENT", "VIDEO", "synthetic.mp4", 100, "caption");
                repository.savePage(new Page(first.key(), List.of(media), List.of(message), 1, "601", true, "COMPLETE", List.of()));
                var before = repository.detail(first.key(), 10, 0);
                var result = await(f.service.importPost("-1001", "101"));
                var after = repository.detail(first.key(), 10, 0);
                assertFalse(result.created()); assertEquals(first.key(), result.post().key());
                assertEquals("updated title", result.post().title()); assertEquals("COMPLETE", result.post().coverage());
                assertEquals(before.commentMedia(), after.commentMedia()); assertEquals(before.nextCursor(), after.nextCursor());
                assertEquals(before.exhausted(), after.exhausted()); assertEquals("COMPLETE", after.commentCoverage());
                assertEquals(1, after.indexedCommentMessages()); assertEquals(1, after.indexedCommentMedia());
                assertEquals(1, repository.posts("account", 10, 0).size());
                assertTrue(repository.containsPost("account", first.key())); assertFalse(repository.containsPost("other", first.key()));
                assertFalse(repository.containsPost("account", "not-present"));
            }
        }
    }

    @Test void dtoListsAreImmutableAndDiagnosticsNeverPrintTitlesOrPostBodies() {
        String privateTitle = "synthetic-private-title", privateText = "synthetic-private-body";
        var channels = new ArrayList<ManagedChannel>(); channels.add(new ManagedChannel("-1001", privateTitle));
        var warning = new ArrayList<String>(); warning.add(privateText);
        var managed = new ManagedChannels(channels, "MANAGED_ONLY", true, warning);
        var candidate = new Candidate("100", "0", privateTitle);
        var preview = new Preview(channels.getFirst(), List.of(candidate), "RECENT_ONLY", 30, warning);
        var imported = new ImportResult(new Post("key", "account", "-1001", privateTitle, "100", privateTitle, privateText,
            "0", List.of(), List.of(), null, null, List.of(), false, "UNAVAILABLE", List.of()), true);
        channels.clear(); warning.clear(); assertEquals(1, managed.items().size()); assertEquals(1, managed.warnings().size());
        assertThrows(UnsupportedOperationException.class, () -> managed.items().clear());
        for (Object dto : List.of(managed.items().getFirst(), managed, candidate, preview, imported)) {
            assertFalse(dto.toString().contains(privateTitle)); assertFalse(dto.toString().contains(privateText));
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(4, TimeUnit.SECONDS); }
    private static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic latch timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Synthetic interruption"); }
    }
    private static ManagedChannels managed() { return new ManagedChannels(List.of(new ManagedChannel("-1001", "Synthetic")), "MANAGED_ONLY", false, List.of()); }
    private static Preview preview() { return new Preview(new ManagedChannel("-1001", "Synthetic"), List.of(new Candidate("100", "0", "Synthetic")), "RECENT_ONLY", 30, List.of()); }
    private static Detail detail(Post post) { return new Detail(post, List.of(), null, false, "PARTIAL", List.of()); }
    private static Post post(String account, String channel) {
        return new Post(account + ":" + channel + ":100", account, channel, "Synthetic", "100", "title", "body", "0",
            List.of(new MessageRef(channel, "100", "")), List.of(), "-2001", "500", List.of(new MessageRef("-2001", "500", "")),
            true, "PARTIAL", List.of(), new AlbumEvidence(true, "SINGLE_MESSAGE", null, null), "COMPLETE");
    }
    private static Post albumPost(String canonical, String title) {
        return new Post("account:-1001:album:42", "account", "-1001", "Synthetic", canonical, title, "body", "42",
            List.of(new MessageRef("-1001", "100", ""), new MessageRef("-1001", "101", "")), List.of(), "-2001", "500",
            List.of(new MessageRef("-2001", "500", "")), true, "PARTIAL", List.of(), new AlbumEvidence(true, "ADJACENT_BOUNDARIES", "102", "99"), "COMPLETE");
    }
    private static final class Fixture implements AutoCloseable {
        final ResourceReader reader = mock(ResourceReader.class);
        final ChannelReader channels = mock(ChannelReader.class);
        final TelegramService telegram = mock(TelegramService.class);
        final AtomicReference<String> account = new AtomicReference<>("account");
        final AtomicLong generation = new AtomicLong(1);
        final ResourceRepository repository;
        final ResourceService resources;
        final ChannelImportService service;
        Fixture() { this(mock(ResourceRepository.class)); }
        Fixture(ResourceRepository repository) {
            this.repository = repository;
            when(telegram.accountId()).thenAnswer(call -> account.get()); when(telegram.sessionGeneration()).thenAnswer(call -> generation.get());
            when(channels.channels()).thenReturn(managed()); when(channels.preview("-1001")).thenReturn(preview());
            when(reader.channelPost("-1001", "100")).thenReturn(post("account", "-1001"));
            if (mockingDetails(repository).isMock()) when(repository.detail(anyString(), eq(1), eq(0))).thenReturn(detail(post("account", "-1001")));
            resources = new ResourceService(reader, repository, telegram);
            service = new ChannelImportService(resources, repository, reader, channels, telegram);
        }
        @Override public void close() { resources.close(); }
    }
}
