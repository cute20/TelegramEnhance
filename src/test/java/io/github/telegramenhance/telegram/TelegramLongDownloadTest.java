package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import it.tdlight.TelegramClient;
import it.tdlight.jni.TdApi;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TelegramLongDownloadTest {
    @Test void aDownloadUsesTheExistingClientWithoutTheNormalFifteenSecondReadTimeout() throws Exception {
        var f=new Fixture();var result=f.service.downloadInSession(1,new TdApi.DownloadFile(7,1,0,0,true));
        assertFalse(result.isDone());assertFalse(f.pending.isDone());
        var file=new TdApi.File();f.pending.complete(file);assertSame(file,result.join());
        verify(f.client,times(1)).sendAsync(any());verifyNoMoreInteractions(f.client);
    }
    @Test void lateSuccessAndLateErrorAreBothFencedByTheOriginalAccountClientAndGeneration() throws Exception {
        for(String mode:List.of("account","session","client","offline"))for(boolean error:List.of(false,true)){
            var f=new Fixture();var result=f.service.downloadInSession(1,new TdApi.DownloadFile(7,1,0,0,true));
            if(mode.equals("account"))set(f.service,"userId","2");
            if(mode.equals("session"))set(f.service,"clientGeneration",2L);
            if(mode.equals("client"))set(f.service,"client",mock(TelegramClient.class));
            if(mode.equals("offline"))set(f.service,"connecting",false);
            if(error)f.pending.completeExceptionally(new java.io.IOException("private"));else f.pending.complete(new TdApi.File());
            var failure=assertThrows(CompletionException.class,result::join,mode);
            assertInstanceOf(IllegalArgumentException.class,failure.getCause(),mode);
            assertFalse(failure.getCause().getMessage().contains("private"));
        }
    }
    @Test void staleSessionsAreRejectedBeforeAnyRequestIsSent() throws Exception {
        var f=new Fixture();assertThrows(IllegalArgumentException.class,()->f.service.downloadInSession(2,new TdApi.DownloadFile(7,1,0,0,true)));
        verifyNoInteractions(f.client);
    }
    @Test void finalNativeErrorsKeepTheirCodeForTheReadersSanitizedClassification() throws Exception {
        var f=new Fixture();var result=f.service.downloadInSession(1,new TdApi.DownloadFile(7,1,0,0,true));
        f.pending.completeExceptionally(new it.tdlight.client.TelegramError(new TdApi.Error(429,"private FLOOD_WAIT_600")));
        Throwable failure=assertThrows(CompletionException.class,result::join);
        while(failure.getCause()!=null)failure=failure.getCause();
        assertEquals(429,assertInstanceOf(it.tdlight.client.TelegramError.class,failure).getErrorCode());
    }
    private static final class NoReadTimeoutFuture<T> extends CompletableFuture<T>{
        @Override public CompletableFuture<T> orTimeout(long timeout,TimeUnit unit){throw new AssertionError("Long download must not inherit read timeout");}
        @Override public <U>CompletableFuture<U>newIncompleteFuture(){return new NoReadTimeoutFuture<>();}
    }
    private static final class Fixture{
        // No constructor or native initialization: only the existing-session method executes in this isolated test.
        final TelegramService service=mock(TelegramService.class,CALLS_REAL_METHODS);
        final TelegramClient client=mock(TelegramClient.class);
        final NoReadTimeoutFuture<TdApi.File> pending=new NoReadTimeoutFuture<>();
        Fixture()throws Exception{
            set(service,"sessionLock",new Object());set(service,"client",client);set(service,"clientGeneration",1L);
            set(service,"userId","1");set(service,"connecting",true);set(service,"authState","READY");
            when(client.sendAsync(any(TdApi.DownloadFile.class))).thenReturn(pending);
        }
    }
    private static void set(TelegramService service,String name,Object value)throws Exception{
        Field field=TelegramService.class.getDeclaredField(name);field.setAccessible(true);field.set(service,value);
    }
}
