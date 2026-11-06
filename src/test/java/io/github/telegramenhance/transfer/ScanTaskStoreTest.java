package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.TelegramService;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanTaskStoreTest {
    @TempDir Path root;
    private ScanTaskStore.Snapshot snapshot(String id,String state,long revision,long processed,boolean controllable,boolean canContinue,long expires){return new ScanTaskStore.Snapshot(id,controllable?"channel-scan":"rule-preparation","Synthetic","Synthetic",state,1000,1000,0,processed,0,"SCANNING",id,null,null,revision,expires,controllable,canContinue);}
    @Test void pauseAllowsAnAdmittedStepToSaveItsNewCheckpointAndResumeDoesNotAdvanceIt(){try(var db=new Database(new AppPaths(root))){
        var telegram=mock(TelegramService.class);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);var clock=new AtomicLong(1000);var store=new ScanTaskStore(db,telegram,clock::get);String source=UUID.randomUUID().toString();
        store.remember("1",1,snapshot(source,"WAITING_STEP",1,0,true,true,601000),true);var initial=store.list().getFirst();
        try(var admitted=store.begin("1",1,"channel-scan",source)){
            assertEquals("PAUSING",store.action(initial.id(),"pause").state());assertThrows(ScanTaskStore.Paused.class,()->store.begin("1",1,"channel-scan",source));
            store.remember("1",1,snapshot(source,"WAITING_STEP",2,7,true,true,601000),false);assertEquals("PAUSING",store.list().getFirst().state());
        }
        var paused=store.list().getFirst();assertEquals("PAUSED",paused.state());assertEquals(2,paused.revision());assertEquals(7,paused.processed());clock.set(2_000_000);
        assertEquals("PAUSED",store.list().getFirst().state(),"a paused checkpoint is pinned beyond its ordinary TTL");
        var resumed=store.action(initial.id(),"resume");assertEquals("WAITING_STEP",resumed.state());assertEquals(2,resumed.revision());assertEquals(7,resumed.processed());assertTrue(store.deadline("1",1,"channel-scan",source)>clock.get());
        store.check("1",1,"channel-scan",source);
    }}
    @Test void aPauseBeforeAQueuedStepStartsBlocksAdmissionWithoutChangingRevision(){try(var db=new Database(new AppPaths(root))){
        var telegram=mock(TelegramService.class);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);var store=new ScanTaskStore(db,telegram,()->1000);String source=UUID.randomUUID().toString();store.remember("1",1,snapshot(source,"WAITING_STEP",4,3,true,true,601000),true);
        var row=store.list().getFirst();store.action(row.id(),"pause");assertThrows(ScanTaskStore.Paused.class,()->store.begin("1",1,"channel-scan",source));assertEquals(4,store.list().getFirst().revision());
    }}
    @Test void restartAndOldSessionsRemainReadOnlyWhileCompletedHistorySurvivesExpiry(){try(var db=new Database(new AppPaths(root))){
        var telegram=mock(TelegramService.class);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);var store=new ScanTaskStore(db,telegram,()->1000);String running=UUID.randomUUID().toString(),done=UUID.randomUUID().toString();store.remember("1",1,snapshot(running,"WAITING_STEP",2,3,true,true,601000),true);store.remember("1",1,snapshot(done,"COMPLETED",4,8,true,false,601000),true);store.expire("1",1,"channel-scan",done,"TTL");assertEquals("COMPLETED",store.find("1",1,"channel-scan",done).state());
        var restarted=new ScanTaskStore(db,telegram,()->1000);var old=restarted.list().stream().filter(v->v.sourceId().equals(running)).findFirst().orElseThrow();assertEquals("EXPIRED",old.state());assertTrue(old.actions().isEmpty());assertThrows(IllegalArgumentException.class,()->restarted.action(old.id(),"resume"));
        restarted.remember("1",1,snapshot(running,"WAITING_STEP",2,3,true,true,601000),true);assertEquals(2,restarted.list().stream().filter(v->v.sourceId().equals(running)).count());
        when(telegram.sessionGeneration()).thenReturn(2L);assertTrue(restarted.list().stream().filter(v->v.sourceId().equals(running)).allMatch(v->v.actions().isEmpty()));when(telegram.accountId()).thenReturn("2");assertTrue(restarted.list().isEmpty());
    }}
    @Test void singleOperationPreparationHasHistoricalResultsButNeverPretendsToPause(){try(var db=new Database(new AppPaths(root))){
        var telegram=mock(TelegramService.class);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);var store=new ScanTaskStore(db,telegram,()->1000);String source=UUID.randomUUID().toString();store.remember("1",1,snapshot(source,"RUNNING",2,0,false,true,0),true);var row=store.list().getFirst();assertTrue(row.actions().isEmpty());assertThrows(IllegalArgumentException.class,()->store.action(row.id(),"pause"));
        store.remember("1",1,snapshot(source,"COMPLETED",2,0,false,false,0),false);var restarted=new ScanTaskStore(db,telegram,()->1000);assertEquals("COMPLETED",restarted.list().getFirst().state());assertTrue(restarted.list().getFirst().actions().isEmpty());
    }}
    @Test void boundedRetentionKeepsCurrentPauseGatesAndRejectsTooManyLiveCheckpoints(){try(var db=new Database(new AppPaths(root))){
        var telegram=mock(TelegramService.class);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);var clock=new AtomicLong(1000);var store=new ScanTaskStore(db,telegram,clock::get);String paused=UUID.randomUUID().toString();store.remember("1",1,snapshot(paused,"WAITING_STEP",1,0,true,true,601000),true);String pauseId=store.list().getFirst().id();store.action(pauseId,"pause");
        for(int n=0;n<130;n++){clock.incrementAndGet();store.remember("1",1,snapshot(UUID.randomUUID().toString(),"COMPLETED",1,n,true,false,601000),true);}assertEquals(100,store.list().size());assertEquals("PAUSED",store.find("1",1,"channel-scan",paused).state());assertThrows(ScanTaskStore.Paused.class,()->store.check("1",1,"channel-scan",paused));
        for(int n=0;n<79;n++){clock.incrementAndGet();store.remember("1",1,snapshot(UUID.randomUUID().toString(),"WAITING_STEP",1,0,true,true,601000),true);}assertThrows(IllegalArgumentException.class,()->store.remember("1",1,snapshot(UUID.randomUUID().toString(),"WAITING_STEP",1,0,true,true,601000),true));assertEquals(100,store.list().size());assertEquals("WAITING_STEP",store.action(pauseId,"resume").state());
    }}
    @Test void explicitDetailLookupShowsTheOriginalCheckpointAndRevisionWithoutClaimingHistory(){try(var db=new Database(new AppPaths(root))){
        var telegram=mock(TelegramService.class);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);var first=new ScanTaskStore(db,telegram,()->1000);String source=UUID.randomUUID().toString();first.remember("1",1,snapshot(source,"WAITING_STEP",23,7,true,true,601000),true);String id=first.list().getFirst().id();
        var restarted=new ScanTaskStore(db,telegram,()->1000);var detail=restarted.detail(id);assertEquals(source,detail.sourceId());assertEquals(23,detail.revision());assertEquals("EXPIRED",detail.state());assertFalse(detail.current());assertTrue(detail.actions().isEmpty());assertEquals(1,restarted.list().size());assertThrows(IllegalArgumentException.class,()->restarted.action(id,"resume"));
        when(telegram.accountId()).thenReturn("2");assertThrows(IllegalArgumentException.class,()->restarted.detail(id));
    }}
    @Test void detailLookupMustRecheckTheSessionBeforeReturningASnapshot(){try(var db=new Database(new AppPaths(root))){
        var telegram=mock(TelegramService.class);when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);var store=new ScanTaskStore(db,telegram,()->1000);String source=UUID.randomUUID().toString();store.remember("1",1,snapshot(source,"WAITING_STEP",4,0,true,true,601000),true);String id=store.list().getFirst().id();when(telegram.sessionGeneration()).thenReturn(1L,2L);assertThrows(IllegalArgumentException.class,()->store.detail(id));
    }}
}
