package com.report.service;

import com.report.model.ReportInfo;
import com.report.model.ReportInfo.ParamDef;
import com.report.model.ReportInfo.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final DataSource dataSource;

    @Value("${report.scripts-dir:scripts}")
    private String scriptsDir;

    @Value("${report.max-rows:50000}")
    private int maxRows;

    @Value("${report.max-sql-length:1000000}")
    private int maxSqlLength;

    @Value("${report.query-timeout:30}")
    private int queryTimeout;

    private final Map<String, ReportInfo> reportCache = new ConcurrentHashMap<>();

    // 信号量控制并发查询数，80用户场景设置为15较为合适
    private final Semaphore queryAllSemaphore = new Semaphore(15);

    // 共享的网络超时执行器（Bug #1修复：避免每次查询创建新ExecutorService导致线程泄漏）
    private final ExecutorService networkTimeoutExecutor = Executors.newFixedThreadPool(2);

    private static final Pattern BLOCKED = Pattern.compile(
        "\\b(DROP|DELETE|TRUNCATE|ALTER|CREATE|GRANT|REVOKE|INSERT|UPDATE|MERGE|EXEC|EXECUTE|CALL|DECLARE)\\b" +
        "|\\b(DBMS_|UTL_|CTXSYS)",
        Pattern.CASE_INSENSITIVE
    );

    // 匹配 :param_name（不在字符串字面量内）
    private static final Pattern PARAM_PATTERN = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");

    // 危险函数模式
    private static final Pattern DANGEROUS_FUNCTIONS = Pattern.compile(
        "\\b(UTL_HTTP|UTL_FILE|UTL_TCP|UTL_SMTP|UTL_MAIL|DBMS_JOB|DBMS_SCHEDULER|DBMS_PIPE|DBMS_ALERT|DBMS_LOCK|DBMS_RANDOM|DBMS_CRYPTO|DBMS_OBFUSCATION|DBMS_SQL|EXECUTE IMMEDIATE|OPEN_CURSOR)\\b",
        Pattern.CASE_INSENSITIVE
    );

    public ReportService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void init() {
        loadReports();
        log.info("报表系统启动，加载了 {} 个报表", reportCache.size());
    }

    @PreDestroy
    public void destroy() {
        networkTimeoutExecutor.shutdownNow();
        log.info("网络超时执行器已关闭");
    }

    public void loadReports() {
        reportCache.clear();
        Path dir = Paths.get(scriptsDir);
        if (!Files.exists(dir)) {
            try { Files.createDirectories(dir); } catch (IOException e) { log.error("创建脚本目录失败", e); }
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.sql")) {
            for (Path file : stream) {
                try {
                    ReportInfo report = parseSqlFile(file);
                    reportCache.put(report.getId(), report);
                } catch (Exception e) {
                    log.warn("解析报表文件失败: {}", file.getFileName(), e);
                }
            }
        } catch (IOException e) {
            log.error("扫描脚本目录失败", e);
        }
    }

    public List<ReportInfo> getAllReports() {
        return reportCache.values().stream()
            .sorted(Comparator.comparing(ReportInfo::getFile))
            .collect(Collectors.toList());
    }

    public ReportInfo getReport(String id) {
        return reportCache.get(id);
    }

    /**
     * 获取下拉框选项
     */
    public List<Map<String, String>> getOptions(String id, String paramName) {
        ReportInfo report = reportCache.get(id);
        if (report == null) return Collections.emptyList();

        ParamDef param = report.getParams().stream()
            .filter(p -> p.getName().equals(paramName))
            .findFirst().orElse(null);

        if (param == null || !"select".equals(param.getType())) return Collections.emptyList();

        String source = param.getSource();
        if (source == null) return Collections.emptyList();

        List<Map<String, String>> options = new ArrayList<>();

        if (source.toLowerCase().startsWith("sql:")) {
            String sql = source.substring(4).trim();
            // 安全校验：下拉选项SQL也必须是SELECT
            String validation = validateSql(sql);
            if (validation != null) {
                log.warn("下拉选项SQL不安全: {} - {}", paramName, validation);
                return Collections.emptyList();
            }
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(30);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> opt = new HashMap<>();
                        opt.put("value", rs.getString(1));
                        opt.put("label", rs.getMetaData().getColumnCount() > 1 ? rs.getString(2) : rs.getString(1));
                        options.add(opt);
                    }
                }
            } catch (SQLException e) {
                log.error("查询下拉选项失败: {}", paramName, e);
            }
        } else if (source.toLowerCase().startsWith("list:")) {
            String[] items = source.substring(5).split(",");
            for (String item : items) {
                Map<String, String> opt = new HashMap<>();
                opt.put("value", item.trim());
                opt.put("label", item.trim());
                options.add(opt);
            }
        }
        return options;
    }

    /**
     * 执行查询
     */
    public QueryResult query(String id, Map<String, String> paramValues, int page, int pageSize) {
        long startTime = System.currentTimeMillis();
        ReportInfo report = reportCache.get(id);
        if (report == null) {
            log.warn("报表不存在: {}", id);
            return errorResult("报表不存在");
        }

        String validation = validateSql(report.getSql());
        if (validation != null) {
            log.warn("SQL验证失败: {} - {}", id, validation);
            return errorResult(validation);
        }

        // 校验必填参数
        String missing = checkRequired(report, paramValues);
        if (missing != null) {
            log.warn("缺少必填参数: {} - {}", id, missing);
            return errorResult("缺少必填参数: " + missing);
        }

        pageSize = Math.min(Math.max(pageSize, 1), 500);
        page = Math.max(page, 1);
        int start = (page - 1) * pageSize + 1;
        int end = page * pageSize;

        Map<String, String> bindings = buildBindings(report, paramValues);

        // 分页SQL：使用ROWNUM分页（兼容复杂查询，不依赖ROWID）
        String pagedSql = "SELECT * FROM (SELECT tt.*, ROWNUM AS rn FROM (" +
            report.getSql() + ") tt WHERE ROWNUM <= :_p_end) WHERE rn >= :_p_start";
        String countSql = "SELECT COUNT(*) FROM (" + report.getSql() + ")";

        // 提取分页SQL中的参数名（按出现顺序，去重）
        List<String> pagedParams = extractParamNames(pagedSql);
        bindings.put("_p_start", String.valueOf(start));
        bindings.put("_p_end", String.valueOf(end));

        QueryResult result = new QueryResult();
        result.setPage(page);
        result.setPageSize(pageSize);

        try (Connection conn = dataSource.getConnection()) {
            // 查总数
            long total = 0;
            List<String> countParams = extractParamNames(countSql);
            try (PreparedStatement ps = buildPreparedStatement(conn, countSql, countParams, bindings);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) total = rs.getLong(1);
            }
            result.setTotal(total);

            // 查数据（带分页）
            try (PreparedStatement ps = buildPreparedStatement(conn, pagedSql, pagedParams, bindings);
                 ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    String colName = meta.getColumnLabel(i);
                    if (!"RN".equalsIgnoreCase(colName)) {
                        columns.add(colName);
                    }
                }
                result.setColumns(columns);

                List<List<String>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        String colName = meta.getColumnLabel(i);
                        if (!"RN".equalsIgnoreCase(colName)) {
                            Object val = rs.getObject(i);
                            row.add(val != null ? val.toString() : "");
                        }
                    }
                    rows.add(row);
                }
                result.setRows(rows);
            }
        } catch (SQLException e) {
            log.error("查询失败: {}", id, e);
            result.setError("查询失败: " + sanitizeError(e.getMessage()));
        }
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("[AUDIT] action=query, reportId={}, rowCount={}, duration={}ms", id, result.getRows() != null ? result.getRows().size() : 0, duration);
        
        return result;
    }

    /**
     * 查询全部数据（不分页，用于前端缓存和导出）
     * 合并了queryAll和exportAll的功能，消除代码重复
     */
    public QueryResult queryAll(String id, Map<String, String> paramValues, String clientIp) {
        return queryAllData(id, paramValues, clientIp, "queryAll");
    }

    /**
     * 导出全部数据（不分页）
     * 复用queryAll的逻辑
     */
    public QueryResult exportAll(String id, Map<String, String> paramValues, String clientIp) {
        return queryAllData(id, paramValues, clientIp, "exportAll");
    }

    /**
     * 查询全部数据的核心实现
     * @param action 操作类型，用于审计日志
     */
    private QueryResult queryAllData(String id, Map<String, String> paramValues, String clientIp, String action) {
        ReportInfo report = reportCache.get(id);
        if (report == null) {
            log.warn("[{}] 报表不存在: {}", action, id);
            return errorResult("报表不存在");
        }

        // 增强的SQL安全校验
        String validation = validateSql(report.getSql());
        if (validation != null) {
            log.warn("[{}] SQL验证失败: {} - {}", action, id, validation);
            return errorResult(validation);
        }

        // SQL长度校验
        if (report.getSql().length() > maxSqlLength) {
            log.warn("[{}] SQL长度超限: {} ({}字符)", action, id, report.getSql().length());
            return errorResult("SQL语句过长，请联系管理员优化");
        }

        String missing = checkRequired(report, paramValues);
        if (missing != null) {
            log.warn("[{}] 缺少必填参数: {} - {}", action, id, missing);
            return errorResult("缺少必填参数: " + missing);
        }

        // 使用tryAcquire带超时，避免无限等待
        boolean acquired = false;
        try {
            acquired = queryAllSemaphore.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[{}] 等待信号量被中断: {}", action, id);
        }

        if (!acquired) {
            log.warn("[{}] 系统繁忙，信号量获取失败: {}", action, id);
            return errorResult("系统繁忙，请稍后再试");
        }

        long startTime = System.currentTimeMillis();
        Map<String, String> bindings = buildBindings(report, paramValues);
        List<String> paramNames = extractParamNames(report.getSql());
        QueryResult result = new QueryResult();

        try (Connection conn = dataSource.getConnection()) {
            // 设置连接超时（使用共享执行器，避免线程泄漏）
            conn.setNetworkTimeout(networkTimeoutExecutor, queryTimeout * 1000);

            try (PreparedStatement ps = buildPreparedStatement(conn, report.getSql(), paramNames, bindings);
                 ResultSet rs = ps.executeQuery()) {

                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                result.setColumns(columns);

                List<List<String>> rows = new ArrayList<>();
                int count = 0;
                while (rs.next() && count < maxRows) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        Object val = rs.getObject(i);
                        row.add(val != null ? val.toString() : "");
                    }
                    rows.add(row);
                    count++;
                }
                result.setRows(rows);
                result.setTotal(count);
            }
        } catch (SQLException e) {
            log.error("[{}] 数据库查询失败: {}", action, id, e);
            result.setError("查询失败: " + sanitizeError(e.getMessage()));
        } catch (Exception e) {
            log.error("[{}] 查询异常: {}", action, id, e);
            result.setError("查询异常，请稍后再试");
        } finally {
            queryAllSemaphore.release();
        }

        long duration = System.currentTimeMillis() - startTime;
        int rowCount = result.getRows() != null ? result.getRows().size() : 0;

        // 审计日志 - 记录慢查询
        if (duration > 5000) {
            log.warn("[AUDIT] action={}, clientIp={}, reportId={}, rowCount={}, duration={}ms (慢查询)",
                    action, clientIp, id, rowCount, duration);
        } else {
            log.info("[AUDIT] action={}, clientIp={}, reportId={}, rowCount={}, duration={}ms",
                    action, clientIp, id, rowCount, duration);
        }

        return result;
    }

    // ============ 私有方法 ============

    /**
     * 解析SQL文件
     */
    private ReportInfo parseSqlFile(Path file) throws IOException {
        String content = Files.readString(file);
        String yamlText = "";
        String sqlText = content;

        Pattern yamlBlock = Pattern.compile("^\\s*---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL);
        Matcher m = yamlBlock.matcher(content);
        if (m.matches()) {
            yamlText = m.group(1);
            sqlText = m.group(2);
        } else {
            String[] lines = content.split("\\n");
            StringBuilder yml = new StringBuilder();
            StringBuilder sql = new StringBuilder();
            boolean inYaml = true;
            for (String line : lines) {
                if (inYaml && line.trim().startsWith("#")) {
                    yml.append(line.trim().substring(1).trim()).append("\n");
                } else if (inYaml && line.trim().isEmpty()) {
                    continue;
                } else {
                    inYaml = false;
                    sql.append(line).append("\n");
                }
            }
            yamlText = yml.toString();
            sqlText = sql.toString();
        }

        Yaml yaml = new Yaml();
        Map<String, Object> config = new HashMap<>();
        try {
            Object loaded = yaml.load(yamlText);
            if (loaded instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) loaded;
                config = map;
            }
        } catch (Exception e) {
            log.warn("YAML解析失败: {} - {}", file.getFileName(), e.getMessage());
        }

        ReportInfo report = new ReportInfo();
        String fileName = file.getFileName().toString();
        report.setId(fileName.replaceFirst("\\.sql$", ""));
        report.setName((String) config.getOrDefault("name", report.getId()));
        report.setDescription((String) config.getOrDefault("description", ""));
        report.setFile(fileName);
        report.setSql(stripComments(sqlText.trim()));

        List<ParamDef> params = new ArrayList<>();
        Object paramsObj = config.get("params");
        if (paramsObj instanceof List) {
            for (Object item : (List<?>) paramsObj) {
                if (item instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) item;
                    ParamDef p = new ParamDef();
                    p.setName((String) map.get("name"));
                    p.setLabel((String) map.get("label"));
                    p.setType((String) map.get("type"));
                    p.setRequired(Boolean.TRUE.equals(map.get("required")));
                    p.setDefaultValue(map.get("default") != null ? map.get("default").toString() : null);
                    p.setPlaceholder((String) map.get("placeholder"));
                    p.setSource((String) map.get("source"));
                    if (p.getName() != null) params.add(p);
                }
            }
        }
        report.setParams(params);
        return report;
    }

    /**
     * 从SQL中提取参数名（按出现顺序，去重，跳过字符串字面量内的）
     */
    private List<String> extractParamNames(String sql) {
        List<String> names = new ArrayList<>();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (c == '\'' && !inDoubleQuote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            if (inSingleQuote || inDoubleQuote) continue;

            if (c == ':' && i + 1 < sql.length()) {
                if (sql.charAt(i + 1) == ':' || sql.charAt(i + 1) == '=') continue;

                Matcher m = PARAM_PATTERN.matcher(sql.substring(i));
                if (m.lookingAt()) {
                    names.add(m.group(1));
                    i += m.end() - 1;
                }
            }
        }
        return names;
    }

    /**
     * 构建绑定参数
     */
    private Map<String, String> buildBindings(ReportInfo report, Map<String, String> paramValues) {
        Map<String, String> bindings = new HashMap<>();
        for (ParamDef p : report.getParams()) {
            String value = paramValues.getOrDefault(p.getName(), p.getDefaultValue());
            if (value != null && value.isEmpty()) value = null;
            bindings.put(p.getName(), value);
        }
        return bindings;
    }

    /**
     * 检查必填参数
     */
    private String checkRequired(ReportInfo report, Map<String, String> paramValues) {
        for (ParamDef p : report.getParams()) {
            if (p.isRequired()) {
                String value = paramValues.get(p.getName());
                if (value == null || value.trim().isEmpty()) {
                    value = p.getDefaultValue();
                }
                if (value == null || value.trim().isEmpty()) {
                    return p.getLabel();
                }
            }
        }
        return null;
    }

    /**
     * 构建PreparedStatement
     * 将SQL中的 :param_name 替换为 ?，并按顺序绑定参数
     */
    private PreparedStatement buildPreparedStatement(Connection conn, String sql,
            List<String> paramNames, Map<String, String> bindings) throws SQLException {

        // 将SQL中的 :param_name 替换为 ?（跳过字符串字面量内的）
        String processedSql = replaceParamsWithPlaceholders(sql);

        PreparedStatement ps = conn.prepareStatement(processedSql);
        ps.setFetchSize(5000);
        ps.setQueryTimeout(queryTimeout); // 使用配置的超时时间

        // 按参数名顺序绑定
        for (int i = 0; i < paramNames.size(); i++) {
            String name = paramNames.get(i);
            String value = bindings.get(name);
            if (value == null) {
                ps.setNull(i + 1, Types.VARCHAR);
            } else {
                ps.setString(i + 1, value);
            }
        }
        return ps;
    }

    /**
     * 将SQL中的 :param_name 替换为 ?（跳过字符串字面量内的）
     */
    private String replaceParamsWithPlaceholders(String sql) {
        StringBuilder result = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (c == '\'' && !inDoubleQuote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    result.append(c).append(sql.charAt(i + 1));
                    i++;
                    continue;
                }
                inSingleQuote = !inSingleQuote;
                result.append(c);
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                result.append(c);
                continue;
            }

            if (inSingleQuote || inDoubleQuote) {
                result.append(c);
                continue;
            }

            // 不在字符串内，检查是否是参数
            if (c == ':' && i + 1 < sql.length()) {
                if (sql.charAt(i + 1) == ':' || sql.charAt(i + 1) == '=') {
                    result.append(c);
                    continue;
                }
                Matcher m = PARAM_PATTERN.matcher(sql.substring(i));
                if (m.lookingAt()) {
                    result.append('?');
                    i += m.end() - 1;
                } else {
                    result.append(c);
                }
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * 增强的SQL安全校验
     * 防止SQL注入和危险操作
     */
    private String validateSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "SQL语句不能为空";
        }

        // 先去除注释再校验，防止注释中藏匿危险SQL绕过检测
        String cleanedSql = stripComments(sql);
        String upper = cleanedSql.toUpperCase().trim();

        // 1. 必须以SELECT或WITH开头
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            log.warn("SQL校验失败: 不以SELECT/WITH开头");
            return "只允许 SELECT 查询";
        }

        // 2. 检查黑名单关键字
        Matcher m = BLOCKED.matcher(upper);
        if (m.find()) {
            log.warn("SQL校验失败: 包含禁止关键字 '{}'", m.group());
            return "SQL包含禁止的操作: " + m.group();
        }

        // 3. 检查危险函数
        Matcher funcMatcher = DANGEROUS_FUNCTIONS.matcher(upper);
        if (funcMatcher.find()) {
            log.warn("SQL校验失败: 包含危险函数 '{}'", funcMatcher.group());
            return "SQL包含禁止的函数: " + funcMatcher.group();
        }

        // 4. 检查是否有多个SQL语句（分号分割）
        // 允许末尾有一个分号
        String withoutTrailingSemicolon = cleanedSql.trim().replaceAll(";\\s*$", "");
        if (withoutTrailingSemicolon.contains(";")) {
            log.warn("SQL校验失败: 包含多个SQL语句");
            return "不允许多条SQL语句";
        }

        // 5. 检查括号匹配（防止子查询注入）
        // Bug #16修复：跳过字符串字面量内的括号，避免误判
        int parenCount = 0;
        boolean inStr = false;
        for (int ci = 0; ci < cleanedSql.length(); ci++) {
            char c = cleanedSql.charAt(ci);
            if (c == '\'') {
                if (ci + 1 < cleanedSql.length() && cleanedSql.charAt(ci + 1) == '\'') {
                    ci++; // '' 转义
                    continue;
                }
                inStr = !inStr;
                continue;
            }
            if (inStr) continue;
            if (c == '(') parenCount++;
            if (c == ')') parenCount--;
            if (parenCount < 0) {
                log.warn("SQL校验失败: 括号不匹配");
                return "SQL语法错误：括号不匹配";
            }
        }
        if (parenCount != 0) {
            log.warn("SQL校验失败: 括号不匹配");
            return "SQL语法错误：括号不匹配";
        }

        return null;
    }

    /**
     * 剥离SQL注释（支持 -- 行注释和 块注释，保留字符串常量不动）
     * Bug #15修复：原实现只处理行注释，攻击者可通过块注释绕过安全校验
     */
    private String stripComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        int len = sql.length();
        while (i < len) {
            // 跳过单行注释 --
            if (i + 1 < len && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < len && sql.charAt(i) != '\n') i++;
                if (i < len) { sb.append('\n'); i++; }
                continue;
            }
            // 跳过块注释 /* ... */
            if (i + 1 < len && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                if (i + 1 < len) i += 2; // 跳过 */
                sb.append(' ');
                continue;
            }
            // 跳过单引号字符串 '...'（支持 '' 转义）
            if (sql.charAt(i) == '\'') {
                sb.append(sql.charAt(i)); i++;
                while (i < len) {
                    sb.append(sql.charAt(i));
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                            i++; sb.append(sql.charAt(i));
                        } else {
                            break;
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
        return sb.toString().trim();
    }

    /**
     * 错误信息脱敏
     * 防止泄露数据库结构等敏感信息
     */
    private String sanitizeError(String msg) {
        if (msg == null) return "未知错误";

        String upper = msg.toUpperCase();

        // 包含SQL关键字或Oracle错误码时，返回通用错误
        if (upper.contains("SELECT ") || upper.contains("FROM ") || upper.contains("WHERE ")
            || upper.contains("INSERT ") || upper.contains("UPDATE ") || upper.contains("DELETE ")
            || upper.contains("ORA-") || upper.contains("TABLE ") || upper.contains("COLUMN ")
            || upper.contains("INVALID") || upper.contains("MISSING")
            || upper.contains("EXCEPTION") || upper.contains("ERROR")) {
            log.debug("原始错误信息（已脱敏）: {}", msg);
            return "数据库查询错误，请联系管理员";
        }

        // 限制错误信息长度
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "...";
        }

        return msg;
    }

    private QueryResult errorResult(String msg) {
        QueryResult r = new QueryResult();
        r.setError(msg);
        return r;
    }
}
