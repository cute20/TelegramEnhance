package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.transfer.DownloadModels.*;
import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class BotDownloadFileReaderTest {
    @Test void capturedBotStartsCheckTheKnownSourceBeforeStartingAtTheVerifiedCheckpoint(){
        var f=new Fixture();var captured=f.capture();f.replaceOriginalFile(27,"stable",11);
        assertEquals(27,f.reader.startObservedBotAt(f.post,captured,1,7,5));
        var start=f.calls.stream().filter(TdApi.DownloadFile.class::isInstance).map(TdApi.DownloadFile.class::cast).findFirst().orElseThrow();
        assertEquals(27,start.fileId);assertEquals(5,start.offset);assertTrue(start.synchronous);
        assertEquals(1,f.calls.stream().filter(TdApi.GetMessage.class::isInstance).count());
        assertEquals(7,captured.botReference().fileId(),"The immutable captured identity is never rewritten");
    }
    @Test void onlyMissingOr404OriginalMessagesMayUseTheirStillVerifiedCapturedFileAtStart(){
        for(boolean notFoundError:List.of(false,true)){
            var f=new Fixture();var captured=f.capture();f.messages.clear();
            if(notFoundError)f.messageFailure=new it.tdlight.client.TelegramError(new TdApi.Error(404,"private"));
            assertEquals(7,f.reader.startObservedBotAt(f.post,captured,1,7,3));assertEquals(1,f.startCalls);
        }
    }
    @Test void readableButChangedBotSourcesCannotStartOrRefreshTheirCapturedFiles(){
        for(String mode:List.of("unique","size","sender","outgoing","protected","secret","overlay")){
            var f=new Fixture();var captured=f.capture();
            if(mode.equals("unique"))f.replaceOriginalFile(27,"replacement",11);
            if(mode.equals("size"))f.replaceOriginalFile(27,"stable",12);
            if(mode.equals("sender"))f.message.senderId=new TdApi.MessageSenderUser(999);
            if(mode.equals("outgoing"))f.message.isOutgoing=true;
            if(mode.equals("protected"))f.chat.hasProtectedContent=true;
            if(mode.equals("secret"))f.message.selfDestructIn=10;
            if(mode.equals("overlay"))f.message.ephemeralContent=new TdApi.EphemeralMessageContent();
            assertThrows(IllegalArgumentException.class,()->f.reader.startObservedBotAt(f.post,captured,1,7,0),mode);
            assertThrows(IllegalArgumentException.class,()->f.reader.refreshObservedBot(f.post,captured,1,7),mode);
            assertEquals(0,f.startCalls,mode);
        }
    }
    @Test void aMissingSourceAfterAFinalDownloadFailureIsRetryableWithoutClaimingConfirmedExpiration(){
        for(boolean notFoundError:List.of(false,true)){
            var f=new Fixture();var captured=f.capture();f.messages.clear();
            if(notFoundError)f.messageFailure=new it.tdlight.client.TelegramError(new TdApi.Error(404,"private"));
            var failure=assertThrows(DownloadFileReader.TemporarilyUnavailable.class,()->f.reader.refreshObservedBot(f.post,captured,1,7));
            assertFalse(failure.getMessage().contains("引用已失效"));assertFalse(failure.getMessage().contains("private"));assertEquals(0,f.startCalls);
        }
    }
    @Test void botMediaFreezesAndRechecksTheOriginalPrivateBotMessageWithoutStartingTransfer() {
        var f = new Fixture(); var file = f.reader.freeze(f.post, List.of(f.media), 1).getFirst();
        assertEquals("BOT", file.media().source()); assertEquals("native-file-name.bin", file.fileName()); assertEquals("stable", file.uniqueId()); assertEquals(11, file.size());
        assertNull(file.media().message().link()); assertEquals(7, f.reader.resolve(f.post, file, 1).fileId());
        assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.GetUser || query instanceof TdApi.GetMessages || query instanceof TdApi.GetMessage || query instanceof TdApi.GetMessageProperties || query instanceof TdApi.GetMessageLink));
    }
    @Test void multipleBotFilesFreezeTheirValidatedIdentityAndVersionWithoutOptionalLinksOrTransfers() {
        var f = new Fixture();
        var selected = List.of(f.media, f.addDocument(102, "second.bin", 12, "stable-102"), f.addDocument(103, "third.bin", 13, "stable-103"));
        var post = f.post("bot:72:71:100", selected.stream().map(Media::message).toList());
        var frozen = f.reader.freeze(post, selected, 1);
        assertEquals(3, frozen.size());
        for (int index = 0; index < selected.size(); index++) {
            assertEquals(selected.get(index), frozen.get(index).media());
            assertEquals(selected.get(index).size(), frozen.get(index).size());
            assertEquals(selected.get(index).fileName(), frozen.get(index).fileName());
            assertNull(frozen.get(index).media().message().link());
        }
        assertEquals(List.of("stable", "stable-102", "stable-103"), frozen.stream().map(file -> file.uniqueId()).toList());
        for (Class<?> type : List.of(TdApi.GetChat.class, TdApi.GetUser.class))
            assertEquals(2, f.calls.stream().filter(type::isInstance).count());
        assertEquals(1,f.calls.stream().filter(TdApi.GetMessages.class::isInstance).count());
        assertTrue(f.calls.stream().noneMatch(query -> query instanceof TdApi.GetMessageProperties || query instanceof TdApi.GetMessageLink));
        assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.GetUser || query instanceof TdApi.GetMessages));
    }
    @Test void hundredsOfBotFilesUseBoundedBatchesWithoutASharedPreparationDeadline(){
        var f=new Fixture();var selected=new ArrayList<Media>();
        for(int index=0;index<258;index++)selected.add(f.addDocument(1000+index,"file-"+index+".bin",100+index,"stable-"+index));
        f.readNanos=TimeUnit.SECONDS.toNanos(12);
        var frozen=f.reader.freeze(f.post("bot:72:71:100",selected.stream().map(Media::message).toList()),selected,1);
        assertEquals(258,frozen.size());
        for(int index=0;index<selected.size();index++){
            assertEquals(selected.get(index),frozen.get(index).media());assertEquals("stable-"+index,frozen.get(index).uniqueId());
        }
        assertTrue(f.now>TimeUnit.SECONDS.toNanos(60),"healthy later batches must not inherit the first batch's deadline");
        var batches=f.calls.stream().filter(TdApi.GetMessages.class::isInstance).map(TdApi.GetMessages.class::cast).toList();
        assertEquals(List.of(50,50,50,50,50,8),batches.stream().map(batch->batch.messageIds.length).toList());
        assertEquals(10,f.calls.size(),"two identity checks and six bounded message requests suffice for258 files");
        assertTrue(f.calls.stream().noneMatch(query->query instanceof TdApi.GetMessage||query instanceof TdApi.DownloadFile||query instanceof TdApi.ReadFilePart));
    }
    @Test void laterBatchFailuresNameTheAffectedFileAndDoNotReturnPartialPreparation(){
        for(String mode:List.of("missing","mismatched","renamed","failed","short")){
            var f=new Fixture();var selected=new ArrayList<Media>();
            for(int index=0;index<158;index++)selected.add(f.addDocument(1000+index,"file-"+index+".bin",100+index,"stable-"+index));
            if(mode.equals("missing"))f.messages.remove(1100L);
            if(mode.equals("mismatched"))f.messages.get(1100L).id=999;
            if(mode.equals("renamed"))((TdApi.MessageDocument)f.messages.get(1100L).content).document.fileName="replacement.bin";
            if(mode.equals("failed"))f.failedBatch=3;
            if(mode.equals("short"))f.shortBatch=3;
            var failure=assertThrows(IllegalArgumentException.class,()->f.reader.freeze(f.post("bot:72:71:100",selected.stream().map(Media::message).toList()),selected,1));
            assertTrue(failure.getMessage().contains("file-100.bin"),mode);
            assertFalse(failure.getMessage().contains("native-private-error"));
            assertEquals(3,f.batches);assertTrue(f.calls.stream().noneMatch(query->query instanceof TdApi.DownloadFile||query instanceof TdApi.ReadFilePart));
        }
    }
    @Test void sessionAndSourceIdentityChangesDuringABatchAbortBeforeReturningFiles(){
        for(String mode:List.of("session","account","protected","bot")){
            var f=new Fixture();
            f.afterBatch=()->{switch(mode){case "session"->f.session++;case "account"->f.account="2";case "protected"->f.chat.hasProtectedContent=true;case "bot"->f.user.type=new TdApi.UserTypeRegular();}};
            assertThrows(IllegalArgumentException.class,()->f.reader.freeze(f.post,List.of(f.media),1));
            assertTrue(f.calls.stream().noneMatch(query->query instanceof TdApi.DownloadFile||query instanceof TdApi.ReadFilePart));
        }
    }
    @Test void anObservedBotVideoUsesTheOriginalFileWithoutTheChannelSavePropertyGate() {
        var f = new Fixture(); f.properties.canBeSaved = false; f.message.canBeSaved = false;
        var video = new TdApi.Video(); video.fileName = "original-video.mp4"; video.video = f.file;
        var content = new TdApi.MessageVideo(); content.video = video; content.caption = new TdApi.FormattedText("", new TdApi.TextEntity[0]);
        f.message.content = content;
        var selected = new Media(f.media.key(), f.media.message(), "BOT", "VIDEO", video.fileName, 11, "");
        var frozen = f.reader.freeze(f.post, List.of(selected), 1).getFirst();
        assertEquals("original-video.mp4", frozen.fileName()); assertEquals("stable", frozen.uniqueId()); assertEquals(11, frozen.size());
        assertEquals(7, f.reader.resolve(f.post, frozen, 1).fileId());
        assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.GetUser || query instanceof TdApi.GetMessages || query instanceof TdApi.GetMessage));
    }
    @Test void anExistingBotSourceLinkIsPreservedWithoutLookingItUpAgain() {
        var f = new Fixture();
        var ref = new MessageRef("71", "101", "https://t.me/sample_bot/101");
        var selected = new Media(f.media.key(), ref, "BOT", f.media.type(), f.media.fileName(), f.media.size(), f.media.caption());
        var frozen = f.reader.freeze(f.post("bot:72:71:100", List.of(ref)), List.of(selected), 1).getFirst();
        assertEquals(ref.link(), frozen.media().message().link());
        assertTrue(f.calls.stream().noneMatch(query -> query instanceof TdApi.GetMessageLink));
    }
    @Test void initialBotFreezeRejectsASizeChangeUnderTheSameObservedMessageKeyWithoutStartingTransfer() {
        var f = new Fixture(); f.file.size = 12;
        var failure = assertThrows(IllegalArgumentException.class, () -> f.reader.freeze(f.post, List.of(f.media), 1));
        assertTrue(failure.getMessage().contains("源文件已变化"));
        assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.GetUser || query instanceof TdApi.GetMessages || query instanceof TdApi.GetMessage || query instanceof TdApi.GetMessageProperties));
        assertTrue(f.calls.stream().noneMatch(query -> query instanceof TdApi.DownloadFile || query instanceof TdApi.ReadFilePart || query instanceof TdApi.GetMessageLink));
    }
    @Test void initialBotFreezeRejectsANameChangeUnderTheSameObservedMessageKeyWithoutStartingTransfer() {
        var f = new Fixture(); ((TdApi.MessageDocument) f.message.content).document.fileName = "replaced-name.bin";
        var failure = assertThrows(IllegalArgumentException.class, () -> f.reader.freeze(f.post, List.of(f.media), 1));
        assertTrue(failure.getMessage().contains("源文件已变化"));
        assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.GetUser || query instanceof TdApi.GetMessages || query instanceof TdApi.GetMessage || query instanceof TdApi.GetMessageProperties));
        assertTrue(f.calls.stream().noneMatch(query -> query instanceof TdApi.DownloadFile || query instanceof TdApi.ReadFilePart || query instanceof TdApi.GetMessageLink));
    }
    @Test void botResumeKeepsItsFrozenNameWhenOnlyTheNativeFileNameChanges() {
        var f = new Fixture(); var file = f.reader.freeze(f.post, List.of(f.media), 1).getFirst();
        ((TdApi.MessageDocument) f.message.content).document.fileName = "renamed-after-freeze.bin";
        var resolved = f.reader.resolve(f.post, file, 1);
        assertSame(file, resolved.file()); assertEquals("native-file-name.bin", resolved.file().fileName()); assertEquals(7, resolved.fileId());
        assertTrue(f.calls.stream().noneMatch(query -> query instanceof TdApi.DownloadFile || query instanceof TdApi.ReadFilePart));
    }
    @Test void aPrivateBotTaskCannotAuthorizeAnUnobservedMessageOrMismatchedProvenanceKey() {
        for (String key : List.of("1:71:100", "bot:72:71:999", "bot:72:999:100", "bot:-72:71:100", "bot:9223372036854775808:71:100")) {
            var f = new Fixture(); assertThrows(IllegalArgumentException.class, () -> f.reader.freeze(f.post(key, List.of(f.media.message())), List.of(f.media), 1)); assertTrue(f.calls.isEmpty());
        }
        var f = new Fixture(); assertThrows(IllegalArgumentException.class, () -> f.reader.freeze(f.post("bot:72:71:100", List.of()), List.of(f.media), 1)); assertTrue(f.calls.isEmpty());
    }
    @Test void botSourcesCannotBorrowTheMainOrCommentAuthorizationBranches() {
        for (String source : List.of("MAIN", "COMMENT", "REPLY")) {
            var f = new Fixture(); var selected = new Media(f.media.key(), f.media.message(), source, f.media.type(), f.media.fileName(), 11, "");
            assertThrows(IllegalArgumentException.class, () -> f.reader.freeze(f.post, List.of(selected), 1));
            assertTrue(f.calls.stream().noneMatch(query -> query instanceof TdApi.DownloadFile || query instanceof TdApi.ReadFilePart));
        }
    }
    @Test void anotherSenderOutgoingOrChannelPostsCannotBeDownloadedAsBotReplies() {
        for (String mode : List.of("sender", "outgoing", "channel")) {
            var f = new Fixture();
            if (mode.equals("sender")) f.message.senderId = new TdApi.MessageSenderUser(999);
            if (mode.equals("outgoing")) f.message.isOutgoing = true;
            if (mode.equals("channel")) f.message.isChannelPost = true;
            assertThrows(IllegalArgumentException.class, () -> f.reader.freeze(f.post, List.of(f.media), 1));
        }
    }
    @Test void chatAndBotIdentityAreRevalidatedAndRegularAccountsCannotUseBotScope() {
        for (String mode : List.of("chat", "type", "user", "regular")) {
            var f = new Fixture();
            if (mode.equals("chat")) f.chat.id = 999;
            if (mode.equals("type")) f.chat.type = new TdApi.ChatTypePrivate(999);
            if (mode.equals("user")) f.user.id = 999;
            if (mode.equals("regular")) f.user.type = new TdApi.UserTypeRegular();
            assertThrows(IllegalArgumentException.class, () -> f.reader.freeze(f.post, List.of(f.media), 1));
        }
    }
    @Test void protectedChatsAndSelfDestructingBotMediaStillFailBeforeAnyTransfer() {
        for (String mode : List.of("protected", "ttl")) {
            var f = new Fixture();
            if (mode.equals("protected")) f.chat.hasProtectedContent = true;
            if (mode.equals("ttl")) f.message.selfDestructIn = 1;
            var failure = assertThrows(IllegalArgumentException.class, () -> f.reader.freeze(f.post, List.of(f.media), 1));
            if (mode.equals("ttl")) { assertTrue(failure.getMessage().contains(f.media.fileName())); assertTrue(failure.getMessage().contains("阅后即焚")); }
            assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.GetUser || query instanceof TdApi.GetMessages || query instanceof TdApi.GetMessage));
        }
    }
    @Test void aUserSpecificContentOverlayCannotFallBackToDownloadingTheUnderlyingMessageFile() {
        var f = new Fixture(); f.message.ephemeralContent = new TdApi.EphemeralMessageContent();
        f.message.ephemeralContent.canBeSaved = true; f.message.ephemeralContent.content = f.message.content;
        var failure = assertThrows(IllegalArgumentException.class, () -> f.reader.freeze(f.post, List.of(f.media), 1));
        assertTrue(failure.getMessage().contains(f.media.fileName())); assertTrue(failure.getMessage().contains("替代内容"));
        assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.GetUser || query instanceof TdApi.GetMessages || query instanceof TdApi.GetMessage));
    }
    @Test void missingAndMismatchedBotMessagesAreReportedSeparatelyFromUnsupportedContent() {
        for (String mode : List.of("missing", "id", "chat", "channel")) {
            var f = new Fixture();
            if (mode.equals("missing")) f.messages.clear();
            if (mode.equals("id")) f.message.id = 999;
            if (mode.equals("chat")) f.message.chatId = 999;
            if (mode.equals("channel")) f.message.isChannelPost = true;
            var failure = assertThrows(IllegalArgumentException.class, () -> f.reader.freeze(f.post, List.of(f.media), 1));
            assertTrue(failure.getMessage().contains(f.media.fileName()));
            if(mode.equals("missing")){
                assertTrue(failure.getMessage().contains("没有可用的已保存文件引用"));
                assertTrue(failure.getMessage().contains("重新让机器人返回该文件"));
                assertFalse(failure.getMessage().contains("身份不符"));assertFalse(failure.getMessage().contains("连接"));
                assertFalse(failure.getMessage().contains("未创建下载任务"));
            }else{
                assertTrue(failure.getMessage().contains(mode.equals("channel") ? "原机器人私聊" : "消息回执身份不符"));
                assertFalse(failure.getMessage().contains("翻页时清理"));
            }
            assertFalse(failure.getMessage().contains("不可保存"));
        }
    }
    @Test void botResumeCannotSubstituteAnotherFileVersionAndSessionChangesStopBeforeReads() {
        var f = new Fixture(); var file = f.reader.freeze(f.post, List.of(f.media), 1).getFirst(); f.file.remote.uniqueId = "new-version";
        assertThrows(IllegalArgumentException.class, () -> f.reader.resolve(f.post, file, 1));
        f.calls.clear(); f.session = 2;
        assertThrows(IllegalArgumentException.class, () -> f.reader.resolve(f.post, file, 1)); assertTrue(f.calls.isEmpty());
    }
    @Test void aMissingMessageDuringTaskResumeExplainsPageRecoveryWithoutClaimingNoTaskExists(){
        var f=new Fixture();var file=f.reader.freeze(f.post,List.of(f.media),1).getFirst();f.messages.clear();
        var failure=assertThrows(IllegalArgumentException.class,()->f.reader.resolve(f.post,file,1));
        assertTrue(failure.getMessage().contains(f.media.fileName()));assertTrue(failure.getMessage().contains("没有可用的已保存文件引用"));
        assertTrue(failure.getMessage().contains("重新让机器人返回该文件"));
        assertFalse(failure.getMessage().contains("未创建下载任务"));assertFalse(failure.getMessage().contains("连接"));
    }
    @Test void observedNativeFileSurvivesAnUnavailableOriginalMessageWithoutRepeatingMessageReads(){
        var f=new Fixture();var captured=f.capture();f.messages.clear();
        var frozen=f.reader.freezeObservedBot(f.post,List.of(f.media),Map.of(f.media.key(),captured),1);
        assertEquals(List.of(captured),frozen);assertEquals(captured.botReference(),frozen.getFirst().botReference());
        var resolved=f.reader.resolve(f.post,captured,1);assertSame(captured,resolved.file());assertEquals(7,resolved.fileId());
        assertEquals(2,f.calls.stream().filter(TdApi.GetFile.class::isInstance).count());
        assertTrue(f.calls.stream().noneMatch(query->query instanceof TdApi.GetMessage||query instanceof TdApi.GetMessages||query instanceof TdApi.GetRemoteFile||query instanceof TdApi.DownloadFile));
    }
    @Test void remappedNativeFileIdsCanOnlyResolveTheExactCapturedRemoteFile(){
        for(boolean nativeFailure:List.of(false,true)){
            var f=new Fixture();var captured=f.capture();f.messages.clear();
            var wrong=new TdApi.File();wrong.id=7;wrong.size=11;wrong.remote=new TdApi.RemoteFile("wrong","somebody-else",false,false,0);
            f.nativeFiles.put(7,wrong);f.failNative=nativeFailure;
            var current=new TdApi.File();current.id=88;current.size=11;current.remote=new TdApi.RemoteFile("rotated-valid-reference","stable",false,false,0);
            f.remoteFiles.put(captured.botReference().remoteId(),current);
            var resolved=f.reader.resolve(f.post,captured,1);assertEquals(88,resolved.fileId());assertSame(captured,resolved.file());
            assertTrue(f.calls.stream().filter(TdApi.GetRemoteFile.class::isInstance).map(TdApi.GetRemoteFile.class::cast).allMatch(query->query.remoteFileId.equals(captured.botReference().remoteId())));
            assertTrue(f.calls.stream().noneMatch(query->query instanceof TdApi.GetMessage||query instanceof TdApi.DownloadFile));
        }
    }
    @Test void aWrongRemoteIdentityOrSizeNeverAuthorizesTheReplacementFile(){
        for(String mismatch:List.of("unique","size","id")){
            var f=new Fixture();var captured=f.capture();f.nativeFiles.clear();f.messages.clear();
            var wrong=new TdApi.File();wrong.id=mismatch.equals("id")?0:88;wrong.size=mismatch.equals("size")?12:11;
            wrong.remote=new TdApi.RemoteFile("opaque",mismatch.equals("unique")?"wrong":"stable",false,false,0);
            f.remoteFiles.put(captured.botReference().remoteId(),wrong);
            var failure=assertThrows(IllegalArgumentException.class,()->f.reader.freezeObservedBot(f.post,List.of(f.media),Map.of(f.media.key(),captured),1));
            assertTrue(failure.getMessage().contains("重新让机器人发送该文件"));
            assertFalse(failure.getMessage().contains("文件引用已失效"),"identity or source failures must not masquerade as a confirmed native expiration");
            assertTrue(f.calls.stream().noneMatch(query->query instanceof TdApi.DownloadFile||query instanceof TdApi.ReadFilePart));
        }
    }
    @Test void capturedReferencesStillRequireExactSelectionSourceProtectionAndAccount(){
        for(String change:List.of("selection","key","protected","bot","account","session","scope")){
            var f=new Fixture();var captured=f.capture();var selected=f.media;var post=f.post;
            if(change.equals("selection"))selected=new Media(selected.key(),selected.message(),"BOT",selected.type(),"changed.bin",11,"");
            if(change.equals("key"))selected=new Media("2:71:101:0",selected.message(),"BOT",selected.type(),selected.fileName(),11,"");
            if(change.equals("protected"))f.chat.hasProtectedContent=true;
            if(change.equals("bot"))f.user.type=new TdApi.UserTypeRegular();
            if(change.equals("account"))f.account="2";
            if(change.equals("session"))f.session=2;
            if(change.equals("scope"))post=f.post("bot:72:71:100",List.of());
            var currentSelection=selected;var currentPost=post;
            assertThrows(IllegalArgumentException.class,()->f.reader.freezeObservedBot(currentPost,List.of(currentSelection),Map.of(currentSelection.key(),captured),1),change);
            assertTrue(f.calls.stream().noneMatch(query->query instanceof TdApi.GetFile||query instanceof TdApi.GetRemoteFile||query instanceof TdApi.DownloadFile),change);
        }
    }
    @Test void capturedAndLegacySelectionsCanBePreparedTogetherWithoutGrantingUncapturedMissingMessages(){
        var f=new Fixture();var captured=f.capture();var next=f.addDocument(102,"next.bin",12,"next-stable");
        var post=f.post("bot:72:71:100",List.of(f.media.message(),next.message()));f.messages.remove(101L);
        var frozen=f.reader.freezeObservedBot(post,List.of(f.media,next),Map.of(f.media.key(),captured),1);
        assertEquals(2,frozen.size());assertSame(captured,frozen.getFirst());assertNull(frozen.getLast().botReference());
        var batch=f.calls.stream().filter(TdApi.GetMessages.class::isInstance).map(TdApi.GetMessages.class::cast).findFirst().orElseThrow();assertArrayEquals(new long[]{102},batch.messageIds);
        f.messages.clear();assertThrows(IllegalArgumentException.class,()->f.reader.freezeObservedBot(post,List.of(f.media,next),Map.of(f.media.key(),captured),1));
    }
    @Test void legacyFrozenJsonHasNoNativeReferenceAndStillRequiresItsOriginalMessage(){
        var f=new Fixture();var legacy=f.reader.freeze(f.post,List.of(f.media),1).getFirst();
        var json=JsonMapper.builder().build();String oldJson=json.writeValueAsString(legacy).replace(",\"botReference\":null","");
        var restored=json.readValue(oldJson,FrozenFile.class);assertNull(restored.botReference());f.messages.clear();f.calls.clear();
        assertThrows(IllegalArgumentException.class,()->f.reader.resolve(f.post,restored,1));
        assertTrue(f.calls.stream().anyMatch(TdApi.GetMessage.class::isInstance));assertTrue(f.calls.stream().noneMatch(TdApi.GetFile.class::isInstance));
    }
    @Test void onlyAnActualTelegramReferenceErrorIsReportedAsExpired(){
        var f=new Fixture();var captured=f.capture();f.nativeFiles.clear();f.messages.clear();
        f.remoteFailure=new it.tdlight.client.TelegramError(new TdApi.Error(400,"FILE_REFERENCE_EXPIRED"));
        var failure=assertThrows(IllegalArgumentException.class,()->f.reader.resolve(f.post,captured,1));
        assertEquals("文件引用已失效，请重新让机器人发送该文件",failure.getMessage());
    }
    @Test void temporaryRetainedHandleFailuresDoNotBecomePermanentMissingSourceErrors(){
        var f=new Fixture();var captured=f.capture();f.nativeFiles.clear();
        f.remoteFailure=new it.tdlight.client.TelegramError(new TdApi.Error(503,"private"));
        assertThrows(DownloadFileReader.TemporarilyUnavailable.class,()->f.reader.resolve(f.post,captured,1));
        assertTrue(f.calls.stream().noneMatch(TdApi.GetMessage.class::isInstance));
    }
    @Test void temporaryErrorsWhileRefreshingAnExpiredReferenceRemainRetryable(){
        var f=new Fixture();var captured=f.capture();
        f.startFailure=new it.tdlight.client.TelegramError(new TdApi.Error(400,"FILE_REFERENCE_EXPIRED"));
        f.messageFailure=new it.tdlight.client.TelegramError(new TdApi.Error(503,"private"));
        assertThrows(DownloadFileReader.TemporarilyUnavailable.class,()->f.reader.startObservedBot(f.post,captured,1,7));
        assertEquals(0,f.startCalls,"The source check happens before beginning the native transfer");
    }
    @Test void aFinalExpiredReferenceIsExposedForTheEngineToRefreshWithoutAnInvisibleInternalRetry(){
        var f=new Fixture();var captured=f.capture();f.replaceOriginalFile(27,"stable",11);
        f.startFailure=new it.tdlight.client.TelegramError(new TdApi.Error(400,"FILE_REFERENCE_EXPIRED"));
        assertEquals(27,f.reader.startObservedBot(f.post,captured,1,7));
        assertTrue(assertThrows(DownloadFileReader.DownloadFailed.class,()->f.reader.checkDownload("1",1,27,"stable",11)).referenceRejected());
        assertEquals(27,f.reader.refreshObservedBot(f.post,captured,1,27));f.reader.startAt("1",1,27,"stable",11,5);
        assertDoesNotThrow(()->f.reader.checkDownload("1",1,27,"stable",11));
        var starts=f.calls.stream().filter(TdApi.DownloadFile.class::isInstance).map(TdApi.DownloadFile.class::cast).toList();
        assertEquals(List.of(27,27),starts.stream().map(query->query.fileId).toList());assertEquals(5,starts.getLast().offset);
        assertEquals(2,f.calls.stream().filter(TdApi.GetMessage.class::isInstance).count());assertEquals("stable",captured.uniqueId());assertEquals(7,captured.botReference().fileId());
    }
    @Test void expiryRefreshCannotSubstituteAnotherVersionOrRetryAnUnavailableSource(){
        for(String mode:List.of("unique","size","missing","sender","protected")){
            var f=new Fixture();var captured=f.capture();f.replaceOriginalFile(27,mode.equals("unique")?"replacement":"stable",mode.equals("size")?12:11);
            if(mode.equals("missing"))f.messages.clear();if(mode.equals("sender"))f.message.senderId=new TdApi.MessageSenderUser(999);
            f.startFailure=new it.tdlight.client.TelegramError(new TdApi.Error(400,"FILE_REFERENCE_EXPIRED"));
            if(mode.equals("protected"))f.beforeStartFailure=()->f.chat.hasProtectedContent=true;
            if(mode.equals("missing")){
                f.reader.startObservedBot(f.post,captured,1,7);assertThrows(DownloadFileReader.DownloadFailed.class,()->f.reader.checkDownload("1",1,7,"stable",11));
                assertThrows(DownloadFileReader.TemporarilyUnavailable.class,()->f.reader.refreshObservedBot(f.post,captured,1,7));
            }else{
                if(mode.equals("protected"))f.chat.hasProtectedContent=true;
                assertThrows(IllegalArgumentException.class,()->f.reader.startObservedBot(f.post,captured,1,7),mode);
                assertEquals(0,f.startCalls,mode);
            }
        }
    }
    @Test void downloadErrorsWithoutAConfirmedExpiredReferenceNeverRefreshOrAutomaticallyRetry(){
        var f=new Fixture();var captured=f.capture();f.startFailure=new it.tdlight.client.TelegramError(new TdApi.Error(400,"FILE_ID_INVALID"));
        f.reader.startObservedBot(f.post,captured,1,7);
        var failure=assertThrows(IllegalArgumentException.class,()->f.reader.checkDownload("1",1,7,"stable",11));
        assertFalse(failure.getMessage().contains("引用已失效"));assertEquals(1,f.startCalls);assertEquals(1,f.calls.stream().filter(TdApi.GetMessage.class::isInstance).count());
    }
    @Test void repeatedActualExpirationStopsAfterOneRefreshAndOneRetry(){
        var f=new Fixture();var captured=f.capture();f.replaceOriginalFile(27,"stable",11);f.alwaysFailStart=true;
        f.startFailure=new it.tdlight.client.TelegramError(new TdApi.Error(400,"FILE_REFERENCE_EXPIRED"));
        f.reader.startObservedBot(f.post,captured,1,7);
        assertThrows(DownloadFileReader.DownloadFailed.class,()->f.reader.checkDownload("1",1,27,"stable",11));
        assertEquals(1,f.startCalls,"The engine, rather than nested reader retries, owns the recovery budget");
        assertEquals(1,f.calls.stream().filter(TdApi.GetMessage.class::isInstance).count());
    }
    private static final class Fixture implements DownloadFileReader.Gateway {
        final List<TdApi.Function<?>> calls = new ArrayList<>();
        final Map<Long, TdApi.Message> messages = new HashMap<>();
        final Map<Integer,TdApi.File> nativeFiles=new HashMap<>();final Map<String,TdApi.File> remoteFiles=new HashMap<>();
        final TdApi.Chat chat = new TdApi.Chat(); final TdApi.User user = new TdApi.User(); final TdApi.Message message = new TdApi.Message();
        final TdApi.MessageProperties properties = new TdApi.MessageProperties(); final TdApi.File file = new TdApi.File();
        long session = 1,now,readNanos;String account="1";int batches,failedBatch,shortBatch;Runnable afterBatch=()->{};
        boolean failNative,alwaysFailStart;RuntimeException remoteFailure,startFailure,messageFailure;int startCalls;Runnable beforeStartFailure=()->{};
        final DownloadFileReader reader = new DownloadFileReader(this, () -> account, () -> session,()->now);
        final Media media = new Media("1:71:101:0", new MessageRef("71", "101", null), "BOT", "DOCUMENT", "native-file-name.bin", 11, "");
        final Post post = post("bot:72:71:100", List.of(media.message()));
        Fixture() {
            chat.id = 71; chat.type = new TdApi.ChatTypePrivate(72); user.id = 72; user.type = new TdApi.UserTypeBot();
            file.id = 7; file.size = 11; file.remote = new TdApi.RemoteFile("private-remote-original", "stable", false, false, 0);
            nativeFiles.put(7,file);remoteFiles.put(file.remote.id,file);
            message.id = 101; message.chatId = 71; message.senderId = new TdApi.MessageSenderUser(72);
            message.content = new TdApi.MessageDocument(new TdApi.Document(media.fileName(), "application/octet-stream", null, null, file), new TdApi.FormattedText("", new TdApi.TextEntity[0]));
            messages.put(message.id, message);
            properties.canBeSaved = true;
        }
        Media addDocument(long id, String name, long size, String uniqueId) {
            var documentFile = new TdApi.File(); documentFile.id = (int) id; documentFile.size = size; documentFile.remote = new TdApi.RemoteFile("", uniqueId, false, false, 0);
            var next = new TdApi.Message(); next.id = id; next.chatId = 71; next.senderId = new TdApi.MessageSenderUser(72);
            next.content = new TdApi.MessageDocument(new TdApi.Document(name, "application/octet-stream", null, null, documentFile), new TdApi.FormattedText("", new TdApi.TextEntity[0]));
            messages.put(id, next);
            return new Media("1:71:" + id + ":0", new MessageRef("71", Long.toString(id), null), "BOT", "DOCUMENT", name, size, "");
        }
        FrozenFile capture(){var captured=DownloadFileReader.observeBotFile("1",new BotReader.Target("71","72","样本机器人"),message,media);assertNotNull(captured);return captured;}
        void replaceOriginalFile(int id,String unique,long size){var fresh=new TdApi.File();fresh.id=id;fresh.size=size;fresh.remote=new TdApi.RemoteFile("fresh-reference",unique,false,false,0);nativeFiles.put(id,fresh);((TdApi.MessageDocument)message.content).document.document=fresh;}
        Post post(String key, List<MessageRef> refs) { return new Post(key, "1", "71", "样本机器人", "100", "样本", "", "0", refs, List.of(media), null, null, List.of(), false, "PARTIAL", List.of()); }
        @Override @SuppressWarnings("unchecked") public <R extends TdApi.Object> CompletableFuture<R> read(long session, TdApi.Function<R> request) {
            calls.add(request);now+=readNanos;TdApi.Object result;
            if (request instanceof TdApi.GetChat) result = chat;
            else if (request instanceof TdApi.GetUser) result = user;
            else if (request instanceof TdApi.GetMessage get) {if(messageFailure!=null)return CompletableFuture.failedFuture(messageFailure);result = messages.get(get.messageId);}
            else if(request instanceof TdApi.GetFile get){if(failNative)return CompletableFuture.failedFuture(new IllegalStateException("native-file-unavailable"));result=nativeFiles.get(get.fileId);}
            else if(request instanceof TdApi.GetRemoteFile get){if(remoteFailure!=null)return CompletableFuture.failedFuture(remoteFailure);result=remoteFiles.get(get.remoteFileId);}
            else if(request instanceof TdApi.DownloadFile download){startCalls++;if(startFailure!=null&&(startCalls==1||alwaysFailStart)){beforeStartFailure.run();return CompletableFuture.failedFuture(startFailure);}result=nativeFiles.get(download.fileId);}
            else if (request instanceof TdApi.GetMessages get) {
                batches++;assertEquals(71,get.chatId);assertTrue(get.messageIds.length>0&&get.messageIds.length<=50);
                if(batches==failedBatch)return CompletableFuture.failedFuture(new IllegalStateException("native-private-error"));
                int length=get.messageIds.length-(batches==shortBatch?1:0);var values=new TdApi.Message[length];
                for(int index=0;index<length;index++)values[index]=messages.get(get.messageIds[index]);
                result=new TdApi.Messages(length,values);afterBatch.run();
            }
            else if (request instanceof TdApi.GetMessageProperties) result = properties;
            else if (request instanceof TdApi.GetMessageLink) result = new TdApi.MessageLink();
            else throw new AssertionError("Unexpected bot file command");
            return CompletableFuture.completedFuture((R) result);
        }
    }
}
