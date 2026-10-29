package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.DownloadFileReader;
import io.github.telegramenhance.telegram.TelegramService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadTaskServiceTest {
    @Test void missingHistoricalLinksAreRepairedThroughTheBoundedReadQueueWithoutFileTransfer()throws Exception{
        try(var f=new Fixture()){
            var task=f.repository.create("1",UUID.randomUUID().toString(),"seed",DownloadPersistenceTest.post("title"),f.output,List.of(DownloadPersistenceTest.file(37,"a.bin")));
            new DownloadFileStore(new DownloadPaths(f.paths),f.repository).write(task,task.items().getFirst(),(offset,count)->new byte[count],()->{});
            when(f.files.messageLink(eq("1"),eq(1L),any())).thenReturn("https://t.me/synthetic/100");
            var repaired=f.service.repairSourceLinks().get();assertEquals(1,repaired.updated());assertEquals(0,repaired.remaining());
            verify(f.files).messageLink(eq("1"),eq(1L),any());verifyNoMoreInteractions(f.files);verifyNoInteractions(f.engine);
        }
    }
    @Test void aSessionChangeWhileResolvingHistoricalLinksDoesNotWriteTheLateResult()throws Exception{
        try(var f=new Fixture()){
            var task=f.repository.create("1",UUID.randomUUID().toString(),"seed",DownloadPersistenceTest.post("title"),f.output,List.of(DownloadPersistenceTest.file(37,"a.bin")));
            new DownloadFileStore(new DownloadPaths(f.paths),f.repository).write(task,task.items().getFirst(),(offset,count)->new byte[count],()->{});
            when(f.files.messageLink(eq("1"),eq(1L),any())).thenAnswer(call->{f.generation.incrementAndGet();return "https://t.me/synthetic/100";});
            assertThrows(java.util.concurrent.ExecutionException.class,()->f.service.repairSourceLinks().get());assertEquals(1,f.repository.missingSourceLinkCount("1"));
        }
    }
    @TempDir Path directory;
    @Test void repeatedCreationReturnsTheOriginalTaskWithoutRequiringTheExpiredPreviewAgain() throws Exception {
        try (var f = new Fixture()) {
            var input = f.input(); var first = f.service.create(input).get(); var again = f.service.create(input).get();
            assertEquals(first.id(), again.id()); assertEquals(1, first.total()); assertEquals("OBSERVED_SELECTION", first.scope());
            verify(f.sources, times(1)).selection(anyString(), anyList(), anyBoolean()); verify(f.files, times(1)).freeze(any(), anyList(), anyLong());
            assertEquals(first, f.service.lookup(input.operationId()).task()); assertFalse(Files.exists(f.output));
        }
    }
    @Test void changingTheTargetUnderTheSameOperationCannotRewriteTheTaskSnapshot() throws Exception {
        try (var f = new Fixture()) {
            var input = f.input(); var first = f.service.create(input).get();
            var changed = new DownloadTaskService.Create(input.operationId(), input.sourceId(), input.mediaKeys(), true, directory.resolve("other").toString());
            assertThrows(IllegalArgumentException.class, () -> f.service.create(changed)); assertEquals(first, f.service.lookup(input.operationId()).task());
        }
    }
    @Test void aSessionChangeDuringFileFreezingCannotCreateATask() {
        try (var f = new Fixture()) {
            when(f.files.freeze(any(), anyList(), anyLong())).thenAnswer(call -> { f.generation.incrementAndGet(); return List.of(DownloadPersistenceTest.file(37, "a.bin")); });
            assertThrows(ExecutionException.class, () -> f.service.create(f.input()).get()); assertTrue(f.repository.list("1", 20, 0).isEmpty());
        }
    }
    @Test void rejectedNativeSavePermissionsLeaveNoTaskOrOutputDirectory() {
        try (var f = new Fixture()) {
            when(f.files.freeze(any(), anyList(), anyLong())).thenThrow(new IllegalArgumentException("restricted"));
            assertThrows(ExecutionException.class, () -> f.service.create(f.input()).get()); assertFalse(Files.exists(f.output)); assertTrue(f.repository.list("1", 20, 0).isEmpty());
        }
    }
    @Test void malformedOrUnboundedSelectionsFailBeforeAnySourceRead() {
        try (var f = new Fixture()) {
            var input = f.input();
            assertThrows(IllegalArgumentException.class, () -> f.service.create(new DownloadTaskService.Create(input.operationId(), input.sourceId(), List.of(), true, f.output.toString())));
            assertThrows(IllegalArgumentException.class, () -> f.service.create(new DownloadTaskService.Create(input.operationId(), input.sourceId(), List.of("same", "same"), true, f.output.toString())));
            assertThrows(IllegalArgumentException.class, () -> f.service.create(new DownloadTaskService.Create(input.operationId(), input.sourceId(), input.mediaKeys(), true, f.paths.root().toString())));
            verifyNoInteractions(f.sources, f.files);
        }
    }
    private final class Fixture implements AutoCloseable {
        final AppPaths paths = new AppPaths(directory.resolve(UUID.randomUUID().toString())); final Database database = new Database(paths); final DownloadRepository repository = new DownloadRepository(database);
        final DownloadSourceService sources = mock(DownloadSourceService.class); final ResourceService work = mock(ResourceService.class); final DownloadFileReader files = mock(DownloadFileReader.class);
        final TelegramService telegram = mock(TelegramService.class); final DownloadEngine engine = mock(DownloadEngine.class); final AtomicLong generation = new AtomicLong(1);
        final Path output = directory.resolve("output").toAbsolutePath(); final DownloadTaskService service;
        Fixture() {
            when(telegram.accountId()).thenReturn("1"); when(telegram.sessionGeneration()).thenAnswer(call -> generation.get());
            var post = DownloadPersistenceTest.post("title"); var file = DownloadPersistenceTest.file(37, "a.bin");
            when(sources.selection(anyString(), anyList(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(new DownloadSourceService.Selection(post, List.of(file.media()))));
            when(files.freeze(any(), anyList(), anyLong())).thenReturn(List.of(file));
            when(work.readWork(any())).thenAnswer(call -> { try { return CompletableFuture.completedFuture(((Supplier<?>) call.getArgument(0)).get()); } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); } });
            service = new DownloadTaskService(sources, work, files, repository, new DownloadPaths(paths), telegram, engine);
        }
        DownloadTaskService.Create input() { return new DownloadTaskService.Create(UUID.randomUUID().toString(), UUID.randomUUID().toString(), List.of("1:-1001:100:0"), true, output.toString()); }
        @Override public void close() { database.close(); }
    }
}
