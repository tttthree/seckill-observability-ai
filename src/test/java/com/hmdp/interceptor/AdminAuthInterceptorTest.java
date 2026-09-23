package com.hmdp.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuthInterceptorTest {

    @Test
    void shouldAllowMatchingAdminToken() throws Exception {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/metrics/reset");
        request.addHeader("X-Admin-Token", "secret-token");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void shouldRejectMissingToken() throws Exception {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(
                new MockHttpServletRequest("POST", "/admin/metrics/reset"), response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void shouldFailClosedWhenAdminTokenIsNotConfigured() throws Exception {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/metrics/reset");
        request.addHeader("X-Admin-Token", "anything");

        assertFalse(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void shouldAllowCorsPreflight() throws Exception {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("");

        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest("OPTIONS", "/admin/metrics/reset"),
                new MockHttpServletResponse(),
                new Object()));
    }

    @Test
    void shouldAllowReadOnlyRequestWithoutAdminToken() throws Exception {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("");

        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/admin/health/queue"),
                new MockHttpServletResponse(),
                new Object()));
    }

    /** 故障事件是敏感运维数据：GET 也必须携带管理员令牌 */
    @Test
    void shouldRequireAdminTokenForIncidentListRead() throws Exception {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/admin/incidents"), response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void shouldRequireAdminTokenForIncidentDetailRead() throws Exception {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("secret-token");

        assertFalse(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/admin/incidents/42"),
                new MockHttpServletResponse(),
                new Object()));
    }

    @Test
    void shouldAllowIncidentReadWithMatchingAdminToken() throws Exception {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/incidents");
        request.addHeader("X-Admin-Token", "secret-token");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void shouldAllowIncidentCorsPreflightWithoutAdminToken() throws Exception {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("secret-token");

        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest("OPTIONS", "/admin/incidents"),
                new MockHttpServletResponse(),
                new Object()));
    }

    /** 最小权限调整：其它 /admin/** 只读接口行为保持不变 */
    @Test
    void shouldKeepOtherAdminReadEndpointsTokenFree() throws Exception {
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("secret-token");

        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/admin/seckill/1/stats"),
                new MockHttpServletResponse(),
                new Object()));
        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/admin/metrics/summary"),
                new MockHttpServletResponse(),
                new Object()));
        // 前缀边界：兄弟路径不属于 /admin/incidents/**
        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/admin/incidents-history"),
                new MockHttpServletResponse(),
                new Object()));
    }
}
