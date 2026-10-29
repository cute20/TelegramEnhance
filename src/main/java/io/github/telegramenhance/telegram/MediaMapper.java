package io.github.telegramenhance.telegram;

import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.library.ResourceModels.MessageRef;
import it.tdlight.jni.TdApi;
import java.util.List;
import java.util.Locale;

final class MediaMapper {
    static String text(TdApi.Message message) {
        if (message == null || message.content == null) return "";
        TdApi.FormattedText value = switch (message.content) {
            case TdApi.MessageText m -> m.text;
            case TdApi.MessageVideo m -> m.caption;
            case TdApi.MessagePhoto m -> m.caption;
            case TdApi.MessageAudio m -> m.caption;
            case TdApi.MessageDocument m -> m.caption;
            case TdApi.MessageAnimation m -> m.caption;
            case TdApi.MessageVoiceNote m -> m.caption;
            default -> null;
        };
        return value == null || value.text == null ? "" : value.text;
    }
    static List<Media> map(String account, TdApi.Message message, String source, MessageRef ref, List<String> warnings) {
        if (message == null || message.content == null) return incomplete(warnings, "一条消息缺少内容，媒体清单不完整。");
        String type; String name; TdApi.File file;
        switch (message.content) {
            case TdApi.MessageText m -> {
                if (m.text == null || m.text.text == null) return incomplete(warnings, "一条文字消息缺少内容结构，媒体清单不完整。");
                return List.of();
            }
            case TdApi.MessageVideo m -> {
                if (m.video == null) return missingFile(warnings);
                type = "VIDEO"; name = m.video.fileName; file = m.video.video;
            }
            case TdApi.MessagePhoto m -> {
                if (m.photo == null || m.photo.sizes == null || m.photo.sizes.length == 0) return incomplete(warnings, "一条图片消息没有可读取的尺寸，媒体清单不完整。");
                TdApi.PhotoSize size = null;
                for (var candidate : m.photo.sizes) {
                    if (candidate == null || candidate.width <= 0 || candidate.height <= 0 || candidate.photo == null) {
                        warnings.add("一条图片消息包含缺失或无效的尺寸信息，媒体清单不完整。");
                        continue;
                    }
                    if (size == null || (long) candidate.width * candidate.height > (long) size.width * size.height) size = candidate;
                }
                if (size == null) return incomplete(warnings, "一条图片消息没有可读取的尺寸文件，媒体清单不完整。");
                type = "PHOTO"; name = "图片_" + message.id + ".jpg"; file = size.photo;
                if (m.video != null) warnings.add("动态图片的附加视频尚未展开，媒体清单不完整。");
            }
            case TdApi.MessageAudio m -> {
                if (m.audio == null) return missingFile(warnings);
                type = "AUDIO"; name = m.audio.fileName; file = m.audio.audio;
            }
            case TdApi.MessageVoiceNote m -> {
                if (m.voiceNote == null) return missingFile(warnings);
                type = "AUDIO"; name = "语音_" + message.id + ".ogg"; file = m.voiceNote.voice;
            }
            case TdApi.MessageVideoNote m -> {
                if (m.videoNote == null) return missingFile(warnings);
                type = "VIDEO"; name = "圆形视频_" + message.id + ".mp4"; file = m.videoNote.video;
            }
            case TdApi.MessageDocument m -> {
                if (m.document == null) return missingFile(warnings);
                type = videoDocument(m.document.fileName, m.document.mimeType) ? "VIDEO" : "DOCUMENT"; name = m.document.fileName; file = m.document.document;
            }
            case TdApi.MessageAnimation m -> {
                if (m.animation == null) return missingFile(warnings);
                type = "OTHER"; name = m.animation.fileName; file = m.animation.animation;
            }
            default -> {
                // Only supported media and ordinary text prove an authoritative (possibly empty) snapshot.
                return incomplete(warnings, "存在本阶段无法展开的消息类型，媒体清单不完整。");
            }
        }
        if (file == null) return missingFile(warnings);
        if (file.size < 0) warnings.add("一条媒体消息的文件大小信息无效，媒体清单不完整。");
        String key = account + ":" + ref.chatId() + ":" + ref.messageId() + ":0";
        return List.of(new Media(key, ref, source, type, name == null || name.isBlank() ? "媒体_" + message.id : name,
                Math.max(0, file.size), text(message)));
    }
    private static List<Media> missingFile(List<String> warnings) { return incomplete(warnings, "一条媒体消息缺少文件信息，媒体清单不完整。"); }
    private static List<Media> incomplete(List<String> warnings, String warning) { warnings.add(warning); return List.of(); }
    private static boolean videoDocument(String name, String mime) {
        return (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/")) || (name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|mov|avi|webm|m4v|ts|wmv)$"));
    }
}
