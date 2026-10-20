package com.report;

import com.report.service.DepartmentService;
import com.report.service.RolePermissionService;
import com.report.service.UserManagementService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 字典端点「统一选项来源」约定守卫。
 *
 * <p>背景：社区控制器早就确立了这条约定，{@code GET /api/admin/communities/enabled}
 * 是「各处'社区'下拉的统一选项来源（科室管理/用户数据权限等），避免要求
 * {@code communities.manage} 权限」；而完整列表 {@code GET /api/admin/communities}
 * 需要 {@code communities.manage}。
 *
 * <p>缺陷：科室一直缺 {@code /enabled} 端点，于是所有下拉/名称映射只能去调
 * 需要 {@code departments.manage} 的 {@code GET /api/admin/departments}。后果最严重的一处是
 * 人员管理页（只需 {@code personnel.manage}）， 当时的「科主任」角色恰好只有该权限，
 * 实测调完整列表会 403，导致科室名映射为空：
 * 科室列只显示编码、按科室名搜索失效、人员编辑弹窗的科室下拉是空的。
 * 前端把 403 静默降级成空数组，所以界面上看不到任何报错。
 *
 * <p>本测试用真实的受限角色账号（而不是构造的角色）来守住这个边界：
 * 它验证「完整列表确实读不到」+「轻量端点确实读得到」，两边都钉住，
 * 防止将来有人又把下拉改回管理接口。
 *
 * <p>同一约定也适用于报表分组：{@code PUT /api/admin/users/{u}/groups} 只要求
 * {@code users.manage}，但 {@code GET /api/admin/groups} 要求 {@code groups.manage} ，
 * 「能写不能读」会让编辑弹窗勾选框全空，一保存就把该工号的报表分组静默清空。
 * 故新增 {@code GET /api/admin/groups/options}（登录即可），一并在此守卫。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DictionaryEnabledEndpointTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserManagementService userService;
    @Autowired private DepartmentService departmentService;

    /**
     * 用「普通用户」角色当「受限身份」样本。
     *
     * <p>它对本测试有用的性质只有一条：没有任何 manage 权限点 ，
     * 不含 {@code communities.manage} / {@code departments.manage} /
     * {@code groups.manage} / {@code roles.manage}。
     *
     * <p>注意：别把样本绑在某个受限角色上。这里原本用的是「科主任」
     * （{@code role_dept_head}），而该角色因「与普通用户权限完全相同」
     * 已废弃**删除**，样本随之失效。教训：本测试只需要「一个没有 manage 权限的
     * 角色」，那就用**最稳定**的那个（{@code role_user}，内置且不会被删），
     * 不要依赖某个受限角色恰好存在、或恰好有哪些权限点。
     */
    private static final String DEPT_HEAD = "dic_dept_head";

    private static final String ENABLED_DEPT = "TD001";
    private static final String DISABLED_DEPT = "TD002";

    @BeforeEach
    void setUp() {
        // 夹具按「状态」补齐（测试数据目录跨运行持久化，见 AffiliationIntegrationTest 的说明）
        if (!userService.userExists(DEPT_HEAD)) {
            userService.createUser(DEPT_HEAD, "字典测试受限账号", "USER",
                RolePermissionService.R_USER, null, null);
        }
        if (userService.mustChangePassword(DEPT_HEAD)) {
            userService.changePassword(DEPT_HEAD, "DicHeadTest2026", false);
        }
        ensureDept(ENABLED_DEPT, "字典测试启用科室", "A", true);
        ensureDept(DISABLED_DEPT, "字典测试停用科室", "A", false);
    }

    private void ensureDept(String code, String name, String community, boolean enabled) {
        boolean exists = departmentService.listAll().stream().anyMatch(d -> code.equals(d.code));
        if (!exists) {
            departmentService.create(code, name, "临床科室", community, null, null);
        }
        departmentService.update(code, name, "临床科室", community, null, null, enabled);
    }

    // ---------------------------------------------------------------- 科室

    /** 原缺陷的现场：只有 personnel.manage 的角色读不到完整科室列表。 */
    @Test
    @WithMockUser(username = DEPT_HEAD, roles = "ADMIN")
    void fullDepartmentList_isDeniedForPersonnelOnlyRole() throws Exception {
        mockMvc.perform(get("/api/admin/departments"))
            .andExpect(status().isForbidden());
    }

    /** 轻量端点：同一角色可以读，且这正是下拉/名称映射该用的那个。 */
    @Test
    @WithMockUser(username = DEPT_HEAD, roles = "ADMIN")
    void enabledDepartments_areReadableForPersonnelOnlyRole() throws Exception {
        mockMvc.perform(get("/api/admin/departments/enabled"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.code=='" + ENABLED_DEPT + "')]").isNotEmpty());
    }

    /** 只返回启用项：停用科室不得出现在下拉数据源里。 */
    @Test
    @WithMockUser(username = DEPT_HEAD, roles = "ADMIN")
    void enabledDepartments_excludeDisabledOnes() throws Exception {
        mockMvc.perform(get("/api/admin/departments/enabled"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.code=='" + DISABLED_DEPT + "')]").isEmpty());
    }

    /**
     * 不泄漏管理侧字段：轻量端点是「登录即可访问」，因此不能把 contact / phone
     * 这类管理信息一并吐出去。只给下拉需要的 code / name / community。
     */
    @Test
    @WithMockUser(username = DEPT_HEAD, roles = "ADMIN")
    void enabledDepartments_doNotLeakAdminOnlyFields() throws Exception {
        String body = mockMvc.perform(get("/api/admin/departments/enabled"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Assertions.assertFalse(body.contains("\"contact\""), "不应返回 contact：" + body);
        Assertions.assertFalse(body.contains("\"phone\""), "不应返回 phone：" + body);
        Assertions.assertTrue(body.contains("\"code\""), "应返回 code：" + body);
        Assertions.assertTrue(body.contains("\"community\""), "应返回 community（归属派生要用）：" + body);
    }

    // ---------------------------------------------------------------- 社区（对照）

    /** 社区侧的老约定：完整列表需要 communities.manage，科主任读不到。 */
    @Test
    @WithMockUser(username = DEPT_HEAD, roles = "ADMIN")
    void fullCommunityList_isDeniedForPersonnelOnlyRole() throws Exception {
        mockMvc.perform(get("/api/admin/communities"))
            .andExpect(status().isForbidden());
    }

    /** 社区侧的轻量端点可以读，「范围」弹窗必须用它，而不是上面那个管理接口。 */
    @Test
    @WithMockUser(username = DEPT_HEAD, roles = "ADMIN")
    void enabledCommunities_areReadableForPersonnelOnlyRole() throws Exception {
        mockMvc.perform(get("/api/admin/communities/enabled"))
            .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 未登录

    /** 轻量端点仍要求登录（isAuthenticated），不能匿名读。 */
    @Test
    void enabledDepartments_requireLogin() throws Exception {
        mockMvc.perform(get("/api/admin/departments/enabled"))
            .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- 报表分组

    /**
     * 报表分组字典的同一条约定：{@code PUT /api/admin/users/{u}/groups} 只要求
     * {@code users.manage}，而分组管理列表 {@code GET /api/admin/groups} 要求
     * {@code groups.manage}，一个「只有 users.manage」的自定义角色能写却不能读。
     * 这是缺陷现场：分组管理列表确实读不到。
     */
    @Test
    @WithMockUser(username = DEPT_HEAD, roles = "ADMIN")
    void fullGroupList_isDeniedForRoleWithoutGroupsManage() throws Exception {
        mockMvc.perform(get("/api/admin/groups"))
            .andExpect(status().isForbidden());
    }

    /** 字典端点：同一角色可以读，且这正是各处「报表分组」勾选框该用的那个。 */
    @Test
    @WithMockUser(username = DEPT_HEAD, roles = "ADMIN")
    void groupOptions_areReadableWithoutGroupsManage() throws Exception {
        mockMvc.perform(get("/api/admin/groups/options"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='default')]").isNotEmpty());
    }

    /**
     * 不泄漏管理侧配置：{@code /groups/options} 是「登录即可访问」，
     * 只能给 {@code id / name / sort}，不能把 assignments / hiddenReports / customNames 吐出去。
     */
    @Test
    @WithMockUser(username = DEPT_HEAD, roles = "ADMIN")
    void groupOptions_doNotLeakManagementConfig() throws Exception {
        String body = mockMvc.perform(get("/api/admin/groups/options"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Assertions.assertFalse(body.contains("assignments"), "不应返回 assignments：" + body);
        Assertions.assertFalse(body.contains("hiddenReports"), "不应返回 hiddenReports：" + body);
        Assertions.assertFalse(body.contains("customNames"), "不应返回 customNames：" + body);
        Assertions.assertTrue(body.contains("\"id\""), "应返回 id：" + body);
        Assertions.assertTrue(body.contains("\"name\""), "应返回 name：" + body);
    }

    /** 分组字典端点同样要求登录。 */
    @Test
    void groupOptions_requireLogin() throws Exception {
        mockMvc.perform(get("/api/admin/groups/options"))
            .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- 角色

    /**
     * 角色字典的同一条约定：侧栏「用户管理」菜单声明 {@code data-perm="users.manage"}，
     * 即「有 users.manage 就能用这一页」。但该页三处（用户列表的「角色」列、
     * 新增用户弹窗、修改归属弹窗）原先都读 {@code GET /api/admin/roles}
     * （要求 {@code roles.manage}）， 一个「只有 users.manage」的角色进得来页面却拿不到角色，
     * 三处 403 全部**静默降级为空数组**：角色列一片空白、角色下拉框全空，
     * 于是**根本建不了新用户**，界面上还没有任何提示说明原因。
     *
     * <p>这是缺陷现场：角色管理列表确实读不到。
     */
    @Test
    @WithMockUser(username = DEPT_HEAD, roles = "ADMIN")
    void fullRoleList_isDeniedForRoleWithoutRolesManage() throws Exception {
        mockMvc.perform(get("/api/admin/roles"))
            .andExpect(status().isForbidden());
    }

    /** 角色字典端点：没有 roles.manage 也能读，且这正是各处角色下拉该用的那个。 */
    @Test
    @WithMockUser(username = DEPT_HEAD, roles = "ADMIN")
    void roleOptions_areReadableWithoutRolesManage() throws Exception {
        mockMvc.perform(get("/api/admin/roles/options"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + RolePermissionService.R_SYS_ADMIN + "')]").isNotEmpty())
            .andExpect(jsonPath("$[?(@.id=='" + RolePermissionService.R_USER + "')]").isNotEmpty());
    }

    /**
     * 不泄漏管理侧信息：{@code /roles/options} 是「登录即可访问」，
     * 只能给 {@code id / name / builtin}，不能把 permissions / usageCount /
     * usedByPositions 这类管理信息吐出去。
     */
    @Test
    @WithMockUser(username = DEPT_HEAD, roles = "ADMIN")
    void roleOptions_doNotLeakManagementFields() throws Exception {
        String body = mockMvc.perform(get("/api/admin/roles/options"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Assertions.assertFalse(body.contains("\"permissions\""), "不应返回 permissions：" + body);
        Assertions.assertFalse(body.contains("usageCount"), "不应返回 usageCount：" + body);
        Assertions.assertFalse(body.contains("usedByPositions"), "不应返回 usedByPositions：" + body);
        Assertions.assertTrue(body.contains("\"id\""), "应返回 id：" + body);
        Assertions.assertTrue(body.contains("\"name\""), "应返回 name：" + body);
    }

    /** 角色字典端点同样要求登录。 */
    @Test
    void roleOptions_requireLogin() throws Exception {
        mockMvc.perform(get("/api/admin/roles/options"))
            .andExpect(status().isUnauthorized());
    }
}
