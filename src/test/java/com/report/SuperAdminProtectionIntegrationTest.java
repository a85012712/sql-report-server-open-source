package com.report;

import com.report.service.RolePermissionService;
import com.report.service.UserManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 超级管理员保护 / CSRF 强制 / 监控端点访问 集成测试。
 * 超级管理员 = 内置 admin（application-test.yml 中 ADMIN_USERNAME=admin），不可变更；
 * 后续创建的角色为 ADMIN 的普通管理员可正常变更。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SuperAdminProtectionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserManagementService userManagementService;

    private static final String SUPER = "admin";

    /** 代表「普通管理员」的操作者账号（非内置超管，可被变更） */
    private static final String REGULAR_ADMIN = "boss1";

    /**
     * 用例统一以普通管理员 boss1 的身份发起请求。
     *
     * <p>注意：本系统的鉴权走**权限点模型**（{@code @PreAuthorize("@perm.has('users.manage')")}，
     * 由 {@link RolePermissionService#permissionsOf} 依据 users.json 中的角色记录判定），
     * 而 {@code @WithMockUser} 只往 SecurityContext 里塞一个 ROLE_ADMIN authority、
     * **不会**产生用户记录。若 boss1 不是真实账号，权限判定会回退为仅 dashboard.view →
     * 接口一律返回 403，使断言（期望 400/200）失败。
     *
     * <p>故这里先把 boss1 建成真实的「系统管理员」账号，让权限判定走真实链路；
     * 断言本身不做任何放宽。
     *
     * <p>另需清掉首登强制改密标记：{@code createUser} 会把 {@code mustChangePassword}
     * 置为 true，而 {@link com.report.config.MustChangePasswordFilter} 会对该类账号的
     * 业务 API 直接返回 403（{@code MUST_CHANGE_PASSWORD}），同样会淹没断言。
     */
    @BeforeEach
    void ensureRegularAdminAccount() {
        if (!userManagementService.userExists(REGULAR_ADMIN)) {
            userManagementService.createUser(REGULAR_ADMIN, "测试普通管理员", "ADMIN",
                RolePermissionService.R_SYS_ADMIN, null, null);
        }
        // 测试数据目录（${java.io.tmpdir}/report-it）跨运行持久化，故此处按状态而非按存在性补齐：
        // 置为正式密码并解除首登强制改密（forceChange=false）
        if (userManagementService.mustChangePassword(REGULAR_ADMIN)) {
            userManagementService.changePassword(REGULAR_ADMIN, "Boss1Test2026", false);
        }
    }

    @Test
    @WithMockUser(username = SUPER, roles = "ADMIN")
    void superAdmin_cannotChangeOwnRole() throws Exception {
        mockMvc.perform(put("/api/admin/users/" + SUPER + "/role").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"USER\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("不能修改当前登录用户自己的角色"));
    }

    @Test
    @WithMockUser(username = REGULAR_ADMIN, roles = "ADMIN")
    void superAdmin_cannotBeRoleChangedByOtherAdmin() throws Exception {
        mockMvc.perform(put("/api/admin/users/" + SUPER + "/role").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"USER\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("超级管理员账号不可变更"));
    }

    @Test
    @WithMockUser(username = REGULAR_ADMIN, roles = "ADMIN")
    void superAdmin_cannotResetPasswordOrDelete() throws Exception {
        mockMvc.perform(put("/api/admin/users/" + SUPER + "/password").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"NewPass1\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("超级管理员账号不可变更"));

        mockMvc.perform(put("/api/admin/users/" + SUPER + "/reset-password").with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("超级管理员账号不可变更"));

        mockMvc.perform(delete("/api/admin/users/" + SUPER).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("不能删除管理员用户"));
    }

    @Test
    @WithMockUser(username = REGULAR_ADMIN, roles = "ADMIN")
    void regularAdmin_canBeChanged() throws Exception {
        // 每次运行用唯一工号，避免 users.json 持久化导致的“用户名已存在”
        String uid = "9" + Long.toString(System.currentTimeMillis(), 36);

        mockMvc.perform(post("/api/admin/users").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + uid + "\",\"name\":\"测试管理员\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isOk());

        // 普通管理员可以被降级为 USER（普通管理员可变更）
        mockMvc.perform(put("/api/admin/users/" + uid + "/role").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"USER\"}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = REGULAR_ADMIN, roles = "ADMIN")
    void writeApi_withoutCsrfToken_rejected() throws Exception {
        mockMvc.perform(post("/api/reports/refresh"))
            .andExpect(status().isForbidden());
    }

    @Test
    void prometheus_anonymous_isBlocked() throws Exception {
        // /actuator/prometheus 已从 permitAll 收回：它会外泄 JVM 内存、Hikari 池名、
        // 各 URI 调用次数与 report.query.rows，内网任何人无需登录即可读走。
        // 现在落到 /actuator/** 的 hasRole("ADMIN")，未认证只会被拦或跳登录，绝不再 200。
        int status = mockMvc.perform(get("/actuator/prometheus"))
            .andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertTrue(status == 401 || status == 403 || status == 302,
            "prometheus 匿名访问应被拦下, actual=" + status);
    }
}
