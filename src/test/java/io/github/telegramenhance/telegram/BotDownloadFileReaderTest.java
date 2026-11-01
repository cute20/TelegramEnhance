package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.library.ResourceModels.*;
import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class BotDownloadFileReaderTest {
    @Test void botMediaFreezesAndRechecksTheOriginalPrivateBotMessageWithoutStartingTransfer() {
        var f = new Fixture(); var file = f.reader.freeze(f.post, List.of(f.media), 1).getFirst();
        assertEquals("BOT", file.media().source()); assertEquals("native-file-name.bin", file.fileName()); assertEquals("stable", file.uniqueId()); assertEquals(11, file.size());
        assertNull(file.media().message().link()); assertEquals(7, f.reader.resolve(f.post, file, 1).fileId());
        assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.GetUser || query instanceof TdApi.GetMessage || query instanceof TdApi.GetMessageProperties || query instanceof TdApi.GetMessageLink));
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
        for (Class<?> type : List.of(TdApi.GetChat.class, TdApi.GetUser.class, TdApi.GetMessage.class))
            assertEquals(3, f.calls.stream().filter(type::isInstance).count());
        assertTrue(f.calls.stream().noneMatch(query -> query instanceof TdApi.GetMessageProperties || query instanceof TdApi.GetMessageLink));
        assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.GetUser || query instanceof TdApi.GetMessage));
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
        assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.GetUser || query instanceof TdApi.GetMessage));
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
        assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.GetUser || query instanceof TdApi.GetMessage || query instanceof TdApi.GetMessageProperties));
        assertTrue(f.calls.stream().noneMatch(query -> query instanceof TdApi.DownloadFile || query instanceof TdApi.ReadFilePart || query instanceof TdApi.GetMessageLink));
    }
    @Test void initialBotFreezeRejectsANameChangeUnderTheSameObservedMessageKeyWithoutStartingTransfer() {
        var f = new Fixture(); ((TdApi.MessageDocument) f.message.content).document.fileName = "replaced-name.bin";
        var failure = assertThrows(IllegalArgumentException.class, () -> f.reader.freeze(f.post, List.of(f.media), 1));
        assertTrue(failure.getMessage().contains("源文件已变化"));
        assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.GetUser || query instanceof TdApi.GetMessage || query instanceof TdApi.GetMessageProperties));
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
            assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.GetUser || query instanceof TdApi.GetMessage));
        }
    }
    @Test void aUserSpecificContentOverlayCannotFallBackToDownloadingTheUnderlyingMessageFile() {
        var f = new Fixture(); f.message.ephemeralContent = new TdApi.EphemeralMessageContent();
        f.message.ephemeralContent.canBeSaved = true; f.message.ephemeralContent.content = f.message.content;
        var failure = assertThrows(IllegalArgumentException.class, () -> f.reader.freeze(f.post, List.of(f.media), 1));
        assertTrue(failure.getMessage().contains(f.media.fileName())); assertTrue(failure.getMessage().contains("替代内容"));
        assertTrue(f.calls.stream().allMatch(query -> query instanceof TdApi.GetChat || query instanceof TdApi.GetUser || query instanceof TdApi.GetMessage));
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
            assertTrue(failure.getMessage().contains(mode.equals("channel") ? "原机器人私聊" : "消息身份不符"));
            assertFalse(failure.getMessage().contains("不可保存"));
        }
    }
    @Test void botResumeCannotSubstituteAnotherFileVersionAndSessionChangesStopBeforeReads() {
        var f = new Fixture(); var file = f.reader.freeze(f.post, List.of(f.media), 1).getFirst(); f.file.remote.uniqueId = "new-version";
        assertThrows(IllegalArgumentException.class, () -> f.reader.resolve(f.post, file, 1));
        f.calls.clear(); f.session = 2;
        assertThrows(IllegalArgumentException.class, () -> f.reader.resolve(f.post, file, 1)); assertTrue(f.calls.isEmpty());
    }
    private static final class Fixture implements DownloadFileReader.Gateway {
        final List<TdApi.Function<?>> calls = new ArrayList<>();
        final Map<Long, TdApi.Message> messages = new HashMap<>();
        final TdApi.Chat chat = new TdApi.Chat(); final TdApi.User user = new TdApi.User(); final TdApi.Message message = new TdApi.Message();
        final TdApi.MessageProperties properties = new TdApi.MessageProperties(); final TdApi.File file = new TdApi.File();
        long session = 1;
        final DownloadFileReader reader = new DownloadFileReader(this, () -> "1", () -> session);
        final Media media = new Media("1:71:101:0", new MessageRef("71", "101", null), "BOT", "DOCUMENT", "native-file-name.bin", 11, "");
        final Post post = post("bot:72:71:100", List.of(media.message()));
        Fixture() {
            chat.id = 71; chat.type = new TdApi.ChatTypePrivate(72); user.id = 72; user.type = new TdApi.UserTypeBot();
            file.id = 7; file.size = 11; file.remote = new TdApi.RemoteFile("", "stable", false, false, 0);
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
        Post post(String key, List<MessageRef> refs) { return new Post(key, "1", "71", "样本机器人", "100", "样本", "", "0", refs, List.of(media), null, null, List.of(), false, "PARTIAL", List.of()); }
        @Override @SuppressWarnings("unchecked") public <R extends TdApi.Object> CompletableFuture<R> read(long session, TdApi.Function<R> request) {
            calls.add(request); TdApi.Object result;
            if (request instanceof TdApi.GetChat) result = chat;
            else if (request instanceof TdApi.GetUser) result = user;
            else if (request instanceof TdApi.GetMessage get) result = messages.get(get.messageId);
            else if (request instanceof TdApi.GetMessageProperties) result = properties;
            else if (request instanceof TdApi.GetMessageLink) result = new TdApi.MessageLink();
            else throw new AssertionError("Unexpected bot file command");
            return CompletableFuture.completedFuture((R) result);
        }
    }
}
