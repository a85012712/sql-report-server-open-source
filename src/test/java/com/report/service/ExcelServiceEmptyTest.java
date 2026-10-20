package com.report.service;

import com.report.model.ReportInfo.QueryResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ExcelServiceEmptyTest {

    @Test
    void exportEmptyResultDoesNotThrow() throws Exception {
        ExcelService excelService = new ExcelService();
        QueryResult result = new QueryResult();
        result.setColumns(null);
        result.setRows(null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> excelService.export("空报表", result, out));
        assertTrue(out.size() > 0);
    }

    @Test
    void exportEmptyListsOk() throws Exception {
        ExcelService excelService = new ExcelService();
        QueryResult result = new QueryResult();
        result.setColumns(Collections.emptyList());
        result.setRows(Collections.emptyList());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        excelService.export("empty", result, out);
        assertTrue(out.size() > 100);
    }
}
