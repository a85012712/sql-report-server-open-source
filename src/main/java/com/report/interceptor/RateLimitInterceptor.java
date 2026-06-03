package com.report.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于IP的请求限流拦截器（固定窗口计数器算法）
 * 防止恶意请求和DDoS攻击
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final ConcurrentHashMap<String, RequestCounter> requestCounts = new ConcurrentHashMap<>();

    @Value("${rate-limiter.requests-per-second:10}")
    private int maxRequestsPerSecond;

    @Value("${rate-limiter.enabled:true}")
    private boolean enabled;

    private static final long CLEANUP_INTERVAL = 60000; // 1分钟清理一次
    private static final long WINDOW_SIZE = 1000; // 1秒窗口

    // Bug #7修复：使用AtomicLong保证原子性，避免多线程重复清理
    private final AtomicLong lastCleanupTime = new AtomicLong(System.currentTimeMillis());

    // Bug #5修复：是否信任代理头（部署在反向代理后面时设为true）
    @Value("${rate-limiter.trust-proxy:false}")
    private boolean trustProxy;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 限流功能关闭时直接放行
        if (!enabled) {
            return true;
        }

        // OPTIONS请求直接放行（CORS预检）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String clientIp = getClientIp(request);
        long currentTime = System.currentTimeMillis();

        // 定期清理过期记录（Bug #7修复：使用CAS保证只有一个线程执行清理）
        long lastCleanup = lastCleanupTime.get();
        if (currentTime - lastCleanup > CLEANUP_INTERVAL) {
            if (lastCleanupTime.compareAndSet(lastCleanup, currentTime)) {
                cleanupExpiredEntries(currentTime);
            }
        }

        RequestCounter counter = requestCounts.compute(clientIp, (key, existing) -> {
            if (existing == null || currentTime - existing.windowStart > WINDOW_SIZE) {
                // 新的1秒窗口
                return new RequestCounter(currentTime, 1);
            } else {
                // 同一窗口内
                existing.count.incrementAndGet();
                return existing;
            }
        });

        int currentCount = counter.count.get();
        if (currentCount >= maxRequestsPerSecond) {
            log.warn("IP {} 请求过于频繁: {} 次/秒", clientIp, currentCount);

            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After", "1");
            response.getWriter().write("{\"error\":\"请求过于频繁，请稍后再试\",\"retryAfter\":1}");
            return false;
        }

        return true;
    }

    /**
     * 获取客户端IP
     * Bug #5修复：默认使用remoteAddr防止伪造，仅在trustProxy=true时读取代理头
     */
    private String getClientIp(HttpServletRequest request) {
        if (!trustProxy) {
            // 不信任代理头，直接使用TCP连接IP（防伪造）
            return request.getRemoteAddr();
        }
        // 信任代理头时，从X-Forwarded-For获取真实IP
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 取第一个IP（最左边的是原始客户端IP）
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    private void cleanupExpiredEntries(long currentTime) {
        int sizeBefore = requestCounts.size();
        requestCounts.entrySet().removeIf(entry ->
            currentTime - entry.getValue().windowStart > 60000
        );
        int sizeAfter = requestCounts.size();
        if (sizeBefore > 100 && sizeAfter < sizeBefore) {
            log.debug("清理限流缓存: {} -> {}", sizeBefore, sizeAfter);
        }
    }

    private static class RequestCounter {
        final long windowStart;
        final AtomicInteger count;

        RequestCounter(long windowStart, int count) {
            this.windowStart = windowStart;
            this.count = new AtomicInteger(count);
        }
    }
}