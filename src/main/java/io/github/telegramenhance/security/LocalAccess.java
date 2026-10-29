package io.github.telegramenhance.security;

import io.github.telegramenhance.config.AppPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalAccess extends OncePerRequestFilter {
    private final String token;
    private final AppPaths paths;
    private volatile int port;
    public LocalAccess(AppPaths paths) {
        this.paths = paths;
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    @EventListener public void started(WebServerInitializedEvent event) throws IOException {
        port = event.getWebServer().getPort();
        var descriptor = paths.root().resolve("runtime.json");
        // Set the ACL before writing the access token.
        Files.deleteIfExists(descriptor);
        Files.createFile(descriptor);
        var acl = Files.getFileAttributeView(descriptor, AclFileAttributeView.class);
        if (acl == null) throw new IOException("无法保护启动握手文件");
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(descriptor)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        Files.writeString(descriptor, "{\"pid\":" + ProcessHandle.current().pid() + ",\"port\":" + port + ",\"token\":\"" + token + "\"}");
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        String authority = "127.0.0.1:" + port;
        String origin = req.getHeader("Origin");
        if (!authority.equals(req.getHeader("Host")) || (origin != null && !("http://" + authority).equals(origin))) {
            deny(res, 403); return;
        }
        String fetchSite = req.getHeader("Sec-Fetch-Site");
        if ("cross-site".equals(fetchSite)) { deny(res, 403); return; }
        // Only exact, unencoded static paths are public. Never match API security on a raw URI prefix:
        // servlet routing can decode characters or remove matrix parameters after this filter.
        String uri = req.getRequestURI();
        boolean publicAsset = "GET".equals(req.getMethod()) && (uri.equals("/") || uri.equals("/index.html") || uri.equals("/favicon.svg") || uri.matches("/assets/[A-Za-z0-9._-]+"));
        if (!publicAsset) {
            String supplied = req.getHeader("X-App-Token");
            if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII))) {
                deny(res, 401); return;
            }
            if (!"GET".equals(req.getMethod()) && !"application/json".equalsIgnoreCase(req.getContentType() == null ? "" : req.getContentType().split(";")[0])) {
                deny(res, 415); return;
            }
        }
        chain.doFilter(req, res);
    }
    private void deny(HttpServletResponse res, int status) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"本机访问校验失败，请从启动脚本重新打开资源台\"}");
    }
}
