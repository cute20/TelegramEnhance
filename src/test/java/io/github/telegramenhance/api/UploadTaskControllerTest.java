package io.github.telegramenhance.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.telegramenhance.transfer.UploadTaskService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UploadTaskControllerTest {
    @Test void failedMemberCheckAndExplicitRetryHaveSeparateRoutesAndOperationLookup()throws Exception{
        var tasks=mock(UploadTaskService.class);var retries=mock(io.github.telegramenhance.transfer.UploadRetryService.class);when(retries.check("task")).thenReturn(CompletableFuture.completedFuture(null));when(retries.lookup("task","operation")).thenReturn(new UploadTaskService.Lookup(null));var mvc=MockMvcBuilders.standaloneSetup(new UploadTaskController(tasks,retries)).setControllerAdvice(new ApiErrors()).build();
        var checked=mvc.perform(post("/api/transfers/upload/tasks/task/retry-check").contentType("application/json").content("{}")).andExpect(request().asyncStarted()).andReturn();mvc.perform(asyncDispatch(checked)).andExpect(status().isOk());verify(retries).check("task");
        mvc.perform(post("/api/transfers/upload/tasks/task/retry-failed").contentType("application/json").content("{\"operationId\":\"operation\",\"checkId\":\"check\"}")).andExpect(status().isOk());verify(retries).retry("task",new io.github.telegramenhance.transfer.UploadRetryModels.Retry("operation","check"));
        mvc.perform(get("/api/transfers/upload/tasks/task/retry-operations/operation")).andExpect(status().isOk());verify(retries).lookup("task","operation");verifyNoInteractions(tasks);
    }
    @Test void creatingViewingStartingAndReconciliationUseDistinctExplicitRoutes()throws Exception{
        var tasks=mock(UploadTaskService.class);when(tasks.create(any())).thenReturn(CompletableFuture.completedFuture(null));when(tasks.list(20,0)).thenReturn(new UploadTaskService.Listing(List.of(),null));when(tasks.lookup("operation")).thenReturn(new UploadTaskService.Lookup(null));var mvc=MockMvcBuilders.standaloneSetup(new UploadTaskController(tasks,mock(io.github.telegramenhance.transfer.UploadRetryService.class))).setControllerAdvice(new ApiErrors()).build();
        var request=mvc.perform(post("/api/transfers/upload/tasks").contentType("application/json").content("{\"operationId\":\"operation\",\"previewId\":\"preview\"}")).andExpect(request().asyncStarted()).andReturn();mvc.perform(asyncDispatch(request)).andExpect(status().isOk());verify(tasks).create(new UploadTaskService.Create("operation","preview"));
        mvc.perform(get("/api/transfers/upload/tasks")).andExpect(status().isOk());verify(tasks).list(20,0);mvc.perform(get("/api/transfers/upload/tasks/by-operation/operation")).andExpect(status().isOk());verify(tasks).lookup("operation");
        for(String action:List.of("start","pause","reconcile","cancel")){mvc.perform(post("/api/transfers/upload/tasks/task/"+action).contentType("application/json").content("{}")).andExpect(status().isOk());verify(tasks).action("task",action);}
        mvc.perform(post("/api/transfers/upload/tasks/task/groups/3/accept-document").contentType("application/json").content("{}")).andExpect(status().isOk());verify(tasks).acceptDocument("task","3");
        when(tasks.recoverPending("task")).thenReturn(CompletableFuture.completedFuture(null));
        var recovery=mvc.perform(post("/api/transfers/upload/tasks/task/recover-pending").contentType("application/json").content("{}")).andExpect(request().asyncStarted()).andReturn();mvc.perform(asyncDispatch(recovery)).andExpect(status().isOk());verify(tasks).recoverPending("task");
        verifyNoMoreInteractions(tasks);
    }
}
