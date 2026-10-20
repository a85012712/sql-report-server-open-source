package com.report;

import com.report.service.AffiliationService;
import com.report.service.DepartmentService;
import com.report.service.RolePermissionService;
import com.report.service.UserManagementService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 工号归属管理 集成测试。
 *
 * <p>验证核心命题：归属是源、权限与数据范围是派生。
 * 特别是「改科室 → 归属社区自动跟随 → 数据范围随之更新并落库」这条链路。
 *
 * <p>测试环境（{@code application-test.yml}）的数据目录是 {@code ${java.io.tmpdir}/report-it}，
 * 该目录跨运行持久化，因此夹具按「状态」而非「存在性」补齐。
 * 该目录没有 {@code departments.json}，故本测试自行创建所需科室。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AffiliationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserManagementService userService;
    @Autowired private DepartmentService departmentService;
    @Autowired private AffiliationService affiliationService;

    /** 操作者：普通系统管理员（有 users.manage 权限） */
    private static final String OPERATOR = "boss1";
    /** 被操作的工号 */
    private static final String TARGET = "aff_target";
    /** 后备管理员工号：用于验证「有后备管理员后即可正常降权」 */
    private static final String BACKUP = "aff_backup";
    /** 内置超级管理员工号（测试配置 app.security.admin.username） */
    private static final String SUPER = "admin";

    private static final String DEPT_A = "T9001";      // 属 A 社区
    private static final String DEPT_B = "T9002";      // 属 B 社区
    private static final String DEPT_NO_COMM = "T9003"; // 未挂社区（行政岗）

    @BeforeEach
    void setUp() {
        if (!userService.userExists(OPERATOR)) {
            userService.createUser(OPERATOR, "测试管理员", "ADMIN",
                RolePermissionService.R_SYS_ADMIN, null, null);
        }
        if (userService.mustChangePassword(OPERATOR)) {
            userService.changePassword(OPERATOR, "Boss1Test2026", false);
        }
        // 夹具兜底：本测试类所有用例都靠 OPERATOR 持有 users.manage 才能调接口。
        // 测试数据目录跨运行持久化，若上一次运行异常中断把 OPERATOR 留在了低权限状态，
        // 后续用例会集体 403，这里无条件校正回来。
        ensureSysAdmin(OPERATOR);

        ensureDept(DEPT_A, "测试A外科", "A");
        ensureDept(DEPT_B, "测试B内科", "B");
        ensureDept(DEPT_NO_COMM, "测试行政科", null);

        if (!userService.userExists(TARGET)) {
            userService.createUser(TARGET, "归属测试用户", "USER",
                RolePermissionService.R_USER, null, null);
        }
        if (userService.mustChangePassword(TARGET)) {
            userService.changePassword(TARGET, "TargetTest2026", false);
        }
        // TARGET 是「被操作工号」，夹具保证它从普通用户起步
        if (!RolePermissionService.R_USER.equals(userService.resolveRoleId(TARGET))) {
            changeRoleOnly(TARGET, RolePermissionService.R_USER);
        }

        // 后备管理员：只保证存在，角色由用例自行安排
        if (!userService.userExists(BACKUP)) {
            userService.createUser(BACKUP, "后备管理员", "ADMIN",
                RolePermissionService.R_SYS_ADMIN, null, null);
        }
        if (userService.mustChangePassword(BACKUP)) {
            userService.changePassword(BACKUP, "BackupTest2026", false);
        }
    }

    /** 确保指定账号持有系统管理员角色（只提升，不会触发降权保护）。 */
    private void ensureSysAdmin(String username) {
        if (!RolePermissionService.R_SYS_ADMIN.equals(userService.resolveRoleId(username))) {
            changeRoleOnly(username, RolePermissionService.R_SYS_ADMIN);
        }
    }

    private void ensureDept(String code, String name, String community) {
        boolean exists = departmentService.listAll().stream().anyMatch(d -> code.equals(d.code));
        if (!exists) {
            departmentService.create(code, name, "临床科室", community, null, null);
        }
    }

    private String body(String department, String position, String roleId) {
        return "{\"department\":" + q(department) + ",\"position\":" + q(position)
            + ",\"roleId\":" + q(roleId) + "}";
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    // ==================================================================
    // 字典
    // ==================================================================

    @Test
    @WithMockUser(username = OPERATOR, roles = "ADMIN")
    void dictionaries_containPositionsAndDepartmentCommunity() throws Exception {
        mockMvc.perform(get("/api/admin/affiliations/dictionaries"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.positions").isArray())
            .andExpect(jsonPath("$.departments").isArray())
            .andExpect(jsonPath("$.roles").isArray())
            .andExpect(jsonPath("$.permissions.length()").value(14))
            // 职务必须带 defaultRoleId 与 scopeRule，前端联动全靠它们
            .andExpect(jsonPath("$.positions[?(@.code=='POS_COMM_HEAD')].scopeRule").value("SELF_COMMUNITY"))
            // 科主任 / 社区主任职务改挂 role_user
            //   （role_dept_head 角色已删，它与 role_user 权限完全相同，没有区分度）
            .andExpect(jsonPath("$.positions[?(@.code=='POS_DEPT_HEAD')].defaultRoleId").value("role_user"));
    }

    @Test
    @WithMockUser(username = OPERATOR, roles = "ADMIN")
    void list_returnsDerivedAffiliation() throws Exception {
        mockMvc.perform(get("/api/admin/affiliations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[?(@.username=='" + OPERATOR + "')].roleId").exists());
    }

    // ==================================================================
    // 核心：改科室 → 社区自动跟随 → 数据范围落库
    // ==================================================================

    @Test
    @WithMockUser(username = OPERATOR, roles = "ADMIN")
    void save_departmentDrivesCommunityAndScope() throws Exception {
        // 先落在 A 社区科室
        mockMvc.perform(put("/api/admin/affiliations/" + TARGET).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(DEPT_A, "POS_STAFF", "role_user")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.derived.community").value("A"))
            .andExpect(jsonPath("$.derived.scopeRule").value("SELF_DEPT"))
            .andExpect(jsonPath("$.derived.communities[0]").value("A"))
            .andExpect(jsonPath("$.derived.departments[0]").value(DEPT_A));

        // 只改科室到 B，社区必须自动跟随，不需要任何人手工维护
        mockMvc.perform(put("/api/admin/affiliations/" + TARGET).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(DEPT_B, "POS_STAFF", "role_user")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.derived.community").value("B"))
            .andExpect(jsonPath("$.derived.communities[0]").value("B"))
            .andExpect(jsonPath("$.derived.departments[0]").value(DEPT_B));

        // 派生结果必须真的落库到既有字段，报表/大屏的数据过滤只认这两个字段
        Assertions.assertEquals(List.of("B"), userService.getAllowedCommunities(TARGET),
            "allowedCommunities 应按派生的社区更新");
        Assertions.assertEquals(List.of(DEPT_B), userService.getAllowedDepartments(TARGET),
            "allowedDepartments 应按派生的科室更新");

        // 归属科室本身也落库了（源数据）
        Assertions.assertEquals(DEPT_B, userService.getDepartment(TARGET));
        Assertions.assertEquals("POS_STAFF", userService.getPosition(TARGET));
    }

    @Test
    @WithMockUser(username = OPERATOR, roles = "ADMIN")
    void save_departmentWithoutCommunity_yieldsUnrestrictedCommunity() throws Exception {
        mockMvc.perform(put("/api/admin/affiliations/" + TARGET).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(DEPT_NO_COMM, "POS_STAFF", "role_user")))
            .andExpect(status().isOk())
            // 科室未挂社区 → 社区为空（=不限），并有明确告警而不是静默
            .andExpect(jsonPath("$.derived.communities").isEmpty())
            .andExpect(jsonPath("$.derived.issues[0].level").value("warn"))
            .andExpect(jsonPath("$.derived.issues[0].text", containsString("未挂社区")));
    }

    // ==================================================================
    // 核心：改职务 → 角色与范围规则自动带出
    // ==================================================================

    @Test
    @WithMockUser(username = OPERATOR, roles = "ADMIN")
    void save_positionDrivesDefaultRoleAndScopeRule() throws Exception {
        // 角色留空，让服务端按职务带出（模拟不经前端直接调接口的情况）
        mockMvc.perform(put("/api/admin/affiliations/" + TARGET).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"department\":\"" + DEPT_A + "\",\"position\":\"POS_DEPT_HEAD\"}"))
            .andExpect(status().isOk())
            // 科主任职务带出 role_user（原 role_dept_head 已删）
            //   权限数随之 4 → 3（role_user 是 dashboard.view + reports.view + report.export）
            .andExpect(jsonPath("$.derived.roleId").value("role_user"))
            .andExpect(jsonPath("$.derived.scopeRule").value("SELF_DEPT"))
            .andExpect(jsonPath("$.derived.permissions.length()").value(3));

        // 社区主任：范围升级为「本社区」，可见科室变为不限，社区为本社区
        mockMvc.perform(put("/api/admin/affiliations/" + TARGET).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"department\":\"" + DEPT_B + "\",\"position\":\"POS_COMM_HEAD\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.derived.roleId").value("role_user"))
            .andExpect(jsonPath("$.derived.scopeRule").value("SELF_COMMUNITY"))
            .andExpect(jsonPath("$.derived.communities[0]").value("B"))
            .andExpect(jsonPath("$.derived.departments").isEmpty());

        // 范围落库：社区 = [B]，科室 = 不限（null）
        Assertions.assertEquals(List.of("B"), userService.getAllowedCommunities(TARGET));
        Assertions.assertNull(userService.getAllowedDepartments(TARGET),
            "SELF_COMMUNITY 规则下科室范围应为不限(null)");
    }

    @Test
    @WithMockUser(username = OPERATOR, roles = "ADMIN")
    void save_medAdminPosition_yieldsUnrestricted() throws Exception {
        mockMvc.perform(put("/api/admin/affiliations/" + TARGET).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(DEPT_A, "POS_MED_ADMIN", "role_med_admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.derived.scopeRule").value("ALL"))
            .andExpect(jsonPath("$.derived.communities").isEmpty())
            .andExpect(jsonPath("$.derived.departments").isEmpty());

        Assertions.assertNull(userService.getAllowedCommunities(TARGET));
        Assertions.assertNull(userService.getAllowedDepartments(TARGET));
    }

    // ==================================================================
    // 自定义覆盖
    // ==================================================================

    @Test
    @WithMockUser(username = OPERATOR, roles = "ADMIN")
    void save_scopeOverride_takesPrecedenceOverPositionRule() throws Exception {
        // 职务是普通员工（本科室），但显式覆盖为「A+B 两个社区」
        mockMvc.perform(put("/api/admin/affiliations/" + TARGET).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"department\":\"" + DEPT_A + "\",\"position\":\"POS_STAFF\",\"roleId\":\"role_user\","
                    + "\"scopeOverride\":true,\"communities\":[\"A\",\"B\"],\"departments\":[\"" + DEPT_A + "\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.derived.scopeRule").value("CUSTOM"))
            .andExpect(jsonPath("$.derived.communities.length()").value(2))
            .andExpect(jsonPath("$.derived.departments[0]").value(DEPT_A));

        Assertions.assertEquals(List.of("A", "B"), userService.getAllowedCommunities(TARGET));

        // 关掉覆盖 → 回到职务规则
        mockMvc.perform(put("/api/admin/affiliations/" + TARGET).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(DEPT_A, "POS_STAFF", "role_user")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.derived.scopeRule").value("SELF_DEPT"));

        Assertions.assertEquals(List.of("A"), userService.getAllowedCommunities(TARGET));
    }

    // ==================================================================
    // 校验与保护
    // ==================================================================

    @Test
    @WithMockUser(username = OPERATOR, roles = "ADMIN")
    void save_superAdmin_rejected() throws Exception {
        mockMvc.perform(put("/api/admin/affiliations/admin").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(DEPT_A, "POS_STAFF", "role_user")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("内置超级管理员")));
    }

    @Test
    @WithMockUser(username = OPERATOR, roles = "ADMIN")
    void save_unknownDepartment_rejected() throws Exception {
        mockMvc.perform(put("/api/admin/affiliations/" + TARGET).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("NO_SUCH_DEPT", "POS_STAFF", "role_user")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("科室编码在字典中不存在")));
    }

    /**
     * 「大屏查看」是**合法的可分配角色**（大屏专用工号），只提示不拒绝。
     *
     * <p>注意区分 M-1 缺陷：那条教训是「{@code role_med_admin}/{@code role_user}
     * 不得携带 {@code screen.view}」（会让工号级大屏开关被角色通道无条件短路），
     * 由 {@code RolePermissionService} 的内置角色定义保证，与本处是否允许分配无关。
     */
    @Test
    @WithMockUser(username = OPERATOR, roles = "ADMIN")
    void save_screenRole_allowedWithWarning() throws Exception {
        mockMvc.perform(put("/api/admin/affiliations/" + TARGET).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(DEPT_A, "POS_STAFF", "role_screen")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.derived.roleId").value("role_screen"))
            // 权限集只有大屏，且必须是 warn 而非 error
            .andExpect(jsonPath("$.derived.permissions.length()").value(1))
            .andExpect(jsonPath("$.derived.permissions[0]").value("screen.view"))
            .andExpect(jsonPath("$.derived.hasError").value(false))
            .andExpect(jsonPath("$.derived.issues[0].level").value("warn"))
            .andExpect(jsonPath("$.derived.issues[0].text", containsString("直达数据大屏")));

        Assertions.assertEquals("role_screen", userService.resolveRoleId(TARGET));
    }

    /**
     * 最后一个「普通」系统管理员降权保护。
     *
     * <p>内置超管不计入，它由环境变量 ADMIN_USERNAME 指定、永远不会失去权限，
     * 只有界面创建的普通管理员才构成「无人可管理系统」的自锁风险。
     *
     * <p>本用例的调用方必须是内置超管：构造「只剩一个普通管理员」的局面时，
     * 需要把所有普通管理员（含 OPERATOR 自己）降权，若用普通管理员调用，
     * 它会在构造过程中失去 {@code users.manage}，被 403 挡在门外而测不到真正的降权保护。
     */
    @Test
    @WithMockUser(username = SUPER, roles = "ADMIN")
    void save_lastOrdinarySysAdmin_cannotBeDemoted() throws Exception {
        // 快照所有「普通」账号的完整归属，测试结束原样还原。
        // 排除两类账号：
        //   1) 内置超管，既不可变更，也不该被扫进降权循环；
        //   2) 备份管理员，由本用例显式调度，最后固定还原为系统管理员。
        Map<String, UserManagementService.UserRecord> snapshot = new LinkedHashMap<>();
        for (UserManagementService.UserRecord r : userService.allRecords()) {
            if (userService.isSuperAdminAccount(r.username)) continue;
            if (BACKUP.equals(r.username)) continue;
            snapshot.put(r.username, r);
        }

        try {
            // 1) 把 TARGET 提升为普通系统管理员
            affiliationService.save("test", TARGET, DEPT_A, "POS_STAFF",
                RolePermissionService.R_SYS_ADMIN, null, null, null);

            // 2) 临时把其它普通系统管理员全部降为普通用户，构造「只剩 TARGET 一个」的局面
            for (String u : snapshot.keySet()) {
                if (TARGET.equals(u)) continue;
                if (RolePermissionService.R_SYS_ADMIN.equals(userService.resolveRoleId(u))) {
                    changeRoleOnly(u, RolePermissionService.R_USER);
                }
            }
            changeRoleOnly(BACKUP, RolePermissionService.R_USER);

            Assertions.assertEquals(1, userService.countOrdinarySysAdmins(),
                "前置条件：此时应只剩 TARGET 一个普通系统管理员");

            // 3) 最后一个普通管理员不允许降权（内置超管不计入）
            mockMvc.perform(put("/api/admin/affiliations/" + TARGET).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(DEPT_A, "POS_STAFF", "role_user")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("最后一个普通系统管理员")));

            // 4) 有后备管理员后即可正常降权
            changeRoleOnly(BACKUP, RolePermissionService.R_SYS_ADMIN);
            Assertions.assertEquals(2, userService.countOrdinarySysAdmins());

            mockMvc.perform(put("/api/admin/affiliations/" + TARGET).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(DEPT_A, "POS_STAFF", "role_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.derived.roleId").value("role_user"));
        } finally {
            restore(snapshot);
        }
    }

    /**
     * 还原快照。
     *
     * <p>两遍还原：先扶起所有系统管理员，再还原其余账号，
     * 否则中途可能出现「一个管理员都不剩」的瞬时状态，被降权保护拦下。
     * 另外跳过 {@code roleId} 为空的历史账号（它们由旧接口创建，从未被本用例改动）。
     */
    private void restore(Map<String, UserManagementService.UserRecord> snapshot) {
        changeRoleOnly(BACKUP, RolePermissionService.R_SYS_ADMIN);
        for (UserManagementService.UserRecord r : snapshot.values()) {
            if (RolePermissionService.R_SYS_ADMIN.equals(r.roleId)) apply(r);
        }
        for (UserManagementService.UserRecord r : snapshot.values()) {
            if (!RolePermissionService.R_SYS_ADMIN.equals(r.roleId)) apply(r);
        }
    }

    private void apply(UserManagementService.UserRecord r) {
        if (r.roleId == null || r.roleId.isBlank()) return;
        userService.setAffiliationRaw("test", r.username, r.department, r.position,
            r.roleId, r.scopeOverride, r.allowedCommunities, r.allowedDepartments);
    }

    /** 只改角色，其余归属字段（科室/职务/范围覆盖/可见范围）原样保留。 */
    private void changeRoleOnly(String username, String roleId) {
        UserManagementService.UserRecord r = userService.allRecords().stream()
            .filter(x -> username.equals(x.username)).findFirst().orElse(null);
        if (r == null) return;
        userService.setAffiliationRaw("test", r.username, r.department, r.position,
            roleId, r.scopeOverride, r.allowedCommunities, r.allowedDepartments);
    }

    @Test
    @WithMockUser(username = TARGET, roles = "USER")
    void writeApi_requiresUsersManagePermission() throws Exception {
        mockMvc.perform(put("/api/admin/affiliations/" + TARGET).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(DEPT_A, "POS_STAFF", "role_user")))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = TARGET, roles = "USER")
    void readApi_requiresUsersManagePermission() throws Exception {
        mockMvc.perform(get("/api/admin/affiliations"))
            .andExpect(status().isForbidden());
    }
}
