package io.github.telegramenhance.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.telegramenhance.library.LibraryPreviewService;
import io.github.telegramenhance.telegram.PostPreviewReader.Preview;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DownloadThumbnailControllerTest {
    @Test void thumbnailRequestsKeepSourceAndOriginPageScopeAndValidateQueryTypes() throws Exception {
        var previews = mock(LibraryPreviewService.class);
        when(previews.downloadMediaPreview("source", 2, "observed-media")).thenReturn(CompletableFuture.completedFuture(Preview.unavailable()));
        var mvc = MockMvcBuilders.standaloneSetup(new DownloadThumbnailController(previews)).setControllerAdvice(new ApiErrors()).build();
        var first = mvc.perform(get("/api/transfers/download/preview/source/thumbnail?mediaKey=observed-media&pageNumber=2"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(first)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNAVAILABLE"));
        verify(previews).downloadMediaPreview("source", 2, "observed-media");
        mvc.perform(get("/api/transfers/download/preview/source/thumbnail?pageNumber=bad")).andExpect(status().isBadRequest());
    }
}
