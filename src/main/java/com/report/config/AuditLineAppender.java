package com.report.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.report.service.AuditService;
import com.report.util.AuditStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把已经存在的那批 {@code log.info("[AUDIT] action=xxx, k=v, ...")} 文本行收进审计库。
 *
 * <p>条数刻意不写在注释里，这类数字写进注释就一定会过期（改审计重复计数时就已经
 * 对不上了）。要数当场数：{@code git grep -c '"\[AUDIT\] action=' src/main/java}。
 *
 * <p>为什么不逐个改成 {@code AuditService.audit(...)}：那些调用点散在 9 个类里，其中
 * {@code UserManagementService} 等还牵扯循环依赖（{@code AuditService} 已注入
 * {@code ClientIpResolver}，而这些服务又被别的组件依赖），一次性全改的出错面远大于收益。
 * 这个 appender 挂在 root logger 上，只读日志流、不改任何调用点，
 * 并且对将来新写的裸 {@code [AUDIT]} 行同样生效，漏改不会静默丢审计。
 *
 * <p>不会重复入库：{@code AuditService.audit()} 写的是 {@code [AUDIT] {json}} 形态，
 * 它自己已经直接落库了，这里靠「负载是否以 <code>{</code> 开头」把它们排除掉，只吃
 * {@code k=v} 形态的旧格式。
 *
 * <p>解析是刻意宽松的：只在第一个 {@code action=} 处较真，其余按 {@code ", "} 拆、
 * 拆开后不含 {@code =} 的片段并回上一个值（因为 {@code permissions=[a, b]} 这类字段
 * 本身就有逗号）。解析不出 action 的行直接跳过，绝不让一行日志把请求带崩。
 *
 * <p>注意：局限（已写进文档，不留惊喜）：旧格式行里没有操作人与来源 IP 的那些
 * （如 {@code createCommunity} 只记了 code/name），入库后 {@code operator} 为空、
 * 审计页显示「系统/无上下文」。这些站点已在各自方法里能拿到操作人，
 * 后续按模块逐个换成 {@code AuditService.audit(...)} 即可补齐，届时本 appender
 * 只是不再收到它们的 {@code k=v} 行。
 */
@Component
public class AuditLineAppender extends AppenderBase<ILoggingEvent> {

    private static final String MARKER = "[AUDIT] ";
    private static final int MAX_VALUE_LEN = 500;

    private final AuditStore auditStore;
    private final AuditService auditService;
    private ch.qos.logback.classic.Logger root;

    public AuditLineAppender(AuditStore auditStore, AuditService auditService) {
        this.auditStore = auditStore;
        this.auditService = auditService;
    }

    @PostConstruct
    void attach() {
        root = (ch.qos.logback.classic.Logger)
            ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        this.setName("AUDIT-LINE");
        this.setContext(root.getLoggerContext());
        this.start();
        root.addAppender(this);
        LoggerFactory.getLogger(AuditLineAppender.class)
            .info("旧格式 [AUDIT] 文本行采集已挂载（只吃 k=v 形态，不会与结构化事件重复入库）");
    }

    @PreDestroy
    void detach() {
        if (root != null) root.detachAppender(this);
        this.stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            String msg = event.getFormattedMessage();
            if (msg == null) return;
            int at = msg.indexOf(MARKER);
            if (at < 0) return;
            String body = msg.substring(at + MARKER.length()).trim();
            // 结构化行由 AuditService 自己落库，这里跳过，避免重复
            if (body.isEmpty() || body.charAt(0) == '{') return;

            Map<String, Object> rec = parse(body);
            if (rec.get("action") == null) return;
            rec.put("ts", event.getTimeStamp());
            /* 复用 AuditService 的上下文补齐，本 appender 跑在请求线程上（logback 默认同步），
             * 所以这里取得到与结构化事件同一个操作人与来源 IP。裸 [AUDIT] 行里通常没有这些，
             * 不补就会在审计库里留下一堆「查不到是谁干的」记录，而这个功能的目的就是按工号追责。*/
            auditService.enrichContext(rec);
            // 旧格式行没有请求上下文，只能从日志级别给个粗判定：warn 视为失败
            rec.putIfAbsent("success", !event.getLevel().isGreaterOrEqual(
                ch.qos.logback.classic.Level.WARN));
            auditStore.append(rec);
        } catch (Exception ignore) {
            // 采集器绝不能把业务带崩
        }
    }

    /** 宽松解析 {@code action=x, k=v, k2=v2} → 有序 map。 */
    static Map<String, Object> parse(String body) {
        Map<String, Object> rec = new LinkedHashMap<>();
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String token : body.split(", ")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                String k = token.substring(0, eq).trim();
                String v = token.substring(eq + 1).trim();
                keys.add(k);
                rec.put(k, shorten(v));
            } else if (!keys.isEmpty() && !token.trim().isEmpty()) {
                // 上一个值里被逗号切开的一段，并回去
                String prev = String.valueOf(rec.get(keys.get(keys.size() - 1)));
                rec.put(keys.get(keys.size() - 1),
                    shorten(prev.isEmpty() ? token.trim() : prev + ", " + token.trim()));
            }
        }
        return rec;
    }

    private static String shorten(String v) {
        if (v == null) return "";
        return v.length() <= MAX_VALUE_LEN ? v : v.substring(0, MAX_VALUE_LEN) + "…";
    }
}
