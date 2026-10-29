package io.github.telegramenhance.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.telegramenhance.library.LibraryPreviewService;
import io.github.telegramenhance.telegram.PostPreviewReader.Preview;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LibraryPreviewControllerTest {
    @Test
    void previewEndpointIsAsynchronousAndNeverUsesClientVersionAsServiceIdentity() throws Exception {
        var service = mock(LibraryPreviewService.class);
        var preview = new Preview("AVAILABLE", "data:image/jpeg;base64,/9j/2Q==", 32, 24);
        when(service.preview("1:-1001:100")).thenReturn(CompletableFuture.completedFuture(preview));
        var mvc = MockMvcBuilders.standaloneSetup(new LibraryPreviewController(service)).setControllerAdvice(new ApiErrors()).build();
        var request = mvc.perform(get("/api/library/posts/1:-1001:100/preview").param("version", "untrusted-client-value"))
            .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.dataUrl").value(preview.dataUrl()))
            .andExpect(jsonPath("$.width").value(32)).andExpect(jsonPath("$.height").value(24));
        verify(service).preview("1:-1001:100"); verifyNoMoreInteractions(service);
    }

    @Test
    void missingPreviewIsAStableEmptyResultWithoutAFileLocation() throws Exception {
        var service = mock(LibraryPreviewService.class);
        when(service.preview("post")).thenReturn(CompletableFuture.completedFuture(new Preview("UNAVAILABLE", null, 0, 0)));
        var mvc = MockMvcBuilders.standaloneSetup(new LibraryPreviewController(service)).build();
        var request = mvc.perform(get("/api/library/posts/post/preview")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.dataUrl").doesNotExist()).andExpect(jsonPath("$.width").value(0)).andExpect(jsonPath("$.height").value(0));
    }

    @Test
    void safeServiceFailureUsesTheExistingApiErrorContract() throws Exception {
        var service = mock(LibraryPreviewService.class);
        when(service.preview("post")).thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException("预览队列已满，请稍后重试")));
        var mvc = MockMvcBuilders.standaloneSetup(new LibraryPreviewController(service)).setControllerAdvice(new ApiErrors()).build();
        var request = mvc.perform(get("/api/library/posts/post/preview")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("预览队列已满，请稍后重试"));
    }

    @Test
    void commentMediaEndpointPassesTheExactMediaIdentityAndIgnoresClientVersion() throws Exception {
        var service = mock(LibraryPreviewService.class);
        String key = "1:-1001:100", media = "1:-2001:601:0";
        var preview = new Preview("AVAILABLE", "data:image/jpeg;base64,/9j/2Q==", 32, 24, "THUMBNAIL");
        when(service.mediaPreview(key, media)).thenReturn(CompletableFuture.completedFuture(preview));
        var mvc = MockMvcBuilders.standaloneSetup(new LibraryPreviewController(service)).setControllerAdvice(new ApiErrors()).build();
        var request = mvc.perform(get("/api/library/posts/" + key + "/media-preview")
            .param("mediaKey", media).param("version", "untrusted-client-version")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andExpect(jsonPath("$.quality").value("THUMBNAIL"))
            .andExpect(jsonPath("$.dataUrl").value(preview.dataUrl()));
        verify(service).mediaPreview(key, media); verifyNoMoreInteractions(service);
    }

    @Test
    void missingCommentMediaKeyIsAClientErrorBeforeAnyReadIsScheduled() throws Exception {
        var service = mock(LibraryPreviewService.class);
        when(service.mediaPreview("post", null)).thenThrow(new IllegalArgumentException("媒体标识无效"));
        MockMvcBuilders.standaloneSetup(new LibraryPreviewController(service)).setControllerAdvice(new ApiErrors()).build()
            .perform(get("/api/library/posts/post/media-preview")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("媒体标识无效"));
        verify(service).mediaPreview("post", null);
    }

    @Test
    void outOfScopeCommentMediaUsesTheSafeClientErrorContract() throws Exception {
        var service = mock(LibraryPreviewService.class);
        when(service.mediaPreview("post", "foreign-media")).thenReturn(CompletableFuture.failedFuture(
            new IllegalArgumentException("评论媒体预览读取未完成，请刷新后重试")));
        var mvc = MockMvcBuilders.standaloneSetup(new LibraryPreviewController(service)).setControllerAdvice(new ApiErrors()).build();
        var request = mvc.perform(get("/api/library/posts/post/media-preview").param("mediaKey", "foreign-media"))
            .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("评论媒体预览读取未完成，请刷新后重试"));
    }
}
