package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.DownloadFileReader;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadEngineTest {
    @TempDir Path directory;
    @Test void aRealFileAndReceiptAreCommittedBeforeTheTaskBecomesCompleteAndOwnedCacheIsReleased() throws Exception {
        try (var f = new Fixture()) {
            var task = f.task(); var complete = new AtomicBoolean();
            when(f.reader.status(anyString(), anyLong(), anyInt(), anyString(), anyLong())).thenAnswer(call -> new DownloadFileReader.Status(complete.get() ? 37 : 0, complete.get(), false, true));
            doAnswer(call -> { complete.set(true); return null; }).when(f.reader).start(anyString(), anyLong(), anyInt(), anyString(), anyLong());
            f.start(); Task done = f.await(task.id(), "COMPLETED");
            var item = done.items().getFirst(); assertEquals(37, item.writtenBytes()); assertNotNull(item.sha256());
            assertArrayEquals(new byte[37], Files.readAllBytes(Path.of(done.targetRoot()).resolve(done.relativeDirectory()).resolve(item.relativeFile())));
            assertTrue(f.repository.owners("1", 20).isEmpty()); verify(f.reader).release("1", 1, 7, "stable-unique", true);
        }
    }
    @Test void pauseCancelsOnlyOwnedNativeActivityAndResumeKeepsTheFrozenFileSnapshot() throws Exception {
        try (var f = new Fixture()) {
            var complete = new AtomicBoolean(); var task = f.task();
            when(f.reader.status(anyString(), anyLong(), anyInt(), anyString(), anyLong())).thenAnswer(call -> new DownloadFileReader.Status(complete.get() ? 37 : 1, complete.get(), false, true));
            f.start(); long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!f.repository.task("1", task.id()).items().getFirst().state().equals("DOWNLOADING") && System.nanoTime() < deadline) Thread.sleep(20);
            f.repository.pause("1", task.id()); var paused = f.await(task.id(), "PAUSED");
            assertEquals("stable-unique", paused.items().getFirst().file().uniqueId()); verify(f.reader, never()).release("1", 1, 7, "stable-unique", true);
            complete.set(true); f.repository.resume("1", task.id()); assertEquals(1, f.repository.view(f.await(task.id(), "COMPLETED")).completed());
        }
    }
    @Test void aDuplicateNewTaskUsesHistoryAfterTheFileWasMovedWithoutRequestingTheFileAgain() throws Exception {
        try (var f = new Fixture()) {
            var first = f.task(); f.start(); var done = f.await(first.id(), "COMPLETED");
            Files.move(f.store.directory(done).resolve(done.items().getFirst().relativeFile()),directory.resolve("moved.bin"));
            var second = f.repository.create("1", UUID.randomUUID().toString(), "second", DownloadPersistenceTest.post("renamed"), f.root, List.of(DownloadPersistenceTest.file(37, "renamed.bin")),DownloadModels.HISTORY_SKIP);
            var duplicate = f.await(second.id(), "COMPLETED");
            assertEquals("SKIPPED_HISTORY",duplicate.items().getFirst().state());assertEquals(done.id(),duplicate.items().getFirst().history().taskId());
            assertEquals(0,f.repository.view(duplicate).completed());assertEquals(1,f.repository.view(duplicate).skipped());
            verify(f.reader, times(1)).resolve(any(), any(), anyLong()); verify(f.reader, times(1)).chunk(anyString(), anyLong(), anyInt(), anyString(), anyLong(), anyLong(), anyInt());
        }
    }
    @Test void aPreviouslyQueuedDuplicateIsSkippedBeforeAnyDirectoryOrNativeWork() throws Exception {
        try(var f=new Fixture()) {
            var first=f.task();var queued=f.task();f.start();f.await(first.id(),"COMPLETED");var done=f.await(queued.id(),"COMPLETED");
            assertEquals(1,f.repository.view(done).skipped());assertEquals(0,f.repository.view(done).completed());
            verify(f.reader,times(1)).resolve(any(),any(),anyLong());verify(f.reader,times(1)).chunk(anyString(),anyLong(),anyInt(),anyString(),anyLong(),anyLong(),anyInt());
        }
    }
    @Test void nativeFailuresDoNotPublishAFileOrExposeNativeExceptionText() throws Exception {
        try (var f = new Fixture()) {
            var task = f.task(); when(f.reader.resolve(any(), any(), anyLong())).thenThrow(new IllegalStateException("synthetic-private-native-detail"));
            f.start(); var failed = f.await(task.id(), "FAILED"); assertFalse(failed.error().contains("private")); assertEquals(0, f.repository.view(failed).completed());
            assertFalse(Files.exists(f.root.resolve(failed.relativeDirectory()).resolve(failed.items().getFirst().relativeFile())));
        }
    }
    @Test void theNewPolicyReadsTheFullDuplicateAndPublishesItsOwnFileAndLog()throws Exception{
        try(var f=new Fixture()){
            var first=f.task();f.start();f.await(first.id(),"COMPLETED");
            var next=f.repository.create("1",UUID.randomUUID().toString(),"keep-copy",DownloadPersistenceTest.post("title"),f.root,List.of(DownloadPersistenceTest.file(37,"test.bin")));
            var view=f.repository.view(f.await(next.id(),"COMPLETED"));assertEquals(1,view.completed());assertEquals(1,view.duplicates());assertEquals(0,view.skipped());
            assertArrayEquals(new byte[37],Files.readAllBytes(Path.of(view.items().getFirst().outputPath())));assertTrue(Files.exists(Path.of(view.items().getFirst().manifestPath())));
            verify(f.reader,times(2)).resolve(any(),any(),anyLong());verify(f.reader,times(2)).chunk(anyString(),anyLong(),anyInt(),anyString(),anyLong(),anyLong(),anyInt());
        }
    }
    @Test void staleCompletedUploadCacheIsRevalidatedBeforeAnyOutputChunkIsRead() throws Exception {
        try(var f=new Fixture()) {var task=f.task();var requests=new java.util.concurrent.atomic.AtomicInteger();
            when(f.reader.status(anyString(),anyLong(),anyInt(),anyString(),anyLong())).thenAnswer(call->new DownloadFileReader.Status(requests.get()==1?0:37,requests.get()!=1,false,true));
            doAnswer(call->{requests.incrementAndGet();return null;}).when(f.reader).start(anyString(),anyLong(),anyInt(),anyString(),anyLong());
            when(f.reader.chunk(anyString(),anyLong(),anyInt(),anyString(),anyLong(),anyLong(),anyInt())).thenAnswer(call->{assertTrue(requests.get()>=2);return new byte[(Integer)call.getArgument(6)];});
            f.start();assertEquals(1,f.repository.view(f.await(task.id(),"COMPLETED")).completed());assertEquals(2,requests.get());
        }
    }
    @Test void revalidatingAnExistingCompleteCacheDoesNotGrantPermissionToDeleteIt() throws Exception {
        try(var f=new Fixture()){var task=f.task();f.start();f.await(task.id(),"COMPLETED");verify(f.reader).start("1",1,7,"stable-unique",37);verify(f.reader,never()).release(anyString(),anyLong(),anyInt(),anyString(),eq(true));}
    }
    private final class Fixture implements AutoCloseable {
        final AppPaths paths = new AppPaths(directory.resolve(UUID.randomUUID().toString())); final Database database = new Database(paths);
        final DownloadRepository repository = new DownloadRepository(database); final DownloadFileReader reader = mock(DownloadFileReader.class); final TelegramService telegram = mock(TelegramService.class);
        final DownloadFileStore store = new DownloadFileStore(new DownloadPaths(paths), repository); final Path root = directory.resolve("out").toAbsolutePath(); DownloadEngine engine;
        Fixture() {
            when(telegram.accountId()).thenReturn("1"); when(telegram.sessionGeneration()).thenReturn(1L);
            when(reader.resolve(any(), any(), anyLong())).thenAnswer(call -> new DownloadFileReader.Resolved(call.getArgument(1), 7));
            when(reader.status(anyString(), anyLong(), anyInt(), anyString(), anyLong())).thenReturn(new DownloadFileReader.Status(37, true, false, true));
            when(reader.chunk(anyString(), anyLong(), anyInt(), anyString(), anyLong(), anyLong(), anyInt())).thenAnswer(call -> new byte[(Integer) call.getArgument(6)]);
            when(reader.release(anyString(), anyLong(), anyInt(), anyString(), anyBoolean())).thenReturn(true);
        }
        Task task() { return repository.create("1", UUID.randomUUID().toString(), UUID.randomUUID().toString(), DownloadPersistenceTest.post("title"), root, List.of(DownloadPersistenceTest.file(37, "test.bin")),DownloadModels.HISTORY_SKIP); }
        void start() { engine = new DownloadEngine(repository, reader, store, telegram, paths); }
        Task await(String id, String state) throws Exception {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(7); Task task;
            do { task = repository.task("1", id); if (task.state().equals(state)) return task; Thread.sleep(20); } while (System.nanoTime() < deadline);
            fail("Expected " + state + " but was " + task.state() + ": " + task.error()); return task;
        }
        @Override public void close() { if (engine != null) engine.close(); database.close(); }
    }
}
