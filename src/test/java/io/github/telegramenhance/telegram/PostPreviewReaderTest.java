package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.library.ResourceModels.MessageRef;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.telegram.PostPreviewReader.Preview;
import it.tdlight.jni.TdApi;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PostPreviewReaderTest {
    private static final String ACCOUNT = "1";
    private static final long CHANNEL = -1001;
    private static final long MESSAGE = 100;
    private static final String PRIVATE_ERROR = "synthetic-native-private-message-and-token";
    private static final byte[] JPEG = image("jpeg", 4, 3);

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedMedia")
    void supportedMainMediaUsesOnlyItsEmbeddedJpeg(String label, String type, TdApi.MessageContent content) {
        var scenario = new Scenario();
        var post = post("0", List.of(ref(MESSAGE)), List.of(media(ref(MESSAGE), "MAIN", type)));
        scenario.response = query -> CompletableFuture.completedFuture(message(query.messageId, 0, content));

        var preview = scenario.reader.read(post);

        assertImage(preview, JPEG, 4, 3);
        assertEquals(List.of(MESSAGE), scenario.requestedIds());
        assertEquals(CHANNEL, scenario.queries.getFirst().chatId);
        assertEquals("PARTIAL", post.coverage());
        assertEquals("PARTIAL", post.mainCoverage());
        assertEquals(List.of("Synthetic indexed warning"), post.warnings());
        assertFalse(preview.toString().contains("data:image"));
        assertFalse(preview.toString().contains(Base64.getEncoder().encodeToString(JPEG)));
    }

    static Stream<Arguments> supportedMedia() {
        return Stream.of(
            Arguments.of("photo", "PHOTO", photo(mini())),
            Arguments.of("video", "VIDEO", video(mini(), null)),
            Arguments.of("document", "DOCUMENT", document(mini())),
            Arguments.of("animation", "OTHER", animation(mini())),
            Arguments.of("audio album cover", "AUDIO", audio(mini())));
    }

    @Test
    void videoPrefersItsCoverAndFallsBackWhenThatCoverIsInvalid() {
        byte[] cover = image("jpeg", 2, 5);
        var scenario = new Scenario();
        var post = single("VIDEO");
        scenario.response = query -> CompletableFuture.completedFuture(message(MESSAGE, 0,
            video(mini(), new TdApi.Minithumbnail(2, 5, cover))));
        assertImage(scenario.reader.read(post), cover, 2, 5);

        scenario.response = query -> CompletableFuture.completedFuture(message(MESSAGE, 0,
            video(mini(), new TdApi.Minithumbnail(2, 5, new byte[]{1, 2, 3}))));
        assertImage(scenario.reader.read(post), JPEG, 4, 3);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidThumbnails")
    void invalidOrMissingThumbnailCannotBecomeAnAvailablePreview(String label, TdApi.Minithumbnail thumbnail) {
        var scenario = new Scenario();
        scenario.response = query -> CompletableFuture.completedFuture(message(MESSAGE, 0, photo(thumbnail)));

        assertUnavailable(scenario.reader.read(single("PHOTO")));
        assertEquals(1, scenario.queries.size());
    }

    static Stream<Arguments> invalidThumbnails() {
        byte[] wrongStart = JPEG.clone(); wrongStart[0] = 0;
        byte[] wrongEnd = JPEG.clone(); wrongEnd[wrongEnd.length - 1] = 0;
        byte[] oversized = Arrays.copyOf(JPEG, 32 * 1024 + 1);
        oversized[oversized.length - 2] = (byte) 0xff; oversized[oversized.length - 1] = (byte) 0xd9;
        return Stream.of(
            Arguments.of("no minithumbnail", null),
            Arguments.of("no data", new TdApi.Minithumbnail(4, 3, null)),
            Arguments.of("empty data", new TdApi.Minithumbnail(4, 3, new byte[0])),
            Arguments.of("invalid JPEG start", new TdApi.Minithumbnail(4, 3, wrongStart)),
            Arguments.of("invalid JPEG end", new TdApi.Minithumbnail(4, 3, wrongEnd)),
            Arguments.of("markers without decodable image", new TdApi.Minithumbnail(4, 3,
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9})),
            Arguments.of("PNG is not JPEG", new TdApi.Minithumbnail(4, 3, image("png", 4, 3))),
            Arguments.of("over 32 KiB", new TdApi.Minithumbnail(4, 3, oversized)),
            Arguments.of("width disagrees with image", new TdApi.Minithumbnail(5, 3, JPEG)),
            Arguments.of("height disagrees with image", new TdApi.Minithumbnail(4, 4, JPEG)),
            Arguments.of("zero width", new TdApi.Minithumbnail(0, 3, JPEG)),
            Arguments.of("negative height", new TdApi.Minithumbnail(4, -1, JPEG)),
            Arguments.of("edge exceeds 256", new TdApi.Minithumbnail(257, 2, image("jpeg", 257, 2))));
    }

    @Test
    void maximumAllowedEdgeIsAccepted() {
        byte[] jpeg = image("jpeg", 256, 1);
        var scenario = new Scenario();
        scenario.response = query -> CompletableFuture.completedFuture(message(MESSAGE, 0,
            photo(new TdApi.Minithumbnail(256, 1, jpeg))));
        assertImage(scenario.reader.read(single("PHOTO")), jpeg, 256, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null-message", "missing-content", "unsupported-content", "missing-photo", "changed-media-type"})
    void missingOrUnsupportedCurrentContentFallsBackWithoutUsingTheOldIndexedType(String state) {
        var scenario = new Scenario();
        var response = switch (state) {
            case "null-message" -> null;
            case "missing-content" -> message(MESSAGE, 0, null);
            case "unsupported-content" -> message(MESSAGE, 0, new TdApi.MessageSticker());
            case "missing-photo" -> message(MESSAGE, 0, new TdApi.MessagePhoto());
            case "changed-media-type" -> message(MESSAGE, 0, video(mini(), null));
            default -> throw new AssertionError(state);
        };
        scenario.response = query -> CompletableFuture.completedFuture(response);
        assertUnavailable(scenario.reader.read(single("PHOTO")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"photo-spoiler", "photo-secret", "video-spoiler", "video-secret", "animation-spoiler",
        "animation-secret", "self-destruct-type", "self-destruct-countdown", "ephemeral-content"})
    void hiddenOrTemporaryMediaIsNotExposedAsAPreview(String hidden) {
        TdApi.MessageContent content;
        String indexedType;
        if (hidden.startsWith("video")) {
            var video = video(mini(), mini());
            video.hasSpoiler = hidden.endsWith("spoiler"); video.isSecret = hidden.endsWith("secret");
            content = video; indexedType = "VIDEO";
        } else if (hidden.startsWith("animation")) {
            var animation = animation(mini());
            animation.hasSpoiler = hidden.endsWith("spoiler"); animation.isSecret = hidden.endsWith("secret");
            content = animation; indexedType = "OTHER";
        } else {
            var photo = photo(mini());
            photo.hasSpoiler = hidden.equals("photo-spoiler"); photo.isSecret = hidden.equals("photo-secret");
            content = photo; indexedType = "PHOTO";
        }
        var message = message(MESSAGE, 0, content);
        switch (hidden) {
            case "self-destruct-type" -> message.selfDestructType = new TdApi.MessageSelfDestructTypeImmediately();
            case "self-destruct-countdown" -> message.selfDestructIn = 10;
            case "ephemeral-content" -> message.ephemeralContent = new TdApi.EphemeralMessageContent();
            default -> { }
        }
        var scenario = new Scenario();
        scenario.response = query -> CompletableFuture.completedFuture(message);

        assertUnavailable(scenario.reader.read(single(indexedType)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "message", "not-channel-post", "album"})
    void returnedMessageMustMatchTheIndexedChannelMessageAndAlbum(String mismatch) {
        var message = message(MESSAGE, 42, photo(mini()));
        switch (mismatch) {
            case "chat" -> message.chatId = -1002;
            case "message" -> message.id = MESSAGE + 1;
            case "not-channel-post" -> message.isChannelPost = false;
            case "album" -> message.mediaAlbumId = 43;
            default -> throw new AssertionError(mismatch);
        }
        var scenario = new Scenario();
        scenario.response = query -> CompletableFuture.completedFuture(message);
        var post = post("42", List.of(ref(MESSAGE)), List.of(media(ref(MESSAGE), "MAIN", "PHOTO")));

        assertUnavailable(scenario.reader.read(post));
        assertEquals(List.of(MESSAGE), scenario.requestedIds());
    }

    @Test
    void onlyMainMediaInBothSavedMembersAndTheCurrentChannelCanBeQueried() {
        var foreign = new MessageRef("-1002", "102", "");
        var post = post("42", List.of(ref(MESSAGE), foreign), List.of(
            media(ref(MESSAGE), "COMMENT", "PHOTO"),
            media(ref(MESSAGE + 1), "MAIN", "PHOTO"),
            media(foreign, "MAIN", "PHOTO")));
        var scenario = new Scenario();
        scenario.response = query -> { throw new AssertionError("Unindexed media must not be queried"); };

        assertUnavailable(scenario.reader.read(post));
        assertTrue(scenario.queries.isEmpty());
    }

    @Test
    void albumCanUseItsSecondIndexedMemberWhenTheFirstHasNoPreview() {
        var scenario = new Scenario();
        scenario.response = query -> CompletableFuture.completedFuture(message(query.messageId, 42,
            photo(query.messageId == MESSAGE ? null : mini())));

        assertImage(scenario.reader.read(album(2)), JPEG, 4, 3);
        assertEquals(List.of(MESSAGE, MESSAGE + 1), scenario.requestedIds());
    }

    @Test
    void albumReadsAtMostTenDistinctIndexedMembers() {
        var scenario = new Scenario();
        scenario.response = query -> CompletableFuture.completedFuture(message(query.messageId, 42,
            photo(query.messageId == MESSAGE + 10 ? mini() : null)));

        assertUnavailable(scenario.reader.read(album(11)));
        assertEquals(10, scenario.queries.size());
        assertFalse(scenario.requestedIds().contains(MESSAGE + 10));

        scenario.queries.clear();
        var repeated = post("42", List.of(ref(MESSAGE), ref(MESSAGE)), List.of(media(ref(MESSAGE), "MAIN", "PHOTO")));
        assertUnavailable(scenario.reader.read(repeated));
        assertEquals(List.of(MESSAGE), scenario.requestedIds());
    }

    @Test
    void allAlbumRequestsShareOneTwelveSecondBudgetWithoutWaitingForRealTimeouts() {
        var scenario = new Scenario();
        var budgets = new ArrayList<Long>();
        scenario.response = query -> new CompletableFuture<>() {
            @Override public TdApi.Message get(long timeout, TimeUnit unit) {
                budgets.add(unit.toNanos(timeout));
                return message(query.messageId, 42, photo(null));
            }
        };

        assertUnavailable(scenario.reader.read(album(2)));
        assertEquals(2, budgets.size());
        assertTrue(budgets.getFirst() > 0 && budgets.getFirst() <= TimeUnit.SECONDS.toNanos(12));
        assertTrue(budgets.getLast() > 0 && budgets.getLast() < budgets.getFirst(), "Second member must use the remaining total budget");
    }

    @Test
    void accountMismatchBeforeReadingPreventsAnyTelegramQuery() {
        var scenario = new Scenario();
        scenario.account.set("2");

        assertThrows(IllegalArgumentException.class, () -> scenario.reader.read(single("PHOTO")));
        assertTrue(scenario.queries.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void accountChangeDuringReadRejectsBothAvailableAndUnavailableResults(boolean hasPreview) {
        var scenario = new Scenario();
        scenario.response = query -> {
            scenario.account.set("2");
            return CompletableFuture.completedFuture(message(MESSAGE, 0, photo(hasPreview ? mini() : null)));
        };

        assertThrows(IllegalArgumentException.class, () -> scenario.reader.read(single("PHOTO")));
        assertEquals(1, scenario.queries.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed-future", "gateway-throw", "timeout"})
    void failuresAreSanitizedAndTheSameReaderCanRetry(String failure) {
        var scenario = new Scenario();
        scenario.response = query -> {
            if (failure.equals("gateway-throw")) throw new IllegalStateException(PRIVATE_ERROR);
            if (failure.equals("failed-future")) return CompletableFuture.failedFuture(new IllegalStateException(PRIVATE_ERROR));
            return new CompletableFuture<>() {
                @Override public TdApi.Message get(long timeout, TimeUnit unit) throws TimeoutException {
                    throw new TimeoutException(PRIVATE_ERROR);
                }
            };
        };

        var exception = assertThrows(IllegalArgumentException.class, () -> scenario.reader.read(single("PHOTO")));
        assertNotNull(exception.getMessage());
        assertFalse(exception.getMessage().contains(PRIVATE_ERROR));
        assertFalse(exception.toString().contains(PRIVATE_ERROR));
        assertNull(exception.getCause(), "Native failure must not escape through the exception cause");

        scenario.response = query -> CompletableFuture.completedFuture(message(MESSAGE, 0, photo(mini())));
        assertImage(scenario.reader.read(single("PHOTO")), JPEG, 4, 3);
        assertEquals(2, scenario.queries.size());
    }

    @Test
    void interruptionIsReportedWithoutClearingTheThreadsInterruptFlag() {
        var scenario = new Scenario();
        scenario.response = query -> new CompletableFuture<>();
        try {
            Thread.currentThread().interrupt();
            var exception = assertThrows(IllegalArgumentException.class, () -> scenario.reader.read(single("PHOTO")));
            assertTrue(Thread.currentThread().isInterrupted());
            assertNull(exception.getCause());
        } finally {
            Thread.interrupted(); // Do not leak this intentional interruption into other JUnit tests.
        }
    }

    private static void assertImage(Preview preview, byte[] jpeg, int width, int height) {
        assertEquals("AVAILABLE", preview.status());
        assertNotNull(preview.dataUrl());
        assertTrue(preview.dataUrl().startsWith("data:image/jpeg;base64,"));
        assertArrayEquals(jpeg, Base64.getDecoder().decode(preview.dataUrl().substring("data:image/jpeg;base64,".length())));
        assertEquals(width, preview.width()); assertEquals(height, preview.height());
    }

    private static void assertUnavailable(Preview preview) {
        assertEquals(Preview.unavailable(), preview);
        assertEquals("UNAVAILABLE", preview.status());
        assertNull(preview.dataUrl());
        assertEquals(0, preview.width()); assertEquals(0, preview.height());
    }

    private static Post single(String type) {
        return post("0", List.of(ref(MESSAGE)), List.of(media(ref(MESSAGE), "MAIN", type)));
    }

    private static Post album(int count) {
        var refs = new ArrayList<MessageRef>(); var media = new ArrayList<Media>();
        for (int index = 0; index < count; index++) {
            var ref = ref(MESSAGE + index); refs.add(ref); media.add(media(ref, "MAIN", "PHOTO"));
        }
        return post("42", refs, media);
    }

    private static Post post(String album, List<MessageRef> refs, List<Media> media) {
        return new Post("synthetic-post", ACCOUNT, Long.toString(CHANNEL), "Synthetic channel", Long.toString(MESSAGE),
            "Synthetic title", "Synthetic main text", album, refs, media, null, null, List.of(), false,
            "PARTIAL", List.of("Synthetic indexed warning"), null, "PARTIAL");
    }

    private static MessageRef ref(long message) { return new MessageRef(Long.toString(CHANNEL), Long.toString(message), ""); }
    private static Media media(MessageRef ref, String source, String type) {
        return new Media(ACCOUNT + ":" + ref.chatId() + ":" + ref.messageId() + ":0", ref, source, type, "synthetic", 123, "");
    }
    private static TdApi.Message message(long id, long album, TdApi.MessageContent content) {
        var message = new TdApi.Message();
        message.chatId = CHANNEL; message.id = id; message.mediaAlbumId = album;
        message.isChannelPost = true; message.content = content;
        return message;
    }
    private static TdApi.Minithumbnail mini() { return new TdApi.Minithumbnail(4, 3, JPEG.clone()); }
    private static TdApi.Photo photoValue(TdApi.Minithumbnail mini) {
        return new TdApi.Photo(false, mini, new TdApi.PhotoSize[]{new TdApi.PhotoSize("s", file(), 4, 3, new int[0])});
    }
    private static TdApi.MessagePhoto photo(TdApi.Minithumbnail mini) {
        return new TdApi.MessagePhoto(photoValue(mini), null, caption(), false, false, false);
    }
    private static TdApi.MessageVideo video(TdApi.Minithumbnail mini, TdApi.Minithumbnail cover) {
        var video = new TdApi.Video(); video.minithumbnail = mini; video.video = file(); video.fileName = "synthetic.mp4";
        var content = new TdApi.MessageVideo(); content.video = video; content.caption = caption();
        if (cover != null) content.cover = photoValue(cover);
        return content;
    }
    private static TdApi.MessageDocument document(TdApi.Minithumbnail mini) {
        return new TdApi.MessageDocument(new TdApi.Document("synthetic.pdf", "application/pdf", mini, null, file()), caption());
    }
    private static TdApi.MessageAnimation animation(TdApi.Minithumbnail mini) {
        var animation = new TdApi.Animation(); animation.minithumbnail = mini; animation.animation = file();
        animation.fileName = "synthetic.gif";
        return new TdApi.MessageAnimation(animation, caption(), false, false, false);
    }
    private static TdApi.MessageAudio audio(TdApi.Minithumbnail mini) {
        var audio = new TdApi.Audio(); audio.albumCoverMinithumbnail = mini; audio.audio = file(); audio.fileName = "synthetic.mp3";
        return new TdApi.MessageAudio(audio, caption());
    }
    private static TdApi.File file() { var file = new TdApi.File(); file.id = 7; file.size = 123; return file; }
    private static TdApi.FormattedText caption() { return new TdApi.FormattedText("", new TdApi.TextEntity[0]); }

    private static byte[] image(String format, int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) image.setRGB(x, y, (x * 37 + y * 101) & 0xffffff);
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) throw new AssertionError("Synthetic image encoder is missing");
            return output.toByteArray();
        } catch (java.io.IOException failure) {
            throw new AssertionError("Cannot generate synthetic test image", failure);
        } finally { image.flush(); }
    }

    private static final class Scenario implements ResourceReader.Gateway {
        final AtomicReference<String> account = new AtomicReference<>(ACCOUNT);
        final List<TdApi.GetMessage> queries = new ArrayList<>();
        final PostPreviewReader reader = new PostPreviewReader(this, account::get);
        Function<TdApi.GetMessage, CompletableFuture<TdApi.Message>> response = query -> CompletableFuture.completedFuture(null);

        @Override @SuppressWarnings("unchecked")
        public <R extends TdApi.Object> CompletableFuture<R> read(TdApi.Function<R> query) {
            if (!(query instanceof TdApi.GetMessage request)) throw new AssertionError("Only GetMessage is allowed for a preview");
            queries.add(request);
            return (CompletableFuture<R>) response.apply(request);
        }
        List<Long> requestedIds() { return queries.stream().map(query -> query.messageId).toList(); }
    }
}
