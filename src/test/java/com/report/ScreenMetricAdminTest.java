package com.report;

import com.report.service.RolePermissionService;
import com.report.service.UserManagementService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 大屏指标 SQL 的后台编辑通道（{@code /api/admin/screen/metrics/**}）。
 *
 * <p>为什么要有这套：大屏口径原先只能登服务器改 {@code scripts/screen/*.sql}，
 * 而报表中心早就有「管理页改 SQL 并落盘」。补齐这条通道时，最容易漏的三件事正好是
 * 三类缺陷的历史来源，本类逐条钉住：
 * <ol>
 *   <li>权限：菜单进得去 ≠ 接口给得了。只有 {@code reports.view} 的账号
 *       直接 PUT 必须 403（与 {@code tools/audit-backend-authz-gaps.mjs} 同源，但那条只静态看注解）。</li>
 *   <li>校验必须早于落盘：本类断言的是「被拒的 SQL 没有改动文件、
 *       也没有留下 .bak」， 只在保存时返回错误、却先把文件写了，
 *       线上就会有一份没人知道、下次 5 分钟重载即生效的口径。</li>
 *   <li>指标ID 是文件名，不能当路径：{@code metricId} 会被拼进
 *       {@code scripts/screen/<id>.sql}，白名单形态不合规就一律按「指标不存在」处理。</li>
 * </ol>
 *
 * <p>用例只碰临时目录（{@code application-test.yml} 已把 {@code screen.scripts-dir}
 * 指到 {@code ${java.io.tmpdir}/report-it/scripts/screen}），不会改动仓库里在用的 5 个指标。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScreenMetricAdminTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RolePermissionService roleService;
    @Autowired private UserManagementService userService;

    @Value("${screen.scripts-dir}")
    private String scriptsDir;

    private static final String METRIC = "it_screen_metric";
    /** 注册表里真实存在的指标 ID：面板标题只认这些 ID 名下的槽位，所以标题类用例必须用它。 */
    private static final String KPI_METRIC = "range_kpi";
    /** 编辑器：只挂 report.manage 的自定义角色（legacy role 必须是 USER，否则等于在测超管） */
    private static final String EDITOR = "scr_test_editor";
    private static final String EDITOR_ROLE = "大屏指标测试-报表管理员";
    /** 对照：内置普通角色，有 reports.view 但没有 report.manage */
    private static final String VIEWER = "scr_test_viewer";

    private static final String HEAD = "---\nname: 集成测试大屏指标\ndescription: IT only\ninterval-minutes: 10\n---\n";
    private static final String ORIGINAL = HEAD + "\nSELECT 'A' AS 社区, 1 AS 指标 FROM DUAL\n";
    private static final String EDITED = HEAD + "\nSELECT 'A' AS 社区, 2 AS 指标 FROM DUAL\n";
    private static final String DANGEROUS = HEAD + "\nDELETE FROM MI_LOG WHERE 1=1\n";
    private static final String MULTI_STATEMENT = HEAD + "\nSELECT 1 FROM DUAL; SELECT 2 FROM DUAL\n";

    /** 带 params 的头：用来证明「只改标题」不会把 params / description 等既有键写丢。 */
    private static final String KPI_HEAD = "---\nname: 区间经营指标\ndescription: 按社区统计\ntimeout: 30\n"
        + "params:\n- name: start_date\n  label: 起始日期\n  type: date\n  required: true\n"
        + "- name: end_date\n  label: 结束日期\n  type: date\n  required: true\n---\n";
    private static final String KPI_BODY = "\nSELECT 'A' AS 社区, 3 AS 门诊人次 FROM DUAL\n";

    private Path metricFile;
    private Path kpiFile;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Path.of(scriptsDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        metricFile = dir.resolve(METRIC + ".sql");
        Files.writeString(metricFile, ORIGINAL, StandardCharsets.UTF_8);
        kpiFile = dir.resolve(KPI_METRIC + ".sql");
        Files.writeString(kpiFile, KPI_HEAD + KPI_BODY, StandardCharsets.UTF_8);
        Files.deleteIfExists(Path.of(metricFile + ".bak"));
        Files.deleteIfExists(Path.of(kpiFile + ".bak"));
        ensureRoleAndUsers();
    }

    /** 夹具按「状态」补齐：角色按名字找，找不到才建；权限被改过就改回来。 */
    private void ensureRoleAndUsers() {
        List<String> wanted = List.of(RolePermissionService.P_REPORTS,
            RolePermissionService.P_REPORT_MANAGE);
        RolePermissionService.Role role = roleService.listRoles().stream()
            .filter(r -> EDITOR_ROLE.equals(r.name))
            .findFirst()
            .orElseGet(() -> roleService.create(EDITOR_ROLE, wanted));
        if (!role.permissions.equals(wanted)) {
            roleService.update(role.id, EDITOR_ROLE, wanted, null);
        }
        if (userService.userExists(EDITOR) && !role.id.equals(userService.resolveRoleId(EDITOR))) {
            userService.deleteUser(EDITOR);
        }
        if (!userService.userExists(EDITOR)) {
            userService.createUser(EDITOR, "大屏指标测试编辑器", "USER", role.id, null, null);
        }
        if (userService.mustChangePassword(EDITOR)) {
            userService.changePassword(EDITOR, "ScreenEdit2026Test", false);
        }
        if (!userService.userExists(VIEWER)) {
            userService.createUser(VIEWER, "大屏指标测试查看者", "USER",
                RolePermissionService.R_USER, null, null);
        }
    }

    // ---------------------------------------------------- 读

    @Test
    @WithMockUser(username = EDITOR, roles = "USER")
    void editor_listsMetricsAndReadsMetricFile() throws Exception {
        mockMvc.perform(get("/api/admin/screen/metrics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.metrics[?(@.id=='" + METRIC + "')].file")
                .value(METRIC + ".sql"));
        mockMvc.perform(get("/api/admin/screen/metrics/" + METRIC + "/sql"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value(ORIGINAL));
    }

    @Test
    @WithMockUser(username = EDITOR, roles = "USER")
    void unknownMetric_readsAsNotFound() throws Exception {
        mockMvc.perform(get("/api/admin/screen/metrics/no_such_metric/sql"))
            .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------- 写：成功路径

    @Test
    @WithMockUser(username = EDITOR, roles = "USER")
    void editor_savesFileAndBacksUpThePreviousOne() throws Exception {
        mockMvc.perform(put("/api/admin/screen/metrics/" + METRIC + "/sql")
                .with(csrf())
                .contentType("application/json")
                .content("{\"content\":" + jsonQuote(EDITED) + "}"))
            .andExpect(status().isOk());
        assertEquals(EDITED, Files.readString(metricFile, StandardCharsets.UTF_8), "新语句应已落盘");
        assertTrue(Files.exists(Path.of(metricFile + ".bak")), "原文件应留 .bak 备份");
        assertEquals(ORIGINAL, Files.readString(Path.of(metricFile + ".bak"), StandardCharsets.UTF_8),
            ".bak 里必须是改动前的那一份，否则回滚等于丢口径");
    }

    // ---------------------------------------------------- 写：拒绝路径不得留下痕迹

    @Test
    @WithMockUser(username = EDITOR, roles = "USER")
    void writeStatement_isRejectedBeforeAnythingIsWritten() throws Exception {
        mockMvc.perform(put("/api/admin/screen/metrics/" + METRIC + "/sql")
                .with(csrf())
                .contentType("application/json")
                .content("{\"content\":" + jsonQuote(DANGEROUS) + "}"))
            .andExpect(status().isBadRequest());
        assertUnchanged();
    }

    /** 多语句：一次请求带两条语句，第一条是 SELECT，只看前缀校验会放过。 */
    @Test
    @WithMockUser(username = EDITOR, roles = "USER")
    void multiStatement_isRejectedBeforeAnythingIsWritten() throws Exception {
        mockMvc.perform(put("/api/admin/screen/metrics/" + METRIC + "/sql")
                .with(csrf())
                .contentType("application/json")
                .content("{\"content\":" + jsonQuote(MULTI_STATEMENT) + "}"))
            .andExpect(status().isBadRequest());
        assertUnchanged();
    }

    @Test
    @WithMockUser(username = EDITOR, roles = "USER")
    void missingYamlHead_isRejected() throws Exception {
        mockMvc.perform(put("/api/admin/screen/metrics/" + METRIC + "/sql")
                .with(csrf())
                .contentType("application/json")
                .content("{\"content\":\"SELECT 1 FROM DUAL\"}"))
            .andExpect(status().isBadRequest());
        assertUnchanged();
    }

    /**
     * 指标ID 是文件名而不是路径：带扩展名的形态（等价于 {@code <id>.sql.sql}，
     * 也是 {@code ../} 这类越界写法被白名单挡下的同一道门）必须按「指标不存在」拒掉，
     * 且不能在目录里留下任何新文件。
     */
    @Test
    @WithMockUser(username = EDITOR, roles = "USER")
    void metricIdWithExtension_isNotFoundAndWritesNothing() throws Exception {
        mockMvc.perform(put("/api/admin/screen/metrics/" + METRIC + ".sql/sql")
                .with(csrf())
                .contentType("application/json")
                .content("{\"content\":" + jsonQuote(EDITED) + "}"))
            .andExpect(status().isBadRequest());
        assertFalse(Files.exists(Path.of(scriptsDir).toAbsolutePath().normalize()
            .resolve(METRIC + ".sql.sql")), "不该凭非法ID形态写出新文件");
    }

    // ---------------------------------------------------- 面板标题

    /** 只改标题：SQL 正文逐字不变，且头部重新生成时既有键一个都不能丢。 */
    @Test
    @WithMockUser(username = EDITOR, roles = "USER")
    void panelTitle_rewritesHeadButNeverTouchesSqlBody() throws Exception {
        saveTitles(KPI_METRIC, "{\"labelOutpatient\":\"门诊人次\"}");
        String saved = Files.readString(kpiFile, StandardCharsets.UTF_8);
        assertEquals(KPI_BODY, sqlPart(saved), "改标题不许动 SQL 正文");
        assertTrue(saved.contains("labelOutpatient"), saved);
        // 头部是 dump 出来的，不是白名单重建，丢任何一个既有键就等于顺手改了运行行为
        assertTrue(saved.contains("start_date") && saved.contains("end_date"), "params 必须原样保留");
        assertTrue(saved.contains("timeout: 30"), "服务层不认识的既有键也必须保留");
        assertTrue(saved.contains("区间经营指标"), "name 必须保留");
    }

    /** 槽位归属：{@code labelHourly} 是 range_hourly 的面板，塞给 range_kpi 必须拒且不留痕。 */
    @Test
    @WithMockUser(username = EDITOR, roles = "USER")
    void panelTitle_belongingToAnotherMetric_isRejected() throws Exception {
        mockMvc.perform(put("/api/admin/screen/metrics/" + KPI_METRIC + "/sql").with(csrf())
                .contentType("application/json")
                .content("{\"content\":" + jsonQuote(KPI_HEAD + KPI_BODY)
                    + ",\"panelTitles\":{\"labelHourly\":\"分时人次\"}}"))
            .andExpect(status().isBadRequest());
        assertKpiUnchanged();
    }

    /** 20 字上限是给 1920×1080 那块屏的标题栏留的，不是随手写的数。 */
    @Test
    @WithMockUser(username = EDITOR, roles = "USER")
    void overlongPanelTitle_isRejected() throws Exception {
        mockMvc.perform(put("/api/admin/screen/metrics/" + KPI_METRIC + "/sql").with(csrf())
                .contentType("application/json")
                .content("{\"content\":" + jsonQuote(KPI_HEAD + KPI_BODY)
                    + ",\"panelTitles\":{\"labelRevenue\":\"一二三四五六七八九十一二三四五六七八九零一二三\"}}"))
            .andExpect(status().isBadRequest());
        assertKpiUnchanged();
    }

    /**
     * 换行注入：标题里塞一个换行再跟一行 YAML 键。
     * 头部若是字符串拼接，这里就能凭空改掉 `name`，用一个 {@code report.manage}
     * 账号往指标文件里塞任意键，与报表侧那次 front matter 注入同源。现在用的是
     * {@code Yaml.dump}，控制字符在校验阶段就被拒；两条防线都要有，所以都断言。
     *
     * <p>值刻意压在 20 字以内：真机探针跑过一次，超长值会先被长度校验拦下，
     * 那样这条用例根本测不到控制字符那道闸（断言里点名报错原因就是为了不再被糊过去）。
     */
    @Test
    @WithMockUser(username = EDITOR, roles = "USER")
    void panelTitleWithNewline_isRejectedAndCannotInjectKeys() throws Exception {
        mockMvc.perform(put("/api/admin/screen/metrics/" + KPI_METRIC + "/sql").with(csrf())
                .contentType("application/json")
                .content("{\"content\":" + jsonQuote(KPI_HEAD + KPI_BODY)
                    + ",\"panelTitles\":{\"labelRevenue\":\"门诊\\nname: 被篡改\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("控制字符")));
        String saved = Files.readString(kpiFile, StandardCharsets.UTF_8);
        assertEquals(KPI_HEAD + KPI_BODY, saved, "被注入的 name 绝不能落盘");
    }

    /** 全部清空 = 移除 {@code panel-titles} 键（大屏还原成 HTML 里的默认文字）。 */
    @Test
    @WithMockUser(username = EDITOR, roles = "USER")
    void clearingPanelTitles_removesTheKey() throws Exception {
        saveTitles(KPI_METRIC, "{\"labelRevenue\":\"总收入\"}");
        assertTrue(Files.readString(kpiFile, StandardCharsets.UTF_8).contains("panel-titles"));
        saveTitles(KPI_METRIC, "{\"labelRevenue\":\"\"}");
        String saved = Files.readString(kpiFile, StandardCharsets.UTF_8);
        assertFalse(saved.contains("panel-titles"), "清空后要整键移除，留个空映射没意义");
        assertEquals(KPI_BODY, sqlPart(saved));
    }

    /** 列表接口要回显「该指标有哪些槽位、默认文字是什么、当前填了什么」， 弹窗输入框全靠它。 */
    @Test
    @WithMockUser(username = EDITOR, roles = "USER")
    void list_exposesPanelSlotsWithTheirCurrentValues() throws Exception {
        saveTitles(KPI_METRIC, "{\"labelInpatient\":\"在院\"}");
        mockMvc.perform(get("/api/admin/screen/metrics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.metrics[?(@.id=='" + KPI_METRIC + "')].panelSlots[?(@.key=='labelInpatient')].current")
                .value("在院"))
            .andExpect(jsonPath(
                "$.metrics[?(@.id=='" + KPI_METRIC + "')].panelSlots[?(@.key=='labelOutpatient')].autoRenamed")
                .value(true))
            // 没注册过的指标（本轮的 it_screen_metric）不该冒出 panelSlots 字段
            .andExpect(jsonPath(
                "$.metrics[?(@.id=='" + METRIC + "')].panelSlots").isEmpty());
    }

    private void saveTitles(String metricId, String titlesJson) throws Exception {
        mockMvc.perform(put("/api/admin/screen/metrics/" + metricId + "/sql").with(csrf())
                .contentType("application/json")
                .content("{\"content\":" + jsonQuote(KPI_HEAD + KPI_BODY)
                    + ",\"panelTitles\":" + titlesJson + "}"))
            .andExpect(status().isOk());
    }

    /** YAML 头之后、结束分隔行那一个换行之后的全部正文，用来断言 SQL 正文一字未动。 */
    private static String sqlPart(String content) {
        int first = content.indexOf("---");
        int closingLine = content.indexOf("\n---", first + 3);
        int bodyStart = content.indexOf('\n', closingLine + 1) + 1;
        return content.substring(bodyStart);
    }

    private void assertKpiUnchanged() throws Exception {
        assertEquals(KPI_HEAD + KPI_BODY, Files.readString(kpiFile, StandardCharsets.UTF_8),
            "被拒绝的标题不允许改动指标文件");
        assertFalse(Files.exists(Path.of(kpiFile + ".bak")),
            "没写成功就不该留下 .bak，留了说明落盘发生在校验之前");
    }

    // ---------------------------------------------------- 权限

    @Test
    @WithMockUser(username = VIEWER, roles = "USER")
    void viewerWithoutReportManage_isForbiddenOnEveryEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/screen/metrics"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/screen/metrics/" + METRIC + "/sql"))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/admin/screen/metrics/" + METRIC + "/sql")
                .with(csrf())
                .contentType("application/json")
                .content("{\"content\":" + jsonQuote(EDITED) + "}"))
            .andExpect(status().isForbidden());
        assertUnchanged();
    }

    /** 未登录：{@code /api/**} 统一 401（不能退化成 403 让人以为账号有效）。 */
    @Test
    void anonymous_isUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/screen/metrics"))
            .andExpect(status().isUnauthorized());
    }

    private void assertUnchanged() throws Exception {
        assertEquals(ORIGINAL, Files.readString(metricFile, StandardCharsets.UTF_8),
            "被拒绝的 SQL 不允许改动指标文件");
        assertFalse(Files.exists(Path.of(metricFile + ".bak")),
            "没写成功就不该留下 .bak，留了说明落盘发生在校验之前");
    }

    private static String jsonQuote(String raw) {
        return '"' + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
