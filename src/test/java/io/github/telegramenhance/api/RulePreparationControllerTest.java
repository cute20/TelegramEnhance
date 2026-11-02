package io.github.telegramenhance.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.telegramenhance.rules.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RulePreparationControllerTest {
    private static final String RULE = "00000000-0000-4000-8000-000000000001";
    private static final String OP = "00000000-0000-4000-8000-000000000002";
    private static final RulePreparationService.Request INPUT = new RulePreparationService.Request(OP, 2, "preview");

    @Test void startingAReadOnlyPreparationImmediatelyReturnsAQueryableReceiptWithoutAsyncHttpOrEnabling() throws Exception {
        var preparations = mock(RulePreparationService.class); var rules = mock(RuleService.class); var defaults = mock(RuleDefaultsService.class);
        var receipt = new RulePreparationService.View(OP, RULE, "1", 1, 2, "preview", "RUNNING", 100, null, null, null);
        when(preparations.start(RULE, INPUT)).thenReturn(receipt); when(preparations.lookup(RULE, OP)).thenReturn(receipt);
        var mvc = MockMvcBuilders.standaloneSetup(new RuleController(rules, defaults, preparations)).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(post("/api/rules/" + RULE + "/preparations").contentType("application/json")
                .content("{\"operationId\":\"" + OP + "\",\"revision\":2,\"kind\":\"preview\"}"))
                .andExpect(request().asyncNotStarted()).andExpect(status().isOk()).andExpect(jsonPath("$.operationId").value(OP))
                .andExpect(jsonPath("$.state").value("RUNNING")).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.result").isEmpty());
        mvc.perform(get("/api/rules/" + RULE + "/preparations/by-operation/" + OP))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("preview"));
        verify(preparations).start(RULE, INPUT); verify(preparations).lookup(RULE, OP); verifyNoMoreInteractions(preparations);
        verifyNoInteractions(rules, defaults);
    }
    @Test void completedAndFailedReceiptsPreserveTheirDifferentResultContracts() throws Exception {
        var preparations = mock(RulePreparationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new RuleController(mock(RuleService.class), mock(RuleDefaultsService.class), preparations))
                .setControllerAdvice(new ApiErrors()).build();
        when(preparations.lookup(RULE, OP)).thenReturn(new RulePreparationService.View(OP, RULE, "1", 1, 2, "preview", "COMPLETED", 100, 200L, null,
                new RuleModels.Preview(RULE, 1, 2, null)));
        mvc.perform(get("/api/rules/" + RULE + "/preparations/by-operation/" + OP)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED")).andExpect(jsonPath("$.result.ruleId").value(RULE))
                .andExpect(jsonPath("$.finishedAt").value(200)).andExpect(jsonPath("$.error").isEmpty());
        when(preparations.lookup(RULE, OP)).thenReturn(new RulePreparationService.View(OP, RULE, "1", 1, 2, "preview", "FAILED", 100, 201L, "目录已变化", null));
        mvc.perform(get("/api/rules/" + RULE + "/preparations/by-operation/" + OP)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED")).andExpect(jsonPath("$.error").value("目录已变化"))
                .andExpect(jsonPath("$.result").isEmpty());
    }
    @Test void invalidOperationsReturnTheirSpecificErrorAndUnknownLookupDoesNotStartWork() throws Exception {
        var preparations = mock(RulePreparationService.class); var rules = mock(RuleService.class);
        when(preparations.start(RULE, INPUT)).thenThrow(new IllegalArgumentException("同类准备正在进行"));
        var mvc = MockMvcBuilders.standaloneSetup(new RuleController(rules, mock(RuleDefaultsService.class), preparations)).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(post("/api/rules/" + RULE + "/preparations").contentType("application/json")
                .content("{\"operationId\":\"" + OP + "\",\"revision\":2,\"kind\":\"preview\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("同类准备正在进行"));
        mvc.perform(get("/api/rules/" + RULE + "/preparations/by-operation/" + OP)).andExpect(status().isOk()).andExpect(content().string("null"));
        verify(preparations).start(RULE, INPUT); verify(preparations).lookup(RULE, OP); verifyNoMoreInteractions(preparations); verifyNoInteractions(rules);
    }
}
