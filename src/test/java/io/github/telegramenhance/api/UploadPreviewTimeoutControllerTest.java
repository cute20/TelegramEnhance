package io.github.telegramenhance.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.telegramenhance.transfer.UploadPreviewModels;
import io.github.telegramenhance.transfer.UploadPreviewService;
import jakarta.servlet.AsyncEvent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UploadPreviewTimeoutControllerTest {
    private static final String ROOT = "/api/transfers/upload/preview";
    private static final UploadPreviewModels.Input INPUT = new UploadPreviewModels.Input(
            "https://t.me/synthetic/1", "D:/synthetic-source", "D:/synthetic-archive", true, "FILENAME");
    private static final String BODY = """
            {"link":"https://t.me/synthetic/1","sourceDirectory":"D:/synthetic-source",
             "archiveDirectory":"D:/synthetic-archive","recursive":true,"captionMode":"FILENAME"}
            """;

    @Test void initialPreviewAndVerificationUseTheLongerLocalDeadlineAndReturnTheirActualViews() throws Exception {
        for (boolean verify : List.of(false, true)) {
            var service = mock(UploadPreviewService.class); var future = new CompletableFuture<UploadPreviewModels.View>();
            if (verify) when(service.verify("snapshot")).thenReturn(future); else when(service.preview(INPUT)).thenReturn(future);
            var mvc = MockMvcBuilders.standaloneSetup(new UploadPreviewController(service)).setControllerAdvice(new ApiErrors()).build();
            var pending = mvc.perform(post(verify ? ROOT + "/snapshot/verify" : ROOT).contentType("application/json").content(verify ? "{}" : BODY))
                    .andExpect(request().asyncStarted()).andReturn();
            assertEquals(135_000, pending.getRequest().getAsyncContext().getTimeout()); assertFalse(future.isDone());
            future.complete(view());
            mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value("snapshot"))
                    .andExpect(jsonPath("$.state").value("PREVIEW_ONLY"));
            if (verify) verify(service).verify("snapshot"); else verify(service).preview(INPUT);
            verifyNoMoreInteractions(service);
        }
    }

    @Test void asynchronousPreparationErrorsRemainSpecificBadRequests() throws Exception {
        for (boolean verify : List.of(false, true)) {
            var service = mock(UploadPreviewService.class); var future = new CompletableFuture<UploadPreviewModels.View>();
            if (verify) when(service.verify("snapshot")).thenReturn(future); else when(service.preview(INPUT)).thenReturn(future);
            var mvc = MockMvcBuilders.standaloneSetup(new UploadPreviewController(service)).setControllerAdvice(new ApiErrors()).build();
            var pending = mvc.perform(post(verify ? ROOT + "/snapshot/verify" : ROOT).contentType("application/json").content(verify ? "{}" : BODY))
                    .andExpect(request().asyncStarted()).andReturn();
            future.completeExceptionally(new CompletionException(new IllegalArgumentException("预览期间文件已变化")));
            mvc.perform(asyncDispatch(pending)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("预览期间文件已变化"));
            if (verify) verify(service).verify("snapshot"); else verify(service).preview(INPUT);
            verifyNoMoreInteractions(service);
        }
    }

    @Test void expiredPreviewResponsesDoNotCancelOrReplayPreparationAndLateResultsCannotDispatchAgain() throws Exception {
        for (boolean verify : List.of(false, true)) {
            var service = mock(UploadPreviewService.class); var future = new CompletableFuture<UploadPreviewModels.View>();
            if (verify) when(service.verify("snapshot")).thenReturn(future); else when(service.preview(INPUT)).thenReturn(future);
            var mvc = MockMvcBuilders.standaloneSetup(new UploadPreviewController(service)).setControllerAdvice(new ApiErrors()).build();
            var pending = mvc.perform(post(verify ? ROOT + "/snapshot/verify" : ROOT).contentType("application/json").content(verify ? "{}" : BODY))
                    .andExpect(request().asyncStarted()).andReturn();
            var context = (MockAsyncContext) pending.getRequest().getAsyncContext();
            var dispatches = new AtomicInteger(); context.addDispatchHandler(dispatches::incrementAndGet);
            for (var listener : List.copyOf(context.getListeners())) listener.onTimeout(new AsyncEvent(context));
            mvc.perform(asyncDispatch(pending)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value(verify
                            ? "上传预览核对结果尚未确认，请读取当前预览后再手动核对；不会自动重试、创建任务或发送文件"
                            : "上传预览结果尚未确认，请核对连接和目录后手动重新预览；不会自动重试、创建任务或发送文件"));
            assertFalse(future.isDone()); assertFalse(future.isCancelled()); assertEquals(1, dispatches.get());
            assertTrue(future.complete(view())); assertEquals(1, dispatches.get());
            if (verify) verify(service).verify("snapshot"); else verify(service).preview(INPUT);
            verifyNoMoreInteractions(service);
        }
    }

    private static UploadPreviewModels.View view() {
        return new UploadPreviewModels.View("snapshot", "1", "PREVIEW_ONLY", INPUT.sourceDirectory(), INPUT.archiveDirectory(), true,
                "FILENAME", "Synthetic channel", "Synthetic post", "Synthetic body", INPUT.link(), "Synthetic discussion", "MEDIA_ALLOWED_AT_CHECK", 0,
                0, 0, List.of(), 0, 0, 0, 0, List.of(), 1, 1, 600_001);
    }
}
