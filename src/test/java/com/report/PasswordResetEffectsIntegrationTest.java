package com.report;

import com.report.config.LoginAttemptService;
import com.report.service.RolePermissionService;
import com.report.service.UserManagementService;
import com.report.util.AuditStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员重置口令的三项收尾行为集成测试。
 *
 * <p>盯的都是「接口返回成功，但用户那边其实没好」这一类：
 * <ul>
 *   <li>重置不解登录锁定 → 报成功，用户仍登不进去，最长要再等 15 分钟；</li>
 *   <li>重置不吊销旧会话 → 口令泄露后攻击者的会话还能用满 30 分钟；</li>
 *   <li>代设口令不强制改密 → 弹窗那句「下次登录将强制修改」是假的。</li>
 * </ul>
 *
 * <p>「吊销旧会话」与「别踢操作者自己」两条必须走真实表单登录 + 真实会话才验得出来：
 * 直接 new service 调方法时 SessionRegistry 里根本不会有记录，测出来永远是假绿。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetEffectsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserManagementService userManagementService;
    @Autowired private LoginAttemptService loginAttemptService;
    @Autowired private AuditStore auditStore;

    private static final String ADMIN_PW = "AdminTest1";   // application-test.yml 的超管口令

    /** 真实登录后的会话。 */
    private record LoggedIn(MockHttpSession session) { }

    private String uniq() {
        return "rp" + Long.toString(System.nanoTime(), 36);
    }

    /** 建一个可用账号，并清掉首登强制改密标记（否则业务 API 被过滤器拦成 403）。 */
    private String makeUser(String role) {
        String uid = uniq();
        userManagementService.createUser(uid, "重置测试账号", role,
            "ADMIN".equals(role) ? RolePermissionService.R_SYS_ADMIN : RolePermissionService.R_USER,
            null, null);
        userManagementService.changePassword(uid, "ZZtest1234", false);
        return uid;
    }

    private LoggedIn login(String username, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult page = mockMvc.perform(get("/login").session(session)).andReturn();
        Matcher m = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"")
            .matcher(page.getResponse().getContentAsString());
        assertTrue(m.find(), "登录页应带 CSRF 令牌");
        mockMvc.perform(post("/login").session(session)
            .param("username", username).param("password", password).param("_csrf", m.group(1)));
        return new LoggedIn(session);
    }

    // ───────────────── 解锁定 ─────────────────

    @Test
    @WithMockUser(value = "admin", roles = "ADMIN")
    void resetToDefault_clearsLoginLock() throws Exception {
        String victim = makeUser("USER");
        try {
            for (int i = 0; i < 6; i++) {
                loginAttemptService.loginFailed(victim);
            }
            assertTrue(loginAttemptService.isLocked(victim), "前置：连续失败后应处于锁定状态");

            mockMvc.perform(put("/api/admin/users/" + victim + "/reset-password").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tempPassword").value("666999"));

            assertFalse(loginAttemptService.isLocked(victim),
                "重置口令必须顺带解除锁定，否则界面报成功而用户仍登不进去");
        } finally {
            userManagementService.deleteUser(victim);
        }
    }

    // ───────────────── 吊销目标用户的旧会话 ─────────────────

    @Test
    @WithMockUser(value = "admin", roles = "ADMIN")
    void resetToDefault_expiresTheVictimsExistingSession() throws Exception {
        String victim = makeUser("USER");
        try {
            LoggedIn v = login(victim, "ZZtest1234");
            mockMvc.perform(get("/api/admin/current-user").session(v.session()))
                .andExpect(status().isOk());          // 前置：会话本来是好的

            mockMvc.perform(put("/api/admin/users/" + victim + "/reset-password").with(csrf()))
                .andExpect(status().isOk());

            mockMvc.perform(get("/api/admin/current-user").session(v.session()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        } finally {
            userManagementService.deleteUser(victim);
        }
    }

    // ───────────────── 代设口令同样强制改密 ─────────────────

    @Test
    @WithMockUser(value = "admin", roles = "ADMIN")
    void adminSettingPassword_alsoForcesChangeOnNextLogin() throws Exception {
        String victim = makeUser("USER");
        try {
            assertFalse(userManagementService.mustChangePassword(victim), "前置：makeUser 已清掉标记");

            mockMvc.perform(put("/api/admin/users/" + victim + "/password").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"password\":\"Setbyadmin1\"}"))
                .andExpect(status().isOk());

            assertTrue(userManagementService.mustChangePassword(victim),
                "管理员代设口令默认要强制下次登录改密，弹窗对两条分支都这么承诺");
            assertFalse(loginAttemptService.isLocked(victim), "代设口令同样要解锁");
        } finally {
            userManagementService.deleteUser(victim);
        }
    }

    /**
     * {@code forceChange:false} 是显式逃生口，给「设一个可立即使用的正式口令」用
     * （验证套件的账号初始化就靠它）。默认值必须是 true，这条测试钉住两件事：
     * 不传 = 强制；传 false = 不强制。
     */
    @Test
    @WithMockUser(value = "admin", roles = "ADMIN")
    void forceChangeParam_isExplicitAndDefaultsToTrue() throws Exception {
        String a = makeUser("USER");
        String b = makeUser("USER");
        try {
            mockMvc.perform(put("/api/admin/users/" + a + "/password").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"password\":\"Defaultforce1\"}"))
                .andExpect(status().isOk());
            mockMvc.perform(put("/api/admin/users/" + b + "/password").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"password\":\"Optoutforce12\",\"forceChange\":false}"))
                .andExpect(status().isOk());

            assertTrue(userManagementService.mustChangePassword(a), "缺省必须强制改密");
            assertFalse(userManagementService.mustChangePassword(b), "显式 false 才允许设成可直接使用的口令");
        } finally {
            userManagementService.deleteUser(a);
            userManagementService.deleteUser(b);
        }
    }

    // ───────────────── 不能把操作者自己踢下线 ─────────────────

    @Test
    void resettingOwnPassword_doesNotKickTheActor() throws Exception {
        String boss = makeUser("ADMIN");
        try {
            LoggedIn b = login(boss, "ZZtest1234");
            mockMvc.perform(get("/api/admin/current-user").session(b.session()))
                .andExpect(status().isOk());

            mockMvc.perform(put("/api/admin/users/" + boss + "/password")
                    .session(b.session()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"password\":\"Selfset12345\"}"))
                .andExpect(status().isOk());

            mockMvc.perform(get("/api/admin/current-user").session(b.session()))
                .andExpect(status().isOk());      // 自己仍在登录态，否则会当场掉线
        } finally {
            userManagementService.deleteUser(boss);
        }
    }

    // ───────────────── 审计里绝不落口令 ─────────────────

    @Test
    @WithMockUser(value = "admin", roles = "ADMIN")
    void resetPassword_neverRecordsThePasswordItself() throws Exception {
        String victim = makeUser("USER");
        try {
            mockMvc.perform(put("/api/admin/users/" + victim + "/reset-password").with(csrf()))
                .andExpect(status().isOk());

            AuditStore.Result r =
                auditStore.query(null, "resetPassword", null, null, null, victim, null, 1, 20);
            assertTrue(r.matched() >= 1, "重置动作应进审计台账");
            for (java.util.Map<String, Object> row : r.rows()) {
                assertFalse(row.toString().contains("666999"),
                    "审计里绝不能出现口令本身：" + row);
            }
        } finally {
            userManagementService.deleteUser(victim);
        }
    }
}
