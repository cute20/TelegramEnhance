package io.github.telegramenhance.library;

import io.github.telegramenhance.library.ResourceModels.Detail;
import io.github.telegramenhance.library.ResourceModels.CommentObservation;
import io.github.telegramenhance.library.ResourceModels.Media;
import io.github.telegramenhance.library.ResourceModels.MessageRef;
import io.github.telegramenhance.library.ResourceModels.Page;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.library.LibraryModels.CatalogEntry;
import io.github.telegramenhance.library.LibraryModels.CatalogPage;
import io.github.telegramenhance.library.LibraryModels.Channel;
import io.github.telegramenhance.library.LibraryModels.MediaPage;
import io.github.telegramenhance.storage.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Persists metadata, not media files. Post text and file names in this database are not encrypted. */
@Repository
public final class ResourceRepository {
    private static final Set<String> COVERAGE = Set.of("PARTIAL", "COMPLETE", "UNAVAILABLE");
    private static final Set<String> MEDIA_TYPES = Set.of("VIDEO", "PHOTO", "AUDIO", "DOCUMENT", "OTHER");
    private final Database database;
    private final JsonMapper json = JsonMapper.builder().build();

    public ResourceRepository(Database database) { this.database = database; }

    public record PreviewSource(Post post, long updatedAt) { }
    public record MediaPreviewSource(Post post, Media media, long postUpdatedAt, long scanUpdatedAt) { }

