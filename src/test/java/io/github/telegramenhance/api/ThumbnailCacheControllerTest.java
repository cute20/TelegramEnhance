package io.github.telegramenhance.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.telegramenhance.library.LibraryPreviewService;
import io.github.telegramenhance.library.ThumbnailCache;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ThumbnailCacheControllerTest {
    @Test void settingsAndClearAreIndependentOfConnectionSettingsAndInvalidateMemory() throws Exception {
        var cache = mock(ThumbnailCache.class); var previews = mock(LibraryPreviewService.class);
        var value = new ThumbnailCache.View("D:/cache", "D:/default", 0, 0, 268435456, null);
        when(cache.view()).thenReturn(value); when(cache.save(any())).thenReturn(value); when(cache.clear()).thenReturn(new ThumbnailCache.Cleared(value, 2));
        var mvc = MockMvcBuilders.standaloneSetup(new ThumbnailCacheController(cache, previews)).build();
        mvc.perform(get("/api/settings/thumbnail-cache")).andExpect(status().isOk()).andExpect(jsonPath("$.directory").value("D:/cache"));
        verifyNoInteractions(previews);
        mvc.perform(put("/api/settings/thumbnail-cache").contentType(MediaType.APPLICATION_JSON).content("{\"directory\":\"D:/cache\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/settings/thumbnail-cache/clear").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.removed").value(2)).andExpect(jsonPath("$.cache.files").value(0));
        verify(previews, times(2)).clearMemory();
    }
    @Test void failedDiskClearStillInvalidatesMemoryAndReportsAnExplicitError() throws Exception {
        var cache = mock(ThumbnailCache.class); var previews = mock(LibraryPreviewService.class);
        when(cache.clear()).thenThrow(new IllegalArgumentException("部分缩略图未能清理"));
        MockMvcBuilders.standaloneSetup(new ThumbnailCacheController(cache, previews)).setControllerAdvice(new ApiErrors()).build()
            .perform(post("/api/settings/thumbnail-cache/clear")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("部分缩略图未能清理"));
        verify(previews).clearMemory();
    }
}
