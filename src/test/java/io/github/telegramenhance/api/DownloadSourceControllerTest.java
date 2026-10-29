package io.github.telegramenhance.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.telegramenhance.transfer.DownloadSourceService;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DownloadSourceControllerTest {
    @Test void sourceLinkIsPassedOnlyToTheIndependentTransferPreviewService() throws Exception {
        var sources = mock(DownloadSourceService.class); when(sources.preview("https://t.me/synthetic/1")).thenReturn(CompletableFuture.completedFuture(null));
        var mvc = MockMvcBuilders.standaloneSetup(new DownloadSourceController(sources)).setControllerAdvice(new ApiErrors()).build();
        var request = mvc.perform(post("/api/transfers/download/preview").contentType("application/json").content("{\"link\":\"https://t.me/synthetic/1\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk()); verify(sources).preview("https://t.me/synthetic/1");
    }
    @Test void nextPageKeepsTheNativeCursorAsAStringAndMissingOrMalformedInputIsRejected() throws Exception {
        var sources = mock(DownloadSourceService.class); when(sources.next("preview-id", "9007199254740999", 1)).thenReturn(CompletableFuture.completedFuture(null));
        var mvc = MockMvcBuilders.standaloneSetup(new DownloadSourceController(sources)).setControllerAdvice(new ApiErrors()).build();
        var request = mvc.perform(post("/api/transfers/download/preview/next").contentType("application/json")
                .content("{\"id\":\"preview-id\",\"cursor\":\"9007199254740999\",\"pageNumber\":1}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk()); verify(sources).next("preview-id", "9007199254740999", 1);
        mvc.perform(post("/api/transfers/download/preview").contentType("application/json").content("null")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/transfers/download/preview/next").contentType("application/json").content("{\"pageNumber\":\"bad\"}")).andExpect(status().isBadRequest());
    }
}
