package io.github.telegramenhance.telegram;

import it.tdlight.jni.TdApi;
import java.time.Clock;

/** Redacted, immutable view of a server-issued code challenge. Never retains phone numbers or codes. */
public final class AuthCodeDelivery {
    public record View(String type, String nextType, Long resendAvailableAt, boolean canResend) {}
    private final Clock clock;
    private String type;
    private String nextType;
    private Long resendAvailableAt;
    private boolean requested;

    public AuthCodeDelivery() { this(Clock.systemUTC()); }
    AuthCodeDelivery(Clock clock) { this.clock = clock; }

    public synchronized void update(TdApi.AuthenticationCodeInfo info) {
        update(info, clock.millis());
    }
    synchronized void update(TdApi.AuthenticationCodeInfo info, long observedAt) {
        if (info == null) { clear(); return; }
        type = name(info.type);
        nextType = info.nextType == null ? null : name(info.nextType);
        // TDLib supplies remaining seconds. Convert once when receiving a fresh TDLib state,
        // never when polling our cached HTTP status.
        resendAvailableAt = nextType == null ? null : observedAt + Math.max(0L, info.timeout) * 1000L;
        requested = false;
    }

    public synchronized View view() {
        if (type == null) return null;
        return new View(type, nextType, resendAvailableAt,
                nextType != null && resendAvailableAt != null && clock.millis() >= resendAvailableAt && !requested);
    }

    public synchronized void claimResend() {
        View current = view();
        if (current == null || current.nextType() == null) throw new IllegalArgumentException("Telegram 本次未提供其他收码方式，无法强制短信；请查看已登录客户端的官方服务消息");
        if (!current.canResend()) throw new IllegalArgumentException("尚未到 Telegram 允许的重发时间，或上次请求仍待确认");
        requested = true;
    }

    public synchronized String hint() {
        if (type == null) return null;
        return switch (type) {
            case "TelegramMessage" -> "验证码已请求发送到已登录的 Telegram 客户端。请查看官方“Telegram”服务消息，本次不是短信。";
            case "Sms" -> "验证码已请求通过短信发送，请查看该 Telegram 账号手机号收到的短信。";
            case "Call" -> "Telegram 将通过语音电话提供验证码，请留意来电。";
            case "FlashCall", "MissedCall" -> "Telegram 使用来电号码验证。当前页面尚未支持此方式，请使用官方客户端完成验证。";
            case "SmsWord", "SmsPhrase" -> "Telegram 已请求发送包含单词或短语的验证短信，请按短信原文填写。";
            case "Fragment" -> "Telegram 要求通过 Fragment 收取验证码，请在官方客户端查看对应提示。";
            case "FirebaseAndroid", "FirebaseIos" -> "Telegram 要求官方移动客户端的设备验证；当前桌面工具不支持此收码方式。";
            default -> "Telegram 返回了当前工具尚不支持的收码方式，请在官方客户端核对登录提示。";
        };
    }

    public synchronized void clear() { type = null; nextType = null; resendAvailableAt = null; requested = false; }

    private String name(TdApi.AuthenticationCodeType value) {
        if (value == null) return "Unknown";
        return value.getClass().getSimpleName().replace("AuthenticationCodeType", "");
    }
}
