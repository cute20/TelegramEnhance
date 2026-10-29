package io.github.telegramenhance.security;

import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** DPAPI CurrentUser. Never silently fall back to plain text on unsupported hosts. */
@Component
public class SecretProtector {
    public String protect(String value) {
        requireWindows();
        return Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(value.getBytes(StandardCharsets.UTF_8)));
    }
    public String unprotect(String value) {
        requireWindows();
        return new String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(value)), StandardCharsets.UTF_8);
    }
    private void requireWindows() {
        if (!System.getProperty("os.name").startsWith("Windows")) throw new IllegalStateException("秘密存储需要 Windows 当前用户 DPAPI");
    }
}
