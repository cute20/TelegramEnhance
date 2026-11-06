package io.github.telegramenhance.telegram;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import io.github.telegramenhance.storage.SettingsService.ProxyConfig;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Supplier;

/** Resolves the current user's explicit Windows proxy at connection time. Never changes Windows settings. */
final class SystemProxyResolver {
    private static final String KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
    private final Supplier<Map<String, Object>> source;

    SystemProxyResolver() { this(SystemProxyResolver::readWindows); }
    SystemProxyResolver(Supplier<Map<String, Object>> source) { this.source = source; }

    ProxyConfig resolve(ProxyConfig configured) {
        if (!"SYSTEM".equals(configured.type())) return configured;
        Map<String, Object> values = source.get();
        if (!text(values, "AutoConfigURL").isBlank()) {
            throw new IllegalArgumentException("系统使用 PAC 自动配置，暂不支持；请改用手动 HTTP 或 SOCKS5 代理。");
        }
        if (!(values.get("ProxyEnable") instanceof Number enabled) || enabled.intValue() == 0) {
            return new ProxyConfig("DIRECT", "", 0, "", "");
        }
        String server = text(values, "ProxyServer").strip();
        String protocol = "http";
        if (server.contains("=")) {
            Map<String, String> entries = new HashMap<>();
            for (String entry : server.split(";")) {
                String[] pair = entry.strip().split("=", 2);
                if (pair.length == 2) entries.put(pair[0].strip().toLowerCase(Locale.ROOT), pair[1].strip());
            }
            if (entries.containsKey("http")) server = entries.get("http");
            else if (entries.containsKey("https")) server = entries.get("https");
            else if (entries.containsKey("socks")) { server = entries.get("socks"); protocol = "socks5"; }
            else throw invalid();
        }
        try {
            URI uri = URI.create(server.contains("://") ? server : protocol + "://" + server);
            String type = switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
                case "http" -> "HTTP";
                case "socks", "socks5" -> "SOCKS5";
                default -> throw invalid();
            };
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) throw invalid();
            String host = uri.getHost();
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            return new ProxyConfig(type, host, uri.getPort(), "", "");
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }

    private static Map<String, Object> readWindows() {
        if (!Platform.isWindows()) throw new IllegalArgumentException("使用系统代理仅支持 Windows。");
        try {
            if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, KEY)) return Map.of();
            return Advapi32Util.registryGetValues(WinReg.HKEY_CURRENT_USER, KEY);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("无法读取 Windows 系统代理，请检查系统设置或使用手动代理。");
        }
    }
    private static String text(Map<String, Object> values, String key) {
        return values.get(key) instanceof String value ? value : "";
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Windows 系统代理地址无效或协议不支持，请检查系统设置或使用手动 HTTP / SOCKS5 代理。");
    }
}
