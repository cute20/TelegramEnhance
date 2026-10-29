package io.github.telegramenhance.downloadrules;

import io.github.telegramenhance.rules.RuleModels.Window;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.DownloadModels.Task;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Rule admission is checked at media boundaries; a started media keeps its original byte checks. */
@Component
public final class DownloadRulePolicy {
    public static final String WINDOW_PAUSE = "等待持续下载时段；当前媒体已处理，后续媒体尚未开始";
    public static final String RULE_PAUSE = "持续下载规则已暂停或改版；原任务和已完成文件保留";
    public static final String HELD_PAUSE = "此持续下载任务已手动暂停，请显式继续";
    public static final String RESTART_PAUSE = "应用已重启，请核对后继续未完成项";
    public static final String SHUTDOWN_PAUSE = "应用正在关闭，持续下载已保存位置；恢复连接后按规则继续";
    private final Database database;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    @org.springframework.beans.factory.annotation.Autowired
    public DownloadRulePolicy(Database database) { this(database, Clock.systemUTC()); }
    public DownloadRulePolicy(Database database, Clock clock) { this.database = database; this.clock = clock; }

    public static final class Blocked extends IllegalArgumentException {
        private final String reason;
        private Blocked(String reason) { super(reason); this.reason = reason; }
        public String reason() { return reason; }
    }

    public boolean allowed(String account, String taskId, Instant instant) {
        return database.read(c -> block(c, account, taskId, instant, false) == null);
    }
    public boolean managed(String account, String taskId) {
        return database.read(c -> {
            try (var q = c.prepareStatement("SELECT 1 FROM download_rule_tasks WHERE account_id=? AND task_id=? AND manual=0 AND settled_at IS NULL")) {
                q.setString(1, account); q.setString(2, taskId); try (var r = q.executeQuery()) { return r.next(); }
            }
        });
    }
    public void beforeItem(Task task) {
        String reason = database.read(c -> block(c, task.accountId(), task.id(), clock.instant(), false));
        if (reason != null) throw new Blocked(reason);
    }

    /** Returns false only for an ordinary, unbound task, whose existing repository path stays unchanged. */
    public boolean manualPause(String account, String taskId, Runnable guard) {
        return database.write(c -> {
            guard.run(); if (!bound(c, account, taskId)) return false;
            String state = taskState(c, account, taskId);
            if (!"COMPLETED".equals(state)) {
                update(c, "UPDATE download_rule_tasks SET held=1 WHERE task_id=? AND account_id=? AND manual=0", taskId, account);
                update(c, "UPDATE download_tasks SET pause_requested=1,state=CASE WHEN state='RUNNING' THEN 'RUNNING' ELSE 'PAUSED' END,updated_at=? WHERE task_id=? AND account_id=?", clock.millis(), taskId, account);
            }
            guard.run(); return true;
        });
    }

    /** The admission check and release of a user hold are in the same short transaction as enqueueing. */
    public boolean manualResume(String account, String taskId, Runnable guard) {
        return database.write(c -> {
            guard.run(); if (!bound(c, account, taskId)) return false;
            String state = taskState(c, account, taskId);
            if (!"PAUSED".equals(state) && !"FAILED".equals(state)) throw new IllegalArgumentException("任务当前不能继续，请刷新状态");
            String reason = block(c, account, taskId, clock.instant(), true);
            if (reason != null) throw new Blocked(reason);
            update(c, "UPDATE download_rule_tasks SET held=0 WHERE task_id=? AND account_id=?", taskId, account);
            update(c, "UPDATE download_tasks SET state='QUEUED',pause_requested=0,error_message=NULL,updated_at=? WHERE task_id=? AND account_id=?", clock.millis(), taskId, account);
            update(c, "UPDATE download_items SET error_message=NULL WHERE task_id=? AND state NOT IN ('COMPLETED','SKIPPED_HISTORY')", taskId);
            guard.run(); return true;
        });
    }

