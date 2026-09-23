package com.hmdp.controller;

import com.hmdp.dto.context.IncidentContext;
import com.hmdp.interceptor.AdminAuthInterceptor;
import com.hmdp.service.IncidentContextBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Context 接口契约测试：404 / 200 / 管理员鉴权。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentContextControllerTest {

    @Mock
    private IncidentContextBuilder incidentContextBuilder;

    private MockMvc anonymousMvc;

    @BeforeEach
    void setUp() {
        IncidentContextController controller = new IncidentContextController();
        ReflectionTestUtils.setField(controller, "incidentContextBuilder", incidentContextBuilder);
        anonymousMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldReturn404WhenIncidentDoesNotExist() throws Exception {
        when(incidentContextBuilder.build(anyLong())).thenReturn(null);

        anonymousMvc.perform(get("/admin/incidents/999/context"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("INCIDENT_NOT_FOUND"))
                .andExpect(jsonPath("$.incident_id").value(999));
    }

    @Test
    void shouldReturnContextWhenIncidentExists() throws Exception {
        when(incidentContextBuilder.build(4L)).thenReturn(new IncidentContext()
                .setContextVersion("v2-2.1")
                .setContextQuality(new IncidentContext.ContextQuality().setComplete(true)));

        anonymousMvc.perform(get("/admin/incidents/4/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context_version").value("v2-2.1"))
                .andExpect(jsonPath("$.context_quality.complete").value(true));
    }

    @Test
    void shouldRequireAdminTokenInMvcChain() throws Exception {
        IncidentContextController controller = new IncidentContextController();
        ReflectionTestUtils.setField(controller, "incidentContextBuilder", incidentContextBuilder);
        MockMvc secured = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new AdminAuthInterceptor("secret-token"))
                .build();

        secured.perform(get("/admin/incidents/4/context"))
                .andExpect(status().isForbidden());

        when(incidentContextBuilder.build(4L)).thenReturn(new IncidentContext().setContextVersion("v2-2.1"));
        secured.perform(get("/admin/incidents/4/context").header("X-Admin-Token", "secret-token"))
                .andExpect(status().isOk());
    }
}
