package com.report.controller;

import com.report.util.AuditStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「操作审计」检索接口（受 {@code logs.view} 守卫）。
 *
 * <p>与 {@code GET /api/admin/logs} 的分工要说清，否则很容易被当成重复功能：
 * <ul>
 *   <li>{@code /api/admin/logs}，运行日志查看器：读应用日志尾部、按关键字分类，
 *       用来排障（「刚才那个报错是什么」）；</li>
 *   <li>{@code /api/admin/audit}，操作审计：按工号/动作/报表/日期检索结构化记录，
 *       用来追责（「工号 1024 这一周查过哪些患者明细、导没导出去」）。留存 30 天，
 *       且不受重启影响（运行日志原先 {@code clean-history-on-start=true} 时重启即删）。</li>
 * </ul>
 *
 * <p>注意：本接口只回非 PII 字段：审计记录本身就不落报表参数值（见
 * {@link AuditStore}），因此这里不需要再做字段裁剪，但这也是为什么
 * 审计库里查不到「他具体输了哪个身份证号」，那是有意的设计，不是漏了。
 */
@RestController
@RequestMapping("/api/admin/audit")
@PreAuthorize("@perm.has('logs.view')")
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);
    private static final int MAX_PAGE_SIZE = 200;
    /** 一次导出的行数上限：台账满 30 天时会有几万条，全量塞进一个 CSV 反而打不开。 */
    private static final int EXPORT_ROW_LIMIT = MAX_PAGE_SIZE * 50;

    private final AuditStore auditStore;
    private final com.report.service.AuditService auditService;

    public AuditController(AuditStore auditStore,
                           com.report.service.AuditService auditService) {
        this.auditStore = auditStore;
        this.auditService = auditService;
    }

    /** 检索审计记录。 */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String user,
                                  @RequestParam(required = false) String action,
                                  @RequestParam(required = false) String reportId,
                                  @RequestParam(required = false) String dateFrom,
                                  @RequestParam(required = false) String dateTo,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) Integer status,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "50") int pageSize,
                                  @AuthenticationPrincipal UserDetails currentUser) {
        try {
            Map<String, String> target = targetOrNull(currentUser);
            if (target != null) {
                // 审计是「谁在查别人」的功能，把查询者自己也记一条。
                // 查自己时不记，否则翻一次自己的轨迹会再产生一条“翻轨迹”记录，自我放大。
                if (user == null || user.isBlank() || !user.trim().equals(target.get("operator"))) {
                    auditStore.append(auditRecordOfViewer("auditView", user, action, reportId,
                        dateFrom, dateTo, keyword, status, target));
                }
            }
            AuditStore.Result r = run(user, action, reportId, dateFrom, dateTo, keyword, status,
                clampPage(page), clampSize(pageSize));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rows", r.rows());
            body.put("total", r.matched());
            body.put("page", r.page());
            body.put("pageSize", r.pageSize());
            body.put("totalPages", r.totalPages());
            body.put("dateFrom", r.dateFrom());
            body.put("dateTo", r.dateTo());
            body.put("truncated", r.capped());
            body.put("malformed", r.malformed());
            body.put("filesScanned", r.filesScanned());
            body.put("retentionDays", auditStore.getRetentionDays());
            // 落盘失败次数直接摆在响应里：审计坏了不能只有服务器日志知道
            body.put("writeFailures", r.writeFailures());
            // 白名单口径也摆出来：台账"少了一类记录"必须是页面上一句明话，不能靠人记住配置
            body.put("exclusionNote", auditStore.exclusionNote());
            body.put("actions", auditStore.distinctActions());
            body.put("dates", auditStore.availableDates());
            body.put("operators", auditStore.distinctOperators());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("审计检索失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "审计检索失败"));
        }
    }

    /**
     * 导出审计记录为 CSV（UTF-8 BOM，Excel 直开）。
     *
     * <p>导出动作本身也要可追责：导一次审计台账，等于把「谁查过什么」整份带走，
     * 所以记的是 {@code auditExport} 而不是 {@code auditView}。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String user,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) String reportId,
                                         @RequestParam(required = false) String dateFrom,
                                         @RequestParam(required = false) String dateTo,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) Integer status,
                                         @AuthenticationPrincipal UserDetails currentUser) {
        try {
            // 导出固定从第 1 页取，最多 EXPORT_ROW_LIMIT 条。
            // 光截断行数并不能防「以为导完了」， 界面那行黄告警跟着不了被带走的文件，
            // 所以截断时必须把「这不是全部」写进 CSV 本身（见 toCsv 末段）。
            AuditStore.Result r = run(user, action, reportId, dateFrom, dateTo, keyword, status,
                1, EXPORT_ROW_LIMIT);
            Map<String, String> target = targetOrNull(currentUser);
            if (target != null) {
                auditStore.append(auditRecordOfViewer("auditExport", user, action, reportId,
                    dateFrom, dateTo, keyword, status, target));
            }
            String csv = toCsv(r);
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] body = csv.getBytes(StandardCharsets.UTF_8);
            byte[] withBom = new byte[bom.length + body.length];
            System.arraycopy(bom, 0, withBom, 0, bom.length);
            System.arraycopy(body, 0, withBom, bom.length, body.length);

            String filename = "audit_" + LocalDate.now() + ".csv";
            HttpHeaders h = new HttpHeaders();
            h.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
            // 审计台账含工号，绝不能缓存在公共诊室终端上
            h.setCacheControl("no-store");
            h.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
            return new ResponseEntity<>(withBom, h, HttpStatus.OK);
        } catch (Exception e) {
            log.error("导出审计失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new byte[0]);
        }
    }

    // ============ 私有 ============

    private AuditStore.Result run(String user, String action, String reportId, String dateFrom,
                                  String dateTo, String keyword, Integer status,
                                  int page, int pageSize) {
        return auditStore.query(user, action, reportId, dateFrom, dateTo, keyword, status,
            page, pageSize);
    }

    private static int clampPage(int page) { return Math.min(100_000, Math.max(1, page)); }

    private static int clampSize(int size) { return Math.min(MAX_PAGE_SIZE, Math.max(1, size)); }

    /**
     * 「谁在查审计」这条记录自己。
     *
     * <p>不走 {@code AuditService.audit()}：那会经 SecurityContext 取操作人，
     * 虽然此处上下文也在，但更重要的是不能让这个调用把筛选条件里的
     * {@code user=} 当成操作人，操作人永远是「点审计页的人」，被查的人是 {@code targetUser} 字段。
     *
     * <p>但要复用 {@link com.report.service.AuditService#enrichContext}
     * 补来源 IP：它是 {@code putIfAbsent} 语义，已经显式给出的 operator / ts / time 不会被覆盖，
     * 只把 clientIp / peerAddr 填上。少了这两个字段，「谁在追溯别人」这条记录恰恰是唯一
     * 定位不到机器的，漏掉时台账里 auditView 的 clientIp 全是 null。
     */
    private Map<String, Object> auditRecordOfViewer(String action, String user, String targetAction,
                                                    String reportId, String dateFrom, String dateTo,
                                                    String keyword, Integer status,
                                                    Map<String, String> viewer) {
        long now = System.currentTimeMillis();
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("action", action);
        rec.put("operator", viewer.get("operator"));
        rec.put("ts", now);
        rec.put("time", AuditStore.formatTs(now));
        rec.put("success", true);
        if (user != null && !user.isBlank()) rec.put("targetUser", user.trim());
        if (targetAction != null && !targetAction.isBlank()) rec.put("filterAction", targetAction);
        if (reportId != null && !reportId.isBlank()) rec.put("filterReport", reportId);
        if (dateFrom != null && !dateFrom.isBlank()) rec.put("from", dateFrom);
        if (dateTo != null && !dateTo.isBlank()) rec.put("to", dateTo);
        // 注意：关键词只记长度、不记内容：搜「14010519」这种片段本身就算 PII
        if (keyword != null && !keyword.isBlank()) rec.put("keywordLen", keyword.trim().length());
        if (status != null) rec.put("filterStatus", status);
        auditService.enrichContext(rec);   // 只补 clientIp / peerAddr
        return rec;
    }

    private static Map<String, String> targetOrNull(UserDetails user) {
        if (user == null) return null;
        Map<String, String> m = new LinkedHashMap<>();
        m.put("operator", user.getUsername());
        return m;
    }

    /** 表头固定这几列：审计记录字段是开放的（各动作自带字段），额外字段在详情里看。 */
    static String toCsv(AuditStore.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("时间,操作人,动作,报表,目标工号,结果,行数,耗时ms,客户端IP,对端IP\n");
        for (Map<String, Object> rec : r.rows()) {
            sb.append(cell(rec.get("time")))
              .append(cell(rec.get("operator")))
              .append(cell(rec.get("action")))
              .append(cell(rec.get("reportId")))
              .append(cell(rec.get("targetUser")))
              .append(cell(resultText(rec.get("success"))))
              .append(cell(rec.get("rowCount")))
              .append(cell(rec.get("durationMs")))
              .append(cell(rec.get("clientIp")))
              .append(cell(rec.get("peerAddr")))
              .append('\n');
        }
        /* 被截断这件事必须落在**文件里**。
         * 页面上那行黄色告警跟着不了被 U 盘带走、被人当证据的 CSV ，
         * 而「共 N 条、文件里只有 M 条」正好是最容易被忽略、又最贵的一种错。 */
        boolean shortExport = r.capped() || r.rows().size() < r.matched();
        if (shortExport) {
            sb.append("\"[提示] 本文件只含最近 ").append(r.rows().size())
              .append(" 条记录，当前条件命中共 ").append(r.matched()).append(" 条")
              .append(r.capped() ? "（扫描已达上限 " + AuditStore.MAX_SCAN_ROWS + " 条，总数也可能偏小）" : "")
              .append("；区间 ")
              .append(r.dateFrom()).append(" ~ ").append(r.dateTo())
              .append("。这不是全部，请缩小日期区间或增加筛选后重新导出。\"");
        }
        return sb.toString();
    }

    private static String resultText(Object success) {
        if (success == null) return "";
        return Boolean.TRUE.equals(success) ? "成功" : "失败/被拒";
    }

    /** CSV 单元格转义 + 公式注入防护（与运行日志导出同一套规则）。 */
    private static String cell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\",";
    }
}
