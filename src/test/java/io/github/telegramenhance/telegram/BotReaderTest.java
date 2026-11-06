package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.telegram.BotReader.*;
import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class BotReaderTest {
    @Test void openingABotResolvesOnlyItsIdentityWithoutStartingOrFollowingTheResource() {
        for (String source : List.of("@sample_bot", "sample_bot", "https://t.me/sample_bot", "https://telegram.me/sample_bot/", "71")) {
            var f = new Fixture(); var target = f.reader.open(source, 1);
            assertEquals(new Target("71", "72", "样本机器人"), target);
            assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.SearchPublicChat || query instanceof TdApi.GetUser));
            if (!source.equals("71")) assertEquals("sample_bot", ((TdApi.SearchPublicChat) f.calls.getFirst()).username);
        }
    }
    @Test void rejectsDeeplinksExecutableUrlsAndInvalidSourcesBeforeNativeCommands() {
        for (String source : List.of("https://t.me/sample_bot?start=secret", "https://t.me/sample_bot/23", "tg://resolve?domain=sample_bot", "http://t.me/sample_bot", "https:///sample_bot", "https://t.me.evil/sample_bot", "https://user@t.me/sample_bot", "-71", "9223372036854775808", "@bad/name", "")) {
            var f = new Fixture(); assertThrows(IllegalArgumentException.class, () -> f.reader.open(source, 1), source); assertTrue(f.calls.isEmpty());
        }
    }
    @Test void privateBotsOnlyAndTheReturnedChatMustMatchTheRequestedNumericIdentity() {
        var regular = new Fixture(); regular.user.type = new TdApi.UserTypeRegular();
        assertThrows(IllegalArgumentException.class, () -> regular.reader.open("71", 1));
        var channel = new Fixture(); channel.chat.type = new TdApi.ChatTypeSupergroup(4, true);
        assertThrows(IllegalArgumentException.class, () -> channel.reader.open("71", 1));
        var protectedChat = new Fixture(); protectedChat.chat.hasProtectedContent = true;
        assertThrows(IllegalArgumentException.class, () -> protectedChat.reader.open("71", 1));
        var wrong = new Fixture(); wrong.chat.id = 999;
        assertThrows(IllegalArgumentException.class, () -> wrong.reader.open("71", 1));
    }
    @Test void historyOffersOnlyIncomingMessagesFromTheVerifiedBotAndMapsNoInventedLink() {
        var f = new Fixture(); var own = message(100); own.content = document(7, "file", 11); own.replyMarkup = keyboard(callback("下一组", new byte[]{1}));
        var outgoing = message(99); outgoing.isOutgoing = true;
        var otherSender = message(98); otherSender.senderId = new TdApi.MessageSenderUser(999);
        var channelPost = message(97); channelPost.isChannelPost = true;
        f.history = new TdApi.Message[]{own, outgoing, otherSender, channelPost};
        var page = f.reader.history(f.target, null, 1);
        assertFalse(page.historyEnd()); assertEquals("97", page.nextCursor()); assertEquals(1, page.messages().size());
        var mapped = page.messages().getFirst(); assertEquals("BOT", mapped.media().getFirst().source()); assertNull(mapped.ref().link());
        assertEquals("CALLBACK", mapped.buttons().getFirst().type());
        assertTrue(f.calls.stream().noneMatch(query -> query instanceof TdApi.DownloadFile || query instanceof TdApi.ReadFilePart || query instanceof TdApi.GetCallbackQueryAnswer));
        var history = (TdApi.GetChatHistory) f.calls.stream().filter(query -> query instanceof TdApi.GetChatHistory).findFirst().orElseThrow();
        assertEquals(50, history.limit); assertFalse(history.onlyLocal); assertEquals(0, history.fromMessageId);
    }
    @Test void aShortPageOrAFilteredEmptyPageNeverPretendsTheBotHasFinished() {
        var f = new Fixture(); var outgoing = message(99); outgoing.isOutgoing = true; f.history = new TdApi.Message[]{outgoing};
        var filtered = f.reader.history(f.target, null, 1); assertTrue(filtered.messages().isEmpty()); assertFalse(filtered.historyEnd()); assertEquals("99", filtered.nextCursor());
        f.history = new TdApi.Message[0]; var empty = f.reader.history(f.target, "99", 1); assertTrue(empty.historyEnd()); assertNull(empty.nextCursor());
    }
    @Test void historyCapturesTheOriginalFileIdentityBeforeLaterPagesCanRemoveTheMessage(){
        var f=new Fixture();f.message.content=document(7,"stable-file",37);
        var file=((TdApi.MessageDocument)f.message.content).document.document;file.remote.id="private-remote-reference";
        var observed=f.reader.history(f.target,null,1).messages().getFirst();var media=observed.media().getFirst();var captured=observed.observedFiles().get(media.key());
        assertNotNull(captured);assertEquals(media,captured.media());assertEquals("stable-file",captured.uniqueId());assertEquals(37,captured.size());
        assertEquals(7,captured.botReference().fileId());assertEquals("private-remote-reference",captured.botReference().remoteId());
        assertThrows(UnsupportedOperationException.class,()->observed.observedFiles().clear());
        file.remote.id="changed-native-reference";file.remote.uniqueId="changed-native-identity";file.id=99;
        assertEquals(7,captured.botReference().fileId());assertEquals("stable-file",captured.uniqueId());assertEquals("private-remote-reference",captured.botReference().remoteId());
        String serialized=JsonMapper.builder().build().writeValueAsString(observed);
        assertFalse(serialized.contains("private-remote-reference"));assertFalse(serialized.contains("observedFiles"));assertFalse(observed.toString().contains("private-remote-reference"));
        assertTrue(f.calls.stream().noneMatch(query->query instanceof TdApi.GetFile||query instanceof TdApi.DownloadFile||query instanceof TdApi.ForwardMessages));
    }
    @Test void unsafeOrUnverifiableMediaNeverCaptureAReusableNativeFileHandle(){
        for(String mode:List.of("self-destruct","overlay","secret","unknown-size","unknown-identity","wrong-sender","outgoing","channel")){
            var f=new Fixture();f.message.content=document(7,"stable-file",37);
            var file=((TdApi.MessageDocument)f.message.content).document.document;
            if(mode.equals("self-destruct"))f.message.selfDestructIn=1;
            if(mode.equals("overlay"))f.message.ephemeralContent=new TdApi.EphemeralMessageContent();
            if(mode.equals("unknown-size"))file.size=0;
            if(mode.equals("unknown-identity"))file.remote.uniqueId="";
            if(mode.equals("wrong-sender"))f.message.senderId=new TdApi.MessageSenderUser(999);
            if(mode.equals("outgoing"))f.message.isOutgoing=true;
            if(mode.equals("channel"))f.message.isChannelPost=true;
            if(mode.equals("secret")){var content=new TdApi.MessageVideo();content.video=new TdApi.Video();content.video.video=file;content.isSecret=true;f.message.content=content;}
            var view=f.reader.history(f.target,null,1);
            assertTrue(view.messages().stream().allMatch(message->message.observedFiles().isEmpty()),mode);
        }
    }
    @Test void legacyBotMessageConstructorsDoNotInventCapturedFileAuthority(){
        var message=new Message(new io.github.telegramenhance.library.ResourceModels.MessageRef("71","100",null),"text",List.of(),List.of());
        assertTrue(message.observedFiles().isEmpty());
    }
    @Test void historyUsesStrictOlderCursorsAndDoesNotRepeatAnInclusiveAnchor() {
        var f = new Fixture(); f.history = new TdApi.Message[]{message(100), message(99), message(99), message(98)};
        var page = f.reader.history(f.target, "100", 1);
        assertEquals(List.of("99", "98"), page.messages().stream().map(item -> item.ref().messageId()).toList()); assertEquals("98", page.nextCursor());
        f.history = new TdApi.Message[]{message(100)};
        assertThrows(IllegalArgumentException.class, () -> f.reader.history(f.target, "100", 1));
    }
    @Test void malformedCrossChatAndOversizedHistoryAreRejected() {
        var f = new Fixture(); var other = message(100); other.chatId = 888; f.history = new TdApi.Message[]{other};
        assertThrows(IllegalArgumentException.class, () -> f.reader.history(f.target, null, 1));
        f.history = new TdApi.Message[51]; assertThrows(IllegalArgumentException.class, () -> f.reader.history(f.target, null, 1));
        f.history = null; assertThrows(IllegalArgumentException.class, () -> f.reader.history(f.target, null, 1));
    }
    @Test void ephemeralMediaAreNotOfferedAndUnsupportedButtonsHaveNoCallbackBytes() {
        var f = new Fixture(); var secret = message(100); secret.content = document(7, "secret", 11); secret.selfDestructIn = 5;
        secret.replyMarkup = keyboard(new TdApi.InlineKeyboardButton("广告", 0, null, new TdApi.InlineKeyboardButtonTypeUrl("https://example.invalid")));
        f.history = new TdApi.Message[]{secret}; var result = f.reader.history(f.target, null, 1).messages().getFirst();
        assertTrue(result.media().isEmpty()); assertEquals("UNSUPPORTED", result.buttons().getFirst().type()); assertEquals(0, result.buttons().getFirst().data().length);
    }
    @Test void aSelectedCallbackIsRefetchedComparedAndInvokedExactlyOnce() {
        var f = new Fixture(); f.message.replyMarkup = keyboard(callback("下一页", new byte[]{1, 2}));
        var button = new Button(0, 0, "下一页", "CALLBACK", new byte[]{1, 2}); f.reader.press(f.target, "100", button, 1);
        assertEquals(1, f.callbacks()); assertEquals(1, f.calls.stream().filter(query -> query instanceof TdApi.GetMessage).count());
        var dispatched = (TdApi.GetCallbackQueryAnswer) f.calls.getLast(); assertEquals(71, dispatched.chatId); assertEquals(100, dispatched.messageId);
        assertArrayEquals(new byte[]{1, 2}, ((TdApi.CallbackQueryPayloadData) dispatched.payload).data);
    }
    @Test void changedPayloadTextRowOrButtonTypeNeverDispatchesACallback() {
        for (String change : List.of("bytes", "text", "row", "type")) {
            var f = new Fixture(); f.message.replyMarkup = keyboard(callback(change.equals("text") ? "另一个操作" : "下一页", new byte[]{1, 2}));
            if (change.equals("type")) f.message.replyMarkup = keyboard(new TdApi.InlineKeyboardButton("下一页", 0, null, new TdApi.InlineKeyboardButtonTypeUrl("https://example.invalid")));
            var expected = new Button(change.equals("row") ? 1 : 0, 0, "下一页", "CALLBACK", change.equals("bytes") ? new byte[]{3} : new byte[]{1, 2});
            assertThrows(IllegalArgumentException.class, () -> f.reader.press(f.target, "100", expected, 1)); assertEquals(0, f.callbacks());
        }
    }
    @Test void unknownCallbackAnswersAreNeverAutomaticallyReplayedOrLeaked() {
        var f = new Fixture(); f.message.replyMarkup = keyboard(callback("下一页", new byte[]{1})); f.callbackFailure = true;
        var error = assertThrows(IllegalArgumentException.class, () -> f.reader.press(f.target, "100", new Button(0, 0, "下一页", "CALLBACK", new byte[]{1}), 1));
        assertEquals(1, f.callbacks()); assertFalse(error.getMessage().contains("payload-secret")); assertTrue(error.getMessage().contains("核对"));
    }
    @Test void staleOrChangedBotIdentitiesAbortBeforeDispatch() {
        var f = new Fixture(); f.session = 2;
        assertThrows(IllegalArgumentException.class, () -> f.reader.open("71", 1)); assertTrue(f.calls.isEmpty());
        f.session = 1; f.chat.type = new TdApi.ChatTypePrivate(999);
        assertThrows(IllegalArgumentException.class, () -> f.reader.history(f.target, null, 1)); assertEquals(0, f.callbacks());
        f.chat.type = new TdApi.ChatTypePrivate(72); f.mutateAccountOnGet = true;
        assertThrows(IllegalArgumentException.class, () -> f.reader.history(f.target, null, 1));
    }
    @Test void buttonBytesAreDefensivelyCopiedAndNeverAppearInToString() {
        byte[] original = {4, 5}; var button = new Button(0, 0, "private-text", "CALLBACK", original); original[0] = 1;
        byte[] returned = button.data(); returned[1] = 2; assertArrayEquals(new byte[]{4, 5}, button.data());
        assertFalse(button.toString().contains("private-text")); assertFalse(button.toString().contains("[4, 5]"));
    }
    @Test void aHistoryWindowRetainsAtMostOneHundredButtonsAcrossAllItsMessages() {
        var f = new Fixture(); var history = new ArrayList<TdApi.Message>();
        for (int index = 0; index < 50; index++) {
            var message = message(100 - index); message.replyMarkup = keyboard(callback("下一页", new byte[]{1}), callback("上一页", new byte[]{2}), callback("广告", new byte[]{3})); history.add(message);
        }
        f.history = history.toArray(TdApi.Message[]::new); var page = f.reader.history(f.target, null, 1);
        assertEquals(50, page.messages().size()); assertEquals(100, page.messages().stream().mapToInt(message -> message.buttons().size()).sum());
        assertEquals(3, page.messages().getFirst().buttons().size()); assertEquals(0, page.messages().getLast().buttons().size());
    }
    @Test void aBotTitleRenameDoesNotInvalidateTheFrozenPrivateChatIdentity() {
        var f = new Fixture(); f.chat.title = "新机器人名称";
        assertEquals(f.target, f.reader.history(f.target, null, 1).target());
    }
    @Test void currentBotRepliesExcludeAllMediaAndControlsBeforeTheLatestOwnRequest() {
        var f = new Fixture();
        var old = message(100); old.content = document(1, "old-resource", 11); old.replyMarkup = keyboard(callback("下一页", new byte[]{1}));
        var current = message(121); current.content = document(2, "current-resource", 12);
        var footer = message(122); footer.replyMarkup = keyboard(callback("下一组", new byte[]{2}));
        f.history = new TdApi.Message[]{footer, current, request(120), message(110), old};
        var snapshot = f.reader.history(f.target, null, 1);
        assertEquals("120", snapshot.requestBoundary());assertEquals("100", snapshot.nextCursor());assertFalse(snapshot.historyEnd());
        assertEquals(List.of("122", "121"), snapshot.messages().stream().map(value -> value.ref().messageId()).toList());
        assertEquals(1, snapshot.messages().stream().mapToInt(value -> value.media().size()).sum());
        assertTrue(snapshot.messages().stream().noneMatch(value -> value.text().contains("private-resource-token")));
        assertFalse(snapshot.toString().contains("private-resource-token"));
    }
    @Test void theLatestOwnRequestWinsEvenWhenRawMessagesAreNotOrderedAndSeveralResourcesShareTheWindow() {
        var f = new Fixture();
        f.history = new TdApi.Message[]{message(101), request(120), message(121), request(100), message(119)};
        var snapshot = f.reader.history(f.target, null, 1);
        assertEquals("120", snapshot.requestBoundary());assertEquals(List.of("121"), snapshot.messages().stream().map(value -> value.ref().messageId()).toList());
    }
    @Test void pendingFailedOrForeignOutgoingMessagesCannotReplaceTheFormalSelfRequestBoundary() {
        for (String kind : List.of("pending", "failed", "foreign", "channel")) {
            var f = new Fixture(); var untrusted = request(150);
            if (kind.equals("pending")) untrusted.sendingState = new TdApi.MessageSendingStatePending();
            if (kind.equals("failed")) untrusted.sendingState = new TdApi.MessageSendingStateFailed();
            if (kind.equals("foreign")) untrusted.senderId = new TdApi.MessageSenderUser(999);
            if (kind.equals("channel")) untrusted.isChannelPost = true;
            f.history = new TdApi.Message[]{untrusted, message(125), request(100)};
            var snapshot = f.reader.history(f.target, null, 1);
            assertEquals("100", snapshot.requestBoundary(), kind);assertEquals(List.of("125"), snapshot.messages().stream().map(value -> value.ref().messageId()).toList(), kind);
        }
    }
    @Test void aWindowWithoutAConfirmedSelfRequestHasNoInventedBoundaryAndStillKeepsItsRawCursor() {
        var f = new Fixture(); f.history = new TdApi.Message[]{message(110), message(100)};
        var snapshot = f.reader.history(f.target, null, 1);
        assertNull(snapshot.requestBoundary());assertEquals(2, snapshot.messages().size());assertEquals("100", snapshot.nextCursor());
        f.history = new TdApi.Message[0];snapshot = f.reader.history(f.target, null, 1);assertNull(snapshot.requestBoundary());assertTrue(snapshot.historyEnd());
    }
    @Test void requestsWithoutAnyLaterBotReplyDoNotExposeThePreviousResource() {
        var f = new Fixture();f.history = new TdApi.Message[]{request(120), message(110), message(100)};
        var snapshot = f.reader.history(f.target, null, 1);
        assertEquals("120", snapshot.requestBoundary());assertTrue(snapshot.messages().isEmpty());assertFalse(snapshot.historyEnd());
    }
    @Test void requestBoundariesRemainExactLongStringsRatherThanJavascriptNumbers() {
        var f = new Fixture();long exact = 9_007_199_254_740_993L;
        f.history = new TdApi.Message[]{message(exact + 1), request(exact), message(exact - 1)};
        var snapshot = f.reader.history(f.target, null, 1);
        assertEquals("9007199254740993", snapshot.requestBoundary());assertEquals("9007199254740994", snapshot.messages().getFirst().ref().messageId());
    }
    @Test void oldSnapshotConstructorsDoNotPretendToHaveARequestBoundary() {
        assertNull(new Snapshot(new Target("71", "72", "机器人"), List.of(), false).requestBoundary());
        assertNull(new Snapshot(new Target("71", "72", "机器人"), List.of(), false, "100").requestBoundary());
    }
    @Test void aSessionChangeWhileReadingTheRawWindowCannotPublishAnOldRequestBoundary() {
        var f = new Fixture();f.history = new TdApi.Message[]{message(121), request(120), message(100)};f.changeSessionOnHistory = true;
        assertThrows(IllegalArgumentException.class, () -> f.reader.history(f.target, null, 1));assertEquals(0, f.callbacks());
    }
    private static TdApi.Message message(long id) {
        var message = new TdApi.Message(); message.id = id; message.chatId = 71; message.senderId = new TdApi.MessageSenderUser(72);
        message.content = new TdApi.MessageText(new TdApi.FormattedText("第1组", new TdApi.TextEntity[0]), null, null); return message;
    }
    private static TdApi.Message request(long id) {
        var request = message(id);request.isOutgoing = true;request.senderId = new TdApi.MessageSenderUser(1);
        request.content = new TdApi.MessageText(new TdApi.FormattedText("/start private-resource-token", new TdApi.TextEntity[0]), null, null);return request;
    }
    private static TdApi.MessageDocument document(int fileId, String uid, long size) {
        var file = new TdApi.File(); file.id = fileId; file.size = size; file.remote = new TdApi.RemoteFile("", uid, false, false, 0);
        return new TdApi.MessageDocument(new TdApi.Document("test.bin", "application/octet-stream", null, null, file), new TdApi.FormattedText("", new TdApi.TextEntity[0]));
    }
    private static TdApi.InlineKeyboardButton callback(String text, byte[] bytes) { return new TdApi.InlineKeyboardButton(text, 0, null, new TdApi.InlineKeyboardButtonTypeCallback(bytes)); }
    private static TdApi.ReplyMarkupInlineKeyboard keyboard(TdApi.InlineKeyboardButton... buttons) { return new TdApi.ReplyMarkupInlineKeyboard(new TdApi.InlineKeyboardButton[][]{buttons}, false); }
    private static final class Fixture implements BotReader.Gateway {
        final List<TdApi.Function<?>> calls = new ArrayList<>(); final TdApi.Chat chat = new TdApi.Chat(); final TdApi.User user = new TdApi.User();
        final TdApi.Message message = message(100); TdApi.Message[] history = {message}; String account = "1"; long session = 1;
        boolean callbackFailure, mutateAccountOnGet, changeSessionOnHistory;
        final Target target = new Target("71", "72", "样本机器人"); final BotReader reader = new BotReader(this, () -> account, () -> session);
        Fixture() { chat.id = 71; chat.title = target.title(); chat.type = new TdApi.ChatTypePrivate(72); user.id = 72; user.type = new TdApi.UserTypeBot(); }
        long callbacks() { return calls.stream().filter(query -> query instanceof TdApi.GetCallbackQueryAnswer).count(); }
        @Override @SuppressWarnings("unchecked") public <R extends TdApi.Object> CompletableFuture<R> read(long session, TdApi.Function<R> request) {
            calls.add(request); TdApi.Object response;
            if (request instanceof TdApi.GetChat || request instanceof TdApi.SearchPublicChat) response = chat;
            else if (request instanceof TdApi.GetUser) { response = user; if (mutateAccountOnGet) account = "2"; }
            else if (request instanceof TdApi.GetChatHistory) {response = new TdApi.Messages(-1, history);if (changeSessionOnHistory) this.session++;}
            else if (request instanceof TdApi.GetMessage) response = message;
            else if (request instanceof TdApi.GetCallbackQueryAnswer) {
                if (callbackFailure) return CompletableFuture.failedFuture(new IllegalStateException("payload-secret"));
                response = new TdApi.CallbackQueryAnswer();
            } else throw new AssertionError("Unexpected native request " + request.getClass().getSimpleName());
            return CompletableFuture.completedFuture((R) response);
        }
    }
}
