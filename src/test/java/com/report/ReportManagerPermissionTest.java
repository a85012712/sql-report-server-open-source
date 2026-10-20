package com.report;

import com.report.service.GroupPermissionService;
import com.report.service.ReportService;
import com.report.service.RolePermissionService;
import com.report.service.UserManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「报表管理员」判定必须按权限点，不能按 legacy 的 {@code ROLE_ADMIN}。
 *
 * <p>背景：{@code UserDetailsConfig} 只给内置超管与 legacy {@code role=ADMIN}
 * 的账号授予 {@code ROLE_ADMIN}；{@code UserManagementService} 创建用户时也只有
 * {@code role_sys_admin} 映射成 {@code ADMIN}，任何自定义角色都是 {@code USER}。
 * 而 {@code ReportController} 原先三处都写成 {@code authorities.contains("ROLE_ADMIN")}
 * 来判「是否管理员」。
 *
 * <p>后果：一个「只有 {@code report.manage}」的自定义角色（报表管理员）
 * 打开「报表管理」页时 ，
 * <ul>
 *   <li>报表列表被该工号的报表分组过滤（{@code getUserGroups} 非管理员默认
 *       {@code ["default"]}）→ 只看到 default 分组内的报表</li>
 *   <li>隐藏报表完全不出现，且响应里没有 {@code hidden} 字段 → 无法取消隐藏</li>
 *   <li>直链打开隐藏报表被 403</li>
 * </ul>
 * 一个「能进这个菜单、却管不了东西」的残页，与「科主任的人员管理页是坏的」同源。
 *
 * <p>本测试的判别力：把测试报表挪到 {@code sales_only} 分组 / 标记隐藏，
 * 再分别以「报表管理员」「只有 reports.view 的普通角色」「超管」三种身份读
 * {@code GET /api/reports}，断言三者的可见性差异，数据没变，只有身份变。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportManagerPermissionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ReportService reportService;
    @Autowired private GroupPermissionService groupPermissionService;
    @Autowired private UserManagementService userService;
    @Autowired private RolePermissionService roleService;

    @Value("${report.scripts-dir}")
    private String scriptsDir;

    private static final String REPORT_ID = "it_demo_report";
    /** 报表管理员：有 report.manage，但没有 ROLE_ADMIN */
    private static final String RM_USER = "dic_report_mgr";
    private static final String RM_ROLE_NAME = "字典测试-报表管理员";
    /** 对照：只有 reports.view 的普通角色（无 report.manage、无分组配置） */
    private static final String VIEWER = "dic_dept_head";

    private static final String OTHER_GROUP = "sales_only";

    @BeforeEach
    void setUp() throws Exception {
        ensureReportFile();
        reportService.loadReports();
        if (reportService.getReport(REPORT_ID) == null) {
            throw new IllegalStateException("测试报表未加载: " + REPORT_ID);
        }
        ensureRoleAndUser();
        // sales_only 分组必须自己保证存在。原先它是由
        //   SecurityAccessIntegrationTest 某个用例顺手创建的，于是本类**能不能跑过
        //   取决于 surefire 先跑哪个类**。新增任何测试类都可能翻转扫描顺序而把本类弄红，
        //   报的还是个跟改动无关的「分组不存在」。测试不该有跨类顺序依赖。
        ensureGroup(OTHER_GROUP, "销售组");
        // 起点：default 分组、未隐藏
        groupPermissionService.setReportGroup(REPORT_ID, "default");
        groupPermissionService.setReportHidden(REPORT_ID, false);
    }

    private void ensureGroup(String id, String name) {
        try {
            groupPermissionService.createGroup(id, name, 99);
        } catch (IllegalArgumentException ignored) {
            // 已存在，属正常
        }
    }

    @AfterEach
    void tearDown() {
        // 还原，避免污染其它用例（测试数据目录跨运行持久化）
        groupPermissionService.setReportGroup(REPORT_ID, "default");
        groupPermissionService.setReportHidden(REPORT_ID, false);
    }

    private void ensureReportFile() throws Exception {
        Path dir = Path.of(scriptsDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path sql = dir.resolve(REPORT_ID + ".sql");
        if (!Files.exists(sql)) {
            Files.writeString(sql, "---\n"
                + "name: 集成测试报表\n"
                + "description: IT only\n"
                + "params:\n"
                + "  - name: q\n"
                + "    label: 关键字\n"
                + "    type: text\n"
                + "    required: false\n"
                + "    default: \"%\"\n"
                + "---\n"
                + "SELECT 1 AS N FROM DUAL\n", StandardCharsets.UTF_8);
        }
    }

    /** 夹具按「状态」补齐：角色按名字找，找不到才建；权限被改过就改回来。 */
    private void ensureRoleAndUser() {
        List<String> wanted = List.of(
            RolePermissionService.P_REPORTS, RolePermissionService.P_REPORT_MANAGE);

        RolePermissionService.Role role = roleService.listRoles().stream()
            .filter(r -> RM_ROLE_NAME.equals(r.name))
            .findFirst()
            .orElseGet(() -> roleService.create(RM_ROLE_NAME, wanted));
        if (!role.permissions.equals(wanted)) {
            // operator 传 null = 无人值守的内部调用（测试装配）；HTTP 路径必须带操作人，
            // 由 RolePermissionService#update 的「不得改自己角色」守卫把守。
            roleService.update(role.id, RM_ROLE_NAME, wanted, null);
        }

        // 用户必须真的挂在这个角色上（roleId 可能被上一次跑动过）
        if (userService.userExists(RM_USER)
            && !role.id.equals(userService.resolveRoleId(RM_USER))) {
            userService.deleteUser(RM_USER);
        }
        if (!userService.userExists(RM_USER)) {
            userService.createUser(RM_USER, "字典测试报表管理员", "USER", role.id, null, null);
        }
        if (userService.mustChangePassword(RM_USER)) {
            userService.changePassword(RM_USER, "ReportMgrTest2026", false);
        }

        // 对照组：只有 reports.view 的普通角色（用 role_user，稳定、不会被删）
        if (!userService.userExists(VIEWER)) {
            userService.createUser(VIEWER, "字典测试普通查看者", "USER",
                RolePermissionService.R_USER, null, null);
        }
    }

    // ---------------------------------------------------- 分组过滤：按权限点放行

    /** 报表管理员不受「工号报表分组」过滤，否则他管不到别组的报表。 */
    @Test
    @WithMockUser(username = RM_USER, roles = "USER")
    void reportManager_seesReportAssignedToOtherGroup() throws Exception {
        groupPermissionService.setReportGroup(REPORT_ID, OTHER_GROUP);
        mockMvc.perform(get("/api/reports"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id", hasItem(REPORT_ID)));
    }

    /** 对照：只有 reports.view 的普通角色仍按自己的分组过滤（该行为不能被改坏）。 */
    @Test
    @WithMockUser(username = VIEWER, roles = "USER")
    void plainViewer_doesNotSeeReportAssignedToOtherGroup() throws Exception {
        groupPermissionService.setReportGroup(REPORT_ID, OTHER_GROUP);
        mockMvc.perform(get("/api/reports"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id", not(hasItem(REPORT_ID))));
    }

    // ---------------------------------------------------- 隐藏报表

    /** 报表管理员能看到隐藏报表，且拿到 hidden 标记（否则无法取消隐藏）。 */
    @Test
    @WithMockUser(username = RM_USER, roles = "USER")
    void reportManager_seesHiddenReportAndItsFlag() throws Exception {
        groupPermissionService.setReportHidden(REPORT_ID, true);
        mockMvc.perform(get("/api/reports"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id", hasItem(REPORT_ID)))
            .andExpect(jsonPath("$[?(@.id=='" + REPORT_ID + "')].hidden").value(true));
    }

    /** 对照：普通角色看不到隐藏报表。 */
    @Test
    @WithMockUser(username = VIEWER, roles = "USER")
    void plainViewer_doesNotSeeHiddenReport() throws Exception {
        groupPermissionService.setReportHidden(REPORT_ID, true);
        mockMvc.perform(get("/api/reports"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id", not(hasItem(REPORT_ID))));
    }

    /** 普通角色的列表项里不应出现 hidden 字段（未隐藏时也不该有）。 */
    @Test
    @WithMockUser(username = VIEWER, roles = "USER")
    void plainViewer_listItemHasNoHiddenFlag() throws Exception {
        mockMvc.perform(get("/api/reports"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id", hasItem(REPORT_ID)))
            .andExpect(jsonPath("$[?(@.id=='" + REPORT_ID + "')].hidden").isEmpty());
    }

    /** 直链：报表管理员可以打开隐藏报表。 */
    @Test
    @WithMockUser(username = RM_USER, roles = "USER")
    void reportManager_canOpenHiddenReportDirectly() throws Exception {
        groupPermissionService.setReportHidden(REPORT_ID, true);
        mockMvc.perform(get("/api/report/" + REPORT_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hidden").value(true));
    }

    /** 直链：普通角色打开隐藏报表仍被拒（这条防线不能被改松）。 */
    @Test
    @WithMockUser(username = VIEWER, roles = "USER")
    void plainViewer_openingHiddenReportDirectly_isForbidden() throws Exception {
        groupPermissionService.setReportHidden(REPORT_ID, true);
        mockMvc.perform(get("/api/report/" + REPORT_ID))
            .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------- 超管回归

    /** 回归守卫：改成权限点判定后，内置超管必须仍然「全部可见 + 含隐藏」。 */
    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void superAdmin_stillSeesEverything() throws Exception {
        groupPermissionService.setReportGroup(REPORT_ID, OTHER_GROUP);
        groupPermissionService.setReportHidden(REPORT_ID, true);
        mockMvc.perform(get("/api/reports"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id", hasItem(REPORT_ID)))
            .andExpect(jsonPath("$[?(@.id=='" + REPORT_ID + "')].hidden").value(true));
    }
}
