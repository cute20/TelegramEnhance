package io.github.telegramenhance.library;

import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import io.github.telegramenhance.library.ChannelImportModels.Candidate;
import io.github.telegramenhance.library.ChannelImportRunModels.RunView;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.ChannelReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Durable bounded batches. SQL callbacks never perform Telegram reads or nested DB transactions. */
@Repository
public final class ChannelImportRunRepository {
    private final Database database;
    private final ResourceRepository posts;
    private final JsonMapper json = JsonMapper.builder().build();

    public ChannelImportRunRepository(Database database, ResourceRepository posts) { this.database = database; this.posts = posts; }

    public RunView state(String account, String channel) {
        State current = current(account, channel); return current == null ? null : current.view();
    }

    State current(String account, String channel) { return database.read(connection -> load(connection, account, channel)); }

    RunView start(String account, String channel, boolean restart, String runId, Long revision, Runnable guard) {
        return database.write(connection -> {
            guard.run();
            State previous = load(connection, account, channel);
            if (previous != null && !restart) return previous.view();
            if (previous != null) {
                expect(previous, runId, revision, false);
                try (var statement = connection.prepareStatement("DELETE FROM channel_import_run_entries WHERE account_id=? AND channel_id=? AND run_id=?")) {
                    statement.setString(1, account); statement.setString(2, channel); statement.setString(3, previous.view().runId()); statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("DELETE FROM channel_import_runs WHERE account_id=? AND channel_id=? AND run_id=? AND revision=?")) {
                    statement.setString(1, account); statement.setString(2, channel); statement.setString(3, runId); statement.setLong(4, revision);
                    if (statement.executeUpdate() != 1) throw conflict();
                }
            }
            long now = Instant.now().toEpochMilli();
            try (var statement = connection.prepareStatement("""
                    INSERT INTO channel_import_runs(account_id,channel_id,run_id,revision,source_cursor,pending_json,pending_offset,
                        pending_next_cursor,pending_exhausted,pending_has_more,status,processed,created,updated,incomplete,
                        exhausted,warning,started_at,updated_at)
                    VALUES (?,?,?,0,NULL,'[]',0,NULL,0,1,'READY',0,0,0,0,0,?,?,?)
                    """)) {
                statement.setString(1, account); statement.setString(2, channel); statement.setString(3, UUID.randomUUID().toString());
                statement.setString(4, null); statement.setLong(5, now); statement.setLong(6, now); statement.executeUpdate();
            }
            guard.run();
            return load(connection, account, channel).view();
        });
    }

    RunView batch(String account, State expected, HistoryPage page, Runnable guard) {
        if (page == null || page.channel() == null || !expected.view().channelId().equals(page.channel().id()) || page.items().size() > 10 || page.limit() < 1 || page.limit() > 10
                || page.exhausted() && page.hasMore()) throw new IllegalArgumentException("频道分页结果无效，检查点未推进");
        for (Candidate candidate : page.items()) validateCandidate(candidate);
        String pending = encode(page.items());
        if (pending.length() > 65_536) throw new IllegalArgumentException("频道分页结果超出本轮范围，检查点未推进");
        boolean advances = ChannelReader.advancesCursor(expected.sourceCursor(), page.nextCursor(), account, expected.view().channelId());
        boolean canContinue = page.hasMore() && advances;
        String next = advances ? page.nextCursor() : expected.sourceCursor();
        String warning = join(expected.warning(), page.warnings());
        if (!page.exhausted() && !canContinue) warning = append(warning, "频道历史未到结尾且未取得可继续推进的游标，本轮已暂停，需核对后重新开始。");
        final String storedWarning = warning;
        return database.write(connection -> {
            guard.run(); State actual = load(connection, account, expected.view().channelId());
            expect(actual, expected.view().runId(), expected.view().revision(), true);
            if (!actual.pending().isEmpty()) throw conflict();
            boolean empty = page.items().isEmpty();
            String status = !empty ? "READY" : page.exhausted() ? "COMPLETE" : canContinue ? "READY" : "BLOCKED";
            State updated = new State(view(actual, status, actual.view().processed(), actual.view().created(), actual.view().updated(), actual.view().incomplete(),
                empty && page.exhausted(), storedWarning), empty ? next : actual.sourceCursor(), page.items(), 0, next, page.exhausted(), canContinue,
                storedWarning, actual.startedAt());
            update(connection, account, actual, updated, pending);
            guard.run();
            return load(connection, account, expected.view().channelId()).view();
        });
    }

