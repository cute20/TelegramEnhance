package io.github.telegramenhance.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ChannelImportRunModels.RunView;
import io.github.telegramenhance.library.ChannelImportRunRepository;
import io.github.telegramenhance.library.ChannelImportRunService;
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

class ChannelImportRunControllerTest {
    private static final String CHANNEL = "-1009007199254740999", RUN = "f5584105-97da-4a0e-9073-a2430165abf4";

    @Test void noSavedRunHasAnExplicitJsonNullBody() throws Exception {
        var service = mock(ChannelImportRunService.class); when(service.state(CHANNEL)).thenReturn(null);
        mvc(service).perform(get("/api/library/import/all/state").param("channelId", CHANNEL)).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json")).andExpect(content().string("null"));
        verify(service).state(CHANNEL);
    }

    @Test void startDefaultsToResumeAndAnExplicitRestartKeepsItsCasParameters() throws Exception {
        var service = mock(ChannelImportRunService.class);
        when(service.start(CHANNEL, false, null, null)).thenReturn(CompletableFuture.completedFuture(view(0)));
        when(service.start(CHANNEL, true, RUN, 7L)).thenReturn(CompletableFuture.completedFuture(view(0)));
        var mvc = mvc(service);
        var first = mvc.perform(post("/api/library/import/all/start").contentType("application/json")
            .content("{\"channelId\":\"" + CHANNEL + "\"}")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(first)).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0))
            .andExpect(jsonPath("$.channelId").value(CHANNEL)).andExpect(jsonPath("$.runId").value(RUN));
        var restart = mvc.perform(post("/api/library/import/all/start").contentType("application/json")
            .content("{\"channelId\":\"" + CHANNEL + "\",\"restart\":true,\"runId\":\"" + RUN + "\",\"revision\":7}"))
            .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(restart)).andExpect(status().isOk());
        verify(service).start(CHANNEL, false, null, null); verify(service).start(CHANNEL, true, RUN, 7L);
    }

    @Test void stepReturnsProgressOnlyWithoutPersistedPendingMetadata() throws Exception {
        var service = mock(ChannelImportRunService.class); when(service.step(CHANNEL, RUN, 4L)).thenReturn(CompletableFuture.completedFuture(view(5)));
        var mvc = mvc(service);
        var request = mvc.perform(post("/api/library/import/all/step").contentType("application/json")
            .content("{\"channelId\":\"" + CHANNEL + "\",\"runId\":\"" + RUN + "\",\"revision\":4}"))
            .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(5))
            .andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.pending").doesNotExist())
            .andExpect(jsonPath("$.pendingJson").doesNotExist()).andExpect(jsonPath("$.post").doesNotExist());
        verify(service).step(CHANNEL, RUN, 4L);
    }

    @Test void invalidCheckpointOrChannelParametersNeverReachTheQueueOrNativeReaders() throws Exception {
        var work = mock(ResourceService.class); var repository = mock(ChannelImportRunRepository.class);
        var channels = mock(ChannelReader.class); var posts = mock(ResourceReader.class); var telegram = mock(TelegramService.class);
        var service = new ChannelImportRunService(work, repository, channels, posts, telegram); var mvc = mvc(service);
        mvc.perform(get("/api/library/import/all/state")).andExpect(status().isBadRequest());
        for (String body : List.of("{}", "{\"channelId\":\"0\"}", "{\"channelId\":\"-1001\",\"revision\":-1}")) {
            mvc.perform(post("/api/library/import/all/start").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        for (String body : List.of("{}", "{\"channelId\":\"-1001\"}", "{\"channelId\":\"-1001\",\"runId\":\"run\",\"revision\":-1}")) {
            mvc.perform(post("/api/library/import/all/step").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(work, repository, channels, posts, telegram);
    }

    @Test void asynchronousCasConflictsUseTheExistingSafeErrorContract() throws Exception {
        var service = mock(ChannelImportRunService.class);
        when(service.step(CHANNEL, RUN, 4L)).thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException("频道导入检查点已变化，请读取最新进度后继续")));
        var mvc = mvc(service);
        var request = mvc.perform(post("/api/library/import/all/step").contentType("application/json")
            .content("{\"channelId\":\"" + CHANNEL + "\",\"runId\":\"" + RUN + "\",\"revision\":4}"))
            .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("频道导入检查点已变化，请读取最新进度后继续"));
    }

    @Test void allRunEndpointsRemainProtectedByTheLocalToken() throws Exception {
        var service = mock(ChannelImportRunService.class); var access = new LocalAccess(mock(AppPaths.class)); ReflectionTestUtils.setField(access, "port", 43127);
        var mvc = MockMvcBuilders.standaloneSetup(new ChannelImportRunController(service)).addFilters(access).build();
        mvc.perform(get("/api/library/import/all/state").header("Host", "127.0.0.1:43127").param("channelId", CHANNEL)).andExpect(status().isUnauthorized());
        for (String endpoint : List.of("start", "step")) {
            mvc.perform(post("/api/library/import/all/" + endpoint).header("Host", "127.0.0.1:43127").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
    }

    private static RunView view(long revision) { return new RunView(CHANNEL, RUN, revision, "READY", 1, 1, 0, 0, false, null); }
    private static MockMvc mvc(ChannelImportRunService service) {
        return MockMvcBuilders.standaloneSetup(new ChannelImportRunController(service)).setControllerAdvice(new ApiErrors()).build();
    }
}
