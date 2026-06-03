package com.report.controller;

import com.report.model.ReportInfo;
import com.report.service.GroupPermissionService;
import com.report.service.ReportService;
import com.report.service.UserManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;

import javax.sql.DataSource;
import java.sql.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final UserManagementService userManagementService;
    private final GroupPermissionService groupPermissionService;
    private final ReportService reportService;
    private final DataSource dataSource;

    @Value("${report.scripts-dir:scripts}")
    private String scriptsDir;

    public AdminController(UserManagementService userManagementService,
                           GroupPermissionService groupPermissionService,
                           ReportService reportService,
                           DataSource dataSource) {
        this.userManagementService = userManagementService;
        this.groupPermissionService = groupPermissionService;
        this.reportService = reportService;
        this.dataSource = dataSource;
    }

    @GetMapping("/current-user")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> currentUser(@AuthenticationPrincipal UserDetails user) {
        boolean isAdmin = user.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("username", user.getUsername());
        m.put("name", userManagementService.getUserName(user.getUsername()));
        m.put("role", isAdmin ? "ADMIN" : "USER");
        m.put("mustChangePassword", userManagementService.mustChangePassword(user.getUsername()));
        return m;
    }

    /**
     * 获取用户列表
     */
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> listUsers() {
        return userManagementService.listUsers();
    }

    @PutMapping("/profile/password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> changeOwnPassword(@RequestBody Map<String, String> body,
                                                @AuthenticationPrincipal UserDetails currentUser) {
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("password");
        if (newPassword == null || newPassword.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "密码长度不能少于6位"));
        }
        try {
            if (oldPassword != null && !oldPassword.isEmpty()) {
                userManagementService.changeOwnPassword(currentUser.getUsername(), oldPassword, newPassword);
            } else {
                userManagementService.changePassword(currentUser.getUsername(), newPassword);
            }
            log.info("[AUDIT] action=changeOwnPassword, username={}", currentUser.getUsername());
            return ResponseEntity.ok(Map.of("status", "ok", "message", "密码修改成功"));
        } catch (Exception e) {
            log.error("修改密码失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "修改密码失败"));
        }
    }

    /**
     * 创建用户
     */
    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> createUser(@RequestBody Map<String, String> body,
                                        @AuthenticationPrincipal UserDetails currentUser) {
        String username = body.get("username");
        String name = body.get("name");
        String role = body.getOrDefault("role", "USER");

        if (username == null || !username.matches("^[a-zA-Z0-9]{3,20}$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "工号必须为3-20位字母或数字"));
        }
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "姓名不能为空"));
        }
        if (!"USER".equals(role) && !"ADMIN".equals(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "角色只能是 USER 或 ADMIN"));
        }
        if (userManagementService.userExists(username)) {
            return ResponseEntity.badRequest().body(Map.of("error", "工号已存在"));
        }

        try {
            userManagementService.createUser(username, name.trim(), role);
            log.info("[AUDIT] action=adminCreateUser, operator={}, username={}, name={}, role={}",
                currentUser.getUsername(), username, name, role);
            return ResponseEntity.ok(Map.of("status", "ok", "message", "用户创建成功，初始密码: " + UserManagementService.DEFAULT_PASSWORD));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("创建用户失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "创建用户失败，请稍后再试"));
        }
    }

    /**
     * 修改密码
     */
    @PutMapping("/users/{username}/password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> changePassword(@PathVariable String username,
                                            @RequestBody Map<String, String> body,
                                            @AuthenticationPrincipal UserDetails currentUser) {
        String newPassword = body.get("password");
        if (newPassword == null || newPassword.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "密码长度不能少于6位"));
        }
        if (!userManagementService.userExists(username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在"));
        }

        try {
            userManagementService.changePassword(username, newPassword);
            log.info("[AUDIT] action=adminChangePassword, operator={}, targetUser={}",
                currentUser.getUsername(), username);
            return ResponseEntity.ok(Map.of("status", "ok", "message", "密码修改成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("修改密码失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "修改密码失败，请稍后再试"));
        }
    }

    /**
     * 删除用户
     */
    @PutMapping("/users/{username}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> resetToDefault(@PathVariable String username,
                                            @AuthenticationPrincipal UserDetails currentUser) {
        if (!userManagementService.userExists(username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在"));
        }
        try {
            userManagementService.changePassword(username, UserManagementService.DEFAULT_PASSWORD);
            // 重置后标记需要改密
            userManagementService.setMustChangePassword(username, true);
            log.info("[AUDIT] action=resetPassword, operator={}, target={}", currentUser.getUsername(), username);
            return ResponseEntity.ok(Map.of("status", "ok", "message", "密码已重置为默认密码"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "重置失败"));
        }
    }

    @DeleteMapping("/users/{username}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteUser(@PathVariable String username,
                                        @AuthenticationPrincipal UserDetails currentUser) {
        if (username.equals(currentUser.getUsername())) {
            return ResponseEntity.badRequest().body(Map.of("error", "不能删除当前登录的用户"));
        }
        if (!userManagementService.userExists(username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在"));
        }

        try {
            userManagementService.deleteUser(username);
            log.info("[AUDIT] action=adminDeleteUser, operator={}, targetUser={}",
                currentUser.getUsername(), username);
            return ResponseEntity.ok(Map.of("status", "ok", "message", "用户删除成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("删除用户失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "删除用户失败，请稍后再试"));
        }
    }

    /**
     * 修改角色
     */
    @PutMapping("/users/{username}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> changeRole(@PathVariable String username,
                                        @RequestBody Map<String, String> body,
                                        @AuthenticationPrincipal UserDetails currentUser) {
        String role = body.get("role");
        if (role == null || (!"USER".equals(role) && !"ADMIN".equals(role))) {
            return ResponseEntity.badRequest().body(Map.of("error", "角色只能是 USER 或 ADMIN"));
        }
        if (!userManagementService.userExists(username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在"));
        }

        try {
            // 可选：同时修改密码
            String newPassword = body.get("password");
            if (newPassword != null && !newPassword.isEmpty()) {
                if (newPassword.length() < 6) {
                    return ResponseEntity.badRequest().body(Map.of("error", "密码长度不能少于6位"));
                }
                userManagementService.changePassword(username, newPassword);
            }

            userManagementService.changeRole(username, role);
            log.info("[AUDIT] action=adminChangeRole, operator={}, targetUser={}, newRole={}",
                currentUser.getUsername(), username, role);
            return ResponseEntity.ok(Map.of("status", "ok", "message", "角色修改成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("修改角色失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "修改角色失败，请稍后再试"));
        }
    }

    @GetMapping("/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> viewLogs(@RequestParam(defaultValue = "200") int lines,
                                      @RequestParam(defaultValue = "all") String category) {
        try {
            String logPathStr = System.getProperty("APP_LOG_DIR", "./logs") + "/his-report-server.log";
            Path logPath = Paths.get(logPathStr);
            if (!Files.exists(logPath)) {
                logPath = Paths.get("./logs/sql-report-server.log");
            }
            if (!Files.exists(logPath)) {
                return ResponseEntity.ok(Map.of("content", "日志文件不存在", "lines", List.of(),
                    "stats", Map.of("total", 0, "audit", 0, "error", 0, "warn", 0, "security", 0)));
            }

            int maxLines = Math.min(lines, 2000);
            java.util.List<String> allLines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            int fromIndex = Math.max(0, allLines.size() - maxLines);
            java.util.List<String> tailLines = allLines.subList(fromIndex, allLines.size());

            // 解析每行日志，分类标记
            java.util.List<Map<String, String>> parsedLines = new java.util.ArrayList<>();
            int auditCount = 0, errorCount = 0, warnCount = 0, securityCount = 0;

            for (String line : tailLines) {
                String type = "info";
                if (line.contains("[AUDIT]")) { type = "audit"; auditCount++; }
                else if (line.contains("ERROR") || line.contains("Exception") || line.contains("异常")) { type = "error"; errorCount++; }
                else if (line.contains("WARN")) { type = "warn"; warnCount++; }

                // 安全类事件检测
                boolean isSecurity = false;
                if (line.contains("权限不足") || line.contains("AccessDenied") || line.contains("Forbidden")
                    || line.contains("401") || line.contains("403")
                    || line.contains("CSRF") || line.contains("攻击") || line.contains("注入")
                    || line.contains("SQL injection") || line.contains("XSS")
                    || line.contains("非法") || line.contains("恶意")
                    || line.contains("Rate limit") || line.contains("限流")
                    || line.contains("认证失败") || line.contains("AuthenticationFailed")) {
                    type = "security"; isSecurity = true; securityCount++;
                }

                // 根据category过滤
                if (!"all".equals(category) && !type.equals(category)) continue;

                Map<String, String> entry = new java.util.LinkedHashMap<>();
                entry.put("type", type);
                entry.put("content", line);
                parsedLines.add(entry);
            }

            String content = tailLines.stream().collect(Collectors.joining("\n"));
            Map<String, Object> stats = new java.util.LinkedHashMap<>();
            stats.put("total", tailLines.size());
            stats.put("audit", auditCount);
            stats.put("error", errorCount);
            stats.put("warn", warnCount);
            stats.put("security", securityCount);

            return ResponseEntity.ok(Map.of(
                "content", content,
                "lines", parsedLines,
                "filePath", logPath.toString(),
                "totalLines", String.valueOf(allLines.size()),
                "stats", stats
            ));
        } catch (IOException e) {
            log.error("读取日志失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "读取日志失败: " + e.getMessage()));
        }
    }

    // ============ 报表分组管理 ============

    /**
     * 获取分组配置（分组列表+报表分配+自定义名称）
     */
    @GetMapping("/groups/config")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> getGroupConfig() {
        return groupPermissionService.getFullConfig();
    }

    /**
     * 获取所有分组
     */
    @GetMapping("/groups")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> listGroups() {
        return groupPermissionService.getAllGroups();
    }

    /**
     * 创建分组
     */
    @PostMapping("/groups")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> createGroup(@RequestBody Map<String, Object> body) {
        String id = (String) body.get("id");
        String name = (String) body.get("name");
        int sort = body.get("sort") instanceof Number ? ((Number) body.get("sort")).intValue() : 1;
        try {
            groupPermissionService.createGroup(id, name, sort);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 更新分组
     */
    @PutMapping("/groups/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateGroup(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            String name = (String) body.get("name");
            Integer sort = body.get("sort") instanceof Number ? ((Number) body.get("sort")).intValue() : null;
            groupPermissionService.updateGroup(id, name, sort);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除分组
     */
    @DeleteMapping("/groups/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteGroup(@PathVariable String id) {
        try {
            groupPermissionService.deleteGroup(id);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 设置报表所属分组
     */
    @PutMapping("/groups/assignment/{reportId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> setReportGroup(@PathVariable String reportId, @RequestBody Map<String, String> body) {
        try {
            groupPermissionService.setReportGroup(reportId, body.get("groupId"));
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 报表重命名
     */
    @PutMapping("/reports/{reportId}/rename")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> renameReport(@PathVariable String reportId, @RequestBody Map<String, String> body) {
        String newName = body.get("name");
        if (newName == null || newName.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "名称不能为空"));
        }
        groupPermissionService.setCustomName(reportId, newName.trim());
        return ResponseEntity.ok(Map.of("status", "ok", "name", newName.trim()));
    }

    // ============ 报表删除/隐藏/编辑SQL ============

    /**
     * 根据报表ID解析完整文件路径
     * report.getFile()只返回文件名，需要拼接scriptsDir
     */
    private Path resolveReportFile(ReportInfo report) {
        String fileName = report.getFile();
        if (fileName == null) return null;
        Path p = Paths.get(scriptsDir, fileName);
        if (Files.exists(p)) return p;
        // 兜底：尝试直接用文件名（兼容旧逻辑）
        p = Paths.get(fileName);
        return Files.exists(p) ? p : null;
    }

    /**
     * 删除报表文件
     * 如果报表已不存在（重复请求），直接返回成功
     */
    @DeleteMapping("/reports/{reportId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteReport(@PathVariable String reportId,
                                          @AuthenticationPrincipal UserDetails currentUser) {
        ReportInfo report = reportService.getReport(reportId);
        if (report == null) {
            // 已被删除，直接返回成功（防止重复请求报错）
            return ResponseEntity.ok(Map.of("status", "ok", "message", "报表已删除"));
        }
        Path filePath = resolveReportFile(report);
        try {
            // 文件存在则删除，不存在也继续（只清理配置）
            if (filePath != null) {
                Files.delete(filePath);
            }
            groupPermissionService.removeReport(reportId);
            reportService.loadReports();
            log.info("[AUDIT] action=deleteReport, operator={}, report={}, file={}, fileExisted={}",
                currentUser.getUsername(), reportId,
                filePath != null ? filePath.getFileName() : "N/A",
                filePath != null);
            return ResponseEntity.ok(Map.of("status", "ok", "message", "报表已删除"));
        } catch (IOException e) {
            log.error("删除报表文件失败: {}", filePath, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "删除失败: " + e.getMessage()));
        }
    }

    /**
     * 隐藏/显示报表
     */
    @PutMapping("/reports/{reportId}/hidden")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> setReportHidden(@PathVariable String reportId, @RequestBody Map<String, Object> body) {
        ReportInfo report = reportService.getReport(reportId);
        if (report == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "报表不存在"));
        }
        boolean hidden = Boolean.TRUE.equals(body.get("hidden"));
        groupPermissionService.setReportHidden(reportId, hidden);
        return ResponseEntity.ok(Map.of("status", "ok", "hidden", hidden));
    }

    /**
     * 获取报表SQL文件内容（用于编辑）
     */
    @GetMapping("/reports/{reportId}/sql")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getReportSql(@PathVariable String reportId) {
        ReportInfo report = reportService.getReport(reportId);
        if (report == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "报表不存在"));
        }
        Path filePath = resolveReportFile(report);
        if (filePath == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "报表文件不存在"));
        }
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("status", "ok");
            resp.put("fileName", filePath.getFileName().toString());
            resp.put("content", content);
            resp.put("reportName", report.getName());
            return ResponseEntity.ok(resp);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "读取失败: " + e.getMessage()));
        }
    }

    /**
     * 更新报表SQL文件内容
     */
    @PutMapping("/reports/{reportId}/sql")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateReportSql(@PathVariable String reportId,
                                              @RequestBody Map<String, String> body,
                                              @AuthenticationPrincipal UserDetails currentUser) {
        ReportInfo report = reportService.getReport(reportId);
        if (report == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "报表不存在"));
        }
        String content = body.get("content");
        if (content == null || content.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "SQL内容不能为空"));
        }
        // 校验内容中必须有YAML头和SQL
        if (!content.contains("---")) {
            return ResponseEntity.badRequest().body(Map.of("error", "内容格式错误，缺少YAML元数据(---分隔符)"));
        }
        Path filePath = resolveReportFile(report);
        if (filePath == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "报表文件路径未知"));
        }
        try {
            // 备份原文件
            Path backupPath = Paths.get(filePath.toString() + ".bak");
            if (Files.exists(filePath)) {
                Files.copy(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }
            // 写入新内容
            Files.writeString(filePath, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            // 重新加载
            reportService.loadReports();
            log.info("[AUDIT] action=updateReportSql, operator={}, report={}, file={}",
                currentUser.getUsername(), reportId, filePath.getFileName());
            return ResponseEntity.ok(Map.of("status", "ok", "message", "SQL已更新，报表已重新加载"));
        } catch (IOException e) {
            log.error("更新报表SQL失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "保存失败: " + e.getMessage()));
        }
    }

    /**
     * 设置用户可访问的分组
     */
    @PutMapping("/users/{username}/groups")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> setUserGroups(@PathVariable String username, @RequestBody Map<String, Object> body) {
        if (!userManagementService.userExists(username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在"));
        }
        try {
            @SuppressWarnings("unchecked")
            List<String> groups = (List<String>) body.get("groups");
            userManagementService.setUserGroups(username, groups);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ============ SQL自动导入 ============

    /**
     * SQL自动导入：粘贴原始SQL，自动检测参数并转换为系统格式
     *
     * 支持的参数格式：
     *   [参数名]  — 方括号风格（如 [起始日期]）
     *   :参数名   — 冒号风格（已是系统格式）
     *
     * 请求体：
     *   name        — 报表名称
     *   description — 报表描述（可选）
     *   sql         — 原始SQL文本
     *   groupId     — 所属分组ID（可选，默认default）
     */
    @PostMapping("/sql/import")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> importSql(@RequestBody Map<String, String> body,
                                       @AuthenticationPrincipal UserDetails currentUser) {
        String name = body.get("name");
        String description = body.getOrDefault("description", "");
        String rawSql = body.get("sql");
        String groupId = body.getOrDefault("groupId", "default");

        // 自动修正全角字符
        if (rawSql != null) rawSql = normalizeFullwidthChars(rawSql);

        // 参数校验
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "报表名称不能为空"));
        }
        if (rawSql == null || rawSql.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "SQL内容不能为空"));
        }
        if (rawSql.length() > 100000) {
            return ResponseEntity.badRequest().body(Map.of("error", "SQL内容过长，最大支持100KB"));
        }

        // 生成文件名（用下划线替换特殊字符）
        String fileName = name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_\\-]", "_").trim();
        if (fileName.isEmpty()) fileName = "imported_report";
        Path filePath = Paths.get(scriptsDir, fileName + ".sql");

        // 检查文件是否已存在
        if (Files.exists(filePath)) {
            // 加序号
            for (int i = 1; i <= 99; i++) {
                filePath = Paths.get(scriptsDir, fileName + "_" + i + ".sql");
                if (!Files.exists(filePath)) break;
            }
        }

        try {
            // 自动检测和转换参数
            ConvertedSql result = convertSqlParameters(rawSql);

            // 生成YAML front matter
            StringBuilder yaml = new StringBuilder();
            yaml.append("---\n");
            yaml.append("name: ").append(name).append("\n");
            if (description != null && !description.isEmpty()) {
                yaml.append("description: ").append(description).append("\n");
            }
            yaml.append("params:\n");
            for (ParamInfo param : result.params) {
                yaml.append("  - name: ").append(param.name).append("\n");
                yaml.append("    label: ").append(param.label).append("\n");
                yaml.append("    type: ").append(param.type).append("\n");
                if ("date".equals(param.type)) {
                    yaml.append("    required: true\n");
                }
                if (!"date".equals(param.type)) {
                    yaml.append("    placeholder: \"%\"\n");
                    yaml.append("    default: \"%\"\n");
                }
            }
            yaml.append("---\n\n");

            // 组合最终文件内容
            String fileContent = yaml.toString() + result.convertedSql;

            // 确保目录存在
            Path parent = filePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.writeString(filePath, fileContent, StandardOpenOption.CREATE_NEW);

            // 设置报表分组
            groupPermissionService.setReportGroup(filePath.getFileName().toString().replace(".sql", ""), groupId);

            // 重新加载报表列表
            reportService.loadReports();

            log.info("[AUDIT] action=importSql, operator={}, name={}, file={}, params={}",
                currentUser.getUsername(), name, filePath.getFileName(), result.params.size());

            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("status", "ok");
            resp.put("message", "SQL导入成功");
            resp.put("fileName", filePath.getFileName().toString());
            resp.put("paramCount", result.params.size());
            resp.put("params", result.params.stream().map(p -> p.name + "(" + p.label + ")").collect(Collectors.toList()));
            return ResponseEntity.ok(resp);

        } catch (IOException e) {
            log.error("SQL导入失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "保存文件失败: " + e.getMessage()));
        } catch (Exception e) {
            log.error("SQL转换失败", e);
            return ResponseEntity.badRequest().body(Map.of("error", "SQL处理失败: " + e.getMessage()));
        }
    }

    /**
     * SQL参数预览：不保存，只返回转换后的结果供用户确认
     */
    @PostMapping("/sql/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> previewSql(@RequestBody Map<String, String> body) {
        String rawSql = body.get("sql");
        if (rawSql == null || rawSql.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "SQL内容不能为空"));
        }
        // 自动修正全角字符
        rawSql = normalizeFullwidthChars(rawSql);
        try {
            ConvertedSql result = convertSqlParameters(rawSql);

            // 生成预览YAML
            StringBuilder yaml = new StringBuilder();
            yaml.append("---\n");
            yaml.append("name: ").append(body.getOrDefault("name", "未命名报表")).append("\n");
            String desc = body.getOrDefault("description", "");
            if (!desc.isEmpty()) yaml.append("description: ").append(desc).append("\n");
            yaml.append("params:\n");
            for (ParamInfo param : result.params) {
                yaml.append("  - name: ").append(param.name).append("\n");
                yaml.append("    label: ").append(param.label).append("\n");
                yaml.append("    type: ").append(param.type).append("\n");
                if ("date".equals(param.type)) yaml.append("    required: true\n");
                if (!"date".equals(param.type)) { yaml.append("    placeholder: \"%\"\n"); yaml.append("    default: \"%\"\n"); }
            }
            yaml.append("---\n\n");

            return ResponseEntity.ok(Map.of(
                "status", "ok",
                "yaml", yaml.toString(),
                "convertedSql", result.convertedSql,
                "params", result.params.stream().map(p -> {
                    Map<String, String> m = new java.util.LinkedHashMap<>();
                    m.put("name", p.name);
                    m.put("label", p.label);
                    m.put("type", p.type);
                    m.put("original", p.original);
                    return m;
                }).collect(Collectors.toList())
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "SQL解析失败: " + e.getMessage()));
        }
    }

    // ============ SQL验证查询 ============

    /**
     * SQL验证查询：使用测试参数执行SQL，返回前10行样例数据
     * 用于导入前确认SQL能正常运行
     *
     * 请求体：
     *   sql    — 转换后的SQL（:param格式）
     *   params — 测试参数值 Map（可选，未提供的参数用默认值）
     */
    @PostMapping("/sql/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> testSql(@RequestBody Map<String, Object> body,
                                     @AuthenticationPrincipal UserDetails currentUser) {
        String sql = (String) body.get("sql");
        if (sql == null || sql.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "SQL内容不能为空"));
        }

        // 自动修正全角字符（从网页/富文本复制SQL时常出现）
        sql = normalizeFullwidthChars(sql);

        // 剥离注释（避免注释中的内容影响SQL执行，同时暴露隐藏的危险操作）
        sql = stripSqlComments(sql);

        // ====== 安全校验：只允许SELECT查询 ======
        String upperSql = sql.trim().toUpperCase();
        if (!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH")) {
            return ResponseEntity.badRequest().body(Map.of("error", "只允许SELECT查询语句"));
        }

        // 检查危险关键字（DDL + DML + 权限 + PL/SQL），用词边界匹配避免误拦 FDELETED 等列名
        Pattern dangerousPattern = Pattern.compile(
            "\\b(DROP|DELETE|TRUNCATE|ALTER|CREATE|INSERT|UPDATE|MERGE" +
            "|GRANT|REVOKE|EXEC|EXECUTE|CALL|COMMIT|ROLLBACK|SAVEPOINT" +
            "|BEGIN|END|DECLARE|DBMS_|UTL_|DBA_|TABLESPACE|ANALYZE)\\b",
            Pattern.CASE_INSENSITIVE);
        Matcher dangerMatcher = dangerousPattern.matcher(sql);
        if (dangerMatcher.find()) {
            String matched = dangerMatcher.group(1);
            log.warn("[SECURITY] SQL包含被禁关键字: operator={}, keyword={}", currentUser.getUsername(), matched);
            return ResponseEntity.badRequest().body(Map.of("error", "SQL包含被禁关键字「" + matched + "」，只允许纯SELECT查询"));
        }
        // SYS. 和 SYSTEM. 单独检查（无词边界，因为后面跟点号）
        if (upperSql.contains("SYS.") || upperSql.contains("SYSTEM.")) {
            return ResponseEntity.badRequest().body(Map.of("error", "SQL不允许访问系统表"));
        }

        // 额外安全：不允许分号（防止SQL注入拼接多条语句）
        if (sql.contains(";")) {
            return ResponseEntity.badRequest().body(Map.of("error", "SQL中不允许包含分号，只允许单条SELECT查询"));
        }

        @SuppressWarnings("unchecked")
        Map<String, String> paramValues = body.get("params") instanceof Map
            ? new HashMap<>((Map<String, String>) body.get("params"))
            : new HashMap<>();

        // 自动填充默认参数值（前端未提供的参数自动补上默认值）
        String today = java.time.LocalDate.now().toString(); // yyyy-MM-dd

        // 构建绑定参数
        Pattern paramPattern = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");
        Matcher matcher = paramPattern.matcher(sql);
        Set<String> paramNames = new LinkedHashSet<>();
        while (matcher.find()) {
            paramNames.add(matcher.group(1));
        }

        // 获取前端传来的参数类型信息（如果有），用于智能填充
        @SuppressWarnings("unchecked")
        Map<String, String> paramTypes = body.get("paramTypes") instanceof Map
            ? (Map<String, String>) body.get("paramTypes")
            : Collections.emptyMap();

        // 对未提供值的参数，根据名称/类型自动填充默认值
        for (String paramName : paramNames) {
            if (paramValues.containsKey(paramName) && paramValues.get(paramName) != null
                && !paramValues.get(paramName).isEmpty()) {
                continue; // 已有值，跳过
            }
            // 根据参数名推断类型并填充
            String lowerName = paramName.toLowerCase();
            if (lowerName.contains("date") || lowerName.contains("日期")
                || "date".equalsIgnoreCase(paramTypes.get(paramName))) {
                paramValues.put(paramName, today);
            } else if (lowerName.contains("month") || lowerName.contains("月份")) {
                paramValues.put(paramName, java.time.LocalDate.now().getMonthValue() < 10
                    ? "0" + java.time.LocalDate.now().getMonthValue()
                    : String.valueOf(java.time.LocalDate.now().getMonthValue()));
            } else if (lowerName.contains("year") || lowerName.contains("年度") || lowerName.contains("年份")) {
                paramValues.put(paramName, String.valueOf(java.time.LocalDate.now().getYear()));
            } else {
                paramValues.put(paramName, "%");
            }
        }

        // 包装为只取前10行，将 :paramName 替换为 ? 占位符
        String trimmedSql = sql.trim().replaceAll(";\\s*$", "");
        String testSql = trimmedSql.replaceAll("(:[a-zA-Z_][a-zA-Z0-9_]*)", "?");

        long startTime = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            // 设置只读 + 关闭自动提交（双保险，防止任何写操作）
            conn.setReadOnly(true);
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(testSql)) {

            ps.setQueryTimeout(10); // 10秒超时
            ps.setMaxRows(10);    // 最多返回10行

            // 绑定参数（按SQL中出现的顺序）
            int idx = 1;
            for (String paramName : paramNames) {
                String value = paramValues.getOrDefault(paramName, "%");
                ps.setString(idx++, value != null ? value : "%");
            }

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }

                List<List<String>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        Object val = rs.getObject(i);
                        row.add(val != null ? val.toString() : "");
                    }
                    rows.add(row);
                }

                long duration = System.currentTimeMillis() - startTime;
                log.info("[AUDIT] action=testSql, operator={}, paramCount={}, rowCount={}, duration={}ms",
                    currentUser.getUsername(), paramNames.size(), rows.size(), duration);

                Map<String, Object> resp = new java.util.LinkedHashMap<>();
                resp.put("status", "ok");
                resp.put("columns", columns);
                resp.put("rows", rows);
                resp.put("rowCount", rows.size());
                resp.put("duration", duration + "ms");
                resp.put("params", paramNames.stream().collect(Collectors.toList()));
                return ResponseEntity.ok(resp);
            }
            } // end PreparedStatement try
        } catch (SQLException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.warn("[AUDIT] action=testSqlFailed, operator={}, error={}, duration={}ms",
                currentUser.getUsername(), e.getMessage(), duration);
            // 脱敏错误信息
            String safeMsg = e.getMessage();
            if (safeMsg != null && (safeMsg.toUpperCase().contains("ORA-") || safeMsg.length() > 50)) {
                safeMsg = "SQL执行失败，请检查语法";
            } else {
                safeMsg = "SQL执行失败: " + safeMsg;
            }
            return ResponseEntity.badRequest().body(Map.of(
                "error", safeMsg,
                "duration", duration + "ms"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "验证查询异常: " + e.getMessage()));
        }
    }

    // ============ 日志导出 ============

    /**
     * 导出过滤后的日志
     * @param format 导出格式: txt 或 csv
     */
    @GetMapping("/logs/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> exportLogs(@RequestParam(defaultValue = "all") String category,
                                        @RequestParam(defaultValue = "txt") String format,
                                        @RequestParam(defaultValue = "2000") int lines) {
        try {
            String logPathStr = System.getProperty("APP_LOG_DIR", "./logs") + "/his-report-server.log";
            Path logPath = Paths.get(logPathStr);
            if (!Files.exists(logPath)) {
                logPath = Paths.get("./logs/sql-report-server.log");
            }
            if (!Files.exists(logPath)) {
                return ResponseEntity.badRequest().body(Map.of("error", "日志文件不存在"));
            }

            int maxLines = Math.min(lines, 5000);
            List<String> allLines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            int fromIndex = Math.max(0, allLines.size() - maxLines);
            List<String> tailLines = allLines.subList(fromIndex, allLines.size());

            // 过滤
            List<String> filtered = new ArrayList<>();
            for (String line : tailLines) {
                String type = "info";
                if (line.contains("[AUDIT]")) type = "audit";
                else if (line.contains("ERROR") || line.contains("Exception")) type = "error";
                else if (line.contains("WARN")) type = "warn";
                if (line.contains("权限不足") || line.contains("AccessDenied") || line.contains("Forbidden")
                    || line.contains("CSRF") || line.contains("攻击") || line.contains("注入")
                    || line.contains("Rate limit") || line.contains("限流")) type = "security";
                if ("all".equals(category) || type.equals(category)) filtered.add(line);
            }

            String content;
            String filename;
            String contentType;

            if ("csv".equals(format)) {
                StringBuilder csv = new StringBuilder();
                csv.append("时间,级别,内容\n");
                for (String line : filtered) {
                    String level = "INFO";
                    if (line.contains("[AUDIT]")) level = "AUDIT";
                    else if (line.contains("ERROR")) level = "ERROR";
                    else if (line.contains("WARN")) level = "WARN";
                    if (line.contains("权限不足") || line.contains("AccessDenied") || line.contains("攻击")) level = "SECURITY";

                    // CSV转义 + CSV注入防护（Bug #18修复）
                    String time = "";
                    if (line.length() > 23) time = line.substring(0, 23);
                    String bodyStr = line.length() > 24 ? line.substring(24) : line;
                    bodyStr = bodyStr.replace("\"", "\"\"");
                    // 防止CSV公式注入：如果字段以特殊字符开头，加前缀单引号
                    if (!bodyStr.isEmpty() && "=+-@\t\r".indexOf(bodyStr.charAt(0)) >= 0) {
                        bodyStr = "'" + bodyStr;
                    }
                    csv.append("\"").append(time).append("\",\"").append(level).append("\",\"").append(bodyStr).append("\"\n");
                }
                content = csv.toString();
                filename = "logs_" + category + "_" + java.time.LocalDate.now() + ".csv";
                contentType = "text/csv; charset=utf-8";
            } else {
                content = String.join("\n", filtered);
                filename = "logs_" + category + "_" + java.time.LocalDate.now() + ".txt";
                contentType = "text/plain; charset=utf-8";
            }

            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            // 添加BOM for Excel CSV compatibility
            if ("csv".equals(format)) {
                byte[] bom = {(byte)0xEF, (byte)0xBB, (byte)0xBF};
                byte[] withBom = new byte[bom.length + bytes.length];
                System.arraycopy(bom, 0, withBom, 0, bom.length);
                System.arraycopy(bytes, 0, withBom, bom.length, bytes.length);
                bytes = withBom;
            }

            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Content-Type", contentType)
                .body(bytes);

        } catch (IOException e) {
            log.error("导出日志失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "导出失败"));
        }
    }

    // ============ SQL转换工具 ============

    private static class ParamInfo {
        String name;     // 参数名（英文）
        String label;    // 显示名（中文）
        String type;     // 类型
        String original; // 原始文本

        ParamInfo(String name, String label, String type, String original) {
            this.name = name;
            this.label = label;
            this.type = type;
            this.original = original;
        }
    }

    private static class ConvertedSql {
        String convertedSql;
        List<ParamInfo> params;

        ConvertedSql(String sql, List<ParamInfo> params) {
            this.convertedSql = sql;
            this.params = params;
        }
    }

    /**
     * 自动转换SQL参数
     *
     * 将 [中文参数名] 格式转换为 :english_name 格式
     * 自动推断参数类型（日期、时间、年月等）
     */
    private ConvertedSql convertSqlParameters(String rawSql) {
        List<ParamInfo> params = new ArrayList<>();
        String sql = rawSql;
        Set<String> usedNames = new HashSet<>();
        Set<String> seenOriginals = new LinkedHashSet<>(); // 去重：已处理的原始文本

        // 先剥离注释，避免注释中的 [参数] 被误提取
        // 同时保留原始SQL用于替换，用去注释版本做参数检测
        String sqlWithoutComments = stripSqlComments(rawSql);

        // 匹配 [参数名] 格式（支持中文和英文）—— 在去注释版本中检测
        Pattern bracketPattern = Pattern.compile("\\[([^\\]]+)\\]");
        Matcher matcher = bracketPattern.matcher(sqlWithoutComments);

        while (matcher.find()) {
            String original = matcher.group(0);  // [起始日期] 或 [start_date]
            String paramNameRaw = matcher.group(1); // 起始日期 或 start_date

            // 跳过SQL关键字/常量
            if (paramNameRaw.length() < 2 || paramNameRaw.matches("(?i)null|all|none|true|false|select|from|where|and|or")) continue;

            // 去重：同一个[xxx]只处理一次
            if (seenOriginals.contains(original)) continue;
            seenOriginals.add(original);

            // 生成英文参数名（如果是英文直接使用，中文则翻译）
            String paramName;
            String paramLabel = paramNameRaw;
            if (paramNameRaw.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                // 已经是英文参数名，直接使用
                paramName = paramNameRaw;
            } else {
                paramName = chineseToParamName(paramNameRaw);
            }

            // 确保参数名唯一
            String baseName = paramName;
            int suffix = 1;
            while (usedNames.contains(paramName)) {
                paramName = baseName + "_" + suffix++;
            }
            usedNames.add(paramName);

            // 推断类型
            String paramType = inferParamType(paramNameRaw);

            params.add(new ParamInfo(paramName, paramLabel, paramType, original));
        }

        // 执行替换（按原始文本长度倒序，避免替换冲突）
        params.sort((a, b) -> b.original.length() - a.original.length());

        for (ParamInfo param : params) {
            String replacement = ":" + param.name;

            // 替换 TO_CHAR('[xxx]', ...) 模式 → :xxx（忽略大小写）
            String toCharPattern = "(?i)TO_CHAR\\(\\s*'" + Pattern.quote(param.original) + "'\\s*,\\s*'[^']*'\\s*\\)";
            sql = sql.replaceAll(toCharPattern, replacement);

            // 替换 TO_DATE('[xxx]', ...) 模式 → :xxx（忽略大小写）
            String toDatePattern = "(?i)TO_DATE\\(\\s*'" + Pattern.quote(param.original) + "'\\s*,\\s*'[^']*'\\s*\\)";
            sql = sql.replaceAll(toDatePattern, replacement);

            // 替换 '[xxx]' → :xxx（单引号包裹的参数）
            sql = sql.replace("'" + param.original + "'", replacement);

            // 替换裸 [xxx] → :xxx（无引号的参数）
            sql = sql.replace(param.original, replacement);
        }

        // 二次清理：移除残留的 to_char/to_date 包装（如 to_char(:param, 'fmt') → :param）
        sql = sql.replaceAll("(?i)TO_CHAR\\(\\s*(:[a-zA-Z_][a-zA-Z0-9_]*)\\s*,\\s*'[^']*'\\s*\\)", "$1");
        sql = sql.replaceAll("(?i)TO_DATE\\(\\s*(:[a-zA-Z_][a-zA-Z0-9_]*)\\s*,\\s*'[^']*'\\s*\\)", "$1");

        // 检测已有的 :param 格式参数（用户已使用冒号风格的）—— 在去注释版本中检测
        Pattern colonPattern = Pattern.compile(":([a-zA-Z_\\u4e00-\\u9fa5][a-zA-Z0-9_\\u4e00-\\u9fa5]*)");
        Matcher colonMatcher = colonPattern.matcher(sqlWithoutComments);
        while (colonMatcher.find()) {
            String existingParam = colonMatcher.group(1);
            // 跳过Oracle绑定变量常量和已处理的
            if (usedNames.contains(existingParam)) continue;
            if (existingParam.matches("\\d+")) continue; // 跳过纯数字

            // 如果是中文参数名，也需要转换
            if (existingParam.matches(".*[\\u4e00-\\u9fa5].*")) {
                String newName = chineseToParamName(existingParam);
                if (!usedNames.contains(newName)) {
                    usedNames.add(newName);
                    String paramType = inferParamType(existingParam);
                    params.add(new ParamInfo(newName, existingParam, paramType, ":" + existingParam));
                    sql = sql.replace(":" + existingParam, ":" + newName);
                }
            }
        }

        // 清理多余空行
        sql = sql.replaceAll("\n{3,}", "\n\n").trim();

        return new ConvertedSql(sql, params);
    }

    /**
     * 修正全角字符为半角（从网页/富文本复制SQL时常出现）
     * 只修正括号、逗号、分号、冒号、引号等SQL标点，不动中文字符
     */
    private String normalizeFullwidthChars(String sql) {
        if (sql == null) return null;
        StringBuilder sb = new StringBuilder(sql.length());
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            switch (c) {
                case '（': sb.append('('); break;  // （
                case '）': sb.append(')'); break;  // ）
                case '，': sb.append(','); break;  // ，
                case '；': sb.append(';'); break;  // ；
                case '：': sb.append(':'); break;  // ：
                case '＝': sb.append('='); break;  // ＝
                case '！': sb.append('!'); break;  // ！
                case '＜': sb.append('<'); break;  // ＜
                case '＞': sb.append('>'); break;  // ＞
                case '「': sb.append('\''); break; // 「
                case '」': sb.append('\''); break; // 」
                case '‘': sb.append('\''); break; // '
                case '’': sb.append('\''); break; // '
                case '“': sb.append('"'); break;  // "
                case '”': sb.append('"'); break;  // "
                case '、': sb.append(','); break;  // 、
                case '．': sb.append('.'); break;  // ．
                case '～': sb.append('~'); break;  // ～
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 剥离SQL注释（保留字符串常量不动）
     * 支持：-- 单行注释、/\* *\/ 块注释、'...' 字符串常量
     */
    private String stripSqlComments(String sql) {
        // 状态机逐字符处理，避免误删字符串中的内容
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        int len = sql.length();
        while (i < len) {
            // 跳过单行注释 --
            if (i + 1 < len && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < len && sql.charAt(i) != '\n') i++;
                // 保留换行符以维持行结构
                if (i < len) { sb.append('\n'); i++; }
                continue;
            }
            // 跳过块注释 /\* ... \*/
            if (i + 1 < len && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                if (i + 1 < len) i += 2; // 跳过 \*/
                sb.append(' '); // 用空格替代注释，避免粘连
                continue;
            }
            // 跳过单引号字符串 '...'（支持 '' 转义）
            if (sql.charAt(i) == '\'') {
                sb.append(sql.charAt(i)); i++;
                while (i < len) {
                    sb.append(sql.charAt(i));
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                            i++; sb.append(sql.charAt(i)); // '' 转义
                        } else {
                            break; // 字符串结束
                        }
                    }
                    i++;
                }
                i++;
                continue;
            }
            sb.append(sql.charAt(i));
            i++;
        }
        return sb.toString();
    }

    /**
     * 中文参数名转英文参数名
     */
    private String chineseToParamName(String chinese) {
        // 常见映射
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("起始日期", "start_date");
        mappings.put("结束日期", "end_date");
        mappings.put("开始日期", "start_date");
        mappings.put("截止日期", "end_date");
        mappings.put("上期起始日期", "prev_start_date");
        mappings.put("上期结束日期", "prev_end_date");
        mappings.put("起始时间", "start_time");
        mappings.put("结束时间", "end_time");
        mappings.put("年度", "year");
        mappings.put("月份", "month");
        mappings.put("年份", "year");
        mappings.put("社区", "community");
        mappings.put("社区代码", "community");
        mappings.put("科室", "dept");
        mappings.put("科室代码", "dept_code");
        mappings.put("科室编码", "dept_code");
        mappings.put("科室名称", "dept_name");
        mappings.put("医生", "doctor");
        mappings.put("医生姓名", "doctor_name");
        mappings.put("姓名", "name");
        mappings.put("人员姓名", "person_name");
        mappings.put("身份证号", "id_card");
        mappings.put("身份证", "id_card");
        mappings.put("人员类别", "staff_category");
        mappings.put("职工居民类别", "staff_category");
        mappings.put("门诊住院类型", "visit_type");
        mappings.put("医疗机构", "institution");
        mappings.put("医疗机构名称", "institution_name");
        mappings.put("状态", "status");
        mappings.put("类型", "type");
        mappings.put("药品", "drug");
        mappings.put("药品名称", "drug_name");
        mappings.put("开始时间", "start_time");
        mappings.put("截止时间", "end_time");
        mappings.put("查询日期", "query_date");
        mappings.put("统计日期", "stat_date");
        mappings.put("操作员", "operator");
        mappings.put("结算操作员", "operator");
        mappings.put("住院号", "admission_no");
        mappings.put("门诊号", "outpatient_no");

        // 精确匹配
        if (mappings.containsKey(chinese)) return mappings.get(chinese);

        // 模糊匹配
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            if (chinese.contains(entry.getKey())) return entry.getValue();
        }

        // 兜底：拼音首字母或p+序号
        StringBuilder sb = new StringBuilder("p");
        for (char c : chinese.toCharArray()) {
            if (c >= '0' && c <= '9') sb.append(c);
            else if (c >= 'a' && c <= 'z') sb.append(c);
            else if (c >= 'A' && c <= 'Z') sb.append(Character.toLowerCase(c));
        }
        String fallback = sb.toString();
        if (fallback.equals("p") || fallback.length() < 3) fallback = "param_" + Math.abs(chinese.hashCode() % 10000);
        return fallback;
    }

    /**
     * 根据参数名推断类型
     */
    private String inferParamType(String name) {
        if (name.contains("日期") || name.contains("date") || name.contains("Date")) return "date";
        if (name.contains("时间") || name.contains("time") || name.contains("Time")) return "text";
        if (name.contains("年度") || name.contains("年份") || name.contains("year")) return "text";
        if (name.contains("月份") || name.contains("month")) return "select";
        if (name.contains("类型") || name.contains("类别") || name.contains("状态")) return "select";
        return "text";
    }
}
