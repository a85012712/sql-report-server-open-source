package com.report.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大屏时间区间：一次解析出「当前区间」与「上一等长区间」，供 SQL 参数绑定与前端标题使用。
 *
 * <p>刻意做成不依赖 Spring 的纯值对象，方便单测直接传 today 断言边界。
 *
 * <p>口径约定：区间为闭区间 [start, end]，按「天」对齐；上一等长区间是紧挨着 start
 * 往前推同样天数的窗口，用于环比，不是自然月/自然周对比。
 */
public final class ScreenRange {

    public static final String PRESET_TODAY = "today";
    public static final String PRESET_7D = "7d";
    public static final String PRESET_30D = "30d";
    public static final String PRESET_THIS_MONTH = "thisMonth";
    public static final String PRESET_LAST_MONTH = "lastMonth";

    /**
     * 大屏默认区间。产品约定：所有面板窗口严格等于所选区间，不做任何隐式回看 ，
     * 因此默认档下三条趋势图各只有 1 个点，这是"严格统一"的代价，不是缺陷。
     */
    public static final String DEFAULT_PRESET = PRESET_TODAY;

    /** 自定义区间的最大跨度，防止在业务库上跑出把整年明细拉平的慢查询。 */
    public static final int MAX_SPAN_DAYS = 92;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final LocalDate start;
    private final LocalDate end;
    private final LocalDate prevStart;
    private final LocalDate prevEnd;
    private final String preset;   // 预设名；自定义区间为 null
    private final String label;

    private ScreenRange(LocalDate start, LocalDate end, String preset, String label) {
        this.start = start;
        this.end = end;
        this.preset = preset;
        this.label = label;
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        this.prevEnd = start.minusDays(1);
        this.prevStart = this.prevEnd.minusDays(span - 1);
    }

    /**
     * 解析区间请求。preset 与 start/end 同时出现时以显式 start/end 为准。
     *
     * @throws IllegalArgumentException 区间非法（格式错、倒挂、超最大跨度、未知预设）
     */
    public static ScreenRange resolve(String preset, String startRaw, String endRaw, LocalDate today) {
        String s = trim(startRaw);
        String e = trim(endRaw);
        if (s != null || e != null) {
            if (s == null || e == null) {
                throw new IllegalArgumentException("自定义区间需要同时提供起始与结束日期");
            }
            return custom(parseDay(s, "起始日期"), parseDay(e, "结束日期"));
        }
        // 空白档位名等同没传，浏览器/代理把 range= 发成空串是常见情形，
        // 这时应落到默认档，而不是报「未知的时间区间」。
        String p = trim(preset);
        return resolvePreset(p == null ? DEFAULT_PRESET : p, today);
    }

    static ScreenRange resolvePreset(String name, LocalDate today) {
        switch (name) {
            case PRESET_TODAY:
                return new ScreenRange(today, today, name, "今日");
            case PRESET_7D:
                return new ScreenRange(today.minusDays(6), today, name, "近7天");
            case PRESET_30D:
                return new ScreenRange(today.minusDays(29), today, name, "近30天");
            case PRESET_THIS_MONTH:
                return new ScreenRange(today.withDayOfMonth(1), today, name, "本月");
            case PRESET_LAST_MONTH: {
                LocalDate first = today.minusMonths(1).withDayOfMonth(1);
                return new ScreenRange(first, today.withDayOfMonth(1).minusDays(1), name, "上月");
            }
            default:
                throw new IllegalArgumentException("未知的时间区间: " + name);
        }
    }

    private static ScreenRange custom(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("起始日期不能晚于结束日期");
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > MAX_SPAN_DAYS) {
            throw new IllegalArgumentException("自定义区间最多 " + MAX_SPAN_DAYS + " 天（当前 " + span + " 天）");
        }
        return new ScreenRange(start, end, null, start.format(DAY) + " ~ " + end.format(DAY));
    }

    private static LocalDate parseDay(String raw, String fieldLabel) {
        try {
            return LocalDate.parse(raw, DAY);
        } catch (Exception ex) {
            throw new IllegalArgumentException(fieldLabel + "格式应为 yyyy-MM-dd（当前: " + raw + "）");
        }
    }

    private static String trim(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /** 供 SqlUtils.prepareNamed 绑定：区间 + 上一等长区间四个日期参数。 */
    public Map<String, String> bindings() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("start_date", start.format(DAY));
        m.put("end_date", end.format(DAY));
        m.put("prev_start_date", prevStart.format(DAY));
        m.put("prev_end_date", prevEnd.format(DAY));
        return m;
    }

    /** 缓存键片段。预设用名字（值域有限），自定义用具体日期。 */
    public String key() {
        return preset != null ? preset : start.format(DAY) + "~" + end.format(DAY);
    }

    public LocalDate start() { return start; }
    public LocalDate end() { return end; }
    public LocalDate prevStart() { return prevStart; }
    public LocalDate prevEnd() { return prevEnd; }
    public String preset() { return preset; }
    public String label() { return label; }

    public int days() {
        return (int) (ChronoUnit.DAYS.between(start, end) + 1);
    }

    public boolean isSingleDay() {
        return days() == 1;
    }

    /** 是否就是今天，决定分时图标题用「当日」还是「区间」。 */
    public boolean isToday() {
        return PRESET_TODAY.equals(preset);
    }

    @Override
    public String toString() {
        return "ScreenRange[" + key() + " " + start + "~" + end + "]";
    }
}
