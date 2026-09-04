package com.hmdp.interceptor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 用独立的管理员 token 保护运维接口和业务写操作。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final String adminToken;

    public AdminAuthInterceptor(@Value("${admin.token:}") String adminToken) {
        this.adminToken = adminToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if ("GET".equalsIgnoreCase(request.getMethod()) || "HEAD".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String suppliedToken = request.getHeader(ADMIN_TOKEN_HEADER);
        if (adminToken.isBlank() || suppliedToken == null || !constantTimeEquals(adminToken, suppliedToken)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"success\":false,\"errorMsg\":\"无管理员权限\"}");
            return false;
        }
        return true;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
