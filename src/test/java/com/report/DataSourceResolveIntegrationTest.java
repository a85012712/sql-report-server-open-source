package com.report;

import com.report.service.DataSourceService;
import com.report.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataSourceResolveIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ReportService reportService;
    @Autowired DataSourceService dataSourceService;

    @Value("${report.scripts-dir}")
    String scriptsDir;

    private static final String RID = "it_ds_report";

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Path.of(scriptsDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String content = "---\n"
            + "name: DS测试\n"
            + "dataSourceId: not_exists_ds\n"
            + "params: []\n"
            + "---\n"
            + "SELECT 1 AS N FROM DUAL\n";
        Files.writeString(dir.resolve(RID + ".sql"), content, StandardCharsets.UTF_8);
        reportService.loadReports();
    }

    @Test
    void getDataSource_unknownId_throws() {
        assertThrows(IllegalArgumentException.class, () -> dataSourceService.getDataSource("no_such_pool"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void query_withUnknownDataSourceId_returnsError() throws Exception {
        mockMvc.perform(post("/api/report/" + RID + "/query")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"params\":{},\"page\":1,\"page_size\":10}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
    }
}
