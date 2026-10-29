package io.github.telegramenhance.library;

import io.github.telegramenhance.library.LibraryModels.*;
import io.github.telegramenhance.telegram.TelegramService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** Browses only the current account's explicitly indexed library. No Telegram enumeration. */
@Service
public final class LibraryCatalogService {
    private static final Set<String> MEDIA_TYPES = Set.of("VIDEO", "PHOTO", "AUDIO", "DOCUMENT", "OTHER");
    private final ResourceRepository repository;
    private final TelegramService telegram;
    public LibraryCatalogService(ResourceRepository repository, TelegramService telegram) {
        this.repository = repository; this.telegram = telegram;
    }
    public List<Channel> channels() { return read(repository::channels); }
    public CatalogPage catalog(List<String> channels, String tags, String keywords, int limit, int offset) {
        var query = PostSearch.parse(channels, tags, keywords);
        return read(account -> repository.catalog(account, query, limit, offset));
    }
    public MediaPage media(String key, List<String> types, boolean includeMain, int limit, int offset) {
        Set<String> selected = new LinkedHashSet<>();
        if (types != null) {
            if (types.size() > 20) throw new IllegalArgumentException("媒体类型筛选项过多，请重新选择");
            for (String type : types) {
                if (type == null || type.isBlank()) continue;
                String normalized = type.strip().toUpperCase(Locale.ROOT);
                if (!MEDIA_TYPES.contains(normalized)) throw new IllegalArgumentException("媒体类型无效，请重新选择");
                selected.add(normalized);
            }
        }
        return read(account -> repository.media(account, key, List.copyOf(selected), includeMain, limit, offset));
    }
    private <T> T read(Function<String, T> action) {
        String account = telegram.accountId();
        T result = action.apply(account);
        if (!account.equals(telegram.accountId())) throw new IllegalArgumentException("Telegram账号已改变，请刷新资源库后重试");
        return result;
    }
}
