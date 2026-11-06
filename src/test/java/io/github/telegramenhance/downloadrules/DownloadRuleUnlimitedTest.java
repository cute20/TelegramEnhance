package io.github.telegramenhance.downloadrules;

import static io.github.telegramenhance.downloadrules.DownloadRuleRepositoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.downloadrules.DownloadRuleModels.Rule;
import io.github.telegramenhance.downloadrules.DownloadRuleModels.Summary;
import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import io.github.telegramenhance.library.ChannelImportModels.Candidate;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.DownloadFileStore;
import io.github.telegramenhance.transfer.DownloadPaths;
import io.github.telegramenhance.transfer.DownloadRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DownloadRuleUnlimitedTest {
    @TempDir Path directory;

    @Test void normalAndUnreadableCandidatesBothAdvancePastTenThousandStoredPosts() {
        try (var f = new Fixture()) {
            f.seedProcessedPosts(10_000);
            assertEquals(10_000, f.count("download_rule_posts"));
            assertEquals(10_000, f.repo.scan(f.rule).scanned());

            var page = new HistoryPage(CHANNEL,
                    List.of(new Candidate("200", null, "next post"), new Candidate("100", null, "unavailable post")),
                    "CHANNEL_PAGE", 10, cursor(80), false, true, List.of());
            var scan = f.repo.saveHistoryPage(f.rule, f.repo.scan(f.rule), page, 10, GUARD);
            scan = f.repo.observePost(f.rule, scan, post(200, true, true), true, 11, GUARD);
            assertEquals(10_001, scan.scanned());
            assertEquals(1, scan.offset());
            assertNotNull(scan.pending());
            assertFalse(scan.historyDone());
            assertEquals("MAIN", f.repo.getPost(f.rule, post(200, true, true).key()).state());

            scan = f.repo.observeCandidateError(f.rule, scan, "temporarily inaccessible", 12, GUARD);
            assertEquals(10_002, f.count("download_rule_posts"));
            assertEquals(10_002, scan.scanned());
            assertEquals(cursor(80), scan.cursor());
            assertNull(scan.pending());
            assertEquals(0, scan.offset());
            assertFalse(scan.historyDone(), "Crossing an old cap is not proof of Telegram EOF");
            assertEquals("HISTORY", scan.phase());
            var missing = f.repo.getPost(f.rule, post(100, true, true).key());
            assertEquals("ERROR", missing.state());
            assertNull(missing.post());
            assertEquals("temporarily inaccessible", missing.error());
            assertEquals(1, f.repo.summary(f.rule).errors());
            f.assertForeignKeys();
        }
    }

    @Test void anotherMediaClaimAfterOneHundredThousandIsAtomicAndRereadingDoesNotDuplicateIt() throws Exception {
        try (var f = new Fixture()) {
            var state = f.comments();
            var first = f.repo.commitPage(f.rule, state, "700", false,
                    List.of(file(701, "original-success", "already saved")), f.root, 5, GUARD);
            var original = f.downloads.task("1", first.job().taskId());
            f.store.write(original, original.items().getFirst(), (offset, count) -> new byte[count], GUARD);
            f.downloads.finishState("1", original.id(), "COMPLETED", null);
            var saved = f.downloads.task("1", original.id());
            var savedItem = saved.items().getFirst();
            Path savedFile = f.store.directory(saved).resolve(savedItem.relativeFile());
            assertEquals("COMPLETED", savedItem.state());
            assertEquals(1, f.count("download_history"));

            // The other claims belong to real, manually taken-over paused tasks, each with
            // at most 100 ordinary items. Their frozen JSON, keys and foreign keys agree.
            f.seedPausedClaims(99_999, post(100, true, true));
            assertEquals(100_000, f.count("download_items"));
            assertEquals(100_000, f.count("download_rule_media"));
            assertEquals(100_000, f.repo.summary(f.rule).media());
            long taskCount = f.count("download_tasks");

            var freshFile = file(600, "new-after-100000", "new media");
            var committed = f.repo.commitPage(f.rule, first.post(), "600", false,
                    List.of(freshFile), f.root, 20, GUARD);
            assertEquals(1, committed.createdMedia());
            assertNotNull(committed.job());
            assertEquals(100_001, f.count("download_rule_media"));
            assertEquals(100_001, f.repo.summary(f.rule).media());
            assertEquals(taskCount + 1, f.count("download_tasks"));
            assertEquals("600", committed.post().cursor());
            assertEquals("COMMENTS", committed.post().state());
            assertEquals(List.of(freshFile), f.downloads.task("1", committed.job().taskId()).items().stream().map(item -> item.file()).toList());

            var reset = f.repo.resetPost(f.rule, committed.post(), post(100, true, true), true, 21, GUARD);
            reset = f.repo.commitPage(f.rule, reset, null, false, List.of(), f.root, 22, GUARD).post();
            var repeated = f.repo.commitPage(f.rule, reset, "600", true,
                    List.of(file(600, "new-after-100000", "edited caption")), f.root, 23, GUARD);
            assertEquals(0, repeated.createdMedia());
            assertNull(repeated.job());
            assertEquals("DONE", repeated.post().state());
            assertEquals(100_001, f.count("download_rule_media"));
            assertEquals(100_001, f.count("download_items"));
            assertEquals(taskCount + 1, f.count("download_tasks"));
            assertEquals(saved, f.downloads.task("1", saved.id()));
            assertEquals(1, f.count("download_history"));
            assertEquals(1, f.count("download_completion_log"));
            assertArrayEquals(new byte[37], Files.readAllBytes(savedFile));
            assertEquals(freshFile, f.downloads.task("1", committed.job().taskId()).items().getFirst().file());
            f.assertForeignKeys();
        }
    }

    @Test void theTwentyFirstRuleCanBeCreatedAndAllAccountRulesRemainListed() {
        try (var f = new Fixture()) {
            var expected = new HashSet<String>();
            expected.add(f.rule.id());
            Rule newest = null;
            for (int n = 2; n <= 21; n++) {
                newest = f.repo.create("1", UUID.randomUUID().toString(), "rule-" + n,
                        input("rule " + n, f.root), CHANNEL, n + 10, GUARD);
                assertTrue(expected.add(newest.id()));
            }
            var foreign = f.repo.create("2", UUID.randomUUID().toString(), "foreign",
                    input("another account", f.root), CHANNEL, 100, GUARD);
            var listing = f.repo.list("1");
            assertEquals(21, listing.size());
            assertEquals(expected, new HashSet<>(listing.stream().map(Rule::id).toList()));
            assertEquals(newest.id(), listing.getFirst().id());
            assertTrue(listing.stream().allMatch(rule -> "1".equals(rule.accountId())));
            assertEquals(List.of(foreign), f.repo.list("2"));
            assertEquals(22, f.count("download_rules"));
            f.assertForeignKeys();
        }
    }

    @Test void accumulatedPostAndCommentPageCountersAdvanceBeyondIntegerRange() {
        try (var f = new Fixture()) {
            var state = f.comments();
            String postKey = state.key();
            f.db.write(c -> {
                sql(c, "UPDATE download_rule_scans SET scanned=? WHERE rule_id=? AND version=?", Integer.MAX_VALUE, f.rule.id(), f.rule.version());
                sql(c, "UPDATE download_rule_posts SET pages=? WHERE rule_id=? AND version=? AND post_key=?", Integer.MAX_VALUE, f.rule.id(), f.rule.version(), postKey);
                return null;
            });
            state = f.repo.getPost(f.rule, state.key());
            var finished = f.repo.commitPage(f.rule, state, null, true, List.of(), f.root, 6, GUARD);
            assertEquals(2_147_483_648L, finished.post().pages());

            var scan = f.repo.saveHistoryPage(f.rule, f.repo.scan(f.rule),
                    new HistoryPage(CHANNEL, List.of(new Candidate("90", null, "new post")),
                            "CHANNEL_PAGE", 10, cursor(80), false, true, List.of()), 7, GUARD);
            scan = f.repo.observePost(f.rule, scan, post(90, true, true), false, 8, GUARD);
            assertEquals(2_147_483_648L, scan.scanned());
            var summary = f.repo.summary(f.rule);
            assertEquals(2_147_483_648L, summary.scanned());
            assertEquals(2_147_483_648L, summary.commentPages());
            Summary decoded = f.json.readValue(f.json.writeValueAsString(summary), Summary.class);
            assertEquals(summary, decoded);
            f.assertForeignKeys();
        }
    }

    private final class Fixture implements AutoCloseable {
        final AppPaths paths = new AppPaths(directory.resolve("data"));
        final Database db = new Database(paths);
        final DownloadRepository downloads = new DownloadRepository(db);
        final DownloadRuleRepository repo = new DownloadRuleRepository(db, downloads);
        final DownloadFileStore store = new DownloadFileStore(new DownloadPaths(paths), downloads);
        final JsonMapper json = JsonMapper.builder().build();
        final Path root = directory.resolve("out").toAbsolutePath();
        final Rule rule;

        Fixture() {
            var saved = repo.create("1", UUID.randomUUID().toString(), "fixture", input("rule", root), CHANNEL, 1, GUARD);
            rule = repo.toggle("1", saved.id(), saved.revision(), true, 2, GUARD);
        }
        DownloadRuleModels.PostState comments() {
            var value = post(100, true, true);
            var scan = repo.saveHistoryPage(rule, repo.scan(rule),
                    new HistoryPage(CHANNEL, List.of(new Candidate("100", null, "post")),
                            "CHANNEL_PAGE", 10, null, true, false, List.of()), 3, GUARD);
            repo.observePost(rule, scan, value, true, 4, GUARD);
            return repo.commitPage(rule, repo.getPost(rule, value.key()), null, false, List.of(), root, 4, GUARD).post();
        }
        void seedProcessedPosts(int count) {
            db.write(c -> {
                sql(c, """
                    WITH RECURSIVE numbers(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM numbers WHERE n<?)
                    INSERT INTO download_rule_posts(rule_id,version,post_key,message_id,post_json,state,cursor,pages,matched,next_review_at,error,revision)
                    SELECT ?,?, '1:-1001:'||(30000+n),CAST(30000+n AS TEXT),
                      json_set(?,'$.key','1:-1001:'||(30000+n),'$.mainMessageId',CAST(30000+n AS TEXT),
                        '$.mainMessages[0].messageId',CAST(30000+n AS TEXT),
                        '$.mainMessages[0].link','https://t.me/synthetic/'||(30000+n)),
                      'DONE',NULL,1,1,300004,NULL,0 FROM numbers
                    """, count, rule.id(), rule.version(), json.writeValueAsString(post(30001, true, true)));
                sql(c, "UPDATE download_rule_scans SET scanned=?,cursor=? WHERE rule_id=? AND version=?",
                        count, cursor(30000), rule.id(), rule.version());
                return null;
            });
        }
        void seedPausedClaims(int count, Post source) {
            int taskCount = (count + 99) / 100;
            db.write(c -> {
                sql(c, """
                    WITH RECURSIVE numbers(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM numbers WHERE n<?)
                    INSERT INTO download_tasks(task_id,account_id,operation_id,fingerprint,post_key,post_json,target_root,relative_directory,
                      state,pause_requested,error_message,created_at,updated_at,duplicate_policy,skip_content_duplicates)
                    SELECT printf('10000000-0000-4000-8000-%012d',n),'1',printf('40000000-0000-4000-8000-%012d',n),
                      'seed-'||n,?,?,?,?,'PAUSED',1,NULL,6,6,'HISTORY_SKIP_V1',0 FROM numbers
                    """, taskCount, source.key(), json.writeValueAsString(source), root.toString(), DownloadPaths.directory(source));
                sql(c, """
                    INSERT INTO download_rule_tasks(task_id,rule_id,account_id,rule_version,snapshot_json,manual,held,settled_at,created_at)
                    SELECT task_id,?,'1',?,?,1,0,6,6 FROM download_tasks WHERE task_id LIKE '10000000-%' AND fingerprint GLOB 'seed-[0-9]*'
                    """, rule.id(), rule.version(), json.writeValueAsString(rule.input()));
                sql(c, """
                    WITH RECURSIVE numbers(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM numbers WHERE n<?)
                    INSERT INTO download_items(item_id,task_id,ordinal,media_key,spec_json,unique_id,size,relative_file,owner_token,
                      state,downloaded_bytes,written_bytes,sha256,error_message,history_id)
                    SELECT printf('20000000-0000-4000-8000-%012d',n),printf('10000000-0000-4000-8000-%012d',((n-1)/100)+1),
                      (n-1)%100,'1:-2001:'||(1000000+n)||':0',
                      json_set(?,'$.media.key','1:-2001:'||(1000000+n)||':0',
                        '$.media.message.messageId',CAST(1000000+n AS TEXT),'$.media.message.link','https://t.me/synthetic/'||(1000000+n),
                        '$.media.fileName','seed-'||n||'.bin','$.fileName','seed-'||n||'.bin','$.uniqueId','seed-unique-'||n),
                      'seed-unique-'||n,37,'seed-'||n||'.bin',printf('30000000-0000-4000-8000-%012d',n),
                      'QUEUED',0,0,NULL,NULL,NULL FROM numbers
                    """, count, json.writeValueAsString(file(1000001, "seed-unique-1", "seed")));
                sql(c, """
                    INSERT INTO download_rule_media(rule_id,version,post_key,media_key,unique_id,size,task_id,item_id,created_at)
                    SELECT ?,?,?,media_key,unique_id,size,task_id,item_id,6 FROM download_items WHERE item_id LIKE '20000000-%' AND unique_id LIKE 'seed-unique-%'
                    """, rule.id(), rule.version(), source.key());
                return null;
            });
            var first = downloads.task("1", "10000000-0000-4000-8000-000000000001");
            var last = downloads.task("1", "10000000-0000-4000-8000-" + String.format(java.util.Locale.ROOT, "%012d", taskCount));
            assertEquals(100, first.items().size());
            assertEquals("seed-unique-1", first.items().getFirst().file().uniqueId());
            assertEquals("seed-unique-" + count, last.items().getLast().file().uniqueId());
            assertTrue(last.items().size() <= 100);
        }
        long count(String table) {
            return db.read(c -> { try (var q = c.createStatement(); var rows = q.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(rows.next()); return rows.getLong(1); } });
        }
        void assertForeignKeys() {
            db.read(c -> { try (var q = c.createStatement(); var rows = q.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next(), "Every seeded row must preserve its foreign keys"); } return null; });
        }
        @Override public void close() { db.close(); }
    }

    private static void sql(Connection c, String statement, Object... args) throws SQLException {
        try (var q = c.prepareStatement(statement)) {
            for (int n = 0; n < args.length; n++) q.setObject(n + 1, args[n]);
            q.executeUpdate();
        }
    }
}
