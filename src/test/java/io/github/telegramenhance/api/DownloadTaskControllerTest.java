package io.github.telegramenhance.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.telegramenhance.transfer.DownloadTaskService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DownloadTaskControllerTest {
    @Test void sourceRepairUsesAnExplicitBoundedAsyncRoute()throws Exception{
        var tasks=mock(DownloadTaskService.class);when(tasks.repairSourceLinks()).thenReturn(CompletableFuture.completedFuture(new DownloadTaskService.RepairedLinks(2,2,0)));
        var mvc=MockMvcBuilders.standaloneSetup(new DownloadTaskController(tasks)).setControllerAdvice(new ApiErrors()).build();
        var request=mvc.perform(post("/api/transfers/download/tasks/repair-source-links").contentType("application/json").content("{}")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(2));verify(tasks).repairSourceLinks();verifyNoMoreInteractions(tasks);
    }
    @Test void explicitCreationAndOperationLookupKeepTheSameOperationIdentity() throws Exception {
        var tasks = mock(DownloadTaskService.class); when(tasks.create(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(tasks.lookup("operation")).thenReturn(new DownloadTaskService.Lookup(null));
        var mvc = MockMvcBuilders.standaloneSetup(new DownloadTaskController(tasks)).setControllerAdvice(new ApiErrors()).build();
        var request = mvc.perform(post("/api/transfers/download/tasks").contentType("application/json").content("{\"operationId\":\"operation\",\"sourceId\":\"source\",\"mediaKeys\":[\"1:-2001:9007199254740999:0\"],\"includeMain\":false,\"targetDirectory\":\"D:/test\"}"))
                .andExpect(request().asyncStarted()).andReturn(); mvc.perform(asyncDispatch(request)).andExpect(status().isOk());
        verify(tasks).create(new DownloadTaskService.Create("operation", "source", List.of("1:-2001:9007199254740999:0"), false, "D:/test"));
        mvc.perform(get("/api/transfers/download/tasks/by-operation/operation")).andExpect(status().isOk()).andExpect(jsonPath("$.task").isEmpty());
    }
    @Test void taskListingAndPauseResumeUseSeparateReadAndMutationRoutes() throws Exception {
        var tasks = mock(DownloadTaskService.class); when(tasks.list(20, 0)).thenReturn(new DownloadTaskService.Listing(List.of(), null));
        var mvc = MockMvcBuilders.standaloneSetup(new DownloadTaskController(tasks)).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/api/transfers/download/tasks")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(post("/api/transfers/download/tasks/task/pause").contentType("application/json").content("{}")).andExpect(status().isOk());
        mvc.perform(post("/api/transfers/download/tasks/task/resume").contentType("application/json").content("{}")).andExpect(status().isOk());
        verify(tasks).pause("task"); verify(tasks).resume("task");
        mvc.perform(get("/api/transfers/download/tasks?limit=invalid")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/transfers/download/tasks").contentType("application/json").content("null")).andExpect(status().isBadRequest());
    }
}
