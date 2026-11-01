package io.github.telegramenhance.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.telegramenhance.transfer.BotDownloadService;
import io.github.telegramenhance.transfer.DownloadModels;
import io.github.telegramenhance.transfer.DownloadModels.TaskView;
import jakarta.servlet.AsyncEvent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BotDownloadControllerTest {
    private static final String ROOT = "/api/transfers/download/bots";
    private static final String BODY = """
            {"operationId":"00000000-0000-4000-8000-000000000001",
             "sourceId":"00000000-0000-4000-8000-000000000002","revision":2,
             "mediaKeys":["1:9:100:0"],"targetDirectory":"D:/bot-test"}
            """;
    private static final BotDownloadService.Create INPUT = new BotDownloadService.Create(
            "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002",
            2, List.of("1:9:100:0"), "D:/bot-test");

    @Test void pendingCreationHasItsOwnLongerHttpDeadlineAndReturnsTheOriginalTaskWhenComplete() throws Exception {
        var service = mock(BotDownloadService.class); var future = new CompletableFuture<TaskView>();
        when(service.create(INPUT)).thenReturn(future);
        var mvc = MockMvcBuilders.standaloneSetup(new BotDownloadController(service)).setControllerAdvice(new ApiErrors()).build();
        var pending = mvc.perform(post(ROOT + "/tasks").contentType("application/json").content(BODY))
                .andExpect(request().asyncStarted()).andReturn();
        var context = (MockAsyncContext) pending.getRequest().getAsyncContext();
        assertEquals(135_000, context.getTimeout()); assertFalse(future.isDone());
        future.complete(task());
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("original-task")).andExpect(jsonPath("$.state").value("QUEUED"));
        verify(service).create(INPUT); verifyNoMoreInteractions(service);
    }

    @Test void asynchronousValidationFailuresKeepTheirOriginalBadRequestMessage() throws Exception {
        var service = mock(BotDownloadService.class); var future = new CompletableFuture<TaskView>();
        when(service.create(INPUT)).thenReturn(future);
        var mvc = MockMvcBuilders.standaloneSetup(new BotDownloadController(service)).setControllerAdvice(new ApiErrors()).build();
        var pending = mvc.perform(post(ROOT + "/tasks").contentType("application/json").content(BODY))
                .andExpect(request().asyncStarted()).andReturn();
        future.completeExceptionally(new CompletionException(new IllegalArgumentException("源文件已变化，请刷新核对")));
        mvc.perform(asyncDispatch(pending)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("源文件已变化，请刷新核对"));
        verify(service).create(INPUT); verifyNoMoreInteractions(service);
    }

    @Test void synchronousValidationFailuresStillReturnBadRequestWithoutStartingAsyncWork() throws Exception {
        var service = mock(BotDownloadService.class);
        when(service.create(INPUT)).thenThrow(new IllegalArgumentException("保存目录无效"));
        var mvc = MockMvcBuilders.standaloneSetup(new BotDownloadController(service)).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(post(ROOT + "/tasks").contentType("application/json").content(BODY))
                .andExpect(request().asyncNotStarted()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("保存目录无效"));
        verify(service).create(INPUT); verifyNoMoreInteractions(service);
    }

    @Test void timeoutReportsAnUnknownOutcomeWithoutCancellingOrReplayingTheOperationOrDispatchingLateResults() throws Exception {
        var service = mock(BotDownloadService.class); var future = new CompletableFuture<TaskView>();
        when(service.create(INPUT)).thenReturn(future);
        var mvc = MockMvcBuilders.standaloneSetup(new BotDownloadController(service)).setControllerAdvice(new ApiErrors()).build();
        var pending = mvc.perform(post(ROOT + "/tasks").contentType("application/json").content(BODY))
                .andExpect(request().asyncStarted()).andReturn();
        var context = (MockAsyncContext) pending.getRequest().getAsyncContext();
        var dispatches = new AtomicInteger(); context.addDispatchHandler(dispatches::incrementAndGet);
        for (var listener : List.copyOf(context.getListeners())) listener.onTimeout(new AsyncEvent(context));
        mvc.perform(asyncDispatch(pending)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("创建结果尚未确认，请按原操作号核对，不要重复创建"));
        assertEquals(1, dispatches.get()); assertFalse(future.isCancelled()); assertFalse(future.isDone());
        assertTrue(future.complete(task())); assertEquals(1, dispatches.get());
        when(service.lookup(INPUT.operationId())).thenReturn(new BotDownloadService.Lookup(task()));
        mvc.perform(get(ROOT + "/tasks/by-operation/" + INPUT.operationId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value("original-task"));
        verify(service).create(INPUT); verify(service).lookup(INPUT.operationId()); verifyNoMoreInteractions(service);
    }

    private static TaskView task() {
        return new TaskView("original-task", "机器人资源", "机器人", "QUEUED", false, "D:/bot-test", "OBSERVED_SELECTION",
                1, 0, 0, 11, null, 1, 1, List.of(), DownloadModels.KEEP_DUPLICATES, 0, "D:/bot-test/重复媒体", null);
    }
}
