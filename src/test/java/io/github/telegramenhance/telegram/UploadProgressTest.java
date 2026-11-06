package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.transfer.UploadTestFixture;
import it.tdlight.jni.TdApi;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadProgressTest {
    @TempDir Path root;
    private static TdApi.File file(int id,long size,long uploaded,boolean active){var file=new TdApi.File();file.id=id;file.size=size;file.remote=new TdApi.RemoteFile();file.remote.uploadedSize=uploaded;file.remote.isUploadingActive=active;return file;}
    @Test void nativeBytesAreObservedWithoutConfirmingDeliveryOrMovingSources()throws Exception{try(var f=new UploadTestFixture(root,1)){
        f.repository.start("1",f.task.id());var task=f.repository.next("1");var item=task.groups().getFirst().items().getFirst();var clock=new AtomicLong(1000);var progress=new UploadProgress(clock::get);
        progress.bind("1",1,item,file(11,100,0,true));clock.set(2000);progress.update("1",1,file(11,100,50,true));
        var snapshot=progress.snapshot(task,1);assertEquals(50,snapshot.uploadedBytes());assertEquals(100,snapshot.totalBytes());assertEquals(50,snapshot.speedBytesPerSecond());assertEquals(1,snapshot.etaSeconds());assertEquals(0,f.repository.view(f.fresh()).confirmed());assertEquals(0,f.repository.view(f.fresh()).archived());
        clock.set(3000);progress.update("1",1,file(11,100,100,false));snapshot=progress.snapshot(task,1);assertEquals(100,snapshot.uploadedBytes());assertNull(snapshot.speedBytesPerSecond());assertNull(snapshot.etaSeconds());assertTrue(java.nio.file.Files.exists(f.source.resolve("file1.bin")));
    }}
    @Test void aChangedSessionDiscardsObservationsAndOldCallbacksCannotAffectTheNewSnapshot()throws Exception{try(var f=new UploadTestFixture(root,1)){
        var task=f.task;var item=task.groups().getFirst().items().getFirst();var progress=new UploadProgress(()->1000L);
        progress.bind("1",1,item,file(11,100,80,true));assertEquals(80,progress.snapshot(task,1).uploadedBytes());assertEquals(0,progress.snapshot(task,2).uploadedBytes());
        progress.bind("1",2,item,file(12,100,20,true));progress.update("1",1,file(12,100,99,true));progress.bind("1",1,item,file(11,100,99,true));assertEquals(20,progress.snapshot(task,2).uploadedBytes());
        assertThrows(IllegalArgumentException.class,()->progress.snapshot(task,1));progress.update("1",2,file(12,100,30,true));assertEquals(30,progress.snapshot(task,2).uploadedBytes(),"an old snapshot cannot clear current bindings");
    }}
    @Test void unobservedMembersPreventAnEtaAndStaleRatesDisappear()throws Exception{try(var f=new UploadTestFixture(root,2)){
        f.repository.start("1",f.task.id());var task=f.repository.next("1");var clock=new AtomicLong(1000);var progress=new UploadProgress(clock::get);var item=task.groups().getFirst().items().getFirst();
        progress.bind("1",1,item,file(11,100,0,true));clock.set(2000);progress.update("1",1,file(11,100,10,true));var snapshot=progress.snapshot(task,1);assertEquals(1,snapshot.observedItems());assertEquals(2,snapshot.totalItems());assertNotNull(snapshot.speedBytesPerSecond());assertNull(snapshot.etaSeconds());
        clock.set(7000);progress.update("1",1,file(11,100,10,true));snapshot=progress.snapshot(task,1);assertNull(snapshot.speedBytesPerSecond());assertNull(snapshot.etaSeconds());
    }}
    @Test void regressingNativeBytesResetTheRateInsteadOfReportingANegativeSpeed()throws Exception{try(var f=new UploadTestFixture(root,1)){
        f.repository.start("1",f.task.id());var task=f.repository.next("1");var clock=new AtomicLong(1000);var progress=new UploadProgress(clock::get);var item=task.groups().getFirst().items().getFirst();progress.bind("1",1,item,file(11,100,70,true));
        clock.set(2000);progress.update("1",1,file(11,100,10,true));assertEquals(10,progress.snapshot(task,1).uploadedBytes());assertNull(progress.snapshot(task,1).speedBytesPerSecond());
    }}
    @Test void anUnknownNativeSizeKeepsTheFrozenDenominatorAndDoesNotAuthorizeAnEta()throws Exception{try(var f=new UploadTestFixture(root,1)){
        var progress=new UploadProgress(()->1000L);var item=f.task.groups().getFirst().items().getFirst();progress.bind("1",1,item,file(11,0,0,true));var snapshot=progress.snapshot(f.task,1);
        assertEquals(item.file().size(),snapshot.totalBytes());assertEquals(0,snapshot.observedItems());assertEquals(0,snapshot.uploadedBytes());assertNull(snapshot.speedBytesPerSecond());assertNull(snapshot.etaSeconds());
    }}
}
