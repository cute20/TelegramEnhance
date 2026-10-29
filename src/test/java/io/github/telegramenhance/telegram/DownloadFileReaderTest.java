package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.library.ResourceModels.*;
import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class DownloadFileReaderTest {
    @Test void aMissingPreviewLinkIsResolvedWithoutDownloadingTheOriginal(){
        var f=new Fixture();var frozen=f.reader.freeze(f.post,List.of(f.media),1).getFirst();
        assertEquals("https://t.me/synthetic/100",frozen.media().message().link());assertEquals(f.media.message().messageId(),frozen.media().message().messageId());
        assertTrue(f.calls.stream().anyMatch(q->q instanceof TdApi.GetMessageLink));assertTrue(f.calls.stream().noneMatch(q->q instanceof TdApi.DownloadFile||q instanceof TdApi.ReadFilePart));
    }
    @Test void sourceLinksCannotBecomeExecutableOrInventedWhenNativeCannotProvideAnHttpsLink(){
        var f=new Fixture();f.nativeLink="javascript:alert(1)";var frozen=f.reader.freeze(f.post,List.of(f.media),1).getFirst();
        assertTrue(frozen.media().message().link()==null||frozen.media().message().link().isBlank());
    }
    @Test void freezingSelectsThePrimaryFileAndDoesNotStartAnyDownload() {
        var f = new Fixture(); var frozen = f.reader.freeze(f.post, List.of(f.media), 1).getFirst();
        assertEquals("unique", frozen.uniqueId()); assertEquals(11, frozen.size());
        assertTrue(f.calls.stream().noneMatch(q -> q instanceof TdApi.DownloadFile || q instanceof TdApi.ReadFilePart));
    }
    @Test void protectedChatsAndMessagesWithoutSavePermissionFailBeforeAnyFileOperation() {
        for (boolean protectedChat : List.of(true, false)) {
            var f = new Fixture(); if (protectedChat) f.chat.hasProtectedContent = true; else f.properties.canBeSaved = false;
            assertThrows(IllegalArgumentException.class, () -> f.reader.freeze(f.post, List.of(f.media), 1));
            assertTrue(f.calls.stream().noneMatch(q -> q instanceof TdApi.DownloadFile || q instanceof TdApi.ReadFilePart));
        }
    }
    @Test void resumingCannotSilentlyReplaceTheFrozenRemoteVersion() {
        var f = new Fixture(); var frozen = f.reader.freeze(f.post, List.of(f.media), 1).getFirst(); f.file.remote.uniqueId = "changed";
        assertThrows(IllegalArgumentException.class, () -> f.reader.resolve(f.post, frozen, 1));
    }
    @Test void aPhotoSelectsTheLargestOriginalInsteadOfItsSmallThumbnail() {
        var f = new Fixture(); var small = new TdApi.File(); small.id = 8; small.size = 3; small.remote = new TdApi.RemoteFile("", "small", false, false, 0);
        f.message.content = new TdApi.MessagePhoto(new TdApi.Photo(false, null, new TdApi.PhotoSize[]{new TdApi.PhotoSize("s", small, 20, 20, new int[0]), new TdApi.PhotoSize("x", f.file, 400, 400, new int[0])}), null, new TdApi.FormattedText("", new TdApi.TextEntity[0]), false, false, false);
        var selected = new Media(f.media.key(), f.media.message(), "MAIN", "PHOTO", "", 11, "");
        assertEquals("unique", f.reader.freeze(f.post, List.of(selected), 1).getFirst().uniqueId());
    }
    @Test void onlyACompletedMatchingNativeFileCanProvideAnExactBoundedOutputChunk() {
        var f = new Fixture(); assertThrows(IllegalArgumentException.class, () -> f.reader.chunk("1", 1, 7, "unique", 11, 0, 11));
        f.file.local.isDownloadingCompleted = true; f.file.local.downloadedSize = 11;
        assertArrayEquals(new byte[11], f.reader.chunk("1", 1, 7, "unique", 11, 0, 11));
        assertThrows(IllegalArgumentException.class, () -> f.reader.chunk("1", 1, 7, "unique", 11, 0, 12));
        assertThrows(IllegalArgumentException.class, () -> f.reader.chunk("1", 1, 7, "wrong", 11, 0, 11));
        f.partLength = 10; assertThrows(IllegalArgumentException.class, () -> f.reader.chunk("1", 1, 7, "unique", 11, 0, 11));
    }
    @Test void staleSessionAndReusedFileNumbersNeverAuthorizeCleanup() {
        var f = new Fixture(); f.session = 2;
        assertThrows(IllegalArgumentException.class, () -> f.reader.release("1", 1, 7, "unique", true)); assertTrue(f.calls.isEmpty());
        f.session = 1; f.file.remote.uniqueId = "somebody-elses-file";
        assertFalse(f.reader.release("1", 1, 7, "unique", true));
        assertTrue(f.calls.stream().noneMatch(q -> q instanceof TdApi.CancelDownloadFile || q instanceof TdApi.DeleteFile));
    }
    @Test void ownedCacheCancellationAndDeletionAreSeparateOperations() {
        var f = new Fixture(); f.file.local.isDownloadingCompleted = true; f.file.local.downloadedSize = 11;
        assertTrue(f.reader.release("1", 1, 7, "unique", false)); assertTrue(f.file.local.isDownloadingCompleted);
        assertTrue(f.reader.release("1", 1, 7, "unique", true)); assertFalse(f.file.local.isDownloadingCompleted);
    }
    @Test void anUploadedInputOutsideNativeCacheIsExplainedWithoutReadingOrDeletingItsLocalPath(){
        var f=new Fixture();f.file.local.isDownloadingCompleted=true;f.file.local.downloadedSize=11;
        var reader=new DownloadFileReader(new DownloadFileReader.Gateway(){@SuppressWarnings("unchecked")public <R extends TdApi.Object>CompletableFuture<R>read(long generation,TdApi.Function<R>request){
            if(request instanceof TdApi.ReadFilePart)return CompletableFuture.failedFuture(new it.tdlight.client.TelegramError(new TdApi.Error(400,"File is not inside the cache")));
            return f.read(generation,request);
        }},()->"1",()->1);
        var problem=assertThrows(IllegalArgumentException.class,()->reader.chunk("1",1,7,"unique",11,0,11));
        assertTrue(problem.getMessage().contains("上传输入副本"));assertTrue(problem.getMessage().contains("归档"));
        assertTrue(f.calls.stream().noneMatch(q->q instanceof TdApi.DeleteFile||q instanceof TdApi.CancelDownloadFile));
    }
    private static final class Fixture implements DownloadFileReader.Gateway {
        final TdApi.Message message = new TdApi.Message(); final TdApi.Chat chat = new TdApi.Chat();
        final TdApi.MessageProperties properties = new TdApi.MessageProperties(); final TdApi.File file = new TdApi.File();
        final List<TdApi.Function<?>> calls = new ArrayList<>(); String account = "1"; long session = 1; int partLength = 11;
        String nativeLink="https://t.me/synthetic/100"; final DownloadFileReader reader = new DownloadFileReader(this, () -> account, () -> session);
        final Media media = new Media("1:-1001:100:0", new MessageRef("-1001", "100", ""), "MAIN", "DOCUMENT", "test.bin", 11, "");
        final Post post = new Post("1:-1001:100", "1", "-1001", "channel", "100", "title", "", "0", List.of(media.message()), List.of(media), null, null, List.of(), false, "UNAVAILABLE", List.of());
        Fixture() {
            file.id = 7; file.size = 11; file.remote = new TdApi.RemoteFile("remote", "unique", false, false, 0); file.local = new TdApi.LocalFile(); file.local.canBeDownloaded = true;
            chat.id = -1001; message.chatId = -1001; message.id = 100; message.isChannelPost = true;
            message.content = new TdApi.MessageDocument(new TdApi.Document("test.bin", "application/octet-stream", null, null, file), new TdApi.FormattedText("", new TdApi.TextEntity[0])); properties.canBeSaved = true;
        }
        @Override @SuppressWarnings("unchecked") public <R extends TdApi.Object> CompletableFuture<R> read(long generation, TdApi.Function<R> request) {
            assertEquals(session, generation); calls.add(request); TdApi.Object result;
            if (request instanceof TdApi.GetChat) result = chat;
            else if (request instanceof TdApi.GetMessage) result = message;
            else if (request instanceof TdApi.GetMessageProperties) result = properties;
            else if (request instanceof TdApi.GetMessageLink) {var link=new TdApi.MessageLink();link.link=nativeLink;result=link;}
            else if (request instanceof TdApi.GetFile || request instanceof TdApi.DownloadFile) result = file;
            else if (request instanceof TdApi.ReadFilePart) result = new TdApi.Data(new byte[partLength]);
            else if (request instanceof TdApi.CancelDownloadFile) { file.local.isDownloadingActive = false; result = new TdApi.Ok(); }
            else if (request instanceof TdApi.DeleteFile) { file.local.isDownloadingCompleted = false; file.local.downloadedSize = 0; result = new TdApi.Ok(); }
            else throw new AssertionError("Unexpected native command");
            return CompletableFuture.completedFuture((R) result);
        }
    }
}
