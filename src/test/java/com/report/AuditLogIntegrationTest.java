package com.report;

import com.report.controller.AuditController;
import com.report.service.AuditService;
import com.report.util.AuditStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 操作审计功能验收测试。
 *
 * <p>重点盯的是「静默不工作」那一类：审计这种东西，坏了不会有人发现，
 * 只会等到真要追责时才发现库里什么都没有。所以每条链路都要有断言钉住。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditLogIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuditStore auditStore;
    @Autowired private AuditService auditService;
    @Autowired private com.report.service.CommunityService communityService;

    /**
     * 测试用的唯一工号。
     *
     * <p>注意：不要用 {@code System.nanoTime() % 1_000_000}：测试数据目录
     * （{@code ${java.io.tmpdir}/report-it}）跨运行持久，同一天的审计日档会一直累加，
     * 取值域只有 1e6 时迟早跟上一轮的记录撞号，表现为「按工号应恰好命中 1 条」拿到 2 条，
     * 看上去像功能回归，实际是测试自己造的唯一性假设不成立（实测撞到）。
     */
    private static String uniq(String prefix) {
        return prefix + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void structuredEvent_reachesStoreAndIsQueryable() throws Exception {
        String operator = uniq("audu");
        String reportId = "smoke-report-" + System.nanoTime();
        auditService.audit("query", Map.of("operator", operator, "reportId", reportId,
            "rowCount", 7L, "durationMs", 12L, "success", true));

        // 直接读当天日档：绕开检索条件，先证明「真的落盘了」
        Path today = auditStore.dir().resolve("audit-" + LocalDate.now() + ".jsonl");
        assertTrue(Files.exists(today), "审计日档未生成: " + today);
        List<String> lines = Files.readAllLines(today, StandardCharsets.UTF_8);
        assertTrue(lines.stream().anyMatch(l -> l.contains(reportId) && l.contains(operator)),
            "落盘内容里没有这条记录");
        assertEquals(0, auditStore.getWriteFailures(), "落盘不应有失败计数");

        // 再证明「按工号查得回来」，这才是这个功能存在的理由
        AuditStore.Result r = auditStore.query(operator, "query", null, null, null, null, null, 1, 20);
        assertEquals(1, r.matched(), "按工号应恰好命中 1 条");
        Map<String, Object> row = r.rows().get(0);
        assertEquals(reportId, row.get("reportId"));
        assertNotNull(row.get("time"), "记录必须带人类可读时间，不能让前端自己解析毫秒");
    }

    @Test
    void filters_narrowByActionStatusAndKeyword() {
        String operator = uniq("audf");
        auditService.audit("export", Map.of("operator", operator, "reportId", "r-export",
            "rowCount", 3L, "success", true));
        auditService.audit("query", Map.of("operator", operator, "reportId", "r-query-fail",
            "success", false));

        // 实参顺序：user, action, reportId, dateFrom, dateTo, keyword, status, page, pageSize。
        // status 是 Integer，少写一个实参会让 page/pageSize 整体上移，编译器不报错的话
        // 断言就会打在错误的槽位上（本条测试就是这么绕了一圈的）。
        assertEquals(1, auditStore.query(operator, "export", null, null, null, null, null, 1, 20).matched(),
            "按动作应只命中 export 那条");
        assertEquals(1, auditStore.query(operator, null, null, null, null, null, Integer.valueOf(1), 1, 20).matched(),
            "status=1 只应命中成功的那条");
        assertEquals(1, auditStore.query(operator, null, null, null, null, null, Integer.valueOf(0), 1, 20).matched(),
            "status=0 只应命中失败的那条");
        assertEquals(2, auditStore.query(operator, null, null, null, null, null, null, 1, 20).matched(),
            "不加条件时两条都该在");
        assertEquals(1, auditStore.query(operator, null, null, null, null, "r-export", null, 1, 20).matched(),
            "关键词应能命中 reportId");
        assertEquals(0, auditStore.query(operator, null, null, null, null, "不存在的词", null, 1, 20).matched());
    }

    @Test
    void pagination_reportsTrueTotalAcrossPages() {
        String operator = uniq("audp");
        for (int i = 0; i < 5; i++) {
            auditService.audit("query", Map.of("operator", operator, "reportId", "page-" + i,
                "success", true));
        }
        AuditStore.Result p1 = auditStore.query(operator, null, null, null, null, null, null, 1, 2);
        AuditStore.Result p2 = auditStore.query(operator, null, null, null, null, null, null, 2, 2);
        // 这条断言防的是「凑满一页就提前退出」，那会让总数偏小、后面的页翻不到
        assertEquals(5, p1.matched(), "第 1 页也必须报出全量总数");
        assertEquals(5, p2.matched());
        assertEquals(2, p1.rows().size());
        assertEquals(2, p2.rows().size());
        assertEquals(3, p1.totalPages());
        assertFalse(p1.rows().get(0).equals(p2.rows().get(0)), "翻页必须换内容");
    }

    @Test
    void legacyAuditLineParser_coversTheBareLogFormat() throws Exception {
        Method parse = Class.forName("com.report.config.AuditLineAppender")
            .getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> one = (Map<String, Object>) parse.invoke(
            null, "action=createCommunity, code=A, name=A社区");
        assertEquals("createCommunity", one.get("action"));
        assertEquals("A", one.get("code"));
        assertEquals("A社区", one.get("name"));

        // 值里自带逗号（角色权限清单就是这种）不能被 ", " 拆坏
        @SuppressWarnings("unchecked")
        Map<String, Object> two = (Map<String, Object>) parse.invoke(
            null, "action=updateRole, roleId=r1, permissions=[dashboard.view, reports.view]");
        assertEquals("[dashboard.view, reports.view]", two.get("permissions"));

        // 解析不出 action 的内容不得抛出，也不得产生半成品记录
        @SuppressWarnings("unchecked")
        Map<String, Object> three = (Map<String, Object>) parse.invoke(null, "完全不是 k=v 的一行");
        assertFalse(three.containsKey("action"), "无 action 的文本不该被当成一条审计动作");
    }

    @Test
    @org.springframework.security.test.context.support.WithUserDetails("admin")
    void legacyBareAuditLine_getsOperatorFromRequestContext() {
        /* 裸 [AUDIT] 文本行里通常没有操作人；appender 必须从当前请求上下文补齐 ，
         * 否则管理端变更在审计库里全是「不知道谁干的」，这个功能就白做。
         * 走真实服务方法而不是手搓日志行：验的是「调用 → logback → appender → 库」整条链路。 */
        String code = "Z" + (System.nanoTime() % 1000);
        /* 测试数据目录跨运行持久化，代码可能已被上次运行占用，那就走更新路径，
         * 两条路径都会打一条裸 [AUDIT] 行，对本用例等价。 */
        try {
            communityService.create(code, "审计探针社区");
        } catch (IllegalArgumentException exists) {
            communityService.update(code, "审计探针社区", null);
        }
        AuditStore.Result r = auditStore.query(null, null, null, null, null, code, null, 1, 20);
        assertTrue(r.matched() >= 1, "管理端变更应能查到（裸 [AUDIT] 行走 appender 入库）");
        assertEquals("admin", r.rows().get(0).get("operator"),
            "操作人必须从请求上下文补齐，不能留空");
        assertNotNull(r.rows().get(0).get("clientIp"), "来源 IP 也应补齐");
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(
        value = "admin", roles = "ADMIN")
    void oneAdminAction_producesExactlyOneAuditRow() throws Exception {
        /* 回归护栏：曾经一次「新建用户」会落**两行**，服务层记 createUser、
         * 控制器再记 adminCreateUser。台账频次因此虚高一倍，而「谁建了这个账号」
         * 被拆成两条半信息。服务层那 11 处与控制器重复的裸 [AUDIT] 行已删除。
         *
         * 注意：用关键词（工号）而不是动作名来数条数：审计目录 ${java.io.tmpdir}/report-it
         * 跨运行持久化，按动作名数绝对条数会被历史运行污染。 */
        String uid = "9" + Long.toString(System.nanoTime(), 36);
        mockMvc.perform(post("/api/admin/users").with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + uid + "\",\"name\":\"审计去重校验\",\"role\":\"USER\"}"))
            .andExpect(status().isOk());

        AuditStore.Result r = auditStore.query(null, null, null, null, null, uid, null, 1, 50);
        assertEquals(1, r.matched(),
            "一次「新建用户」在审计台账里必须只有一行，实际=" + r.rows());
        assertEquals("adminCreateUser", r.rows().get(0).get("action"));
        assertEquals("admin", r.rows().get(0).get("operator"), "操作人应是发起请求的管理员");
    }

    @Test
    void tornTailLine_healedBeforeNextAppendSoNextRecordSurvives() throws Exception {
        Path today = auditStore.dir().resolve("audit-" + LocalDate.now() + ".jsonl");
        Files.createDirectories(today.getParent());
        // 模拟断电/强杀留下的半行：没有结尾换行
        Files.writeString(today, "{\"action\":\"query\",\"operator\":",
            StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        // 关键断言不是「坏行被跳过」，而是紧随其后的正常记录不会被粘坏：
        // 没有 append 前的尾行修复时，这条新记录会与残行合成一行、两条一起报废，
        // 现象就是「审计莫名少了一条」，事后完全无从发现。
        String survivor = uniq("audt");
        auditService.audit("query", Map.of("operator", survivor, "reportId", "r-survivor",
            "success", true));
        AuditStore.Result r = auditStore.query(survivor, null, null, null, null, null, null, 1, 20);
        assertEquals(1, r.matched(), "残行之后的新记录必须还能按工号查到");
        assertEquals("r-survivor", r.rows().get(0).get("reportId"));
        assertTrue(r.malformed() >= 1, "残行本身要被计数，不能无声消失");
        assertEquals(0, auditStore.getWriteFailures(), "尾行修复不该记成写失败");
    }

    @Test
    void retentionPurge_deletesOnlyExpiredDailyFiles() throws Exception {
        Path dir = auditStore.dir();
        Files.createDirectories(dir);
        Path expired = dir.resolve("audit-2020-01-01.jsonl");
        Path fresh = dir.resolve("audit-" + LocalDate.now() + ".jsonl");
        Files.writeString(expired, "", StandardCharsets.UTF_8);

        int removed = auditStore.purgeExpired();
        assertFalse(Files.exists(expired), "超过留存期的日档必须被删掉");
        assertTrue(removed >= 1);
        assertFalse(auditStore.purgeExpired() >= 1, "重复清理不应再删到东西（幂等）");
    }

    @Test
    @WithMockUser(username = "aud_viewer", roles = "USER")
    void auditEndpoints_requireLogsViewPermission() throws Exception {
        // aud_viewer 是 @WithMockUser 造出来的假用户，没有 logs.view，
        // 必须被后端拦下，前端藏菜单不算 enforcement。
        mockMvc.perform(get("/api/admin/audit")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/audit/export")).andExpect(status().isForbidden());
    }

    @Test
    void csvExport_escapesFormulaInjection() throws Exception {
        String operator = uniq("audc");
        auditService.audit("query", Map.of("operator", operator, "reportId", "=1+1", "success", true));
        ResponseEntity<?> re = new AuditController(auditStore, auditService).export(
            operator, null, null, null, null, null, null,
            org.springframework.security.core.userdetails.User.withUsername("audc")
                .password("x").roles("ADMIN").build());
        byte[] body = (byte[]) re.getBody();
        assertNotNull(body);
        String csv = new String(body, 3, body.length - 3, StandardCharsets.UTF_8);  // 跳过 BOM
        assertTrue(csv.contains("'=1+1"), "以 = 开头的单元格必须加前缀，防 CSV 公式注入: " + csv);
    }

    @Test
    void exportResponse_isNotCacheable() throws Exception {
        ResponseEntity<?> re = new AuditController(auditStore, auditService).export(
            null, null, null, null, null, null, null,
            org.springframework.security.core.userdetails.User.withUsername("audx")
                .password("x").roles("ADMIN").build());
        assertEquals("no-store", re.getHeaders().getCacheControl(),
            "审计台账含工号，不能缓存在公共诊室终端上");
    }

    @Test
    @WithMockUser(value = "admin", roles = "ADMIN")
    void auditViewerRecord_carriesClientIpAndPeer() throws Exception {
        /* 「谁在追溯别人」恰恰是最需要定位到机器的一条，其他动作都有 clientIp，
         * 而这条曾经全是 null（auditView/auditExport 走 auditRecordOfViewer 这条旁路，
         * 一度没调 enrichContext）。断言只盯这一件事，别混进导出内容那条测试里。
         *
         * <p>探针动作用的是 auditExport 而不是 auditView：admin 的 auditView 现在被白名单
         * 挡掉（见 {@link #whitelistSuppressesOnlyAdminsListedActions()}），而本测试上下文里
         * 只有内置 admin 过得了 logs.view（@WithMockUser 造的假用户没有角色映射，会 403）。
         * 两者出自同一个构造器，换动作不改变这条测试要验的东西。 */
        String target = uniq("audip");
        mockMvc.perform(get("/api/admin/audit/export").param("user", target))
            .andExpect(status().isOk());

        AuditStore.Result r = auditStore.query(null, "auditExport", null, null, null, null, null, 1, 50);
        Map<String, Object> rec = r.rows().stream()
            .filter(x -> target.equals(x.get("targetUser")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("导别人的台账应留下一条 auditExport，实际没有"));

        assertNotNull(rec.get("clientIp"), "auditExport 必须有来源 IP");
        assertNotNull(rec.get("peerAddr"), "auditExport 还应有不可伪造的对端地址");
        assertEquals("admin", rec.get("operator"), "操作人是「点导出的人」，不是被查的人");
    }

    @Test
    void whitelistSuppressesOnlyAdminsListedActions() throws Exception {
        /* 白名单的边界就是这条测试要钉住的三件事：
         *   ① admin + 名单内动作 → 不落盘；
         *   ② admin + 名单外动作（查询/导出/写操作/登录失败）→ 照落，
         *      否则等于给超管开了无痕迹通道；
         *   ③ 别的工号 + 名单内动作 → 照落，白名单只针对那一个账号。 */
        assertTrue(auditStore.isExclusionActive(), "测试上下文应带上 application.yml 的白名单默认值");
        long before = auditStore.getSuppressed();
        String marker = "wl-" + System.nanoTime();

        auditStore.append(new java.util.LinkedHashMap<String, Object>(Map.of(
            "operator", "admin", "action", "auditView", "marker", marker + "-view")));
        auditStore.append(new java.util.LinkedHashMap<String, Object>(Map.of(
            "operator", "admin", "action", "loginSuccess", "marker", marker + "-login")));
        auditStore.append(new java.util.LinkedHashMap<String, Object>(Map.of(
            "operator", "admin", "action", "query", "reportId", "r", "marker", marker + "-query")));
        auditStore.append(new java.util.LinkedHashMap<String, Object>(Map.of(
            "operator", "admin", "action", "loginFailed", "marker", marker + "-failed")));
        auditStore.append(new java.util.LinkedHashMap<String, Object>(Map.of(
            "operator", uniq("wlu"), "action", "auditView", "marker", marker + "-other-view")));

        assertEquals(before + 2, auditStore.getSuppressed(), "只应挡掉 admin 的 auditView 与 loginSuccess 两条");

        Path today = auditStore.dir().resolve("audit-" + LocalDate.now() + ".jsonl");
        List<String> lines = Files.readAllLines(today, StandardCharsets.UTF_8);
        assertTrue(lines.stream().noneMatch(l -> l.contains(marker + "-view")),
            "admin 的 auditView 不该进台账");
        assertTrue(lines.stream().noneMatch(l -> l.contains(marker + "-login")),
            "admin 的 loginSuccess 不该进台账");
        assertTrue(lines.stream().anyMatch(l -> l.contains(marker + "-query")),
            "admin 的报表查询必须照记，免掉它等于超管查患者数据无痕迹");
        assertTrue(lines.stream().anyMatch(l -> l.contains(marker + "-failed")),
            "admin 的登录失败必须照记，那是超管账号被爆破的信号，不是巡检噪声");
        assertTrue(lines.stream().anyMatch(l -> l.contains(marker + "-other-view")),
            "白名单只针对 admin，别的工号翻审计照样留痕");
    }

    @Test
    @WithMockUser(value = "admin", roles = "ADMIN")
    void auditListSurfacesExclusionNote() throws Exception {
        /* 台账"少了一类记录"必须在页面上是句明话：口径由接口给，前端只负责显示，
         * 免得配置改了、界面还写着旧的一套。 */
        mockMvc.perform(get("/api/admin/audit"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exclusionNote").isString())
            .andExpect(jsonPath("$.exclusionNote", org.hamcrest.Matchers.containsString("admin")));
    }

    @Test
    @WithMockUser(value = "admin", roles = "ADMIN")
    void exportEndpoint_servesCsvThroughTheRealHttpMapping() throws Exception {
        /* 上面两条 export 测试是**直接 new controller 调方法**，绕开了 Servlet 映射，
         * 所以它们证明不了「浏览器点『导出 CSV』那个 URL 打得通、响应头是对的」。
         * 这里走真映射：@GetMapping("/export") 写错路径、内容协商被改坏、
         * BOM 丢了（Excel 直开就乱码）， 都只在这一条里才会红。 */
        org.springframework.test.web.servlet.MvcResult res =
            mockMvc.perform(get("/api/admin/audit/export")).andExpect(status().isOk()).andReturn();

        byte[] body = res.getResponse().getContentAsByteArray();
        assertTrue(body.length >= 3, "响应体不能是空的");
        assertEquals((byte) 0xEF, body[0], "必须有 UTF-8 BOM，否则 Excel 双击打开中文列必乱码");
        assertEquals((byte) 0xBB, body[1]);
        assertEquals((byte) 0xBF, body[2]);

        String csv = new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("时间,操作人,动作,报表,目标工号,结果,行数,耗时ms,客户端IP,对端IP"),
            "表头是文档承诺的固定列，不能漂：" + csv.substring(0, Math.min(60, csv.length())));

        String ct = res.getResponse().getContentType();
        assertNotNull(ct);
        assertTrue(ct.toLowerCase().contains("text/csv"), "content-type 应为 text/csv，实际=" + ct);
        String cd = res.getResponse().getHeader("Content-Disposition");
        assertNotNull(cd, "必须带 Content-Disposition，否则浏览器是「打开」而不是「下载」");
        assertTrue(cd.contains("attachment"), cd);
        assertTrue(cd.contains("audit_"), "文件名要以 audit_ 开头（含日期），实际=" + cd);
        assertEquals("no-store", res.getResponse().getHeader("Cache-Control"));
    }
}
