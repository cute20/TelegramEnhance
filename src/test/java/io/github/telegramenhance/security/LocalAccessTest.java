package io.github.telegramenhance.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import io.github.telegramenhance.config.AppPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class LocalAccessTest {
    private static final int PORT = 43127;
    private static final String AUTHORITY = "127.0.0.1:" + PORT;
    private LocalAccess access;
    private String token;

    @BeforeEach
    void setUp() {
        // Exercise the filter without starting a server or creating its token descriptor.
        access = new LocalAccess(mock(AppPaths.class));
        ReflectionTestUtils.setField(access, "port", PORT);
        token = (String) ReflectionTestUtils.getField(access, "token");
    }

    @Test
    void apiRequiresToken() throws Exception {
        denied(request("GET", "/api/bootstrap"), 401);
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        var request = request("GET", "/api/bootstrap");
        request.addHeader("X-App-Token", "incorrect-token");
        denied(request, 401);
    }

    @Test
    void validTokenAllowsApiAndSetsSecurityHeaders() throws Exception {
        var response = allowed(authenticated("GET", "/api/bootstrap"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertNotNull(response.getHeader("Content-Security-Policy"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.html", "/assets/index-AbC123.js", "/assets/index-AbC123.css"})
    void exactStaticGetPathsDoNotNeedToken(String uri) throws Exception {
        allowed(request("GET", uri));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%61pi/bootstrap", "/api;x/bootstrap", "/api/bootstrap;x=1", "/%61ssets/index.js"})
    void encodedAndMatrixPathsCannotBypassToken(String uri) throws Exception {
        denied(request("GET", uri), 401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:43127", "127.0.0.1:43128", "127.0.0.1.evil.test:43127"})
    void mismatchedHostIsRejectedEvenWithToken(String host) throws Exception {
        var request = authenticated("GET", "/api/bootstrap");
        request.removeHeader("Host");
        request.addHeader("Host", host);
        denied(request, 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.test", "http://localhost:43127", "https://127.0.0.1:43127", "null"})
    void mismatchedOriginIsRejectedEvenWithToken(String origin) throws Exception {
        var request = authenticated("GET", "/api/bootstrap");
        request.addHeader("Origin", origin);
        denied(request, 403);
    }

    @Test
    void matchingOriginIsAccepted() throws Exception {
        var request = authenticated("GET", "/api/bootstrap");
        request.addHeader("Origin", "http://" + AUTHORITY);
        allowed(request);
    }

    @Test
    void crossSiteRequestsCannotReadPublicAssets() throws Exception {
        var request = request("GET", "/index.html");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        denied(request, 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "text/plain", "application/x-www-form-urlencoded"})
    void mutationsRequireJsonContentType(String contentType) throws Exception {
        var request = authenticated("POST", "/api/telegram/connect");
        if (!contentType.isEmpty()) request.setContentType(contentType);
        denied(request, 415);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/json;charset=UTF-8", "Application/JSON"})
    void jsonMutationsAreAccepted(String contentType) throws Exception {
        var request = authenticated("POST", "/api/telegram/connect");
        request.setContentType(contentType);
        allowed(request);
    }

    @Test
    void staticPathsAreNotPublicForMutations() throws Exception {
        var request = request("POST", "/index.html");
        request.setContentType("application/json");
        denied(request, 401);
    }

    private MockHttpServletRequest request(String method, String uri) {
        var request = new MockHttpServletRequest(method, uri);
        request.addHeader("Host", AUTHORITY);
        return request;
    }

    private MockHttpServletRequest authenticated(String method, String uri) {
        var request = request(method, uri);
        request.addHeader("X-App-Token", token);
        return request;
    }

    private MockHttpServletResponse allowed(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        access.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), "Allowed request must reach the application");
        return response;
    }

    private void denied(MockHttpServletRequest request, int status) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        access.doFilter(request, response, chain);
        assertEquals(status, response.getStatus());
        assertNull(chain.getRequest(), "Denied request must not reach the application");
    }
}
