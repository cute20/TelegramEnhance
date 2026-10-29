package io.github.telegramenhance.api;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.DownloadChannelModels.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class DownloadChannelControllerTest{
    @Test void routesOnlyUseIndependentPreviewServiceAndRevisionIsExplicit()throws Exception{
        var service=mock(DownloadChannelService.class);var input=new Input("@synthetic","旅行","周末");when(service.start(input)).thenReturn(CompletableFuture.completedFuture(null));when(service.next("id",new Position(1))).thenReturn(CompletableFuture.completedFuture(null));var mvc=MockMvcBuilders.standaloneSetup(new DownloadChannelController(service)).setControllerAdvice(new ApiErrors()).build();
        var request=mvc.perform(post("/api/transfers/download/channels/preview").contentType("application/json").content("{\"source\":\"@synthetic\",\"tags\":\"旅行\",\"keywords\":\"周末\"}")).andExpect(request().asyncStarted()).andReturn();mvc.perform(asyncDispatch(request)).andExpect(status().isOk());verify(service).start(input);
        request=mvc.perform(post("/api/transfers/download/channels/preview/id/next").contentType("application/json").content("{\"revision\":1}")).andExpect(request().asyncStarted()).andReturn();mvc.perform(asyncDispatch(request)).andExpect(status().isOk());verify(service).next("id",new Position(1));mvc.perform(get("/api/transfers/download/channels/preview/id")).andExpect(status().isOk());verify(service).get("id");
    }
}
