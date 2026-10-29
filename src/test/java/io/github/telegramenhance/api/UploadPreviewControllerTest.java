package io.github.telegramenhance.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.telegramenhance.transfer.UploadPreviewModels.Input;
import io.github.telegramenhance.transfer.UploadPreviewService;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UploadPreviewControllerTest {
    @Test void previewAndRecheckAreSeparateReadOnlyPreparationRoutes() throws Exception {
        var service = mock(UploadPreviewService.class); when(service.preview(any())).thenReturn(CompletableFuture.completedFuture(null)); when(service.verify("snapshot")).thenReturn(CompletableFuture.completedFuture(null));
        var mvc = MockMvcBuilders.standaloneSetup(new UploadPreviewController(service)).setControllerAdvice(new ApiErrors()).build();
        var request = mvc.perform(post("/api/transfers/upload/preview").contentType("application/json").content("{\"link\":\"https://t.me/test/1\",\"sourceDirectory\":\"D:/source\",\"archiveDirectory\":\"D:/archive\",\"recursive\":true,\"captionMode\":\"FILENAME\"}")).andExpect(request().asyncStarted()).andReturn(); mvc.perform(asyncDispatch(request)).andExpect(status().isOk());
        verify(service).preview(new Input("https://t.me/test/1", "D:/source", "D:/archive", true, "FILENAME"));
        mvc.perform(get("/api/transfers/upload/preview/snapshot")).andExpect(status().isOk()); verify(service).get("snapshot");
        var check = mvc.perform(post("/api/transfers/upload/preview/snapshot/verify").contentType("application/json").content("{}")).andExpect(request().asyncStarted()).andReturn(); mvc.perform(asyncDispatch(check)).andExpect(status().isOk()); verify(service).verify("snapshot");
        verifyNoMoreInteractions(service);
    }
}
