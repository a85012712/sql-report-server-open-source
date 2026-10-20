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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 越权格封堵集成测试。
 *
 * <p>背景（验收审查中的 Critical 项）：本系统的授权是**权限点模型**，
 * {@code users.manage} 与 {@code roles.manage} 各自单独可授。改造前存在两条
 * 只靠这两个权限点就能走通的自提权路径：
 * <ol>
 *   <li>{@code PUT /api/admin/users/&lt;自己&gt;/scope} 把 roleId 写成 role_sys_admin
 *      ，setUserScope 只拦内置超管，不拦「操作对象 == 操作人」；</li>
 *   <li>{@code PUT /api/admin/roles/&lt;自己当前所持角色&gt;} 给角色原地加上
 *       自己本来没有的权限点。</li>
 * </ol>
 * 两条都是「一格之差」的逃逸，必须同时封：只封其一，另一条仍然通。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrivilegeEscalationGuardIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserManagementService userManagementService;

    @Autowired
    private RolePermissionService roleService;

    /** 真实的系统管理员账号：让 @perm.has 走真实链路（@WithMockUser 不产生用户记录）。 */
    private static final String ADMIN_ACCOUNT = "esc_admin1";

    /** 只有 users.manage 的委托管理员账号。 */
    private static final String DELEGATED = "esc_deleg1";

    /** 只有 roles.manage 的角色管理员账号。 */
    private static final String ROLE_MANAGER = "esc_roles1";

    @BeforeEach
    void ensureAccounts() {
        ensureAdminLike(ADMIN_ACCOUNT, "提权测试管理员", RolePermissionService.R_SYS_ADMIN);
        ensureDelegated();
        ensureRoleManager();
    }

    private void ensureOnSinglePermission(String uid, String name, String roleName, String permission) {
        String roleId = roleService.listRoles().stream()
            .filter(r -> roleName.equals(r.name))
            .findFirst()
            .map(r -> r.id)
            .orElseGet(() -> roleService.create(roleName, List.of(permission)).id);

        if (userManagementService.userExists(uid)) userManagementService.deleteUser(uid);
        userManagementService.createUser(uid, name, "USER", roleId, null, null);
        if (userManagementService.mustChangePassword(uid)) {
            userManagementService.changePassword(uid, "EscTest123", false);
        }
        assertTrue(roleService.permissionsOf(uid).contains(permission),
            uid + " 应持有 " + permission + "，否则本用例没有意义");
        assertTrue(!roleService.permissionsOf(uid).contains(RolePermissionService.P_DATASOURCE),
            uid + " 不应持有 datasource.manage，否则提权判定不成立");
    }

    /** 角色管理员：挂在只含 roles.manage 的自定义角色上。 */
    private void ensureRoleManager() {
        ensureOnSinglePermission(ROLE_MANAGER, "提权测试角色管理员",
            "提权测试·仅角色管理", RolePermissionService.P_ROLES);
    }

    private void ensureAdminLike(String uid, String name, String roleId) {
        // 测试数据目录跨运行持久化，账号一律重建：避免上一次运行留下的角色状态淹没断言
        if (userManagementService.userExists(uid)) userManagementService.deleteUser(uid);
        userManagementService.createUser(uid, name, "ADMIN", roleId, null, null);
        if (userManagementService.mustChangePassword(uid)) {
            userManagementService.changePassword(uid, "EscTest123", false);
        }
    }

    /** 委托管理员：挂在一个只含 users.manage 的自定义角色上。 */
    private void ensureDelegated() {
        String roleId = roleService.listRoles().stream()
            .filter(r -> "提权测试·仅用户管理".equals(r.name))
            .findFirst()
            .map(r -> r.id)
            .orElseGet(() -> roleService.create("提权测试·仅用户管理",
                List.of(RolePermissionService.P_USERS)).id);

        if (userManagementService.userExists(DELEGATED)) {
            if (!roleId.equals(userManagementService.resolveRoleId(DELEGATED))) {
                userManagementService.deleteUser(DELEGATED);
            }
        }
        if (!userManagementService.userExists(DELEGATED)) {
            userManagementService.createUser(DELEGATED, "提权测试委托管理员", "USER",
                roleId, null, null);
        }
        if (userManagementService.mustChangePassword(DELEGATED)) {
            userManagementService.changePassword(DELEGATED, "EscTest123", false);
        }
        // 前置条件本身要成立：委托管理员**不是**超管，也不持 datasource.manage
        assertTrue(roleService.permissionsOf(DELEGATED).contains(RolePermissionService.P_USERS),
            "委托管理员应持有 users.manage");
        assertTrue(!roleService.permissionsOf(DELEGATED).contains(RolePermissionService.P_DATASOURCE),
            "委托管理员不应持有 datasource.manage，否则本用例没有意义");
    }

    @Test
    @WithMockUser(username = DELEGATED, roles = "USER")
    void usersManage_cannotWidenOwnRoleViaScopeEndpoint() throws Exception {
        mockMvc.perform(put("/api/admin/users/" + DELEGATED + "/scope").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":\"" + RolePermissionService.R_SYS_ADMIN
                    + "\",\"allowedCommunities\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("不能变更自己的角色或数据权限，请由其他管理员操作"));

        assertTrue(!roleService.permissionsOf(DELEGATED).contains(RolePermissionService.P_DATASOURCE),
            "请求被拒后委托管理员仍不得持有 datasource.manage");
    }

    @Test
    @WithMockUser(username = DELEGATED, roles = "USER")
    void usersManage_cannotWidenOwnCommunityScope() throws Exception {
        // 空 allowedCommunities 的语义是「不限」，所以自改范围同样是提权，一并拦
        mockMvc.perform(put("/api/admin/users/" + DELEGATED + "/scope").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":\"role_user\",\"allowedCommunities\":[]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = ROLE_MANAGER, roles = "USER")
    void rolesManage_cannotEditRoleTheyCurrentlyHold() throws Exception {
        // 该账号只有 roles.manage 一个权限点；若允许它编辑自己当前所持的角色，
        // 原地补上 datasource.manage 就完成提权，与 scope 端点那条是同格之差。
        String ownRole = userManagementService.resolveRoleId(ROLE_MANAGER);
        mockMvc.perform(put("/api/admin/roles/" + ownRole).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"" + RolePermissionService.P_ROLES + "\",\""
                    + RolePermissionService.P_DATASOURCE + "\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("不能修改自己当前所持的角色，请改用其他角色或由他人为你分配"));

        assertTrue(!roleService.permissionsOf(ROLE_MANAGER).contains(RolePermissionService.P_DATASOURCE),
            "拒绝即生效：不得通过改自己角色来加权限点");
    }

    @Test
    @WithMockUser(username = ADMIN_ACCOUNT, roles = "ADMIN")
    void superAdmin_mayEditAnyRole() throws Exception {
        String target = userManagementService.resolveRoleId(DELEGATED);
        mockMvc.perform(put("/api/admin/roles/" + target).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"" + RolePermissionService.P_USERS + "\"]}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = ADMIN_ACCOUNT, roles = "ADMIN")
    void demotingByLegacyRole_mustActuallyChangeRoleId() throws Exception {
        String uid = "9" + Long.toString(System.nanoTime(), 36);
        userManagementService.createUser(uid, "降级口径校验", "ADMIN",
            RolePermissionService.R_SYS_ADMIN, null, null);
        assertEquals(RolePermissionService.R_SYS_ADMIN, userManagementService.resolveRoleId(uid));

        mockMvc.perform(put("/api/admin/users/" + uid + "/role").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"USER\"}"))
            .andExpect(status().isOk());

        /* resolveRoleId 优先读 roleId：早先 changeRole 只改 legacy 的 role 字段，
         * 于是「降级成功」的提示与落库数据都在，权限却一点没变。 */
        assertEquals(RolePermissionService.R_USER, userManagementService.resolveRoleId(uid),
            "legacy 角色降级必须同步 roleId");
        assertTrue(!roleService.permissionsOf(uid).contains(RolePermissionService.P_USERS),
            "降级后不得再持有 users.manage");
    }
}
