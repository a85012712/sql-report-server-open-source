package com.report;

import com.report.service.UserManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MustChangePasswordIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserManagementService userManagementService;

    @Test
    @WithMockUser(username = "force_user", roles = "USER")
    void businessApiBlockedUntilPasswordChanged() throws Exception {
        if (!userManagementService.userExists("force_user")) {
            userManagementService.createUser("force_user", "强制改密用户", "USER");
        } else {
            // 确保处于必须改密状态
            userManagementService.resetToTemporaryPassword("force_user");
        }

        mockMvc.perform(get("/api/reports"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MUST_CHANGE_PASSWORD"));

        // 当前用户与改密接口仍可用
        mockMvc.perform(get("/api/admin/current-user"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    @WithMockUser(username = "force_user2", roles = "USER")
    void afterChangePassword_apiAllowed() throws Exception {
        String u = "force_user2";
        if (userManagementService.userExists(u)) {
            userManagementService.deleteUser(u);
        }
        String temp = userManagementService.createUser(u, "用户2", "USER");
        // 用服务直接改密模拟用户改密完成
        userManagementService.changeOwnPassword(u, temp, "Abcdef12");

        mockMvc.perform(get("/api/reports"))
            .andExpect(status().isOk());
    }
}