    /** Failed or user-held tasks are never silently retried by the rule monitor. */
    public boolean autoResume(String account, String taskId, Instant instant, Runnable guard) {
        return database.write(c -> {
            guard.run();
            try (var q = c.prepareStatement("SELECT t.state,t.error_message,b.manual,b.held,b.settled_at FROM download_rule_tasks b JOIN download_tasks t ON t.task_id=b.task_id AND t.account_id=b.account_id WHERE b.task_id=? AND b.account_id=?")) {
                q.setString(1, taskId); q.setString(2, account);
                try (var r = q.executeQuery()) {
                    if (!r.next() || !"PAUSED".equals(r.getString(1)) || r.getBoolean(3) || r.getBoolean(4) || r.getObject(5) != null) return false;
                    String reason = r.getString(2);
                    if (!RESTART_PAUSE.equals(reason) && !SHUTDOWN_PAUSE.equals(reason) && !WINDOW_PAUSE.equals(reason) && !RULE_PAUSE.equals(reason)) return false;
                }
            }
            if (block(c, account, taskId, instant, false) != null) return false;
            update(c, "UPDATE download_tasks SET state='QUEUED',pause_requested=0,error_message=NULL,updated_at=? WHERE task_id=? AND account_id=?", clock.millis(), taskId, account);
            guard.run(); return true;
        });
    }
    public boolean autoResume(String account, String taskId, Runnable guard) { return autoResume(account, taskId, clock.instant(), guard); }

    private String block(Connection c, String account, String taskId, Instant instant, boolean ignoreHold) throws SQLException {
        try (var q = c.prepareStatement("SELECT b.account_id,b.rule_version,b.snapshot_json,b.manual,b.held,b.settled_at,r.account_id,r.enabled,r.current_version,v.config_json FROM download_rule_tasks b LEFT JOIN download_rules r ON r.id=b.rule_id LEFT JOIN download_rule_versions v ON v.rule_id=b.rule_id AND v.version=b.rule_version WHERE b.task_id=?")) {
            q.setString(1, taskId);
            try (var r = q.executeQuery()) {
                if (!r.next()) return null;
                if (!Objects.equals(account, r.getString(1)) || !Objects.equals(account, r.getString(7))) return RULE_PAUSE;
                if (r.getBoolean(4)) return null;
                if (r.getObject(6) != null || !r.getBoolean(8) || r.getLong(2) != r.getLong(9) || !Objects.equals(r.getString(3), r.getString(10))) return RULE_PAUSE;
                if (!ignoreHold && r.getBoolean(5)) return HELD_PAUSE;
                try {
                    var node = json.readTree(r.getString(3));
                    if (node == null || node.get("window") == null) return RULE_PAUSE;
                    Window window = json.readValue(node.get("window").toString(), Window.class);
                    return window.allows(instant) ? null : WINDOW_PAUSE;
                } catch (RuntimeException malformed) { return RULE_PAUSE; }
            }
        }
    }
    private static boolean bound(Connection c, String account, String taskId) throws SQLException {
        try (var q = c.prepareStatement("SELECT account_id FROM download_rule_tasks WHERE task_id=?")) {
            q.setString(1, taskId); try (var r = q.executeQuery()) {
                if (!r.next()) return false;
                if (!Objects.equals(account, r.getString(1))) throw new IllegalArgumentException("当前账号中没有此下载任务");
                return true;
            }
        }
    }
    private static String taskState(Connection c, String account, String taskId) throws SQLException {
        try (var q = c.prepareStatement("SELECT state FROM download_tasks WHERE account_id=? AND task_id=?")) {
            q.setString(1, account); q.setString(2, taskId); try (var r = q.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("当前账号中没有此下载任务"); return r.getString(1);
            }
        }
    }
    private static void update(Connection c, String sql, Object... values) throws SQLException {
        try (var q = c.prepareStatement(sql)) { for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i]); q.executeUpdate(); }
    }
}
