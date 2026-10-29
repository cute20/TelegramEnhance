package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.library.ResourceModels.MessageRef;
import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MediaMapperTest {
    private static final MessageRef REF = new MessageRef("-1001", "1000", "");

    @Test
    void photoSizesProduceOneMediaUsingTheLargestDimensions() {
        var photo = new TdApi.Photo(false, null, new TdApi.PhotoSize[]{
                new TdApi.PhotoSize("s", file(9000), 100, 100, new int[0]),
                new TdApi.PhotoSize("x", file(5000), 1920, 1080, new int[0]),
                new TdApi.PhotoSize("m", file(6000), 640, 480, new int[0])});
        var message = message(new TdApi.MessagePhoto(photo, null, caption("photo caption"), false, false, false));
        var warnings = new ArrayList<String>();

        var media = MediaMapper.map("account", message, "MAIN", REF, warnings);

        assertEquals(1, media.size());
        assertEquals("PHOTO", media.getFirst().type());
        assertEquals(5000, media.getFirst().size());
        assertEquals("photo caption", media.getFirst().caption());
        assertTrue(warnings.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("documentTypes")
    void documentsUseVideoMimeOrVideoExtension(String name, String mime, String expectedType) {
        var document = new TdApi.Document(name, mime, null, null, file(42));
        var message = message(new TdApi.MessageDocument(document, caption("description")));
        var warnings = new ArrayList<String>();

        var media = MediaMapper.map("account", message, "COMMENT", REF, warnings);

        assertEquals(1, media.size());
        assertEquals(expectedType, media.getFirst().type());
        assertEquals("COMMENT", media.getFirst().source());
        assertEquals(name, media.getFirst().fileName());
        assertEquals("description", media.getFirst().caption());
        assertTrue(warnings.isEmpty());
    }

    static Stream<Arguments> documentTypes() {
        return Stream.of(
                Arguments.of("recording", "video/mp4", "VIDEO"),
                Arguments.of("recording.MKV", "application/octet-stream", "VIDEO"),
                Arguments.of("recording.MP4", null, "VIDEO"),
                Arguments.of("notes.pdf", "application/pdf", "DOCUMENT"));
    }

    @ParameterizedTest
    @MethodSource("unsupportedContents")
    void unsupportedMediaProducesAnExplicitIncompleteWarning(TdApi.MessageContent content) {
        var warnings = new ArrayList<String>();

        var media = MediaMapper.map("account", message(content), "COMMENT", REF, warnings);

        assertTrue(media.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("不完整")));
    }

    static Stream<TdApi.MessageContent> unsupportedContents() {
        return Stream.of(new TdApi.MessageUnsupported(), new TdApi.MessagePaidMedia(),
                new TdApi.MessageExpiredPhoto(), new TdApi.MessageStory(),
                new TdApi.MessageInvoice(), new TdApi.MessageSticker(), new TdApi.MessageLocation());
    }

    @ParameterizedTest
    @MethodSource("missingMediaStructures")
    void missingNestedMediaStructuresWarnInsteadOfThrowing(TdApi.MessageContent content) {
        var warnings = new ArrayList<String>();

        var media = assertDoesNotThrow(() -> MediaMapper.map("account", message(content), "COMMENT", REF, warnings));

        assertTrue(media.isEmpty());
        assertFalse(warnings.isEmpty());
    }

    static Stream<TdApi.MessageContent> missingMediaStructures() {
        return Stream.of(new TdApi.MessageVideo(), new TdApi.MessageAudio(),
                new TdApi.MessageVoiceNote(), new TdApi.MessageVideoNote(),
                new TdApi.MessageDocument(), new TdApi.MessageAnimation());
    }

    @Test
    void plainTextIsNotUnsupportedMedia() {
        var warnings = new ArrayList<String>();
        var message = message(new TdApi.MessageText(caption("ordinary text"), null, null));

        assertEquals("ordinary text", MediaMapper.text(message));
        assertTrue(MediaMapper.map("account", message, "MAIN", REF, warnings).isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void missingFileCannotBeReportedAsAnIndexedMedia() {
        var warnings = new ArrayList<String>();
        var content = new TdApi.MessageDocument(new TdApi.Document("missing.mp4", "video/mp4", null, null, null), caption(""));

        assertTrue(MediaMapper.map("account", message(content), "MAIN", REF, warnings).isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("不完整")));
    }

    @Test
    void missingPhotoSizesProduceAnIncompleteWarning() {
        var warnings = new ArrayList<String>();
        var content = new TdApi.MessagePhoto(new TdApi.Photo(false, null, new TdApi.PhotoSize[0]), null, caption(""), false, false, false);

        assertTrue(MediaMapper.map("account", message(content), "MAIN", REF, warnings).isEmpty());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void invalidPhotoSizesDoNotHideAValidSizeOrProveACompleteSnapshot() {
        var warnings = new ArrayList<String>();
        var photo = new TdApi.Photo(false, null, new TdApi.PhotoSize[]{null,
                new TdApi.PhotoSize("bad", null, 1920, 1080, new int[0]),
                new TdApi.PhotoSize("invalid", file(999), 0, 480, new int[0]),
                new TdApi.PhotoSize("valid", file(123), 640, 480, new int[0])});
        var content = new TdApi.MessagePhoto(photo, null, caption(""), false, false, false);

        var media = assertDoesNotThrow(() -> MediaMapper.map("account", message(content), "MAIN", REF, warnings));

        assertEquals(1, media.size());
        assertEquals(123, media.getFirst().size());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void malformedTextCannotProveAnEmptyMediaSnapshot() {
        var warnings = new ArrayList<String>();
        var content = new TdApi.MessageText(null, null, null);

        assertTrue(MediaMapper.map("account", message(content), "COMMENT", REF, warnings).isEmpty());
        assertFalse(warnings.isEmpty());
        assertEquals("", MediaMapper.text(message(content)));
    }

    @Test
    void missingContentIsAnIncompleteObservation() {
        var warnings = new ArrayList<String>();

        assertTrue(MediaMapper.map("account", message(null), "COMMENT", REF, warnings).isEmpty());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void negativeFileSizeKeepsTheKnownAttachmentButCannotProveCompleteness() {
        var warnings = new ArrayList<String>();
        var content = new TdApi.MessageDocument(new TdApi.Document("video.mp4", "video/mp4", null, null, file(-1)), caption(""));

        var media = MediaMapper.map("account", message(content), "COMMENT", REF, warnings);

        assertEquals(1, media.size());
        assertEquals(0, media.getFirst().size());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void animatedPhotoRetainsThePhotoAndWarnsAboutItsUnexpandedVideo() {
        var warnings = new ArrayList<String>();
        var photo = new TdApi.Photo(false, null, new TdApi.PhotoSize[]{
                new TdApi.PhotoSize("x", file(123), 640, 480, new int[0])});
        var content = new TdApi.MessagePhoto(photo, new TdApi.Video(), caption(""), false, false, false);

        assertEquals(1, MediaMapper.map("account", message(content), "MAIN", REF, warnings).size());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("不完整")));
    }

    @Test
    void mediaIdentityIncludesAccountAndChatRatherThanTheClientFileId() {
        var message = message(new TdApi.MessageDocument(new TdApi.Document("video.mp4", "video/mp4", null, null, file(42)), caption("")));
        var otherChat = new MessageRef("-1002", REF.messageId(), "");
        var first = MediaMapper.map("account-a", message, "MAIN", REF, new ArrayList<>()).getFirst();
        var second = MediaMapper.map("account-a", message, "MAIN", otherChat, new ArrayList<>()).getFirst();
        var third = MediaMapper.map("account-b", message, "MAIN", REF, new ArrayList<>()).getFirst();

        assertEquals(3, Stream.of(first.key(), second.key(), third.key()).distinct().count());
    }

    private static TdApi.Message message(TdApi.MessageContent content) {
        var message = new TdApi.Message();
        message.chatId = Long.parseLong(REF.chatId());
        message.id = Long.parseLong(REF.messageId());
        message.content = content;
        return message;
    }

    private static TdApi.File file(long size) {
        var file = new TdApi.File();
        file.id = 7;
        file.size = size;
        return file;
    }

    private static TdApi.FormattedText caption(String text) {
        return new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
    }
}
