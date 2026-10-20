package com.report;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SqlImportIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void previewSql_ok() throws Exception {
        mockMvc.perform(post("/api/admin/sql/preview")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"预览测试\",\"sql\":\"SELECT 1 AS X FROM DUAL WHERE A = :start_date\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.convertedSql").exists());
    }

    @Test
    @WithMockUser(username = "u1", roles = "USER")
    void previewSql_userForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/sql/preview")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\":\"SELECT 1 FROM DUAL\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void testSql_selectOk() throws Exception {
        mockMvc.perform(post("/api/admin/sql/test")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\":\"SELECT 1 AS N FROM DUAL\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.columns").isArray());
    }
}
