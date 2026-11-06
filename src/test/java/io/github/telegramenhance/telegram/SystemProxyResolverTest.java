package io.github.telegramenhance.telegram;

import io.github.telegramenhance.storage.SettingsService.ProxyConfig;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SystemProxyResolverTest {
    private final ProxyConfig system = new ProxyConfig("SYSTEM", "", 0, "", "old-password");

    @Test void readsCurrentSettingsEachTimeAndDoesNotReuseManualCredentials() {
        Map<String, Object> values = new HashMap<>(Map.of("ProxyEnable", 1, "ProxyServer", "127.0.0.1:7897"));
        var resolver = new SystemProxyResolver(() -> values);
        assertEquals(new ProxyConfig("HTTP", "127.0.0.1", 7897, "", ""), resolver.resolve(system));
        values.put("ProxyServer", "127.0.0.1:10808");
        assertEquals(10808, resolver.resolve(system).port());
        values.put("ProxyEnable", 0);
        assertEquals("DIRECT", resolver.resolve(system).type());
    }

    @Test void supportsProtocolListsAndIpv6Socks() {
        var http = new SystemProxyResolver(() -> Map.of("ProxyEnable", 1, "ProxyServer", "http=localhost:7897;https=localhost:7898;socks=localhost:1080"));
        assertEquals(new ProxyConfig("HTTP", "localhost", 7897, "", ""), http.resolve(system));
        var socks = new SystemProxyResolver(() -> Map.of("ProxyEnable", 1, "ProxyServer", "socks=[::1]:1080"));
        assertEquals(new ProxyConfig("SOCKS5", "::1", 1080, "", ""), socks.resolve(system));
    }

    @Test void rejectsPacAndInvalidEnabledProxyInsteadOfSilentlyConnectingDirectly() {
        assertThrows(IllegalArgumentException.class, () -> new SystemProxyResolver(() -> Map.of("AutoConfigURL", "http://localhost/proxy.pac")).resolve(system));
        for (String server : new String[]{"", "localhost", "localhost:0", "localhost:65536", "ftp=localhost:21", "http://user:secret@localhost:80", "localhost:80/path"}) {
            assertThrows(IllegalArgumentException.class, () -> new SystemProxyResolver(() -> Map.of("ProxyEnable", 1, "ProxyServer", server)).resolve(system), server);
        }
    }

    @Test void manualAndDirectModesNeverReadSystemSettings() {
        var resolver = new SystemProxyResolver(() -> { throw new AssertionError("must not read registry"); });
        var manual = new ProxyConfig("SOCKS5", "localhost", 1080, "user", "password");
        assertSame(manual, resolver.resolve(manual));
        var direct = new ProxyConfig("DIRECT", "", 0, "", "");
        assertSame(direct, resolver.resolve(direct));
    }
}
