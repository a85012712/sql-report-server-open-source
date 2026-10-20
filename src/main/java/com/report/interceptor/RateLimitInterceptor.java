package com.report.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 IP 的请求限流（固定窗口），支持按路径前缀分档限流。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final ConcurrentHashMap<String, RequestCounter> requestCounts = new ConcurrentHashMap<>();

    @Value("${rate-limiter.requests-per-second:10}")
    private int defaultMaxPerSecond;

    @Value("${rate-limiter.enabled:true}")
    private boolean enabled;

    @Value("${rate-limiter.trust-proxy:false}")
    private boolean trustProxy;

    /** 格式: /api/report/:20,/api/admin/:5 */
    @Value("${rate-limiter.path-limits:}")
    private String pathLimitsConfig;

    private static final long CLEANUP_INTERVAL = 60000;
    private static final long WINDOW_SIZE = 1000;

    private final AtomicLong lastCleanupTime = new AtomicLong(System.currentTimeMillis());
    private volatile List<PathLimit> pathLimits = List.of();
    private volatile boolean pathLimitsParsed;

    private static final class PathLimit {
        final String prefix;
        final int limit;
        PathLimit(String prefix, int limit) {
            this.prefix = prefix;
            this.limit = limit;
        }
    }

    private void ensurePathLimitsParsed() {
        if (pathLimitsParsed) return;
        synchronized (this) {
            if (pathLimitsParsed) return;
            List<PathLimit> list = new ArrayList<>();
            if (pathLimitsConfig != null && !pathLimitsConfig.isBlank()) {
                for (String part : pathLimitsConfig.split(",")) {
                    String p = part.trim();
                    if (p.isEmpty()) continue;
                    int idx = p.lastIndexOf(':');
                    if (idx <= 0 || idx == p.length() - 1) continue;
                    String prefix = p.substring(0, idx).trim();
                    try {
                        int lim = Integer.parseInt(p.substring(idx + 1).trim());
                        if (lim > 0 && !prefix.isEmpty()) {
                            list.add(new PathLimit(prefix, lim));
                        }
                    } catch (NumberFormatException ignore) {
                        log.warn("忽略无效限流配置: {}", p);
                    }
                }
                // 长前缀优先匹配
                list.sort((a, b) -> Integer.compare(b.prefix.length(), a.prefix.length()));
            }
            pathLimits = list;
            pathLimitsParsed = true;
        }
    }

    private int resolveLimit(String path) {
        ensurePathLimitsParsed();
        if (path != null) {
            for (PathLimit pl : pathLimits) {
                if (path.startsWith(pl.prefix)) {
                    return pl.limit;
                }
            }
        }
        return defaultMaxPerSecond;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!enabled) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String clientIp = getClientIp(request);
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        int maxPerSecond = resolveLimit(path);
        // 分桶：IP + 限流档位，避免不同档位互相抢配额
        String bucket = clientIp + "|" + maxPerSecond;
        long currentTime = System.currentTimeMillis();

        long lastCleanup = lastCleanupTime.get();
        if (currentTime - lastCleanup > CLEANUP_INTERVAL) {
            if (lastCleanupTime.compareAndSet(lastCleanup, currentTime)) {
                cleanupExpiredEntries(currentTime);
            }
        }

        RequestCounter counter = requestCounts.compute(bucket, (key, existing) -> {
            if (existing == null || currentTime - existing.windowStart > WINDOW_SIZE) {
                return new RequestCounter(currentTime, 1);
            } else {
                existing.count.incrementAndGet();
                return existing;
            }
        });

        int currentCount = counter.count.get();
        if (currentCount >= maxPerSecond) {
            log.warn("IP {} 请求过于频繁: {} 次/秒 (path={}, limit={})", clientIp, currentCount, path, maxPerSecond);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After", "1");
            response.getWriter().write("{\"error\":\"请求过于频繁，请稍后再试\",\"retryAfter\":1}");
            return false;
        }
        return true;
    }

    private String getClientIp(HttpServletRequest request) {
        if (!trustProxy) {
            return request.getRemoteAddr();
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
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
