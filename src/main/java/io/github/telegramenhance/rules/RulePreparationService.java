package io.github.telegramenhance.rules;

import io.github.telegramenhance.telegram.TelegramService;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;

/** Bounded, read-only preparation receipts. A preparation never enables a rule or sends a file. */
@Service
public final class RulePreparationService implements AutoCloseable {
    private static final long BUDGET = TimeUnit.MINUTES.toNanos(12);
    private static final long TTL = TimeUnit.MINUTES.toNanos(10);
    private static final String EXPIRED = "本次准备达到12分钟上限，后续读取已停止；请刷新规则核对检测结果，本次不会启用或发送文件";
    private final RuleService rules;
    private final TelegramService telegram;
    private final Clock clock;
    private final LongSupplier nanos;
    private final Executor starter;
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private volatile boolean closed;

    public record Request(String operationId, long revision, String kind) { }
    public record View(String operationId, String ruleId, String accountId, long version, long revision,
                       String kind, String state, long startedAt, Long finishedAt, String error, Object result) { }
    private static final class Job {
        final String operation, rule, account, kind;
        final long generation, version, revision, startedAt, startedNanos;
        String state = "RUNNING", error;
        Long finishedAt;
        long finishedNanos;
        Object result;
        Job(Request input, RuleModels.Rule rule, long generation, long stamp, long tick) {
            operation = input.operationId(); this.rule = rule.id(); account = rule.accountId(); kind = input.kind();
            this.generation = generation; version = rule.version(); revision = rule.revision(); startedAt = stamp; startedNanos = tick;
        }
        View view() { return new View(operation, rule, account, version, revision, kind, state, startedAt, finishedAt, error, result); }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RulePreparationService(RuleService rules, TelegramService telegram) {
        this(rules, telegram, Clock.systemUTC(), System::nanoTime,
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8),
                        Thread.ofPlatform().daemon(true).name("rule-preparation-start").factory()));
    }
    RulePreparationService(RuleService rules, TelegramService telegram, Clock clock, LongSupplier nanos, Executor starter) {
        this.rules = rules; this.telegram = telegram; this.clock = clock; this.nanos = nanos; this.starter = starter;
    }

    public View start(String ruleId, Request input) {
        id(ruleId); validate(input);
        String account = telegram.accountId(); long generation = telegram.sessionGeneration();
        var rule = rules.get(ruleId).rule();
        if (!account.equals(rule.accountId()) || rule.revision() != input.revision()) throw new IllegalArgumentException("规则已变化，请刷新后重新准备");
        context(account, generation);
        Job job;
        synchronized (this) {
            requireOpen(); prune();
            String key = key(account, input.operationId()); var previous = jobs.get(key);
            if (previous != null) {
                if (!previous.rule.equals(ruleId) || previous.revision != input.revision() || !previous.kind.equals(input.kind()))
                    throw new IllegalArgumentException("同一准备操作的参数已变化，请按原操作号核对结果");
                context(previous.account, previous.generation); expire(previous); return previous.view();
            }
            for (var pending : jobs.values()) if (pending.account.equals(account) && pending.rule.equals(ruleId)
                    && pending.kind.equals(input.kind()) && pending.state.equals("RUNNING"))
                throw new IllegalArgumentException("这条规则的同类准备正在进行，请等待原操作结果");
            if (jobs.size() >= 8) throw new IllegalArgumentException("准备记录已达到8份上限，请等待已完成记录过期后重试");
            job = new Job(input, rule, generation, clock.millis(), nanos.getAsLong()); jobs.put(key, job);
        }
        try { starter.execute(() -> run(job)); }
        catch (RuntimeException unavailable) { fail(job, new IllegalArgumentException("准备队列暂不可用，本次未启用或发送文件")); }
        synchronized (this) { return job.view(); }
    }

    /** Lookup does not start, replay, cancel, enable, or send anything. */
    public View lookup(String ruleId, String operation) {
        id(ruleId); id(operation); String account = telegram.accountId(); long generation = telegram.sessionGeneration();
        var current = rules.get(ruleId).rule(); context(account, generation);
        synchronized (this) {
            requireOpen(); prune(); var job = jobs.get(key(account, operation));
            if (job == null) return null;
            if (!job.rule.equals(ruleId)) throw new IllegalArgumentException("准备操作不属于这条规则");
            context(job.account, job.generation);
            if (current.version() != job.version || current.revision() != job.revision) {
                fail(job, new IllegalArgumentException("规则已更新或暂停，旧准备结果已失效，请刷新后重新准备"));
                throw new IllegalArgumentException("规则已更新或暂停，旧准备结果已失效，请刷新后重新准备");
            }
            expire(job); return job.view();
        }
    }

    private void run(Job job) {
        try {
            check(job); var position = new RuleService.Position(job.revision);
            CompletableFuture<?> work = job.kind.equals("observe") ? rules.observe(job.rule, position, () -> check(job))
                    : rules.preview(job.rule, position, () -> check(job));
            Objects.requireNonNull(work, "preparation future").whenComplete((result, failure) -> {
                if (failure != null) { fail(job, failure); return; }
                try {
                    check(job); var current = rules.get(job.rule).rule();
                    if (current.version() != job.version || current.revision() != job.revision) throw new IllegalArgumentException("规则已变化，旧准备结果已丢弃");
                    if (job.kind.equals("observe") && (!(result instanceof RuleModels.View observed)
                            || !job.rule.equals(observed.rule().id()) || !job.account.equals(observed.rule().accountId())
                            || job.version != observed.rule().version() || job.revision != observed.rule().revision())
                            || job.kind.equals("preview") && (!(result instanceof RuleModels.Preview preview)
                            || !job.rule.equals(preview.ruleId()) || job.version != preview.version() || job.revision != preview.revision()))
                        throw new IllegalArgumentException("准备结果与当前规则不符，已丢弃旧结果");
                    if (result instanceof RuleModels.View observed && observed.rule().observation() != null
                            && "ERROR".equals(observed.rule().observation().outcome()))
                        throw new IllegalArgumentException(observed.rule().observation().error());
                    synchronized (this) {
                        context(job.account, job.generation); expire(job); if (!job.state.equals("RUNNING")) return;
                        job.result = result; job.state = "COMPLETED"; finished(job);
                    }
                } catch (RuntimeException invalid) { fail(job, invalid); }
            });
        } catch (RuntimeException failure) { fail(job, failure); }
    }
    private void check(Job job) {
        context(job.account, job.generation);
        synchronized (this) {
            requireOpen(); expire(job);
            if (!job.state.equals("RUNNING")) throw new IllegalArgumentException(job.error == null ? "本次准备已结束" : job.error);
        }
    }
    private synchronized void fail(Job job, Throwable failure) {
        expire(job); if (!job.state.equals("RUNNING")) return;
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) cause = cause.getCause();
        job.error = cause instanceof IllegalArgumentException && cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getMessage() : "目录准备未完成，请检查目录和连接状态；本次未启用或发送文件";
        job.result = null; job.state = "FAILED"; finished(job);
    }
    private void expire(Job job) {
        if (job.state.equals("RUNNING") && nanos.getAsLong() - job.startedNanos >= BUDGET) {
            job.error = EXPIRED; job.result = null; job.state = "FAILED";
            job.finishedAt = job.startedAt + TimeUnit.NANOSECONDS.toMillis(BUDGET); job.finishedNanos = job.startedNanos + BUDGET;
        }
    }
    private void finished(Job job) { job.finishedAt = clock.millis(); job.finishedNanos = nanos.getAsLong(); }
    private void prune() {
        for (var job : jobs.values()) expire(job);
        long now = nanos.getAsLong(); jobs.values().removeIf(job -> !job.state.equals("RUNNING") && now - job.finishedNanos >= TTL);
    }
    private void context(String account, long generation) {
        if (Thread.currentThread().isInterrupted() || !account.equals(telegram.accountId()) || generation != telegram.sessionGeneration())
            throw new IllegalArgumentException("账号或会话已变化，请重新准备规则");
    }
    private void requireOpen() { if (closed) throw new IllegalArgumentException("后台正在退出，准备已停止"); }
    private static String key(String account, String operation) { return account + ":" + operation; }
    private static void validate(Request input) {
        if (input == null) throw new IllegalArgumentException("请填写准备操作"); id(input.operationId());
        if (input.revision() < 1 || !("observe".equals(input.kind()) || "preview".equals(input.kind())))
            throw new IllegalArgumentException("请选择检测或分组预览，并核对规则版本");
    }
    private static void id(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("准备操作或规则标识无效");
    }
    @Override @PreDestroy public synchronized void close() {
        closed = true;
        for (var job : jobs.values()) fail(job, new IllegalArgumentException("后台已退出，准备已停止；请重新核对规则"));
        if (starter instanceof ExecutorService executor) executor.shutdownNow();
    }
}
