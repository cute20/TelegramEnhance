package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class BotDownloadServiceTest {
    @TempDir Path directory;
    private static final BotReader.Target TARGET=new BotReader.Target("9","9","合成机器人");
    private static BotReader.Message message(String id,String text,String button){
        var ref=new MessageRef("9",id,null);
        return new BotReader.Message(ref,text,List.of(new Media("1:9:"+id+":VIDEO",ref,"BOT","VIDEO","original.mp4",37,"")),
                button==null?List.of():List.of(new BotReader.Button(0,0,button,"CALLBACK","private-callback-payload".getBytes(StandardCharsets.UTF_8))));
    }
    private static BotReader.Snapshot page(String id,String text,String button){return new BotReader.Snapshot(TARGET,List.of(message(id,text,button)),false,id,"50");}
    @Test void previewProjectsOnlyOpaqueButtonsAndNeverWritesTasksOrTransfers()throws Exception{
        try(var f=new Fixture()){
            var view=f.open();assertEquals("MORE",view.paginationStatus());assertNotNull(view.recommendedButtonId());assertEquals("100",view.controlMessageId());
            assertTrue(f.repository.list("1",20,0).isEmpty());verifyNoInteractions(f.files);
            String json=JsonMapper.builder().build().writeValueAsString(view);
            assertFalse(json.contains("private-callback-payload"));assertFalse(json.contains(Base64.getEncoder().encodeToString("private-callback-payload".getBytes(StandardCharsets.UTF_8))));
            assertFalse(Files.exists(f.output));
        }
    }
    @Test void callbackIntentIsIdempotentAndUnchangedRepliesRequireReadOnlyReview()throws Exception{
        try(var f=new Fixture()){
            var view=f.open();var input=new BotDownloadService.Press(UUID.randomUUID().toString(),view.revision(),view.recommendedButtonId());
            doThrow(new IllegalArgumentException("simulated uncertain callback")).when(f.reader).press(any(),anyString(),any(),anyLong());
            var pending=f.service.press(view.id(),input).get();assertEquals("NEEDS_REVIEW",pending.state());
            assertEquals(pending,f.service.press(view.id(),input).get());verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());
            f.advance(5);var checked=f.service.refresh(view.id()).get();assertEquals("NEEDS_REVIEW",checked.state());assertFalse(checked.waitInfo().progressObserved());
            assertThrows(ExecutionException.class,()->f.service.press(view.id(),new BotDownloadService.Press(UUID.randomUUID().toString(),checked.revision(),view.recommendedButtonId())).get());
            verify(f.reader,times(3)).history(TARGET,null,1);
            verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());assertTrue(f.repository.list("1",20,0).isEmpty());
        }
    }
    @Test void aNewReplyCanBeReadAfterAnUncertainCallbackWithoutClickingItAgain()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();doThrow(new IllegalArgumentException("unknown")).when(f.reader).press(any(),anyString(),any(),anyLong());
            f.service.press(first.id(),new BotDownloadService.Press(UUID.randomUUID().toString(),first.revision(),first.recommendedButtonId())).get();
            when(f.reader.history(TARGET,null,1)).thenReturn(page("101","已获取第2组文件（共3组）","下一组 (3/3)"));
            f.advance(5);var arriving=f.service.refresh(first.id()).get();assertEquals("NEEDS_REVIEW",arriving.state());assertTrue(arriving.waitInfo().progressObserved());assertEquals(0,arriving.waitInfo().quietMs());
            f.advance(5);var checked=f.service.refresh(first.id()).get();assertEquals("READY",checked.state());assertNull(checked.waitInfo());assertEquals(2,checked.media().size());assertEquals(2,checked.currentPage());
            verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void successfulButDelayedBotRepliesWaitAndCannotDoubleClick()throws Exception{
        try(var f=new Fixture()){
            var view=f.open();var input=new BotDownloadService.Press(UUID.randomUUID().toString(),view.revision(),view.recommendedButtonId());
            var waiting=f.service.press(view.id(),input).get();assertEquals("WAITING",waiting.state());
            f.advance(5);var checked=f.service.refresh(view.id()).get();assertEquals("WAITING",checked.state());assertEquals(waiting.revision(),checked.revision());assertFalse(checked.waitInfo().progressObserved());
            verify(f.reader,times(3)).history(TARGET,null,1);
            assertThrows(ExecutionException.class,()->f.service.press(view.id(),new BotDownloadService.Press(input.operationId(),input.revision(),UUID.randomUUID().toString())).get());
            verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void unrelatedAdvertisementsDoNotUnlockAnUncertainPaginationIntent()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();doThrow(new IllegalArgumentException("unknown")).when(f.reader).press(any(),anyString(),any(),anyLong());
            f.service.press(first.id(),new BotDownloadService.Press(UUID.randomUUID().toString(),first.revision(),first.recommendedButtonId())).get();
            var advert=new BotReader.Message(new MessageRef("9","105",null),"会员推广广告",List.of(),List.of(new BotReader.Button(0,0,"购买会员","CALLBACK",new byte[]{1})));
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(advert,message("100","已获取第1组文件（共3组）","下一组 (2/3)")),false,"100"));
            f.advance(5);var checked=f.service.refresh(first.id()).get();assertEquals("NEEDS_REVIEW",checked.state());assertEquals("100",checked.controlMessageId());assertFalse(checked.waitInfo().progressObserved());
            assertThrows(ExecutionException.class,()->f.service.press(first.id(),new BotDownloadService.Press(UUID.randomUUID().toString(),checked.revision(),first.recommendedButtonId())).get());
            verify(f.reader,times(3)).history(TARGET,null,1);
            verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void anExplicitNewCompletionMessageCanFinishWaitingWithoutInventingNewMedia()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();f.service.press(first.id(),new BotDownloadService.Press(UUID.randomUUID().toString(),first.revision(),first.recommendedButtonId())).get();
            var end=new BotReader.Message(new MessageRef("9","105",null),"文件全部获取成功！共收到3组文件",List.of(),List.of());
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(end),false,"105"));
            f.advance(5);var settling=f.service.refresh(first.id()).get();assertEquals("WAITING",settling.state());assertEquals("UNKNOWN",settling.paginationStatus());
            f.advance(5);var checked=f.service.refresh(first.id()).get();assertEquals("READY",checked.state());assertEquals("END",checked.paginationStatus());assertNull(checked.recommendedButtonId());assertEquals(1,checked.media().size());
            assertTrue(checked.historyEnd(),"the current-resource preview never offers older chat history");assertNull(checked.nextHistoryCursor());
        }
    }
    @Test void batchDeliveryAdvancesToAFolderEndWithoutClickingJumpOrFolderUtilities()throws Exception{
        try(var f=new Fixture()){
            var batch=message("100","✅当前批次发送完成\n已发送：26/38条消息\n点击下方按钮查看下一组文件↓","➡点击查看下一组");
            var batchButtons=new ArrayList<>(batch.buttons());batchButtons.add(new BotReader.Button(1,0,"📍1-26/38 跳转","CALLBACK",new byte[]{2}));
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(new BotReader.Message(batch.ref(),batch.text(),batch.media(),batchButtons)),false,"100","50"));
            var first=f.open();assertEquals("MORE",first.paginationStatus());assertNull(first.currentPage());assertNull(first.totalPages());
            var waiting=f.service.press(first.id(),new BotDownloadService.Press(UUID.randomUUID().toString(),first.revision(),first.recommendedButtonId())).get();assertEquals("WAITING",waiting.state());
            var utilities=List.of(new BotReader.Button(0,0,"📍27-38/38 跳转","CALLBACK",new byte[]{1}),new BotReader.Button(1,0,"📁文件夹详情","CALLBACK",new byte[]{2}),new BotReader.Button(1,1,"☆收藏","CALLBACK",new byte[]{3}),new BotReader.Button(2,0,"🗂文件夹列表","CALLBACK",new byte[]{4}));
            var end=new BotReader.Message(new MessageRef("9","150",null),"✅文件夹内容发送完成\n共发送38条消息",List.of(),utilities);
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(end),false,"150"));
            f.advance(5);var settling=f.service.refresh(first.id()).get();assertEquals("WAITING",settling.state());
            f.advance(5);var checked=f.service.refresh(first.id()).get();assertEquals("READY",checked.state());assertEquals("END",checked.paginationStatus());assertNull(checked.recommendedButtonId());assertNull(checked.totalPages());assertEquals(1,checked.media().size());
            assertTrue(checked.messages().getFirst().buttons().stream().noneMatch(BotDownloadService.Button::supported));
            verify(f.reader,times(1)).press(eq(TARGET),eq("100"),any(),eq(1L));verifyNoInteractions(f.files);assertTrue(f.repository.list("1",20,0).isEmpty());
        }
    }
    @Test void advertisingEditsInsideTheControlMessageAreNotPaginationProgress()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();f.service.press(first.id(),new BotDownloadService.Press(UUID.randomUUID().toString(),first.revision(),first.recommendedButtonId())).get();
            when(f.reader.history(TARGET,null,1)).thenReturn(page("100","已获取第1组文件（共3组）\n广告文字更新","下一组 (2/3)"));
            f.advance(5);var checked=f.service.refresh(first.id()).get();assertEquals("WAITING",checked.state());assertFalse(checked.waitInfo().progressObserved());
            verify(f.reader,times(3)).history(TARGET,null,1);verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void anOlderControlCannotTreatAPreexistingNewerEndMessageAsTheCallbackResult()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();var end=new BotReader.Message(new MessageRef("9","150",null),"文件全部获取成功！",List.of(),List.of());
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(end),false,"150"));
            var waiting=f.service.press(first.id(),new BotDownloadService.Press(UUID.randomUUID().toString(),first.revision(),first.recommendedButtonId())).get();
            f.advance(5);var checked=f.service.refresh(first.id()).get();assertEquals("WAITING",waiting.state());assertEquals("WAITING",checked.state());assertFalse(checked.waitInfo().progressObserved());
            verify(f.reader,times(3)).history(TARGET,null,1);
        }
    }
    @Test void waitingStartsAfterTheNativeAnswerAndEarlyReadsUseOnlyTheCachedView()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();doAnswer(call->{f.advance(9);return null;}).when(f.reader).press(any(),anyString(),any(),anyLong());
            var waiting=f.service.press(first.id(),f.press(first)).get();assertEquals("WAITING",waiting.state());
            assertEquals(0,waiting.waitInfo().elapsedMs());assertEquals(5,waiting.waitInfo().waitSeconds());assertEquals(120,waiting.waitInfo().timeoutSeconds());
            assertEquals(120000,waiting.waitInfo().remainingMs());assertFalse(waiting.waitInfo().progressObserved());
            verify(f.reader,times(2)).history(TARGET,null,1);
            f.service.refresh(first.id()).get();f.advance(4);var cached=f.service.refresh(first.id()).get();assertEquals(4000,cached.waitInfo().elapsedMs());
            verify(f.reader,times(2)).history(TARGET,null,1);
            f.advance(1);var checked=f.service.refresh(first.id()).get();assertEquals("WAITING",checked.state());verify(f.reader,times(3)).history(TARGET,null,1);
            String json=JsonMapper.builder().build().writeValueAsString(checked);assertTrue(json.contains("\"wait\":"));assertFalse(json.contains("\"waitInfo\""));
            assertTrue(json.contains("\"waitSeconds\":5"));assertTrue(json.contains("\"progressObserved\":false"));
        }
    }
    @Test void uncertainAnswersAlsoReceiveTheEntireConfiguredInitialWait()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();doAnswer(call->{f.advance(9);throw new IllegalArgumentException("unknown");}).when(f.reader).press(any(),anyString(),any(),anyLong());
            var pending=f.service.press(first.id(),new BotDownloadService.Press(UUID.randomUUID().toString(),first.revision(),first.recommendedButtonId(),2,15)).get();
            assertEquals("NEEDS_REVIEW",pending.state());assertEquals(0,pending.waitInfo().elapsedMs());
            f.advance(1);assertEquals(1000,f.service.refresh(first.id()).get().waitInfo().elapsedMs());verify(f.reader,times(2)).history(TARGET,null,1);
            f.advance(1);assertEquals("NEEDS_REVIEW",f.service.refresh(first.id()).get().state());verify(f.reader,times(3)).history(TARGET,null,1);
        }
    }
    @Test void eachLaterBatchRestartsQuietTimeAndOneMediaNeverImmediatelyUnlocksTheNextCallback()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();f.service.press(first.id(),f.press(first)).get();
            when(f.reader.history(TARGET,null,1)).thenReturn(page("101","第2组正在发送",null));
            f.advance(5);var one=f.service.refresh(first.id()).get();assertEquals("WAITING",one.state());assertEquals(2,one.media().size());assertTrue(one.waitInfo().progressObserved());assertEquals(0,one.waitInfo().quietMs());
            f.advance(3);when(f.reader.history(TARGET,null,1)).thenReturn(page("102","第2组正在发送",null));
            var two=f.service.refresh(first.id()).get();assertEquals("WAITING",two.state());assertEquals(3,two.media().size());assertEquals(0,two.waitInfo().quietMs());
            f.advance(4);var quiet=f.service.refresh(first.id()).get();assertEquals("WAITING",quiet.state());assertEquals(4000,quiet.waitInfo().quietMs());
            f.advance(1);var stable=f.service.refresh(first.id()).get();assertEquals("READY",stable.state());assertNull(stable.waitInfo());
            verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());verify(f.reader,times(6)).history(TARGET,null,1);
        }
    }
    @Test void editsToAnExistingMediaTypeSizeOrFilenameAreObservableVersionsThatNeedAnotherQuietPeriod()throws Exception{
        for(String field:List.of("type","size","name"))try(var f=new Fixture()){
            var first=f.open();f.service.press(first.id(),f.press(first)).get();var original=message("100","已获取第1组文件（共3组）","下一组 (2/3)");var media=original.media().getFirst();
            var changed=new Media(media.key(),media.message(),"BOT",field.equals("type")?"DOCUMENT":media.type(),field.equals("name")?"new-name.bin":media.fileName(),field.equals("size")?38:media.size(),media.caption());
            var edited=new BotReader.Message(original.ref(),original.text(),List.of(changed),original.buttons());
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(edited),false,"100"));
            f.advance(5);var arriving=f.service.refresh(first.id()).get();assertEquals("WAITING",arriving.state(),field);assertEquals(1,arriving.media().size());assertTrue(arriving.waitInfo().progressObserved(),field);assertEquals(0,arriving.waitInfo().quietMs());
            f.advance(4);assertEquals("WAITING",f.service.refresh(first.id()).get().state(),field);
            f.advance(1);assertEquals("READY",f.service.refresh(first.id()).get().state(),field);
        }
    }
    @Test void adsDoNotRestartAnAlreadyObservedMediaQuietPeriod()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();f.service.press(first.id(),f.press(first)).get();var media=message("101","第二组媒体",null);
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(media),false,"101"));
            f.advance(5);assertTrue(f.service.refresh(first.id()).get().waitInfo().progressObserved());
            var advert=new BotReader.Message(new MessageRef("9","105",null),"会员推广广告",List.of(),List.of(new BotReader.Button(0,0,"购买会员","CALLBACK",new byte[]{1})));
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(advert,media),false,"101"));
            f.advance(3);var checked=f.service.refresh(first.id()).get();assertEquals("WAITING",checked.state());assertEquals(3000,checked.waitInfo().quietMs());
            f.advance(2);assertEquals("READY",f.service.refresh(first.id()).get().state());verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void advertisingRowsMovingNavigationDoNotCreateProgressOrRestartTheQuietPeriod()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();f.service.press(first.id(),f.press(first)).get();
            var original=message("100","已获取第1组文件（共3组）","下一组 (2/3)");var next=original.buttons().getFirst();
            var advert=new BotReader.Button(0,0,"购买会员","CALLBACK",new byte[]{1});
            var shifted=new BotReader.Message(original.ref(),original.text(),original.media(),List.of(advert,new BotReader.Button(1,0,next.text(),next.type(),next.data())));
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(shifted),false,"100"));
            f.advance(5);var onlyAds=f.service.refresh(first.id()).get();assertEquals("WAITING",onlyAds.state());assertFalse(onlyAds.waitInfo().progressObserved());
            f.advance(5);assertFalse(f.service.refresh(first.id()).get().waitInfo().progressObserved());
            var arriving=message("101","第二组媒体",null);
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(arriving,shifted),false,"100"));
            var progress=f.service.refresh(first.id()).get();assertTrue(progress.waitInfo().progressObserved());
            var movedAgain=new BotReader.Message(original.ref(),original.text(),original.media(),List.of(advert,new BotReader.Button(1,0,"会员广告","CALLBACK",new byte[]{2}),new BotReader.Button(2,0,next.text(),next.type(),next.data())));
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(arriving,movedAgain),false,"100"));
            f.advance(3);assertEquals(3000,f.service.refresh(first.id()).get().waitInfo().quietMs());
            f.advance(2);assertEquals("READY",f.service.refresh(first.id()).get().state());verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void ordinarySendingTextAndCaptionAdsDoNotCreatePaginationOrMediaProgress()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();f.service.press(first.id(),f.press(first)).get();var original=message("100","正在发送，请稍等",null);var media=original.media().getFirst();
            var advertCaption=new Media(media.key(),media.message(),media.source(),media.type(),media.fileName(),media.size(),"广告文字更新");
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(new BotReader.Message(original.ref(),original.text(),List.of(advertCaption),List.of())),false,"100"));
            f.advance(5);var firstCheck=f.service.refresh(first.id()).get();assertEquals("WAITING",firstCheck.state());assertFalse(firstCheck.waitInfo().progressObserved());
            f.advance(5);var secondCheck=f.service.refresh(first.id()).get();assertEquals("WAITING",secondCheck.state());assertFalse(secondCheck.waitInfo().progressObserved());
            verify(f.reader,times(4)).history(TARGET,null,1);
        }
    }
    @Test void callbackReplayDoesNotRestartTheObservationWindowAndChangedWaitOptionsConflict()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();var request=f.press(first);f.service.press(first.id(),request).get();f.advance(3);
            var same=f.service.press(first.id(),request).get();assertEquals("WAITING",same.state());assertEquals(3000,same.waitInfo().elapsedMs());
            assertThrows(ExecutionException.class,()->f.service.press(first.id(),new BotDownloadService.Press(request.operationId(),request.revision(),request.buttonId(),6,120)).get());
            verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());verify(f.reader,times(2)).history(TARGET,null,1);
        }
    }
    @Test void timeoutNeverInventsEndOrUnlocksEvenWhenAReplyLaterArrivesAndContinueWaitDoesNotClickAgain()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();f.service.press(first.id(),new BotDownloadService.Press(UUID.randomUUID().toString(),first.revision(),first.recommendedButtonId(),2,15)).get();
            f.advance(15);var timed=f.service.refresh(first.id()).get();assertEquals("WAITING",timed.state());assertTrue(timed.waitInfo().timedOut());assertEquals(0,timed.waitInfo().remainingMs());assertFalse(timed.waitInfo().progressObserved());
            assertThrows(ExecutionException.class,()->f.service.press(first.id(),new BotDownloadService.Press(UUID.randomUUID().toString(),timed.revision(),first.recommendedButtonId())).get());
            verifyNoInteractions(f.files);assertTrue(f.repository.list("1",20,0).isEmpty());
            var end=new BotReader.Message(new MessageRef("9","150",null),"文件全部获取成功！共收到3组文件",List.of(),List.of());
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(end),false,"150"));
            f.advance(1);var arrived=f.service.refresh(first.id()).get();assertEquals("WAITING",arrived.state());assertTrue(arrived.waitInfo().progressObserved());assertTrue(arrived.waitInfo().timedOut());assertEquals("UNKNOWN",arrived.paginationStatus());
            f.advance(10);var stillTimed=f.service.refresh(first.id()).get();assertEquals("WAITING",stillTimed.state());assertEquals("UNKNOWN",stillTimed.paginationStatus());
            int reads=(int)mockingDetails(f.reader).getInvocations().stream().filter(call->call.getMethod().getName().equals("history")).count();
            var rearmed=f.service.waitForReply(first.id(),new BotDownloadService.WaitRequest(stillTimed.revision(),null,null)).get();
            assertEquals("WAITING",rearmed.state());assertFalse(rearmed.waitInfo().timedOut());assertTrue(rearmed.waitInfo().progressObserved());assertEquals(0,rearmed.waitInfo().elapsedMs());assertEquals(0,rearmed.waitInfo().quietMs());assertEquals(2,rearmed.waitInfo().waitSeconds());assertEquals(15,rearmed.waitInfo().timeoutSeconds());
            verify(f.reader,times(reads)).history(TARGET,null,1);verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());
            f.advance(1);assertEquals("WAITING",f.service.refresh(first.id()).get().state());verify(f.reader,times(reads)).history(TARGET,null,1);
            f.advance(1);var stable=f.service.refresh(first.id()).get();assertEquals("READY",stable.state());assertEquals("END",stable.paginationStatus());assertNull(stable.waitInfo());verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void timeoutReachedInsideAHistoryRpcCannotBeMistakenForStableCompletion()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();f.service.press(first.id(),new BotDownloadService.Press(UUID.randomUUID().toString(),first.revision(),first.recommendedButtonId(),2,15)).get();
            var next=page("101","已获取第2组文件（共3组）","下一组 (3/3)");when(f.reader.history(TARGET,null,1)).thenReturn(next);
            f.advance(2);assertTrue(f.service.refresh(first.id()).get().waitInfo().progressObserved());
            when(f.reader.history(TARGET,null,1)).thenAnswer(call->{f.advance(12);return next;});
            f.advance(2);var delayed=f.service.refresh(first.id()).get();assertEquals("WAITING",delayed.state());assertTrue(delayed.waitInfo().timedOut());assertEquals(16000,delayed.waitInfo().elapsedMs());assertEquals(14000,delayed.waitInfo().quietMs());
        }
    }
    @Test void invalidWaitSettingsAreRejectedBeforeAnyCallbackOrHistoryRequest()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();clearInvocations(f.reader);
            Integer[][] invalid={{1,120},{61,120},{2,14},{2,301},{60,119},{5,-1}};
            for(var values:invalid)assertThrows(IllegalArgumentException.class,()->f.service.press(first.id(),new BotDownloadService.Press(UUID.randomUUID().toString(),first.revision(),first.recommendedButtonId(),values[0],values[1])));
            verifyNoInteractions(f.reader);
            var waiting=f.service.press(first.id(),f.press(first)).get();
            for(var values:invalid)assertThrows(ExecutionException.class,()->f.service.waitForReply(first.id(),new BotDownloadService.WaitRequest(waiting.revision(),values[0],values[1])).get());
            verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());verify(f.reader,times(1)).history(TARGET,null,1);
        }
    }
    @Test void continueWaitRequiresAnActiveIntentAndStaleOrDifferentSessionsCannotRefreshIt()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();assertThrows(ExecutionException.class,()->f.service.waitForReply(first.id(),new BotDownloadService.WaitRequest(first.revision(),null,null)).get());
            var waiting=f.service.press(first.id(),f.press(first)).get();
            assertThrows(ExecutionException.class,()->f.service.waitForReply(first.id(),new BotDownloadService.WaitRequest(waiting.revision()-1,2,15)).get());
            var rearmed=f.service.waitForReply(first.id(),new BotDownloadService.WaitRequest(waiting.revision(),3,18)).get();assertEquals(3,rearmed.waitInfo().waitSeconds());assertEquals(18,rearmed.waitInfo().timeoutSeconds());
            for(boolean replacement:List.of(false,true)){
                if(replacement)f.generation.incrementAndGet();else when(f.telegram.accountId()).thenReturn("2");
                assertThrows(ExecutionException.class,()->f.service.refresh(first.id()).get());
                assertThrows(ExecutionException.class,()->f.service.waitForReply(first.id(),new BotDownloadService.WaitRequest(rearmed.revision(),null,null)).get());
                when(f.telegram.accountId()).thenReturn("1");
            }
            verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());verify(f.reader,times(2)).history(TARGET,null,1);
        }
    }
    @Test void anOldTerminalMessageCannotBecomeTheNewReplyEndAfterNewMediaStabilizesOrLaterAdvertisementsArrive()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();var oldEnd=new BotReader.Message(new MessageRef("9","200",null),"文件全部获取成功！共收到3组文件",List.of(),List.of());
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(oldEnd),false,"200"));
            f.service.press(first.id(),f.press(first)).get();
            var newMedia=message("201","本轮新媒体",null);
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(newMedia,oldEnd),false,"200"));
            f.advance(5);var arriving=f.service.refresh(first.id()).get();assertTrue(arriving.waitInfo().progressObserved());assertNotEquals("END",arriving.paginationStatus());assertNotEquals("200",arriving.controlMessageId());
            f.advance(5);var stable=f.service.refresh(first.id()).get();assertEquals("READY",stable.state());assertNotEquals("END",stable.paginationStatus());assertNotEquals("200",stable.controlMessageId());
            var advert=new BotReader.Message(new MessageRef("9","202",null),"广告更新",List.of(),List.of());
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(advert,newMedia,oldEnd),false,"200"));
            assertNotEquals("END",f.service.refresh(first.id()).get().paginationStatus());
            verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void theOriginalControlCanBecomeANewExplicitEndAfterItsPaginationSemanticsChange()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();f.service.press(first.id(),f.press(first)).get();
            var end=new BotReader.Message(new MessageRef("9","100",null),"文件全部获取成功！共收到3组文件",List.of(),List.of());
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(end),false,"100"));
            f.advance(5);assertEquals("WAITING",f.service.refresh(first.id()).get().state());
            f.advance(5);var stable=f.service.refresh(first.id()).get();assertEquals("READY",stable.state());assertEquals("END",stable.paginationStatus());assertEquals("100",stable.controlMessageId());
        }
    }
    @Test void preflightPaginationChangesMustBeReviewedBeforeDispatchEvenWhenTheCallbackPayloadMatches()throws Exception{
        try(var f=new Fixture()){
            when(f.reader.history(TARGET,null,1)).thenReturn(page("100","页码:1/3","下一页"));
            var first=f.open();var request=f.press(first);
            var changed=message("100","页码:2/3","下一页");
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(changed),false,"100"));
            var failure=assertThrows(ExecutionException.class,()->f.service.press(first.id(),request).get());assertInstanceOf(IllegalArgumentException.class,failure.getCause());
            verify(f.reader,never()).press(any(),anyString(),any(),anyLong());
            var checked=f.service.refresh(first.id()).get();assertEquals("READY",checked.state());assertNull(checked.waitInfo());assertTrue(checked.revision()>first.revision());assertEquals(2,checked.currentPage());
            var pending=f.service.press(first.id(),new BotDownloadService.Press(request.operationId(),checked.revision(),checked.recommendedButtonId())).get();
            assertEquals("WAITING",pending.state());verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void aPreflightVersionAlreadySeenButMissingFromTheNextWindowCannotTurnAnOlderRetainedGrantIntoProgress()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();var original=message("100","已获取第1组文件（共3组）","下一组 (2/3)");var oldMedia=original.media().getFirst();
            var latest=new Media(oldMedia.key(),oldMedia.message(),oldMedia.source(),oldMedia.type(),oldMedia.fileName(),38,oldMedia.caption());
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(new BotReader.Message(original.ref(),original.text(),List.of(latest),original.buttons())),false,"100"));
            f.service.press(first.id(),f.press(first)).get();
            var unrelated=new BotReader.Message(new MessageRef("9","101",null),"新的广告文字",List.of(),List.of());
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(unrelated),false,"101"));
            f.advance(5);var checked=f.service.refresh(first.id()).get();assertEquals("WAITING",checked.state());assertFalse(checked.waitInfo().progressObserved());assertEquals(0,checked.waitInfo().quietMs());assertEquals(37,checked.media().getFirst().size(),"a retained old grant is preserved, not misread as a fresh rollback");
            f.advance(5);assertFalse(f.service.refresh(first.id()).get().waitInfo().progressObserved());
            verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());verify(f.reader,times(4)).history(TARGET,null,1);
        }
    }
    @Test void staleOrForeignOrBlockedButtonsNeverReachNative()throws Exception{
        try(var f=new Fixture()){
            var original=message("100","页码:1/2","关闭");var buttons=new ArrayList<>(original.buttons());buttons.add(new BotReader.Button(1,0,"下一页","CALLBACK",new byte[]{2}));
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(new BotReader.Message(original.ref(),original.text(),original.media(),buttons)),false,"100","50"));var view=f.open();
            var button=view.messages().getFirst().buttons().getFirst();assertFalse(button.supported());
            for(var input:List.of(new BotDownloadService.Press(UUID.randomUUID().toString(),view.revision(),button.id()),
                    new BotDownloadService.Press(UUID.randomUUID().toString(),view.revision()-1,button.id()),
                    new BotDownloadService.Press(UUID.randomUUID().toString(),view.revision(),UUID.randomUUID().toString())))
                assertThrows(ExecutionException.class,()->f.service.press(view.id(),input).get());
            verify(f.reader,never()).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void conflictingInitialCountersNeverFallBackToHistoricalFirstPages()throws Exception{
        try(var f=new Fixture()){
            when(f.reader.history(TARGET,null,1)).thenReturn(page("100","页码:9/3","下一页"));
            assertThrows(ExecutionException.class,f::open);verify(f.reader,never()).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void olderHistoryAndManualControllerSwitchingAreRejectedWithoutReadingOrClicking()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();assertTrue(first.historyEnd());assertNull(first.nextHistoryCursor());
            assertThrows(IllegalArgumentException.class,()->f.service.older(first.id(),new BotDownloadService.Position(first.revision())));
            assertThrows(IllegalArgumentException.class,()->f.service.control(first.id(),new BotDownloadService.Control(first.revision(),"100")));
            verify(f.reader,times(1)).history(TARGET,null,1);verify(f.reader,never()).history(eq(TARGET),anyString(),anyLong());verify(f.reader,never()).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void openingOnlyAdmitsTheLatestRequestFirstPageAndOldMediaCannotCreateATask()throws Exception{
        try(var f=new Fixture()){
            var footer=new BotReader.Message(new MessageRef("9","240",null),"页码：1/6",List.of(),List.of(new BotReader.Button(0,0,"下一页","CALLBACK",new byte[]{1})));
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(footer,message("220","本次B",null),message("210","本次A",null),message("170","页码1/4","下一页"),message("150","以前的视频",null)),false,"150","200"));
            var first=f.open();assertEquals("240",first.controlMessageId());assertEquals(Set.of("210","220"),first.media().stream().map(m->m.message().messageId()).collect(java.util.stream.Collectors.toSet()));
            assertTrue(first.messages().stream().allMatch(m->Long.parseLong(m.id())>200));assertTrue(first.historyEnd());assertNull(first.nextHistoryCursor());
            var request=f.input(first);assertThrows(ExecutionException.class,()->f.service.create(new BotDownloadService.Create(request.operationId(),request.sourceId(),request.revision(),List.of("1:9:150:VIDEO"),request.targetDirectory())).get());
            verifyNoInteractions(f.files);verify(f.reader,times(1)).history(TARGET,null,1);verify(f.reader,never()).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void aMissingResourceBoundaryDoesNotImportTheWholeRecentChat()throws Exception{
        try(var f=new Fixture()){
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(message("100","页码1/3","下一页"),message("90","不知属于哪次资源",null)),false,"90"));
            var failure=assertThrows(ExecutionException.class,f::open);assertInstanceOf(IllegalArgumentException.class,failure.getCause());
            verify(f.reader,times(1)).history(TARGET,null,1);verify(f.reader,never()).history(eq(TARGET),anyString(),anyLong());verifyNoInteractions(f.files);assertTrue(f.repository.list("1",20,0).isEmpty());
        }
    }
    @Test void openingOnALaterPageCannotFallBackToAnOlderFirstPage()throws Exception{
        try(var f=new Fixture()){
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(message("200","页码2/3","下一页"),message("100","页码1/3","下一页")),false,"100","50"));
            assertThrows(ExecutionException.class,f::open);verify(f.reader,never()).press(any(),anyString(),any(),anyLong());verifyNoInteractions(f.files);
        }
    }
    @Test void unrequestedHistoryIsNotAppendedAndEachCallbackOnlyAdmitsNewRepliesPastItsBarrier()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();var original=message("100","已获取第1组文件（共3组）","下一组 (2/3)");
            var unrelated=message("110","点击翻页之前已经存在的别的媒体",null);var old=message("20","旧媒体",null);
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(unrelated,original,old),false,"20","50"));
            var checked=f.service.refresh(first.id()).get();assertEquals(1,checked.media().size());assertEquals("100",checked.media().getFirst().message().messageId());
            f.service.press(first.id(),f.press(checked)).get();
            var next=message("111","本次翻页新媒体",null);var updated=message("100","已获取第2组文件（共3组）","下一组 (3/3)");
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(next,unrelated,updated,old),false,"20","50"));
            f.advance(5);var arriving=f.service.refresh(first.id()).get();assertEquals(Set.of("100","111"),arriving.media().stream().map(m->m.message().messageId()).collect(java.util.stream.Collectors.toSet()));
            f.advance(5);assertEquals("READY",f.service.refresh(first.id()).get().state());verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void aNewOutgoingRequestInvalidatesTheOldResourceBeforeItCanReadOrCreateAgain()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();var request=f.input(first);
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(message("300","页码1/6","下一页")),false,"300","250"));
            assertThrows(ExecutionException.class,()->f.service.refresh(first.id()).get());
            assertThrows(ExecutionException.class,()->f.service.create(request).get());assertThrows(ExecutionException.class,()->f.service.press(first.id(),f.press(first)).get());
            verify(f.reader,never()).press(any(),anyString(),any(),anyLong());verifyNoInteractions(f.files);assertTrue(f.repository.list("1",20,0).isEmpty());
        }
    }
    @Test void anOutgoingRequestWithoutANewFirstPageNeverReturnsThePreviousResource()throws Exception{
        try(var f=new Fixture()){
            when(f.reader.history(TARGET,null,1)).thenReturn(new BotReader.Snapshot(TARGET,List.of(message("100","页码1/3","下一页")),false,"100","200"));
            assertThrows(ExecutionException.class,f::open);verify(f.reader,times(1)).history(TARGET,null,1);verify(f.reader,never()).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void expiredAndReplacementSessionPreviewsCannotExecuteCallbacks()throws Exception{
        for(boolean expired:List.of(true,false))try(var f=new Fixture()){
            var view=f.open();if(expired)f.clock.addAndGet(TimeUnit.MINUTES.toNanos(11));else f.generation.incrementAndGet();
            assertThrows(ExecutionException.class,()->f.service.press(view.id(),new BotDownloadService.Press(UUID.randomUUID().toString(),view.revision(),view.recommendedButtonId())).get());
            verify(f.reader,never()).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void creatingBotTaskFreezesObservedMembersAndReusesExistingPersistenceAndLookup()throws Exception{
        try(var f=new Fixture()){
            var view=f.open();var input=f.input(view);var task=f.service.create(input).get();
            assertEquals("QUEUED",task.state());assertEquals(1,task.total());assertEquals(f.output.toString(),task.targetDirectory());assertEquals(DownloadModels.KEEP_DUPLICATES,task.duplicatePolicy());
            assertEquals(task,f.service.lookup(input.operationId()).task());assertEquals(task,f.service.create(input).get());
            var stored=f.repository.task("1",task.id());assertEquals("bot:9:9:100",stored.post().key());assertEquals("BOT",stored.items().getFirst().file().media().source());
            assertEquals("original.mp4",stored.items().getFirst().relativeFile());verify(f.files,times(1)).freeze(any(),anyList(),eq(1L));assertFalse(Files.exists(f.output));
            f.clock.addAndGet(TimeUnit.MINUTES.toNanos(20));assertEquals(task,f.service.create(input).get());
            assertThrows(IllegalArgumentException.class,()->f.service.create(new BotDownloadService.Create(input.operationId(),input.sourceId(),input.revision(),input.mediaKeys(),directory.resolve("other").toString())));
        }
    }
    @Test void observedMediaCanBeDownloadedWithoutWaitingForPaginationOrReplayingAnyCallback()throws Exception{
        for(String state:List.of("READY","WAITING","NEEDS_REVIEW","TIMED_OUT"))try(var f=new Fixture()){
            var view=f.open();BotDownloadService.Press press=null;
            if(!state.equals("READY")){
                if(state.equals("NEEDS_REVIEW"))doThrow(new IllegalArgumentException("unknown callback result")).when(f.reader).press(any(),anyString(),any(),anyLong());
                press=new BotDownloadService.Press(UUID.randomUUID().toString(),view.revision(),view.recommendedButtonId(),2,15);
                view=f.service.press(view.id(),press).get();
                if(state.equals("TIMED_OUT")){f.advance(15);view=f.service.refresh(view.id()).get();assertTrue(view.waitInfo().timedOut());}
            }
            String before=view.state();var beforeWait=view.waitInfo();
            int calls=mockingDetails(f.reader).getInvocations().size();
            var request=f.input(view);var task=f.service.create(request).get();
            assertEquals("QUEUED",task.state(),state);assertEquals(1,task.total(),state);assertEquals(task,f.service.create(request).get());
            assertEquals(calls,mockingDetails(f.reader).getInvocations().size(),"creating a download must not read or press the bot: "+state);
            verify(f.files,times(1)).freeze(any(),anyList(),eq(1L));assertEquals(1,f.repository.list("1",20,0).size());
            if(press!=null){var unchanged=f.service.press(view.id(),press).get();assertEquals(before,unchanged.state());assertEquals(beforeWait,unchanged.waitInfo());verify(f.reader,times(1)).press(any(),anyString(),any(),anyLong());}
            else verify(f.reader,never()).press(any(),anyString(),any(),anyLong());
        }
    }
    @Test void aStaleSelectionRevisionStillCannotCreateADownloadWhilePaginationIsWaiting()throws Exception{
        try(var f=new Fixture()){
            var first=f.open();var oldRequest=f.input(first);f.service.press(first.id(),f.press(first)).get();
            assertThrows(ExecutionException.class,()->f.service.create(oldRequest).get());verifyNoInteractions(f.files);assertTrue(f.repository.list("1",20,0).isEmpty());
        }
    }
    @Test void unobservedSelectionsPermissionsAndSessionChangesNeverCreateTasks()throws Exception{
        try(var f=new Fixture()){
            var view=f.open();var input=f.input(view);
            assertThrows(ExecutionException.class,()->f.service.create(new BotDownloadService.Create(input.operationId(),input.sourceId(),input.revision(),List.of("not-observed"),input.targetDirectory())).get());
            when(f.files.freeze(any(),anyList(),anyLong())).thenThrow(new IllegalArgumentException("protected"));assertThrows(ExecutionException.class,()->f.service.create(input).get());
            doAnswer(call->{f.generation.incrementAndGet();return List.of();}).when(f.files).freeze(any(),anyList(),anyLong());assertThrows(ExecutionException.class,()->f.service.create(input).get());
            assertTrue(f.repository.list("1",20,0).isEmpty());assertFalse(Files.exists(f.output));
        }
    }
    @Test void sessionChangeAfterCommitIsUnknownAndLookupFindsTheOriginalTask()throws Exception{
        try(var f=new Fixture()){
            var repository=spy(f.repository);
            doAnswer(call->{var result=call.callRealMethod();f.generation.incrementAndGet();return result;}).when(repository).create(anyString(),anyString(),anyString(),any(),any(),anyList());
            var service=new BotDownloadService(f.work,f.reader,f.telegram,f.files,repository,new DownloadPaths(f.paths),f.clock::get);
            var view=service.open(new BotDownloadService.Open("@synthetic_bot")).get();var input=f.input(view);
            var failure=assertThrows(ExecutionException.class,()->service.create(input).get());assertInstanceOf(IllegalStateException.class,failure.getCause());
            assertEquals(1,f.repository.list("1",20,0).size());var task=service.lookup(input.operationId()).task();assertNotNull(task);assertEquals(task,service.create(input).get());
        }
    }
    private final class Fixture implements AutoCloseable {
        final AppPaths paths=new AppPaths(directory.resolve(UUID.randomUUID().toString()));final Database database=new Database(paths);final DownloadRepository repository=new DownloadRepository(database);
        final ResourceService work=mock(ResourceService.class);final BotReader reader=mock(BotReader.class);final TelegramService telegram=mock(TelegramService.class);final DownloadFileReader files=mock(DownloadFileReader.class);
        final AtomicLong generation=new AtomicLong(1),clock=new AtomicLong(1);final Path output=directory.resolve("output").toAbsolutePath();final BotDownloadService service;
        Fixture(){
            when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenAnswer(call->generation.get());
            when(work.readWork(any())).thenAnswer(call->{try{return CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(0)).get());}catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}});
            when(reader.open("@synthetic_bot",1)).thenReturn(TARGET);when(reader.history(TARGET,null,1)).thenReturn(page("100","已获取第1组文件（共3组）","下一组 (2/3)"));
            when(files.freeze(any(),anyList(),anyLong())).thenAnswer(call->{List<Media> media=call.getArgument(1);return media.stream().map(m->new FrozenFile(m,"uid:"+m.key(),m.size(),m.fileName())).toList();});
            service=new BotDownloadService(work,reader,telegram,files,repository,new DownloadPaths(paths),clock::get);
        }
        BotDownloadService.View open()throws Exception{return service.open(new BotDownloadService.Open("@synthetic_bot")).get();}
        BotDownloadService.Press press(BotDownloadService.View view){return new BotDownloadService.Press(UUID.randomUUID().toString(),view.revision(),view.recommendedButtonId());}
        void advance(long seconds){clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds));}
        BotDownloadService.Create input(BotDownloadService.View view){return new BotDownloadService.Create(UUID.randomUUID().toString(),view.id(),view.revision(),view.media().stream().map(Media::key).toList(),output.toString());}
        public void close(){database.close();}
    }
}
