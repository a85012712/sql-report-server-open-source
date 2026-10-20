package com.report.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 大屏区间解析与校验。
 *
 * <p>此前 ScreenService / 大屏链路**一个单测都没有**，而区间这套逻辑全是纯计算
 * （边界、上一等长区间、非法输入），不需要数据库就能测，是最该有单测的一层。
 */
class ScreenRangeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);

    @Test
    void 默认档位是今日且边界就是当天() {
        // 默认档是产品约定，改它必须连带改趋势图的预期（今日档下趋势只有 1 个点），
        // 所以这里钉死，不允许被无声改掉。
        assertEquals(ScreenRange.PRESET_TODAY, ScreenRange.DEFAULT_PRESET);
        ScreenRange r = ScreenRange.resolvePreset(ScreenRange.DEFAULT_PRESET, TODAY);
        assertEquals("today", r.key());
        assertEquals(TODAY, r.start());
        assertEquals(TODAY, r.end());
        assertEquals(1, r.days());
        assertTrue(r.isSingleDay());
        // 上一等长区间 = 昨天
        assertEquals(TODAY.minusDays(1), r.prevStart());
        assertEquals(TODAY.minusDays(1), r.prevEnd());
    }

    @Test
    void 未传档位时落到默认档() {
        ScreenRange r = ScreenRange.resolve(null, null, null, TODAY);
        assertEquals(ScreenRange.DEFAULT_PRESET, r.key());
    }

    @Test
    void 近30天档边界含当天且跨30天() {
        ScreenRange r = ScreenRange.resolvePreset(ScreenRange.PRESET_30D, TODAY);
        assertEquals("30d", r.key());
        assertEquals(TODAY.minusDays(29), r.start());
        assertEquals(TODAY, r.end());
        assertEquals(30, r.days());
        assertFalse(r.isSingleDay());
    }

    @Test
    void 今日档是单日且上一区间是昨天() {
        ScreenRange r = ScreenRange.resolvePreset(ScreenRange.PRESET_TODAY, TODAY);
        assertEquals(TODAY, r.start());
        assertEquals(TODAY, r.end());
        assertTrue(r.isSingleDay());
        assertTrue(r.isToday());
        assertEquals(TODAY.minusDays(1), r.prevStart());
        assertEquals(TODAY.minusDays(1), r.prevEnd());
    }

    @Test
    void 本月档从1号到今天() {
        ScreenRange r = ScreenRange.resolvePreset(ScreenRange.PRESET_THIS_MONTH, TODAY);
        assertEquals(LocalDate.of(2026, 9, 1), r.start());
        assertEquals(TODAY, r.end());
        assertEquals(22, r.days());
    }

    @Test
    void 上月档是完整自然月() {
        ScreenRange r = ScreenRange.resolvePreset(ScreenRange.PRESET_LAST_MONTH, TODAY);
        assertEquals(LocalDate.of(2026, 8, 1), r.start());
        assertEquals(LocalDate.of(2026, 8, 31), r.end());
        assertEquals(31, r.days());
        // 上月的上一等长区间应正好落在 7 月之前的 31 天
        assertEquals(31, r.days());
        assertEquals(LocalDate.of(2026, 7, 1), r.prevStart());
        assertEquals(LocalDate.of(2026, 7, 31), r.prevEnd());
    }

    @Test
    void 上一等长区间紧贴当前区间且天数相同() {
        ScreenRange r = ScreenRange.resolve(null, "2026-09-01", "2026-09-10", TODAY);
        assertEquals(10, r.days());
        assertEquals(LocalDate.of(2026, 8, 22), r.prevStart());
        assertEquals(LocalDate.of(2026, 8, 31), r.prevEnd());
        // 两段必须首尾相接、不重叠
        assertEquals(r.start().minusDays(1), r.prevEnd());
    }

    @Test
    void 绑定值全部是ISO日期字符串() {
        Map<String, String> b = ScreenRange.resolve(null, "2026-09-01", "2026-09-03", TODAY).bindings();
        assertEquals("2026-09-01", b.get("start_date"));
        assertEquals("2026-09-03", b.get("end_date"));
        // 3 天区间 → 上一等长区间是紧挨着的前 3 天
        assertEquals("2026-08-29", b.get("prev_start_date"));
        assertEquals("2026-08-31", b.get("prev_end_date"));
    }

    @Test
    void 自定义区间的键带具体日期而非档位名() {
        ScreenRange r = ScreenRange.resolve(null, "2026-09-01", "2026-09-05", TODAY);
        assertEquals("2026-09-01~2026-09-05", r.key());
        assertNull(r.preset());
        assertTrue(r.label().contains("~"));
    }

    @Test
    void 显式起止优先于档位名() {
        ScreenRange r = ScreenRange.resolve("30d", "2026-09-01", "2026-09-02", TODAY);
        assertEquals(2, r.days());
        assertEquals("2026-09-01~2026-09-02", r.key());
    }

    @Test
    void 倒挂区间被拒绝() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ScreenRange.resolve(null, "2026-09-20", "2026-09-10", TODAY));
        assertTrue(e.getMessage().contains("起始日期不能晚于结束日期"));
    }

    @Test
    void 只给一头被拒绝而不是静默补齐() {
        assertThrows(IllegalArgumentException.class,
            () -> ScreenRange.resolve(null, "2026-09-01", null, TODAY));
        assertThrows(IllegalArgumentException.class,
            () -> ScreenRange.resolve(null, null, "2026-09-10", TODAY));
    }

    @Test
    void 超过最大跨度被拒绝() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ScreenRange.resolve(null, "2020-01-01", "2026-09-22", TODAY));
        assertTrue(e.getMessage().contains("92"));
    }

    @Test
    void 恰好最大跨度允许() {
        ScreenRange r = ScreenRange.resolve(null, "2026-06-23", "2026-09-22", TODAY);
        assertEquals(ScreenRange.MAX_SPAN_DAYS, r.days());
    }

    @Test
    void 日期格式错误给出可读原因() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ScreenRange.resolve(null, "2026/09/01", "2026-09-02", TODAY));
        assertTrue(e.getMessage().contains("yyyy-MM-dd"));
    }

    @Test
    void 未知档位被拒绝而不是回退默认() {
        // 静默回退会让用户以为自己选的区间生效了，大屏上这是最坏失败方式
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ScreenRange.resolve("notAPreset", null, null, TODAY));
        assertTrue(e.getMessage().contains("未知的时间区间"));
    }

    @Test
    void 空白参数等同于未提供_走默认档() {
        ScreenRange r = ScreenRange.resolve("  ", "   ", "", TODAY);
        assertEquals(ScreenRange.DEFAULT_PRESET, r.key());
    }
}
