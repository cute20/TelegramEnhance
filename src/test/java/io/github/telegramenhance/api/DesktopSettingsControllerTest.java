package io.github.telegramenhance.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.telegramenhance.desktop.WorkbenchTray;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DesktopSettingsControllerTest {
    @Test void stateAndExplicitPreferenceAreExposedWithoutAnyLaunchToken() throws Exception {
        var tray = mock(WorkbenchTray.class);
        when(tray.view()).thenReturn(new WorkbenchTray.View(false, true, false, ""));
        when(tray.save(new WorkbenchTray.Update(true))).thenReturn(new WorkbenchTray.View(true, true, true, ""));
        var mvc = MockMvcBuilders.standaloneSetup(new DesktopSettingsController(tray)).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/api/settings/desktop")).andExpect(status().isOk())
                .andExpect(jsonPath("$.trayEnabled").value(false)).andExpect(jsonPath("$.traySupported").value(true));
        mvc.perform(put("/api/settings/desktop").contentType(MediaType.APPLICATION_JSON).content("{\"trayEnabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trayActive").value(true)).andExpect(jsonPath("$.warning").value(""))
                .andExpect(jsonPath("$.launchUri").doesNotExist()).andExpect(jsonPath("$.token").doesNotExist());
        verify(tray).save(new WorkbenchTray.Update(true));
    }
}
