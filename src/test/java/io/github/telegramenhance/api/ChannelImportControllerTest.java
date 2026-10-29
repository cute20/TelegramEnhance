package io.github.telegramenhance.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ChannelImportModels.*;
import io.github.telegramenhance.library.ChannelImportService;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.library.ResourceRepository;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.security.LocalAccess;
import io.github.telegramenhance.telegram.ChannelReader;
import io.github.telegramenhance.telegram.ResourceReader;
import io.github.telegramenhance.telegram.TelegramService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChannelImportControllerTest {
    private static final String CHANNEL = "-1009007199254740999", MESSAGE = "9007199254740999";

    @Test void managedChannelsAreAsynchronousAndKeepStringIdentitiesAndBoundedScope() throws Exception {
        var service = mock(ChannelImportService.class);
        when(service.channels()).thenReturn(CompletableFuture.completedFuture(new ManagedChannels(
            List.of(new ManagedChannel(CHANNEL, "Synthetic channel")), "LOADED_MANAGED_CHANNELS", true, List.of("范围有界"))));
        var mvc = mvc(service);
        var request = mvc.perform(get("/api/library/import/channels")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(CHANNEL))
            .andExpect(jsonPath("$.partial").value(true)).andExpect(jsonPath("$.scope").value("LOADED_MANAGED_CHANNELS"));
        verify(service).channels(); verifyNoMoreInteractions(service);
    }

    @Test void previewUsesTheSelectedChannelAndKeepsMessageAndAlbumIdsAsStrings() throws Exception {
        var service = mock(ChannelImportService.class);
        when(service.preview(CHANNEL)).thenReturn(CompletableFuture.completedFuture(new Preview(new ManagedChannel(CHANNEL, "Synthetic"),
            List.of(new Candidate(MESSAGE, "9007199254740998", "Synthetic post")), "RECENT_CHANNEL_POSTS", 30, List.of("仅最近窗口"))));
        var mvc = mvc(service);
        var request = mvc.perform(post("/api/library/import/preview").contentType("application/json")
            .content("{\"channelId\":\"" + CHANNEL + "\"}")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andExpect(jsonPath("$.channel.id").value(CHANNEL))
            .andExpect(jsonPath("$.items[0].messageId").value(MESSAGE)).andExpect(jsonPath("$.items[0].albumId").value("9007199254740998"))
            .andExpect(jsonPath("$.limit").value(30));
        verify(service).preview(CHANNEL); verifyNoMoreInteractions(service);
    }

    @Test void postEndpointImportsOnlyTheExplicitMessageAndReturnsThePersistedPost() throws Exception {
        var service = mock(ChannelImportService.class);
        Post saved = new Post("account:" + CHANNEL + ":album:42", "account", CHANNEL, "Synthetic", MESSAGE, "title", "body", "42",
            List.of(), List.of(), null, null, List.of(), false, "UNAVAILABLE", List.of());
        when(service.importPost(CHANNEL, MESSAGE)).thenReturn(CompletableFuture.completedFuture(new ImportResult(saved, false)));
        var mvc = mvc(service);
        var request = mvc.perform(post("/api/library/import/posts").contentType("application/json")
            .content("{\"channelId\":\"" + CHANNEL + "\",\"messageId\":\"" + MESSAGE + "\"}"))
            .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false))
            .andExpect(jsonPath("$.post.key").value(saved.key())).andExpect(jsonPath("$.post.mainMessageId").value(MESSAGE));
        verify(service).importPost(CHANNEL, MESSAGE); verifyNoMoreInteractions(service);
    }

    @Test void invalidOrMissingParametersFailBeforeAnyQueuedOrNativeWork() throws Exception {
        var resources = mock(ResourceService.class); var repository = mock(ResourceRepository.class);
        var reader = mock(ResourceReader.class); var channels = mock(ChannelReader.class); var telegram = mock(TelegramService.class);
        var service = new ChannelImportService(resources, repository, reader, channels, telegram);
        var mvc = mvc(service);
        for (String body : List.of("{}", "{\"channelId\":null}", "{\"channelId\":\"0\"}", "{\"channelId\":\"9223372036854775808\"}")) {
            mvc.perform(post("/api/library/import/preview").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        for (String body : List.of("{}", "{\"channelId\":\"-1001\"}", "{\"channelId\":\"-1001\",\"messageId\":\"-1\"}",
                "{\"channelId\":\"-1001\",\"messageId\":\"1.2\"}")) {
            mvc.perform(post("/api/library/import/posts").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(resources, repository, reader, channels, telegram);
    }

    @Test void malformedJsonIsAClientErrorAndDoesNotReachTheService() throws Exception {
        var service = mock(ChannelImportService.class);
        mvc(service).perform(post("/api/library/import/posts").contentType("application/json").content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("请求格式无效，请检查设置后重试"));
        verifyNoInteractions(service);
    }

    @Test void safeOwnershipFailureUsesTheExistingAsynchronousApiErrorContract() throws Exception {
        var service = mock(ChannelImportService.class);
        when(service.importPost("-1001", "100")).thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException("该频道不是当前账号管理的频道")));
        var mvc = mvc(service);
        var request = mvc.perform(post("/api/library/import/posts").contentType("application/json")
            .content("{\"channelId\":\"-1001\",\"messageId\":\"100\"}")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("该频道不是当前账号管理的频道"));
    }

    @Test void allImportEndpointsRequireTheLocalTokenBeforeCallingTheService() throws Exception {
        var service = mock(ChannelImportService.class); var access = access();
        var mvc = MockMvcBuilders.standaloneSetup(new ChannelImportController(service)).addFilters(access).build();
        mvc.perform(get("/api/library/import/channels").header("Host", "127.0.0.1:43127")).andExpect(status().isUnauthorized());
        for (String path : List.of("preview", "posts")) {
            mvc.perform(post("/api/library/import/" + path).header("Host", "127.0.0.1:43127")
                .contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/library/import/channels").header("Host", "127.0.0.1:43127").header("X-App-Token", "wrong-token"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void aValidLocalTokenCanUseTheImportApiWithExistingSecurityHeaders() throws Exception {
        var service = mock(ChannelImportService.class); var access = access();
        String token = (String) ReflectionTestUtils.getField(access, "token"); assertNotNull(token);
        when(service.preview("-1001")).thenReturn(CompletableFuture.completedFuture(new Preview(new ManagedChannel("-1001", "Synthetic"),
            List.of(), "RECENT_CHANNEL_POSTS", 30, List.of())));
        var mvc = MockMvcBuilders.standaloneSetup(new ChannelImportController(service)).addFilters(access).build();
        var request = mvc.perform(post("/api/library/import/preview").header("Host", "127.0.0.1:43127").header("X-App-Token", token)
            .header("Origin", "http://127.0.0.1:43127").contentType("application/json").content("{\"channelId\":\"-1001\"}"))
            .andExpect(request().asyncStarted()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk()); verify(service).preview("-1001");
    }

    @Test void importMutationsStillRequireJsonEvenWithAValidToken() throws Exception {
        var service = mock(ChannelImportService.class); var access = access();
        String token = (String) ReflectionTestUtils.getField(access, "token");
        var mvc = MockMvcBuilders.standaloneSetup(new ChannelImportController(service)).addFilters(access).build();
        mvc.perform(post("/api/library/import/posts").header("Host", "127.0.0.1:43127").header("X-App-Token", token)
            .contentType("text/plain").content("{}")).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }

    private static MockMvc mvc(ChannelImportService service) {
        return MockMvcBuilders.standaloneSetup(new ChannelImportController(service)).setControllerAdvice(new ApiErrors()).build();
    }
    private static LocalAccess access() {
        var filter = new LocalAccess(mock(AppPaths.class)); ReflectionTestUtils.setField(filter, "port", 43127); return filter;
    }
}
