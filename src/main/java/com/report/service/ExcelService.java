package com.report.service;

import com.report.model.ReportInfo.QueryResult;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

@Service
public class ExcelService {

    private static final Logger log = LoggerFactory.getLogger(ExcelService.class);

    private static final int WINDOW_SIZE = 1000;

    public void export(String reportName, QueryResult result, OutputStream outputStream) throws IOException {
        long startTime = System.currentTimeMillis();
        int rowCount = result.getRows() != null ? result.getRows().size() : 0;
        log.info("开始导出Excel: {}, 数据行数: {}", reportName, rowCount);

        try (SXSSFWorkbook wb = new SXSSFWorkbook(WINDOW_SIZE)) {
            Sheet sheet = wb.createSheet(truncateSheetName(reportName));
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle dataStyle = createDataStyle(wb);

            List<String> columns = result.getColumns() != null ? result.getColumns() : List.of();
            List<List<String>> rows = result.getRows() != null ? result.getRows() : List.of();

            Row headerRow = sheet.createRow(0);
            if (columns.isEmpty()) {
                Cell emptyHeader = headerRow.createCell(0);
                emptyHeader.setCellValue("（无数据）");
                emptyHeader.setCellStyle(headerStyle);
            } else {
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(columns.get(i) != null ? columns.get(i) : "");
                    cell.setCellStyle(headerStyle);
                }
            }

            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> rowData = rows.get(r);
                if (rowData == null) continue;
                for (int c = 0; c < rowData.size(); c++) {
                    Cell cell = row.createCell(c);
                    String val = rowData.get(c);
                    cell.setCellValue(val != null ? val : "");
                    cell.setCellStyle(dataStyle);
                }
            }

            autoSizeColumns(sheet, columns, rows);
            if (result.isTruncated()) {
                /* 多社区「全部」的合并导出走的是本方法：早先只有 exportFromResultSet 追加
                 * 截断提示，于是受限账号一次导出被静默砍到 maxRows 行，拿到的 xlsx 看起来完整。 */
                appendWarningRow(wb, sheet, rows.size() + 1,
                    "⚠ 数据已截断：结果超过导出上限，未导出的部分不在本文件中，请缩小查询范围后重新导出");
            }
            sheet.createFreezePane(0, 1);
            wb.write(outputStream);

