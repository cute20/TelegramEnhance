package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.library.ResourceModels.*;
import it.tdlight.jni.TdApi;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SourceMediaPreviewReaderTest {
    @Test void mainAttachmentPreviewReadsItsSelectedMemberRatherThanRepeatingTheFirstAlbumCover() {
        var f = new Fixture();
        var result = f.reader.readSourceMedia(f.post, f.media.getLast());
        assertEquals("AVAILABLE", result.status()); assertEquals("MINI", result.quality());
        assertEquals(List.of(101L), f.requested);
    }
    @Test void anUnknownMediaIdentityAndANonVisualTypeCannotReachTheNativeClient() {
        var f = new Fixture(); var valid = f.media.getLast();
        for (var invalid : List.of(new Media("unobserved", valid.message(), "MAIN", "PHOTO", "", 1, ""),
                new Media(valid.key(), valid.message(), "MAIN", "AUDIO", "", 1, ""))) {
            assertEquals("UNAVAILABLE", f.reader.readSourceMedia(f.post, invalid).status());
        }
        assertTrue(f.requested.isEmpty());
    }
    @Test void aChangedMainAlbumOrContentIdentityIsNotReusedForAnOldAttachment() {
        var f = new Fixture(); f.messages.getLast().mediaAlbumId = 999;
        assertEquals("UNAVAILABLE", f.reader.readSourceMedia(f.post, f.media.getLast()).status());
        assertEquals(List.of(101L), f.requested);
    }
    private static final class Fixture implements ResourceReader.Gateway {
        final List<Long> requested = new ArrayList<>(); final List<TdApi.Message> messages = new ArrayList<>();
        final List<Media> media = new ArrayList<>(); final PostPreviewReader reader = new PostPreviewReader(this, () -> "1");
        final Post post;
        Fixture() {
            for (long id : List.of(100L, 101L)) {
                var message = new TdApi.Message(); message.id = id; message.chatId = -1001; message.isChannelPost = true; message.mediaAlbumId = 42;
                var file = new TdApi.File(); file.id = (int) id; file.size = 100;
                byte[] jpeg;
                try { var data = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "JPEG", data); jpeg = data.toByteArray(); }
                catch (Exception error) { throw new AssertionError(error); }
                message.content = new TdApi.MessagePhoto(new TdApi.Photo(false, new TdApi.Minithumbnail(3, 2, jpeg),
                        new TdApi.PhotoSize[]{new TdApi.PhotoSize("x", file, 40, 30, new int[0])}), null,
                        new TdApi.FormattedText("", new TdApi.TextEntity[0]), false, false, false);
                messages.add(message); media.addAll(MediaMapper.map("1", message, "MAIN", new MessageRef("-1001", Long.toString(id), ""), new ArrayList<>()));
            }
            post = new Post("1:-1001:album:42", "1", "-1001", "channel", "100", "title", "text", "42", media.stream().map(Media::message).toList(), media, null, null, List.of(), false, "UNAVAILABLE", List.of());
        }
        @Override @SuppressWarnings("unchecked") public <R extends TdApi.Object> CompletableFuture<R> read(TdApi.Function<R> query) {
            if (!(query instanceof TdApi.GetMessage request)) throw new AssertionError("Only the selected metadata message may be read");
            requested.add(request.messageId); assertEquals(-1001, request.chatId);
            return CompletableFuture.completedFuture((R) messages.stream().filter(message -> message.id == request.messageId).findFirst().orElseThrow());
        }
    }
}
