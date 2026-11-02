package io.github.telegramenhance.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.telegramenhance.rules.RuleDefaultsService;
import io.github.telegramenhance.rules.RuleModels;
import io.github.telegramenhance.rules.RuleService;
import jakarta.servlet.AsyncEvent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RuleControllerTimeoutTest {
    private static final String RULE = "00000000-0000-4000-8000-000000000001";
    private static final RuleService.Position POSITION = new RuleService.Position(2);

    @Test void observationAndPreviewWaitBeyondTheContainerDefaultAndReturnTheirActualResultsWithoutEnablingRules() throws Exception {
        for (String route : List.of("observe", "preview")) {
            var service = mock(RuleService.class); var defaults = mock(RuleDefaultsService.class);
            var observation = new CompletableFuture<RuleModels.View>(); var preview = new CompletableFuture<RuleModels.Preview>();
            if (route.equals("observe")) when(service.observe(RULE, POSITION)).thenReturn(observation);
            else when(service.preview(RULE, POSITION)).thenReturn(preview);
            var mvc = MockMvcBuilders.standaloneSetup(new RuleController(service, defaults)).setControllerAdvice(new ApiErrors()).build();
            var pending = mvc.perform(post("/api/rules/" + RULE + "/" + route).contentType("application/json").content("{\"revision\":2}"))
                    .andExpect(request().asyncStarted()).andReturn();
            assertEquals(route.equals("observe") ? 75_000 : 135_000, pending.getRequest().getAsyncContext().getTimeout());
            if (route.equals("observe")) {
                assertTrue(observation.complete(paused()));
                mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andExpect(jsonPath("$.rule.enabled").value(false))
                        .andExpect(jsonPath("$.state").value("PAUSED"));
                verify(service).observe(RULE, POSITION);
            } else {
                assertTrue(preview.complete(new RuleModels.Preview(RULE, 1, 2, null)));
                mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andExpect(jsonPath("$.ruleId").value(RULE))
                        .andExpect(jsonPath("$.version").value(1));
                verify(service).preview(RULE, POSITION);
            }
            verifyNoMoreInteractions(service); verifyNoInteractions(defaults);
        }
    }

    @Test void asynchronousValidationFailuresKeepTheirSpecificMessageAndDoNotStartAnotherOperation() throws Exception {
        for (String route : List.of("observe", "preview")) {
            var service = mock(RuleService.class); var defaults = mock(RuleDefaultsService.class);
            var observation = new CompletableFuture<RuleModels.View>(); var preview = new CompletableFuture<RuleModels.Preview>();
            if (route.equals("observe")) when(service.observe(RULE, POSITION)).thenReturn(observation);
            else when(service.preview(RULE, POSITION)).thenReturn(preview);
            var mvc = MockMvcBuilders.standaloneSetup(new RuleController(service, defaults)).setControllerAdvice(new ApiErrors()).build();
            var pending = mvc.perform(post("/api/rules/" + RULE + "/" + route).contentType("application/json").content("{\"revision\":2}"))
                    .andExpect(request().asyncStarted()).andReturn();
            var failure = new CompletionException(new IllegalArgumentException("目录内容已变化，请重新核对"));
            if (route.equals("observe")) observation.completeExceptionally(failure); else preview.completeExceptionally(failure);
            mvc.perform(asyncDispatch(pending)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("目录内容已变化，请重新核对"));
            if (route.equals("observe")) verify(service).observe(RULE, POSITION); else verify(service).preview(RULE, POSITION);
            verifyNoMoreInteractions(service); verifyNoInteractions(defaults);
        }
    }

    @Test void synchronousValidationErrorsStillReturnBadRequestBeforeStartingAsyncWork() throws Exception {
        for (String route : List.of("observe", "preview")) {
            var service = mock(RuleService.class); var defaults = mock(RuleDefaultsService.class);
            if (route.equals("observe")) when(service.observe(RULE, POSITION)).thenThrow(new IllegalArgumentException("规则已变化，请刷新核对"));
            else when(service.preview(RULE, POSITION)).thenThrow(new IllegalArgumentException("规则已变化，请刷新核对"));
            var mvc = MockMvcBuilders.standaloneSetup(new RuleController(service, defaults)).setControllerAdvice(new ApiErrors()).build();
            mvc.perform(post("/api/rules/" + RULE + "/" + route).contentType("application/json").content("{\"revision\":2}"))
                    .andExpect(request().asyncNotStarted()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("规则已变化，请刷新核对"));
            if (route.equals("observe")) verify(service).observe(RULE, POSITION); else verify(service).preview(RULE, POSITION);
            verifyNoMoreInteractions(service); verifyNoInteractions(defaults);
        }
    }

    @Test void eachTimeoutReportsUncertaintyWithoutCancellingTheUnderlyingObservationOrEnablingOrReplaying() throws Exception {
        for (String route : List.of("observe", "preview")) {
            var service = mock(RuleService.class); var defaults = mock(RuleDefaultsService.class);
            var observation = new CompletableFuture<RuleModels.View>(); var preview = new CompletableFuture<RuleModels.Preview>();
            if (route.equals("observe")) when(service.observe(RULE, POSITION)).thenReturn(observation);
            else when(service.preview(RULE, POSITION)).thenReturn(preview);
            var mvc = MockMvcBuilders.standaloneSetup(new RuleController(service, defaults)).setControllerAdvice(new ApiErrors()).build();
            var pending = mvc.perform(post("/api/rules/" + RULE + "/" + route).contentType("application/json").content("{\"revision\":2}"))
                    .andExpect(request().asyncStarted()).andReturn();
            var dispatches = timeout(pending);
            mvc.perform(asyncDispatch(pending)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value(route.equals("observe")
                            ? "目录检测结果尚未确认，请刷新规则核对；不会自动重复检测或启用上传"
                            : "分组预览结果尚未确认，请刷新规则核对；不会自动重试、启用或发送文件"));
            CompletableFuture<?> underlying = route.equals("observe") ? observation : preview;
            assertFalse(underlying.isDone()); assertFalse(underlying.isCancelled()); assertEquals(1, dispatches.get());
            if (route.equals("observe")) observation.complete(paused()); else preview.complete(new RuleModels.Preview(RULE, 1, 2, null));
            assertEquals(1, dispatches.get());
            when(service.get(RULE)).thenReturn(paused());
            mvc.perform(get("/api/rules/" + RULE)).andExpect(status().isOk()).andExpect(jsonPath("$.rule.enabled").value(false));
            if (route.equals("observe")) verify(service).observe(RULE, POSITION); else verify(service).preview(RULE, POSITION);
            verify(service).get(RULE); verifyNoMoreInteractions(service); verifyNoInteractions(defaults);
        }
    }

    private static AtomicInteger timeout(MvcResult pending) throws Exception {
        var context = (MockAsyncContext) pending.getRequest().getAsyncContext();
        var dispatches = new AtomicInteger(); context.addDispatchHandler(dispatches::incrementAndGet);
        for (var listener : List.copyOf(context.getListeners())) listener.onTimeout(new AsyncEvent(context));
        return dispatches;
    }
    private static RuleModels.View paused() {
        var input = new RuleModels.Input("Synthetic rule", "https://t.me/synthetic/1", "D:/synthetic-source", "D:/synthetic-archive", true,
                "FILENAME", new RuleModels.Window("ALL_DAY", null, null, "Asia/Shanghai"), "AUTO_UPLOAD");
        var rule = new RuleModels.Rule(RULE, "1", 1, 2, false, 0, 1, 1, input, null);
        return new RuleModels.View(rule, "AUTO_UPLOAD", "PAUSED", false, 0, null);
    }
}
