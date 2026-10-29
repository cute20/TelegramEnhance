package io.github.telegramenhance.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.telegramenhance.library.LibraryCatalogService;
import io.github.telegramenhance.library.LibraryModels.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LibraryControllerTest {
    @Test void catalogUsesBoundedDefaults() throws Exception {
        var service = mock(LibraryCatalogService.class);
        when(service.catalog(null, "", "", 20, 0)).thenReturn(new CatalogPage(List.of(), 20, 0, 0, 0, 0, false, "INDEXED_ONLY"));
        MockMvcBuilders.standaloneSetup(new LibraryController(service)).setControllerAdvice(new ApiErrors()).build()
                .perform(get("/api/library/catalog")).andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("INDEXED_ONLY"));
        verify(service).catalog(null, "", "", 20, 0);
    }
    @Test void repeatedChannelsStayStringsAndSearchInputsReachOneSharedService() throws Exception {
        var service = mock(LibraryCatalogService.class);
        var channels = List.of("-1009007199254740999", "-1002");
        when(service.catalog(channels, "#北京 #旅行", "风景 相机", 10, 20)).thenReturn(new CatalogPage(List.of(), 10, 20, 0, 3, 2, false, "INDEXED_ONLY"));
        MockMvcBuilders.standaloneSetup(new LibraryController(service)).setControllerAdvice(new ApiErrors()).build()
                .perform(get("/api/library/catalog").param("channelIds", channels.toArray(String[]::new))
                        .param("tags", "#北京 #旅行").param("keywords", "风景 相机").param("limit", "10").param("offset", "20"))
                .andExpect(status().isOk());
        verify(service).catalog(channels, "#北京 #旅行", "风景 相机", 10, 20);
    }
    @Test void channelIdentifiersAreSerializedAsStrings() throws Exception {
        var service = mock(LibraryCatalogService.class);
        when(service.channels()).thenReturn(List.of(new Channel("-1009007199254740999", "test channel", 3)));
        MockMvcBuilders.standaloneSetup(new LibraryController(service)).build().perform(get("/api/library/channels"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("\"id\":\"-1009007199254740999\"")));
    }
    @Test void mediaFiltersAndMainSwitchAreIndependentParameters() throws Exception {
        var service = mock(LibraryCatalogService.class);
        when(service.media("sample", List.of("PHOTO", "VIDEO"), false, 15, 30))
                .thenReturn(new MediaPage(List.of(), 15, 30, 0, false, 0, 0, "INDEXED_ONLY"));
        MockMvcBuilders.standaloneSetup(new LibraryController(service)).build()
                .perform(get("/api/library/posts/sample/media").param("types", "PHOTO", "VIDEO")
                        .param("includeMain", "false").param("limit", "15").param("offset", "30"))
                .andExpect(status().isOk());
        verify(service).media("sample", List.of("PHOTO", "VIDEO"), false, 15, 30);
    }
    @Test void malformedPaginationIsAClientErrorWithoutRunningQueries() throws Exception {
        var service = mock(LibraryCatalogService.class);
        MockMvcBuilders.standaloneSetup(new LibraryController(service)).setControllerAdvice(new ApiErrors()).build()
                .perform(get("/api/library/catalog").param("limit", "not-a-number"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("筛选或分页参数无效，请检查后重试"));
        verifyNoInteractions(service);
    }
}
