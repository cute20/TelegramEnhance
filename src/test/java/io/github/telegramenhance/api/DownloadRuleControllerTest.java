package io.github.telegramenhance.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.telegramenhance.downloadrules.DownloadRuleModels.*;
import io.github.telegramenhance.downloadrules.DownloadRuleService;
import io.github.telegramenhance.rules.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DownloadRuleControllerTest {
    @Test void downloadRulesHaveIndependentRoutesBesideExistingUploadRules()throws Exception {
        var download=mock(DownloadRuleService.class);var upload=mock(RuleService.class);
        when(download.list()).thenReturn(new Listing(List.of(),2));
        when(upload.list()).thenReturn(new RuleModels.Listing(List.of(),"OBSERVE_ONLY",30));
        when(download.versions("id")).thenReturn(List.of());
        var mvc=MockMvcBuilders.standaloneSetup(new DownloadRuleController(download),new RuleController(upload,mock(RuleDefaultsService.class))).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/api/rules/download")).andExpect(status().isOk()).andExpect(jsonPath("$.intervalSeconds").value(2));
        mvc.perform(get("/api/rules")).andExpect(status().isOk()).andExpect(jsonPath("$.intervalSeconds").value(30));
        mvc.perform(get("/api/rules/download/id")).andExpect(status().isOk());
        mvc.perform(get("/api/rules/download/id/versions")).andExpect(status().isOk());
        mvc.perform(get("/api/rules/download/by-operation/op")).andExpect(status().isOk()).andExpect(content().string("null"));
        mvc.perform(post("/api/rules/download/id/enabled").contentType("application/json").content("{\"revision\":3,\"enabled\":false}")).andExpect(status().isOk());
        mvc.perform(post("/api/rules/download/id/takeover").contentType("application/json").content("{\"revision\":3,\"taskId\":\"task\"}")).andExpect(status().isOk());
        verify(download).list();verify(upload).list();verify(download).get("id");verify(download).versions("id");verify(download).lookup("op");
        verify(download).toggle("id",new DownloadRuleService.Toggle(3,false));verify(download).takeover("id",new DownloadRuleService.Takeover(3,"task"));
        verifyNoMoreInteractions(upload,download);
    }
    @Test void scanningAndConfigurationResolveAsynchronouslyWithoutEnablingByController()throws Exception {
        var service=mock(DownloadRuleService.class);when(service.check("id",new DownloadRuleService.Position(2))).thenReturn(CompletableFuture.completedFuture(null));
        when(service.create(any())).thenReturn(CompletableFuture.completedFuture(null));
        var mvc=MockMvcBuilders.standaloneSetup(new DownloadRuleController(service)).setControllerAdvice(new ApiErrors()).build();
        var request=mvc.perform(post("/api/rules/download/id/check").contentType("application/json").content("{\"revision\":2}")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk());
        var create=mvc.perform(post("/api/rules/download").contentType("application/json").content("{\"operationId\":\"op\",\"input\":{\"name\":\"sample\",\"source\":\"-1001\",\"tags\":\"\",\"keywords\":\"\",\"types\":[\"DOCUMENT\"],\"includeMain\":false,\"targetDirectory\":\"D:/out\",\"window\":{\"mode\":\"ALL_DAY\",\"timezone\":\"Asia/Shanghai\"}}}")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(create)).andExpect(status().isOk());verify(service).check("id",new DownloadRuleService.Position(2));verify(service).create(any());verifyNoMoreInteractions(service);
    }
}
