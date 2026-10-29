package io.github.telegramenhance.api;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.DownloadBatchModels.*;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class DownloadBatchControllerTest{
    @Test void batchHistoryStatusAndAtomicControlsHaveDistinctRoutes()throws Exception{
        var service=mock(DownloadBatchService.class);
        when(service.list(10,0)).thenReturn(new DownloadBatchService.Listing(List.of(),null));
        var mvc=MockMvcBuilders.standaloneSetup(new DownloadBatchController(service)).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/api/transfers/download/batches")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/transfers/download/batches?limit=5&offset=10")).andExpect(status().isOk());
        mvc.perform(get("/api/transfers/download/batches/batch-id")).andExpect(status().isOk());
        mvc.perform(post("/api/transfers/download/batches/batch-id/pause")).andExpect(status().isOk());
        mvc.perform(post("/api/transfers/download/batches/batch-id/resume")).andExpect(status().isOk());
        verify(service).list(10,0);verify(service).list(5,10);verify(service).status("batch-id");verify(service).pause("batch-id");verify(service).resume("batch-id");
        verifyNoMoreInteractions(service);
        mvc.perform(get("/api/transfers/download/batches?limit=invalid")).andExpect(status().isBadRequest());
    }
    @Test void preparationLookupAndExplicitCreationHaveIndependentRoutes()throws Exception{
        var service=mock(DownloadBatchService.class);when(service.next("id",new Position(2))).thenReturn(CompletableFuture.completedFuture(null));var mvc=MockMvcBuilders.standaloneSetup(new DownloadBatchController(service)).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(post("/api/transfers/download/batches/preview").contentType("application/json").content("{\"scanId\":\"scan\",\"revision\":1,\"postKeys\":[\"key\"],\"types\":[\"VIDEO\"],\"includeMain\":false,\"targetDirectory\":\"x\"}")).andExpect(status().isOk());verify(service).start(any(Input.class));
        mvc.perform(get("/api/transfers/download/batches/preview/id")).andExpect(status().isOk());verify(service).get("id");var request=mvc.perform(post("/api/transfers/download/batches/preview/id/next").contentType("application/json").content("{\"revision\":2}")).andExpect(request().asyncStarted()).andReturn();mvc.perform(asyncDispatch(request)).andExpect(status().isOk());
        mvc.perform(post("/api/transfers/download/batches").contentType("application/json").content("{\"operationId\":\"op\",\"previewId\":\"id\",\"revision\":3}")).andExpect(status().isOk());verify(service).create(new Create("op","id",3));mvc.perform(get("/api/transfers/download/batches/by-operation/op")).andExpect(status().isOk());verify(service).lookup("op");
    }
}
