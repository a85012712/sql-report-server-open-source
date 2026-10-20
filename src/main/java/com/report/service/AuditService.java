package com.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.report.util.AuditStore;
import com.report.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 结构化审计日志 + Micrometer 指标。
 *
 * <p>所有审计行统一补齐 {@code user}（操作人）、{@code clientIp}（来源 IP）与
 * {@code peerAddr}（TCP 对端地址）三个关键字段，
 * 保证「谁、在什么时间、从哪里、做了什么」四要素完整可追溯。
 * {@code peerAddr} 不受请求头影响，代理配置错误时仍可回溯真实来源。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ClientIpResolver clientIpResolver;
    private final AuditStore auditStore;

    public AuditService(ObjectMapper objectMapper, MeterRegistry meterRegistry,
                        ClientIpResolver clientIpResolver, AuditStore auditStore) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.clientIpResolver = clientIpResolver;
        this.auditStore = auditStore;
    }

    /**
     * 写一条结构化审计记录，同时落两处：
     * <ol>
     *   <li>运行日志里的 {@code [AUDIT]} 行（沿用既有排障习惯，不动它）；</li>
     *   <li>{@link AuditStore} 的按天 JSONL，供「操作审计」页按工号检索。</li>
     * </ol>
     *
     * <p>缺陷 M-3：调用方经常只传业务字段，导致审计行缺少「谁做的（operator）」与
     * 「从哪来的（clientIp）」，事后无法追责。此处统一兜底补齐，调用方显式传入时以显式值为准。
     *
     * <p>取值来源：当前线程的 SecurityContext（登录用户）与 RequestContextHolder（HTTP 请求）。
     * 非 HTTP 线程（定时任务、启动期）取不到时留空，不抛异常、不影响主流程。
     */
    public void audit(String action, Map<String, Object> fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        if (fields != null) {
            payload.putAll(fields);
        }
        enrichContext(payload);
        try {
            log.info("[AUDIT] {}", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.info("[AUDIT] action={}, fields={}", action, payload);
        }
        auditStore.append(payload);
    }

    /**
     * 补齐「谁、从哪、何时」三个溯源维度（调用方已给的字段一律不覆盖）。
     *
     * <p>公开是给 {@code AuditLineAppender} 复用的：那 49 处裸
     * {@code log.info("[AUDIT] action=…")} 文本行里没有操作人，
     * 不补就会在审计库里留下一堆查不到「是谁干的」的记录，而这个功能存在的目的
     * 恰恰就是按工号追责。appender 跑在请求线程上（logback 默认同步），
     * 所以这里能取到同一份上下文。
     *
     * <p>非 HTTP 线程（定时任务、启动期迁移）取不到时留空，不抛异常、不影响主流程。
     */
    public void enrichContext(Map<String, Object> payload) {
        payload.putIfAbsent("ts", System.currentTimeMillis());
        String user = currentUsername();
        if (user != null) {
            payload.putIfAbsent("operator", user);
        }
        String ip = currentClientIp();
        if (ip != null) {
            payload.putIfAbsent("clientIp", ip);
        }
        String peer = currentPeerAddr();
        if (peer != null) {
            payload.putIfAbsent("peerAddr", peer);
        }
        // 检索与展示要一个人类可读时间，省掉前端再解析毫秒数
        payload.putIfAbsent("time", java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    /**
     * 键值对写法的便捷入口：{@code audit("createCommunity", "code", cc, "name", c.name)}。
     *
     * <p>存在的意义是把那 40 多处
     * {@code log.info("[AUDIT] action=xxx, k1={}, k2={}", ...)} 平移过来 ，
     * 那些裸日志行不带操作人与来源 IP，等于「谁改了角色」这类问题答不上来，
     * 也进不了审计库。
     *
     * <p>注意：故意不用 {@code Map.of}：它遇到 null 值直接抛 NPE，
     * 而这里不少字段（被改的昵称、可选的描述）本来就可能为 null。
     * null 值一律跳过该键，而不是让一次本来会成功的管理操作因为记日志而失败。
     */
    public void audit(String action, Object... keyValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object v = keyValues[i + 1];
            if (v != null) fields.put(String.valueOf(keyValues[i]), v);
        }
        audit(action, fields);
    }

    /** 当前登录用户名；未登录（匿名/无上下文）返回 "anonymous"。 */
    private static String currentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken)) {
                String name = auth.getName();
                if (name != null && !name.isBlank()) return name;
            }
        } catch (Exception ignore) {
            // 上下文不可用时降级为匿名
        }
        return "anonymous";
    }

    /** 当前请求的客户端 IP；非 HTTP 线程返回 null。 */
    private String currentClientIp() {
        HttpServletRequest req = currentRequest();
        return req == null ? null : clientIpResolver.resolve(req);
    }

    /** 当前请求的 TCP 对端地址；非 HTTP 线程返回 null。 */
    private String currentPeerAddr() {
        HttpServletRequest req = currentRequest();
        return req == null ? null : clientIpResolver.peerAddr(req);
    }

    private static HttpServletRequest currentRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (!(attrs instanceof ServletRequestAttributes sra)) return null;
            return sra.getRequest();
        } catch (Exception ignore) {
            return null;
        }
    }

    public void recordQuery(String action, String reportId, String clientIp, long durationMs, long rowCount, boolean success) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reportId", reportId);
        fields.put("clientIp", clientIp);
        fields.put("durationMs", durationMs);
        fields.put("rowCount", rowCount);
        fields.put("success", success);
        if (durationMs > 5000) {
            fields.put("slow", true);
        }
        audit(action, fields);

        Timer.builder("report.query")
            .description("报表查询耗时")
            .tag("action", action != null ? action : "query")
            .tag("success", String.valueOf(success))
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);

        meterRegistry.counter("report.query.count",
            "action", action != null ? action : "query",
            "success", String.valueOf(success)
        ).increment();

        if (rowCount >= 0) {
            meterRegistry.summary("report.query.rows", "action", action != null ? action : "query")
                .record(rowCount);
        }
    }

    public void recordExport(String reportId, String clientIp, long durationMs, long rowCount, boolean success) {
        recordQuery("export", reportId, clientIp, durationMs, rowCount, success);
        meterRegistry.counter("report.export.count", "success", String.valueOf(success)).increment();
    }
}
