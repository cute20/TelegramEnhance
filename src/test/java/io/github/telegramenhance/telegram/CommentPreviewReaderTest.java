package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.library.ResourceModels.MessageRef;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.telegram.PostPreviewReader.Preview;
import it.tdlight.jni.TdApi;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommentPreviewReaderTest {
    private static final String ACCOUNT = "1";
    private static final long CHANNEL = -1001;
    private static final long DISCUSSION = -2001;
    private static final long ROOT = 500;
    private static final long SECOND_ROOT = 501;
    private static final long COMMENT = 900;
    private static final String PRIVATE_TEXT = "synthetic-private-caption";
    private static final String PRIVATE_ERROR = "synthetic-native-private-token";
    private static final String PREFIX = "data:image/jpeg;base64,";
    private static final byte[] MINI = jpeg(4, 3);
    private static final byte[] CLEAR = jpeg(64, 32);

    @ParameterizedTest
    @ValueSource(strings = {"PHOTO", "VIDEO"})
    void indexedCommentPhotoAndVideoUseOnlyOneExactDiscussionMessage(String type) {
        var scenario = new Scenario(type);

        var preview = scenario.read();

        assertClear(preview, 64, 32);
        assertExactMessageQuery(scenario);
        assertEquals(List.of(11), scenario.fileIds());
        assertIndexUnchanged(scenario);
        assertFalse(preview.toString().contains(PREFIX));
        assertFalse(preview.toString().contains(Base64.getEncoder().encodeToString(CLEAR)));
        assertFalse(scenario.post.toString().contains(PRIVATE_TEXT));
        assertFalse(scenario.media.toString().contains(PRIVATE_TEXT));
    }

    @Test
    void videoCommentCanPreferItsLargerSafeCoverDerivative() {
        var scenario = new Scenario("VIDEO");
        var video = video();
        video.cover = new TdApi.Photo(false, mini(), new TdApi.PhotoSize[]{
            size("x", 1280, 640, file(1001)), size("m", 80, 40, file(12))});
        scenario.messages = query -> CompletableFuture.completedFuture(message(video));
        scenario.fileRead = ignored -> jpeg(80, 40);

        assertClear(scenario.read(), 80, 40);
        assertEquals(List.of(12), scenario.fileIds());
        assertExactMessageQuery(scenario);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PHOTO", "VIDEO"})
    void unavailableThumbnailFileFallsBackToTheSameCommentMini(String type) {
        var scenario = new Scenario(type);
        scenario.fileRead = ignored -> null;

        assertMini(scenario.read());
        assertExactMessageQuery(scenario);
        assertEquals(List.of(11), scenario.fileIds());
        assertIndexUnchanged(scenario);
    }

    @ParameterizedTest
    @ValueSource(strings = {"other-chat-same-id", "other-post-discussion", "primary-root", "second-root", "main-member",
        "MAIN", "AUDIO", "DOCUMENT", "OTHER", "no-discussion", "missing-ref"})
    void outOfScopeRowsCannotIssueAnyNativeOrFileRequest(String invalid) {
        var scenario = new Scenario("PHOTO");
        switch (invalid) {
            case "other-chat-same-id" -> scenario.media = media(ref(-2002, COMMENT), "COMMENT", "PHOTO", null);
            case "other-post-discussion" -> scenario.post = post("-2002", Long.toString(ROOT), roots(), mainRefs());
            case "primary-root" -> scenario.media = media(ref(DISCUSSION, ROOT), "COMMENT", "PHOTO", null);
            case "second-root" -> scenario.media = media(ref(DISCUSSION, SECOND_ROOT), "COMMENT", "PHOTO", null);
            case "main-member" -> scenario.post = post(Long.toString(DISCUSSION), Long.toString(ROOT), roots(),
                List.of(ref(CHANNEL, 100), ref(DISCUSSION, COMMENT)));
            case "MAIN", "AUDIO", "DOCUMENT", "OTHER" -> scenario.media = media(ref(DISCUSSION, COMMENT),
                invalid.equals("MAIN") ? "MAIN" : "COMMENT", invalid.equals("MAIN") ? "PHOTO" : invalid, null);
            case "no-discussion" -> scenario.post = post(null, null, List.of(), mainRefs());
            case "missing-ref" -> scenario.media = media(null, "COMMENT", "PHOTO", "synthetic-missing-ref");
            default -> throw new AssertionError(invalid);
        }

        assertEquals(Preview.unavailable(), scenario.read());
        assertTrue(scenario.queries.isEmpty());
        verifyNoInteractions(scenario.files);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "message", "channel-post", "other-thread", "foreign-root-thread", "changed-type", "wrong-key"})
    void currentMessageAndMappedMediaMustStillMatchTheSavedComment(String invalid) {
        var scenario = new Scenario("PHOTO");
        var message = message(photo());
        switch (invalid) {
            case "chat" -> message.chatId = -2002;
            case "message" -> message.id = COMMENT + 1;
            case "channel-post" -> message.isChannelPost = true;
            case "other-thread" -> message.topicId = new TdApi.MessageTopicThread(700);
            case "foreign-root-thread" -> message.topicId = new TdApi.MessageTopicThread(502);
            case "changed-type" -> message.content = video();
            case "wrong-key" -> scenario.media = media(ref(DISCUSSION, COMMENT), "COMMENT", "PHOTO", "2:-2001:900:0");
            default -> throw new AssertionError(invalid);
        }
        scenario.messages = query -> CompletableFuture.completedFuture(message);

        assertEquals(Preview.unavailable(), scenario.read());
        assertExactMessageQuery(scenario);
        verifyNoInteractions(scenario.files);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, ROOT, SECOND_ROOT})
    void bothKnownDiscussionRootsAndMissingTopicAllowNestedRepliesWithTheirOwnAlbum(long threadId) {
        var scenario = new Scenario("PHOTO");
        var message = message(photo());
        message.topicId = threadId == 0 ? null : new TdApi.MessageTopicThread(threadId);
        var nestedReply = new TdApi.MessageReplyToMessage();
        nestedReply.chatId = DISCUSSION; nestedReply.messageId = 800;
        message.replyTo = nestedReply;
        message.mediaAlbumId = 999; // The selected main post belongs to album 42, not this comment album.
        scenario.messages = query -> CompletableFuture.completedFuture(message);

        assertClear(scenario.read(), 64, 32);
        assertExactMessageQuery(scenario);
        assertEquals(List.of(11), scenario.fileIds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"photo-spoiler", "photo-secret", "video-spoiler", "video-secret", "self-destruct-type",
        "self-destruct-countdown", "ephemeral-content", "missing-content", "missing-message"})
    void hiddenTemporaryOrMissingCommentsNeverExposeAnImageOrReadAFile(String hidden) {
        var scenario = new Scenario(hidden.startsWith("video") ? "VIDEO" : "PHOTO");
        var message = message(hidden.startsWith("video") ? video() : photo());
        if (message.content instanceof TdApi.MessagePhoto photo) {
            photo.hasSpoiler = hidden.equals("photo-spoiler"); photo.isSecret = hidden.equals("photo-secret");
        }
        if (message.content instanceof TdApi.MessageVideo video) {
            video.hasSpoiler = hidden.equals("video-spoiler"); video.isSecret = hidden.equals("video-secret");
        }
        switch (hidden) {
            case "self-destruct-type" -> message.selfDestructType = new TdApi.MessageSelfDestructTypeImmediately();
            case "self-destruct-countdown" -> message.selfDestructIn = 10;
            case "ephemeral-content" -> message.ephemeralContent = new TdApi.EphemeralMessageContent();
            case "missing-content" -> message.content = null;
            default -> { }
        }
        scenario.messages = query -> CompletableFuture.completedFuture(hidden.equals("missing-message") ? null : message);

        assertEquals(Preview.unavailable(), scenario.read());
        assertExactMessageQuery(scenario);
        verifyNoInteractions(scenario.files);
        assertIndexUnchanged(scenario);
    }

    @Test
    void anAccountMismatchBeforeReadingPreventsAllNativeAccess() {
        var scenario = new Scenario("PHOTO");
        scenario.account.set("2");

        assertThrows(IllegalArgumentException.class, scenario::read);
        assertTrue(scenario.queries.isEmpty());
        verifyNoInteractions(scenario.files);
    }

    @Test
    void anAccountChangeDuringMetadataReadPreventsFileAccess() {
        var scenario = new Scenario("PHOTO");
        scenario.messages = query -> {
            scenario.account.set("2");
            return CompletableFuture.completedFuture(message(photo()));
        };

        assertThrows(IllegalArgumentException.class, scenario::read);
        assertExactMessageQuery(scenario);
        verifyNoInteractions(scenario.files);
        assertIndexUnchanged(scenario);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anAccountChangeDuringThumbnailReadRejectsBothClearAndMiniResults(boolean clear) {
        var scenario = new Scenario("PHOTO");
        scenario.fileRead = ignored -> {
            scenario.account.set("2");
            return clear ? CLEAR : null;
        };

        assertThrows(IllegalArgumentException.class, scenario::read);
        assertExactMessageQuery(scenario);
        assertEquals(List.of(11), scenario.fileIds());
        assertIndexUnchanged(scenario);
    }

    @Test
    void theAccountIsCheckedAgainAfterRenderingAClearPreview() {
        var scenario = new Scenario("PHOTO");
        var accountChecks = new AtomicInteger();
        var reader = new PostPreviewReader(scenario, () -> accountChecks.incrementAndGet() < 4 ? ACCOUNT : "2", scenario.files);

        assertThrows(IllegalArgumentException.class, () -> reader.readMedia(scenario.post, scenario.media));
        assertTrue(accountChecks.get() >= 4);
        assertEquals(List.of(11), scenario.fileIds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed-future", "gateway-throw", "timeout"})
    void nativeFailuresAreSanitizedPreserveTheIndexAndAllowAnotherAttempt(String failure) {
        var scenario = new Scenario("PHOTO");
        scenario.messages = query -> {
            if (failure.equals("gateway-throw")) throw new IllegalStateException(PRIVATE_ERROR);
            if (failure.equals("failed-future")) return CompletableFuture.failedFuture(new IllegalStateException(PRIVATE_ERROR));
            return new CompletableFuture<>() {
                @Override public TdApi.Message get(long timeout, TimeUnit unit) throws TimeoutException {
                    throw new TimeoutException(PRIVATE_ERROR);
                }
            };
        };

        var error = assertThrows(IllegalArgumentException.class, scenario::read);
        assertNotNull(error.getMessage());
        assertFalse(error.toString().contains(PRIVATE_ERROR));
        assertNull(error.getCause());
        verifyNoInteractions(scenario.files);
        assertIndexUnchanged(scenario);

        scenario.messages = query -> CompletableFuture.completedFuture(message(photo()));
        scenario.fileRead = ignored -> null;
        assertMini(scenario.read());
        assertEquals(2, scenario.queries.size());
        assertIndexUnchanged(scenario);
    }

    private static void assertExactMessageQuery(Scenario scenario) {
        assertEquals(1, scenario.queries.size());
        assertEquals(DISCUSSION, scenario.queries.getFirst().chatId);
        assertEquals(COMMENT, scenario.queries.getFirst().messageId);
    }

    private static void assertIndexUnchanged(Scenario scenario) {
        assertEquals("PARTIAL", scenario.post.coverage());
        assertEquals("COMPLETE", scenario.post.mainCoverage());
        assertEquals(List.of("Synthetic existing warning"), scenario.post.warnings());
        assertEquals(PRIVATE_TEXT, scenario.media.caption());
    }

    private static void assertClear(Preview preview, int width, int height) {
        assertEquals("AVAILABLE", preview.status()); assertEquals("THUMBNAIL", preview.quality());
        assertEquals(width, preview.width()); assertEquals(height, preview.height());
        byte[] bytes = bytes(preview); assertTrue(bytes.length <= 128 * 1024);
        assertEquals(0xff, bytes[0] & 255); assertEquals(0xd8, bytes[1] & 255);
        try {
            var decoded = ImageIO.read(new ByteArrayInputStream(bytes)); assertNotNull(decoded);
            try { assertEquals(width, decoded.getWidth()); assertEquals(height, decoded.getHeight()); }
            finally { decoded.flush(); }
        } catch (java.io.IOException failure) { throw new AssertionError("Synthetic preview is not a valid JPEG", failure); }
    }
    private static void assertMini(Preview preview) {
        assertEquals("AVAILABLE", preview.status()); assertEquals("MINI", preview.quality());
        assertEquals(4, preview.width()); assertEquals(3, preview.height()); assertArrayEquals(MINI, bytes(preview));
    }
    private static byte[] bytes(Preview preview) {
        assertNotNull(preview.dataUrl()); assertTrue(preview.dataUrl().startsWith(PREFIX));
        return Base64.getDecoder().decode(preview.dataUrl().substring(PREFIX.length()));
    }
    private static MessageRef ref(long chat, long id) { return new MessageRef(Long.toString(chat), Long.toString(id), ""); }
    private static List<MessageRef> roots() { return List.of(ref(DISCUSSION, SECOND_ROOT), ref(-2002, 502)); }
    private static List<MessageRef> mainRefs() { return List.of(ref(CHANNEL, 100)); }
    private static Post post(String discussion, String root, List<MessageRef> roots, List<MessageRef> mainRefs) {
        return new Post("synthetic-post", ACCOUNT, Long.toString(CHANNEL), "Synthetic channel", "100", "Synthetic title",
            PRIVATE_TEXT, "42", mainRefs, List.of(), discussion, root, roots, true,
            "PARTIAL", List.of("Synthetic existing warning"), null, "COMPLETE");
    }
    private static Media media(MessageRef ref, String source, String type, String key) {
        return new Media(key == null ? ACCOUNT + ":" + ref.chatId() + ":" + ref.messageId() + ":0" : key,
            ref, source, type, "synthetic", 4096, PRIVATE_TEXT);
    }
    private static TdApi.Message message(TdApi.MessageContent content) {
        var message = new TdApi.Message(); message.chatId = DISCUSSION; message.id = COMMENT;
        message.isChannelPost = false; message.topicId = new TdApi.MessageTopicThread(ROOT);
        message.mediaAlbumId = 99; message.content = content; return message;
    }
    private static TdApi.MessagePhoto photo() {
        return new TdApi.MessagePhoto(new TdApi.Photo(false, mini(), new TdApi.PhotoSize[]{
            size("x", 1280, 640, file(1000)), size("m", 64, 32, file(11))}), null, caption(), false, false, false);
    }
    private static TdApi.MessageVideo video() {
        var video = new TdApi.Video(); video.video = file(1000); video.fileName = "synthetic.mp4";
        video.minithumbnail = mini(); video.thumbnail = new TdApi.Thumbnail(new TdApi.ThumbnailFormatJpeg(), 64, 32, file(11));
        var content = new TdApi.MessageVideo(); content.video = video; content.caption = caption(); return content;
    }
    private static TdApi.PhotoSize size(String type, int width, int height, TdApi.File file) {
        return new TdApi.PhotoSize(type, file, width, height, new int[0]);
    }
    private static TdApi.File file(int id) {
        var file = new TdApi.File(); file.id = id; file.size = 4096; file.expectedSize = 4096;
        file.remote = new TdApi.RemoteFile("synthetic-remote-" + id, "synthetic-unique-" + id, false, false, 0); return file;
    }
    private static TdApi.Minithumbnail mini() { return new TdApi.Minithumbnail(4, 3, MINI.clone()); }
    private static TdApi.FormattedText caption() { return new TdApi.FormattedText(PRIVATE_TEXT, new TdApi.TextEntity[0]); }
    private static byte[] jpeg(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) image.setRGB(x, y, (x * 37 + y * 101) & 0xffffff);
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "jpeg", output)) throw new AssertionError("Synthetic JPEG encoder is missing");
            return output.toByteArray();
        } catch (java.io.IOException failure) { throw new AssertionError("Cannot generate synthetic JPEG", failure); }
        finally { image.flush(); }
    }

    private static final class Scenario implements ResourceReader.Gateway {
        final AtomicReference<String> account = new AtomicReference<>(ACCOUNT);
        final ThumbnailFileReader files = mock(ThumbnailFileReader.class);
        final PostPreviewReader reader = new PostPreviewReader(this, account::get, files);
        final List<TdApi.GetMessage> queries = new ArrayList<>();
        final List<TdApi.File> fileRequests = new ArrayList<>();
        Post post = post(Long.toString(DISCUSSION), Long.toString(ROOT), roots(), mainRefs());
        Media media;
        Function<TdApi.GetMessage, CompletableFuture<TdApi.Message>> messages;
        Function<TdApi.File, byte[]> fileRead = ignored -> CLEAR;
        Scenario(String type) {
            media = media(ref(DISCUSSION, COMMENT), "COMMENT", type, null);
            messages = query -> CompletableFuture.completedFuture(message(type.equals("PHOTO") ? photo() : video()));
            when(files.read(any(TdApi.File.class), anyString(), anyLong())).thenAnswer(invocation -> {
                TdApi.File candidate = invocation.getArgument(0);
                assertEquals(ACCOUNT, invocation.getArgument(1));
                long remaining = (long) invocation.getArgument(2) - System.nanoTime();
                assertTrue(remaining > 0 && remaining <= TimeUnit.SECONDS.toNanos(12));
                fileRequests.add(candidate); return fileRead.apply(candidate);
            });
        }
        Preview read() { return reader.readMedia(post, media); }
        @Override @SuppressWarnings("unchecked")
        public <R extends TdApi.Object> CompletableFuture<R> read(TdApi.Function<R> query) {
            if (!(query instanceof TdApi.GetMessage request)) throw new AssertionError("Comment metadata must only use GetMessage");
            queries.add(request); return (CompletableFuture<R>) messages.apply(request);
        }
        List<Integer> fileIds() { return fileRequests.stream().map(file -> file.id).toList(); }
    }
}
