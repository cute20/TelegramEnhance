package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.DownloadModels.*;
import it.tdlight.jni.TdApi;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class BotCapturedDownloadIntegrationTest {
    @TempDir Path directory;

    @Test void observedFileSurvivesMessageLossAndRestartedNativeHandlesThroughCreationPersistenceAndDownload()throws Exception{
        exerciseDownload(false);
    }
    @Test void aFinalReferenceFailureRefreshesTheSameFileOnceAndTheEngineReadsItsRepairedHandle()throws Exception{
        exerciseDownload(true);
    }
    private void exerciseDownload(boolean expireOnStart)throws Exception{
        var paths=new AppPaths(directory.resolve("business"));var gateway=new Gateway();var telegram=mock(TelegramService.class);var work=mock(ResourceService.class);
        when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);
        when(work.readWork(any())).thenAnswer(call->{try{return CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(0)).get());}catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}});
        var nativeReader=new DownloadFileReader(gateway,()->"1",()->1L);var botReader=new BotReader(gateway,()->"1",()->1L);
        var json=JsonMapper.builder().build();
        try(var database=new Database(paths)){
            var repository=new DownloadRepository(database);var downloadPaths=new DownloadPaths(paths);
            var service=new BotDownloadService(work,botReader,telegram,nativeReader,repository,downloadPaths);
            DownloadEngine engine=null;
            try{
                var view=service.open(new BotDownloadService.Open("@synthetic_bot")).get(3,TimeUnit.SECONDS);
                assertEquals(1,view.media().size());assertPrivateProjection(json.writeValueAsString(view));
                assertEquals(0,gateway.fileDownloads());
                // Bot pagination can make the original message unavailable after its file was observed.
                gateway.originalMissing=!expireOnStart;
                var request=new BotDownloadService.Create(UUID.randomUUID().toString(),view.id(),view.revision(),view.media().stream().map(Media::key).toList(),directory.resolve("output").toString());
                service.prepare(request).get(3,TimeUnit.SECONDS);var created=service.create(request).get(3,TimeUnit.SECONDS);
                assertEquals("READY",service.preparation(request.operationId()).state());assertEquals(1,created.total());assertPrivateProjection(json.writeValueAsString(created));
                var stored=repository.task("1",created.id());var captured=stored.items().getFirst().file();
                assertEquals(Gateway.UNIQUE,captured.uniqueId());assertNotNull(captured.botReference());assertEquals(7,captured.botReference().fileId());assertEquals(Gateway.REMOTE,captured.botReference().remoteId());
                assertEquals(captured,json.readValue(json.writeValueAsString(captured),FrozenFile.class));
                assertEquals(0,gateway.fileDownloads(),"Capturing and preparing file metadata must not start a transfer");

                // A new native session may assign a different integer handle. The persisted remote identity remains exact.
                gateway.remapHandles=!expireOnStart;gateway.expireOnStart=expireOnStart;
                var resumedReader=new DownloadFileReader(gateway,()->"1",()->1L);
                engine=new DownloadEngine(repository,resumedReader,new DownloadFileStore(downloadPaths,repository),telegram,paths);
                var completed=await(repository,created.id(),"COMPLETED");assertEquals(1,repository.view(completed).completed());
                var output=Path.of(repository.view(completed).items().getFirst().outputPath());assertArrayEquals(Gateway.BYTES,Files.readAllBytes(output));
                if(!expireOnStart)assertTrue(gateway.calls.stream().anyMatch(query->query instanceof TdApi.GetRemoteFile));
                assertTrue(gateway.calls.stream().filter(query->query instanceof TdApi.ReadFilePart).allMatch(query->((TdApi.ReadFilePart)query).fileId==27));
                assertTrue(gateway.calls.stream().noneMatch(query->query instanceof TdApi.GetMessages));
                assertEquals(expireOnStart?2:1,gateway.calls.stream().filter(query->query instanceof TdApi.GetMessage).count(),"The known source is checked before transfer and again after a confirmed final failure");
                assertEquals(expireOnStart?2:1,gateway.fileDownloads());
                if(expireOnStart){
                    assertTrue(gateway.calls.stream().anyMatch(query->query instanceof TdApi.CancelDownloadFile cancel&&cancel.fileId==27));
                    assertTrue(gateway.calls.stream().anyMatch(query->query instanceof TdApi.DeleteFile delete&&delete.fileId==27));
                }
            }finally{if(engine!=null)engine.close();service.close();}
        }
    }

    @Test void legacyFrozenFileJsonHasNoInventedReferenceAndPrivateReferenceStringsStayRedacted(){
        var media=new io.github.telegramenhance.library.ResourceModels.Media("1:71:101:0",new io.github.telegramenhance.library.ResourceModels.MessageRef("71","101",null),"BOT","DOCUMENT","file.bin",11,"");
        var json=JsonMapper.builder().build();String legacy="{\"media\":"+json.writeValueAsString(media)+",\"uniqueId\":\"legacy\",\"size\":11,\"fileName\":\"file.bin\"}";
        var restored=json.readValue(legacy,FrozenFile.class);assertNull(restored.botReference());assertEquals("legacy",restored.uniqueId());
        var captured=new FrozenFile(media,Gateway.UNIQUE,11,"file.bin",new BotFileReference(7,Gateway.REMOTE));
        assertFalse(captured.toString().contains(Gateway.REMOTE));assertFalse(captured.botReference().toString().contains(Gateway.REMOTE));
    }

    private static void assertPrivateProjection(String json){
        for(String privateValue:List.of(Gateway.REMOTE,Gateway.UNIQUE,"botReference","observedFiles","fileId","remoteId"))assertFalse(json.contains(privateValue),privateValue);
    }
    private static Task await(DownloadRepository repository,String id,String state)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);Task task;
        do{task=repository.task("1",id);if(task.state().equals(state))return task;if(task.state().equals("FAILED"))fail(task.error());Thread.sleep(20);}while(System.nanoTime()<until);
        fail("Expected "+state+" but got "+task.state()+": "+task.error());return task;
    }
    private static final class Gateway implements BotReader.Gateway,DownloadFileReader.Gateway {
        static final String REMOTE="private-native-remote-captured",UNIQUE="private-native-unique-captured";
        static final byte[] BYTES={1,2,3,4,5,6,7,8,9,10,11};
        final Queue<TdApi.Function<?>> calls=new ConcurrentLinkedQueue<>();volatile boolean originalMissing,remapHandles,expireOnStart,expired,downloadCompleted,cacheDeleted;
        long fileDownloads(){return calls.stream().filter(query->query instanceof TdApi.DownloadFile).count();}
        @Override @SuppressWarnings("unchecked") public <R extends TdApi.Object> CompletableFuture<R> read(long session,TdApi.Function<R> query){
            calls.add(query);assertEquals(1,session);TdApi.Object response;
            if(query instanceof TdApi.GetChat||query instanceof TdApi.SearchPublicChat){var chat=new TdApi.Chat();chat.id=71;chat.title="合成机器人";chat.type=new TdApi.ChatTypePrivate(72);response=chat;}
            else if(query instanceof TdApi.GetUser){var user=new TdApi.User();user.id=72;user.type=new TdApi.UserTypeBot();response=user;}
            else if(query instanceof TdApi.GetChatHistory)response=originalMissing?new TdApi.Messages(2,new TdApi.Message[]{footer(),request()}):new TdApi.Messages(3,new TdApi.Message[]{footer(),media(),request()});
            else if(query instanceof TdApi.GetMessages messages){var values=new TdApi.Message[messages.messageIds.length];for(int i=0;i<values.length;i++)if(!originalMissing&&messages.messageIds[i]==101)values[i]=media();response=new TdApi.Messages(values.length,values);}
            else if(query instanceof TdApi.GetMessage message)response=originalMissing?null:message.messageId==101?media():footer();
            else if(query instanceof TdApi.GetFile get)response=remapHandles&&!expireOnStart&&get.fileId==7?file(7,"unrelated-unique","unrelated-remote"):file(expireOnStart?get.fileId:remapHandles?27:7,UNIQUE,REMOTE);
            else if(query instanceof TdApi.GetRemoteFile get){assertEquals(REMOTE,get.remoteFileId);response=file(remapHandles?27:7,UNIQUE,REMOTE);}
            else if(query instanceof TdApi.GetFileDownloadedPrefixSize get)response=new TdApi.FileDownloadedPrefixSize(downloadCompleted&&!cacheDeleted?Math.max(0,BYTES.length-get.offset):0);
            else if(query instanceof TdApi.DownloadFile download){
                assertEquals(remapHandles?27:7,download.fileId);
                assertTrue(download.synchronous);
                if(expireOnStart&&!expired){expired=true;remapHandles=true;return CompletableFuture.failedFuture(new it.tdlight.client.TelegramError(new TdApi.Error(400,"FILE_REFERENCE_0_EXPIRED")));}
                downloadCompleted=true;cacheDeleted=false;
                response=file(download.fileId,UNIQUE,REMOTE);
            }
            else if(query instanceof TdApi.ReadFilePart part){assertEquals(27,part.fileId);assertFalse(cacheDeleted);response=new TdApi.Data(Arrays.copyOfRange(BYTES,(int)part.offset,(int)(part.offset+part.count)));}
            else if(query instanceof TdApi.CancelDownloadFile cancel){assertEquals(27,cancel.fileId);response=new TdApi.Ok();}
            else if(query instanceof TdApi.DeleteFile delete){assertEquals(27,delete.fileId);cacheDeleted=true;response=new TdApi.Ok();}
            else throw new AssertionError("Unexpected native request "+query.getClass().getSimpleName());
            return CompletableFuture.completedFuture((R)response);
        }
        private TdApi.File file(int id,String unique,String remote){
            var file=new TdApi.File();file.id=id;file.size=BYTES.length;file.remote=new TdApi.RemoteFile(remote,unique,false,true,BYTES.length);file.local=new TdApi.LocalFile();
            file.local.canBeDownloaded=true;file.local.isDownloadingCompleted=!cacheDeleted&&(!expireOnStart||downloadCompleted);file.local.downloadedSize=file.local.isDownloadingCompleted?BYTES.length:0;return file;
        }
        private static TdApi.Message message(long id){var value=new TdApi.Message();value.id=id;value.chatId=71;value.senderId=new TdApi.MessageSenderUser(72);return value;}
        private TdApi.Message media(){var value=message(101);value.content=new TdApi.MessageDocument(new TdApi.Document("captured.bin","application/octet-stream",null,null,file(remapHandles?27:7,UNIQUE,REMOTE)),new TdApi.FormattedText("",new TdApi.TextEntity[0]));return value;}
        private static TdApi.Message footer(){var value=message(102);value.content=new TdApi.MessageText(new TdApi.FormattedText("页码:1/2",new TdApi.TextEntity[0]),null,null);value.replyMarkup=new TdApi.ReplyMarkupInlineKeyboard(new TdApi.InlineKeyboardButton[][]{{new TdApi.InlineKeyboardButton("下一页",0,null,new TdApi.InlineKeyboardButtonTypeCallback(new byte[]{1}))}},false);return value;}
        private static TdApi.Message request(){var value=message(50);value.isOutgoing=true;value.senderId=new TdApi.MessageSenderUser(1);value.content=new TdApi.MessageText(new TdApi.FormattedText("/start synthetic",new TdApi.TextEntity[0]),null,null);return value;}
    }
}
