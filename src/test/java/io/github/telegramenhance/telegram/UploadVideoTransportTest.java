package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import it.tdlight.jni.TdApi;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Native requests are captured by an isolated gateway; no files are uploaded or opened. */
class UploadVideoTransportTest {
    @TempDir Path root;

    @Test void aVideoAlbumCarriesEachVerifiedThumbnailDimensionsDurationAndStreamingFlagAsVideoContent() throws Exception {
        var first = staged("first.mp4", 1920, 1080, new VideoDetails(123, true, thumbnail("first.jpg", 320, 180)));
        var second = staged("second.mp4", 1080, 1920, new VideoDetails(34, false, thumbnail("second.jpg", 180, 320)));
        try (var f = new Fixture(List.of(first, second))) {
            f.send(); assertEquals(1, f.requests.size()); var album = assertInstanceOf(TdApi.SendMessageAlbum.class, f.requests.getFirst());
            assertEquals(2, album.inputMessageContents.length); assertEquals(-2001, album.chatId);
            assertEquals(500, assertInstanceOf(TdApi.MessageTopicThread.class, album.topicId).messageThreadId);
            for (int n = 0; n < 2; n++) {
                var expected = List.of(first, second).get(n); var sent = assertInstanceOf(TdApi.InputMessageVideo.class, album.inputMessageContents[n]);
                assertEquals(expected.path(), assertInstanceOf(TdApi.InputFileLocal.class, sent.video.video).path);
                assertEquals(expected.video().duration(), sent.video.duration); assertEquals(expected.width(), sent.video.width);
                assertEquals(expected.height(), sent.video.height); assertEquals(expected.video().supportsStreaming(), sent.video.supportsStreaming);
                assertNotNull(sent.video.thumbnail); assertEquals(expected.video().thumbnail().path(),
                        assertInstanceOf(TdApi.InputFileLocal.class, sent.video.thumbnail.thumbnail).path);
                assertEquals(expected.video().thumbnail().width(), sent.video.thumbnail.width); assertEquals(expected.video().thumbnail().height(), sent.video.thumbnail.height);
                assertEquals("video-" + n, sent.caption.text); assertNull(sent.video.cover); assertEquals(0, sent.video.startTimestamp);
            }
            verify(f.repository).dispatch(f.task, f.group, f.transport.owner(1)); verify(f.repository).initial(eq(f.task), eq(f.group), anyList());
            verify(f.repository, never()).event(anyLong(), any());
        }
    }

    @Test void aSingleVideoUsesTheSameVideoAndThumbnailContractWithoutDocumentFallback() throws Exception {
        var version = staged("single.mp4", 640, 480, new VideoDetails(5, true, thumbnail("single.jpg", 320, 240)));
        try (var f = new Fixture(List.of(version))) {
            f.send(); assertEquals(1, f.requests.size()); var message = assertInstanceOf(TdApi.SendMessage.class, f.requests.getFirst());
            var content = assertInstanceOf(TdApi.InputMessageVideo.class, message.inputMessageContent);
            assertEquals(5, content.video.duration); assertEquals(640, content.video.width); assertEquals(480, content.video.height);
            assertEquals(version.video().thumbnail().path(), assertInstanceOf(TdApi.InputFileLocal.class, content.video.thumbnail.thumbnail).path);
        }
    }

