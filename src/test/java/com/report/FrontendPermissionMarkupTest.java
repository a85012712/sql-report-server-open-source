package com.report;

import com.report.service.RolePermissionService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前端「权限点驱动显隐」的结构守卫（不依赖运行中的服务）。
 *
 * <p>背景 / 缺陷（优化记录 D7）：{@code index.html} 里带 {@code admin-only} 类的元素，
 * 显隐原本有两条互不相同的判据：
 * <ul>
 *   <li>侧栏菜单（11 个 {@code .nav-item}）， 带 {@code data-perm}，由 {@code app.js}
 *       按权限点显示，是对的；</li>
 *   <li>首页「快捷操作」里的两个按钮（SQL导入 / 用户管理）， 当时没有 {@code data-perm}，
 *       只能靠一段 legacy 判定显示：{@code if (user.role === 'ADMIN') 把所有 .admin-only 设为可见}。</li>
 * </ul>
 *
 * <p>后果：任何自定义角色只要有 {@code report.manage} / {@code users.manage}，
 * 侧栏菜单看得见、点得进去、功能真能用，首页的快捷按钮却不出现 ，
 * 同一个功能两处判据不同。而超管（legacy {@code role=ADMIN}）两处都满足，
 * 用超管永远测不出来（要站在「受限角色」的视角才看得见）。
 *
 * <p>本测试守住的规则：凡带 {@code admin-only} 类的元素，都必须声明 {@code data-perm}。
 * 这样它的显隐就一定有权限点依据，不会再退化成「只有超管能看见」。
 * 同时禁止 {@code app.js} 再出现「按 legacy 字段批量放开 {@code .admin-only}」的写法。
 *
 * <p>为什么要有这条（而不是只靠渲染验证）：渲染验证需要服务在运行，
 * 且必须用「自定义角色」身份才有判别力；这条结构守卫在任何一次 {@code mvn test} 都会跑，
 * 能在写代码的当下就把这类退化拦下来。
 */
class FrontendPermissionMarkupTest {

    private static Path projectFile(String relative) {
        Path p = Path.of(relative);
        if (!Files.exists(p)) {
            p = Path.of(System.getProperty("user.dir")).resolve(relative);
        }
        return p;
    }

    private static String read(String relative) throws IOException {
        Path p = projectFile(relative);
        assertTrue(Files.exists(p), "找不到文件（请确认 mvn 的工作目录是项目根）: " + p.toAbsolutePath());
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 取出所有「class 里含 admin-only」的起始标签 */
    private static List<String> adminOnlyTags(String html) {
        Pattern tag = Pattern.compile("<[a-zA-Z][^>]*\\bclass=\"[^\"]*\\badmin-only\\b[^\"]*\"[^>]*>");
        Matcher m = tag.matcher(html);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group());
        return out;
    }

    @Test
    void everyAdminOnlyElementDeclaresDataPerm() throws IOException {
        String html = read("src/main/resources/templates/index.html");
        List<String> tags = adminOnlyTags(html);

        // 防「测试空转」：当前已知 11 个侧栏菜单 + 2 个首页快捷按钮 + 1 个报表管理页按钮 = 14 个
        assertTrue(tags.size() >= 14,
            "带 admin-only 类的元素少于预期（当前 " + tags.size() + " 个），"
                + "若确实删减了菜单/按钮请同步更新本测试的下限");

        List<String> missing = new ArrayList<>();
        for (String t : tags) {
            if (!t.contains("data-perm=")) missing.add(t.trim());
        }
        assertTrue(missing.isEmpty(),
            "以下带 admin-only 类的元素没有 data-perm，它们的显隐只能靠 legacy 判定，"
                + "自定义角色（有对应权限点但 legacy role=USER）将看不到它们:\n  "
                + String.join("\n  ", missing));
    }

