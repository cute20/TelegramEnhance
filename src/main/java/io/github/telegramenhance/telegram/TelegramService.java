package io.github.telegramenhance.telegram;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.storage.SettingsService;
import io.github.telegramenhance.storage.SettingsService.ConnectionConfig;
import io.github.telegramenhance.storage.SettingsService.ProxyConfig;
import it.tdlight.ClientFactory;
import it.tdlight.Init;
import it.tdlight.TelegramClient;
import it.tdlight.UpdatesHandler;
import it.tdlight.jni.TdApi;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Service @DependsOn("instanceLock")
public class TelegramService {
    public record Status(boolean nativeLoaded, String nativeVersion, String authState, String connectionState,
                         String userId, String displayName, String error, String authHint, AuthCodeDelivery.View codeDelivery) {}
    public record ProxyResult(String level, String message, long milliseconds) {}
    private final SettingsService settings;
    private final AppPaths paths;
    private final AuthCodeDelivery codeDelivery = new AuthCodeDelivery();
    private final ExecutorService commands = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), Thread.ofPlatform().name("telegram-control").factory());
    private final ClientFactory factory;
    private volatile TelegramClient client;
    private final Object sessionLock = new Object();
    private long clientGeneration;
    private volatile boolean nativeLoaded;
    private volatile String nativeVersion = "unknown", authState = "DISCONNECTED", connectionState = "DISCONNECTED";
    private volatile String userId, displayName, error, authHint;
    private volatile boolean connecting, stopping;
    private boolean parametersConfigured;
    private ConnectionConfig connection;
    interface SendUpdateListener { void accept(String account,long generation,TdApi.Update update); }
    private final java.util.concurrent.CopyOnWriteArrayList<SendUpdateListener> sendListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    AutoCloseable observeSends(SendUpdateListener listener) { sendListeners.add(listener); return () -> sendListeners.remove(listener); }
    private volatile CompletableFuture<Void> closed = CompletableFuture.completedFuture(null);

    public TelegramService(SettingsService settings, AppPaths paths) {
        this.settings = settings; this.paths = paths;
        ClientFactory created = null;
        try {
            Init.init();
            created = ClientFactory.create();
            factory = created;
            newClient();
            client.execute(new TdApi.SetLogVerbosityLevel(0));
            client.execute(new TdApi.SetLogStream(new TdApi.LogStreamEmpty()));
            var version = client.execute(new TdApi.GetOption("version"));
            if (version instanceof TdApi.OptionValueString v) nativeVersion = v.value;
            nativeLoaded = true;
        } catch (Exception | LinkageError e) {
            if (created != null) created.close();
            error = "Windows TDLight 原生库加载失败。请检查 x64 VC++ 运行库及配套依赖。";
            throw new IllegalStateException(error, e);
        }
    }
    private void newClient() {
        TelegramClient fresh = factory.createClient();
        synchronized (sessionLock) { client = fresh; clientGeneration++; }
        final long freshGeneration = sessionGeneration();
        closed = new CompletableFuture<>(); parametersConfigured = false;
        fresh.initialize((UpdatesHandler) updates -> updates.forEach(update -> {
            if (update instanceof TdApi.UpdateAuthorizationState a) {
                long observedAt = System.currentTimeMillis();
                if (a.authorizationState instanceof TdApi.AuthorizationStateClosed) {
                    if (fresh == client) closed.complete(null);
                }
                enqueueUpdate(() -> { if (fresh == client) onAuth(a.authorizationState, observedAt); });
            } else if (update instanceof TdApi.UpdateConnectionState c) {
                if (fresh == client && connecting) connectionState = c.state.getClass().getSimpleName().replace("ConnectionState", "").replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
            } else if (fresh == client && userId != null && (update instanceof TdApi.UpdateMessageSendSucceeded || update instanceof TdApi.UpdateMessageSendFailed)) {
                for (var listener : sendListeners) listener.accept(userId, freshGeneration, (TdApi.Update) update);
            }
        }), ignored -> error = "Telegram 更新处理异常，请断开后重新连接", ignored -> error = "Telegram 回调异常，请断开后重新连接");
    }
    private void enqueueUpdate(Runnable action) {
        if (commands.isShutdown()) return;
        try { commands.execute(action); } catch (RejectedExecutionException ignored) { error = "Telegram 状态队列繁忙，请断开后重试"; }
    }
    public Status status() { return new Status(nativeLoaded, nativeVersion, authState, connectionState, userId, displayName, error, authHint, codeDelivery.view()); }
    public boolean isActive() { return connecting || authState.equals("CLOSING"); }
    public String accountId() {
        if (stopping || !connecting || !"READY".equals(authState) || userId == null) throw new IllegalArgumentException("请先连接 Telegram 并完成账号授权");
        return userId;
    }
    <R extends TdApi.Object> CompletableFuture<R> read(TdApi.Function<R> query) {
        return readInSession(sessionGeneration(), query);
    }
    public long sessionGeneration() { synchronized (sessionLock) { return clientGeneration; } }
    /** Bind file operations to the captured client; an old cleanup can never target a new session. */
    <R extends TdApi.Object> CompletableFuture<R> readInSession(long generation, TdApi.Function<R> query) {
        TelegramClient target;
        synchronized (sessionLock) {
            accountId();
            if (generation != clientGeneration) throw new IllegalArgumentException("Telegram会话已变化，请重试");
            target = client;
        }
        return target.sendAsync(query).orTimeout(15, TimeUnit.SECONDS);
    }
    private <T> CompletableFuture<T> submit(Supplier<T> action) {
        if (stopping) throw new IllegalStateException("后台正在退出");
        try { return CompletableFuture.supplyAsync(action, commands); }
        catch (RejectedExecutionException e) { throw new IllegalStateException("操作队列繁忙，请稍后重试"); }
    }
    public CompletableFuture<Status> connect() { return submit(() -> {
        if (connecting) return status();
        connection = settings.credentials();
        if (connection.apiId() <= 0 || connection.apiHash().isBlank()) throw new IllegalArgumentException("请先保存 API ID 和 API Hash");
        if (closed.isDone()) newClient();
        connecting = true; authState = "STARTING"; error = null; authHint = null;
        try { onAuth(await(client.sendAsync(new TdApi.GetAuthorizationState()))); }
        catch (RuntimeException e) { error = safeError(e); }
        return status();
    }); }
    private void onAuth(TdApi.AuthorizationState state) {
        onAuth(state, System.currentTimeMillis());
    }
    private void onAuth(TdApi.AuthorizationState state, long observedAt) {
        if (!(state instanceof TdApi.AuthorizationStateWaitCode)) codeDelivery.clear();
        if (state instanceof TdApi.AuthorizationStateClosed) {
            connecting = false; authState = "CLOSED"; connectionState = "DISCONNECTED"; userId = null; displayName = null; connection = null; return;
        }
        if (!connecting) return;
        authHint = null;
        if (state instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            if (parametersConfigured) return;
            parametersConfigured = true;
            try {
                var p = connection.proxy();
                var networkPaused = client.sendAsync(new TdApi.SetNetworkType(new TdApi.NetworkTypeNone()));
                // These are preauthentication requests, queued by TDLib until parameters arrive.
                // Never await this future before submitting SetTdlibParameters.
                var proxyReady = "DIRECT".equals(p.type()) ? client.sendAsync(new TdApi.DisableProxy())
                        : client.sendAsync(new TdApi.AddProxy(proxy(p), true, "Telegram resource workbench"));
                var params = new TdApi.SetTdlibParameters();
                params.databaseDirectory = paths.telegram().resolve("database").toString();
                params.filesDirectory = paths.telegram().resolve("cache").toString();
                params.databaseEncryptionKey = settings.tdlibKey();
                params.useFileDatabase = true; params.useChatInfoDatabase = true; params.useMessageDatabase = true;
                params.useSecretChats = false; params.apiId = connection.apiId(); params.apiHash = connection.apiHash();
                params.systemLanguageCode = "zh-hans"; params.deviceModel = "Telegram 资源台 (Windows)";
                params.systemVersion = System.getProperty("os.version"); params.applicationVersion = "0.1.0-M1";
                await(client.sendAsync(params));
                await(networkPaused);
                await(proxyReady);
                await(client.sendAsync(new TdApi.SetNetworkType(new TdApi.NetworkTypeOther())));
            } catch (RuntimeException e) { error = safeError(e); authState = "ERROR"; }
        } else if (state instanceof TdApi.AuthorizationStateWaitPhoneNumber) authState = "WAIT_PHONE";
        else if (state instanceof TdApi.AuthorizationStateWaitCode a) { authState = "WAIT_CODE"; codeDelivery.update(a.codeInfo, observedAt); authHint = codeDelivery.hint(); }
        else if (state instanceof TdApi.AuthorizationStateWaitPassword) authState = "WAIT_PASSWORD";
        else if (state instanceof TdApi.AuthorizationStateWaitEmailAddress) authState = "WAIT_EMAIL";
        else if (state instanceof TdApi.AuthorizationStateWaitEmailCode) authState = "WAIT_EMAIL_CODE";
        else if (state instanceof TdApi.AuthorizationStateWaitRegistration) { authState = "WAIT_REGISTRATION"; authHint = "该号码需要注册；本工具仅连接已有账号，请断开并核对号码。"; }
        else if (state instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation) { authState = "WAIT_OTHER_DEVICE"; authHint = "Telegram 要求其他设备确认，请在已有客户端确认。"; }
        else if (state instanceof TdApi.AuthorizationStateReady) {
            authState = "READY"; error = null;
            client.sendAsync(new TdApi.GetMe()).whenComplete((me, failure) -> {
                if (failure == null && connecting) { userId = Long.toString(me.id); displayName = (me.firstName + " " + me.lastName).strip(); }
            });
        } else if (state instanceof TdApi.AuthorizationStateClosing || state instanceof TdApi.AuthorizationStateLoggingOut) authState = "CLOSING";
        else { authState = "ERROR"; authHint = "当前授权状态尚不支持，请在 Telegram 客户端完成要求后重试。"; }
    }
    public CompletableFuture<Status> authenticate(String kind, String value) { return submit(() -> {
        if (value == null || value.isBlank() || value.length() > 1024) throw new IllegalArgumentException("请填写有效的授权信息");
        TdApi.Function<TdApi.Ok> request = switch (kind == null ? "" : kind) {
            case "phone" -> { requireState("WAIT_PHONE"); if (!value.matches("\\+[0-9]{6,16}")) throw new IllegalArgumentException("手机号需包含 + 和国家区号"); yield new TdApi.SetAuthenticationPhoneNumber(value, null); }
            case "code" -> { requireState("WAIT_CODE"); yield new TdApi.CheckAuthenticationCode(value.strip()); }
            case "password" -> { requireState("WAIT_PASSWORD"); yield new TdApi.CheckAuthenticationPassword(value); }
            case "email" -> { requireState("WAIT_EMAIL"); yield new TdApi.SetAuthenticationEmailAddress(value.strip()); }
            case "emailCode" -> { requireState("WAIT_EMAIL_CODE"); yield new TdApi.CheckAuthenticationEmailCode(new TdApi.EmailAddressAuthenticationCode(value.strip())); }
            default -> throw new IllegalArgumentException("授权步骤无效，请刷新连接状态");
        };
        error = null;
        try { await(client.sendAsync(request)); } catch (RuntimeException e) { error = safeError(e); }
        return status();
    }); }
    private void requireState(String expected) { if (!connecting || !expected.equals(authState)) throw new IllegalArgumentException("授权状态已变化，请按当前页面继续"); }
    public CompletableFuture<Status> resendAuthenticationCode() { return submit(() -> {
        requireState("WAIT_CODE");
        codeDelivery.claimResend();
        error = null;
        try {
            await(client.sendAsync(new TdApi.ResendAuthenticationCode(new TdApi.ResendCodeReasonUserRequest())));
            onAuth(await(client.sendAsync(new TdApi.GetAuthorizationState())));
        } catch (RuntimeException e) {
            // Keep the request claimed after an uncertain result. Never automatically resend.
            error = safeError(e);
        }
        return status();
    }); }
    public CompletableFuture<Status> disconnect(boolean logout) { return submit(() -> {
        if (closed.isDone()) return status();
        authState = "CLOSING"; error = null;
        try {
            await(client.sendAsync(logout ? new TdApi.LogOut() : new TdApi.Close()));
            closed.get(20, TimeUnit.SECONDS);
            connecting = false; connection = null; authState = "CLOSED"; connectionState = "DISCONNECTED"; userId = null; displayName = null;
        } catch (Exception e) { error = "客户端尚未确认关闭，请等待后重试；本机会话保留。"; }
        return status();
    }); }
    public CompletableFuture<ProxyResult> testProxy() { return submit(() -> {
        long started = System.nanoTime(); var p = settings.credentials().proxy();
        if ("DIRECT".equals(p.type())) return new ProxyResult("INFO", "已选择直连。请点击连接 Telegram，以实际连接状态验证网络。", 0);
        // TestProxy is explicitly supported before SetTdlibParameters by this pinned TDLight build.
        if (closed.isDone()) newClient();
        try {
            // TDLib performs a Telegram DC handshake through the selected proxy; TCP reachability alone is insufficient.
            await(client.sendAsync(new TdApi.TestProxy(proxy(p), 2, 8)));
            return new ProxyResult("SUCCESS", "TDLib 已通过代理完成 Telegram 数据中心握手；账号授权状态单独显示。", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (RuntimeException e) {
            return new ProxyResult("ERROR", "代理到 Telegram 的握手未通过。请检查协议、地址、端口、认证和网络。", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }); }
    private TdApi.Proxy proxy(ProxyConfig p) {
        TdApi.ProxyType type = "SOCKS5".equals(p.type()) ? new TdApi.ProxyTypeSocks5(p.username(), p.password()) : new TdApi.ProxyTypeHttp(p.username(), p.password(), false);
        return new TdApi.Proxy(p.host(), p.port(), type);
    }
    private <T> T await(CompletableFuture<T> result) {
        try { return result.get(15, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("操作已中断"); }
        catch (ExecutionException | TimeoutException e) { throw new CompletionException(e); }
    }
    private String safeError(Throwable failure) {
        Throwable root = failure; while (root.getCause() != null) root = root.getCause();
        if (root instanceof TimeoutException) return "Telegram 请求超时，请检查代理和连接状态；勿反复提交验证码。";
        String detail = root.getMessage() == null ? "" : root.getMessage();
        if (detail.contains("PHONE_CODE_INVALID")) return "验证码不正确，请重新填写。";
        if (detail.contains("PHONE_CODE_EXPIRED")) return "验证码已过期，请断开后重新连接。";
        if (detail.contains("PASSWORD_HASH_INVALID")) return "两步验证密码不正确。";
        if (detail.contains("API_ID_INVALID")) return "API ID 或 API Hash 无效，请断开后核对应用凭证。";
        if (detail.contains("PHONE_NUMBER_INVALID")) return "手机号无效，请核对国家区号和号码。";
        if (detail.contains("FLOOD_WAIT") || detail.contains("Too Many Requests")) return "Telegram 限制请求频率，请等待后再试。";
        return "Telegram 未接受本次操作，请核对授权信息和网络后重试。";
    }
    @PreDestroy public void close() {
        stopping = true;
        try {
            commands.submit(() -> { if (!closed.isDone()) client.send(new TdApi.Close(), ignored -> {}); }).get(16, TimeUnit.SECONDS);
            if (!closed.isDone()) closed.get(25, TimeUnit.SECONDS);
            factory.close();
        } catch (Exception e) {
            // Do not release the single-instance lock while the old native client is still alive.
            Runtime.getRuntime().halt(2);
        } finally { commands.shutdownNow(); }
    }
}
