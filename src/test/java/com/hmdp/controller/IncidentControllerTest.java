package com.hmdp.controller;

import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.entity.Incident;
import com.hmdp.enums.IncidentSeverity;
import com.hmdp.enums.IncidentSource;
import com.hmdp.enums.IncidentStatus;
import com.hmdp.enums.IncidentType;
import com.hmdp.service.IncidentService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运维查询接口契约测试：数据库异常必须显式失败（沿用 WebExceptionAdvice），
 * 不得被伪装成空列表或 found=false。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentControllerTest {

    @Mock
    private IncidentService incidentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        IncidentController controller = new IncidentController();
        ReflectionTestUtils.setField(controller, "incidentService", incidentService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionAdvice())
                .build();
    }

    @Test
    void shouldReturnIncidentList() throws Exception {
        when(incidentService.listIncidents(any(), any(), any(), any()))
                .thenReturn(List.of(openIncident()));

        mockMvc.perform(get("/admin/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].businessKey").value("voucher:99"))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"));
    }

    /** 关键：查询抛异常时必须返回显式失败，而不是 count=0 的空列表 */
    @Test
    void shouldFailExplicitlyWhenListQueryFails() throws Exception {
        when(incidentService.listIncidents(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        mockMvc.perform(get("/admin/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("服务器异常"))
                .andExpect(jsonPath("$.count").doesNotExist())
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    /** 关键：详情查询抛异常时必须返回显式失败，而不是 found=false */
    @Test
    void shouldFailExplicitlyWhenDetailQueryFails() throws Exception {
        when(incidentService.getIncident(any())).thenThrow(new RuntimeException("db down"));

        mockMvc.perform(get("/admin/incidents/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("服务器异常"))
                .andExpect(jsonPath("$.found").doesNotExist());
    }

    /** 查询成功但确实不存在 → 正常的 found=false（与失败区分开） */
    @Test
    void shouldReportNotFoundOnlyWhenQuerySucceeds() throws Exception {
        // 未做任何 stub：查询正常返回 null（不存在），而不是抛异常
        mockMvc.perform(get("/admin/incidents/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.success").doesNotExist());
    }

    private static Incident openIncident() {
        return new Incident()
                .setId(1L)
                .setIncidentType(IncidentType.INVENTORY_MISMATCH)
                .setSeverity(IncidentSeverity.HIGH)
                .setSource(IncidentSource.RECONCILE)
                .setStatus(IncidentStatus.OPEN)
                .setBusinessKey("voucher:99")
                .setOpenKey("INVENTORY_MISMATCH:voucher:99")
                .setOccurrenceCount(2);
    }
}
