package com.report.controller;

import com.report.model.ReportInfo;
import com.report.model.ReportInfo.QueryResult;
import com.report.service.ExcelService;
import com.report.service.GroupPermissionService;
import com.report.service.ReportService;
import com.report.service.UserManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportService reportService;
    private final ExcelService excelService;
    private final GroupPermissionService groupPermissionService;
    private final UserManagementService userManagementService;

    public ReportController(ReportService reportService, ExcelService excelService,
                            GroupPermissionService groupPermissionService,
                            UserManagementService userManagementService) {
        this.reportService = reportService;
        this.excelService = excelService;
        this.groupPermissionService = groupPermissionService;
        this.userManagementService = userManagementService;
    }

    /**
     * 获取所有报表列表（根据用户权限过滤）
     */
    @GetMapping("/api/reports")
    public List<Map<String, Object>> listReports(@AuthenticationPrincipal UserDetails user) {
        // 获取用户可访问的分组
        List<String> userGroups = null; // null=全部
        if (user != null) {
            boolean isAdmin = user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            if (!isAdmin) {
                userGroups = userManagementService.getUserGroups(user.getUsername());
            }
        }

        // 过滤报表
        List<String> allIds = reportService.getAllReports().stream()
            .map(ReportInfo::getId).collect(Collectors.toList());
        List<String> allowedIds = groupPermissionService.filterReportsByGroups(allIds, userGroups);

        boolean isAdmin = user != null && user.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        List<Map<String, Object>> list = new ArrayList<>();
        for (ReportInfo r : reportService.getAllReports()) {
            if (!allowedIds.contains(r.getId())) continue;
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
            if (isAdmin) map.put("hidden", groupPermissionService.isReportHidden(r.getId()));
            list.add(map);
        }
        return list;
    }

    /**
     * 获取单个报表详情
     */
    @GetMapping("/api/report/{id}")
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
        return ResponseEntity.ok(report);
    }

    /**
     * 获取下拉框选项
     */
    @GetMapping("/api/report/{id}/options/{paramName}")
    public ResponseEntity<?> getOptions(@PathVariable String id, @PathVariable String paramName,
                                        @AuthenticationPrincipal UserDetails user) {
        ResponseEntity<?> error = validateReportId(id);
        if (error != null) return error;
        error = checkReportAccess(user, id);
        if (error != null) return error;
        if (paramName == null || paramName.trim().isEmpty() || paramName.length() > 50) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的参数名"));
        }

        List<Map<String, String>> options = reportService.getOptions(id, paramName);
        return ResponseEntity.ok(options);
    }

    /**
     * 执行查询
     */
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

        QueryResult result = reportService.query(id, params, page, pageSize);

        if (result.getError() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", result.getError()));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 查询全部数据（不分页，用于前端缓存）
     */
    @PostMapping("/api/report/{id}/query-all")
    public ResponseEntity<?> queryAll(@PathVariable String id, @RequestBody Map<String, Object> body,
                                      HttpServletRequest request,
                                      @AuthenticationPrincipal UserDetails user) {
        ResponseEntity<?> error = validateReportId(id);
        if (error != null) return error;
        error = checkReportAccess(user, id);
        if (error != null) return error;

        Map<String, String> params = toStringMap(body.getOrDefault("params", Collections.emptyMap()));
        String clientIp = getClientIp(request);

        QueryResult result = reportService.queryAll(id, params, clientIp);

        if (result.getError() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", result.getError()));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 导出Excel
     */
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

        ReportInfo report = reportService.getReport(id);
        if (report == null) {
            sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "报表不存在");
            return;
        }

        String clientIp = getClientIp(request);
        QueryResult result = reportService.exportAll(id, params, clientIp);
        if (result.getError() != null) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, result.getError());
            return;
        }

        String filename = report.getName() + "_" +
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
            "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);
        response.setHeader("Cache-Control", "must-revalidate, post-check=0, pre-check=0");

        excelService.export(report.getName(), result, response.getOutputStream());
        response.getOutputStream().flush();
    }

    /**
     * 刷新报表列表
     */
    @PostMapping("/api/reports/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> refresh() {
        reportService.loadReports();
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("count", reportService.getAllReports().size());
        return result;
    }

    // ============ 异常处理 ============

    /**
     * 处理权限不足异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException e) {
        log.warn("权限不足: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("error", "权限不足，无法执行此操作"));
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        return ResponseEntity.badRequest()
            .body(Map.of("error", e.getMessage()));
    }

    /**
     * 处理SQL异常
     */
    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, String>> handleSqlException(SQLException e) {
        log.error("数据库异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "数据库查询失败，请联系管理员"));
    }

    /**
     * 全局异常处理
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception e) {
        log.error("请求处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "服务器内部错误，请稍后再试"));
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
     * 校验当前用户是否有权访问指定报表（基于分组权限）
     * @return 如果无权访问返回403响应，如果有权或管理员返回null
     */
    private ResponseEntity<?> checkReportAccess(UserDetails user, String reportId) {
        if (user == null) return null; // 未认证请求由Spring Security处理
        boolean isAdmin = user.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (isAdmin) return null; // 管理员可访问全部

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
}
