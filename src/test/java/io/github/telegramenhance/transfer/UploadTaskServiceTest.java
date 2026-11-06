package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.telegram.TelegramService;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadTaskServiceTest {
    @TempDir Path root;
    @Test void anExpiredPreviewIsADefiniteRejectionBeforeAnyTaskCanBeCommitted()throws Exception{
        try(var f=new UploadTestFixture(root,1)){
            var previews=mock(UploadPreviewService.class);String preview=UUID.randomUUID().toString();
            when(previews.freeze(preview)).thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException("preview expired")));
            var telegram=mock(TelegramService.class);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);
            var repository=spy(f.repository);var service=new UploadTaskService(previews,repository,telegram,mock(UploadEngine.class),mock(io.github.telegramenhance.telegram.UploadTransport.class),mock(io.github.telegramenhance.library.ResourceService.class));
            var failure=assertThrows(ExecutionException.class,()->service.create(new UploadTaskService.Create(UUID.randomUUID().toString(),preview)).get());
            assertInstanceOf(CreationRejectedException.class,failure.getCause());verify(repository,never()).create(anyString(),anyString(),anyString(),any());
        }
    }
    @Test void aPostCommitSessionChangeRemainsUnknownAndCannotCarryTheDefiniteRejectionType()throws Exception{
        try(var f=new UploadTestFixture(root,1)){
            f.repository.cancel("1",f.task.id());var previews=mock(UploadPreviewService.class);String preview=UUID.randomUUID().toString();when(previews.freeze(preview)).thenReturn(CompletableFuture.completedFuture(f.prepared));
            var telegram=mock(TelegramService.class);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);var repository=spy(f.repository);
            doAnswer(call->{var result=call.callRealMethod();when(telegram.sessionGeneration()).thenReturn(2L);return result;}).when(repository).create(anyString(),anyString(),anyString(),any());
            var service=new UploadTaskService(previews,repository,telegram,mock(UploadEngine.class),mock(io.github.telegramenhance.telegram.UploadTransport.class),mock(io.github.telegramenhance.library.ResourceService.class));
            String operation=UUID.randomUUID().toString();var failure=assertThrows(ExecutionException.class,()->service.create(new UploadTaskService.Create(operation,preview)).get());
            assertInstanceOf(IllegalArgumentException.class,failure.getCause());assertFalse(failure.getCause() instanceof CreationRejectedException);assertNotNull(repository.byOperation("1",operation));
        }
    }
    @Test void cancellingCommitsBeforeRequestingCollectionOnTheSendingLane()throws Exception{
        try(var f=new UploadTestFixture(root,1)){
            var telegram=mock(TelegramService.class);when(telegram.accountId()).thenReturn("1");var engine=mock(UploadEngine.class);
            doAnswer(call->{assertEquals("CANCELLED",f.fresh().state());return null;}).when(engine).cleanupCancelled(any());
            var service=new UploadTaskService(mock(UploadPreviewService.class),f.repository,telegram,engine,mock(io.github.telegramenhance.telegram.UploadTransport.class),mock(io.github.telegramenhance.library.ResourceService.class));
            assertEquals("CANCELLED",service.action(f.task.id(),"cancel").state());verify(engine).cleanupCancelled(any());
        }
    }
    @Test void createWaitsForExplicitStartAndRemainsIdempotentAfterThePreviewExpires()throws Exception{
        try(var f=new UploadTestFixture(root,1)){f.repository.cancel("1",f.task.id());var previews=mock(UploadPreviewService.class);var telegram=mock(TelegramService.class);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);String preview=UUID.randomUUID().toString();when(previews.freeze(preview)).thenReturn(CompletableFuture.completedFuture(f.prepared));var engine=mock(UploadEngine.class);var service=new UploadTaskService(previews,f.repository,telegram,engine,mock(io.github.telegramenhance.telegram.UploadTransport.class),mock(io.github.telegramenhance.library.ResourceService.class));var input=new UploadTaskService.Create(UUID.randomUUID().toString(),preview);var first=service.create(input).get();assertEquals("READY",first.state());assertEquals(0,first.confirmed());when(previews.freeze(preview)).thenThrow(new IllegalArgumentException("expired"));var again=service.create(input).get();assertEquals(first.id(),again.id());verify(previews,times(1)).freeze(preview);verifyNoInteractions(engine);assertThrows(IllegalArgumentException.class,()->service.create(new UploadTaskService.Create(input.operationId(),UUID.randomUUID().toString())));}
    }
    @Test void anAccountChangeWhileFreezingCannotCreateAReadyTaskForTheWrongAccount()throws Exception{
        try(var f=new UploadTestFixture(root,1)){f.repository.cancel("1",f.task.id());var previews=mock(UploadPreviewService.class);var telegram=mock(TelegramService.class);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);var pending=new CompletableFuture<UploadPreviewService.Prepared>();String id=UUID.randomUUID().toString();when(previews.freeze(id)).thenReturn(pending);var service=new UploadTaskService(previews,f.repository,telegram,mock(UploadEngine.class),mock(io.github.telegramenhance.telegram.UploadTransport.class),mock(io.github.telegramenhance.library.ResourceService.class));var result=service.create(new UploadTaskService.Create(UUID.randomUUID().toString(),id));when(telegram.accountId()).thenReturn("2");pending.complete(f.prepared);assertThrows(ExecutionException.class,result::get);assertEquals(1,f.repository.list("1",20,0).size());assertTrue(f.repository.list("2",20,0).isEmpty());}
    }
    @Test void explicitRecoveryUsesTheReadQueueAndQueuesOnlyArchiveWork()throws Exception{
        try(var f=new UploadTestFixture(root,1)){
            f.prepareAndIntent();new UploadRepository(f.database);
            var telegram=mock(TelegramService.class);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(2L);
            var reads=mock(io.github.telegramenhance.library.ResourceService.class);when(reads.readWork(any())).thenAnswer(i->CompletableFuture.completedFuture(((java.util.function.Supplier<?>)i.getArgument(0)).get()));
            var transport=mock(io.github.telegramenhance.telegram.UploadTransport.class);var engine=mock(UploadEngine.class);
            var service=new UploadTaskService(mock(UploadPreviewService.class),f.repository,telegram,engine,transport,reads);
            var result=service.recoverPending(f.task.id()).get();assertEquals("QUEUED",result.state());assertTrue(result.stopRequested());assertEquals(0,result.confirmed());
            verify(transport).recoverPending(any(),eq(2L));verifyNoMoreInteractions(transport);verifyNoInteractions(engine);
            assertThrows(IllegalArgumentException.class,()->service.recoverPending(f.task.id()));
        }
    }
    @Test void aSessionChangeBeforeQueuedRecoveryLeavesTheTaskUntouched()throws Exception{
        try(var f=new UploadTestFixture(root,1)){
            f.prepareAndIntent();new UploadRepository(f.database);
            var telegram=mock(TelegramService.class);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(2L);
            var reads=mock(io.github.telegramenhance.library.ResourceService.class);var queued=new java.util.concurrent.atomic.AtomicReference<java.util.function.Supplier<?>>();
            when(reads.readWork(any())).thenAnswer(i->{queued.set(i.getArgument(0));return new CompletableFuture<>();});
            var transport=mock(io.github.telegramenhance.telegram.UploadTransport.class);var service=new UploadTaskService(mock(UploadPreviewService.class),f.repository,telegram,mock(UploadEngine.class),transport,reads);
            service.recoverPending(f.task.id());when(telegram.sessionGeneration()).thenReturn(3L);
            assertThrows(IllegalArgumentException.class,()->queued.get().get());verifyNoInteractions(transport);assertEquals("NEEDS_REVIEW",f.fresh().state());
        }
    }
}
