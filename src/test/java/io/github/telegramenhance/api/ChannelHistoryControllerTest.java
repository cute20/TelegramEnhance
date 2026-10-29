package io.github.telegramenhance.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import io.github.telegramenhance.library.ChannelImportModels.*;
import io.github.telegramenhance.library.ChannelImportService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChannelHistoryControllerTest {
    @Test void channelHistoryReturnsOpaqueCursorAndStringMessageIdentity() throws Exception {
        var imports = mock(ChannelImportService.class);
        when(imports.historyPage("-1001", "opaque-position")).thenReturn(CompletableFuture.completedFuture(
                new HistoryPage(new ManagedChannel("-1001", "synthetic"), List.of(new Candidate("9007199254740999", null, "synthetic")),
                        "CHANNEL_PAGE", 10, "next-position", false, true, List.of())));
        var mvc = MockMvcBuilders.standaloneSetup(new ChannelHistoryController(imports)).setControllerAdvice(new ApiErrors()).build();
        var result = mvc.perform(post("/api/library/import/history").contentType("application/json")
                .content("{\"channelId\":\"-1001\",\"cursor\":\"opaque-position\"}")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].messageId").value("9007199254740999"))
                .andExpect(jsonPath("$.nextCursor").value("next-position")).andExpect(jsonPath("$.hasMore").value(true));
        verify(imports).historyPage("-1001", "opaque-position");
    }
    @Test void confirmedEmptyBoundaryIsDistinctFromAnEmptyContinuingPage() throws Exception {
        var imports = mock(ChannelImportService.class);
        when(imports.historyPage("-1001", null)).thenReturn(CompletableFuture.completedFuture(
                new HistoryPage(new ManagedChannel("-1001", "synthetic"), List.of(), "CHANNEL_PAGE", 10, null, true, false, List.of())));
        var mvc = MockMvcBuilders.standaloneSetup(new ChannelHistoryController(imports)).setControllerAdvice(new ApiErrors()).build();
        var result = mvc.perform(post("/api/library/import/history").contentType("application/json").content("{\"channelId\":\"-1001\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(jsonPath("$.exhausted").value(true)).andExpect(jsonPath("$.hasMore").value(false));
    }
}
