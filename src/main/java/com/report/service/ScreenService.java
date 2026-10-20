package com.report.service;

import com.report.util.AtomicFileWriter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可视化平台（数据大屏）定时查询服务。
 *
 * 设计要点：
 * - 指标SQL放 scripts/screen/*.sql（YAML头 + SQL，同报表格式），
 *   YAML头可声明 interval-minutes（默认10），各指标独立按各自间隔刷新；
 * - 指标SQL统一按"社区"维度返回（社区列名固定为"社区"），本服务在内存中
 *   按所选社区过滤（单社区）或跨社区求和（全部），切换社区不打数据库；
 * - 单线程串行执行：任意时刻最多1条大屏SQL在跑，上一轮未完成跳过本轮，
 *   对生产库压力可预测；
 * - 查询结果存内存缓存，大屏接口只读缓存（毫秒级响应，不碰数据库）；
 * - 单条失败保留上次成功数据并记录错误原因，不影响其他指标；
 * - SQL强制只读校验（SqlUtils.validateSelectOnly），走默认数据源。
 */
@Service
public class ScreenService {

    private static final Logger log = LoggerFactory.getLogger(ScreenService.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 自定义区间实时查询时，请求线程最多排队等多久（超过就退回旧快照/空数据，不吊死请求）。 */
    private static final int LIVE_QUERY_WAIT_SECONDS = 75;

    /**
     * 刷新失败后的退避时长。失败快照的 {@code fetchedAt} 为空即「永不新鲜」，
     * 每个 60s 轮询都会重新打库；HIS 抖动时这就是重试风暴。
     */
    private static final int FAILED_RETRY_BACKOFF_SECONDS = 60;

    /** 一次区间组装要用到的 5 个区间指标（顺序与 buildScreenData 里的取数一致）。 */
    private static final List<String> RANGE_METRIC_IDS = List.of(
        "range_kpi", "range_margin", "range_daily", "range_hourly", "range_category");

    /**
     * 一个面板标题槽位。
     *
     * @param key         前端节点上的槽位 id（{@code screen.html} 里标题那个 span 的 id）
     * @param text        大屏当前默认显示的文字（后台输入框的占位提示）
     * @param autoRenamed 是否由 {@code screen.js#updateRangeLabels} 按所选区间自动换字
     *                    （如「今日门诊量」/「区间门诊量」）。配了自定义标题就不再自动换。
     */
    public record PanelSlot(String key, String text, boolean autoRenamed) {}

    /**
     * 指标 → 它负责的面板槽位。
     *
     * <p>为什么按槽位而不是「指标名当标题」：一条 SQL 喂好几个面板是常态 ，
     * {@code range_daily} 同时是门诊量趋势、门诊/住院收入趋势、出入院趋势三块图的数据源，
     * {@code range_kpi} 喂左栏三张 KPI 卡。把标题挂在指标上，改一个名字会同时改掉三块，
     * 或者只能凭空多出「主标题/副标题」这种没人要的区分。
     */
    private static final Map<String, List<PanelSlot>> METRIC_PANEL_SLOTS = Map.of(
        "range_kpi", List.of(
            new PanelSlot("labelOutpatient", "区间门诊量", true),
            new PanelSlot("labelInpatient", "期末在院人数", false),
            new PanelSlot("labelRevenue", "区间收入", true)),
        "range_margin", List.of(
            new PanelSlot("labelMarginRate", "毛利率", false),
            new PanelSlot("labelOutpatientPrice", "门诊客单价", false),
            new PanelSlot("labelCommunity", "各社区收入·毛利对比", false)),
        "range_daily", List.of(
            new PanelSlot("labelOutpatientTrend", "门诊量趋势", true),
            new PanelSlot("labelRevenueTrend", "门诊 / 住院收入趋势", false),
            new PanelSlot("labelAdmission", "出入院趋势", false)),
        "range_hourly", List.of(new PanelSlot("labelHourly", "分时门诊量", true)),
        "range_category", List.of(new PanelSlot("labelCategory", "费用分类构成", false)));

    /** 面板标题长度上限：1920 三栏布局里最长的现有标题是 11 字，20 已经富余。 */
    private static final int MAX_PANEL_TITLE_CHARS = 20;

    private final DataSourceService dataSourceService;
    private final CommunityService communityService;

    @Value("${screen.enabled:true}")
    private boolean enabled;

    @Value("${screen.scripts-dir:scripts/screen}")
    private String scriptsDir;

    @Value("${screen.default-interval-minutes:10}")
    private int defaultIntervalMinutes;

    @Value("${report.query-timeout:120}")
    private int queryTimeout;

    /**
     * 指标快照缓存。
     * 无参数指标键为 指标ID；带区间参数的指标键为 指标ID|区间键，后者值域可能很大
     * （自定义区间任意），所以用带容量上限的 LRU，避免长时间运行后无界增长。
     */
    private final Map<String, MetricSnapshot> cache = new RangeAwareCache();

    /**
     * 自定义区间实时查询的并发上限：防止多人同时切区间把 HIS 打穿。
     * 一个区间要跑 5 条指标 SQL（单条 5~25s），许可太少会让冷区间必然部分超时。
     */
    private final java.util.concurrent.Semaphore liveQueryPermits =
        new java.util.concurrent.Semaphore(3);

    private static final int MAX_RANGE_SNAPSHOTS = 60;

    /** 串行执行器：保证同一时刻最多1条大屏SQL在跑 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "screen-refresh");
        t.setDaemon(true);
        return t;
    });

    /** 跑批进行中标志（防止手动刷新与定时刷新叠加） */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    /** 指标定义 */
    private volatile List<MetricDef> metrics = new ArrayList<>();
    private volatile boolean loaded = false;

    public ScreenService(DataSourceService dataSourceService, CommunityService communityService) {
        this.dataSourceService = dataSourceService;
        this.communityService = communityService;
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("可视化平台（数据大屏）功能已通过配置关闭: screen.enabled=false");
            return;
        }
        loadMetricDefs();
        // 首轮查询延迟30秒：等数据源连通性检查等启动流程完成，不阻塞应用启动
        executor.submit(() -> {
            try {
                Thread.sleep(30_000L);
                refreshAll("startup");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("大屏首轮查询失败", e);
            }
        });
    }

