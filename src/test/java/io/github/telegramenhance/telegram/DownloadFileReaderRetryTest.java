package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import it.tdlight.client.TelegramError;
import it.tdlight.jni.TdApi;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class DownloadFileReaderRetryTest {
    @Test void timeoutAndTransportErrorsAreRetryableWithoutExposingNativeDetails(){
        for(var reason: java.util.List.of(new TimeoutException("private"),new java.net.ConnectException("private"),new TelegramError(new TdApi.Error(503,"private")),new TelegramError(new TdApi.Error(408,"private")))){
            var reader=reader(reason);
            var error=assertThrows(DownloadFileReader.TemporarilyUnavailable.class,()->reader.status("1",1,7,"u",11));
            assertFalse(error.getMessage().contains("private"));assertEquals(0,error.minimumDelayMillis());
        }
    }
    @Test void rateLimitsHonorTheServerWaitInsteadOfImmediatelyRetrying(){
        for(String message:java.util.List.of("Too Many Requests: retry after 600","FLOOD_WAIT_600")){
            var reader=reader(new TelegramError(new TdApi.Error(429,message)));
            var error=assertThrows(DownloadFileReader.TemporarilyUnavailable.class,()->reader.status("1",1,7,"u",11));
            assertEquals(600_000,error.minimumDelayMillis());assertFalse(error.getMessage().contains(message));
        }
    }
    @Test void permissionMissingSourceAndExpiredReferencesDoNotAutomaticallyRetry(){
        for(int code:java.util.List.of(400,401,403,404)){
            var reader=reader(new TelegramError(new TdApi.Error(code,code==400?"FILE_REFERENCE_EXPIRED":"private")));
            var error=assertThrows(IllegalArgumentException.class,()->reader.status("1",1,7,"u",11));
            assertFalse(error instanceof DownloadFileReader.TemporarilyUnavailable);assertFalse(error.getMessage().contains("private"));
        }
    }
    @Test void accountAndSessionChangesDuringATimeoutKeepTheirStopSemantics(){
        for(boolean changeAccount:java.util.List.of(false,true)){
            String[] account={"1"};long[] session={1};
            var reader=new DownloadFileReader(new DownloadFileReader.Gateway(){
                public <R extends TdApi.Object> CompletableFuture<R> read(long generation,TdApi.Function<R> request){
                    if(changeAccount)account[0]="2";else session[0]=2;
                    return CompletableFuture.failedFuture(new TimeoutException());
                }
            },()->account[0],()->session[0]);
            var error=assertThrows(IllegalArgumentException.class,()->reader.status("1",1,7,"u",11));
            assertFalse(error instanceof DownloadFileReader.TemporarilyUnavailable);
        }
    }
    private static DownloadFileReader reader(Throwable reason){
        return new DownloadFileReader(new DownloadFileReader.Gateway(){
            public <R extends TdApi.Object> CompletableFuture<R> read(long generation,TdApi.Function<R> request){return CompletableFuture.failedFuture(reason);}
        },()->"1",()->1);
    }
}
