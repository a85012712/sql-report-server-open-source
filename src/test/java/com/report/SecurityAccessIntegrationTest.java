package com.report;

import com.report.service.GroupPermissionService;
import com.report.service.ReportService;
import com.report.service.RolePermissionService;
import com.report.service.UserManagementService;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 登录/权限/隐藏报表/详情脱敏 端到端集成测试（MockMvc + 测试配置）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityAccessIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReportService reportService;

    @Autowired
    private GroupPermissionService groupPermissionService;

    @Autowired
    private UserManagementService userManagementService;

    @Value("${report.scripts-dir}")
    private String scriptsDir;

    private static final String REPORT_ID = "it_demo_report";

    /**
     * 集成测试用的「普通报表查看者」工号。
     *
     * <p>必须是**真实存在**、挂在有 {@code reports.view} 角色上的工号。
     * 这些用例原先写的是虚构的 {@code "u1"}，而权限判定是按 username 查**真实角色**的，
     * 查不到只会回退成 {@code [dashboard.view]}。于是
     * {@code GET /api/reports} / {@code GET /api/report/{id}} 拿到 403，
     * 用例测的就不再是「普通用户能看到什么」，而是「没有角色的用户被拒」（见 D9）。
     *
     * <p>另一个副作用：{@code groupFilter_userWithoutGroupForbidden} 本意是验证
     * **分组过滤**（报表被挪到 sales_only，普通用户查不到），但它此前其实是靠
     * 「没有 reports.view 所以 403」通过的，断言绿了，测的东西却不是它想测的。
     */
    private static final String VIEWER = "it_plain_viewer";
    private static final String VIEWER_NAME = "集成测试普通查看者";

    @BeforeEach
    void setUpReportFile() throws Exception {
        Path dir = Path.of(scriptsDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path sql = dir.resolve(REPORT_ID + ".sql");
        String content = "---\n"
            + "name: 集成测试报表\n"
            + "description: IT only\n"
            + "params:\n"
            + "  - name: q\n"
            + "    label: 关键字\n"
            + "    type: text\n"
            + "    required: false\n"
            + "    default: \"%\"\n"
            + "---\n"
            + "SELECT 1 AS N FROM DUAL\n";
        Files.writeString(sql, content, StandardCharsets.UTF_8);
        if (!Files.exists(sql)) {
            throw new IllegalStateException("测试 SQL 未写出: " + sql);
        }
        reportService.loadReports();
        if (reportService.getReport(REPORT_ID) == null) {
            throw new IllegalStateException("报表未加载, scriptsDir=" + dir
                + " files=" + Files.list(dir).map(Path::getFileName).toList());
        }
        // 默认不隐藏
        groupPermissionService.setReportHidden(REPORT_ID, false);
        groupPermissionService.setReportGroup(REPORT_ID, "default");
    }

    /**
     * 夹具：保证 {@link #VIEWER} 真实存在、且**确实挂**在有 {@code reports.view} 的角色上。
     *
     * <p>按「状态」补齐而不是无脑创建：测试数据目录
     * （{@code ${java.io.tmpdir}/report-it/}）跨运行持久化，
     * 上一次跑动可能把角色改过或把用户删过。写法与
     * {@code ReportManagerPermissionTest#ensureRoleAndUser} 一致。
     */
    @BeforeEach
    void ensureViewerUser() {
        boolean wrongRole = userManagementService.userExists(VIEWER)
            && !RolePermissionService.R_USER.equals(userManagementService.resolveRoleId(VIEWER));
        if (wrongRole) {
            userManagementService.deleteUser(VIEWER);
        }
        if (!userManagementService.userExists(VIEWER)) {
            userManagementService.createUser(VIEWER, VIEWER_NAME, "USER",
                RolePermissionService.R_USER, null, null);
        }
        // 注意：新建用户带「首次登录必须改密」标记，会被过滤器拦成
        //   403 {"code":"MUST_CHANGE_PASSWORD"}，**看起来和权限不足一模一样**。
        //   必须清掉，否则本类会误判成「权限点没生效」而把 D9 的修复回退掉。
        if (userManagementService.mustChangePassword(VIEWER)) {
            userManagementService.changePassword(VIEWER, "ItPlainViewer2026", false);
        }
    }

    @Test
    void apiReports_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/reports"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void apiReports_admin_ok() throws Exception {
        mockMvc.perform(get("/api/reports"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id", hasItem(REPORT_ID)));
    }

    @Test
    @WithMockUser(username = "u1", roles = "USER")
    void adminUsers_userForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminUsers_adminOk() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = VIEWER, roles = "USER")
    void reportDetail_doesNotExposeSql() throws Exception {
        mockMvc.perform(get("/api/report/" + REPORT_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(REPORT_ID))
            .andExpect(jsonPath("$.name").value("集成测试报表"))
            .andExpect(jsonPath("$.sql").doesNotExist())
            .andExpect(jsonPath("$.params").isArray());
    }

    @Test
    @WithMockUser(username = VIEWER, roles = "USER")
    void hiddenReport_userCannotQuery() throws Exception {
        groupPermissionService.setReportHidden(REPORT_ID, true);

        mockMvc.perform(get("/api/reports"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id", not(hasItem(REPORT_ID))));

        mockMvc.perform(post("/api/report/" + REPORT_ID + "/query")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"params\":{},\"page\":1,\"page_size\":10}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/report/" + REPORT_ID))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void hiddenReport_adminCanStillAccessDetail() throws Exception {
        groupPermissionService.setReportHidden(REPORT_ID, true);
        mockMvc.perform(get("/api/report/" + REPORT_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(REPORT_ID))
            .andExpect(jsonPath("$.hidden").value(true));
    }

    @Test
    @WithMockUser(username = VIEWER, roles = "USER")
    void groupFilter_userWithoutGroupForbidden() throws Exception {
        // 本用例验证的是**分组过滤**：报表被挪到 sales_only，普通用户（默认只有 default 分组）
        // 因此查不到，403 必须来自 checkReportAccess 的分组判定。
        // 注意 VIEWER 现在**有** reports.view（见字段说明），所以这个 403 不可能来自权限点缺失，
        // 断言才真的落在「分组」上。
        try {
            groupPermissionService.createGroup("sales_only", "销售组", 99);
        } catch (IllegalArgumentException ignored) {
            // 分组已存在
        }
        groupPermissionService.setReportGroup(REPORT_ID, "sales_only");
        groupPermissionService.setReportHidden(REPORT_ID, false);

        mockMvc.perform(post("/api/report/" + REPORT_ID + "/query")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"params\":{},\"page\":1,\"page_size\":10}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void actuatorHealth_public() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "u1", roles = "USER")
    void actuatorPrometheus_userForbidden() throws Exception {
        // 未暴露时可能 404/500(NoHandlerFound)；暴露时非 admin 应为 403
        int status = mockMvc.perform(get("/actuator/prometheus"))
            .andReturn().getResponse().getStatus();
        assertTrue(status == 403 || status == 404 || status == 500,
            "expected 403/404/500 but was " + status);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void actuatorPrometheus_adminNotUnauthorized() throws Exception {
        int status = mockMvc.perform(get("/actuator/prometheus"))
            .andReturn().getResponse().getStatus();
        // 200 可读；404/500 表示测试配置未暴露端点；关键：不能是 401
        assertTrue(status != 401, "admin should not get 401, was " + status);
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