    /** 每5分钟检查一次：把到期的指标串行跑一遍（同时加载新增的指标SQL文件） */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    void refreshDue() {
        if (!enabled) return;
        loadMetricDefs();
        if (metrics.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        ScreenRange warmRange = ScreenRange.resolvePreset(ScreenRange.DEFAULT_PRESET, LocalDate.now());
        boolean anyDue = metrics.stream().anyMatch(m -> {
            String key = m.isRangeDriven() ? m.id + "|" + warmRange.key() : m.id;
            MetricSnapshot s;
            synchronized (cache) {
                s = cache.get(key);
            }
            return s == null || s.fetchedAt == null
                || s.fetchedAt.plusMinutes(m.intervalMinutes).isBefore(now);
        });
        if (anyDue) refreshAll("scheduled");
    }

    /** 手动立即刷新（管理员触发，异步执行，全部指标强制重跑） */
    public void refreshNow() {
        if (!enabled) throw new IllegalStateException("大屏功能已关闭");
        executor.submit(() -> refreshAll("manual", true));
    }

    // ============ 指标定义加载 ============

    private synchronized void loadMetricDefs() {
        List<MetricDef> defs = new ArrayList<>();
        Path dir = Paths.get(scriptsDir);
        if (!Files.isDirectory(dir)) {
            log.warn("大屏指标目录不存在，大屏将无数据: {}", dir.toAbsolutePath());
            this.metrics = defs;
            this.loaded = true;
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.sql")) {
            for (Path file : stream) {
                try {
                    defs.add(parseMetricFile(file));
                } catch (Exception e) {
                    log.error("解析大屏指标文件失败: {}", file, e);
                }
            }
        } catch (IOException e) {
            log.error("读取大屏指标目录失败: {}", scriptsDir, e);
        }
        // 按 interval 从长到短排序，跑批时长指标先跑（减少新旧数据混合窗口）
        defs.sort((a, b) -> Integer.compare(b.intervalMinutes, a.intervalMinutes));
        this.metrics = List.copyOf(defs);
        this.loaded = true;
        log.info("大屏指标加载完成: {} 个指标, 目录: {}", defs.size(), scriptsDir);
    }

    /** 解析单个指标文件（YAML头 + SQL，兼容无YAML头的纯SQL文件） */
    private MetricDef parseMetricFile(Path file) throws IOException {
        String content = Files.readString(file);
        String yamlText = "";
        String sqlText = content;

        java.util.regex.Pattern yamlBlock =
            java.util.regex.Pattern.compile("^\\s*---\\s*\\n(.*?)\\n---\\s*\\n(.*)", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = yamlBlock.matcher(content);
        if (m.matches()) {
            yamlText = m.group(1);
            sqlText = m.group(2);
        }

        Map<String, Object> config = new LinkedHashMap<>();
        try {
            Object loadedYaml = new Yaml().load(yamlText);
            if (loadedYaml instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) loadedYaml;
                config = map;
            }
        } catch (Exception e) {
            log.warn("大屏指标YAML解析失败: {} - {}", file.getFileName(), e.getMessage());
        }

        MetricDef def = new MetricDef();
        String fileName = file.getFileName().toString();
        def.id = fileName.replaceFirst("\\.sql$", "");
        def.file = fileName;
        def.name = String.valueOf(config.getOrDefault("name", def.id));
        Object desc = config.get("description");
        def.description = desc == null ? "" : desc.toString().trim();
        Object interval = config.get("interval-minutes");
        def.intervalMinutes = defaultIntervalMinutes;
        if (interval != null) {
            try {
                int v = Integer.parseInt(interval.toString().trim());
                if (v > 0) def.intervalMinutes = v;
            } catch (NumberFormatException ignore) {
                log.warn("指标 {} 的 interval-minutes 配置无效: {}，使用默认 {} 分钟",
                    def.id, interval, defaultIntervalMinutes);
            }
        }
        def.sql = SqlUtils.stripComments(sqlText.trim());
        def.paramNames = parseParamNames(config.get("params"), def.sql, file);
        def.panelTitles = parsePanelTitles(def.id, config.get("panel-titles"), file);
        return def;
    }

    /** 该指标可配的面板槽位（不在注册表里的指标 = 没有可配标题）。 */
    private static List<PanelSlot> panelSlotsOf(String metricId) {
        return METRIC_PANEL_SLOTS.getOrDefault(metricId, List.of());
    }

    /** 合并全部指标配置的面板标题；槽位在各指标间不重叠，所以直接 putAll。 */
    private Map<String, String> collectPanelTitles() {
        Map<String, String> out = new LinkedHashMap<>();
        for (MetricDef def : metrics) {
            out.putAll(def.panelTitles);
        }
        return out;
    }

    /**
     * 全部可配槽位的 id（前端据此知道「哪些标题可能被改过、清掉时要还原成什么」）。
     * 前端不另抄一份清单，清单只有 {@link #METRIC_PANEL_SLOTS} 这一处。
     */
    private static final List<String> PANEL_SLOT_IDS = METRIC_PANEL_SLOTS.values().stream()
        .flatMap(List::stream).map(PanelSlot::key).sorted().toList();

    /**
     * 解析 YAML 头的 {@code panel-titles}。
     *
     * <p>只认该指标注册过的槽位：手改文件时写一个不存在的 key，后果不是「报错」而是
     * 没有任何反应（前端只按注册表里的槽位取字），所以在这里就丢掉并留一行告警，
     * 免得有人对着一个不生效的键查半天。
     */
    private Map<String, String> parsePanelTitles(String metricId, Object raw, Path file) {
        if (!(raw instanceof Map)) return Map.of();
        List<String> allowed = panelSlotsOf(metricId).stream().map(PanelSlot::key).toList();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            String key = String.valueOf(e.getKey());
            Object value = e.getValue();
            if (value == null || value.toString().trim().isEmpty()) continue;
            if (!allowed.contains(key)) {
                log.warn("大屏指标 {} 配了不属于它的面板槽位 {}（可配：{}），已忽略",
                    file.getFileName(), key, String.join("/", allowed));
                continue;
            }
            out.put(key, value.toString().trim());
        }
        return Map.copyOf(out);
    }

