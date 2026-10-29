package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.telegram.DownloadFileReader;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadBatchModels.BatchStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DownloadBatchStatusServiceTest {
    static final String ID=UUID.randomUUID().toString();
    static final class Fixture {
        final DownloadChannelService scans=mock(DownloadChannelService.class);
        final ResourceService work=mock(ResourceService.class);
        final ResourceReader reader=mock(ResourceReader.class);
        final DownloadFileReader files=mock(DownloadFileReader.class);
        final DownloadRepository repository=mock(DownloadRepository.class);
        final TelegramService telegram=mock(TelegramService.class);
        final DownloadEngine engine=mock(DownloadEngine.class);
        final AtomicLong generation=new AtomicLong(1);
        final DownloadBatchService service;
        Fixture(){
            when(telegram.accountId()).thenReturn("1");
            when(telegram.sessionGeneration()).thenAnswer(i->generation.get());
            service=new DownloadBatchService(scans,work,reader,files,repository,null,telegram,engine);
        }
        void noNativeOrFileReads(){verifyNoInteractions(scans,work,reader,files);}
    }
    @Test void summariesUseCurrentAccountAndExposeWorkerWarningWithoutMetadataOrNativeReads(){
        var f=new Fixture();var row=mock(BatchStatus.class);
        when(f.repository.batchStatuses("1",10,20)).thenReturn(List.of(row));
        when(f.engine.warning()).thenReturn("worker warning");
        var listing=f.service.list(10,20);
        assertEquals(List.of(row),listing.items());assertEquals("worker warning",listing.workerWarning());
        assertThrows(UnsupportedOperationException.class,()->listing.items().clear());
        verify(f.repository).batchStatuses("1",10,20);f.noNativeOrFileReads();
    }
    @Test void readPauseAndResumeUseSeparateAccountScopedRepositoryOperations(){
        var f=new Fixture();var row=mock(BatchStatus.class);
        when(f.repository.batchStatus("1",ID)).thenReturn(row);
        when(f.repository.pauseBatch(eq("1"),eq(ID),any(Runnable.class))).thenAnswer(i->{((Runnable)i.getArgument(2)).run();return row;});
        when(f.repository.resumeBatch(eq("1"),eq(ID),any(Runnable.class))).thenAnswer(i->{((Runnable)i.getArgument(2)).run();return row;});
        assertSame(row,f.service.status(ID));assertSame(row,f.service.pause(ID));assertSame(row,f.service.resume(ID));
        verify(f.repository).batchStatus("1",ID);verify(f.repository).pauseBatch(eq("1"),eq(ID),any(Runnable.class));verify(f.repository).resumeBatch(eq("1"),eq(ID),any(Runnable.class));
        verifyNoMoreInteractions(f.repository);f.noNativeOrFileReads();
    }
    @Test void paginationAndMalformedIdsCannotReachTheRepository(){
        var f=new Fixture();
        for(int limit:List.of(0,-1,21,Integer.MAX_VALUE))assertThrows(IllegalArgumentException.class,()->f.service.list(limit,0));
        assertThrows(IllegalArgumentException.class,()->f.service.list(10,-1));
        for(String id:new String[]{null,"not-a-batch","../preview","00000000-0000-0000-0000-000000000000/next"}){
            assertThrows(IllegalArgumentException.class,()->f.service.status(id));
            assertThrows(IllegalArgumentException.class,()->f.service.pause(id));
            assertThrows(IllegalArgumentException.class,()->f.service.resume(id));
        }
        verifyNoInteractions(f.repository);f.noNativeOrFileReads();
    }
    @Test void authorizationFailurePreventsEveryRepositoryOperation(){
        var f=new Fixture();when(f.telegram.accountId()).thenThrow(new IllegalArgumentException("not authorized"));
        assertThrows(IllegalArgumentException.class,()->f.service.list(10,0));
        assertThrows(IllegalArgumentException.class,()->f.service.status(ID));
        assertThrows(IllegalArgumentException.class,()->f.service.pause(ID));
        assertThrows(IllegalArgumentException.class,()->f.service.resume(ID));
        verifyNoInteractions(f.repository);f.noNativeOrFileReads();
    }
    @Test void accountOrSessionChangeDiscardsStaleSummaryAndControlResponses(){
        var f=new Fixture();
        when(f.repository.batchStatuses("1",10,0)).thenAnswer(i->{f.generation.incrementAndGet();return List.of();});
        assertThrows(IllegalArgumentException.class,()->f.service.list(10,0));
        when(f.repository.batchStatus("1",ID)).thenAnswer(i->{f.generation.incrementAndGet();return mock(BatchStatus.class);});
        assertThrows(IllegalArgumentException.class,()->f.service.status(ID));
        when(f.repository.pauseBatch(eq("1"),eq(ID),any(Runnable.class))).thenAnswer(i->{when(f.telegram.accountId()).thenReturn("2");return mock(BatchStatus.class);});
        assertThrows(IllegalArgumentException.class,()->f.service.pause(ID));
        when(f.repository.resumeBatch(eq("2"),eq(ID),any(Runnable.class))).thenAnswer(i->{f.generation.incrementAndGet();return mock(BatchStatus.class);});
        assertThrows(IllegalArgumentException.class,()->f.service.resume(ID));
        f.noNativeOrFileReads();
    }
}