    @Test void incompleteVideoPreparationCannotConsumeTheDispatchIntentOrSendAnyOtherMediaType() throws Exception {
        var valid = thumbnail("valid.jpg", 320, 180);
        var invalid = List.of(
                staged("old.mp4", 0, 0, null),
                staged("missing-meta.mp4", 1920, 1080, null),
                staged("zero-width.mp4", 0, 1080, new VideoDetails(12, true, valid)),
                staged("large-width.mp4", 32769, 1080, new VideoDetails(12, true, valid)),
                staged("large-height.mp4", 1920, 32769, new VideoDetails(12, true, valid)),
                staged("zero-duration.mp4", 1920, 1080, new VideoDetails(0, true, valid)),
                staged("missing-thumb.mp4", 1920, 1080, new VideoDetails(12, true, null)),
                staged("empty-thumb.mp4", 1920, 1080, new VideoDetails(12, true, new VideoThumbnail(valid.path(), 0, valid.sha256(), valid.identity(), 320, 180))),
                staged("large-thumb.mp4", 1920, 1080, new VideoDetails(12, true, new VideoThumbnail(valid.path(), 128 * 1024 + 1, valid.sha256(), valid.identity(), 320, 180))),
                staged("wide-thumb.mp4", 1920, 1080, new VideoDetails(12, true, new VideoThumbnail(valid.path(), 100, valid.sha256(), valid.identity(), 321, 180))),
                staged("high-thumb.mp4", 1920, 1080, new VideoDetails(12, true, new VideoThumbnail(valid.path(), 100, valid.sha256(), valid.identity(), 180, 321))),
                staged("unowned-thumb.mp4", 1920, 1080, new VideoDetails(12, true, new VideoThumbnail(valid.path(), 100, valid.sha256(), "", 320, 180))),
                staged("bad-hash.mp4", 1920, 1080, new VideoDetails(12, true, new VideoThumbnail(valid.path(), 100, "not-a-digest", valid.identity(), 320, 180))));
        for (var version : invalid) try (var f = new Fixture(List.of(version))) {
            var failure = assertThrows(IllegalArgumentException.class, f::send); assertTrue(failure.getMessage().contains("视频"));
            assertEquals(0, f.requests.size()); verify(f.repository, never()).dispatch(any(), any(), anyString());
            verify(f.repository, never()).initial(any(), any(), anyList());
        }
    }

    @Test void legacySixFieldStagingJsonAndConstructorRemainReadableAndDoNotAcquireInventedVideoMetadata() throws Exception {
        var mapper = JsonMapper.builder().build(); String oldJson = "{\"path\":\"D:/synthetic/old.mp4\",\"size\":100,\"sha256\":\"abc\",\"identity\":\"owned\",\"width\":0,\"height\":0}";
        var old = mapper.readValue(oldJson, Staged.class); assertNull(old.video()); assertEquals(100, old.size());
        assertEquals(old, new Staged("D:/synthetic/old.mp4", 100, "abc", "owned", 0, 0));
        var current = staged("new.mp4", 1920, 1080, new VideoDetails(12, true, thumbnail("new.jpg", 320, 180)));
        assertEquals(current, mapper.readValue(mapper.writeValueAsString(current), Staged.class));
    }

    @Test void previouslyPendingVideoCanRecoverItsNativeAssociationWithoutRequiringOrGeneratingNewMetadata() throws Exception {
        var legacy = new Staged(root.resolve("legacy.mp4").toString(), 100, "b".repeat(64), "owned-video", 0, 0);
        var lease = mock(UploadFiles.Lease.class);
        try (var f = new Fixture(List.of(legacy), "UNCERTAIN")) {
            when(f.files.lease(anyList(), any())).thenReturn(lease);
            assertEquals(1, f.transport.recoverPending(f.task, 1)); assertNull(f.task.groups().getFirst().items().getFirst().staged().video());
            assertEquals(1, f.requests.size()); assertInstanceOf(TdApi.GetMessage.class, f.requests.getFirst());
            verify(f.repository, never()).dispatch(any(), any(), anyString()); verify(f.repository, never()).initial(any(), any(), anyList());
        }
        verify(lease).close();
    }

    private Staged staged(String name, int width, int height, VideoDetails details) { return new Staged(root.resolve(name).toString(), 100, "b".repeat(64), "owned-video-" + name, width, height, details); }
    private VideoThumbnail thumbnail(String name, int width, int height) { return new VideoThumbnail(root.resolve(name).toString(), 100, "a".repeat(64), "owned-thumb-" + name, width, height); }