    /**
     * 解析 YAML 头里的 params 声明（形态与报表一致：-name/-label/-type/-required）。
     *
     * <p>早先这里只读 name 与 interval-minutes，其它键一律静默丢弃，意味着给大屏
     * SQL 加了 params 也不会生效且毫无提示。现在解析后还要校验声明的参数确实出现在
     * SQL 里：SQL 用了但没声明 → 绑定时拿不到值；声明了但 SQL 没用 → 白写。两种都告警。
     */
    private List<String> parseParamNames(Object raw, String sql, Path file) {
        List<String> declared = new ArrayList<>();
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (!(item instanceof Map)) continue;
                Object n = ((Map<?, ?>) item).get("name");
                if (n != null && !n.toString().trim().isEmpty()) declared.add(n.toString().trim());
            }
        }
        List<String> used = SqlUtils.extractParamNamesInOrder(sql);
        for (String u : used) {
            if (!declared.contains(u)) {
                log.warn("大屏指标 {} 的 SQL 用了未声明的参数 :{}", file.getFileName(), u);
            }
        }
        for (String d : declared) {
            if (!used.contains(d)) {
                log.warn("大屏指标 {} 声明了参数 :{}，但 SQL 里没有它", file.getFileName(), d);
            }
        }
        // 绑定顺序以 SQL 里实际出现顺序为准，避免声明顺序与占位符顺序不一致
        return List.copyOf(used);
    }

    // ============ 管理侧：指标清单与 SQL 读写 ============

    /**
     * 指标ID的合法形态，只当文件名用，不给路径分隔符和扩展名留口子。
     * 编辑器不支持新建指标：光把 SQL 写进去只会多出一个没人画的面板，
     * 新增指标还要在 screen.js 配渲染函数（见 development.md「添加大屏指标」）。
     */
    private static final java.util.regex.Pattern METRIC_ID_PATTERN =
        java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,64}");

    /** 与 SQL 导入同一个上限，防止一次粘贴把指标文件写成几十 MB。 */
    private static final int MAX_METRIC_CONTENT_CHARS = 100_000;

    /**
     * 管理页的指标清单。以已加载的定义为准（定时任务每 5 分钟重载、保存后立即重载），
     * 并附带默认区间下最近一次真实取数的状态，改口径的人第一个问题就是
     * 「新语句跑成功了没」，答不上这条，编辑器等于让人盲改。
     */
    public List<Map<String, Object>> listMetricsForAdmin() {
        if (!loaded) loadMetricDefs();
        ScreenRange warm = ScreenRange.resolvePreset(ScreenRange.DEFAULT_PRESET, LocalDate.now());
        List<Map<String, Object>> out = new ArrayList<>();
        for (MetricDef def : metrics) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", def.id);
            m.put("name", def.name);
            m.put("description", def.description);
            m.put("file", def.file);
            m.put("intervalMinutes", def.intervalMinutes);
            List<PanelSlot> slots = panelSlotsOf(def.id);
            if (!slots.isEmpty()) {
                List<Map<String, Object>> slotViews = new ArrayList<>();
                for (PanelSlot s : slots) {
                    Map<String, Object> sv = new LinkedHashMap<>();
                    sv.put("key", s.key());
                    sv.put("defaultText", s.text());
                    // 前端据此提示：这个槽位的文字平时是代码按区间自动换的，填了就盖掉它
                    sv.put("autoRenamed", s.autoRenamed());
                    sv.put("current", def.panelTitles.get(s.key()));
                    slotViews.add(sv);
                }
                m.put("panelSlots", slotViews);
            }
            MetricSnapshot s;
            synchronized (cache) {
                s = cache.get(cacheKey(def, def.isRangeDriven() ? warm : null));
            }
            m.put("lastFetchedAt", s == null || s.fetchedAt == null
                ? null : s.fetchedAt.format(TS_FMT));
            m.put("rowCount", s == null || s.rows == null ? 0 : s.rows.size());
            m.put("stale", s != null && s.stale);
            m.put("lastError", s == null ? null : s.lastError);
            out.add(m);
        }
        return out;
    }

    /** 读指标文件原文（YAML 头 + SQL）。指标不存在返回 {@code null}。 */
    public String readMetricContent(String metricId) throws IOException {
        Path file = resolveMetricFile(metricId);
        return file == null ? null : Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * 保存指标文件，后台编辑器唯一的落盘通道，所以把关设在这一步而不是控制器：
     * 以后再多一个入口也不会漏（development.md §3「任何收下 SQL 并留下痕迹的入口
     * 都必须与预览同严」）。
     *
     * <p>校验的文本与运行时的 {@code def.sql} 逐字同源（都取 YAML 头之后的部分并
     * {@code stripComments}），避免出现「编辑器拒了、跑批却照跑」或反之。
     *
     * <p>保存后不清快照：快照是「上一次的好数据」，是取数失败时大屏继续显示的
     * 那一份；清掉它等于让一次改坏的 SQL 直接把整屏变成空白。改为把该指标排进串行
     * 执行器立刻重跑，跑成功自然覆盖，跑失败则沿用旧快照并打「数据延迟」标。
     *
     * @param panelTitles 面板标题（槽位key → 文字）；传 {@code null} 表示不动标题，
     *                    传空 Map 或全空值表示清除该指标已配的自定义标题
     * @throws IllegalArgumentException 指标不存在，或内容/标题未通过校验
     * @throws IOException 备份或写文件失败
     */
    public void saveMetricContent(String metricId, String content, Map<String, String> panelTitles)
        throws IOException {
        Path file = resolveMetricFile(metricId);
        if (file == null) throw new IllegalArgumentException("大屏指标不存在: " + metricId);
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL内容不能为空");
        }
        if (content.length() > MAX_METRIC_CONTENT_CHARS) {
            throw new IllegalArgumentException("SQL内容过长，最大支持100KB");
        }
        if (!content.contains("---")) {
            throw new IllegalArgumentException("内容格式错误，缺少YAML元数据(---分隔符)");
        }
        String executable = SqlUtils.stripComments(SqlUtils.extractSql(content).trim());
        String error = SqlUtils.validateSelectOnly(executable);
        if (error == null) error = SqlUtils.validateExtraDangerous(executable);
        if (error != null) throw new IllegalArgumentException("SQL校验失败: " + error);
        String next = withPanelTitles(metricId, content, panelTitles);

        Files.copy(file, Paths.get(file + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        AtomicFileWriter.writeString(file, next);
        loadMetricDefs();
        log.info("大屏指标[{}]SQL已保存并重新加载: {}（备份 {}）",
            metricId, file.getFileName(), file.getFileName() + ".bak");
        refreshMetricAfterSave(metricId);
    }

    /**
     * 把面板标题写进 YAML 头，SQL 正文原样保留。
     *
     * <p>头部用 snakeyaml 的 {@code dump} 重新生成而不是字符串拼接：报表侧踩过的那次
     * 「报表名里塞一个换行就能注入 {@code dataSourceId:} / {@code source: "sql:…"}`」
     * 在这里同样成立，标题是自由文本，拼进去就能往指标文件里塞任意 YAML 键
     * （包括 {@code interval-minutes: 1} 这种会把 HIS 打穿的和 {@code params:} 这种会改绑定的）。
     */
    private String withPanelTitles(String metricId, String content, Map<String, String> panelTitles) {
        if (panelTitles == null) return content;
        // 按「行」定位头尾，而不是套用解析用的那个 \s* 宽松正则：\s* 会把正文开头的空行一起吃掉，
        // 那样「只改标题」就会顺手删掉 SQL 前面的空行，正文必须逐字节原样回去。
        if (!content.startsWith("---")) {
            throw new IllegalArgumentException("无法解析 YAML 头，改不了标题；请在弹窗里直接编辑文件头部");
        }
        java.util.regex.Matcher open = java.util.regex.Pattern
            .compile("---[ \\t]*\\r?\\n").matcher(content);
        if (!open.find() || open.start() != 0) {
            throw new IllegalArgumentException("无法解析 YAML 头，改不了标题；请在弹窗里直接编辑文件头部");
        }
        java.util.regex.Matcher close = java.util.regex.Pattern
            .compile("\\r?\\n---[ \\t]*\\r?\\n").matcher(content);
        if (!close.find(Math.max(0, open.end() - 1))) {
            throw new IllegalArgumentException("无法解析 YAML 头（缺少结束的 --- 行），改不了标题");
        }
        String headText = content.substring(open.end(), close.start());
        String body = content.substring(close.end());
        Map<String, Object> head = new LinkedHashMap<>();
        try {
            Object parsed = new Yaml().load(headText);
            if (parsed instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) parsed).entrySet()) {
                    head.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("YAML 头解析失败，改不了标题: " + e.getMessage());
        }

        List<String> allowed = panelSlotsOf(metricId).stream().map(PanelSlot::key).toList();
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : panelTitles.entrySet()) {
            String key = e.getKey();
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (value.isEmpty()) continue;
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("该指标没有面板槽位: " + key);
            }
            if (value.length() > MAX_PANEL_TITLE_CHARS) {
                throw new IllegalArgumentException("面板标题最长 " + MAX_PANEL_TITLE_CHARS + " 个字: " + key);
            }
            if (value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("面板标题不允许换行或控制字符: " + key);
            }
            cleaned.put(key, value);
        }
        if (cleaned.isEmpty()) {
            head.remove("panel-titles");
        } else {
            head.put("panel-titles", cleaned);
        }

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setSplitLines(false);
        opts.setWidth(Integer.MAX_VALUE);   // 别把中文标题折行，折了就多出一个物理行
        return "---\n" + new Yaml(opts).dump(head) + "---\n" + body;
    }

    /** 排队到既有的串行执行器上立刻重跑该指标（不并发、不阻塞保存请求）。 */
    private void refreshMetricAfterSave(String metricId) {
        if (!enabled) return;
        MetricDef def = findDef(metricId);
        if (def == null) return;
        ScreenRange range = def.isRangeDriven()
            ? ScreenRange.resolvePreset(ScreenRange.DEFAULT_PRESET, LocalDate.now()) : null;
        executor.submit(() -> runMetric(def, "post-save", range));
    }

    /** 指标ID → 磁盘文件；非法形态、越出目录或文件不存在都返回 {@code null}。 */
    private Path resolveMetricFile(String metricId) {
        if (metricId == null || !METRIC_ID_PATTERN.matcher(metricId).matches()) return null;
        Path base = Paths.get(scriptsDir).toAbsolutePath().normalize();
        Path file = base.resolve(metricId + ".sql").normalize();
        return file.startsWith(base) && Files.isRegularFile(file) ? file : null;
    }

    // ============ 定时刷新 ============

    /** 无参数指标用指标ID作键；带区间参数的指标再拼上区间键。 */
    private String cacheKey(MetricDef def, ScreenRange range) {
        return def.isRangeDriven() ? def.id + "|" + range.key() : def.id;
    }

    private void refreshAll(String trigger) {
        refreshAll(trigger, false);
    }

    private void refreshAll(String trigger, boolean force) {
        if (!refreshing.compareAndSet(false, true)) {
            log.debug("大屏上一轮刷新尚未完成，跳过本次触发({})", trigger);
            return;
        }
        // 区间指标只预热「默认区间」：任意自定义区间不可能预先跑完，
        // 全部预跑等于把大屏的库压力乘以区间档位数，得不偿失。
        ScreenRange warmRange = ScreenRange.resolvePreset(ScreenRange.DEFAULT_PRESET, LocalDate.now());
        try {
            LocalDateTime now = LocalDateTime.now();
            for (MetricDef def : metrics) {
                ScreenRange range = def.isRangeDriven() ? warmRange : null;
                MetricSnapshot prev;
                synchronized (cache) {
                    prev = cache.get(cacheKey(def, range));
                }
                if (!force && prev != null && prev.fetchedAt != null
                    && prev.fetchedAt.plusMinutes(def.intervalMinutes).isAfter(now)) {
                    continue; // 未到期，跳过
                }
                runMetric(def, trigger, range);
            }
        } finally {
            refreshing.set(false);
        }
    }

    private void runMetric(MetricDef def, String trigger, ScreenRange range) {
        String key = cacheKey(def, range);
        long start = System.currentTimeMillis();
        try {
            String validationError = SqlUtils.validateSelectOnly(def.sql);
            if (validationError != null) {
                throw new IllegalArgumentException("SQL校验失败: " + validationError);
            }
            List<Map<String, Object>> rows = executeQuery(def.sql,
                range == null ? Map.of() : range.bindings());
            MetricSnapshot snapshot = new MetricSnapshot();
            snapshot.fetchedAt = LocalDateTime.now();
            snapshot.rows = rows;
            snapshot.success = true;
            snapshot.stale = false;
            snapshot.lastError = null;
            snapshot.nextRetryAt = null;
            synchronized (cache) {
                cache.put(key, snapshot);
            }
            log.info("大屏指标[{}]刷新成功({}触发): {}行, 耗时{}ms",
                key, trigger, rows.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("大屏指标[{}]刷新失败({}触发), 耗时{}ms: {}",
                key, trigger, System.currentTimeMillis() - start, e.getMessage());
            LocalDateTime retryAt = LocalDateTime.now().plusSeconds(FAILED_RETRY_BACKOFF_SECONDS);
            synchronized (cache) {
                MetricSnapshot snapshot = cache.get(key);
                boolean hasGoodData = snapshot != null && snapshot.fetchedAt != null
                    && snapshot.rows != null && !snapshot.rows.isEmpty();
                if (hasGoodData) {
                    /* 保留上一次的好数据继续展示，只打「延迟」标。早先这里把 success 置 false，
                     *   rangeRows 便返回 null、整屏空白，既违反本文件开头「保留最后一次好数据」
                     *   的规则，又让前端回落到演示数据，把编造的门诊人次画在墙上。 */
                    snapshot.lastError = e.getMessage();
                    snapshot.stale = true;
                    snapshot.nextRetryAt = retryAt;
                } else {
                    if (snapshot == null) {
                        snapshot = new MetricSnapshot();
                        snapshot.rows = List.of();
                        cache.put(key, snapshot);
                    }
                    snapshot.success = false;
                    snapshot.stale = false;
                    snapshot.lastError = e.getMessage();
                    snapshot.nextRetryAt = retryAt;
                }
            }
        }
    }

    /**
     * 取某指标在指定区间下的快照：命中且未到期直接返回，否则实时查一次。
     *
     * <p>与定时跑批不同，这里跑在请求线程上，因此额外受 {@link #liveQueryPermits} 约束 ，
     * 多人同时切到没预热过的区间时，排队而不是各自向 HIS 发一条重查询。
     */
    private MetricSnapshot snapshotFor(MetricDef def, ScreenRange range) {
        String key = cacheKey(def, range);
        synchronized (cache) {
            MetricSnapshot s = cache.get(key);
            if (s != null && s.fetchedAt != null
                && s.fetchedAt.plusMinutes(def.intervalMinutes).isAfter(LocalDateTime.now())) {
                return s;
            }
            // 刚失败过就先别打库：直接回退到手上的快照（可能是上一次的好数据，标记为 stale）
            if (s != null && s.nextRetryAt != null && s.nextRetryAt.isAfter(LocalDateTime.now())) {
                return s;
            }
        }
        boolean acquired;
        try {
            acquired = liveQueryPermits.tryAcquire(LIVE_QUERY_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            synchronized (cache) { return cache.getOrDefault(key, emptySnapshot()); }
        }
        if (!acquired) {
            log.warn("大屏区间查询排队超时({}s)，返回旧快照或空数据: {}", LIVE_QUERY_WAIT_SECONDS, key);
            synchronized (cache) { return cache.getOrDefault(key, emptySnapshot()); }
        }
        try {
            // 拿到许可后先复查：等位期间可能已被别人跑完
            synchronized (cache) {
                MetricSnapshot s = cache.get(key);
                if (s != null && s.fetchedAt != null
                    && s.fetchedAt.plusMinutes(def.intervalMinutes).isAfter(LocalDateTime.now())) {
                    return s;
                }
                if (s != null && s.nextRetryAt != null && s.nextRetryAt.isAfter(LocalDateTime.now())) {
                    return s;
                }
            }
            runMetric(def, "on-demand", range);
        } finally {
            // 许可必须在 finally 里还。早先这里漏了 release，3 次冷区间请求就把许可
            //   全部泄漏，之后每条实时查询都恰好等满 LIVE_QUERY_WAIT_SECONDS 再放弃 ，
            //   表现是「所有非默认区间永久拿不到数」，而不是报错。
            liveQueryPermits.release();
        }
        synchronized (cache) {
            return cache.getOrDefault(key, emptySnapshot());
        }
    }

    private MetricSnapshot emptySnapshot() {
        MetricSnapshot s = new MetricSnapshot();
        s.rows = List.of();
        s.success = false;
        return s;
    }

    private MetricDef findDef(String id) {
        for (MetricDef d : metrics) {
            if (d.id.equals(id)) return d;
        }
        return null;
    }

    /** 区间指标取行；指标文件缺失/查询未完成时返回 null，调用方必须把它渲染成「未知」，
     *  绝不能当 0 画出来：大屏上「0」和「没取到」长得一样，但后者会让人误判业务归零。 */
    private List<Map<String, Object>> rangeRows(String metricId, ScreenRange range) {
        MetricDef def = findDef(metricId);
        if (def == null) {
            log.warn("大屏缺少区间指标文件 {}，相关面板将为空", metricId);
            return null;
        }
        MetricSnapshot s = snapshotFor(def, range);
        if (!s.success || s.fetchedAt == null) {
            log.warn("大屏区间指标[{}]未取得数据（区间 {}），相关面板将显示为未知而非 0",
                metricId, range.key());
            return null;
        }
        return s.rows;
    }

    /** 区间指标中是否有「本次刷新失败、仍在展示上一次好数据」的。 */
    private boolean anyRangeMetricStale(ScreenRange range) {
        for (String id : RANGE_METRIC_IDS) {
            MetricDef def = findDef(id);
            if (def == null) continue;
            MetricSnapshot s;
            synchronized (cache) { s = cache.get(cacheKey(def, range)); }
            if (s != null && s.stale) return true;
        }
        return false;
    }

    private List<Map<String, Object>> executeQuery(String sql, Map<String, String> bindings) throws SQLException {
        DataSource ds = dataSourceService.getDataSource(null);
        try (Connection conn = ds.getConnection()) {
            // 复用报表那套命名参数绑定（:name → ? 按出现顺序 setString）；
            // 无参数指标传空 bindings，等价于原来的裸 prepareStatement。
            try (PreparedStatement ps = SqlUtils.prepareNamed(conn, sql, bindings, queryTimeout, 500)) {
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    List<String> colNames = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        // label为SQL别名（中文列名），无别名时用列名
                        String label = meta.getColumnLabel(i);
                        colNames.add(label != null ? label : meta.getColumnName(i));
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next() && rows.size() < 2000) {
                        Map<String, Object> row = new LinkedHashMap<>(colCount * 2);
                        for (int i = 1; i <= colCount; i++) {
                            Object v = rs.getObject(i);
                            if (v instanceof java.sql.Timestamp || v instanceof java.sql.Date) {
                                v = rs.getString(i);
                            }
                            row.put(colNames.get(i - 1), v);
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            }
        }
    }

    // ============ 数据读取（供Controller调用） ============

    /** 默认区间（近30天）下的大屏数据，供未显式传区间的调用方使用。 */
    public Map<String, Object> buildScreenData(String communityRaw, List<String> allowedCommunities) {
        return buildScreenData(communityRaw, allowedCommunities,
            ScreenRange.resolvePreset(ScreenRange.DEFAULT_PRESET, LocalDate.now()));
    }

    /**
     * 组装大屏完整数据。所有面板的时间窗口严格等于 range，不做任何隐式回看 ，
     * 标题上写什么区间，取数就是什么区间。
     *
     * @param communityRaw       所选社区：null/空/"ALL"=全部社区；否则为具体社区（如 A/B/C）
     * @param allowedCommunities 用户可查看社区。null = 不限；空列表 = 无可见社区
     *                           （所有社区维度数据均为空，前端据 noCommunityAccess 给出提示）
     * @param range              时间区间（含用于环比的上一等长区间）
     */
    public Map<String, Object> buildScreenData(String communityRaw, List<String> allowedCommunities,
                                               ScreenRange range) {
        boolean noCommunityAccess = allowedCommunities != null && allowedCommunities.isEmpty();

        // 一次区间内各指标只取一次行，多个视图复用同一份结果。
        // null = 该指标未取得（文件缺失/查询超时），与「取到了但都是 0」必须区分开。
        List<Map<String, Object>> kpiRows = rangeRows("range_kpi", range);
        List<Map<String, Object>> marginRows = rangeRows("range_margin", range);
        List<Map<String, Object>> dailyRows = rangeRows("range_daily", range);
        List<Map<String, Object>> hourlyRows = rangeRows("range_hourly", range);
        List<Map<String, Object>> categoryRows = rangeRows("range_category", range);

        List<String> missing = new ArrayList<>();
        if (kpiRows == null) missing.add("range_kpi");
        if (marginRows == null) missing.add("range_margin");
        if (dailyRows == null) missing.add("range_daily");
        if (hourlyRows == null) missing.add("range_hourly");
        if (categoryRows == null) missing.add("range_category");

        List<String> communities = collectCommunities(kpiRows, marginRows, dailyRows);
        if (allowedCommunities != null) {
            List<String> filtered = new ArrayList<>();
            for (String c : communities) {
                if (allowedCommunities.contains(c)) filtered.add(c);
            }
            communities = filtered;
        }
        String community = normalizeCommunity(communityRaw, communities);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverTime", LocalDateTime.now().format(TS_FMT));
        data.put("communities", communities);
        data.put("selectedCommunity", community);
        data.put("noCommunityAccess", noCommunityAccess);
        data.put("range", describeRange(range));
        if (!missing.isEmpty()) data.put("missingMetrics", missing);
        // 刷新失败但手上仍有上一次的好数据时，数字照旧展示、额外打「数据延迟」标：
        // 前端据此不再回落到演示数据（那等于把编造的门诊人次画在墙上）。
        if (anyRangeMetricStale(range)) data.put("stale", true);

        // 自定义面板标题：没配就不输出 panelTitles，前端继续用 screen.html 里的原文字，
        // 大屏的视觉表现不会因为这次改造有任何变化。slots 是清单本身（前端据此在
        // 「标题被清空」时还原成默认文字），不另在前端抄第二份列表。
        data.put("panelTitleSlots", PANEL_SLOT_IDS);
        Map<String, String> panelTitles = collectPanelTitles();
        if (!panelTitles.isEmpty()) data.put("panelTitles", panelTitles);

        // ---- 业务视图层：把原始指标行组装成前端直接可用的结构 ----
        // 每个视图独立兜异常：一个面板取数失败不该把整屏打成白屏。
        putView(data, "kpiPrimary", () -> buildKpiPrimary(kpiRows, community, allowedCommunities, range));
        Map<String, Object> primary = asMap(data.get("kpiPrimary"));
        putView(data, "kpiMargin", () -> buildKpiMargin(marginRows, community, allowedCommunities, primary));
        putView(data, "trend", () -> buildDailySeries(dailyRows, community, allowedCommunities));
        putView(data, "admission", () -> buildAdmission(dailyRows, community, allowedCommunities));
        putView(data, "hourly", () -> buildHourly(hourlyRows, community, allowedCommunities));
        putView(data, "category", () -> buildCategory(categoryRows, community, allowedCommunities));
        putView(data, "community", () -> buildCommunity(kpiRows, marginRows, allowedCommunities));
        return data;
    }

    private void putView(Map<String, Object> data, String key, java.util.function.Supplier<Object> sup) {
        try {
            data.put(key, sup.get());
        } catch (Exception e) {
            log.warn("组装大屏视图 {} 失败: {}", key, e.getMessage());
            data.put(key, Map.of());
        }
    }

    private Map<String, Object> describeRange(ScreenRange r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", r.key());
        m.put("label", r.label());
        m.put("preset", r.preset());
        m.put("start", r.start().format(DateTimeFormatter.ISO_LOCAL_DATE));
        m.put("end", r.end().format(DateTimeFormatter.ISO_LOCAL_DATE));
        m.put("days", r.days());
        m.put("singleDay", r.isSingleDay());
        m.put("prevStart", r.prevStart().format(DateTimeFormatter.ISO_LOCAL_DATE));
        m.put("prevEnd", r.prevEnd().format(DateTimeFormatter.ISO_LOCAL_DATE));
        // 「在院人数」依赖 zhuangt 的当前态，只有区间末日就是今天时才有意义；
        // 把这个判断随数据一起下发，前端才能解释「--」是「不支持」而不是「没查到」。
        m.put("inpatientSupported", LocalDate.now().equals(r.end()));
        return m;
    }

    /** 可选社区列表：社区字典启用项 ∪ 区间数据中实际出现的社区 */
    private List<String> collectCommunities(List<Map<String, Object>>... rowSets) {
        Set<String> set = new LinkedHashSet<>(communityService.enabledCodes());
        for (List<Map<String, Object>> rows : rowSets) {
            if (rows == null) continue;
            for (Map<String, Object> row : rows) {
                Object sq = row.get("社区");
                if (sq != null && !sq.toString().trim().isEmpty()) {
                    set.add(sq.toString().trim());
                }
            }
        }
        List<String> list = new ArrayList<>(set);
        list.sort(String::compareTo);
        return list;
    }

    /** 指标未取得时的视图：所有字段显式为 null，前端据此渲染「--」而不是 0。 */
    private Map<String, Object> unavailable(String... keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : keys) m.put(k, null);
        return m;
    }

    /**
     * 区间核心KPI：门诊人次（区间整段去重，来自 SQL）、上一等长区间对比、
     * 期末在院、区间入出院、门诊/住院收入。
     *
     * <p>「在院人数」只在 end 就是今天时给出：SQL 用的是 zhuangt='1'，那是「此刻」的
     * 状态标志而非历史快照，套到历史区间上算出来的是错的；而改用入/出院时间括号又会被
     * chuysj 未填的记录撑大（实测同时点 27 vs 12）。两种都不如直接说「不支持」，
     * 返回 null 让前端显示「--」，好过给一个看起来合理的错数字。
     */
    private Map<String, Object> buildKpiPrimary(List<Map<String, Object>> raw, String community,
                                                List<String> allowed, ScreenRange range) {
        if (raw == null) {
            return unavailable("outpatientCount", "outpatientCountPrev", "inpatientAtEnd",
                "admitted", "discharged", "revenueMz", "revenueZy", "revenueTotal");
        }
        List<Map<String, Object>> rows = filterRows(raw, community, allowed);
        Map<String, Object> out = new LinkedHashMap<>();
        putSum(out, "outpatientCount", rows, "门诊人次");
        putSum(out, "outpatientCountPrev", rows, "对比区间门诊人次");
        if (range != null && LocalDate.now().equals(range.end())) {
            putSum(out, "inpatientAtEnd", rows, "当前在院人数");
        } else {
            out.put("inpatientAtEnd", null);
        }
        putSum(out, "admitted", rows, "入院人数");
        putSum(out, "discharged", rows, "出院人数");
        putSum(out, "revenueMz", rows, "门诊收入");
        putSum(out, "revenueZy", rows, "住院收入");
        putSum(out, "revenueTotal", rows, "收入合计");
        return out;
    }

    /**
     * 区间毛利与客单价：毛利率 = 毛利额 / 药品销售（聚合后重算，口径与单社区一致）。
     *
     * <p>客单价的分子分母取自 {@code primary}（range_kpi）而不是本快照 ，
     * 两个指标的刷新时刻错开，各取各的会让同一屏出现两个「今日门诊人次」
     * （实测 936 vs 901），看着就像数据错了。客单价与左栏的门诊量/门诊收入
     * 必须同源同快照。
     */
    private Map<String, Object> buildKpiMargin(List<Map<String, Object>> raw, String community,
                                               List<String> allowed, Map<String, Object> primary) {
        if (raw == null) {
            return unavailable("revenueTotal", "revenueMz", "revenueZy", "outpatientCount",
                "grossProfit", "marginRate", "marginRatePrev", "outpatientPrice");
        }
        List<Map<String, Object>> rows = filterRows(raw, community, allowed);
        Map<String, Object> out = new LinkedHashMap<>();
        putSum(out, "revenueTotal", rows, "收入合计");
        putSum(out, "revenueMz", rows, "门诊收入");
        putSum(out, "revenueZy", rows, "住院收入");
        double gross = sumColumn(rows, "毛利额");
        double drugSales = sumColumn(rows, "药品销售");
        double prevGross = sumColumn(rows, "对比区间毛利额");
        double prevSales = sumColumn(rows, "对比区间药品销售");
        out.put("grossProfit", round2(gross));
        out.put("marginRate", drugSales > 0 ? round2(gross / drugSales * 100) : null);
        out.put("marginRatePrev", prevSales > 0 ? round2(prevGross / prevSales * 100) : null);
        // 门诊客单价 = 区间门诊收入 / 区间门诊人次（与销售报表同口径，但强制与左栏同源）
        double mzRevenue = asDouble(primary == null ? null : primary.get("revenueMz"),
            sumColumn(rows, "门诊收入"));
        double mzCount = asDouble(primary == null ? null : primary.get("outpatientCount"),
            sumColumn(rows, "门诊人次"));
        out.put("outpatientCount", Math.round(mzCount));
        out.put("outpatientPrice", mzCount > 0 ? round2(mzRevenue / mzCount) : null);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private double asDouble(Object v, double fallback) {
        Number n = num(v);
        return n == null ? fallback : n.doubleValue();
    }

    /**
     * 区间逐日序列（趋势图用）。
     *
     * <p>注意：这里的「门诊人次」是**逐日各自去重**后按社区求和，跨日不再去重 ，
     * 它表达的是「每天有多少人来」，与 KPI 上「区间整段去重人次」是两个不同口径，
     * 两者相加不相等属正常，不可互相校验。
     */
    private Map<String, Object> buildDailySeries(List<Map<String, Object>> raw, String community,
                                                 List<String> allowed) {
        Map<String, double[]> byDate = new TreeMap<>();
        if (raw == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("days", null); out.put("outpatient", null);
            out.put("revenueMz", null); out.put("revenueZy", null);
            return out;
        }
        for (Map<String, Object> row : filterRows(raw, community, allowed)) {
            Object day = row.get("日期");
            if (day == null) continue;
            double[] agg = byDate.computeIfAbsent(day.toString(), k -> new double[3]);
            Number rc = num(row.get("门诊人次"));
            Number mz = num(row.get("门诊收入"));
            Number zy = num(row.get("住院收入"));
            if (rc != null) agg[0] += rc.doubleValue();
            if (mz != null) agg[1] += mz.doubleValue();
            if (zy != null) agg[2] += zy.doubleValue();
        }
        List<Object> days = new ArrayList<>();
        List<Object> outpatient = new ArrayList<>();
        List<Object> revenueMz = new ArrayList<>();
        List<Object> revenueZy = new ArrayList<>();
        for (Map.Entry<String, double[]> e : byDate.entrySet()) {
            days.add(shortDay(e.getKey()));
            outpatient.add(Math.round(e.getValue()[0]));
            revenueMz.add(toWan(e.getValue()[1]));
            revenueZy.add(toWan(e.getValue()[2]));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("outpatient", outpatient);
        out.put("revenueMz", revenueMz);
        out.put("revenueZy", revenueZy);
        return out;
    }

    /** 区间出入院趋势（逐日），与 buildDailySeries 共用 range_daily 的行 */
    private Map<String, Object> buildAdmission(List<Map<String, Object>> raw, String community,
                                               List<String> allowed) {
        Map<String, double[]> byDate = new TreeMap<>();
        if (raw == null) return unavailable("dates", "admitted", "discharged");
        for (Map<String, Object> row : filterRows(raw, community, allowed)) {
            Object day = row.get("日期");
            if (day == null) continue;
            double[] agg = byDate.computeIfAbsent(day.toString(), k -> new double[2]);
            Number ry = num(row.get("入院人数"));
            Number cy = num(row.get("出院人数"));
            if (ry != null) agg[0] += ry.doubleValue();
            if (cy != null) agg[1] += cy.doubleValue();
        }
        List<Object> dates = new ArrayList<>();
        List<Object> admitted = new ArrayList<>();
        List<Object> discharged = new ArrayList<>();
        for (Map.Entry<String, double[]> e : byDate.entrySet()) {
            dates.add(shortDay(e.getKey()));
            admitted.add(Math.round(e.getValue()[0]));
            discharged.add(Math.round(e.getValue()[1]));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dates", dates);
        out.put("admitted", admitted);
        out.put("discharged", discharged);
        return out;
    }

    /**
     * 分时门诊：把区间内各日**同一时段**的门诊人次合计，得到「一天里的作息曲线」。
     * 区间为单日时它就是当日 24 小时曲线；跨多日时它是各日同时段之和（不是均值），
     * 前端标题需据此区分「当日分时」与「区间分时」。
     */
    private Map<String, Object> buildHourly(List<Map<String, Object>> raw, String community,
                                            List<String> allowed) {
        Map<String, Double> byHour = new TreeMap<>();
        if (raw == null) return unavailable("hours", "outpatient");
        for (Map<String, Object> row : filterRows(raw, community, allowed)) {
            Object hr = row.get("小时");
            if (hr == null) continue;
            Number rc = num(row.get("门诊人次"));
            if (rc == null) continue;
            byHour.merge(hr.toString(), rc.doubleValue(), Double::sum);
        }
        List<Object> hours = new ArrayList<>();
        List<Object> outpatient = new ArrayList<>();
        for (Map.Entry<String, Double> e : byHour.entrySet()) {
            hours.add(e.getKey() + "时");
            outpatient.add(Math.round(e.getValue()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hours", hours);
        out.put("outpatient", outpatient);
        return out;
    }

    /** 区间费用分类构成：聚合取 TOP7 + 其他（万元） */
    private Map<String, Object> buildCategory(List<Map<String, Object>> raw, String community,
                                              List<String> allowed) {
        Map<String, Double> byCat = new LinkedHashMap<>();
        if (raw == null) return unavailable("names", "revenues");
        for (Map<String, Object> row : filterRows(raw, community, allowed)) {
            Object cat = row.get("分类");
            if (cat == null) continue;
            Number amt = num(row.get("金额"));
            if (amt == null) continue;
            byCat.merge(cat.toString(), amt.doubleValue(), Double::sum);
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(byCat.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<Object> names = new ArrayList<>();
        List<Object> revenues = new ArrayList<>();
        int topN = Math.min(7, sorted.size());
        double otherSum = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (i < topN) {
                names.add(sorted.get(i).getKey());
                revenues.add(toWan(sorted.get(i).getValue()));
            } else {
                otherSum += sorted.get(i).getValue();
            }
        }
        if (sorted.size() > topN) {
            names.add("其他");
            revenues.add(toWan(otherSum));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("names", names);
        out.put("revenues", revenues);
        return out;
    }

    /**
     * 各社区收入·毛利对比：收入取 range_kpi、毛利取 range_margin，按社区对齐。
     * 受限用户只出现权限内社区；两表社区集合可能不完全一致，缺失侧按 0 计。
     */
    private Map<String, Object> buildCommunity(List<Map<String, Object>> kpiRows,
                                               List<Map<String, Object>> marginRows,
                                               List<String> allowed) {
        if (kpiRows == null && marginRows == null) return unavailable("names", "revenue", "gross");
        Map<String, Double> revenue = new java.util.HashMap<>();
        for (Map<String, Object> row : filterRows(kpiRows == null ? List.of() : kpiRows, "ALL", allowed)) {
            Object sq = row.get("社区");
            if (sq == null) continue;
            Number v = num(row.get("收入合计"));
            revenue.merge(sq.toString().trim(), v == null ? 0 : v.doubleValue(), Double::sum);
        }
        Map<String, Double> gross = new java.util.HashMap<>();
        for (Map<String, Object> row : filterRows(marginRows == null ? List.of() : marginRows, "ALL", allowed)) {
            Object sq = row.get("社区");
            if (sq == null) continue;
            Number v = num(row.get("毛利额"));
            gross.merge(sq.toString().trim(), v == null ? 0 : v.doubleValue(), Double::sum);
        }
        List<String> names = new ArrayList<>(new java.util.TreeSet<>(revenue.keySet()));
        names.addAll(gross.keySet());
        names = new ArrayList<>(new java.util.TreeSet<>(names));

        List<Object> revenueOut = new ArrayList<>();
        List<Object> grossOut = new ArrayList<>();
        for (String n : names) {
            revenueOut.add(toWan(revenue.getOrDefault(n, 0d)));
            grossOut.add(toWan(gross.getOrDefault(n, 0d)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("names", names);
        out.put("revenue", revenueOut);
        out.put("gross", grossOut);
        return out;
    }

    // ============ 小工具 ============

    /** 规范化社区参数：空/ALL→全部；未知社区→回退全部 */
    private String normalizeCommunity(String communityRaw, List<String> communities) {
        if (communityRaw == null || communityRaw.trim().isEmpty()
            || "ALL".equalsIgnoreCase(communityRaw.trim())) {
            return "ALL";
        }
        String c = communityRaw.trim();
        for (String known : communities) {
            if (known.equalsIgnoreCase(c)) return known;
        }
        return "ALL";
    }

    private boolean matchCommunity(Map<String, Object> row, String community) {
        if ("ALL".equals(community)) return true;
        Object sq = row.get("社区");
        return sq != null && community.equals(sq.toString().trim());
    }

    private List<Map<String, Object>> filterRows(List<Map<String, Object>> rows, String community,
                                                 List<String> allowed) {
        // 空列表 = 无可见社区 → 任何行都不可见（区别于 null = 不限）
        if (allowed != null && allowed.isEmpty()) return List.of();
        boolean noLimit = allowed == null;
        if ("ALL".equals(community) && noLimit) return rows;
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object sq = row.get("社区");
            if (!noLimit && (sq == null || !allowed.contains(sq.toString().trim()))) continue;
            if ("ALL".equals(community) || matchCommunity(row, community)) filtered.add(row);
        }
        return filtered;
    }

    /** 把候选列的跨行求和放进结果（值取整型） */
    private void putSum(Map<String, Object> out, String key, List<Map<String, Object>> rows,
                        String... candidates) {
        out.put(key, Math.round(sumColumn(rows, candidates)));
    }

    /** 对每个行取第一个命中的候选列值并求和 */
    private double sumColumn(List<Map<String, Object>> rows, String... candidates) {
        double sum = 0;
        for (Map<String, Object> row : rows) {
            for (String c : candidates) {
                Object v = row.get(c);
                if (v != null) {
                    Number n = num(v);
                    if (n != null) sum += n.doubleValue();
                    break;
                }
            }
        }
        return sum;
    }

    @SuppressWarnings("unchecked")
    private Number num(Object v) {
        if (v instanceof Number) return (Number) v;
        if (v != null) {
            try {
                return Double.parseDouble(v.toString().trim());
            } catch (NumberFormatException ignore) { }
        }
        return null;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 元 -> 万元（保留2位小数），用于图表 */
    private Object toWan(Object v) {
        Number n = num(v);
        if (n == null) return null;
        return round2(n.doubleValue() / 10000.0);
    }

    private Object toWan(double v) {
        return round2(v / 10000.0);
    }

    /** '2026-09-09' -> '09-09'，图表X轴用短日期 */
    private String shortDay(String day) {
        if (day == null) return "";
        if (day.length() >= 10) return day.substring(5, 10);
        return day;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ============ 数据结构 ============

    private static class MetricDef {
        String id;
        String name;
        String description;
        /** 指标文件名（含 .sql），管理页回显与「保存时确认改的是哪个文件」用。 */
        String file;
        int intervalMinutes;
        String sql;
        /** SQL 里 :name 形式声明的参数名（按 YAML params 顺序）。空 = 无参数指标。 */
        List<String> paramNames = List.of();
        /** YAML 头 {@code panel-titles} 里配置的面板标题（槽位key → 文字），只保留该指标负责的槽位。 */
        Map<String, String> panelTitles = Map.of();

        boolean isRangeDriven() {
            return !paramNames.isEmpty();
        }
    }

    private static class MetricSnapshot {
        LocalDateTime fetchedAt;
        List<Map<String, Object>> rows = List.of();
        boolean success;
        String lastError;
        /** 最近一次刷新失败、当前展示的是上一次的好数据，前端据此打「数据延迟」标。 */
        boolean stale;
        /** 失败后的退避截止时刻：在此之前不再打库，避免 HIS 抖动时每 60s 一次重试风暴。 */
        LocalDateTime nextRetryAt;
    }

    /**
     * 访问序 LRU，只淘汰「区间快照」（键里含 '|' 的），无参数的基础指标永不淘汰。
     * 所有访问都在调用方持有的 synchronized(cache) 块内，这里不额外加锁。
     */
    private static class RangeAwareCache extends LinkedHashMap<String, MetricSnapshot> {
        RangeAwareCache() {
            super(64, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MetricSnapshot> eldest) {
            return size() > MAX_RANGE_SNAPSHOTS && eldest.getKey().indexOf('|') >= 0;
        }
    }
}
