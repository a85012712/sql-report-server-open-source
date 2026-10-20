package com.report.controller;

import com.report.util.AuditStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计 CSV 的「截断留痕」单元测试（不起 Spring 上下文）。
 *
 * <p>为什么单独测这个：导出行数被 {@code EXPORT_ROW_LIMIT} 截断时，界面上那行黄色告警
 * 跟着不了被 U 盘带走的 CSV。而「共 N 条、文件里只有 M 条」恰恰是最容易被忽略、
 * 又最贵的一种错，拿一份不完整的台账去追责，比没有台账更糟。
 */
class AuditControllerCsvTest {

    private static AuditStore.Result result(int rows, long matched, boolean capped) {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (int i = 0; i < rows; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("time", "2026-09-24 10:0" + i + ":00");
            r.put("operator", "1024");
            r.put("action", "query");
            r.put("reportId", "r-" + i);
            r.put("success", true);
            list.add(r);
        }
        return new AuditStore.Result(list, matched, 1, list.size(),
            "2026-08-25", "2026-09-24", capped, 0, 30, 0);
    }

    @Test
    void truncatedExport_saysItIsNotEverything() {
        String csv = AuditController.toCsv(result(2, 5, false));

        assertTrue(csv.contains("本文件只含最近 2 条记录"), "必须写明文件里有多少条：" + csv);
        assertTrue(csv.contains("命中共 5 条"), "必须写明命中多少条：" + csv);
        assertTrue(csv.contains("这不是全部"), "必须有「这不是全部」的结论：" + csv);
        assertTrue(csv.contains("2026-08-25 ~ 2026-09-24"), "要给出区间，便于判断该缩哪：" + csv);
        // 未触达扫描上限时不要把「总数也可能偏小」塞进来，那是另一件事，别混着说
        assertFalse(csv.contains("扫描已达上限"), "未 capped 不该报扫描上限：" + csv);
    }

    @Test
    void scanCappedExport_alsoWarnsTotalMayBeUnderstated() {
        String csv = AuditController.toCsv(result(2, 3, true));

        assertTrue(csv.contains("扫描已达上限"), "capped 时必须说明总数也不可信：" + csv);
        assertTrue(csv.contains("本文件只含最近 2 条记录"), csv);
    }

    @Test
    void completeExport_staysClean() {
        String csv = AuditController.toCsv(result(2, 2, false));

        assertFalse(csv.contains("[提示]"), "导全了就不该追加提示行：" + csv);
        assertTrue(csv.startsWith("时间,操作人,动作,报表,目标工号,结果,行数,耗时ms,客户端IP,对端IP\n"),
            "表头不能动：" + csv);
        // 数据行数 = 表头 1 + 记录 2
        assertTrue(csv.chars().filter(c -> c == '\n').count() == 3,
            "完整导出应当只有 3 行，实际：\n" + csv);
    }

    @Test
    void noticeIsOneCsvCell_soExcelDoesNotSplitIt() {
        String csv = AuditController.toCsv(result(1, 9, false));
        String last = csv.substring(csv.lastIndexOf('\n') + 1).trim();

        assertTrue(last.startsWith("\"") && last.endsWith("\""),
            "提示行必须整体包在一个单元格里，否则 Excel 会把它拆成一堆列：" + last);
        // 内部不能出现未转义的双引号
        String inner = last.substring(1, last.length() - 1);
        assertFalse(inner.replace("\"\"", "").contains("\""),
            "单元格内的双引号必须成对转义：" + inner);
    }
}