    private final class Fixture implements AutoCloseable, UploadTransport.Gateway {
        final TelegramService telegram = mock(TelegramService.class); final UploadRepository repository = mock(UploadRepository.class);
        final UploadFiles files = mock(UploadFiles.class); final UploadTransport transport;
        final List<TdApi.Function<?>> requests = new ArrayList<>(); final Task task; final Group group;
        Fixture(List<Staged> staged) { this(staged, "INTENT"); }
        Fixture(List<Staged> staged, String state) {
            var versions = new ArrayList<UploadPreviewModels.FileVersion>(); var items = new ArrayList<Item>();
            for (int n = 0; n < staged.size(); n++) {
                var version = new UploadPreviewModels.FileVersion("video" + n + ".mp4", "VIDEO", 100, 1, "source-" + n, "b".repeat(64)); versions.add(version);
                items.add(new Item("item-" + n, n, version, "video-" + n, "STAGED", state.equals("UNCERTAIN") ? Integer.toString(100 + n) : null,
                        null, null, staged.get(n), null, null, null, null));
            }
            group = new Group(7, 1, "VIDEO", items.size() > 1 ? "ALBUM" : "SINGLE", state, "fixture-run", 1L, null, items);
            var input = new UploadPreviewModels.Input("https://t.me/synthetic/1", root.resolve("source").toString(), root.resolve("archive").toString(), true, "FILENAME");
            var post = new Post("1:-1001:100", "1", "-1001", "Synthetic", "100", "Synthetic", "text", "0", List.of(), List.of(), "-2001", "500", List.of(), true, "COMPLETE", List.of(), null, "COMPLETE");
            var scan = new UploadPreviewModels.Scan(input.sourceDirectory(), input.archiveDirectory(), true, versions, versions, 0, 0, 0, 0);
            var prepared = new UploadPreviewService.Prepared(input, new UploadPreviewModels.Target(post, "Synthetic discussion", "MEDIA_ALLOWED_AT_CHECK", 0, 1), scan, List.of());
            task = new Task("synthetic-task", "1", "synthetic-operation", "fingerprint", prepared, "RUNNING", false, null, 1, 1, List.of(group));
            when(telegram.accountId()).thenReturn("1"); when(telegram.sessionGeneration()).thenReturn(1L);
            transport = new UploadTransport(telegram, repository, files, this);
        }
        void send() { transport.send(task, group, 1); }
        @SuppressWarnings("unchecked") @Override public <R extends TdApi.Object> CompletableFuture<R> call(long session, TdApi.Function<R> query) {
            requests.add(query);
            if (query instanceof TdApi.GetMessage get) return CompletableFuture.completedFuture((R) message(group.items().getFirst(), get.messageId));
            var messages = group.items().stream().map(i -> message(i, 100 + i.ordinal())).toArray(TdApi.Message[]::new);
            return CompletableFuture.completedFuture((R) (query instanceof TdApi.SendMessage ? messages[0] : new TdApi.Messages(messages.length, messages)));
        }
        TdApi.Message message(Item item, long id) {
            var message = new TdApi.Message(); message.id = id; message.chatId = -2001; message.isOutgoing = true; message.date = 1;
            message.topicId = new TdApi.MessageTopicThread(500); message.mediaAlbumId = group.items().size() > 1 ? 77 : 0;
            message.sendingState = new TdApi.MessageSendingStatePending(Math.toIntExact(group.id()));
            var file = new TdApi.File(); file.size = item.file().size(); file.local = new TdApi.LocalFile(); file.local.path = item.staged().path();
            file.remote = new TdApi.RemoteFile(); file.remote.uniqueId = "remote-" + item.id();
            var content = new TdApi.MessageVideo(); content.caption = new TdApi.FormattedText(item.caption(), new TdApi.TextEntity[0]); content.video = new TdApi.Video(); content.video.video = file;
            message.content = content; return message;
        }
        @Override public void close() { transport.close(); }
    }
}