    public boolean containsPost(String accountId, String key) {
        requireText(accountId, "账号标识无效"); requireText(key, "主帖标识无效");
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM resource_posts WHERE account_id=? AND post_key=? LIMIT 1")) {
                statement.setString(1, accountId); statement.setString(2, key);
                try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
            }
        });
    }

    /** A single indexed comment-media row, scoped by account and its owning resource post. */
    public MediaPreviewSource mediaPreviewSource(String accountId, String key, String mediaKey) {
        requireText(accountId, "账号标识无效"); requireText(key, "主帖标识无效"); requireText(mediaKey, "媒体标识无效");
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT p.post_key,p.account_id,p.post_json,p.updated_at,
                           m.media_key,m.chat_id,m.message_id,m.media_json,s.updated_at
                    FROM resource_posts p JOIN resource_comment_media m ON m.post_key=p.post_key
                    JOIN resource_comment_messages c ON c.post_key=m.post_key
                        AND c.chat_id=m.chat_id AND c.message_id=m.message_id
                    JOIN resource_scan_state s ON s.post_key=p.post_key
                    WHERE p.account_id=? AND p.post_key=? AND m.media_key=?
                      AND json_extract(m.media_json,'$.source')='COMMENT'
                      AND json_extract(m.media_json,'$.type') IN ('PHOTO','VIDEO') LIMIT 1
                    """)) {
                statement.setString(1, accountId); statement.setString(2, key); statement.setString(3, mediaKey);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw invalid("当前账号的评论图片或视频记录不存在");
                    Post post = decode(rows.getString(3), Post.class);
                    Media media = decode(rows.getString(8), Media.class);
                    if (post == null || !rows.getString(1).equals(post.key()) || !rows.getString(2).equals(post.accountId())
                            || media == null || !rows.getString(5).equals(media.key()) || media.message() == null
                            || !rows.getString(6).equals(media.message().chatId()) || !rows.getString(7).equals(media.message().messageId())) {
                        throw new IllegalStateException("本机评论媒体身份不一致，无法读取预览");
                    }
                    if (!present(post.discussionChatId()) || !present(post.discussionRootMessageId())
                            || post.channelId().equals(post.discussionChatId()) || !post.discussionChatId().equals(media.message().chatId())
                            || !"COMMENT".equals(media.source()) || !("PHOTO".equals(media.type()) || "VIDEO".equals(media.type()))) {
                        throw invalid("该媒体不属于已索引的评论图片或视频范围");
                    }
                    Set<String> roots = identities(post.discussionRoots());
                    roots.add(identity(post.discussionChatId(), post.discussionRootMessageId()));
                    if (roots.contains(identity(media.message())) || identities(post.mainMessages()).contains(identity(media.message()))) {
                        throw invalid("主帖或讨论根消息不能作为评论媒体预览");
                    }
                    return new MediaPreviewSource(post, media, rows.getLong(4), rows.getLong(9));
                }
            }
        });
    }

    /** Reads exactly one account-scoped immutable snapshot; never enumerates comment media. */
    public PreviewSource previewSource(String accountId, String key) {
        requireText(accountId, "账号标识无效"); requireText(key, "主帖标识无效");
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT post_key,account_id,post_json,updated_at FROM resource_posts
                    WHERE account_id=? AND post_key=? LIMIT 1
                    """)) {
                statement.setString(1, accountId); statement.setString(2, key);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw invalid("当前账号的主帖记录不存在");
                    Post post = decode(rows.getString(3), Post.class);
                    if (post == null || !rows.getString(1).equals(post.key()) || !rows.getString(2).equals(post.accountId())) {
                        throw new IllegalStateException("本机主帖身份不一致，无法读取预览");
                    }
                    return new PreviewSource(post, rows.getLong(4));
                }
            }
        });
    }

    public void savePost(Post post) {
        savePostAndThen(post, (connection, created) -> null);
    }

    @FunctionalInterface
    interface AfterSave<T> { T run(Connection connection, boolean created) throws SQLException; }

    <T> T savePostAndThen(Post post, AfterSave<T> afterSave) {
        validatePost(post);
        return database.write(connection -> {
            Post previous = findPost(connection, post.key());
            if (previous != null && (!previous.accountId().equals(post.accountId())
                    || !previous.channelId().equals(post.channelId())
                    || (!previous.mainMessageId().equals(post.mainMessageId()) && !sameAlbum(previous, post)))) {
                throw invalid("主帖身份与已有记录不一致");
            }
            Post saved = reconcileRefresh(previous, post);
            String snapshot = encode(saved);
            boolean reset = previous != null && saved.canReadComments() && !sameThread(previous, saved);
            if (reset) {
                // Reset only this post's old associations when Telegram changes its linked thread.
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM resource_comment_messages WHERE post_key=?")) {
                    delete.setString(1, post.key());
                    delete.executeUpdate();
                }
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM resource_scan_state WHERE post_key=?")) {
                    delete.setString(1, post.key());
                    delete.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO resource_posts(post_key, account_id, channel_id, main_message_id, post_json, updated_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(post_key)
                DO UPDATE SET main_message_id=excluded.main_message_id, post_json=excluded.post_json, updated_at=excluded.updated_at
                """)) {
                statement.setString(1, post.key());
                statement.setString(2, post.accountId());
                statement.setString(3, post.channelId());
                statement.setString(4, saved.mainMessageId());
                statement.setString(5, snapshot);
                statement.setLong(6, Instant.now().toEpochMilli());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO resource_scan_state(post_key, next_cursor, exhausted, coverage, warnings_json, message_count, updated_at)
                VALUES (?, NULL, 0, ?, ?, 0, ?) ON CONFLICT(post_key) DO NOTHING
                """)) {
                statement.setString(1, post.key());
                statement.setString(2, post.canReadComments() ? "PARTIAL" : "UNAVAILABLE");
                statement.setString(3, encode(List.of()));
                statement.setLong(4, Instant.now().toEpochMilli());
                statement.executeUpdate();
            }
            if (saved.canReadComments()) {
                // Newly discovered album roots are not comments. Root-set changes do not rebind
                // an otherwise identical thread or discard unrelated comments and its checkpoint.
                Set<String> rootIds = identities(saved.discussionRoots());
                if (present(saved.discussionRootMessageId())) rootIds.add(identity(saved.discussionChatId(), saved.discussionRootMessageId()));
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM resource_comment_messages WHERE post_key=? AND chat_id=? AND message_id=?")) {
                    for (String rootId : rootIds) {
                        int separator = rootId.indexOf(':');
                        delete.setString(1, saved.key()); delete.setString(2, rootId.substring(0, separator));
                        delete.setString(3, rootId.substring(separator + 1)); delete.addBatch();
                    }
                    delete.executeBatch();
                }
                try (PreparedStatement count = connection.prepareStatement("""
                        UPDATE resource_scan_state SET message_count=(SELECT COUNT(*) FROM resource_comment_messages WHERE post_key=?)
                        WHERE post_key=?
                        """)) {
                    count.setString(1, saved.key()); count.setString(2, saved.key()); count.executeUpdate();
                }
            }
            projectPost(connection, saved);
            return afterSave.run(connection, previous == null);
        });
    }

    /** All enumerated message identities, media, and the next cursor commit together. */
    public void savePage(Page page) {
        validatePage(page);
        List<String> snapshots = page.media().stream().map(this::encode).toList();
        String warnings = encode(page.scanWarnings());
        List<String> observations = page.observations().stream().map(this::encode).toList();
        database.write(connection -> {
            Post post = findPost(connection, page.postKey());
            if (post == null) throw invalid("请先读取并保存主帖");
            Set<String> roots = identities(post.discussionRoots());
            if (present(post.discussionRootMessageId())) roots.add(identity(post.discussionChatId(), post.discussionRootMessageId()));
            for (MessageRef message : page.messages()) {
                if (!Objects.equals(post.discussionChatId(), message.chatId()) || roots.contains(identity(message))) {
                    throw invalid("评论页包含不属于该线程的消息或根消息");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO resource_comment_messages(post_key, chat_id, message_id, link) VALUES (?, ?, ?, ?)
                ON CONFLICT(post_key, chat_id, message_id) DO UPDATE SET link=excluded.link
                """)) {
                for (MessageRef message : page.messages()) {
                    statement.setString(1, page.postKey());
                    statement.setString(2, message.chatId());
                    statement.setString(3, message.messageId());
                    statement.setString(4, message.link());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO resource_comment_media(post_key, media_key, chat_id, message_id, media_json) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(post_key, media_key) DO UPDATE SET media_json=excluded.media_json
                WHERE resource_comment_media.chat_id=excluded.chat_id AND resource_comment_media.message_id=excluded.message_id
                """)) {
                for (int i = 0; i < page.media().size(); i++) {
                    Media media = page.media().get(i);
                    statement.setString(1, page.postKey());
                    statement.setString(2, media.key());
                    statement.setString(3, media.message().chatId());
                    statement.setString(4, media.message().messageId());
                    statement.setString(5, snapshots.get(i));
                    statement.addBatch();
                }
                for (int affected : statement.executeBatch()) {
                    if (affected == 0) throw invalid("媒体标识与已有消息身份不一致");
                }
            }
            // A complete observation replaces only that message's media snapshot. Unseen and
            // incomplete messages retain previous media; existing keys keep their row positions.
            Map<String, Set<String>> returnedKeys = new LinkedHashMap<>();
            for (Media media : page.media()) returnedKeys.computeIfAbsent(identity(media.message()), ignored -> new HashSet<>()).add(media.key());
            try (PreparedStatement existing = connection.prepareStatement("""
                    SELECT media_key FROM resource_comment_media WHERE post_key=? AND chat_id=? AND message_id=?
                    """);
                 PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM resource_comment_media WHERE post_key=? AND chat_id=? AND message_id=? AND media_key=?
                    """);
                 PreparedStatement observation = connection.prepareStatement("""
                    UPDATE resource_comment_messages SET observation_json=? WHERE post_key=? AND chat_id=? AND message_id=?
                    """)) {
                for (int index = 0; index < page.observations().size(); index++) {
                    CommentObservation observed = page.observations().get(index);
                    MessageRef message = observed.message();
                    if (observed.mediaComplete()) {
                        Set<String> keep = returnedKeys.getOrDefault(identity(message), Set.of());
                        existing.setString(1, page.postKey()); existing.setString(2, message.chatId()); existing.setString(3, message.messageId());
                        List<String> obsolete = new ArrayList<>();
                        try (ResultSet rows = existing.executeQuery()) {
                            while (rows.next()) if (!keep.contains(rows.getString(1))) obsolete.add(rows.getString(1));
                        }
                        for (String key : obsolete) {
                            delete.setString(1, page.postKey()); delete.setString(2, message.chatId());
                            delete.setString(3, message.messageId()); delete.setString(4, key); delete.addBatch();
                        }
                    }
                    observation.setString(1, observations.get(index)); observation.setString(2, page.postKey());
                    observation.setString(3, message.chatId()); observation.setString(4, message.messageId());
                    if (observation.executeUpdate() != 1) throw new IllegalStateException("评论观测对应的消息身份缺失");
                }
                delete.executeBatch();
            }
            String commentCoverage = ReadCoverage.comments(post.canReadComments(), page.exhausted(),
                commentWarnings(connection, page.postKey(), ReadCoverage.commentWarnings(page.scanWarnings())));
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE resource_scan_state SET next_cursor=?, exhausted=?, coverage=?, warnings_json=?,
                    message_count=(SELECT COUNT(*) FROM resource_comment_messages WHERE post_key=?), updated_at=?
                WHERE post_key=?
                """)) {
                statement.setString(1, page.nextCursor());
                statement.setInt(2, page.exhausted() ? 1 : 0);
                statement.setString(3, commentCoverage);
                statement.setString(4, warnings);
                statement.setString(5, page.postKey());
                statement.setLong(6, Instant.now().toEpochMilli());
                statement.setString(7, page.postKey());
                if (statement.executeUpdate() != 1) throw new IllegalStateException("主帖扫描检查点缺失，评论页未保存");
            }
            return null;
        });
    }

    /** limit/offset page through local media; nextCursor is solely the remote history checkpoint. */
    public Detail detail(String key, int limit, int offset) {
        requireText(key, "主帖标识无效");
        validateBounds(limit, offset);
        return database.read(connection -> {
            Post post = findPost(connection, key);
            if (post == null) throw invalid("主帖记录不存在，请重新读取链接");
            List<Media> media = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT media_json FROM resource_comment_media WHERE post_key=? ORDER BY position LIMIT ? OFFSET ?
                """)) {
                statement.setString(1, key);
                statement.setInt(2, limit);
                statement.setInt(3, offset);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) media.add(decode(rows.getString(1), Media.class));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT next_cursor, exhausted, coverage, warnings_json FROM resource_scan_state WHERE post_key=?
                """)) {
                statement.setString(1, key);
                try (ResultSet state = statement.executeQuery()) {
                    if (!state.next()) throw new IllegalStateException("主帖扫描检查点缺失");
                    List<String> scanWarnings = ReadCoverage.commentWarnings(List.of(decode(state.getString(4), String[].class)));
                    List<String> commentWarnings = commentWarnings(connection, key, scanWarnings);
                    LinkedHashSet<String> warnings = new LinkedHashSet<>(post.warnings()); warnings.addAll(commentWarnings);
                    boolean exhausted = post.canReadComments() && state.getBoolean(2);
                    String comments = ReadCoverage.comments(post.canReadComments(), exhausted, commentWarnings);
                    String coverage = ReadCoverage.overall(post.mainCoverage(), comments);
                    long messages = 0, mediaCount = 0;
                    try (PreparedStatement counts = connection.prepareStatement("""
                            SELECT (SELECT COUNT(*) FROM resource_comment_messages WHERE post_key=?),
                                   (SELECT COUNT(*) FROM resource_comment_media WHERE post_key=?)
                            """)) {
                        counts.setString(1, key); counts.setString(2, key);
                        try (ResultSet count = counts.executeQuery()) { if (count.next()) { messages = count.getLong(1); mediaCount = count.getLong(2); } }
                    }
                    return new Detail(post.withCoverage(coverage), media, state.getString(1), exhausted, coverage,
                            List.copyOf(warnings), post.mainCoverage(), comments, messages, mediaCount, commentWarnings, scanWarnings);
                }
            }
        });
    }

    public List<Post> posts(int limit, int offset) {
        validateBounds(limit, offset);
        return readPosts(null, limit, offset);
    }

    /** The live library must always select the currently connected Telegram account. */
    public List<Post> posts(String accountId, int limit, int offset) {
        requireText(accountId, "账号标识无效");
        validateBounds(limit, offset);
        return readPosts(accountId, limit, offset);
    }

    /** Bounded seed identities only: no post JSON decoding or search projection backfill. */
    public List<String> indexedChannelIds(String accountId, int limit) {
        requireText(accountId, "账号标识无效");
        if (limit < 1 || limit > 101) throw invalid("频道候选范围无效，每次需为 1 至 101 条");
        return database.read(connection -> {
            List<String> channels = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT DISTINCT channel_id FROM resource_posts WHERE account_id=? ORDER BY channel_id LIMIT ?")) {
                statement.setString(1, accountId); statement.setInt(2, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) channels.add(rows.getString(1));
                }
            }
            return List.copyOf(channels);
        });
    }

    public List<Channel> channels(String accountId) {
        requireText(accountId, "账号标识无效");
        backfillSearch(accountId);
        return database.read(connection -> {
            List<Channel> channels = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT p.channel_id, COUNT(*), (SELECT latest.post_json FROM resource_posts latest
                        WHERE latest.account_id=p.account_id AND latest.channel_id=p.channel_id
                        ORDER BY latest.updated_at DESC, latest.post_key LIMIT 1)
                    FROM resource_posts p WHERE p.account_id=? GROUP BY p.account_id, p.channel_id ORDER BY p.channel_id
                    """)) {
                statement.setString(1, accountId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Post latest = decode(rows.getString(3), Post.class);
                        channels.add(new Channel(rows.getString(1), latest.channelTitle(), rows.getLong(2)));
                    }
                }
            }
            return List.copyOf(channels);
        });
    }

    public CatalogPage catalog(String accountId, PostSearch.Query query, int limit, int offset) {
        requireText(accountId, "账号标识无效");
        validateBounds(limit, offset);
        if (query == null) throw invalid("缺少资源筛选条件");
        backfillSearch(accountId);
        return database.read(connection -> {
            List<String> parameters = new ArrayList<>(); parameters.add(accountId);
            StringBuilder filter = new StringBuilder(" WHERE p.account_id=?");
            if (!query.channelIds().isEmpty()) {
                filter.append(" AND p.channel_id IN (").append(placeholders(query.channelIds().size())).append(')');
                parameters.addAll(query.channelIds());
            }
            for (String tag : query.tags()) {
                filter.append(" AND EXISTS (SELECT 1 FROM resource_post_tags t WHERE t.post_key=p.post_key AND t.normalized_tag=?)");
                parameters.add(PostSearch.normalize(tag));
            }
            for (String keyword : query.keywords()) {
                filter.append(" AND instr(s.normalized_text,?)>0");
                parameters.add(PostSearch.normalize(keyword));
            }
            String from = " FROM resource_posts p JOIN resource_post_search s ON s.post_key=p.post_key";
            long total;
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*)" + from + filter)) {
                bind(statement, parameters);
                try (ResultSet rows = statement.executeQuery()) { rows.next(); total = rows.getLong(1); }
            }
            long indexedPosts, indexedChannels;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*),COUNT(DISTINCT channel_id) FROM resource_posts WHERE account_id=?")) {
                statement.setString(1, accountId);
                try (ResultSet rows = statement.executeQuery()) { rows.next(); indexedPosts = rows.getLong(1); indexedChannels = rows.getLong(2); }
            }
            List<CatalogEntry> items = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT p.post_key,p.updated_at" + from + filter
                    + " ORDER BY p.updated_at DESC,p.post_key LIMIT ? OFFSET ?")) {
                bind(statement, parameters);
                statement.setInt(parameters.size() + 1, limit); statement.setInt(parameters.size() + 2, offset);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String key = rows.getString(1);
                        // The synchronized Database read keeps these authoritative scope/count reads
                        // in the same snapshot as the selected catalog page.
                        Detail detail = detail(key, 1, 0);
                        List<String> tags = new ArrayList<>();
                        try (PreparedStatement selectTags = connection.prepareStatement(
                                "SELECT normalized_tag FROM resource_post_tags WHERE post_key=? ORDER BY normalized_tag")) {
                            selectTags.setString(1, key);
                            try (ResultSet tagRows = selectTags.executeQuery()) { while (tagRows.next()) tags.add(tagRows.getString(1)); }
                        }
                        items.add(new CatalogEntry(detail.post(), List.copyOf(tags), detail.indexedCommentMessages(),
                            detail.indexedCommentMedia(), rows.getLong(2)));
                    }
                }
            }
            return new CatalogPage(List.copyOf(items), limit, offset, total, indexedPosts, indexedChannels,
                (long) offset + items.size() < total, "INDEXED_ONLY");
        });
    }

    public MediaPage media(String accountId, String key, List<String> types, boolean includeMain, int limit, int offset) {
        requireText(accountId, "账号标识无效"); requireText(key, "主帖标识无效"); validateBounds(limit, offset);
        Set<String> selected = new LinkedHashSet<>();
        if (types != null) for (String type : types) {
            String normalized = type == null ? "" : type.strip().toUpperCase(Locale.ROOT);
            if (!MEDIA_TYPES.contains(normalized)) throw invalid("媒体类型无效");
            selected.add(normalized);
        }
        return database.read(connection -> {
            Post post;
            try (PreparedStatement statement = connection.prepareStatement("SELECT post_json FROM resource_posts WHERE post_key=? AND account_id=?")) {
                statement.setString(1, key); statement.setString(2, accountId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw invalid("当前账号的主帖记录不存在");
                    post = decode(rows.getString(1), Post.class);
                }
            }
            if (!accountId.equals(post.accountId())) throw invalid("当前账号的主帖记录不存在");
            List<Media> main = includeMain ? post.mainMedia() : List.of();
            List<String> parameters = new ArrayList<>(); parameters.add(key); parameters.addAll(selected);
            String filter = " WHERE post_key=?" + (selected.isEmpty() ? "" :
                " AND json_extract(media_json,'$.type') IN (" + placeholders(selected.size()) + ")");
            long comments;
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM resource_comment_media" + filter)) {
                bind(statement, parameters);
                try (ResultSet rows = statement.executeQuery()) { rows.next(); comments = rows.getLong(1); }
            }
            List<Media> items = new ArrayList<>();
            if (offset < main.size()) items.addAll(main.subList(offset, Math.min(main.size(), offset + limit)));
            int remaining = limit - items.size();
            if (remaining > 0) {
                try (PreparedStatement statement = connection.prepareStatement("SELECT media_json FROM resource_comment_media" + filter
                        + " ORDER BY position LIMIT ? OFFSET ?")) {
                    bind(statement, parameters);
                    statement.setInt(parameters.size() + 1, remaining);
                    statement.setInt(parameters.size() + 2, Math.max(0, offset - main.size()));
                    try (ResultSet rows = statement.executeQuery()) { while (rows.next()) items.add(decode(rows.getString(1), Media.class)); }
                }
            }
            long total = main.size() + comments;
            return new MediaPage(List.copyOf(items), limit, offset, total, (long) offset + items.size() < total,
                main.size(), comments, "INDEXED_ONLY");
        });
    }

    private void backfillSearch(String accountId) {
        while (true) {
            int filled = database.write(connection -> {
                List<Post> posts = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT p.post_key,p.post_json FROM resource_posts p WHERE p.account_id=?
                        AND NOT EXISTS (SELECT 1 FROM resource_post_search s WHERE s.post_key=p.post_key)
                        ORDER BY p.post_key LIMIT 100
                        """)) {
                    statement.setString(1, accountId);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            Post post = decode(rows.getString(2), Post.class);
                            if (post == null || !rows.getString(1).equals(post.key()) || !accountId.equals(post.accountId())) {
                                throw new IllegalStateException("本机主帖身份不一致，搜索索引未更新");
                            }
                            posts.add(post);
                        }
                    }
                }
                for (Post post : posts) projectPost(connection, post);
                return posts.size();
            });
            if (filled < 100) return;
        }
    }

    private void projectPost(Connection connection, Post post) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO resource_post_search(post_key,normalized_text) VALUES (?,?)
                ON CONFLICT(post_key) DO UPDATE SET normalized_text=excluded.normalized_text
                """)) {
            statement.setString(1, post.key()); statement.setString(2, PostSearch.normalize(post.text())); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM resource_post_tags WHERE post_key=?")) {
            statement.setString(1, post.key()); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO resource_post_tags(post_key,normalized_tag) VALUES (?,?)")) {
            for (String tag : new LinkedHashSet<>(PostSearch.extractTags(post.text()))) {
                statement.setString(1, post.key()); statement.setString(2, tag); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }
    private static void bind(PreparedStatement statement, List<String> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) statement.setString(index + 1, parameters.get(index));
    }

    private List<Post> readPosts(String accountId, int limit, int offset) {
        return database.read(connection -> {
            List<Post> posts = new ArrayList<>();
            String filter = accountId == null ? "" : " WHERE account_id=?";
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT post_json, (SELECT exhausted FROM resource_scan_state s WHERE s.post_key=resource_posts.post_key), "
                    + "(SELECT warnings_json FROM resource_scan_state s WHERE s.post_key=resource_posts.post_key) FROM resource_posts"
                    + filter + " ORDER BY updated_at DESC, post_key LIMIT ? OFFSET ?")) {
                int parameter = 1;
                if (accountId != null) statement.setString(parameter++, accountId);
                statement.setInt(parameter++, limit);
                statement.setInt(parameter, offset);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Post post = decode(rows.getString(1), Post.class);
                        List<String> scanWarnings = rows.getString(3) == null ? List.of() : ReadCoverage.commentWarnings(List.of(decode(rows.getString(3), String[].class)));
                        List<String> warnings = commentWarnings(connection, post.key(), scanWarnings);
                        String comments = ReadCoverage.comments(post.canReadComments(), rows.getBoolean(2), warnings);
                        posts.add(post.withCoverage(ReadCoverage.overall(post.mainCoverage(), comments)));
                    }
                }
            }
            return List.copyOf(posts);
        });
    }

    private Post findPost(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT post_json FROM resource_posts WHERE post_key=?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? decode(rows.getString(1), Post.class) : null; }
        }
    }

    private List<String> commentWarnings(Connection connection, String postKey, List<String> scanWarnings) throws SQLException {
        LinkedHashSet<String> warnings = new LinkedHashSet<>(scanWarnings);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT chat_id, message_id, observation_json FROM resource_comment_messages
                WHERE post_key=? AND observation_json IS NOT NULL ORDER BY chat_id, message_id
                """)) {
            statement.setString(1, postKey);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    CommentObservation observed = decode(rows.getString(3), CommentObservation.class);
                    if (observed == null || observed.message() == null || !Objects.equals(rows.getString(1), observed.message().chatId())
                            || !Objects.equals(rows.getString(2), observed.message().messageId())) {
                        throw new IllegalStateException("本机评论观测身份无效，请重新读取对应评论");
                    }
                    if (!observed.mediaComplete() && observed.warnings().stream().noneMatch(ResourceRepository::present)) {
                        warnings.add("已保存评论的媒体观测尚不完整，请重新读取对应评论。");
                    }
                    warnings.addAll(observed.warnings());
                }
            }
        }
        return List.copyOf(warnings);
    }

    private void validatePost(Post post) {
        if (post == null) throw invalid("缺少主帖内容");
        requireText(post.key(), "主帖标识无效");
        requireText(post.accountId(), "账号标识无效");
        requireText(post.channelId(), "频道标识无效");
        requireText(post.mainMessageId(), "主帖消息标识无效");
        validateCoverage(post.coverage());
        validateCoverage(post.mainCoverage());
        for (MessageRef message : post.mainMessages()) {
            validateMessage(message);
            if (!post.channelId().equals(message.chatId())) throw invalid("主帖消息的频道身份不一致");
        }
        for (MessageRef root : post.discussionRoots()) {
            validateMessage(root);
            if (!Objects.equals(post.discussionChatId(), root.chatId())) throw invalid("讨论根消息的聊天身份不一致");
        }
        for (Media media : post.mainMedia()) validateMedia(media);
    }

    private void validatePage(Page page) {
        if (page == null) throw invalid("缺少评论页");
        requireText(page.postKey(), "主帖标识无效");
        validateCoverage(page.coverage());
        validateCoverage(page.commentCoverage());
        if ("COMPLETE".equals(page.coverage()) && !page.exhausted()) throw invalid("评论范围尚未结束，不能标为完整");
        if ("COMPLETE".equals(page.commentCoverage()) && !page.exhausted()) throw invalid("评论历史尚未结束，不能标为完整");
        if (page.messageCount() != page.messages().size()) throw invalid("评论页消息计数与身份清单不一致");
        for (MessageRef message : page.messages()) validateMessage(message);
        Set<String> messages = identities(page.messages());
        for (Media media : page.media()) {
            validateMedia(media);
            if (!messages.contains(identity(media.message()))) throw invalid("评论媒体缺少对应的已枚举消息身份");
        }
        Set<String> observedMessages = new HashSet<>();
        for (CommentObservation observed : page.observations()) {
            if (observed == null) throw invalid("评论观测无效");
            validateMessage(observed.message());
            String identity = identity(observed.message());
            if (!messages.contains(identity)) throw invalid("评论观测必须属于本页已枚举消息");
            if (!observedMessages.add(identity)) throw invalid("本页评论观测身份重复");
            if (!observed.mediaComplete() && observed.warnings().stream().noneMatch(ResourceRepository::present)) {
                throw invalid("不完整评论观测必须说明具体警告");
            }
        }
    }

    private void validateMessage(MessageRef message) {
        if (message == null) throw invalid("消息身份无效");
        requireText(message.chatId(), "聊天标识无效");
        requireText(message.messageId(), "消息标识无效");
    }

    private void validateMedia(Media media) {
        if (media == null) throw invalid("媒体记录无效");
        requireText(media.key(), "媒体标识无效");
        validateMessage(media.message());
        if (media.size() < 0) throw invalid("媒体大小无效");
    }

    private boolean sameThread(Post first, Post second) {
        return Objects.equals(first.discussionChatId(), second.discussionChatId())
            && Objects.equals(first.discussionRootMessageId(), second.discussionRootMessageId());
    }

    private Post reconcileRefresh(Post previous, Post fresh) {
        if (previous == null) return fresh;
        List<MessageRef> members = fresh.mainMessages(); List<Media> media = fresh.mainMedia();
        String mainMessageId = fresh.mainMessageId();
        LinkedHashSet<String> warnings = new LinkedHashSet<>(fresh.warnings());
        boolean album = sameAlbum(previous, fresh);
        if ((album || sameSingleMessage(previous, fresh)) && !"COMPLETE".equals(fresh.mainCoverage())) {
            var knownMembers = new LinkedHashMap<String, MessageRef>();
            previous.mainMessages().forEach(m -> knownMembers.put(identity(m), m));
            fresh.mainMessages().forEach(m -> knownMembers.put(identity(m), m));
            var knownMedia = new LinkedHashMap<String, Media>();
            previous.mainMedia().forEach(m -> knownMedia.put(m.key(), m));
            fresh.mainMedia().forEach(m -> knownMedia.put(m.key(), m));
            members = List.copyOf(knownMembers.values()); media = List.copyOf(knownMedia.values());
            mainMessageId = members.stream().map(MessageRef::messageId)
                    .min(java.util.Comparator.comparingLong(Long::parseLong)).orElse(mainMessageId);
            warnings.add(album ? "本次相册未完整确认，已保留此前已知成员；尚未确认远端删除或编辑。"
                : "本次主帖媒体未完整确认，已保留此前已知媒体；尚未确认远端删除或编辑。");
        }
        // A failed/unknown mapping is not evidence that Telegram rebound the thread. Keep its
        // last known identities so a later successful refresh can resume the original checkpoint.
        Post mapping = fresh.canReadComments() ? fresh : previous;
        return new Post(fresh.key(), fresh.accountId(), fresh.channelId(), fresh.channelTitle(), mainMessageId,
                fresh.title(), fresh.text(), fresh.albumId(), members, media, mapping.discussionChatId(),
                mapping.discussionRootMessageId(), mapping.discussionRoots(), fresh.canReadComments(),
                fresh.coverage(), List.copyOf(warnings), fresh.albumEvidence(), fresh.mainCoverage());
    }

    private boolean sameAlbum(Post first, Post second) {
        return present(first.albumId()) && !"0".equals(first.albumId()) && first.albumId().equals(second.albumId());
    }

    private boolean sameSingleMessage(Post first, Post second) {
        return (!present(first.albumId()) || "0".equals(first.albumId()))
            && (!present(second.albumId()) || "0".equals(second.albumId()))
            && first.accountId().equals(second.accountId()) && first.channelId().equals(second.channelId())
            && first.mainMessageId().equals(second.mainMessageId());
    }

    private static Set<String> identities(List<MessageRef> messages) {
        Set<String> result = new HashSet<>();
        for (MessageRef message : messages) result.add(identity(message));
        return result;
    }
    private static String identity(MessageRef message) { return identity(message.chatId(), message.messageId()); }
    private static String identity(String chat, String message) { return chat + ":" + message; }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static void requireText(String value, String error) { if (!present(value)) throw invalid(error); }
    private static void validateCoverage(String coverage) {
        if (!COVERAGE.contains(coverage == null ? "" : coverage)) throw invalid("资源范围状态无效");
    }
    private static void validateBounds(int limit, int offset) {
        if (limit < 1 || limit > 200 || offset < 0) throw invalid("分页范围无效，每页需为 1 至 200 条");
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (RuntimeException failure) { throw new IllegalStateException("无法序列化资源记录"); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (RuntimeException failure) { throw new IllegalStateException("本机资源记录格式无效，请重新读取主帖"); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
