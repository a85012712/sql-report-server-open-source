package com.report.controller;

import com.report.model.ReportInfo;
import com.report.model.ReportInfo.QueryResult;
import com.report.service.GroupPermissionService;
import com.report.service.ReportService;
import com.report.service.RolePermissionService;
import com.report.service.UserManagementService;
import com.report.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportService reportService;
    private final GroupPermissionService groupPermissionService;
    private final UserManagementService userManagementService;
    private final RolePermissionService rolePermissionService;
    private final ClientIpResolver clientIpResolver;

    public ReportController(ReportService reportService,
                            GroupPermissionService groupPermissionService,
                            UserManagementService userManagementService,
                            RolePermissionService rolePermissionService,
                            ClientIpResolver clientIpResolver) {
        this.reportService = reportService;
        this.groupPermissionService = groupPermissionService;
        this.userManagementService = userManagementService;
        this.rolePermissionService = rolePermissionService;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * 是否为「报表管理员」，按权限点判定，不按 legacy 的 {@code ADMIN}/{@code USER} 字段。
     *
     * <p>缺陷：本类原先三处都写成 {@code authorities.contains("ROLE_ADMIN")}，
     * 而 {@code UserDetailsConfig} 只给内置超管与 legacy {@code role=ADMIN} 的账号
     * 授予 {@code ROLE_ADMIN}，任何自定义角色（含只有 {@code report.manage} 的
     * 「报表管理员」）都是 {@code ROLE_USER}。后果：该角色打开「报表管理」页时
     * 只看到自己所属报表分组内的报表、隐藏报表完全不出现、也没有 {@code hidden} 字段
     * （于是无法取消隐藏）， 一个「能进这个菜单、却管不了东西」的残页。
     *
     * <p>与「角色三处同源」是同一个缺陷类：权限判定与实际授权不一致。
     * 内置超管天然拥有全部权限点，故用权限点判定不会削弱超管。
     */
    private boolean isReportManager(UserDetails user) {
        return user != null
            && rolePermissionService.has(user.getUsername(), RolePermissionService.P_REPORT_MANAGE);
    }

    /**
     * 获取所有报表列表（根据用户权限过滤）
     *
     * <p>权限点统一（D9）：本端点原先**没有任何 {@code @PreAuthorize}**，只按「报表分组」
     * 过滤，而下游的 {@code /api/report/{id}/options} 与 {@code /api/report/{id}/query}
     * 却要求 {@code reports.view}。于是「谁能看报表」变成两个判据：
     * 列表按分组、查询按权限点。后果是「大屏查看」角色（只有 {@code screen.view}）
     * 侧栏进得去报表中心、列表也拉得到，一点进去查就 403 ，
     * 界面给出「能点」的假象，点下去才知道不行。
     *
     * <p>现在与查询端点同源：{@code reports.view} 决定「能不能看报表」，
     * 分组只决定「能看到哪些报表」。{@code report.manage} 一并放行，
     * 因为报表管理页（{@code page-reportmanage}）也用本端点取列表，
     * 而它的入口权限是 {@code report.manage}。
     */
    @GetMapping("/api/reports")
    @PreAuthorize("@perm.has('reports.view') or @perm.has('report.manage')")
    public List<Map<String, Object>> listReports(@AuthenticationPrincipal UserDetails user) {
        boolean isAdmin = isReportManager(user);

        // 获取用户可访问的分组
        List<String> userGroups = null; // null=全部
        if (!isAdmin && user != null) {
            userGroups = userManagementService.getUserGroups(user.getUsername());
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (ReportInfo r : reportService.getAllReports()) {
            // 分组权限过滤
            if (userGroups != null) {
                String reportGroup = groupPermissionService.getReportGroup(r.getId());
                if (!userGroups.contains(reportGroup)) continue;
            }
            // 非管理员不显示隐藏报表
            if (!isAdmin && groupPermissionService.isReportHidden(r.getId())) continue;

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", r.getId());

            // 应用自定义显示名
            String customName = groupPermissionService.getCustomName(r.getId());
            map.put("name", customName != null ? customName : r.getName());

            map.put("description", r.getDescription());
            map.put("params", r.getParams());
            map.put("group", groupPermissionService.getReportGroup(r.getId()));
            if (r.getDataSourceId() != null && !r.getDataSourceId().isBlank()) {
                map.put("dataSourceId", r.getDataSourceId());
            }
            if (isAdmin) map.put("hidden", groupPermissionService.isReportHidden(r.getId()));
            list.add(map);
        }
        return list;
    }

    /**
     * 获取单个报表详情
     *
     * <p>与 {@link #listReports} 同理（D9）：详情也是「看报表」的一部分，
     * 判据必须与列表、查询一致，都走 {@code reports.view}。
     */
    @GetMapping("/api/report/{id}")
    @PreAuthorize("@perm.has('reports.view') or @perm.has('report.manage')")
    public ResponseEntity<?> getReport(@PathVariable String id, @AuthenticationPrincipal UserDetails user) {
        ResponseEntity<?> error = validateReportId(id);
        if (error != null) return error;
        error = checkReportAccess(user, id);
        if (error != null) return error;

        ReportInfo report = reportService.getReport(id);
        if (report == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "报表不存在"));
        }
        // 普通接口不返回 SQL 全文，避免业务表结构泄露；管理员请走 /api/admin/reports/{id}/sql
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", report.getId());
        dto.put("name", report.getName());
        dto.put("description", report.getDescription());
        dto.put("params", report.getParams());
        dto.put("file", report.getFile());
        if (report.getDataSourceId() != null && !report.getDataSourceId().isBlank()) {
            dto.put("dataSourceId", report.getDataSourceId());
        }
        dto.put("group", groupPermissionService.getReportGroup(report.getId()));
        if (isReportManager(user)) {
            dto.put("hidden", groupPermissionService.isReportHidden(report.getId()));
        }
        return ResponseEntity.ok(dto);
    }

    /**
     * 获取下拉框选项
     */
    @GetMapping("/api/report/{id}/options/{paramName}")
    @PreAuthorize("@perm.has('reports.view')")
    public ResponseEntity<?> getOptions(@PathVariable String id, @PathVariable String paramName,
                                        @AuthenticationPrincipal UserDetails user) {
        ResponseEntity<?> error = validateReportId(id);
        if (error != null) return error;
        error = checkReportAccess(user, id);
        if (error != null) return error;
        if (paramName == null || paramName.trim().isEmpty() || paramName.length() > 50) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的参数名"));
        }

        List<String> allowed = userManagementService.getAllowedCommunities(user.getUsername());
        List<Map<String, String>> options = reportService.getOptions(id, paramName, allowed);
        return ResponseEntity.ok(options);
    }

    /**
     * 执行查询
     */
    @PreAuthorize("@perm.has('reports.view')")
    @PostMapping("/api/report/{id}/query")
    public ResponseEntity<?> query(@PathVariable String id, @RequestBody Map<String, Object> body,
                                   @AuthenticationPrincipal UserDetails user) {
        ResponseEntity<?> error = validateReportId(id);
        if (error != null) return error;
        error = checkReportAccess(user, id);
        if (error != null) return error;

        Map<String, String> params = toStringMap(body.getOrDefault("params", Collections.emptyMap()));
        int page = toInt(body.get("page"), 1);
        int pageSize = toInt(body.get("page_size"), 100);

        // 参数验证
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 1;
        if (pageSize > 500) pageSize = 500; // 限制最大页面大小
        // 上限防的是 (page-1)*pageSize+1 在 ReportService 里溢出成负数起点
        if (page > 100000) page = 100000;

        List<String> allowedCommunities = userManagementService.getAllowedCommunities(user.getUsername());
        QueryResult result = reportService.query(id, params, page, pageSize, allowedCommunities);

        if (result.getError() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", result.getError()));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 查询全部数据（仅管理员；普通前端已改用分页 /query）。
     * 防止误用导致内存与连接池压力。
     */
    @PostMapping("/api/report/{id}/query-all")
    @PreAuthorize("@perm.has('report.manage')")
    public ResponseEntity<?> queryAll(@PathVariable String id, @RequestBody Map<String, Object> body,
                                      HttpServletRequest request,
                                      @AuthenticationPrincipal UserDetails user) {
        ResponseEntity<?> error = validateReportId(id);
        if (error != null) return error;
        error = checkReportAccess(user, id);
        if (error != null) return error;

        Map<String, String> params = toStringMap(body.getOrDefault("params", Collections.emptyMap()));
        String clientIp = getClientIp(request);

        List<String> allowed = userManagementService.getAllowedCommunities(user.getUsername());
        QueryResult result = reportService.queryAll(id, params, clientIp, allowed);

        if (result.getError() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", result.getError()));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 导出Excel
     */
    @PreAuthorize("@perm.has('report.export')")
    @PostMapping("/api/report/{id}/export")
    public void export(@PathVariable String id, @RequestBody Map<String, Object> body,
                        HttpServletRequest request, HttpServletResponse response,
                        @AuthenticationPrincipal UserDetails user) throws IOException {
        // 参数校验
        if (id == null || id.trim().isEmpty() || id.length() > 100) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "无效的报表ID");
            return;
        }

        // 分组权限校验
        ResponseEntity<?> accessError = checkReportAccess(user, id);
        if (accessError != null) {
            sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "权限不足，无法访问该报表");
            return;
        }

        Map<String, String> params = toStringMap(body.getOrDefault("params", Collections.emptyMap()));
        List<String> allowed = userManagementService.getAllowedCommunities(user.getUsername());

        ReportInfo report = reportService.getReport(id);
        if (report == null) {
            sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "报表不存在");
            return;
        }

        String clientIp = getClientIp(request);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        // 报表名是中文：不能直接进 filename=""；而把 percent 编码塞进 filename="" 会让
        // 不认 filename* 的客户端存出 "%E9%97%A8…xlsx"。所以 plain 段用 ASCII 兜底名，
        // 中文名走 RFC 5987 的 filename*。
        String asciiFallback = "report_" + ts + ".xlsx";
        String encodedFilename = URLEncoder.encode(report.getName() + "_" + ts + ".xlsx",
            StandardCharsets.UTF_8).replace("+", "%20");

        // 预检通过后再设置 xlsx 下载头，避免错误时客户端收到坏文件
        try {
            reportService.validateForExport(id, params, allowed);
        } catch (IllegalArgumentException e) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
            "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encodedFilename);
        // 患者明细导出必须 no-store：原先写的 must-revalidate… 把 Spring Security 兜底设的
        // no-store 覆盖掉了，公共诊室的共享终端会把导出留在磁盘缓存里。
        response.setHeader("Cache-Control", "no-store");

        try {
            var outcome = reportService.exportStream(id, params, clientIp, allowed, response.getOutputStream());
            if (outcome.isTruncated()) {
                response.setHeader("X-Report-Truncated", "true");
            }
            response.getOutputStream().flush();
        } catch (IllegalArgumentException e) {
            if (!response.isCommitted()) {
                response.reset();
                sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            } else {
                log.warn("导出参数错误且响应已提交: {}", e.getMessage());
            }
        } catch (IllegalStateException e) {
            if (!response.isCommitted()) {
                response.reset();
                sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, e.getMessage());
            } else {
                log.warn("导出繁忙且响应已提交: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("导出失败: reportId={}", id, e);
            if (!response.isCommitted()) {
                response.reset();
                sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "导出失败，请稍后再试");
            }
        }
    }

    /**
     * 刷新报表列表
     */
    @PostMapping("/api/reports/refresh")
    @PreAuthorize("@perm.has('report.manage')")
    public Map<String, Object> refresh() {
        reportService.loadReports();
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("count", reportService.getAllReports().size());
        return result;
    }

    // ============ 工具方法 ============

    /**
     * 验证报表ID是否有效
     * @return 如果无效返回错误响应，如果有效返回null
     */
    private ResponseEntity<?> validateReportId(String id) {
        if (id == null || id.trim().isEmpty() || id.length() > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的报表ID"));
        }
        return null;
    }

    /**
     * 校验当前用户是否有权访问指定报表（分组权限 + 隐藏状态）
     * @return 如果无权访问返回403响应，如果有权或管理员返回null
     */
    private ResponseEntity<?> checkReportAccess(UserDetails user, String reportId) {
        if (user == null) return null; // 未认证请求由Spring Security处理
        if (isReportManager(user)) return null; // 报表管理员可访问全部（含隐藏报表）

        // 非管理员不可访问隐藏报表（列表与直链查询/导出统一拦截）
        if (groupPermissionService.isReportHidden(reportId)) {
            log.warn("访问隐藏报表被拒绝: user={}, report={}", user.getUsername(), reportId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "权限不足，无权访问该报表"));
        }

        List<String> userGroups = userManagementService.getUserGroups(user.getUsername());
        if (userGroups == null) return null; // null表示全部可见

        String reportGroup = groupPermissionService.getReportGroup(reportId);
        if (!userGroups.contains(reportGroup)) {
            log.warn("权限不足: user={}, report={}, requiredGroup={}, userGroups={}",
                user.getUsername(), reportId, reportGroup, userGroups);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "权限不足，无权访问该报表"));
        }
        return null;
    }

    /**
     * 发送错误响应（用于HttpServletResponse）
     */
    private void sendErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toStringMap(Object obj) {
        if (obj instanceof Map) {
            Map<String, String> result = new HashMap<>();
            ((Map<String, Object>) obj).forEach((k, v) -> {
                if (k != null) {
                    result.put(k.toString(), v != null ? v.toString() : null);
                }
            });
            return result;
        }
        return Collections.emptyMap();
    }

    private int toInt(Object obj, int defaultVal) {
        if (obj instanceof Number) return ((Number) obj).intValue();
        try {
            return Integer.parseInt(obj.toString());
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private String getClientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }
}