    @Test
    void dashboardShortcutButtonsArePermissionDriven() throws IOException {
        String html = read("src/main/resources/templates/index.html");
        // 首页「快捷操作」里的两个按钮：必须各自带正确的 data-perm
        assertTrue(Pattern.compile(
                "<button[^>]*data-perm=\"report\\.manage\"[^>]*onclick=\"showSqlImport\\(\\)\"[^>]*>")
                .matcher(html).find(),
            "首页「SQL导入」按钮必须带 data-perm=\"report.manage\"（与侧栏 sqlimport 菜单同源）");
        assertTrue(Pattern.compile(
                "<button[^>]*data-perm=\"users\\.manage\"[^>]*onclick=\"navigateTo\\('users'\\)\"[^>]*>")
                .matcher(html).find(),
            "首页「用户管理」按钮必须带 data-perm=\"users.manage\"（与侧栏 users 菜单同源）");
    }

    @Test
    void appJsDoesNotRevealAdminOnlyByLegacyField() throws IOException {
        String js = read("src/main/resources/static/js/app.js");
        assertFalse(js.contains("querySelectorAll('.admin-only')")
                || js.contains("querySelectorAll(\".admin-only\")"),
            "app.js 不应再按 legacy 字段批量放开 .admin-only，"
                + "那会让自定义角色看不到首页快捷按钮（见优化记录 D7）");
    }

    @Test
    void appJsDrivesVisibilityByDataPerm() throws IOException {
        String js = read("src/main/resources/static/js/app.js");
        assertTrue(js.contains("querySelectorAll('[data-perm]')"),
            "app.js 必须用统一的 '[data-perm]' 选择器驱动显隐（菜单与快捷按钮同一条规则）");
        // 不能只处理菜单，否则又会把按钮漏掉
        assertFalse(js.contains("querySelectorAll('.nav-item[data-perm]')"),
            "app.js 的显隐规则应收敛为 '[data-perm]'，而不是只处理 '.nav-item[data-perm]'");
    }

    // ---------------------------------------------------------------- D8

