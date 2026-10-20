package com.report.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 操作审计的落盘与检索，按天一个 append-only 的 JSON Lines 文件。
 *
 * <p>为什么要单独存，而不是继续混在运行日志里：审计要回答的是
 * 「工号 X 在什么时间查了哪张报表、导没导过数据」，而运行日志是给人看的文本流，
 * 既查不动也保不住，原先 {@code clean-history-on-start: true} 让每次重启都把归档日志
 * 全删掉，「保存 30 天」在纸面上就已经被破坏了。
 *
 * <p>选 JSONL 而不是一个大 JSON 数组，是为了绕开本项目已经踩过两次的整类问题：
 * 读-改-写整个文件的丢更新与半写损坏。审计只有追加，于是
 * <ul>
 *   <li>写 = 单行 append，由 {@link #writeLock} 串行化，不存在覆盖；</li>
 *   <li>断电最坏只丢最后半行，读取时跳过坏行即可，不会像 JSON 数组那样整档报废；</li>
 *   <li>留存 = 删过期日文件，不需要重写任何保留数据。</li>
 * </ul>
 *
 * <p>注意：绝不在这里落报表参数值。参数里会出现身份证号、患者姓名
 * （{@code id_card} / {@code person_name}），把「谁查了哪张报表」写成
 * 「谁用身份证号 1401… 查了明细」等于把 PII 复制一份进留存 30 天的审计档，
 * 而审计页的可见范围（{@code logs.view}）比患者数据的可见范围大得多。
 * 这里只记报表标识与行数；具体查了哪些值去问 HIS 自己的日志。
 */
@Component
public class AuditStore {

    private static final Logger log = LoggerFactory.getLogger(AuditStore.class);
    private static final String PREFIX = "audit-";
    private static final String SUFFIX = ".jsonl";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单次检索最多读多少条（命中更多时提示收窄条件，而不是把 30 天全读进内存）。 */
    public static final int MAX_SCAN_ROWS = 50_000;

    private final ObjectMapper objectMapper;
    private final Object writeLock = new Object();


    /** 写入失败次数。审计是旁路，不能因为磁盘满而让业务失败，但静默失败等于没有审计。 */
    private final AtomicLong writeFailures = new AtomicLong();

    @Value("${app.audit.dir:}")
    private String configuredDir;

    /** 与其他运行态文件同源：未显式配置时落在 users.json 同级目录下的 audit/ 子目录。 */
    @Value("${app.users.data-file:}")
    private String usersDataFile;

    @Value("${app.audit.retention-days:30}")
    private int retentionDays;

    /**
     * 审计白名单：同时命中「用户」与「动作」两份名单的记录才不落台账。
     *
     * <p>刻意要求两个条件同时成立，而不是只配一份名单：
     * 只按用户免记，等于给那个账号开一条无痕迹通道，超管查患者数据、给别人重置口令、
     * 删账号都不留痕，这是医疗数据合规检查最容易点名的地方；
     * 只按动作免记，又会把所有工号的同类操作一起抹掉。
     *
     * <p>默认只挡 admin 的「看审计 / 登录成功 / 登出」这类巡检噪声；
     * {@code query}、{@code export}、各类写操作与 {@code loginFailed}
     * （超管账号被爆破的信号，恰恰不是噪声）都不在名单内，照记。
     */
    @Value("${app.audit.exclude-users:}")
    private String excludeUsersRaw;

    @Value("${app.audit.exclude-actions:}")
    private String excludeActionsRaw;

    private Set<String> excludeUsers = Set.of();
    private Set<String> excludeActions = Set.of();

    /** 被白名单挡掉的条数，让「台账里为什么没有它」可核对，而不是靠人记住规则。 */
    private final AtomicLong suppressed = new AtomicLong();

    public AuditStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        excludeUsers = parseList(excludeUsersRaw);
        excludeActions = parseList(excludeActionsRaw);
        Path dir = dir();
        try {
            Files.createDirectories(dir);
            int removed = purgeExpired();
            log.info("操作审计存储就绪: {}（留存 {} 天{}）", dir.toAbsolutePath(), retentionDays,
                removed > 0 ? "，清理过期文件 " + removed + " 个" : "");
            if (!excludeUsers.isEmpty() && !excludeActions.isEmpty()) {
                log.warn("审计白名单已启用：用户 {} 的动作 {} 不落台账；其余动作（查询/导出/写操作/登录失败）照记",
                    excludeUsers, excludeActions);
            }
        } catch (IOException e) {
            log.error("创建审计目录失败: {}", dir.toAbsolutePath(), e);
        }
    }

    private static Set<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return java.util.Arrays.stream(raw.split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Path dir() {
        if (configuredDir != null && !configuredDir.isBlank()) return Paths.get(configuredDir);
        if (usersDataFile != null && !usersDataFile.isEmpty()) {
            Path parent = Paths.get(usersDataFile).getParent();
            if (parent != null) return parent.resolve("audit");
        }
        String appHome = System.getProperty("APP_HOME", System.getProperty("user.dir"));
        return Paths.get(appHome, "data", "audit");
    }

    public int getRetentionDays() { return retentionDays; }

    public long getWriteFailures() { return writeFailures.get(); }

    /** 被白名单挡掉的条数（本进程内）。 */
    public long getSuppressed() { return suppressed.get(); }

    /** 白名单是否真的在生效（两份名单都非空才算）。 */
    public boolean isExclusionActive() {
        return !excludeUsers.isEmpty() && !excludeActions.isEmpty();
    }

    /** 给人看的口径说明，如 {@code admin 的 auditView/loginSuccess/logout 不记}；未启用时返回空串。 */
    public String exclusionNote() {
        if (!isExclusionActive()) return "";
        return String.join("/", excludeActions) + " 类动作对 " + String.join("/", excludeUsers) + " 不记录";
    }

    /**
     * 是否命中白名单。用户与动作必须同时命中，见 {@link #excludeUsersRaw} 上那段说明：
     * 只按用户免记会给高权限账号开出无痕迹通道。
     */
    private boolean isExcluded(Map<String, Object> record) {
        if (!isExclusionActive()) return false;
        Object operator = record.get("operator");
        Object action = record.get("action");
        return operator != null && action != null
            && excludeUsers.contains(String.valueOf(operator).trim())
            && excludeActions.contains(String.valueOf(action).trim());
    }

    private Path fileFor(LocalDate date) {
        return dir().resolve(PREFIX + date + SUFFIX);
    }

    /**
     * 追加一条审计记录。
     *
     * <p>写失败不往上抛：审计挂在业务主路径上，不能因为磁盘满让一次报表查询失败。
     * 但也不会无声无息，计数累加（供接口与健康检查读取），且首次与每 20 次打一条 ERROR。
     *
     * <p>白名单在这里生效，而不是在各个调用点：落台账的路径有三条
     * （{@code AuditService.audit}、{@code AuditLineAppender} 收的旧格式文本行、
     * 审计页自己记的 {@code auditView}），散在调用点上过滤必然漏。
     */
    public void append(Map<String, Object> record) {
        if (isExcluded(record)) {
            suppressed.incrementAndGet();
            return;
        }
        synchronized (writeLock) {
            String line;
            LocalDate date;
            try {
                Object ts = record.get("ts");
                long millis = ts instanceof Number n ? n.longValue() : System.currentTimeMillis();
                date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
                line = objectMapper.writeValueAsString(record) + "\n";
            } catch (Exception e) {
                noteWriteFailure("序列化", e);
                return;
            }
            try {
                Path file = fileFor(date);
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                healTornTail(file);
                Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            } catch (IOException e) {
                noteWriteFailure("落盘 " + date, e);
            }
        }
    }

    /**
     * 追加前修复「尾行没有换行符」的档。
     *
     * <p>断电/强杀会让正在写的那行只落一半且不带结尾换行；JSONL 按行切，于是下一条
     * 完整记录会被粘在同一行上，损失不止那半行，其后第一条也一起报废。
     * 这不是理论：本项目单测里先写一条残行、再写正常记录，第二条就查不出来了，
     * 而现象表现为「审计漏记了一条」，最难查。这里在追加前补换行，把损失限制回撕裂那一行。
     *
     * <p>每次追加都检查（读 1 个字节）而不是「每进程查一次」：审计只记关键操作，
     * 这一字节的开销可以忽略，换来的是无条件正确的语义，且任何外部写入者
     * （运维手工修档、脚本追加）制造了残尾也能自愈。
     */
    private void healTornTail(Path file) throws IOException {
        long size = Files.exists(file) ? Files.size(file) : 0;
        if (size == 0) return;
        byte[] last = new byte[1];
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(file,
                StandardOpenOption.READ)) {
            ch.position(size - 1);
            if (ch.read(java.nio.ByteBuffer.wrap(last)) != 1) return;
        }
        if (last[0] != '\n') {
            Files.writeString(file, "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            log.warn("审计档 {} 尾行缺换行符（上一次进程未正常结束），已补换行以防下一条记录被粘坏",
                file.getFileName());
        }
    }

    private void noteWriteFailure(String stage, Exception e) {
        long n = writeFailures.incrementAndGet();
        if (n == 1 || n % 20 == 0) {
            log.error("[审计落盘] {}失败（累计 {} 次）， 审计记录正在丢失，请检查磁盘与目录权限: {}",
                stage, n, e.getMessage());
        }
    }

    /**
     * 按条件检索（时间倒序 + 分页）。
     *
     * <p>只读落在请求区间内的日文件；单行解析失败（断电留下的半行）跳过并计数。
     */
    public Result query(String user, String action, String reportId, String dateFrom, String dateTo,
                        String keyword, Integer status, int page, int pageSize) {
        LocalDate fallbackFrom = LocalDate.now().minusDays(Math.max(0, retentionDays - 1));
        LocalDate from = parseDate(dateFrom, fallbackFrom);
        LocalDate to = parseDate(dateTo, LocalDate.now());
        if (from.isAfter(to)) { LocalDate t = from; from = to; to = t; }
        // 留存期是硬边界：请求更早的区间也只看得到还留着的那些
        LocalDate oldest = LocalDate.now().minusDays(Math.max(0, retentionDays - 1));
        if (from.isBefore(oldest)) from = oldest;

        List<Path> files = new ArrayList<>();
        for (LocalDate d = to; !d.isBefore(from); d = d.minusDays(1)) {
            Path p = fileFor(d);
            if (Files.exists(p)) files.add(p);
        }

        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim().toLowerCase();
        String u = blankToNull(user);
        String a = blankToNull(action);
        String r = blankToNull(reportId);
        int skip = Math.max(0, (page - 1) * pageSize);

        List<Map<String, Object>> pageRows = new ArrayList<>();
        long matched = 0;
        long malformed = 0;
        boolean capped = false;

        outer:
        for (Path file : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("审计文件读取失败: {}", file, e);
                continue;
            }
            for (int i = lines.size() - 1; i >= 0; i--) {   // 文件内也倒序：新记录在尾部
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                Map<?, ?> parsed;
                try {
                    parsed = objectMapper.readValue(line, Map.class);
                } catch (Exception e) {
                    malformed++;
                    continue;
                }
                if (!matches(parsed, u, a, r, status, kw)) continue;
                matched++;
                if (matched > MAX_SCAN_ROWS) { capped = true; break outer; }
                if (matched > skip && pageRows.size() < pageSize) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    parsed.forEach((k, v) -> row.put(String.valueOf(k), v));
                    pageRows.add(row);
                }
                // 注意：凑满一页不能提前 break：matched 是给分页控件当「共 N 条」用的，
                //   提前退出会让总数偏小、后面的页翻不到。区间受留存期与 MAX_SCAN_ROWS
                //   双重封顶，全量扫是毫秒级，换总数正确性值得。
            }
        }

        return new Result(pageRows, matched, page, pageSize, from.toString(), to.toString(),
            capped, malformed, files.size(), writeFailures.get());
    }

    private boolean matches(Map<?, ?> rec, String user, String action, String reportId,
                            Integer status, String keyword) {
        if (user != null && !user.equals(str(rec.get("operator")))) return false;
        if (action != null && !action.equals(str(rec.get("action")))) return false;
        if (reportId != null && !reportId.equals(str(rec.get("reportId")))) return false;
        if (status != null) {
            // status: 1 = 成功，0 = 失败/被拒（被拒统一以 success=false 落盘）
            boolean ok = Boolean.TRUE.equals(rec.get("success"));
            if (status == 1 ? !ok : ok) return false;
        }
        if (keyword != null) {
            StringBuilder hay = new StringBuilder();
            for (Object v : rec.values()) hay.append(str(v)).append(' ');
            return hay.toString().toLowerCase().contains(keyword);
        }
        return true;
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static LocalDate parseDate(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 出现过的动作名（筛选下拉用）：扫现存日文件去重。 */
    public List<String> distinctActions() {
        Set<String> seen = new HashSet<>();
        for (Path p : existingFiles()) {
            for (Map<String, Object> rec : readLoose(p)) {
                Object act = rec.get("action");
                if (act != null) seen.add(String.valueOf(act));
            }
        }
        List<String> list = new ArrayList<>(seen);
        list.sort(Comparator.naturalOrder());
        return list;
    }

    /** 出现过的操作人工号（筛选下拉用）。 */
    public List<String> distinctOperators() {
        Set<String> seen = new HashSet<>();
        for (Path p : existingFiles()) {
            for (Map<String, Object> rec : readLoose(p)) {
                Object o = rec.get("operator");
                if (o != null && !String.valueOf(o).isBlank()) seen.add(String.valueOf(o));
            }
        }
        List<String> list = new ArrayList<>(seen);
        list.sort(Comparator.naturalOrder());
        return list;
    }

    /** 有记录的日期（倒序），审计页日期快选用。 */
    public List<String> availableDates() {
        List<String> out = new ArrayList<>();
        for (Path p : existingFiles()) {
            LocalDate d = dateOf(p);
            if (d != null) out.add(d.format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
        out.sort(Comparator.reverseOrder());
        return out;
    }

    /**
     * 每天凌晨清理过期审计文件。
     *
     * <p>留存在这一套里自己管，不依赖 logback：应用日志按大小滚动、审计按天分文件，
     * 两者节奏不同，混用一套参数必然出现「一个满足了另一个没满足」。
     */
    @org.springframework.scheduling.annotation.Scheduled(
        cron = "0 10 3 * * *", zone = "Asia/Shanghai")
    public void scheduledPurge() {
        int removed = purgeExpired();
        if (removed > 0) log.info("审计留存清理: 删除 {} 个超过 {} 天的审计文件", removed, retentionDays);
    }

    /**
     * 删除超出留存期的日文件。
     *
     * <p>与运行日志的 30 天是两套独立机制：{@code logback.max-history} 管归档日志，
     * 这里管审计。审计绝不能跟着应用包走，换一次 jar 就把历史操作删了不叫审计。
     *
     * @return 实际删除的文件数
     */
    public int purgeExpired() {
        if (retentionDays <= 0) return 0;   // <=0 表示永不过期
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        int deleted = 0;
        for (Path p : existingFiles()) {
            LocalDate d = dateOf(p);
            if (d == null || !d.isBefore(cutoff)) continue;
            try {
                Files.deleteIfExists(p);
                deleted++;
            } catch (IOException e) {
                log.warn("删除过期审计文件失败（可能仍被句柄占用，下次再试）: {}", p, e);
            }
        }
        return deleted;
    }

    private LocalDate dateOf(Path p) {
        String name = p.getFileName().toString();
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return null;
        try {
            return LocalDate.parse(name.substring(PREFIX.length(), name.length() - SUFFIX.length()));
        } catch (Exception e) {
            return null;
        }
    }

    private List<Path> existingFiles() {
        Path dir = dir();
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(out::add);
        } catch (IOException e) {
            log.warn("列举审计目录失败: {}", dir, e);
            return List.of();
        }
        out.removeIf(p -> dateOf(p) == null);
        out.sort(Comparator.comparing(this::dateOf).reversed());
        return out;
    }

    /** 容错读取：坏行跳过，读不到文件返回空。 */
    private List<Map<String, Object>> readLoose(Path file) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = objectMapper.readValue(line, Map.class);
                    rows.add(m);
                } catch (Exception ignore) {
                    // 半行损坏：跳过
                }
            }
        } catch (IOException ignore) {
            // 文件可能正被清理，跳过
        }
        return rows;
    }

    /** 一次检索的结果。 */
    public record Result(List<Map<String, Object>> rows, long matched, int page, int pageSize,
                         String dateFrom, String dateTo, boolean capped, long malformed,
                         int filesScanned, long writeFailures) {

        public int totalPages() {
            return (int) Math.max(1, Math.ceil((double) matched / Math.max(1, pageSize)));
        }

    }

    /** 供 appender 复用：把毫秒时间戳格式化成展示用时间。 */
    public static String formatTs(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime().format(TS);
    }
}
