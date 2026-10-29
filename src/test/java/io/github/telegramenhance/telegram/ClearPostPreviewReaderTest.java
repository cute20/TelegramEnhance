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
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ClearPostPreviewReaderTest {
    private static final String ACCOUNT = "1";
    private static final long CHANNEL = -1001;
    private static final long MESSAGE = 100;
    private static final byte[] MINI = image("jpeg", 4, 3, false);
    private static final byte[] CLEAR = image("jpeg", 64, 32, false);
    private static final String JPEG_PREFIX = "data:image/jpeg;base64,";

    @Test
    void legacyAvailablePreviewConstructorKeepsMiniQualityAndUnavailableHasNoQuality() {
        assertEquals("MINI", new Preview("AVAILABLE", JPEG_PREFIX + Base64.getEncoder().encodeToString(MINI), 4, 3).quality());
        assertNull(new Preview("UNAVAILABLE", null, 0, 0).quality());
        assertNull(Preview.unavailable().quality());
    }

    @Test
    void unorderedPhotosChooseLargestEligibleMediumDerivativeRatherThanOriginalOrInputSizes() {
        var small = size("s", 80, 40, file(11));
        var medium = size("m", 320, 160, file(12));
        var original = size("x", 1280, 640, file(13));
        var input = size("i", 500, 250, file(14));
        var thumbnail = size("t", 400, 200, file(15));
        var scenario = new Scenario(photo(true, small, original, thumbnail, medium, input), "PHOTO");
        scenario.fileRead = candidate -> image("jpeg", 320, 160, false);

        assertClear(scenario.read(), 320, 160);
        assertEquals(List.of(medium.photo), scenario.fileRequests);
        assertEquals(List.of(MESSAGE), scenario.requestedIds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"s", "m", "i", "t"})
    void aSinglePhotoSizeIsNeverFetchedAsADerivativeEvenIfSmall(String sizeType) {
        var scenario = new Scenario(photo(true, size(sizeType, 64, 32, file(11))), "PHOTO");

        assertMini(scenario.read());
        verifyNoInteractions(scenario.files);
    }

    @Test
    void equallyLargestPhotoSizesAreAllExcludedAsOriginals() {
        var scenario = new Scenario(photo(true, size("s", 64, 32, file(11)), size("m", 64, 32, file(12))), "PHOTO");

        assertMini(scenario.read());
        verifyNoInteractions(scenario.files);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file-id", "remote-unique-id"})
    void photoDerivativeAliasingTheLargestOriginalCannotBeRead(String alias) {
        var original = file(20);
        var aliased = file(alias.equals("file-id") ? original.id : 21);
        if (alias.equals("remote-unique-id")) aliased.remote.uniqueId = original.remote.uniqueId;
        var safe = file(22);
        var scenario = new Scenario(photo(true, size("m", 320, 160, aliased),
            size("x", 640, 320, original), size("s", 64, 32, safe)), "PHOTO");

        assertClear(scenario.read(), 64, 32);
        assertEquals(List.of(safe), scenario.fileRequests);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("staticMedia")
    void supportedMediaUsesOnlyItsStaticJpegOrPngThumbnail(String kind, String format) {
        var candidate = file(11);
        var scenario = new Scenario(content(kind, thumbnail(format, 64, 32, candidate)), indexedType(kind));
        scenario.fileRead = ignored -> image(format, 64, 32, false);

        assertClear(scenario.read(), 64, 32);
        assertEquals(List.of(candidate), scenario.fileRequests);
        assertEquals(List.of(MESSAGE), scenario.requestedIds());
    }

    static Stream<Arguments> staticMedia() {
        return Stream.of("VIDEO", "DOCUMENT", "ANIMATION", "AUDIO")
            .flatMap(kind -> Stream.of("jpeg", "png").map(format -> Arguments.of(kind, format)));
    }

    @Test
    void videoCanUseAnEligibleCoverDerivativeBeforeItsSmallerStaticThumbnail() {
        var content = (TdApi.MessageVideo) content("VIDEO", thumbnail("jpeg", 64, 32, file(11)));
        var medium = size("m", 320, 160, file(12));
        content.cover = new TdApi.Photo(false, null, new TdApi.PhotoSize[]{medium, size("x", 1280, 640, file(13))});
        var scenario = new Scenario(content, "VIDEO");
        scenario.fileRead = ignored -> image("jpeg", 320, 160, false);

        assertClear(scenario.read(), 320, 160);
        assertEquals(List.of(medium.photo), scenario.fileRequests);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("originalAliases")
    void staticThumbnailCannotAliasItsMainMediaFile(String kind, String alias) {
        var candidate = file(alias.equals("file-id") ? 1000 : 11);
        if (alias.equals("remote-unique-id")) candidate.remote.uniqueId = "synthetic-unique-1000";
        var scenario = new Scenario(content(kind, thumbnail("jpeg", 64, 32, candidate)), indexedType(kind));

        assertMini(scenario.read());
        verifyNoInteractions(scenario.files);
    }

    static Stream<Arguments> originalAliases() {
        return Stream.of("VIDEO", "DOCUMENT", "ANIMATION", "AUDIO")
            .flatMap(kind -> Stream.of("file-id", "remote-unique-id").map(alias -> Arguments.of(kind, alias)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonStaticFormats")
    void animatedAndOtherThumbnailFormatsAreNotFetched(String label, TdApi.ThumbnailFormat format) {
        var thumbnail = new TdApi.Thumbnail(format, 64, 32, file(11));
        var scenario = new Scenario(content("VIDEO", thumbnail), "VIDEO");

        assertMini(scenario.read());
        verifyNoInteractions(scenario.files);
    }

    static Stream<Arguments> nonStaticFormats() {
        return Stream.of(
            Arguments.of("GIF", new TdApi.ThumbnailFormatGif()),
            Arguments.of("MPEG4", new TdApi.ThumbnailFormatMpeg4()),
            Arguments.of("WebM", new TdApi.ThumbnailFormatWebm()),
            Arguments.of("TGS", new TdApi.ThumbnailFormatTgs()),
            Arguments.of("WebP", new TdApi.ThumbnailFormatWebp()),
            Arguments.of("missing format", null));
    }

    @Test
    void transparentPngBecomesOpaqueWhiteBackedRgbJpeg() throws Exception {
        var scenario = new Scenario(content("DOCUMENT", thumbnail("png", 64, 32, file(11))), "DOCUMENT");
        scenario.fileRead = ignored -> image("png", 64, 32, true);

        var preview = scenario.read();
        assertClear(preview, 64, 32);
        var decoded = ImageIO.read(new ByteArrayInputStream(bytes(preview)));
        try {
            int pixel = decoded.getRGB(0, 0);
            assertTrue((pixel >> 16 & 255) >= 245);
            assertTrue((pixel >> 8 & 255) >= 245);
            assertTrue((pixel & 255) >= 245);
            assertFalse(decoded.getColorModel().hasAlpha());
        } finally { decoded.flush(); }
    }

    @Test
    void a640PixelSourceIsScaledProportionallyTo480WithoutReturningTheSourceBytes() {
        var source = image("jpeg", 640, 320, false);
        var scenario = new Scenario(content("VIDEO", thumbnail("jpeg", 640, 320, file(11))), "VIDEO");
        scenario.fileRead = ignored -> source;

        var preview = scenario.read();
        assertClear(preview, 480, 240);
        assertFalse(Arrays.equals(source, bytes(preview)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidImageData")
    void invalidThumbnailPayloadFallsBackToTheEmbeddedMini(String label, String declaredFormat,
                                                          int width, int height, byte[] data) {
        var candidate = file(11);
        var scenario = new Scenario(content("VIDEO", thumbnail(declaredFormat, width, height, candidate)), "VIDEO");
        scenario.fileRead = ignored -> data;

        assertMini(scenario.read());
        assertEquals(List.of(candidate), scenario.fileRequests);
    }

    static Stream<Arguments> invalidImageData() {
        byte[] png = image("png", 64, 32, false);
        byte[] oversized = Arrays.copyOf(CLEAR, 256 * 1024 + 1);
        oversized[oversized.length - 2] = (byte) 0xff; oversized[oversized.length - 1] = (byte) 0xd9;
        return Stream.of(
            Arguments.of("helper unavailable", "jpeg", 64, 32, null),
            Arguments.of("empty bytes", "jpeg", 64, 32, new byte[0]),
            Arguments.of("JPEG declaration with PNG bytes", "jpeg", 64, 32, png),
            Arguments.of("PNG declaration with JPEG bytes", "png", 64, 32, CLEAR),
            Arguments.of("wrong width", "jpeg", 65, 32, CLEAR),
            Arguments.of("wrong height", "jpeg", 64, 33, CLEAR),
            Arguments.of("truncated JPEG", "jpeg", 64, 32, Arrays.copyOf(CLEAR, CLEAR.length - 2)),
            Arguments.of("truncated PNG", "png", 64, 32, Arrays.copyOf(png, 40)),
            Arguments.of("undecodable JPEG markers", "jpeg", 64, 32,
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9}),
            Arguments.of("over 256 KiB input", "jpeg", 64, 32, oversized));
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero-id", "zero-size", "negative-size", "large-size", "negative-expected-size",
        "large-expected-size", "zero-width", "negative-height", "large-width", "large-height"})
    void invalidFileOrDimensionMetadataPreventsTheHelperRead(String invalid) {
        var candidate = file(11);
        var thumbnail = thumbnail("jpeg", 64, 32, candidate);
        switch (invalid) {
            case "zero-id" -> candidate.id = 0;
            case "zero-size" -> candidate.size = 0;
            case "negative-size" -> candidate.size = -1;
            case "large-size" -> candidate.size = 256 * 1024 + 1;
            case "negative-expected-size" -> candidate.expectedSize = -1;
            case "large-expected-size" -> candidate.expectedSize = 256 * 1024 + 1;
            case "zero-width" -> thumbnail.width = 0;
            case "negative-height" -> thumbnail.height = -1;
            case "large-width" -> thumbnail.width = 641;
            case "large-height" -> thumbnail.height = 641;
            default -> throw new AssertionError(invalid);
        }
        var scenario = new Scenario(content("VIDEO", thumbnail), "VIDEO");

        assertMini(scenario.read());
        verifyNoInteractions(scenario.files);
    }

    @Test
    void zeroExpectedSizeIsAllowedWhenActualSizeIsBoundedAndPositive() {
        var candidate = file(11); candidate.expectedSize = 0;
        var scenario = new Scenario(content("DOCUMENT", thumbnail("jpeg", 64, 32, candidate)), "DOCUMENT");

        assertClear(scenario.read(), 64, 32);
        assertEquals(List.of(candidate), scenario.fileRequests);
    }

    @Test
    void anUnavailableFirstFileDoesNotTryAnotherFileInTheSameAlbum() {
        var firstFile = file(11); var secondFile = file(21);
        var first = photo(false, size("m", 64, 32, firstFile), size("x", 1280, 640, file(12)));
        var second = photo(true, size("m", 64, 32, secondFile), size("x", 1280, 640, file(22)));
        var scenario = new Scenario(first, "PHOTO");
        scenario.post = post("PHOTO", "42", 2);
        scenario.messages = query -> message(query.messageId, 42, query.messageId == MESSAGE ? first : second);
        scenario.fileRead = ignored -> null;

        assertMini(scenario.read());
        assertEquals(List.of(firstFile), scenario.fileRequests);
        assertEquals(List.of(MESSAGE, MESSAGE + 1), scenario.requestedIds());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changingAccountDuringFileReadRejectsBothClearAndMiniFallback(boolean helperReturnsImage) {
        var scenario = new Scenario(content("VIDEO", thumbnail("jpeg", 64, 32, file(11))), "VIDEO");
        scenario.fileRead = ignored -> {
            scenario.account.set("2");
            return helperReturnsImage ? CLEAR : null;
        };

        assertThrows(IllegalArgumentException.class, scenario::read);
        assertEquals(1, scenario.fileRequests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"photo-spoiler", "photo-secret", "video-spoiler", "video-secret", "animation-spoiler",
        "animation-secret", "self-destruct-type", "self-destruct-countdown", "ephemeral-content"})
    void hiddenOrTemporaryMessagesNeverCallTheThumbnailFileHelper(String hidden) {
        TdApi.MessageContent content;
        String type;
        if (hidden.startsWith("photo")) {
            var photo = photo(true, size("m", 64, 32, file(11)), size("x", 1280, 640, file(12)));
            photo.hasSpoiler = hidden.endsWith("spoiler"); photo.isSecret = hidden.endsWith("secret");
            content = photo; type = "PHOTO";
        } else if (hidden.startsWith("animation")) {
            var animation = (TdApi.MessageAnimation) content("ANIMATION", thumbnail("jpeg", 64, 32, file(11)));
            animation.hasSpoiler = hidden.endsWith("spoiler"); animation.isSecret = hidden.endsWith("secret");
            content = animation; type = "OTHER";
        } else {
            var video = (TdApi.MessageVideo) content("VIDEO", thumbnail("jpeg", 64, 32, file(11)));
            video.hasSpoiler = hidden.equals("video-spoiler"); video.isSecret = hidden.equals("video-secret");
            content = video; type = "VIDEO";
        }
        var response = message(MESSAGE, 0, content);
        switch (hidden) {
            case "self-destruct-type" -> response.selfDestructType = new TdApi.MessageSelfDestructTypeImmediately();
            case "self-destruct-countdown" -> response.selfDestructIn = 10;
            case "ephemeral-content" -> response.ephemeralContent = new TdApi.EphemeralMessageContent();
            default -> { }
        }
        var scenario = new Scenario(content, type);
        scenario.messages = query -> response;

        assertEquals(Preview.unavailable(), scenario.read());
        verifyNoInteractions(scenario.files);
    }

    private static void assertClear(Preview preview, int width, int height) {
        assertEquals("AVAILABLE", preview.status());
        assertEquals("THUMBNAIL", preview.quality());
        assertEquals(width, preview.width()); assertEquals(height, preview.height());
        assertTrue(width <= 480 && height <= 480);
        byte[] bytes = bytes(preview);
        assertTrue(bytes.length <= 128 * 1024);
        assertEquals(0xff, bytes[0] & 255); assertEquals(0xd8, bytes[1] & 255);
        assertEquals(0xff, bytes[bytes.length - 2] & 255); assertEquals(0xd9, bytes[bytes.length - 1] & 255);
        try {
            var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            assertNotNull(decoded);
            try {
                assertEquals(width, decoded.getWidth()); assertEquals(height, decoded.getHeight());
                assertFalse(decoded.getColorModel().hasAlpha());
            } finally { decoded.flush(); }
        } catch (java.io.IOException failure) { throw new AssertionError("Preview is not a decodable JPEG", failure); }
    }

    private static void assertMini(Preview preview) {
        assertEquals("AVAILABLE", preview.status()); assertEquals("MINI", preview.quality());
        assertEquals(4, preview.width()); assertEquals(3, preview.height());
        assertArrayEquals(MINI, bytes(preview));
    }

    private static byte[] bytes(Preview preview) {
        assertNotNull(preview.dataUrl()); assertTrue(preview.dataUrl().startsWith(JPEG_PREFIX));
        return Base64.getDecoder().decode(preview.dataUrl().substring(JPEG_PREFIX.length()));
    }

    private static TdApi.MessageContent content(String kind, TdApi.Thumbnail thumbnail) {
        return switch (kind) {
            case "VIDEO" -> {
                var video = new TdApi.Video(); video.video = file(1000); video.thumbnail = thumbnail;
                video.minithumbnail = mini(); video.fileName = "synthetic.mp4";
                var content = new TdApi.MessageVideo(); content.video = video; content.caption = caption(); yield content;
            }
            case "DOCUMENT" -> new TdApi.MessageDocument(
                new TdApi.Document("synthetic.pdf", "application/pdf", mini(), thumbnail, file(1000)), caption());
            case "ANIMATION" -> {
                var animation = new TdApi.Animation(); animation.animation = file(1000); animation.thumbnail = thumbnail;
                animation.minithumbnail = mini(); animation.fileName = "synthetic.gif";
                yield new TdApi.MessageAnimation(animation, caption(), false, false, false);
            }
            case "AUDIO" -> {
                var audio = new TdApi.Audio(); audio.audio = file(1000); audio.albumCoverThumbnail = thumbnail;
                audio.albumCoverMinithumbnail = mini(); audio.fileName = "synthetic.mp3";
                yield new TdApi.MessageAudio(audio, caption());
            }
            default -> throw new AssertionError(kind);
        };
    }

    private static TdApi.MessagePhoto photo(boolean withMini, TdApi.PhotoSize... sizes) {
        return new TdApi.MessagePhoto(new TdApi.Photo(false, withMini ? mini() : null, sizes), null, caption(), false, false, false);
    }
    private static TdApi.PhotoSize size(String type, int width, int height, TdApi.File file) {
        return new TdApi.PhotoSize(type, file, width, height, new int[0]);
    }
    private static TdApi.Thumbnail thumbnail(String format, int width, int height, TdApi.File file) {
        return new TdApi.Thumbnail(format.equals("jpeg") ? new TdApi.ThumbnailFormatJpeg() : new TdApi.ThumbnailFormatPng(), width, height, file);
    }
    private static TdApi.File file(int id) {
        var file = new TdApi.File(); file.id = id; file.size = 4096; file.expectedSize = 4096;
        file.remote = new TdApi.RemoteFile("synthetic-remote-" + id, "synthetic-unique-" + id, false, false, 0);
        return file;
    }
    private static TdApi.Minithumbnail mini() { return new TdApi.Minithumbnail(4, 3, MINI.clone()); }
    private static TdApi.FormattedText caption() { return new TdApi.FormattedText("", new TdApi.TextEntity[0]); }
    private static String indexedType(String kind) { return kind.equals("ANIMATION") ? "OTHER" : kind; }
    private static TdApi.Message message(long id, long album, TdApi.MessageContent content) {
        var message = new TdApi.Message(); message.id = id; message.chatId = CHANNEL;
        message.mediaAlbumId = album; message.isChannelPost = true; message.content = content; return message;
    }
    private static Post post(String type, String album, int count) {
        var refs = new ArrayList<MessageRef>(); var media = new ArrayList<Media>();
        for (int i = 0; i < count; i++) {
            var ref = new MessageRef(Long.toString(CHANNEL), Long.toString(MESSAGE + i), ""); refs.add(ref);
            media.add(new Media("synthetic-" + i, ref, "MAIN", type, "synthetic", 4096, ""));
        }
        return new Post("synthetic-post", ACCOUNT, Long.toString(CHANNEL), "Synthetic channel", Long.toString(MESSAGE),
            "Synthetic title", "Synthetic text", album, refs, media, null, null, List.of(), false,
            "PARTIAL", List.of("Synthetic existing warning"), null, "PARTIAL");
    }

    private static byte[] image(String format, int width, int height, boolean transparent) {
        var image = new BufferedImage(width, height, transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        if (!transparent) for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) image.setRGB(x, y, (x * 37 + y * 101) & 0xffffff);
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) throw new AssertionError("Synthetic image encoder is missing");
            return output.toByteArray();
        } catch (java.io.IOException failure) { throw new AssertionError("Cannot generate synthetic test image", failure); }
        finally { image.flush(); }
    }

    private static final class Scenario implements ResourceReader.Gateway {
        final AtomicReference<String> account = new AtomicReference<>(ACCOUNT);
        final ThumbnailFileReader files = mock(ThumbnailFileReader.class);
        final List<TdApi.GetMessage> queries = new ArrayList<>();
        final List<TdApi.File> fileRequests = new ArrayList<>();
        final PostPreviewReader reader = new PostPreviewReader(this, account::get, files);
        Post post;
        Function<TdApi.GetMessage, TdApi.Message> messages;
        Function<TdApi.File, byte[]> fileRead = ignored -> CLEAR;

        Scenario(TdApi.MessageContent content, String type) {
            post = post(type, "0", 1);
            messages = query -> message(query.messageId, 0, content);
            when(files.read(any(TdApi.File.class), anyString(), anyLong())).thenAnswer(invocation -> {
                TdApi.File candidate = invocation.getArgument(0);
                assertEquals(ACCOUNT, invocation.getArgument(1));
                long remaining = (long) invocation.getArgument(2) - System.nanoTime();
                assertTrue(remaining > 0 && remaining <= TimeUnit.SECONDS.toNanos(12));
                fileRequests.add(candidate);
                return fileRead.apply(candidate);
            });
        }
        Preview read() { return reader.read(post); }
        @Override @SuppressWarnings("unchecked")
        public <R extends TdApi.Object> CompletableFuture<R> read(TdApi.Function<R> query) {
            if (!(query instanceof TdApi.GetMessage request)) throw new AssertionError("Reader metadata must only use GetMessage");
            queries.add(request);
            return CompletableFuture.completedFuture((R) messages.apply(request));
        }
        List<Long> requestedIds() { return queries.stream().map(query -> query.messageId).toList(); }
    }
}
