package io.github.telegramenhance.telegram;

import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.transfer.DownloadModels.FrozenFile;
import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Resolves primary files and reads bounded chunks; never opens TDLib's local.path. */
@Service
public final class DownloadFileReader {
    interface Gateway { <R extends TdApi.Object> CompletableFuture<R> read(long generation, TdApi.Function<R> request); }
    private final Gateway gateway; private final Supplier<String> account; private final LongSupplier generation;
    @org.springframework.beans.factory.annotation.Autowired
    public DownloadFileReader(TelegramService telegram) {
        this(new Gateway() { public <R extends TdApi.Object> CompletableFuture<R> read(long gen, TdApi.Function<R> request) { return telegram.readInSession(gen, request); } }, telegram::accountId, telegram::sessionGeneration);
    }
    DownloadFileReader(Gateway gateway, Supplier<String> account, LongSupplier generation) { this.gateway = gateway; this.account = account; this.generation = generation; }
    public record Resolved(FrozenFile file, int fileId) { }
    public record Status(long downloadedBytes, boolean complete, boolean active, boolean canDownload) { }
    public List<FrozenFile> freeze(Post post, List<Media> selected, long session) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        var files = new ArrayList<FrozenFile>();
        for (var media : selected) {
            check(post.accountId(), session);var frozen=resolve(post,media,session,deadline).file();var current=frozen.media();var ref=current.message();
            if(ref.link()==null||ref.link().isBlank()){
                String link=messageLink(post.accountId(),session,ref,deadline);
                if(link!=null)frozen=new FrozenFile(new Media(current.key(),new MessageRef(ref.chatId(),ref.messageId(),link),current.source(),current.type(),current.fileName(),current.size(),current.caption()),frozen.uniqueId(),frozen.size(),frozen.fileName());
            }files.add(frozen);
        }
        return List.copyOf(files);
    }
    public String messageLink(String accountId,long session,MessageRef ref){return messageLink(accountId,session,ref,deadline());}
    private String messageLink(String accountId,long session,MessageRef ref,long deadline){
        try{
            var result=call(accountId,session,new TdApi.GetMessageLink(Long.parseLong(ref.chatId()),Long.parseLong(ref.messageId()),0,0,"",false,true),deadline);
            if(result==null||result.link==null)return null;var uri=java.net.URI.create(result.link);
            return "https".equals(uri.getScheme())&&java.util.Set.of("t.me","telegram.me").contains(uri.getHost())?result.link:null;
        }catch(RuntimeException unavailable){check(accountId,session);return null;}
    }
    public Resolved resolve(Post post, FrozenFile expected, long session) {
        var fresh = resolve(post, expected.media(), session, System.nanoTime() + TimeUnit.SECONDS.toNanos(45));
        if (!expected.uniqueId().equals(fresh.file().uniqueId()) || expected.size() != fresh.file().size()) throw new IllegalArgumentException("源文件已变化，请重新预览并创建新任务；原任务快照保留");
        return new Resolved(expected, fresh.fileId());
    }
    private Resolved resolve(Post post, Media selected, long session, long deadline) {
        check(post.accountId(), session); var ref = selected.message();
        if (ref == null) throw new IllegalArgumentException("下载媒体身份无效");
        long chatId = Long.parseLong(ref.chatId()), messageId = Long.parseLong(ref.messageId());
        boolean main = "MAIN".equals(selected.source());
        if (main ? !post.channelId().equals(ref.chatId()) || post.mainMessages().stream().noneMatch(m -> same(m, ref))
                : !"COMMENT".equals(selected.source()) || !Objects.equals(post.discussionChatId(), ref.chatId())
                    || Objects.equals(post.discussionRootMessageId(), ref.messageId()) || post.discussionRoots().stream().anyMatch(m -> same(m, ref))) {
            throw new IllegalArgumentException("媒体不属于当前主帖下载范围");
        }
        var chat = call(post.accountId(), session, new TdApi.GetChat(chatId), deadline);
        if (chat == null || chat.id != chatId || chat.hasProtectedContent) throw new IllegalArgumentException("来源不可访问或限制保存内容");
        var message = call(post.accountId(), session, new TdApi.GetMessage(chatId, messageId), deadline);
        var properties = call(post.accountId(), session, new TdApi.GetMessageProperties(chatId, messageId), deadline);
        if (message == null || message.id != messageId || message.chatId != chatId || message.isChannelPost != main
                || properties == null || !properties.canBeSaved || message.selfDestructType != null || message.selfDestructIn > 0 || message.ephemeralContent != null
                || main && !Long.toString(message.mediaAlbumId).equals(post.albumId() == null ? "0" : post.albumId())) {
            throw new IllegalArgumentException("源消息不可保存或身份已变化，请重新核对");
        }
        if (!main && message.topicId instanceof TdApi.MessageTopicThread thread
                && !Long.toString(thread.messageThreadId).equals(post.discussionRootMessageId())
                && post.discussionRoots().stream().noneMatch(root -> root.messageId().equals(Long.toString(thread.messageThreadId)))) {
            throw new IllegalArgumentException("源消息不属于原评论线程");
        }
        var mapped = MediaMapper.map(post.accountId(), message, selected.source(), ref, new ArrayList<>());
        var media = mapped.stream().filter(m -> m.key().equals(selected.key()) && m.type().equals(selected.type())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("源媒体类型已变化，请重新预览"));
        TdApi.File file = switch (message.content) {
            case TdApi.MessagePhoto m -> {
                if (m.isSecret || m.photo == null || m.photo.sizes == null) yield null;
                yield java.util.Arrays.stream(m.photo.sizes).filter(size -> size != null && size.photo != null && size.width > 0 && size.height > 0)
                        .max(Comparator.comparingLong(size -> (long) size.width * size.height)).map(size -> size.photo).orElse(null);
            }
            case TdApi.MessageVideo m -> m.isSecret || m.video == null ? null : m.video.video;
            case TdApi.MessageDocument m -> m.document == null ? null : m.document.document;
            case TdApi.MessageAudio m -> m.audio == null ? null : m.audio.audio;
            case TdApi.MessageVoiceNote m -> m.voiceNote == null ? null : m.voiceNote.voice;
            case TdApi.MessageVideoNote m -> m.videoNote == null ? null : m.videoNote.video;
            case TdApi.MessageAnimation m -> m.isSecret || m.animation == null ? null : m.animation.animation;
            default -> null;
        };
        if (file == null || file.id <= 0 || file.size <= 0 || file.remote == null || file.remote.uniqueId == null || file.remote.uniqueId.isBlank() || file.remote.uniqueId.length() > 1024) {
            throw new IllegalArgumentException("未取得可校验的原文件大小和稳定身份，未开始下载");
        }
        return new Resolved(new FrozenFile(media, file.remote.uniqueId, file.size, media.fileName()), file.id);
    }
    public Status status(String accountId, long session, int id, String uniqueId, long size) {
        var file = checked(accountId, session, id, uniqueId, size);
        if (file.local == null) throw new IllegalArgumentException("下载状态暂不可用");
        return new Status(Math.min(size, Math.max(0, file.local.downloadedSize)), file.local.isDownloadingCompleted,
                file.local.isDownloadingActive, file.local.canBeDownloaded);
    }
    public void start(String accountId, long session, int id, String uniqueId, long size) {
        checked(accountId, session, id, uniqueId, size);
        var result = call(accountId, session, new TdApi.DownloadFile(id, 1, 0, 0, false), deadline());
        verify(result, id, uniqueId, size);
    }
    public byte[] chunk(String accountId, long session, int id, String uniqueId, long size, long offset, int count) {
        if (offset < 0 || count < 1 || count > 1024 * 1024 || offset > size - count) throw new IllegalArgumentException("文件读取范围无效");
        var current = checked(accountId, session, id, uniqueId, size);
        if (current.local == null || !current.local.isDownloadingCompleted || current.local.downloadedSize < size) throw new IllegalArgumentException("客户端尚未确认原文件完整下载");
        var part = call(accountId, session, new TdApi.ReadFilePart(id, offset, count), deadline());
        if (part == null || part.data == null || part.data.length != count) throw new IllegalArgumentException("原文件读取长度不符，未发布目标文件");
        return part.data;
    }
    public boolean release(String accountId, long session, int id, String uniqueId, boolean delete) {
        var current = call(accountId, session, new TdApi.GetFile(id), deadline());
        if (current == null || current.id != id || current.remote == null || !uniqueId.equals(current.remote.uniqueId)) return false;
        call(accountId, session, new TdApi.CancelDownloadFile(id, false), deadline());
        if (!delete) return true;
        call(accountId, session, new TdApi.DeleteFile(id), deadline());
        var after = call(accountId, session, new TdApi.GetFile(id), deadline());
        return after != null && after.id == id && after.remote != null && uniqueId.equals(after.remote.uniqueId) && after.local != null
                && !after.local.isDownloadingActive && !after.local.isDownloadingCompleted && after.local.downloadedSize == 0;
    }
    private TdApi.File checked(String accountId, long session, int id, String uniqueId, long size) {
        var file = call(accountId, session, new TdApi.GetFile(id), deadline()); verify(file, id, uniqueId, size); return file;
    }
    private static void verify(TdApi.File file, int id, String uniqueId, long size) {
        if (file == null || file.id != id || file.remote == null || !uniqueId.equals(file.remote.uniqueId) || file.size != size) throw new IllegalArgumentException("原文件身份或大小已变化，下载已暂停");
    }
    public void check(String expectedAccount, long session) {
        if (Thread.currentThread().isInterrupted() || !Objects.equals(expectedAccount, account.get()) || session != generation.getAsLong()) throw new IllegalArgumentException("账号或会话已变化，下载已暂停");
    }
    private static boolean same(MessageRef a, MessageRef b) { return a.chatId().equals(b.chatId()) && a.messageId().equals(b.messageId()); }
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(16); }
    private <R extends TdApi.Object> R call(String accountId, long session, TdApi.Function<R> request, long deadline) {
        check(accountId, session); long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IllegalArgumentException("下载准备超时，请核对任务后重试");
        try { R result = gateway.read(session, request).get(Math.min(remaining, TimeUnit.SECONDS.toNanos(16)), TimeUnit.NANOSECONDS); check(accountId, session); return result; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalArgumentException("下载已中断，可在任务中心继续"); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            Throwable reason=failure;while(reason.getCause()!=null)reason=reason.getCause();
            if(request instanceof TdApi.ReadFilePart&&reason instanceof it.tdlight.client.TelegramError nativeError&&nativeError.getErrorCode()==400&&"File is not inside the cache".equals(nativeError.getErrorMessage()))
                throw new IllegalArgumentException("该文件仍引用上传输入副本，暂时无法回读；请先核对对应上传与归档，再继续下载");
            throw new IllegalArgumentException("Telegram 文件读取未完成，请检查连接后继续任务");
        }
    }
}
