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
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 密码策略与创建用户临时密码接口集成测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordPolicyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserManagementService userManagementService;

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void createUser_returnsTempPassword_andRejectsWeakPasswordChange() throws Exception {
        String username = "9088";
        if (userManagementService.userExists(username)) {
            userManagementService.deleteUser(username);
        }

        MvcResult created = mockMvc.perform(post("/api/admin/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"name\":\"测试员\",\"role\":\"USER\",\"groups\":[\"default\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tempPassword").isString())
            .andReturn();

        String body = created.getResponse().getContentAsString();
        assertTrue(body.contains("tempPassword"));
        assertTrue(userManagementService.mustChangePassword(username));

        // 弱密码应 400
        mockMvc.perform(put("/api/admin/users/" + username + "/password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"123456\"}"))
            .andExpect(status().isBadRequest());

        // 合规密码
        mockMvc.perform(put("/api/admin/users/" + username + "/password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"Abcdef12\"}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void changeOwnPassword_wrongOldPassword_returns400() throws Exception {
        // admin 由 SecurityConfig 管理，changeOwnPassword 会校验旧密码
        mockMvc.perform(put("/api/admin/profile/password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oldPassword\":\"wrong-old\",\"password\":\"Abcdef12\"}"))
            .andExpect(status().isBadRequest());
    }
}
