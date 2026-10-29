package io.github.telegramenhance.telegram;

import io.github.telegramenhance.library.ResourceModels.*;
import it.tdlight.jni.TdApi;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.RenderingHints;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Indexed or server-observed messages: bounded static thumbnails, then embedded low-resolution fallback. */
@Service
public class PostPreviewReader {
    private static final int MAX_BYTES = 32 * 1024;
    private static final int MAX_EDGE = 256;
    private static final int MAX_MESSAGES = 10;
    private static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(12);
    private static final int MAX_THUMBNAIL_EDGE = 640;
    private static final int OUTPUT_EDGE = 480;
    private static final int MAX_THUMBNAIL_BYTES = 256 * 1024;
    private static final int MAX_OUTPUT_BYTES = 128 * 1024;
    private final ResourceReader.Gateway gateway;
    private final Supplier<String> account;
    private final ThumbnailFileReader files;

    public record Preview(String status, String dataUrl, int width, int height, String quality) {
        public Preview(String status, String dataUrl, int width, int height) { this(status, dataUrl, width, height, "AVAILABLE".equals(status) ? "MINI" : null); }
        public static Preview unavailable() { return new Preview("UNAVAILABLE", null, 0, 0, null); }
        @Override public String toString() { return "Preview[status=" + status + ", width=" + width + ", height=" + height + ", quality=" + quality + "]"; }
    }

    @Autowired public PostPreviewReader(TelegramService telegram, ThumbnailFileReader files) { this(telegram::read, telegram::accountId, files); }
    PostPreviewReader(ResourceReader.Gateway gateway, Supplier<String> account) { this(gateway, account, null); }
    PostPreviewReader(ResourceReader.Gateway gateway, Supplier<String> account, ThumbnailFileReader files) { this.gateway = gateway; this.account = account; this.files = files; }

    public Preview read(Post post) {
        return read(post, null);
    }
    /** The source service supplies an observed item, never a client-supplied file identity. */
    public Preview readSourceMedia(Post post, Media media) {
        if (post == null || media == null) throw new IllegalArgumentException("来源媒体预览身份无效");
        if (!("PHOTO".equals(media.type()) || "VIDEO".equals(media.type()))) return Preview.unavailable();
        if ("COMMENT".equals(media.source())) return readMedia(post, media);
        if (!"MAIN".equals(media.source()) || !post.mainMedia().contains(media)) return Preview.unavailable();
        return read(post, media);
    }
    private Preview read(Post post, Media selected) {
        if (post == null) throw new IllegalArgumentException("主帖预览身份无效");
        ensureAccount(post.accountId());
        long channel = identifier(post.channelId(), false);
        long album = identifier(post.albumId(), true);
        long deadline = System.nanoTime() + BUDGET_NANOS;
        Set<String> visited = new HashSet<>();
        int requested = 0;
        boolean attemptedFile = false;
        for (var ref : post.mainMessages()) {
            if (ref == null || !post.channelId().equals(ref.chatId()) || !visited.add(ref.messageId())) continue;
            var indexed = post.mainMedia().stream().filter(media -> media != null && "MAIN".equals(media.source())
                    && (selected == null || selected.equals(media))
                    && media.message() != null && ref.chatId().equals(media.message().chatId())
                    && ref.messageId().equals(media.message().messageId())).toList();
            if (indexed.isEmpty()) continue;
            if (++requested > MAX_MESSAGES) break;
            long messageId = identifier(ref.messageId(), false);
            if (messageId <= 0) throw new IllegalArgumentException("主帖预览身份无效");
            ensureAccount(post.accountId());
            var message = call(new TdApi.GetMessage(channel, messageId), deadline);
            ensureAccount(post.accountId());
            if (message == null || message.chatId != channel || message.id != messageId || !message.isChannelPost
                    || message.mediaAlbumId != album || message.selfDestructType != null || message.selfDestructIn > 0
                    || message.ephemeralContent != null || message.content == null) continue;
            // A changed content type must be reindexed before its preview can represent this saved media.
            var mapped = MediaMapper.map(post.accountId(), message, "MAIN", ref, new ArrayList<>());
            if (mapped.isEmpty() || indexed.stream().noneMatch(media -> Objects.equals(media.type(), mapped.getFirst().type())
                    && (selected == null || mapped.stream().anyMatch(current -> Objects.equals(current.key(), media.key()))))) continue;
            var candidates = fileThumbnails(message.content);
            var candidate = files != null && !attemptedFile && !candidates.isEmpty() ? candidates.getFirst() : null;
            if (candidate != null) attemptedFile = true;
            var preview = render(message.content, post.accountId(), deadline, candidate);
            if ("AVAILABLE".equals(preview.status())) return preview;
        }
        ensureAccount(post.accountId());
        return Preview.unavailable();
    }

