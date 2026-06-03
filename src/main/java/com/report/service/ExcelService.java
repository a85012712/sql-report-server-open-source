package com.report.service;

import com.report.model.ReportInfo.QueryResult;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.io.IOException;
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

            CellStyle dataStyle = wb.createCellStyle();
            dataStyle.setBorderBottom(BorderStyle.THIN);
            dataStyle.setBorderTop(BorderStyle.THIN);
            dataStyle.setBorderLeft(BorderStyle.THIN);
            dataStyle.setBorderRight(BorderStyle.THIN);

            List<String> columns = result.getColumns();
            if (columns == null || columns.isEmpty()) {
                log.warn("导出Excel: 列信息为空, reportName={}", reportName);
                return;
            }
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns.get(i));
                cell.setCellStyle(headerStyle);
            }

            List<List<String>> rows = result.getRows();
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> rowData = rows.get(r);
                for (int c = 0; c < rowData.size(); c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(rowData.get(c));
                    cell.setCellStyle(dataStyle);
                }
            }

            for (int i = 0; i < columns.size(); i++) {
                int maxWidth = getStringDisplayWidth(columns.get(i));
                for (List<String> row : rows.subList(0, Math.min(rows.size(), 100))) {
                    if (i < row.size()) {
                        maxWidth = Math.max(maxWidth, getStringDisplayWidth(row.get(i)));
                    }
                }
                sheet.setColumnWidth(i, Math.min((maxWidth + 4) * 256, 15000));
            }

            sheet.createFreezePane(0, 1);

            wb.write(outputStream);

            long duration = System.currentTimeMillis() - startTime;
            log.info("[AUDIT] action=exportExcel, reportName={}, rowCount={}, duration={}ms", reportName, rowCount, duration);
        }
    }

    private String truncateSheetName(String name) {
        return name.length() > 31 ? name.substring(0, 31) : name;
    }

    /**
     * 计算字符串的显示宽度（中文字符占2个宽度，英文字符占1个宽度）
     */
    private int getStringDisplayWidth(String str) {
        if (str == null) return 0;
        int width = 0;
        for (char c : str.toCharArray()) {
            // 中文字符、全角字符占2个宽度
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