            long duration = System.currentTimeMillis() - startTime;
        }
    }

    /**
     * 流式导出的结果：实际写出的数据行数 + 是否因超上限被截断。
     */
    public static class ExportOutcome {
        private final long rows;
        private final boolean truncated;
        public ExportOutcome(long rows, boolean truncated) {
            this.rows = rows;
            this.truncated = truncated;
        }
        public long getRows() { return rows; }
        public boolean isTruncated() { return truncated; }
    }

    /**
     * 从 ResultSet 流式写出 Excel，最多 maxRows 行数据（不含表头）。
     * 超出上限时会在末尾追加一行醒目提示，并通过 {@link ExportOutcome#isTruncated()} 告知调用方。
     * @return 写出的数据行数与截断状态
     */
    public ExportOutcome exportFromResultSet(String reportName, ResultSet rs, int maxRows, OutputStream outputStream) throws Exception {
        long startTime = System.currentTimeMillis();
        log.info("开始流式导出Excel: {}", reportName);

        try (SXSSFWorkbook wb = new SXSSFWorkbook(WINDOW_SIZE)) {
            Sheet sheet = wb.createSheet(truncateSheetName(reportName != null ? reportName : "export"));
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle dataStyle = createDataStyle(wb);

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            List<String> columns = new ArrayList<>(colCount);
            Row headerRow = sheet.createRow(0);
            if (colCount <= 0) {
                Cell emptyHeader = headerRow.createCell(0);
                emptyHeader.setCellValue("（无数据）");
                emptyHeader.setCellStyle(headerStyle);
            } else {
                for (int i = 1; i <= colCount; i++) {
                    String label = meta.getColumnLabel(i);
                    columns.add(label);
                    Cell cell = headerRow.createCell(i - 1);
                    cell.setCellValue(label != null ? label : "");
                    cell.setCellStyle(headerStyle);
                }
            }

            // 采样列宽（前 500 行）
            int[] maxWidths = new int[Math.max(colCount, 1)];
            for (int i = 0; i < columns.size(); i++) {
                maxWidths[i] = getStringDisplayWidth(columns.get(i));
            }

            long count = 0;
            boolean truncated = false;
            int sampleLimit = 500;
            while (rs.next()) {
                if (count >= maxRows) {
                    truncated = true;
                    break;
                }
                Row row = sheet.createRow((int) count + 1);
                for (int c = 1; c <= colCount; c++) {
                    Object val = rs.getObject(c);
                    String s = val != null ? val.toString() : "";
                    Cell cell = row.createCell(c - 1);
                    cell.setCellValue(s);
                    cell.setCellStyle(dataStyle);
                    if (count < sampleLimit) {
                        maxWidths[c - 1] = Math.max(maxWidths[c - 1], getStringDisplayWidth(s));
                    }
                }
                count++;
            }

            // 超上限：末尾追加醒目提示行，避免用户误以为数据完整
            if (truncated) {
                appendWarningRow(wb, sheet, (int) count + 1,
                    "⚠ 数据已截断：超过 " + maxRows + " 行未导出，请缩小查询范围后重新导出");
            }

            int widthCols = Math.max(colCount, 1);
            for (int i = 0; i < widthCols; i++) {
                int w = i < maxWidths.length ? maxWidths[i] : 4;
                sheet.setColumnWidth(i, Math.min((w + 4) * 256, 15000));
            }
            sheet.createFreezePane(0, 1);
            wb.write(outputStream);

            long duration = System.currentTimeMillis() - startTime;
            if (truncated) {
                log.warn("导出数据被截断: reportName={}, maxRows={}", reportName, maxRows);
            }
            return new ExportOutcome(count, truncated);
        }
    }

    /** 末尾追加一行醒目的截断提示（两条导出路径共用，避免只有一条带提示）。 */
    private void appendWarningRow(SXSSFWorkbook wb, Sheet sheet, int rowIndex, String message) {
        CellStyle warnStyle = wb.createCellStyle();
        warnStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        warnStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font warnFont = wb.createFont();
        warnFont.setBold(true);
        warnFont.setColor(IndexedColors.RED.getIndex());
        warnStyle.setFont(warnFont);
        Row warnRow = sheet.createRow(rowIndex);
        Cell warnCell = warnRow.createCell(0);
        warnCell.setCellValue(message);
        warnCell.setCellStyle(warnStyle);
    }

    private CellStyle createHeaderStyle(Workbook wb) {
        CellStyle headerStyle = wb.createCellStyle();
        headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setBorderTop(BorderStyle.THIN);
        headerStyle.setBorderLeft(BorderStyle.THIN);
        headerStyle.setBorderRight(BorderStyle.THIN);
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerFont.setFontHeightInPoints((short) 11);
        headerStyle.setFont(headerFont);
        return headerStyle;
    }

    private CellStyle createDataStyle(Workbook wb) {
        CellStyle dataStyle = wb.createCellStyle();
        dataStyle.setBorderBottom(BorderStyle.THIN);
        dataStyle.setBorderTop(BorderStyle.THIN);
        dataStyle.setBorderLeft(BorderStyle.THIN);
        dataStyle.setBorderRight(BorderStyle.THIN);
        return dataStyle;
    }

    private void autoSizeColumns(Sheet sheet, List<String> columns, List<List<String>> rows) {
        int colCount = Math.max(columns.size(), 1);
        for (int i = 0; i < colCount; i++) {
            int maxWidth = i < columns.size() ? getStringDisplayWidth(columns.get(i)) : 4;
            int sampleEnd = Math.min(rows.size(), 500);
            for (int r = 0; r < sampleEnd; r++) {
                List<String> row = rows.get(r);
                if (row != null && i < row.size()) {
                    maxWidth = Math.max(maxWidth, getStringDisplayWidth(row.get(i)));
                }
            }
            sheet.setColumnWidth(i, Math.min((maxWidth + 4) * 256, 15000));
        }
    }

    private String truncateSheetName(String name) {
        if (name == null || name.isEmpty()) return "Sheet1";
        String cleaned = name.replaceAll("[\\\\/*?\\[\\]:]", "_");
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    private int getStringDisplayWidth(String str) {
        if (str == null) return 0;
        int width = 0;
        for (char c : str.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN ||
                (c >= '　' && c <= '鿿') ||
                (c >= '豈' && c <= '﫿') ||
                (c >= '︰' && c <= '﹏')) {
                width += 2;
            } else {
                width += 1;
            }
        }
        return width;
    }
}
