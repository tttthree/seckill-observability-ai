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
}
