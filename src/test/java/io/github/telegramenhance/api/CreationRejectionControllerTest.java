package io.github.telegramenhance.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.telegramenhance.transfer.CreationRejectedException;
import io.github.telegramenhance.transfer.DownloadBatchService;
import io.github.telegramenhance.transfer.DownloadTaskService;
import io.github.telegramenhance.transfer.UploadRetryService;
import io.github.telegramenhance.transfer.UploadTaskService;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CreationRejectionControllerTest {
    @Test void onlyExplicitPreCommitRefusalsCarryTheEditableCreationCode() throws Exception {
        var downloads = mock(DownloadTaskService.class);
        var uploads = mock(UploadTaskService.class);
        var batches = mock(DownloadBatchService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DownloadTaskController(downloads),
                new UploadTaskController(uploads, mock(UploadRetryService.class)), new DownloadBatchController(batches))
                .setControllerAdvice(new ApiErrors()).build();

        when(downloads.create(any())).thenThrow(new CreationRejectedException("Invalid directory"));
        mvc.perform(post("/api/transfers/download/tasks").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CREATION_REJECTED"))
                .andExpect(jsonPath("$.message").value("Invalid directory"));

        when(uploads.create(any())).thenReturn(CompletableFuture.failedFuture(new CreationRejectedException("Preview expired")));
        var upload = mvc.perform(post("/api/transfers/upload/tasks").contentType("application/json").content("{}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(upload)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREATION_REJECTED"));

        when(batches.create(any())).thenThrow(new CreationRejectedException("Batch preview expired"));
        mvc.perform(post("/api/transfers/download/batches").contentType("application/json")
                        .content("{\"operationId\":\"operation\",\"previewId\":\"preview\",\"revision\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CREATION_REJECTED"));

        doReturn(CompletableFuture.failedFuture(new IllegalArgumentException("Session changed after commit"))).when(downloads).create(any());
        var committed = mvc.perform(post("/api/transfers/download/tasks").contentType("application/json").content("{}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(committed)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").doesNotExist());

        doReturn(CompletableFuture.failedFuture(new IllegalStateException("Storage outcome unknown"))).when(uploads).create(any());
        var uncertain = mvc.perform(post("/api/transfers/upload/tasks").contentType("application/json").content("{}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(uncertain)).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").doesNotExist());
    }
}
