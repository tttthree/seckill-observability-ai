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
 * <p>
 * 约定：写操作（非 GET/HEAD）必须携带 token；只读 GET/HEAD 默认放行，
 * 仅 {@link #TOKEN_REQUIRED_READ_PREFIX} 下的故障事件查询例外——故障事件包含业务键、
 * 券 id 与库存快照，属于敏感运维数据，读取同样需要管理员令牌。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    /** 该前缀下的只读请求也要求管理员令牌（其余 /admin/** GET 行为不变） */
    private static final String TOKEN_REQUIRED_READ_PREFIX = "/admin/incidents";

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
        if (isReadOnly(request.getMethod()) && !requiresAdminTokenForRead(request)) {
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

    private static boolean isReadOnly(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    /**
     * 判断只读请求是否仍需要管理员令牌。
     * 只匹配 /admin/incidents 与其子路径，避免影响其它 /admin/** 只读接口。
     */
    private static boolean requiresAdminTokenForRead(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.equals(TOKEN_REQUIRED_READ_PREFIX)
                || path.startsWith(TOKEN_REQUIRED_READ_PREFIX + "/");
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