    /** The caller supplies an account/post-scoped saved or observed comment row, never a raw file ID. */
    public Preview readMedia(Post post, Media media) {
        if (post == null || media == null) throw new IllegalArgumentException("媒体预览身份无效");
        ensureAccount(post.accountId());
        var ref = media.message();
        if (!"COMMENT".equals(media.source()) || !("PHOTO".equals(media.type()) || "VIDEO".equals(media.type()))
                || ref == null || post.discussionChatId() == null || !post.discussionChatId().equals(ref.chatId())) return Preview.unavailable();
        if (Objects.equals(post.discussionRootMessageId(), ref.messageId())
                || post.discussionRoots().stream().anyMatch(root -> root != null && Objects.equals(root.chatId(), ref.chatId()) && Objects.equals(root.messageId(), ref.messageId()))
                || post.mainMessages().stream().anyMatch(root -> root != null && Objects.equals(root.chatId(), ref.chatId()) && Objects.equals(root.messageId(), ref.messageId()))) return Preview.unavailable();
        long chat = identifier(ref.chatId(), false), id = identifier(ref.messageId(), false);
        if (id <= 0) throw new IllegalArgumentException("媒体预览身份无效");
        long deadline = System.nanoTime() + BUDGET_NANOS;
        var message = call(new TdApi.GetMessage(chat, id), deadline);
        ensureAccount(post.accountId());
        if (message == null || message.chatId != chat || message.id != id || message.isChannelPost || message.selfDestructType != null
                || message.selfDestructIn > 0 || message.ephemeralContent != null || message.content == null) return Preview.unavailable();
        if (message.topicId instanceof TdApi.MessageTopicThread thread) {
            String threadId = Long.toString(thread.messageThreadId);
            boolean knownThread = Objects.equals(threadId, post.discussionRootMessageId()) || post.discussionRoots().stream()
                    .anyMatch(root -> root != null && Objects.equals(root.chatId(), ref.chatId()) && Objects.equals(root.messageId(), threadId));
            if (!knownThread) return Preview.unavailable();
        }
        // Comments have their own album/thread identities. They must never inherit the main album ID.
        var mapped = MediaMapper.map(post.accountId(), message, "COMMENT", ref, new ArrayList<>());
        if (mapped.stream().noneMatch(current -> Objects.equals(current.key(), media.key()) && Objects.equals(current.type(), media.type()))) return Preview.unavailable();
        var candidates = fileThumbnails(message.content);
        var preview = render(message.content, post.accountId(), deadline, files != null && !candidates.isEmpty() ? candidates.getFirst() : null);
        ensureAccount(post.accountId());
        return preview;
    }

    private Preview render(TdApi.MessageContent content, String expectedAccount, long deadline, Candidate candidate) {
        Preview fallback = null;
        for (var mini : thumbnails(content)) { fallback = validate(mini); if (fallback != null) break; }
        if (candidate != null) {
            byte[] data = files.read(candidate.file(), expectedAccount, deadline);
            ensureAccount(expectedAccount);
            var preview = clearPreview(candidate, data);
            if (preview != null) return preview;
        }
        ensureAccount(expectedAccount);
        return fallback != null ? fallback : Preview.unavailable();
    }