    RunView commit(String account, State expected, Post post, Runnable guard) {
        if (post == null || !account.equals(post.accountId()) || !expected.view().channelId().equals(post.channelId())) {
            throw new IllegalArgumentException("频道主帖身份无效，检查点未推进");
        }
        return posts.savePostAndThen(post, (connection, created) -> {
            guard.run(); State actual = load(connection, account, expected.view().channelId());
            expect(actual, expected.view().runId(), expected.view().revision(), true);
            if (actual.offset() >= actual.pending().size()) throw conflict();
            boolean complete;
            try (var statement = connection.prepareStatement("SELECT json_extract(post_json,'$.mainCoverage') FROM resource_posts WHERE post_key=? AND account_id=?")) {
                statement.setString(1, post.key()); statement.setString(2, account);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("频道主帖保存结果不存在");
                    complete = "COMPLETE".equals(rows.getString(1));
                }
            }
            int inserted;
            try (var statement = connection.prepareStatement("""
                    INSERT OR IGNORE INTO channel_import_run_entries(account_id,channel_id,run_id,post_key,main_complete) VALUES (?,?,?,?,?)
                    """)) {
                entryParameters(statement, account, actual, post.key()); statement.setBoolean(5, complete); inserted = statement.executeUpdate();
            }
            long deltaIncomplete = inserted == 1 && !complete ? 1 : 0;
            if (inserted == 0) {
                try (var statement = connection.prepareStatement("SELECT main_complete FROM channel_import_run_entries WHERE account_id=? AND channel_id=? AND run_id=? AND post_key=?")) {
                    entryParameters(statement, account, actual, post.key());
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) throw new IllegalStateException("频道导入去重记录无效");
                        boolean previousComplete = rows.getBoolean(1);
                        deltaIncomplete = previousComplete == complete ? 0 : complete ? -1 : 1;
                    }
                }
                try (var statement = connection.prepareStatement("UPDATE channel_import_run_entries SET main_complete=? WHERE account_id=? AND channel_id=? AND run_id=? AND post_key=?")) {
                    statement.setBoolean(1, complete); statement.setString(2, account); statement.setString(3, actual.view().channelId());
                    statement.setString(4, actual.view().runId()); statement.setString(5, post.key()); statement.executeUpdate();
                }
            }
            boolean last = actual.offset() + 1 == actual.pending().size();
            String status = !last ? "READY" : actual.pendingExhausted() ? "COMPLETE" : actual.pendingHasMore() ? "READY" : "BLOCKED";
            State updated = new State(view(actual, status, actual.view().processed() + inserted,
                actual.view().created() + (inserted == 1 && created ? 1 : 0), actual.view().updated() + (inserted == 1 && !created ? 1 : 0),
                actual.view().incomplete() + deltaIncomplete, last && actual.pendingExhausted(), actual.warning()),
                last ? actual.nextCursor() : actual.sourceCursor(), last ? List.of() : actual.pending(), last ? 0 : actual.offset() + 1,
                last ? null : actual.nextCursor(), last ? false : actual.pendingExhausted(), last ? true : actual.pendingHasMore(),
                actual.warning(), actual.startedAt());
            update(connection, account, actual, updated, encode(updated.pending()));
            guard.run();
            return load(connection, account, expected.view().channelId()).view();
        });
    }

    private RunView view(State current, String status, long processed, long created, long updated, long incomplete, boolean exhausted, String warning) {
        return new RunView(current.view().channelId(), current.view().runId(), current.view().revision() + 1, status,
            processed, created, updated, incomplete, exhausted, displayWarning(warning, incomplete));
    }

    private void update(Connection connection, String account, State previous, State next, String pendingJson) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE channel_import_runs SET revision=?,source_cursor=?,pending_json=?,pending_offset=?,pending_next_cursor=?,
                    pending_exhausted=?,pending_has_more=?,status=?,processed=?,created=?,updated=?,incomplete=?,exhausted=?,warning=?,updated_at=?
                WHERE account_id=? AND channel_id=? AND run_id=? AND revision=?
                """)) {
            statement.setLong(1, next.view().revision()); statement.setString(2, next.sourceCursor()); statement.setString(3, pendingJson);
            statement.setInt(4, next.offset()); statement.setString(5, next.nextCursor()); statement.setBoolean(6, next.pendingExhausted());
            statement.setBoolean(7, next.pendingHasMore()); statement.setString(8, next.view().status()); statement.setLong(9, next.view().processed());
            statement.setLong(10, next.view().created()); statement.setLong(11, next.view().updated()); statement.setLong(12, next.view().incomplete());
            statement.setBoolean(13, next.view().exhausted()); statement.setString(14, next.warning()); statement.setLong(15, Instant.now().toEpochMilli());
            statement.setString(16, account); statement.setString(17, previous.view().channelId()); statement.setString(18, previous.view().runId());
            statement.setLong(19, previous.view().revision()); if (statement.executeUpdate() != 1) throw conflict();
        }
    }

    private State load(Connection connection, String account, String channel) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM channel_import_runs WHERE account_id=? AND channel_id=?")) {
            statement.setString(1, account); statement.setString(2, channel);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                List<Candidate> pending = decode(rows.getString("pending_json")); int offset = rows.getInt("pending_offset");
                long processed = rows.getLong("processed"), created = rows.getLong("created"), updated = rows.getLong("updated"), incomplete = rows.getLong("incomplete");
                if (offset < 0 || !pending.isEmpty() && offset >= pending.size() || pending.isEmpty() && offset != 0 || created + updated != processed || incomplete > processed) {
                    throw new IllegalStateException("频道导入检查点无效，原记录已保留");
                }
                String warning = rows.getString("warning");
                var view = new RunView(channel, rows.getString("run_id"), rows.getLong("revision"), rows.getString("status"), processed,
                    created, updated, incomplete, rows.getBoolean("exhausted"), displayWarning(warning, incomplete));
                return new State(view, rows.getString("source_cursor"), pending, offset, rows.getString("pending_next_cursor"),
                    rows.getBoolean("pending_exhausted"), rows.getBoolean("pending_has_more"), warning, rows.getLong("started_at"));
            }
        }
    }

    static void expect(State state, String runId, Long revision, boolean ready) {
        if (state == null || runId == null || revision == null || !runId.equals(state.view().runId()) || revision != state.view().revision()) throw conflict();
        if (ready && !"READY".equals(state.view().status())) throw new IllegalArgumentException("本轮频道导入已结束或阻断，请刷新进度或重新开始");
    }
    private static void entryParameters(java.sql.PreparedStatement statement, String account, State state, String key) throws SQLException {
        statement.setString(1, account); statement.setString(2, state.view().channelId()); statement.setString(3, state.view().runId()); statement.setString(4, key);
    }
    private String encode(List<Candidate> candidates) {
        try { return json.writeValueAsString(candidates); }
        catch (RuntimeException failure) { throw new IllegalStateException("频道导入批次无法保存"); }
    }
    private List<Candidate> decode(String value) {
        try {
            if (value == null || value.length() > 65_536) throw new IllegalArgumentException();
            var candidates = List.copyOf(Arrays.asList(json.readValue(value, Candidate[].class)));
            if (candidates.size() > 10) throw new IllegalArgumentException(); candidates.forEach(ChannelImportRunRepository::validateCandidate);
            return candidates;
        } catch (RuntimeException failure) { throw new IllegalStateException("频道导入批次无效，原记录已保留"); }
    }
    private static void validateCandidate(Candidate candidate) {
        if (candidate == null || !positive(candidate.messageId()) || candidate.albumId() != null && !integer(candidate.albumId())) throw new IllegalArgumentException("频道候选身份无效，检查点未推进");
    }
    private static boolean integer(String value) {
        try { return value != null && value.length() <= 20 && Long.toString(Long.parseLong(value)).equals(value); }
        catch (NumberFormatException failure) { return false; }
    }
    private static boolean positive(String value) {
        try { return value != null && value.length() <= 19 && Long.parseLong(value) > 0 && Long.toString(Long.parseLong(value)).equals(value); }
        catch (NumberFormatException failure) { return false; }
    }
    private static String join(String previous, List<String> warnings) {
        String result = previous;
        for (String warning : warnings) if (warning != null && !warning.isBlank()) result = append(result, warning);
        return result;
    }
    private static String append(String previous, String warning) {
        if (previous == null || previous.isBlank()) return warning.substring(0, Math.min(4096, warning.length()));
        if (previous.contains(warning)) return previous;
        String joined = previous + "\n" + warning; return joined.substring(0, Math.min(4096, joined.length()));
    }
    private static String displayWarning(String warning, long incomplete) {
        return incomplete == 0 ? warning : append(warning, incomplete + " 条主帖媒体尚未完整核验，已保留已知索引。");
    }
    private static IllegalArgumentException conflict() { return new IllegalArgumentException("频道导入检查点已变化，请读取最新进度后继续"); }

    record State(RunView view, String sourceCursor, List<Candidate> pending, int offset, String nextCursor,
                 boolean pendingExhausted, boolean pendingHasMore, String warning, long startedAt) {
        State { pending = List.copyOf(pending); }
        @Override public String toString() { return "ImportState[revision=" + view.revision() + ", pending=" + pending.size() + "]"; }
    }
}
