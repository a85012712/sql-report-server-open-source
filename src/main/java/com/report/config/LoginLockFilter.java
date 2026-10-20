package com.report.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import com.report.service.AuditService;
import com.report.service.CaptchaService;
import com.report.util.ClientIpResolver;

/**
 * POST /login 前置检查：账号是否被锁定、按 IP 限速、需要时校验算术式验证码。
 *
 * <p>三道检查的顺序是定的，别随手换：
 * 锁定先判（都要锁了，再让人认验证码没意义）→ IP 限速再判（最便宜的拒绝放前面，
 * 也让洪水请求碰不到验证码表）→ 最后才是验证码（它要消费一道题，放最省的那步之后）。
 */
@Component
public class LoginLockFilter extends OncePerRequestFilter {

    private final LoginAttemptService loginAttemptService;
    private final ClientIpResolver clientIpResolver;
    private final LoginCaptchaPolicy captchaPolicy;
    private final CaptchaService captchaService;
    private final AuditService auditService;

    public LoginLockFilter(LoginAttemptService loginAttemptService,
                           ClientIpResolver clientIpResolver,
                           LoginCaptchaPolicy captchaPolicy,
                           CaptchaService captchaService,
                           AuditService auditService) {
        this.loginAttemptService = loginAttemptService;
        this.clientIpResolver = clientIpResolver;
        this.captchaPolicy = captchaPolicy;
        this.captchaService = captchaService;
        this.auditService = auditService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
            String ctx = request.getContextPath();
            if (ctx != null && !ctx.isEmpty() && path != null && path.startsWith(ctx)) {
                path = path.substring(ctx.length());
            }
        }
        if (path != null && path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return !"/login".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String username = request.getParameter("username");
        if (loginAttemptService.isLocked(username)) {
            long sec = loginAttemptService.remainingLockSeconds(username);
            response.sendRedirect(contextPath(request) + "/login?locked&sec=" + sec);
            return;
        }
        // 表单登录不经过 /api/** 的限流拦截器，这里单独按 IP 限速，
        // 否则同一台内网机器可以对 4 位数字工号段逐个试初始口令。
        // 本机回环豁免（自检与验证脚本从 127.0.0.1 连续登录十几个临时账号）；
        // 账号锁定按用户名计数，对本机同样生效，不会因为豁免而失去这层保护。
        if (!clientIpResolver.isLoopback(request)
            && !loginAttemptService.noteLoginAttempt(clientIpResolver.resolve(request))) {
            response.sendRedirect(contextPath(request) + "/login?rate=1");
            return;
        }
        if (captchaPolicy.required(request, username)) {
            String token = readCaptchaToken(request);
            String input = request.getParameter("captcha");
            if (!captchaService.verify(token, input)) {
                // 只记到 IP 维度，不记到工号维度：否则任何人只要知道一个工号，
                // 连发几次「不带验证码/验证码错」的请求就能把同事的账号锁 15 分钟 ，
                // 这是把防撞库的机制变成拒绝服务的入口。工号维度仍然只在真实口令校验失败时累加。
                loginAttemptService.noteLoginFailureFromIp(clientIpResolver.resolve(request));
                auditService.audit("loginCaptchaFailed", java.util.Map.of(
                    "operator", username == null ? "" : username.trim(),
                    "success", false,
                    // 只记「没带码」还是「码不对」两个枚举值，绝不记用户输入，也不记答案
                    "reason", token == null ? "MISSING_CAPTCHA" : "WRONG_CAPTCHA"));
                // cap=1 保证重新渲染时验证码框还在：缺了这一句，用户会看到「验证码错误」
                // 却找不到填验证码的地方，只能自己刷新页面才恢复。
                response.sendRedirect(contextPath(request) + "/login?caperr=1&cap=1");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private static String readCaptchaToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (CaptchaService.COOKIE_NAME.equals(c.getName())) {
                String v = c.getValue();
                return (v == null || v.isBlank()) ? null : v;
            }
        }
        return null;
    }

    private static String contextPath(HttpServletRequest request) {
        String ctx = request.getContextPath();
        return ctx != null ? ctx : "";
    }
}