    private TdApi.Message call(TdApi.GetMessage query, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IllegalArgumentException("主帖小预览读取超时，请稍后重试");
        try { return gateway.read(query).get(remaining, TimeUnit.NANOSECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalArgumentException("主帖小预览读取已中断"); }
        catch (ExecutionException | TimeoutException | RuntimeException e) { throw new IllegalArgumentException("主帖小预览暂不可读取，请稍后重试"); }
    }

    private void ensureAccount(String expected) {
        if (expected == null || !expected.equals(account.get())) throw new IllegalArgumentException("Telegram账号已改变，请刷新资源库后重试");
    }
    private static long identifier(String value, boolean allowZero) {
        try {
            long parsed = Long.parseLong(value);
            if (!allowZero && parsed == 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("主帖预览身份无效"); }
    }

    private static List<TdApi.Minithumbnail> thumbnails(TdApi.MessageContent content) {
        List<TdApi.Minithumbnail> result = new ArrayList<>();
        switch (content) {
            case TdApi.MessagePhoto m -> { if (!m.hasSpoiler && !m.isSecret && m.photo != null) result.add(m.photo.minithumbnail); }
            case TdApi.MessageVideo m -> {
                if (!m.hasSpoiler && !m.isSecret) {
                    if (m.cover != null) result.add(m.cover.minithumbnail);
                    if (m.video != null) result.add(m.video.minithumbnail);
                }
            }
            case TdApi.MessageAnimation m -> { if (!m.hasSpoiler && !m.isSecret && m.animation != null) result.add(m.animation.minithumbnail); }
            case TdApi.MessageDocument m -> { if (m.document != null) result.add(m.document.minithumbnail); }
            case TdApi.MessageAudio m -> { if (m.audio != null) result.add(m.audio.albumCoverMinithumbnail); }
            default -> { }
        }
        return result;
    }

    private record Candidate(TdApi.File file, int width, int height, String format) { }
    private static List<Candidate> fileThumbnails(TdApi.MessageContent content) {
        List<Candidate> result = new ArrayList<>();
        Set<String> originals = new HashSet<>();
        switch (content) {
            case TdApi.MessagePhoto m -> { if (!m.hasSpoiler && !m.isSecret) { if (m.video != null) primary(m.video.video, originals); smallPhotos(m.photo, result, originals); } }
            case TdApi.MessageVideo m -> {
                if (!m.hasSpoiler && !m.isSecret && m.video != null) {
                    primary(m.video.video, originals); smallPhotos(m.cover, result, originals);
                    thumbnail(m.video.thumbnail, result);
                }
            }
            case TdApi.MessageAnimation m -> { if (!m.hasSpoiler && !m.isSecret && m.animation != null) { primary(m.animation.animation, originals); thumbnail(m.animation.thumbnail, result); } }
            case TdApi.MessageDocument m -> { if (m.document != null) { primary(m.document.document, originals); thumbnail(m.document.thumbnail, result); } }
            case TdApi.MessageAudio m -> { if (m.audio != null) { primary(m.audio.audio, originals); thumbnail(m.audio.albumCoverThumbnail, result); } }
            default -> { }
        }
        return result.stream().filter(c -> bounded(c) && !isPrimary(c.file(), originals))
                .sorted(Comparator.<Candidate>comparingLong(c -> (long)c.width() * c.height()).reversed()
                        .thenComparingLong(c -> c.file().size)).toList();
    }
    private static void primary(TdApi.File file, Set<String> originals) {
        if (file == null) return;
        originals.add("ID:" + file.id);
        if (file.remote != null && file.remote.uniqueId != null && !file.remote.uniqueId.isBlank()) originals.add("REMOTE:" + file.remote.uniqueId);
    }
    private static boolean isPrimary(TdApi.File file, Set<String> originals) {
        return originals.contains("ID:" + file.id) || (file.remote != null && file.remote.uniqueId != null && originals.contains("REMOTE:" + file.remote.uniqueId));
    }
    private static void smallPhotos(TdApi.Photo photo, List<Candidate> result, Set<String> originals) {
        if (photo == null || photo.sizes == null) return;
        long largestArea = 0;
        for (var size : photo.sizes) if (size != null && size.width > 0 && size.height > 0 && size.photo != null) {
            largestArea = Math.max(largestArea, (long)size.width * size.height);
        }
        for (var size : photo.sizes) if (size != null && size.photo != null && size.width > 0 && size.height > 0) {
            long area = (long)size.width * size.height;
            if (area == largestArea) { primary(size.photo, originals); continue; }
            // Only Telegram's small/medium derivative sizes. Input/local originals are never fallback candidates.
            if (("s".equals(size.type) || "m".equals(size.type)) && area < largestArea) result.add(new Candidate(size.photo, size.width, size.height, "JPEG"));
        }
    }
    private static void thumbnail(TdApi.Thumbnail thumbnail, List<Candidate> result) {
        if (thumbnail == null) return;
        String format = thumbnail.format instanceof TdApi.ThumbnailFormatJpeg ? "JPEG"
                : thumbnail.format instanceof TdApi.ThumbnailFormatPng ? "PNG" : null;
        if (format != null) result.add(new Candidate(thumbnail.file, thumbnail.width, thumbnail.height, format));
    }
    private static boolean bounded(Candidate candidate) {
        return candidate.file() != null && candidate.file().id > 0 && candidate.file().size > 0
                && candidate.file().size <= MAX_THUMBNAIL_BYTES && candidate.file().expectedSize >= 0 && candidate.file().expectedSize <= MAX_THUMBNAIL_BYTES
                && candidate.width() > 0 && candidate.height() > 0
                && candidate.width() <= MAX_THUMBNAIL_EDGE && candidate.height() <= MAX_THUMBNAIL_EDGE;
    }
    private static Preview clearPreview(Candidate candidate, byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_THUMBNAIL_BYTES) return null;
        if ("JPEG".equals(candidate.format()) && (data.length < 4 || (data[0] & 255) != 255 || (data[1] & 255) != 216
                || (data[2] & 255) != 255 || (data[data.length - 2] & 255) != 255 || (data[data.length - 1] & 255) != 217)) return null;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            BufferedImage source = null, output = null;
            try {
                if (!candidate.format().equalsIgnoreCase(reader.getFormatName())) return null;
                reader.setInput(input, true, true);
                if (reader.getWidth(0) != candidate.width() || reader.getHeight(0) != candidate.height()) return null;
                source = reader.read(0);
                if (source == null || source.getWidth() != candidate.width() || source.getHeight() != candidate.height()) return null;
                double scale = Math.min(1d, (double)OUTPUT_EDGE / Math.max(source.getWidth(), source.getHeight()));
                int width = Math.max(1, (int)Math.round(source.getWidth() * scale));
                int height = Math.max(1, (int)Math.round(source.getHeight() * scale));
                output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                var graphics = output.createGraphics();
                try {
                    graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(source, 0, 0, width, height, null);
                } finally { graphics.dispose(); }
                var bytes = new ByteArrayOutputStream();
                var writers = ImageIO.getImageWritersByFormatName("JPEG");
                if (!writers.hasNext()) return null;
                var writer = writers.next();
                try (var encoded = new MemoryCacheImageOutputStream(bytes)) {
                    writer.setOutput(encoded); writer.write(output); encoded.flush();
                } finally { writer.dispose(); }
                if (bytes.size() > MAX_OUTPUT_BYTES) return null;
                return new Preview("AVAILABLE", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()), width, height, "THUMBNAIL");
            } finally { reader.dispose(); if (source != null) source.flush(); if (output != null) output.flush(); }
        } catch (java.io.IOException | RuntimeException e) { return null; }
    }

    private static Preview validate(TdApi.Minithumbnail mini) {
        if (mini == null || mini.width <= 0 || mini.height <= 0 || mini.width > MAX_EDGE || mini.height > MAX_EDGE
                || mini.data == null || mini.data.length < 4 || mini.data.length > MAX_BYTES) return null;
        byte[] data = mini.data;
        if ((data[0] & 255) != 255 || (data[1] & 255) != 216 || (data[2] & 255) != 255
                || (data[data.length - 2] & 255) != 255 || (data[data.length - 1] & 255) != 217) return null;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                if (!"JPEG".equalsIgnoreCase(reader.getFormatName())) return null;
                reader.setInput(input, true, true);
                if (reader.getWidth(0) != mini.width || reader.getHeight(0) != mini.height) return null;
                BufferedImage image = reader.read(0);
                if (image == null) return null;
                boolean correct = image.getWidth() == mini.width && image.getHeight() == mini.height;
                image.flush();
                if (!correct) return null;
                return new Preview("AVAILABLE", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(data), mini.width, mini.height);
            } finally { reader.dispose(); }
        } catch (java.io.IOException | RuntimeException e) { return null; }
    }
}
