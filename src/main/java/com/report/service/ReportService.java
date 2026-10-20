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

    /** {@code sql:} 型下拉选项的取数上限：超过即截断并告警（千条以上的下拉框人眼也无法用）。 */
    private static final int MAX_OPTIONS = 1000;

    /** 单个报表参数的取值长度上限（LIKE 模式串也受此约束）。 */
    private static final int MAX_PARAM_LENGTH = 200;

    private final DataSourceService dataSourceService;
    private final AuditService auditService;
    private final ExcelService excelService;

    @Value("${report.scripts-dir:scripts}")
    private String scriptsDir;

    @Value("${report.max-rows:50000}")
    private int maxRows;

    @Value("${report.max-sql-length:1000000}")
    private int maxSqlLength;

    @Value("${report.query-timeout:30}")
    private int queryTimeout;

    /** COUNT 缓存 TTL（秒），0 表示关闭缓存 */
    @Value("${report.count-cache-ttl-seconds:60}")
    private long countCacheTtlSeconds;

    /** 下拉选项 SQL 结果缓存 TTL（秒），0 表示关闭 */
    @Value("${report.options-cache-ttl-seconds:300}")
    private long optionsCacheTtlSeconds;

    /** 导出并发上限（独立信号量，避免与分页查询叠加打满连接池） */
    @Value("${report.export-concurrency:5}")
    private int exportConcurrency;

    /** 分页查询结果短缓存 TTL（秒），0 表示关闭（默认关闭，避免无谓内存占用） */
    @Value("${report.query-cache-ttl-seconds:0}")
    private long queryCacheTtlSeconds;

    /** 仅缓存行数不超过该值的结果，防止整表缓存撑爆堆内存 */
    @Value("${report.query-cache-max-rows:2000}")
    private int queryCacheMaxRows;

    private volatile Map<String, ReportInfo> reportCache = new ConcurrentHashMap<>();

    // 全量查询并发
    private final Semaphore queryAllSemaphore = new Semaphore(15);

    // 分页查询并发（防止打满连接池）
    private final Semaphore querySemaphore = new Semaphore(25);

    // 导出并发（独立于查询，导出长时间占连接，避免与查询叠加打满连接池）
    private volatile Semaphore exportSemaphore = new Semaphore(5);

    // 下拉选项查询信号量
    private final Semaphore optionsSemaphore = new Semaphore(10);

    private final ExecutorService networkTimeoutExecutor = Executors.newFixedThreadPool(2);

    /** COUNT 结果短缓存 */
    private final ConcurrentHashMap<String, CountCacheEntry> countCache = new ConcurrentHashMap<>();

    /** 下拉选项缓存：key = reportId|paramName */
    private final ConcurrentHashMap<String, OptionsCacheEntry> optionsCache = new ConcurrentHashMap<>();

    /** 分页查询结果短缓存：key = reportId|参数哈希 */
    private final ConcurrentHashMap<String, QueryCacheEntry> queryCache = new ConcurrentHashMap<>();

    private static final class CountCacheEntry {
        final long total;
        final long expireAt;
        CountCacheEntry(long total, long expireAt) {
            this.total = total;
            this.expireAt = expireAt;
        }
    }

    private static final class OptionsCacheEntry {
        final List<Map<String, String>> options;
        final long expireAt;
        OptionsCacheEntry(List<Map<String, String>> options, long expireAt) {
            this.options = options;
            this.expireAt = expireAt;
        }
    }

    private static final class QueryCacheEntry {
        final List<String> columns;
        final List<List<String>> rows;
        final long total;
        final long expireAt;
        QueryCacheEntry(List<String> columns, List<List<String>> rows, long total, long expireAt) {
            this.columns = columns;
            this.rows = rows;
            this.total = total;
            this.expireAt = expireAt;
        }
    }

    private final UserManagementService userManagementService;
    private final CommunityService communityService;

    public ReportService(DataSourceService dataSourceService,
                         AuditService auditService,
                         ExcelService excelService,
                         UserManagementService userManagementService,
                         CommunityService communityService) {
        this.dataSourceService = dataSourceService;
        this.auditService = auditService;
        this.excelService = excelService;
        this.userManagementService = userManagementService;
        this.communityService = communityService;
    }

    /**
     * 按报表绑定的 dataSourceId 解析连接池（统一 DataSourceService，无双池回退）。
     * 未配置 dataSourceId 用默认池；错误 id 直接抛错。
     */
    private DataSource resolveDataSource(ReportInfo report) {
        String dsId = report != null ? report.getDataSourceId() : null;
        if (dsId != null && !dsId.isBlank()) {
            return dataSourceService.getDataSource(dsId.trim());
        }
        return dataSourceService.getDataSource(null);
    }

    @PostConstruct
    public void init() {
        this.exportSemaphore = new Semaphore(Math.max(1, exportConcurrency));
        loadReports();
        log.info("报表系统启动，加载了 {} 个报表", reportCache.size());
    }

    @PreDestroy
    public void destroy() {
        networkTimeoutExecutor.shutdownNow();
        log.info("网络超时执行器已关闭");
    }

    public void loadReports() {
        Map<String, ReportInfo> newCache = new ConcurrentHashMap<>();
        Path dir = Paths.get(scriptsDir);
        if (!Files.exists(dir)) {
            try { Files.createDirectories(dir); } catch (IOException e) { log.error("创建脚本目录失败", e); }
            this.reportCache = newCache;
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.sql")) {
            for (Path file : stream) {
                try {
                    ReportInfo report = parseSqlFile(file);
                    newCache.put(report.getId(), report);
                } catch (Exception e) {
                    log.warn("解析报表文件失败: {}", file.getFileName(), e);
                }
            }
        } catch (IOException e) {
            log.error("扫描脚本目录失败", e);
        }
        this.reportCache = newCache;
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
        return getOptions(id, paramName, null);
    }

    /**
     * 下拉选项。社区参数（community）由系统层生成：社区字典启用项 ∩ 用户数据权限，
     * 不走 list:/sql: 分支，也不进全局缓存（选项因人而异）。
     * @param allowedCommunities 用户可查看社区，null/空=不限
     */
    public List<Map<String, String>> getOptions(String id, String paramName, List<String> allowedCommunities) {
        ReportInfo report = reportCache.get(id);
        if (report == null) return Collections.emptyList();

        ParamDef param = report.getParams().stream()
            .filter(p -> p.getName().equals(paramName))
            .findFirst().orElse(null);

        if (param == null || !"select".equals(param.getType())) return Collections.emptyList();

        // 社区参数：系统层动态生成（"全部"放最后，与原 source list 顺序习惯一致）
        if ("community".equals(paramName)) {
            List<Map<String, String>> options = new ArrayList<>();
            List<String> codes = new ArrayList<>(communityService.enabledCodes());
            if (allowedCommunities != null && !allowedCommunities.isEmpty()) {
                codes.retainAll(allowedCommunities);
            }
            java.util.Collections.sort(codes);
            for (String code : codes) {
                Map<String, String> opt = new HashMap<>();
                CommunityService.Community dict = communityService.getByCode(code);
                opt.put("value", code);
                opt.put("label", dict != null ? dict.name : code);
                options.add(opt);
            }
            Map<String, String> all = new HashMap<>();
            all.put("value", "%");
            all.put("label", "全部");
            options.add(all);
            return options;
        }

        String source = param.getSource();
        if (source == null) return Collections.emptyList();

        List<Map<String, String>> options = new ArrayList<>();

        if (source.toLowerCase().startsWith("sql:")) {
            String cacheKey = id + "|" + paramName;
            if (optionsCacheTtlSeconds > 0) {
                OptionsCacheEntry cached = optionsCache.get(cacheKey);
                if (cached != null && System.currentTimeMillis() <= cached.expireAt) {
                    return new ArrayList<>(cached.options);
                }
            }
            String sql = source.substring(4).trim();
            // 安全校验：下拉选项SQL也必须是SELECT
            String validation = validateSql(sql);
            if (validation != null) {
                log.warn("下拉选项SQL不安全: {} - {}", paramName, validation);
                return Collections.emptyList();
            }
            boolean acquired = false;
            try {
                acquired = optionsSemaphore.tryAcquire(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!acquired) {
                log.warn("下拉选项查询信号量获取失败: {}", paramName);
                return Collections.emptyList();
            }
            try (Connection conn = resolveDataSource(report).getConnection();
                 // 与主查询一致：:param → ?（无参数时等价于裸 prepare）
                 PreparedStatement ps = SqlUtils.prepareNamed(conn, sql, Collections.emptyMap(), 30, 200)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // 早先无行数上限：一条返回百万行的 sql: 源会把整份结果集读进内存并缓存，
                        // 而千条以上的下拉框人眼也无法用，截断并留下可定位的告警。
                        if (options.size() >= MAX_OPTIONS) {
                            log.warn("下拉选项超过 {} 条已截断: report={}, param={}",
                                MAX_OPTIONS, report.getId(), paramName);
                            break;
                        }
                        Map<String, String> opt = new HashMap<>();
                        opt.put("value", rs.getString(1));
                        opt.put("label", rs.getMetaData().getColumnCount() > 1 ? rs.getString(2) : rs.getString(1));
                        options.add(opt);
                    }
                }
                if (optionsCacheTtlSeconds > 0) {
                    optionsCache.put(cacheKey, new OptionsCacheEntry(
                        new ArrayList<>(options),
                        System.currentTimeMillis() + optionsCacheTtlSeconds * 1000L));
                    if (optionsCache.size() > 200) {
                        long now = System.currentTimeMillis();
                        optionsCache.entrySet().removeIf(e -> e.getValue().expireAt < now);
                    }
                }
            } catch (SQLException e) {
                log.error("查询下拉选项失败: {}", paramName, e);
            } catch (IllegalArgumentException e) {
                log.warn("下拉选项数据源错误: {} - {}", paramName, e.getMessage());
            } finally {
                optionsSemaphore.release();
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
        return query(id, paramValues, page, pageSize, null);
    }

    /**
     * 分页查询（带社区数据权限）。
     * @param allowedCommunities 用户可查看社区，null/空=不限；
     *                           受限用户选"全部"且权限内多个社区时，逐社区全量合并后内存分页
     */
    public QueryResult query(String id, Map<String, String> paramValues, int page, int pageSize,
                             List<String> allowedCommunities) {
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

        String missing = checkRequired(report, paramValues);
        if (missing != null) {
            log.warn("缺少必填参数: {} - {}", id, missing);
            return errorResult("缺少必填参数: " + missing);
        }

        // 社区数据权限：越权直接拒绝；单社区权限选"全部"会被强制替换为该社区；
        // 多社区选"全部"走逐社区合并（queryAllData 内已含审计）
        List<String> commScope;
        try {
            commScope = resolveCommunities(report, paramValues, allowedCommunities);
        } catch (IllegalArgumentException e) {
            log.warn("[query] 社区越权拦截: {} - {}", id, e.getMessage());
            auditService.audit("communityDenied", Map.of("report", id,
                "detail", e.getMessage() == null ? "" : e.getMessage()));
            return errorResult(e.getMessage());
        }
        if (commScope != null && commScope.size() > 1) {
            QueryResult full = queryAllData(id, paramValues, null, "query", allowedCommunities);
            if (full.getError() != null) return full;
            return paginateFromCache(full, page, pageSize);
        }

        boolean acquired = false;
        try {
            acquired = querySemaphore.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!acquired) {
            return errorResult("系统繁忙，请稍后再试");
        }
        /* 许可一到手就进 try：早先 clamp / buildBindings / bindingsHash 夹在
         *   tryAcquire 与 try 之间，那里一旦抛异常许可就永久漏掉，
         *   累计 25 次后所有分页查询从此只回「系统繁忙」且不再自愈。 */
        try {
            pageSize = Math.min(Math.max(pageSize, 1), 500);
            page = Math.max(page, 1);
            int start = (page - 1) * pageSize + 1;
            int end = page * pageSize;

            Map<String, String> bindings = buildBindings(report, paramValues);
            // 缓存键不含分页参数，否则每页都会失效
            String cacheKey = id + "|" + bindingsHash(bindings);

            QueryResult result;
            if (queryCacheTtlSeconds > 0) {
                result = queryWithCache(report, bindings, cacheKey, page, pageSize);
            } else {
                result = queryPagedDb(report, bindings, cacheKey, page, pageSize, start, end);
            }

            long duration = System.currentTimeMillis() - startTime;
            long rowCount = result.getRows() != null ? result.getRows().size() : 0;
            auditService.recordQuery("query", id, null, duration, rowCount, result.getError() == null);
            return result;
        } finally {
            querySemaphore.release();
        }
    }

    /** 原分页路径：COUNT + ROWNUM 分页查询。 */
    private QueryResult queryPagedDb(ReportInfo report, Map<String, String> bindings, String cacheKey,
                                     int page, int pageSize, int start, int end) {
        QueryResult result = new QueryResult();
        result.setPage(page);
        result.setPageSize(pageSize);

        String pagedSql = "SELECT * FROM (SELECT tt.*, ROWNUM AS rn FROM (" +
            report.getSql() + ") tt WHERE ROWNUM <= :_p_end) WHERE rn >= :_p_start";
        String countSql = "SELECT COUNT(*) FROM (" + report.getSql() + ")";

        Map<String, String> pageBindings = new HashMap<>(bindings);
        pageBindings.put("_p_start", String.valueOf(start));
        pageBindings.put("_p_end", String.valueOf(end));

        try (Connection conn = resolveDataSource(report).getConnection()) {
            long total = getCachedCount(cacheKey);
            if (total < 0) {
                total = 0;
                try (PreparedStatement ps = buildPreparedStatement(conn, countSql, bindings);
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) total = rs.getLong(1);
                }
                putCachedCount(cacheKey, total);
            }
            result.setTotal(total);

            try (PreparedStatement ps = buildPreparedStatement(conn, pagedSql, pageBindings);
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
        } catch (IllegalArgumentException e) {
            log.warn("查询数据源错误: {} - {}", report.getId(), e.getMessage());
            result.setError(e.getMessage());
        } catch (SQLException e) {
            log.error("查询失败: {}", report.getId(), e);
            result.setError("查询失败: " + sanitizeError(e.getMessage()));
        }
        return result;
    }

    /** 结果缓存路径：一次取全量（受 maxRows 限制），未截断才缓存，之后内存分页。 */
    private QueryResult queryWithCache(ReportInfo report, Map<String, String> bindings, String cacheKey,
                                       int page, int pageSize) {
        QueryResult cached = getCachedResult(cacheKey);
        if (cached != null) {
            return paginateFromCache(cached, page, pageSize);
        }

        try (Connection conn = resolveDataSource(report).getConnection()) {
            conn.setNetworkTimeout(networkTimeoutExecutor, queryTimeout * 1000);
            QueryResult full = fetchFullResult(conn, report, bindings);
            if (!full.isTruncated() && full.getRows().size() <= queryCacheMaxRows) {
                putCachedResult(cacheKey, full.getColumns(), full.getRows(), full.getTotal());
            }
            return paginateFromCache(full, page, pageSize);
        } catch (IllegalArgumentException e) {
            log.warn("查询数据源错误: {} - {}", report.getId(), e.getMessage());
            return errorResult(e.getMessage());
        } catch (SQLException e) {
            log.error("查询失败: {}", report.getId(), e);
            return errorResult("查询失败: " + sanitizeError(e.getMessage()));
        }
    }

    /** 读取全量结果（受 maxRows 上限），返回含列、行、截断标记的 QueryResult。 */
    private QueryResult fetchFullResult(Connection conn, ReportInfo report, Map<String, String> bindings) throws SQLException {
        QueryResult r = new QueryResult();
        try (PreparedStatement ps = buildPreparedStatement(conn, report.getSql(), bindings);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                columns.add(meta.getColumnLabel(i));
            }
            r.setColumns(columns);

            List<List<String>> rows = new ArrayList<>();
            int count = 0;
            boolean truncated = false;
            while (rs.next()) {
                if (count >= maxRows) {
                    truncated = true;
                    break;
                }
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    Object val = rs.getObject(i);
                    row.add(val != null ? val.toString() : "");
                }
                rows.add(row);
                count++;
            }
            r.setRows(rows);
            r.setTotal(count);
            r.setTruncated(truncated);
        }
        return r;
    }

    /** 从全量结果中取一页（内存分页）。 */
    private QueryResult paginateFromCache(QueryResult full, int page, int pageSize) {
        QueryResult r = new QueryResult();
        r.setColumns(full.getColumns());
        r.setTotal(full.getTotal());
        r.setTruncated(full.isTruncated());
        r.setPage(page);
        r.setPageSize(pageSize);
        List<List<String>> all = full.getRows() != null ? full.getRows() : List.of();
        int from = (page - 1) * pageSize;
        if (from >= all.size()) {
            r.setRows(new ArrayList<>());
        } else {
            int to = Math.min(all.size(), from + pageSize);
            r.setRows(new ArrayList<>(all.subList(from, to)));
        }
        return r;
    }

    private QueryResult getCachedResult(String key) {
        if (queryCacheTtlSeconds <= 0) return null;
        QueryCacheEntry e = queryCache.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() > e.expireAt) {
            queryCache.remove(key, e);
            return null;
        }
        QueryResult r = new QueryResult();
        r.setColumns(new ArrayList<>(e.columns));
        r.setRows(new ArrayList<>(e.rows));
        r.setTotal(e.total);
        return r;
    }

    private void putCachedResult(String key, List<String> columns, List<List<String>> rows, long total) {
        if (queryCacheTtlSeconds <= 0) return;
        queryCache.put(key, new QueryCacheEntry(
            new ArrayList<>(columns), new ArrayList<>(rows), total,
            System.currentTimeMillis() + queryCacheTtlSeconds * 1000L));
        if (queryCache.size() > 50) {
            long now = System.currentTimeMillis();
            queryCache.entrySet().removeIf(en -> en.getValue().expireAt < now);
        }
    }

    /**
     * 查询全部数据（不分页）：前端「全量」缓存与导出都走这里。
     *
     * <p>原来另有一个人手写的 {@code exportAll(...)} 转发到本方法，全仓零调用点，
     * 已删，导出的实际入口是 {@code ReportController} 的导出接口，它调的就是带社区
     * 权限的这个重载。留两个同名包装只会让人分不清导出到底走的哪条路。
     */
    public QueryResult queryAll(String id, Map<String, String> paramValues, String clientIp) {
        return queryAllData(id, paramValues, clientIp, "queryAll", null);
    }

    /** 查询全部（带社区数据权限，受限多社区选"全部"时逐社区合并） */
    public QueryResult queryAll(String id, Map<String, String> paramValues, String clientIp,
                                List<String> allowedCommunities) {
        return queryAllData(id, paramValues, clientIp, "queryAll", allowedCommunities);
    }

    /**
     * 查询全部数据的核心实现
     * @param action 操作类型，用于审计日志
     */
    private QueryResult queryAllData(String id, Map<String, String> paramValues, String clientIp, String action,
                                     List<String> allowedCommunities) {
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

        // 社区数据权限：越权拒绝；单社区强制替换；多社区"全部"逐社区合并（内存拼接，按社区分组呈现）
        List<String> commScope;
        try {
            commScope = resolveCommunities(report, paramValues, allowedCommunities);
        } catch (IllegalArgumentException e) {
            log.warn("[{}] 社区越权拦截: {} - {}", action, id, e.getMessage());
            auditService.audit("communityDenied", Map.of("report", id,
                "detail", e.getMessage() == null ? "" : e.getMessage()));
            return errorResult(e.getMessage());
        }
        if (commScope != null && commScope.size() > 1) {
            String communityParam = communityParamName(report);
            QueryResult merged = new QueryResult();
            List<String> columns = null;
            List<List<String>> rows = new ArrayList<>();
            long total = 0;
            boolean truncated = false;
            for (String c : commScope) {
                Map<String, String> pv = new HashMap<>(paramValues);
                pv.put(communityParam, c);
                QueryResult r = queryAllData(id, pv, clientIp, action, allowedCommunities);
                if (r.getError() != null) return r;
                if (columns == null) { columns = r.getColumns(); merged.setColumns(columns); }
                rows.addAll(r.getRows());
                total += r.getTotal();
                truncated |= r.isTruncated();
                if (rows.size() >= maxRows) {
                    rows = new ArrayList<>(rows.subList(0, maxRows));
                    truncated = true;
                    break;
                }
            }
            merged.setRows(rows);
            merged.setTotal(total);
            merged.setTruncated(truncated);
            return merged;
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
        QueryResult result = new QueryResult();

        try (Connection conn = resolveDataSource(report).getConnection()) {
            conn.setNetworkTimeout(networkTimeoutExecutor, queryTimeout * 1000);
            QueryResult full = fetchFullResult(conn, report, bindings);
            result.setColumns(full.getColumns());
            result.setRows(full.getRows());
            result.setTotal(full.getTotal());
            result.setTruncated(full.isTruncated());
        } catch (IllegalArgumentException e) {
            log.warn("[{}] 数据源错误: {} - {}", action, id, e.getMessage());
            result.setError(e.getMessage());
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
        auditService.recordQuery(action, id, clientIp, duration, rowCount, result.getError() == null);
        return result;
    }

    /**
     * 导出前预检（不写流）：报表存在、SQL 安全、必填参数、数据源可解析。
     * 通过后 Controller 再设置下载响应头。
     */
    public void validateForExport(String id, Map<String, String> paramValues) {
        validateForExport(id, paramValues, null);
    }

    /** 导出预检（带社区数据权限，越权在此抛出，避免已写 xlsx 头） */
    public void validateForExport(String id, Map<String, String> paramValues, List<String> allowedCommunities) {
        ReportInfo report = reportCache.get(id);
        if (report == null) {
            throw new IllegalArgumentException("报表不存在");
        }
        String validation = validateSql(report.getSql());
        if (validation != null) {
            throw new IllegalArgumentException(validation);
        }
        if (report.getSql().length() > maxSqlLength) {
            throw new IllegalArgumentException("SQL语句过长，请联系管理员优化");
        }
        String missing = checkRequired(report, paramValues);
        if (missing != null) {
            throw new IllegalArgumentException("缺少必填参数: " + missing);
        }
        try {
            resolveCommunities(report, paramValues, allowedCommunities);
        } catch (IllegalArgumentException e) {
            auditService.audit("communityDenied", Map.of("report", id,
                "detail", e.getMessage() == null ? "" : e.getMessage()));
            throw e;
        }
        // 强制解析数据源：错误 id 在此失败，避免已写 xlsx 头
        resolveDataSource(report);
    }

    /**
     * 流式导出：ResultSet 边读边写 Excel，避免全量 List 占满堆。
     * 调用前建议先 validateForExport。
     * @return 写出的数据行数
     */
    public ExcelService.ExportOutcome exportStream(String id, Map<String, String> paramValues, String clientIp,
                             java.io.OutputStream outputStream) throws Exception {
        return exportStream(id, paramValues, clientIp, null, outputStream);
    }

    /**
     * 流式导出（带社区数据权限）。
     * 受限多社区选"全部"时：逐社区全量合并为 QueryResult 后走内存导出（复用 ExcelService.export）。
     */
    public ExcelService.ExportOutcome exportStream(String id, Map<String, String> paramValues, String clientIp,
                             List<String> allowedCommunities, java.io.OutputStream outputStream) throws Exception {
        validateForExport(id, paramValues, allowedCommunities);
        ReportInfo report = reportCache.get(id);

        // 多社区"全部"：合并路径（内存导出，行数已受 maxRows 限制）
        List<String> commScope = resolveCommunities(report, paramValues, allowedCommunities);
        if (commScope != null && commScope.size() > 1) {
            long mStart = System.currentTimeMillis();
            QueryResult merged = queryAllData(id, paramValues, clientIp, "exportStream", allowedCommunities);
            if (merged.getError() != null) throw new IllegalStateException(merged.getError());
            excelService.export(report.getName(), merged, outputStream);
            long rowCount = merged.getRows() != null ? merged.getRows().size() : 0;
            // 走到这里导出已成功；truncated 是数据量标记、不是成败标记，
            // 早先误把 isTruncated() 传进 success 形参，导致每次被截断的成功导出都记成失败。
            auditService.recordExport(id, clientIp, System.currentTimeMillis() - mStart, rowCount, true);
            return new ExcelService.ExportOutcome(rowCount, merged.isTruncated());
        }

        boolean acquired = false;
        try {
            acquired = exportSemaphore.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("系统繁忙，请稍后再试");
        }
        if (!acquired) {
            throw new IllegalStateException("系统繁忙，请稍后再试");
        }

        long startTime = System.currentTimeMillis();
        long rowCount = 0;
        boolean truncated = false;
        boolean success = false;
        try {
            Map<String, String> bindings = buildBindings(report, paramValues);
            try (Connection conn = resolveDataSource(report).getConnection()) {
                conn.setNetworkTimeout(networkTimeoutExecutor, queryTimeout * 1000);
                try (PreparedStatement ps = buildPreparedStatement(conn, report.getSql(), bindings);
                     ResultSet rs = ps.executeQuery()) {
                    ExcelService.ExportOutcome outcome =
                        excelService.exportFromResultSet(report.getName(), rs, maxRows, outputStream);
                    rowCount = outcome.getRows();
                    truncated = outcome.isTruncated();
                    success = true;
                }
            }
            return new ExcelService.ExportOutcome(rowCount, truncated);
        } finally {
            exportSemaphore.release();
            long duration = System.currentTimeMillis() - startTime;
            auditService.recordExport(id, clientIp, duration, rowCount, success);
        }
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
        // 支持 dataSource / datasource / dataSourceId
        Object ds = config.get("dataSourceId");
        if (ds == null) ds = config.get("dataSource");
        if (ds == null) ds = config.get("datasource");
        if (ds != null && !ds.toString().isBlank()) {
            report.setDataSourceId(ds.toString().trim());
        }
        report.setSql(SqlUtils.stripComments(sqlText.trim()));

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

    /** 从SQL中提取参数名（按出现顺序，跳过字符串字面量） */
    private List<String> extractParamNames(String sql) {
        return SqlUtils.extractParamNamesInOrder(sql);
    }

    /** 找到报表的社区参数名（约定参数名为 community），无则返回 null */
    private String communityParamName(ReportInfo report) {
        if (report.getParams() == null) return null;
        for (ParamDef p : report.getParams()) {
            if ("community".equals(p.getName())) return p.getName();
        }
        return null;
    }

    /**
     * 解析社区数据权限（会在必要时改写 paramValues 中的 community 值）：
     * - 返回 null：该用户不限社区 → 按原参数执行
     * - 返回单元素：单社区执行（限权用户选"全部"时已把参数强制替换为该社区）
     * - 返回多元素：需逐社区合并（参数保持"全部"）
     * 越权（选择了权限外社区）抛 IllegalArgumentException。
     *
     * <p>fail-closed：受限账号只能运行SQL 里真的绑定了 community 参数的报表。
     * 此前报表未声明该参数时直接返回 null（=不过滤执行），后果有两层：
     * 一是单社区账号能看到全院数据（{@code community_statistics_query.sql} 即属此类），
     * 二是多社区账号会把同一条无过滤 SQL 逐社区重复执行后拼接，行数翻 N 倍。
     * 只校验「声明了参数」不够，管理员可以把 params 写上却在改写 SQL 时删掉 WHERE 里的绑定，
     * 所以这里查的是实际绑定序列。
     */
    private List<String> resolveCommunities(ReportInfo report, Map<String, String> paramValues,
                                            List<String> allowedCommunities) {
        if (allowedCommunities == null || allowedCommunities.isEmpty()) return null;

        String name = communityParamName(report);
        if (name == null || !extractParamNames(report.getSql()).contains(name)) {
            throw new IllegalArgumentException("报表「" + report.getName()
                + "」未按社区限定数据范围，受限账号不可查看，请联系报表管理员");
        }
        String raw = paramValues.get(name);
        String v = raw == null ? "%" : raw.trim();
        if (v.isEmpty()) v = "%";
        if (!"%".equals(v)) {
            if (!allowedCommunities.contains(v)) {
                throw new IllegalArgumentException("无权查看社区 " + v);
            }
            return List.of(v);
        }
        // "全部" → 权限集合（排序保证合并顺序稳定）
        List<String> scope = new ArrayList<>(allowedCommunities);
        Collections.sort(scope);
        if (scope.size() == 1) {
            paramValues.put(name, scope.get(0));
        }
        return scope;
    }

    /**
     * 构建绑定参数。值仍以 {@code ?} 绑定，不存在注入；这里挡的是**取值本身不合理**：
     * 一个 {@code 0001-01-01} 就能把任何带日期区间的 PII 报表拉成全历史，
     * 再经 /export 一次导出 2 万行患者明细。
     */
    private Map<String, String> buildBindings(ReportInfo report, Map<String, String> paramValues) {
        Map<String, String> bindings = new HashMap<>();
        for (ParamDef p : report.getParams()) {
            String value = paramValues.getOrDefault(p.getName(), p.getDefaultValue());
            if (value != null && value.isEmpty()) value = null;
            validateParamValue(report, p, value);
            bindings.put(p.getName(), value);
        }
        return bindings;
    }

    /** 单个报表参数的取值约束：长度、控制字符、date 型必须是真的日期。 */
    private void validateParamValue(ReportInfo report, ParamDef p, String value) {
        if (value == null) return;
        if (value.length() > MAX_PARAM_LENGTH) {
            throw new IllegalArgumentException("参数「" + p.getLabel() + "」过长（最多 "
                + MAX_PARAM_LENGTH + " 字符）");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException("参数「" + p.getLabel() + "」含非法控制字符");
            }
        }
        // 通配/空值不参与日期校验：LIKE 语义允许 %
        if (!"date".equals(p.getType()) || "%".equals(value) || value.isBlank()) return;
        try {
            java.time.LocalDate.parse(value.trim(),
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("参数「" + p.getLabel() + "」需为 yyyy-MM-dd 日期");
        }
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

    /** 构建PreparedStatement：:param → ? 并顺序绑定 */
    private PreparedStatement buildPreparedStatement(Connection conn, String sql,
            Map<String, String> bindings) throws SQLException {
        return SqlUtils.prepareNamed(conn, sql, bindings, queryTimeout, 5000);
    }

    private long getCachedCount(String key) {
        if (countCacheTtlSeconds <= 0) return -1;
        CountCacheEntry e = countCache.get(key);
        if (e == null) return -1;
        if (System.currentTimeMillis() > e.expireAt) {
            countCache.remove(key, e);
            return -1;
        }
        return e.total;
    }

    private void putCachedCount(String key, long total) {
        if (countCacheTtlSeconds <= 0) return;
        countCache.put(key, new CountCacheEntry(total, System.currentTimeMillis() + countCacheTtlSeconds * 1000L));
        // 简单防止无限增长
        if (countCache.size() > 500) {
            long now = System.currentTimeMillis();
            countCache.entrySet().removeIf(en -> en.getValue().expireAt < now);
        }
    }

    private static String bindingsHash(Map<String, String> bindings) {
        if (bindings == null || bindings.isEmpty()) return "";
        return bindings.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("&"));
    }

    /**
     * 增强的SQL安全校验
     * 防止SQL注入和危险操作
     */
    private String validateSql(String sql) {
        String error = SqlUtils.validateSelectOnly(sql);
        if (error != null) {
            log.warn("SQL校验失败: {}", error);
        }
        return error;
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
