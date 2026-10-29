package io.github.telegramenhance.library;

import io.github.telegramenhance.library.ChannelImportModels.*;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.telegram.ChannelReader;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.telegram.TelegramService;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** Explicitly imports one chosen post through the existing bounded serial metadata queue. */
@Service
public final class ChannelImportService {
    private final ResourceService resources;
    private final ResourceRepository repository;
    private final ResourceReader posts;
    private final ChannelReader channels;
    private final TelegramService telegram;

    public ChannelImportService(ResourceService resources, ResourceRepository repository, ResourceReader posts,
            ChannelReader channels, TelegramService telegram) {
        this.resources = resources; this.repository = repository; this.posts = posts;
        this.channels = channels; this.telegram = telegram;
    }

    public CompletableFuture<ManagedChannels> channels() {
        return read(session -> channels.channels());
    }

    public CompletableFuture<Preview> preview(String channelId) {
        requireId(channelId, false);
        return read(session -> {
            Preview preview = channels.preview(channelId);
            if (preview == null || preview.channel() == null || !channelId.equals(preview.channel().id())) {
                throw new IllegalArgumentException("频道预览身份不一致，请重新读取频道");
            }
            return preview;
        });
    }

    public CompletableFuture<ChannelHistoryModels.HistoryPage> historyPage(String channelId, String cursor) {
        requireId(channelId, false);
        return read(session -> {
            var page = channels.historyPage(channelId, cursor);
            if (page == null || page.channel() == null || !channelId.equals(page.channel().id())) {
                throw new IllegalArgumentException("频道历史身份不一致，请重新浏览");
            }
            return page;
        });
    }

    public CompletableFuture<ImportResult> importPost(String channelId, String messageId) {
        requireId(channelId, false); requireId(messageId, true);
        return read(session -> {
            Post post = posts.channelPost(channelId, messageId);
            requireCurrent(session);
            requirePost(post, session, channelId);
            boolean created = !repository.containsPost(session.account(), post.key());
            requireCurrent(session);
            repository.savePost(post);
            requireCurrent(session);
            Post stored = repository.detail(post.key(), 1, 0).post();
            requirePost(stored, session, channelId);
            if (!post.key().equals(stored.key())) throw new IllegalArgumentException("主帖保存身份不一致，请刷新本机索引");
            return new ImportResult(stored, created);
        });
    }

    private <T> CompletableFuture<T> read(Function<Session, T> action) {
        Session session = new Session(telegram.accountId(), telegram.sessionGeneration());
        requireCurrent(session);
        return resources.readWork(() -> {
            requireCurrent(session);
            T result = action.apply(session);
            requireCurrent(session);
            return result;
        });
    }

    private void requireCurrent(Session expected) {
        if (!Objects.equals(expected.account(), telegram.accountId()) || expected.generation() != telegram.sessionGeneration()) {
            throw new IllegalArgumentException("Telegram 账号或会话已变化，请刷新后重试");
        }
    }

    private static void requirePost(Post post, Session session, String channelId) {
        if (post == null || !session.account().equals(post.accountId()) || !channelId.equals(post.channelId())
                || post.key() == null || post.key().isBlank()) {
            throw new IllegalArgumentException("频道主帖身份不一致，未导入该主帖");
        }
    }

    private static void requireId(String value, boolean positive) {
        try {
            if (value == null || value.length() > 20) throw new NumberFormatException();
            long parsed = Long.parseLong(value);
            if ((positive ? parsed <= 0 : parsed == 0) || !Long.toString(parsed).equals(value)) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(positive ? "主帖消息标识无效" : "频道标识无效");
        }
    }

    private record Session(String account, long generation) { }
}
