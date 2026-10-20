package com.report.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 客户端 IP 的唯一取值口。
 *
 * <p>缺陷：{@code AuditService} 与 {@code ReportController} 此前无条件信任
 * {@code X-Forwarded-For}，而系统直跑 jar、无反向代理（{@code trust-proxy: false}）。
 * 任何登录用户加一个请求头就能伪造所有审计行的来源 IP，
 * 使「异常登录可经审计追溯」这一风险接受前提失效。
 *
 * <p>规则：只有明确配置了可信代理时才读转发头；否则一律用 TCP 对端地址。
 */
@Component
public class ClientIpResolver {

    @Value("${rate-limiter.trust-proxy:false}")
    private boolean trustProxy;

    /** 用于限流与审计展示的来源 IP。 */
    public String resolve(HttpServletRequest request) {
        if (request == null) return null;
        if (!trustProxy) {
            return request.getRemoteAddr();
        }
        String ip = firstHeaderValue(request.getHeader("X-Forwarded-For"));
        if (ip == null) {
            ip = firstHeaderValue(request.getHeader("X-Real-IP"));
        }
        return ip != null ? ip : request.getRemoteAddr();
    }

    /**
     * TCP 对端地址：不受任何请求头影响，不可被客户端伪造。
     * 审计里与 {@code clientIp} 并列记录，代理配置错误时仍可回溯真实来源。
     */
    public String peerAddr(HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }

    /**
     * 是否本机回环地址。
     *
     * <p>用于把「服务器自己」从按 IP 的登录限速里豁免：限速要挡的是内网局域网里
     * 拿初始口令遍历工号段的机器，而不是本机上的运维自检与验证脚本
     * （它们会从 127.0.0.1 连续登录十几个临时账号）。
     * 能登录到服务器本机的人本来就有本机权限，不构成这条防护的目标。
     */
    public boolean isLoopback(HttpServletRequest request) {
        String peer = peerAddr(request);
        if (peer == null) return false;
        return peer.equals("127.0.0.1") || peer.equals("::1")
            || peer.startsWith("127.") || peer.equalsIgnoreCase("0:0:0:0:0:0:0:1");
    }

    private static String firstHeaderValue(String header) {
        if (header == null || header.isEmpty() || "unknown".equalsIgnoreCase(header)) return null;
        int comma = header.indexOf(',');
        String ip = (comma > 0 ? header.substring(0, comma) : header).trim();
        return ip.isEmpty() ? null : ip;
    }
}
