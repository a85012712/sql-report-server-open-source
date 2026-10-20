package com.report.config;

import com.report.service.UserManagementService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 首次登录强制改密：mustChangePassword=true 时，除改密/当前用户/登出/静态资源外拒绝业务 API。
 */
@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private final UserManagementService userManagementService;

    public MustChangePasswordFilter(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathOf(request);
        // 静态与登录相关
        if (path.startsWith("/static/") || path.startsWith("/css/") || path.startsWith("/js/")) return true;
        if (path.equals("/login") || path.equals("/logout")) return true;
        if (path.startsWith("/actuator/health")) return true;
        // 允许查询自身状态与修改密码
        if (path.equals("/api/admin/current-user")) return true;
        if (path.equals("/api/admin/profile/password")) return true;
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            filterChain.doFilter(request, response);
            return;
        }
        String username = auth.getName();
        if (username == null || !userManagementService.mustChangePassword(username)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 页面请求：由前端弹窗；API：403
        String path = pathOf(request);
        if (path.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"首次登录必须修改密码后再使用系统\",\"code\":\"MUST_CHANGE_PASSWORD\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** 使用 servletPath，兼容 context-path */
    private static String pathOf(HttpServletRequest request) {
        String p = request.getServletPath();
        if (p == null || p.isEmpty()) {
            p = request.getRequestURI();
            String ctx = request.getContextPath();
            if (ctx != null && !ctx.isEmpty() && p != null && p.startsWith(ctx)) {
                p = p.substring(ctx.length());
            }
        }
        if (p == null || p.isEmpty()) p = "/";
        // 去掉末尾多余 /
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