    /**
     * 截取 app.js 中某个函数的**函数体**（按花括号配平，从函数头后的第一个 `{` 到配对的 `}`）。
     *
     * <p>注意：不能用「到下一个 `async function` 为止」这种朴素切法，那样会把**下一个函数的
     * 文档注释**也算进来，而注释里常常正好提到被禁的端点名，造成误报（本测试踩过）。
     * 同理，断言前要把行注释剥掉：自己的说明性注释里可能就写着那个标识符。
     */
    private static String functionBody(String js, String header) {
        int start = js.indexOf(header);
        assertTrue(start >= 0, "app.js 里找不到函数: " + header);
        int open = js.indexOf('{', start);
        assertTrue(open > start, "函数头后找不到 '{': " + header);
        int depth = 0;
        for (int p = open; p < js.length(); p++) {
            char c = js.charAt(p);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return stripLineComments(js.substring(open, p + 1));
            }
        }
        throw new AssertionError("函数体花括号不配平: " + header);
    }

    /** 剥掉 `//` 行注释（只用于「不该出现某标识符」这类断言，避免被自己的注释误伤） */
    private static String stripLineComments(String src) {
        return src.replaceAll("(?m)//[^\n]*$", "");
    }

    /**
     * 「刷新报表列表」不能要求管理权限。
     *
     * <p>缺陷 D8b：三个「刷新」按钮（顶栏 / 工作台快捷操作 / 报表中心）**人人可见**，
     * 但函数里原先还调了 `POST /api/reports/refresh`（需 `report.manage`），
     * 非管理员一点就静默 403，且前端不检查 `res.ok`、照样弹「已刷新」。
     * 现在「重新拉取列表」（人人可用）与「从磁盘重载定义」（管理动作）必须分开。
     *
     * <p>实测过这条断言的判别力：把旧写法放回 refreshReports 后，
     * `render-check-personnel.mjs` 第 5.5 节会报 `POST /api/reports/refresh → 403`。
     */
    @Test
    void listRefreshMustNotCallAdminOnlyReload() throws IOException {
        String js = read("src/main/resources/static/js/app.js");
        String body = functionBody(js, "async function refreshReports(");
        assertFalse(body.contains("/api/reports/refresh"),
            "refreshReports()（人人可见的「刷新」按钮）不得调用需要 report.manage 的 "
                + "POST /api/reports/refresh，应改用 loadReports()，"
                + "磁盘重载请放到 reloadReportDefinitions()（见 D8）");
    }

    /**
     * 只有管理权限才能读的接口，不能放在「所有用户都会执行」的初始化路径上。
     *
     * <p>缺陷 D8a：`loadDefaultDataSourceId()` 原先在 `DOMContentLoaded` 里对**所有人**执行，
     * 而它读的 `/api/admin/datasources/default-id` 在类级 `datasource.manage` 下 ，
     * 每个非管理员每次打开页面都静默吃一个 403；而该值只在「数据源管理」页用到。
     */
    @Test
    void adminOnlyFetchMustNotSitInGlobalInit() throws IOException {
        String js = read("src/main/resources/static/js/app.js");
        int start = js.indexOf("document.addEventListener('DOMContentLoaded'");
        assertTrue(start >= 0, "app.js 里找不到 DOMContentLoaded 初始化块");
        int end = js.indexOf("\n// ==================== ", start);
        assertTrue(end > start, "定位不到 DOMContentLoaded 块的结尾分隔注释");
        // 剥掉注释再断言：初始化块里那段解释「为什么不要调它」的注释本身会提到函数名
        String initBlock = stripLineComments(js.substring(start, end));
        assertFalse(initBlock.contains("loadDefaultDataSourceId("),
            "DOMContentLoaded 初始化块里不得调 loadDefaultDataSourceId()，"
                + "它读的接口需要 datasource.manage，非管理员会静默 403（见 D8）");
    }

    // ---------------------------------------------------------------- D9

    /**
     * 侧栏「报表中心」菜单必须按 {@code reports.view} 显隐。
     *
     * <p>缺陷 D9：这个菜单原先**没有 {@code data-perm}**，对**所有登录用户**可见。
     * 而「大屏查看」角色（只有 {@code screen.view}）点进去能拉到列表（列表端点当时不判权限点），
     * 一点开报表就 403。用超管或任何有 {@code reports.view} 的角色都测不出来。
     *
     * <p>判别力：修好之前，用「大屏查看」角色跑一圈会在
     * {@code GET /api/report/{id}/options/community} 上撞到 403。
     */
    @Test
    void reportsNavItemIsPermissionDriven() throws IOException {
        String html = read("src/main/resources/templates/index.html");
        Matcher nav = Pattern.compile("<li[^>]*data-page=\"reports\"[^>]*>").matcher(html);
        assertTrue(nav.find(), "找不到侧栏「报表中心」菜单项（data-page=\"reports\"）");
        assertTrue(nav.group().contains("data-perm=\"reports.view\""),
            "侧栏「报表中心」菜单必须带 data-perm=\"reports.view\"，否则只有 screen.view 的"
                + "「大屏查看」角色也能看见它，点进去查报表必 403（见 D9）。当前：" + nav.group().trim());
    }

    /**
     * 工作台里所有「报表中心」入口都必须自带 {@code data-perm="reports.view"}。
     *
     * <p>这些元素**没有带 data-perm 的祖先**可以依赖（不像「最近访问」卡片里的
     * 「查看全部」链接，它父级 {@code .recent-section} 已经整体按权限隐藏了），
     * 所以必须逐个声明。
     */
    @Test
    void dashboardReportEntriesArePermissionDriven() throws IOException {
        String html = read("src/main/resources/templates/index.html");
        // ① 直接调报表函数的元素（刷新报表列表）
        Matcher t = Pattern.compile("<[a-zA-Z][^>]*onclick=\"refreshReports\\(\\)\"[^>]*>").matcher(html);
        int seen = 0;
        while (t.find()) {
            seen++;
            assertTrue(t.group().contains("data-perm=\"reports.view\""),
                "调用 refreshReports() 的元素必须带 data-perm=\"reports.view\"（见 D9）。当前："
                    + t.group().trim());
        }
        assertTrue(seen >= 2, "预期至少 2 处「刷新报表列表」入口（工作台 / 报表中心），实际 " + seen);

        // ② 防空转：整个页面按 reports.view 显隐的元素应成规模
        int cnt = html.split("data-perm=\"reports\\.view\"", -1).length - 1;
        assertTrue(cnt >= 5,
            "index.html 里 data-perm=\"reports.view\" 少于 5 处（当前 " + cnt + "）， "
                + "侧栏菜单 + 报表总数卡 + 进入报表中心 + 刷新报表列表 + 最近访问，缺一即有入口漏判");
    }

    /**
     * 报表的「读」端点必须与「查」端点同源，都要求 {@code reports.view}。
     *
     * <p>缺陷 D9 的根因：{@code GET /api/reports}（列表）与 {@code GET /api/report/{id}}（详情）
     * **没有任何 {@code @PreAuthorize}**，只按「报表分组」过滤；而
     * {@code /api/report/{id}/query}、{@code /options} 却要 {@code reports.view}。
     * 「谁能看报表」于是有两个判据，列表说能看、查询说不能看。
     *
     * <p>规则收敛为：{@code reports.view} 决定「能不能看报表」，分组只决定「能看到哪些报表」。
     * {@code report.manage} 一并放行，因为报表管理页也复用列表端点。
     */
    @Test
    void reportReadEndpointsRequireReportsView() throws IOException {
        String ctrl = read("src/main/java/com/report/controller/ReportController.java");
        for (String path : new String[]{"/api/reports", "/api/report/{id}"}) {
            int idx = ctrl.indexOf("@GetMapping(\"" + path + "\")");
            assertTrue(idx >= 0, "ReportController 里找不到端点 @GetMapping(\"" + path + "\")");
            // 取该注解往后的一小段窗口：紧随其后的 @PreAuthorize + 方法签名
            String window = ctrl.substring(idx, Math.min(ctrl.length(), idx + 400));
            assertTrue(window.contains("@PreAuthorize") && window.contains("reports.view"),
                "端点 " + path + " 必须声明含 reports.view 的 @PreAuthorize，"
                    + "否则「能不能看报表」又变成两个判据（见 D9）");
        }
    }

    // ---------------------------------------------------------------- D10

    /**
     * 工作台统计必须**按权限点**决定去不去调接口。
     *
     * <p>缺陷 D10：统计卡的数字原先写在 {@code index.html} 的内联脚本里，
     * 每个用户打开页面都无条件调 {@code /api/admin/datasources}（需 {@code datasource.manage}），
     * **每一个非管理员角色**都在静默吃 403，「数据源」卡片永远显示 {@code -}。
     * 修法不是「把卡片藏起来」了事（那样接口照样被调、照样 403），
     * 而是把统计逻辑移进 app.js 并放在权限判定之内。
     */
    @Test
    void dashboardStatsMustBePermissionGated() throws IOException {
        String js = read("src/main/resources/static/js/app.js");
        String body = functionBody(js, "async function loadDashboardStats(");
        int permDs = body.indexOf("datasource.manage");
        int fetchDs = body.indexOf("/api/admin/datasources");
        assertTrue(fetchDs >= 0, "loadDashboardStats() 里找不到 /api/admin/datasources（统计卡数据源数）");
        assertTrue(permDs >= 0 && permDs < fetchDs,
            "loadDashboardStats() 必须在调 /api/admin/datasources **之前**判 datasource.manage，"
                + "否则非管理员每次开页面都静默 403（见 D10）");
        int permRep = body.indexOf("reports.view");
        int fetchRep = body.indexOf("/api/reports");
        assertTrue(fetchRep >= 0, "loadDashboardStats() 里找不到 /api/reports（统计卡报表数）");
        assertTrue(permRep >= 0 && permRep < fetchRep,
            "loadDashboardStats() 必须在调 /api/reports **之前**判 reports.view（见 D9/D10）");
    }

    /**
     * {@code index.html} 的内联脚本里不得直接调 {@code /api/admin/*}。
     *
     * <p>内联脚本是「所有用户打开页面都会执行」的路径，而 {@code /api/admin/*} 几乎都要管理权限点。
     * 这类统计/加载逻辑应放进 app.js，与 {@code loadUserInfo()} 拿到权限点之后再决定调不调。
     */
    @Test
    void inlineScriptMustNotFetchAdminEndpoints() throws IOException {
        String html = read("src/main/resources/templates/index.html");
        Matcher m = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>(.*?)</script>", Pattern.DOTALL)
            .matcher(html);
        List<String> offenders = new ArrayList<>();
        while (m.find()) {
            String body = m.group(1);
            if (body.contains("fetch('/api/admin/") || body.contains("fetch(\"/api/admin/")) {
                offenders.add(body.trim().substring(0, Math.min(160, body.trim().length())));
            }
        }
        assertTrue(offenders.isEmpty(),
            "index.html 的内联脚本里不得直接调 /api/admin/*，它每个用户打开页面都会执行，"
                + "非管理员必然 403（见 D10）。请移入 app.js 并按权限点判断：\n  "
                + String.join("\n  ", offenders));
    }

    /**
     * 凡调 {@code doExport()} 的元素，都必须带 {@code data-perm="report.export"}；
     * 且 {@code doExport()} 自身必须在发请求**之前**判权限。
     *
     * <p>缺陷（优化记录 D13）：{@code POST /api/report/{id}/export} 要求
     * {@code report.export}，但导出按钮 {@code #exportBtn} 既没带 data-perm、
     * doExport() 里也不判权限，整个前端对 {@code report.export} 零引用。
     *
     * <p>「导出Excel」是**独立的权限点**（与「报表中心」分开），所以管理员完全可以配出
     * 「能看报表、但不许导出」的角色，那种角色看到的是一个**可见、可点**的导出按钮，
     * 点下去只得到一句语焉不详的「导出失败」。
     *
     * <p>和 D8/D10 一样：光把按钮藏起来不够，请求也必须拦，
     * 否则程序化调用（或按钮被别处触发）照样会打过去。所以这里两条都断言。
     */
    @Test
    void exportButtonAndFunctionArePermissionDriven() throws IOException {
        String html = read("src/main/resources/templates/index.html");
        Matcher t = Pattern.compile("<[a-zA-Z][^>]*onclick=\"doExport\\(\\)\"[^>]*>").matcher(html);
        int seen = 0;
        while (t.find()) {
            seen++;
            assertTrue(t.group().contains("data-perm=\"report.export\""),
                "调用 doExport() 的元素必须带 data-perm=\"report.export\"（见 D13）。当前："
                    + t.group().trim());
        }
        assertTrue(seen >= 1, "预期至少 1 处「导出」入口（报表查询页），实际 " + seen);

        String js = read("src/main/resources/static/js/app.js");
        String body = functionBody(js, "async function doExport(");
        assertTrue(body.contains("report.export"),
            "doExport() 必须在发请求之前判 report.export，否则无权用户仍会打出 403 请求（见 D13）");
        int permAt = body.indexOf("report.export");
        int fetchAt = body.indexOf("fetch(");
        assertTrue(fetchAt < 0 || permAt < fetchAt,
            "doExport() 里 report.export 的判断必须在 fetch 之前（见 D13）");
    }

    /**
     * 权限点目录里的每一项，都必须真的管住某个东西。
     *
     * <p>否则管理员在「角色管理」里勾上或取消它没有任何效果，界面在骗人，
     * 而且这种「摆设权限点」不会被任何别的测试抓到（它既不 403、也不报错）。
     *
     * <p>判据：每个权限点至少要在下面三处之一出现：
     * <ol>
     *   <li>后端 {@code @PreAuthorize("@perm.has('X')")}，接口真的强制了；</li>
     *   <li>前端 {@code data-perm="X"}，元素真的按它显隐；</li>
     *   <li>前端 JS 里 {@code .has('X')}，条件渲染/条件请求里真的用了它
     *       （如 {@code announce.manage}、{@code screen.view} 走的是 JS 判断而非 data-perm）。</li>
     * </ol>
     *
     * <p>扫描的是**真实源码**，权限点清单取自 {@link RolePermissionService#ALL_PERMISSIONS}
     *，单一事实来源，不在这里另抄一份（抄一份就会重演「改一处红四个脚本」）。
     */
    @Test
    void everyPermissionPointHasAConsumer() throws IOException {
        Set<String> consumers = new TreeSet<>();
        // ① 后端强制点（注解 SpEL 形式）
        Set<String> backendAnn = collectIds("src/main/java", Pattern.compile("@perm\\.has\\('([a-z.]+)'\\)"));
        consumers.addAll(backendAnn);
        // ② 后端强制点（**常量式判定调用**），见下方注释
        Set<String> backendConst = collectConstantFormJudgements();
        consumers.addAll(backendConst);
        // ③ 前端 data-perm
        consumers.addAll(collectIds("src/main/resources", Pattern.compile("data-perm=\"([a-z.]+)\"")));
        // ④ 前端 JS 条件判断
        consumers.addAll(collectIds("src/main/resources/static/js", Pattern.compile("\\.has\\(['\"]([a-z.]+)['\"]\\)")));

        // 回归锚点：常量式判定必须真的被扫到。
        //   背景：`screen.view` 在后端**只以常量形式**被消费 ，
        //   `RolePermissionService#hasScreenPermission` 里写的是 `has(username, P_SCREEN)`，
        //   按字符串字面量 grep `has('screen.view')` 一个都找不到。
        //   原先本测试的后端信号只有注解形式，于是 `screen.view` 是**靠前端 data-perm 才通过**的：
        //   一旦前端菜单的 data-perm 被改名，它会被误判成「孤儿权限点」，
        //   有人据此把它从 ALL_PERMISSIONS 删掉，就会**静默破坏大屏访问控制**。
        //   这条断言把「常量式扫描」钉死：它坏了本测试立刻红。
        assertTrue(backendConst.contains(RolePermissionService.P_SCREEN),
            "常量式判定扫描失效：`screen.view` 在后端由 RolePermissionService#hasScreenPermission 里的 "
                + "`has(username, P_SCREEN)` 强制，但没被扫到（实际扫到：" + backendConst + "）");

        // 负向锚点：**「被授予」不等于「被消费」**。
        //   `dashboard.view`（P_DASHBOARD）在 RolePermissionService 里被引用 5 次，
        //   但全部是「目录声明 / 内置角色默认权限 / 兜底授予」，**没有一处是判定**。
        //   若把「任何常量引用」都算作消费者，`dashboard.view` 这个摆设权限点就会被悄悄洗白，
        //   本测试的核心价值（识别摆设）也就没了。故必须断言它**不在**强制点集合里。
        assertFalse(backendConst.contains(RolePermissionService.P_DASHBOARD),
            "常量式扫描把「授予」误算成了「消费」：`dashboard.view` 只在目录声明与默认权限清单里出现，"
                + "没有任何判定点读它。扫描器必须只认判定调用（has/hasAny/permissionsOf.contains），"
                + "不能认「出现过」，否则摆设权限点会被洗白。");

        // 已知例外：显式列出并注明原因，任何**新**出现的孤儿权限点都会让本测试失败。
        //
        // **例外清单已清空**（此前唯一的例外是 `dashboard.view`）。
        //   方案选定「给工作台菜单补 data-perm + 配套无权限落地页」，
        //   于是 `index.html` 的侧栏工作台菜单现在带 `data-perm="dashboard.view"`
        //   （被上面的 ③ 扫到），`dashboard.view` 有了真实消费者。
        //   从此本测试回到**零例外**状态：每一项权限点都必须真的有消费者。
        //   注意：若将来又要加例外，请先确认「两条路都真的走不通」，
        //   加例外等于承认「管理员勾它没用」，是**界面在骗人**，不能当成省事的手段。
        Map<String, String> knownOrphans = new LinkedHashMap<>();

        List<String> orphans = new ArrayList<>();
        for (String[] p : RolePermissionService.ALL_PERMISSIONS) {
            String id = p[0];
            if (consumers.contains(id)) continue;
            if (knownOrphans.containsKey(id)) continue;
            orphans.add(id);
        }
        assertTrue(orphans.isEmpty(),
            "以下权限点在前后端**都没有任何消费者**，勾上/取消它没有任何效果，"
                + "「角色管理」页在骗管理员。请给它接上真实的强制点（后端 @perm.has 或前端 data-perm），"
                + "或从 ALL_PERMISSIONS 里移除：\n  " + String.join("\n  ", orphans));

        // 防空转：扫描确实扫到了东西（否则「全都在消费者里」可能只是因为一个都没扫到）
        assertTrue(consumers.size() >= 10,
            "只扫到 " + consumers.size() + " 个权限点消费者，扫描逻辑可能已失效："
                + "（检查路径/正则，不要靠放宽断言让测试变绿）");
    }

    /**
     * 扫描「常量式判定调用」形式的权限点强制点，返回被强制到的权限点 id 集合。
     *
     * <p>为什么需要它（工具盲区）：权限点在后端有两种写法 ，
     * <pre>
     *   @PreAuthorize("@perm.has('screen.view')")        // ① 注解 + 字符串字面量
     *   if (has(username, P_SCREEN)) { ... }             // ② 方法体 + 常量
     * </pre>
     * 按字符串字面量 grep（{@code has('screen.view')}）**只能看见 ①**，
     * ② 一个都找不到。{@code screen.view} 恰好只以 ② 的形式存在
     * （{@code RolePermissionService#hasScreenPermission}），
     * 于是它在「权限点是否有消费者」这件事上长期**只靠前端 {@code data-perm} 撑着** ，
     * 前端一旦改名就会被误判成孤儿权限点，进而被「清理」掉，**静默破坏大屏访问控制**。
     *
     * <p>只认「判定」，不认「出现过」：{@code dashboard.view} 在
     * {@code RolePermissionService} 里被引用 5 次，但全部是「目录声明 / 内置角色默认权限 /
     * 兜底授予」， 没有一处读它来做判断。若把「任何常量引用」都算消费者，
     * 这个摆设权限点就会被悄悄洗白。故本方法只匹配
     * {@code has(..., P_X)} / {@code hasAny(..., P_X)} / {@code permissionsOf(...).contains(P_X)}，
     * 且允许 {@code RolePermissionService.P_X} 这样的限定前缀。
     */
    private static Set<String> collectConstantFormJudgements() throws IOException {
        // 1. 常量表：P_SCREEN → "screen.view"
        Map<String, String> constMap = new LinkedHashMap<>();
        Matcher cm = Pattern.compile(
            "public\\s+static\\s+final\\s+String\\s+(P_\\w+)\\s*=\\s*\"([^\"]+)\"")
            .matcher(read("src/main/java/com/report/service/RolePermissionService.java"));
        while (cm.find()) constMap.put(cm.group(1), cm.group(2));

        // 2. 判定调用（两种参数形态：常量 / 字符串字面量）
        Pattern judgeConst = Pattern.compile(
            "\\b(?:has|hasAny)\\s*\\(\\s*[^,()]*,\\s*(?:[A-Z]\\w*\\.)?(P_\\w+)\\s*\\)");
        Pattern judgeLiteral = Pattern.compile(
            "\\b(?:has|hasAny)\\s*\\(\\s*[^,()]*,\\s*['\"]([a-z.]+)['\"]\\s*\\)");
        Pattern containsConst = Pattern.compile(
            "\\bpermissionsOf\\s*\\([^)]*\\)\\s*\\.\\s*contains\\s*\\(\\s*(?:[A-Z]\\w*\\.)?(P_\\w+)\\s*\\)");

        Set<String> out = new TreeSet<>();
        Path root = projectFile("src/main/java");
        if (!Files.exists(root)) return out;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                if (!f.toString().endsWith(".java")) continue;
                String src = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = judgeConst.matcher(src);
                while (m.find()) {
                    String id = constMap.get(m.group(1));
                    if (id != null) out.add(id);
                }
                m = judgeLiteral.matcher(src);
                while (m.find()) out.add(m.group(1));
                m = containsConst.matcher(src);
                while (m.find()) {
                    String id = constMap.get(m.group(1));
                    if (id != null) out.add(id);
                }
            }
        }
        return out;
    }

    /** 把某目录树下所有 .java/.html/.js 文件里匹配到的 id 收集起来 */
    private static Set<String> collectIds(String dir, Pattern p) throws IOException {
        Set<String> out = new TreeSet<>();
        Path root = projectFile(dir);
        if (!Files.exists(root)) return out;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                String name = f.toString();
                if (!(name.endsWith(".java") || name.endsWith(".html") || name.endsWith(".js"))) continue;
                Matcher m = p.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) out.add(m.group(1));
            }
        }
        return out;
    }
}
