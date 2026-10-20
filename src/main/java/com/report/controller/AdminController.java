package com.report.controller;

import com.report.model.ReportInfo;
import com.report.service.GroupPermissionService;
import com.report.service.ReportService;
import com.report.service.SqlImportService;
import com.report.service.UserManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final UserManagementService userManagementService;
    private final GroupPermissionService groupPermissionService;
    private final ReportService reportService;
    private final com.report.service.DataSourceService dataSourceService;
    private final SqlImportService sqlImportService;
    private final com.report.service.PasswordResetEffects passwordResetEffects;
    private final com.report.service.ScreenService screenService;
    private final com.report.service.AuditService auditService;

    @Value("${report.scripts-dir:scripts}")
    private String scriptsDir;

    // 统一日志文件路径（与 application.yml 的 logging.file.name 保持一致）
    @Value("${logging.file.name:./logs/sql-report-server.log}")
    private String logFile;

    public AdminController(UserManagementService userManagementService,
                           GroupPermissionService groupPermissionService,
                           ReportService reportService,
                           com.report.service.DataSourceService dataSourceService,
                           SqlImportService sqlImportService,
                           com.report.service.PasswordResetEffects passwordResetEffects,
                           com.report.service.ScreenService screenService,
                           com.report.service.AuditService auditService) {
        this.userManagementService = userManagementService;
        this.groupPermissionService = groupPermissionService;
        this.reportService = reportService;
        this.dataSourceService = dataSourceService;
        this.sqlImportService = sqlImportService;
        this.passwordResetEffects = passwordResetEffects;
        this.screenService = screenService;
        this.auditService = auditService;
    }

    /**
     * 重置用户口令为**初始口令**（默认 666999，可用配置项改），并置 mustChangePassword 强制下次登录修改。
     *
     * <p>注意：这里原本写「随机临时密码」，与实际不符：{@code resetToTemporaryPassword}
     * 用的是配置项 app.security.initial-password 的值（默认 666999）。
     * 前端文案里"自动生成临时密码"那句也已按实际改掉 —— 默认值是共享的既知口令，
     * 把它说成"自动生成的临时密码"会让人误以为它不可猜。
     *
     * <p>重置之后还要 {@code clearLock}（用户常是「连错几次被锁 + 忘密」
     * 才来找管理员，不解锁则重置报成功而用户仍登不进去）与吊销该用户已登录会话
     * （重置动机往往就是口令泄露，而会话 30 分钟才自然过期）。
     */
    @PutMapping("/users/{username}/reset-password")
    @PreAuthorize("@perm.has('users.manage')")
    public ResponseEntity<?> resetToDefault(@PathVariable String username,
                                            @AuthenticationPrincipal UserDetails currentUser,
                                            jakarta.servlet.http.HttpServletRequest request) {
        if (!userManagementService.userExists(username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在"));
        }
        try {
            String tempPassword = userManagementService.resetToTemporaryPassword(username);
            passwordResetEffects.afterPasswordReset(username, request);
            log.info("[AUDIT] action=resetPassword, operator={}, target={}", currentUser.getUsername(), username);
            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("status", "ok");
            resp.put("message", "密码已重置为初始密码 " + tempPassword + "，用户下次登录须修改");
            resp.put("tempPassword", tempPassword);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("重置密码失败: {}", username, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "重置失败"));
        }
    }

    @GetMapping("/logs")
    @PreAuthorize("@perm.has('logs.view')")
    public ResponseEntity<?> viewLogs(@RequestParam(defaultValue = "200") int lines,
                                      @RequestParam(defaultValue = "all") String category) {
        try {
            Path logPath = Paths.get(logFile);
            if (!Files.exists(logPath)) {
                return ResponseEntity.ok(Map.of("content", "日志文件不存在", "lines", List.of(),
                    "stats", Map.of("total", 0, "audit", 0, "error", 0, "warn", 0, "security", 0)));
            }

            int maxLines = Math.min(lines, 2000);
            // 从文件尾部反向读取，避免将整个日志文件加载到内存
            java.util.List<String> tailLines = readTailLines(logPath, maxLines);

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
                "totalLines", String.valueOf(tailLines.size()),
                "stats", stats
            ));
        } catch (IOException e) {
            log.error("读取日志失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "读取日志失败: " + e.getMessage()));
        }
    }

    // ============ 报表删除/隐藏/编辑SQL ============

    /**
     * 根据报表ID解析完整文件路径
     * report.getFile()只返回文件名，需要拼接scriptsDir
     */
    /**
     * 生成报表文件的 YAML front matter。
     *
     * <p>必须走 {@link Yaml#dump} 而不是字符串拼接：早先直接 {@code append(name)}，
     * 报表名里塞一个换行即可注入 {@code dataSourceId:} 或 {@code source: "sql:SELECT ..."}
     * ，即由一个 report.manage 账号向所有查看者的执行路径里植入自定义数据源与 SQL。
     */
    private static String buildReportFrontMatter(String name, String description,
                                                 List<SqlImportService.ParamInfo> params) {
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("name", name == null ? "" : name.trim());
        if (description != null && !description.isEmpty()) {
            head.put("description", description.trim());
        }
        List<Map<String, Object>> paramList = new ArrayList<>();
        if (params != null) {
            for (SqlImportService.ParamInfo param : params) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", param.name);
                p.put("label", param.label);
                p.put("type", param.type);
                if ("date".equals(param.type)) {
                    p.put("required", true);
                } else {
                    p.put("placeholder", "%");
                    p.put("default", "%");
                }
                paramList.add(p);
            }
        }
        head.put("params", paramList);

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setSplitLines(false);
        return new Yaml(opts).dump(head);
    }

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
    @PreAuthorize("@perm.has('report.manage')")
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
    @PreAuthorize("@perm.has('report.manage')")
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
    @PreAuthorize("@perm.has('report.manage')")
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
    @PreAuthorize("@perm.has('report.manage')")
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
        // 保存前用与执行路径一致的校验校验 SQL，给管理员即时反馈（与运行时校验同源，不会误拦现有脚本）
        String sqlText = com.report.service.SqlUtils.extractSql(content);
        String saveErr = com.report.service.SqlUtils.validateSelectOnly(sqlText);
        if (saveErr != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "SQL校验失败: " + saveErr));
        }
        // 落盘路径必须与 /sql/preview、/sql/test 同严：早先只有那两个接口调
        //   validateExtraDangerous，结果是同一段文本在「测试」被拒、在「保存」被收下，
        //   落盘后由任意 reports.view 用户执行。
        String saveExtraErr = com.report.service.SqlUtils.validateExtraDangerous(sqlText);
        if (saveExtraErr != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "SQL包含被禁关键字: " + saveExtraErr));
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
            // 写入新内容（临时文件 + fsync + 原子 move；直写 TRUNCATE_EXISTING 在断电下会留下半份报表）
            com.report.util.AtomicFileWriter.writeString(filePath, content);
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

    // ============ 大屏指标（可视化平台）SQL ============

    /**
     * 大屏指标清单：口径与刷新间隔都写在 {@code scripts/screen/*.sql} 的 YAML 头 + SQL 里，
     * 这里把它摊到「报表管理」页上，并附带默认区间下最近一次真实取数的状态。
     */
    @GetMapping("/screen/metrics")
    @PreAuthorize("@perm.has('report.manage')")
    public ResponseEntity<?> listScreenMetrics() {
        return ResponseEntity.ok(Map.of("status", "ok", "metrics", screenService.listMetricsForAdmin()));
    }

    @GetMapping("/screen/metrics/{metricId}/sql")
    @PreAuthorize("@perm.has('report.manage')")
    public ResponseEntity<?> getScreenMetricSql(@PathVariable String metricId) {
        try {
            String content = screenService.readMetricContent(metricId);
            if (content == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "大屏指标不存在"));
            }
            return ResponseEntity.ok(Map.of("status", "ok", "metricId", metricId, "content", content));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "读取失败: " + e.getMessage()));
        }
    }

    /**
     * 保存大屏指标 SQL。校验与落盘都在 {@code ScreenService#saveMetricContent} ，
     * 那是唯一能写 {@code scripts/screen} 的地方，把关设在写文件的那一步，
     * 以后再多一个入口也不会漏。这里只做异常到 HTTP 的映射与审计。
     */
    @PutMapping("/screen/metrics/{metricId}/sql")
    @PreAuthorize("@perm.has('report.manage')")
    public ResponseEntity<?> updateScreenMetricSql(@PathVariable String metricId,
                                                    @RequestBody Map<String, Object> body) {
        try {
            screenService.saveMetricContent(metricId, asString(body.get("content")),
                asTitleMap(body.get("panelTitles")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("保存大屏指标SQL失败: {}", metricId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "保存失败: " + e.getMessage()));
        }
        // 只记标识、不记正文：SQL 全文动辄上百行，审计台账不是 SQL 仓库
        auditService.audit("updateScreenMetricSql", "metric", metricId);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "SQL已保存，指标定义已重新加载"));
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 请求体里的 {@code panelTitles} → 服务层要的形状。
     * 键缺失 = 不改标题（返回 null），空对象 = 清除自定义标题，这两件事必须分得开，
     * 否则「只改 SQL 没动标题」的保存会把标题一起抹掉。
     */
    private static Map<String, String> asTitleMap(Object raw) {
        if (raw == null) return null;
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
        }
        return out;
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
    @PreAuthorize("@perm.has('report.manage')")
    public ResponseEntity<?> importSql(@RequestBody Map<String, String> body,
                                       @AuthenticationPrincipal UserDetails currentUser) {
        String name = body.get("name");
        String description = body.getOrDefault("description", "");
        String rawSql = body.get("sql");
        String groupId = body.getOrDefault("groupId", "default");

        // 自动修正全角字符
        if (rawSql != null) rawSql = sqlImportService.normalizeFullwidthChars(rawSql);

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
            // 脚本预处理（兼容数据库客户端可直接运行的脚本：分号/斜杠/COMMIT/多语句等）
            SqlImportService.PreprocessResult pre = sqlImportService.preprocessScript(rawSql);
            if (pre.error != null) {
                return ResponseEntity.badRequest().body(Map.of("error", pre.error, "notes", pre.notes));
            }

            // 自动检测和转换参数
            SqlImportService.ConvertedSql result = sqlImportService.convertSqlParameters(pre.sql);

            // 保存前校验转换后的 SQL（与执行路径一致），拦截非 SELECT 或危险语句
            String saveErr = com.report.service.SqlUtils.validateSelectOnly(result.convertedSql);
            if (saveErr != null) {
                return ResponseEntity.badRequest().body(Map.of("error", "SQL校验失败: " + saveErr));
            }
            // 与 /sql/preview、/sql/test 同严：这条是真正落盘的写路径
            String saveExtraErr = com.report.service.SqlUtils.validateExtraDangerous(result.convertedSql);
            if (saveExtraErr != null) {
                return ResponseEntity.badRequest().body(Map.of("error", "SQL包含被禁关键字: " + saveExtraErr));
            }

            // 生成YAML front matter
            String fileContent = "---\n"
                + buildReportFrontMatter(name, description, result.params) + "---\n\n"
                + result.convertedSql;

            // 确保目录存在
            Path parent = filePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            com.report.util.AtomicFileWriter.writeStringNew(filePath, fileContent);

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
    @PreAuthorize("@perm.has('report.manage')")
    public ResponseEntity<?> previewSql(@RequestBody Map<String, String> body) {
        String rawSql = body.get("sql");
        if (rawSql == null || rawSql.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "SQL内容不能为空"));
        }
        String name = body.getOrDefault("name", "未命名报表");
        String desc = body.getOrDefault("description", "");

        // 1. 脚本预处理：兼容数据库客户端可直接运行的脚本
        //    （末尾分号/斜杠、COMMIT等辅助命令、PL/SQL块、多语句、全角字符）
        SqlImportService.PreprocessResult pre = sqlImportService.preprocessScript(rawSql);
        if (pre.error != null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", pre.error,
                "notes", pre.notes));
        }

        // 2. 参数自动转换（[参数]/:参数/&变量/硬编码日期）
        SqlImportService.ConvertedSql result = sqlImportService.convertSqlParameters(pre.sql);

        // 3. 校验前置：转换结果必须能通过与执行/保存一致的只读校验
        String vErr = com.report.service.SqlUtils.validateSelectOnly(result.convertedSql);
        if (vErr != null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "SQL校验失败: " + vErr,
                "notes", pre.notes,
                "convertedSql", result.convertedSql));
        }
        String vExtra = com.report.service.SqlUtils.validateExtraDangerous(result.convertedSql);
        if (vExtra != null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "SQL包含被禁关键字: " + vExtra,
                "notes", pre.notes,
                "convertedSql", result.convertedSql));
        }

        // 4. 性能类警告
        List<String> warnings = sqlImportService.analyzeWarnings(result.convertedSql);

        // 5. 生成预览 YAML（community 参数为 select，选项由系统层按社区字典+用户权限生成）
        StringBuilder yaml = new StringBuilder();
        yaml.append("---\n");
        yaml.append("name: ").append(name).append("\n");
        if (!desc.isEmpty()) yaml.append("description: ").append(desc).append("\n");
        yaml.append("params:\n");
        for (SqlImportService.ParamInfo param : result.params) {
            yaml.append("  - name: ").append(param.name).append("\n");
            yaml.append("    label: ").append(param.label).append("\n");
            yaml.append("    type: ").append(param.type).append("\n");
            if ("date".equals(param.type)) {
                yaml.append("    required: true\n");
            } else if ("community".equals(param.name)) {
                // 社区参数：选项由系统层按社区字典+当前用户数据权限动态生成
                yaml.append("    type: select\n");
                yaml.append("    source: \"list:%\"\n");
                yaml.append("    default: \"%\"\n");
                yaml.append("    placeholder: \"%\"\n");
            } else {
                yaml.append("    placeholder: \"%\"\n");
                yaml.append("    default: \"%\"\n");
            }
        }
        yaml.append("---\n\n");

        List<String> allNotes = new java.util.ArrayList<>(pre.notes);
        allNotes.addAll(result.notes);

        return ResponseEntity.ok(Map.of(
            "status", "ok",
            "yaml", yaml.toString(),
            "convertedSql", result.convertedSql,
            "notes", allNotes,
            "warnings", warnings,
            "params", result.params.stream().map(p -> {
                Map<String, String> m = new java.util.LinkedHashMap<>();
                m.put("name", p.name);
                m.put("label", p.label);
                m.put("type", p.type);
                m.put("original", p.original);
                return m;
            }).collect(Collectors.toList())
        ));
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
    @PreAuthorize("@perm.has('report.manage')")
    public ResponseEntity<?> testSql(@RequestBody Map<String, Object> body,
                                     @AuthenticationPrincipal UserDetails currentUser) {
        String sql = (String) body.get("sql");
        if (sql == null || sql.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "SQL内容不能为空"));
        }

        // 自动修正全角字符（从网页/富文本复制SQL时常出现）
        sql = sqlImportService.normalizeFullwidthChars(sql);

        // 剥离注释（避免注释中的内容影响SQL执行，同时暴露隐藏的危险操作）
        sql = com.report.service.SqlUtils.stripComments(sql);

        // 统一安全校验：基础校验与报表执行路径一致；额外关键字仅在此处收紧
        String vErr = com.report.service.SqlUtils.validateSelectOnly(sql);
        if (vErr != null) {
            log.warn("[SECURITY] SQL校验失败: operator={}, reason={}", currentUser.getUsername(), vErr);
            return ResponseEntity.badRequest().body(Map.of("error", vErr));
        }
        String vExtra = com.report.service.SqlUtils.validateExtraDangerous(sql);
        if (vExtra != null) {
            log.warn("[SECURITY] SQL包含被禁关键字: operator={}, reason={}", currentUser.getUsername(), vExtra);
            return ResponseEntity.badRequest().body(Map.of("error", vExtra));
        }

        @SuppressWarnings("unchecked")
        Map<String, String> paramValues = body.get("params") instanceof Map
            ? new HashMap<>((Map<String, String>) body.get("params"))
            : new HashMap<>();

        // 自动填充默认参数值（前端未提供的参数自动补上默认值）
        String today = java.time.LocalDate.now().toString(); // yyyy-MM-dd

        // 按出现顺序提取 :param（含重复出现），用于 ? 绑定
        List<String> paramNamesInOrder = com.report.service.SqlUtils.extractParamNamesInOrder(sql);
        Set<String> uniqueParamNames = new LinkedHashSet<>(paramNamesInOrder);

        // 获取前端传来的参数类型信息（如果有），用于智能填充
        @SuppressWarnings("unchecked")
        Map<String, String> paramTypes = body.get("paramTypes") instanceof Map
            ? (Map<String, String>) body.get("paramTypes")
            : Collections.emptyMap();

        // 对未提供值的参数，根据名称/类型自动填充默认值
        Map<String, String> bindings = new HashMap<>(paramValues);
        for (String paramName : uniqueParamNames) {
            if (bindings.containsKey(paramName) && bindings.get(paramName) != null
                && !bindings.get(paramName).isEmpty()) {
                continue;
            }
            String lowerName = paramName.toLowerCase();
            if (lowerName.contains("date") || lowerName.contains("日期")
                || "date".equalsIgnoreCase(paramTypes.get(paramName))) {
                bindings.put(paramName, today);
            } else if (lowerName.contains("month") || lowerName.contains("月份")) {
                bindings.put(paramName, java.time.LocalDate.now().getMonthValue() < 10
                    ? "0" + java.time.LocalDate.now().getMonthValue()
                    : String.valueOf(java.time.LocalDate.now().getMonthValue()));
            } else if (lowerName.contains("year") || lowerName.contains("年度") || lowerName.contains("年份")) {
                bindings.put(paramName, String.valueOf(java.time.LocalDate.now().getYear()));
            } else {
                bindings.put(paramName, "%");
            }
        }

        String trimmedSql = sql.trim().replaceAll(";\\s*$", "");

        // 可选按 body.dataSourceId 选库；指定 id 不存在时直接失败
        String dsId = body.get("dataSourceId") != null ? body.get("dataSourceId").toString() : null;
        javax.sql.DataSource dsToUse;
        try {
            dsToUse = (dsId != null && !dsId.isBlank())
                ? dataSourceService.getDataSource(dsId.trim())
                : dataSourceService.getDataSource(null);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        long startTime = System.currentTimeMillis();
        Connection conn = null;
        try {
            conn = dsToUse.getConnection();
            conn.setReadOnly(true);
            conn.setAutoCommit(false);

            try (PreparedStatement ps = com.report.service.SqlUtils.prepareNamed(
                    conn, trimmedSql, bindings, 10, 100)) {
                ps.setMaxRows(10);

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
                        currentUser.getUsername(), uniqueParamNames.size(), rows.size(), duration);

                    Map<String, Object> resp = new java.util.LinkedHashMap<>();
                    resp.put("status", "ok");
                    resp.put("columns", columns);
                    resp.put("rows", rows);
                    resp.put("rowCount", rows.size());
                    resp.put("duration", duration + "ms");
                    resp.put("params", new ArrayList<>(uniqueParamNames));
                    return ResponseEntity.ok(resp);
                }
            }
        } catch (SQLException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.warn("[AUDIT] action=testSqlFailed, operator={}, error={}, duration={}ms",
                currentUser.getUsername(), e.getMessage(), duration);
            return ResponseEntity.badRequest().body(Map.of(
                "error", "SQL执行失败: " + e.getMessage(),
                "duration", duration + "ms"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "验证查询异常: " + e.getMessage()));
        } finally {
            if (conn != null) {
                try { conn.rollback(); } catch (Exception ignore) { /* pool will reset */ }
                try { conn.setAutoCommit(true); } catch (Exception ignore) { /* ignore */ }
                try { conn.setReadOnly(false); } catch (Exception ignore) { /* ignore */ }
                try { conn.close(); } catch (Exception ignore) { /* ignore */ }
            }
        }
    }

    /**
     * 兼容旧前端路径：POST /api/admin/reports/import → 同 importSql
     */
    @PostMapping("/reports/import")
    @PreAuthorize("@perm.has('report.manage')")
    public ResponseEntity<?> importSqlCompat(@RequestBody Map<String, String> body,
                                             @AuthenticationPrincipal UserDetails currentUser) {
        return importSql(body, currentUser);
    }

    // ============ 日志导出 ============

    /**
     * 导出过滤后的日志
     * @param format 导出格式: txt 或 csv
     */
    @GetMapping("/logs/export")
    @PreAuthorize("@perm.has('logs.view')")
    public ResponseEntity<?> exportLogs(@RequestParam(defaultValue = "all") String category,
                                        @RequestParam(defaultValue = "txt") String format,
                                        @RequestParam(defaultValue = "2000") int lines) {
        try {
            Path logPath = Paths.get(logFile);
            if (!Files.exists(logPath)) {
                return ResponseEntity.badRequest().body(Map.of("error", "日志文件不存在"));
            }

            int maxLines = Math.min(lines, 5000);
            List<String> tailLines = readTailLines(logPath, maxLines);

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

    // ============ 工具方法 ============

    /**
     * 从文件尾部读取最后N行（UTF-8 安全，避免按字节把中文拆坏）
     */
    private java.util.List<String> readTailLines(Path path, int maxLines) throws IOException {
        java.util.List<String> result = new java.util.ArrayList<>();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path.toFile(), "r")) {
            long fileLen = raf.length();
            if (fileLen == 0 || maxLines <= 0) {
                return result;
            }

            // 从尾部多读一些字节，再按 UTF-8 解码后取最后 N 行
            long readSize = Math.min(fileLen, Math.max(64L * 1024, (long) maxLines * 512));
            long start = fileLen - readSize;
            // 若不是从文件头开始，向前多看最多 3 字节，尽量落在 UTF-8 字符边界
            if (start > 0) {
                raf.seek(start);
                int skip = 0;
                while (skip < 3 && start + skip < fileLen) {
                    int b = raf.read();
                    if (b < 0) break;
                    // 不是 continuation byte (10xxxxxx) 则认为可从该处解码
                    if ((b & 0xC0) != 0x80) break;
                    skip++;
                }
                start = start + skip;
                readSize = fileLen - start;
            }

            byte[] buf = new byte[(int) readSize];
            raf.seek(start);
            raf.readFully(buf);
            String chunk = new String(buf, StandardCharsets.UTF_8);
            // 去掉可能因边界产生的替换字符
            if (start > 0 && !chunk.isEmpty() && chunk.charAt(0) == '\uFFFD') {
                chunk = chunk.substring(1);
            }

            String[] lines = chunk.split("\\R", -1);
            int from = Math.max(0, lines.length - maxLines);
            // 若不是从文件头读起，第一行可能是半行，丢弃
            if (start > 0 && from == 0 && lines.length > 1) {
                from = 1;
            }
            for (int i = from; i < lines.length; i++) {
                // split 在文件以换行结尾时可能产生末尾空串
                if (i == lines.length - 1 && lines[i].isEmpty() && result.size() + (lines.length - from) > maxLines) {
                    continue;
                }
                result.add(lines[i]);
            }
            if (result.size() > maxLines) {
                result = new java.util.ArrayList<>(result.subList(result.size() - maxLines, result.size()));
            }
        }
        return result;
    }

}
