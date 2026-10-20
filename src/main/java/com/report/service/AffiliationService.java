package com.report.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工号归属服务，派生引擎。
 *
 * <h3>设计要点</h3>
 * <p>一个工号的归属只需要维护 4 个源字段：
 * <pre>
 *   department     归属科室
 *   position       职务
 *   roleId         角色
 *   scopeOverride  是否自定义数据范围（+ 自定义时用 allowedCommunities/allowedDepartments）
 * </pre>
 * 其余全部实时派生，不手工维护：
 * <pre>
 *   departments[].community   ──► 归属社区 ─┐
 *   positions[].scopeRule     ─────────────┼──► 数据范围
 *                                          │
 *   positions[].defaultRoleId ──► 角色 ────┼──► 权限点集合
 *   roles[].permissions       ─────────────┘
 * </pre>
 *
 * <p>「改科室 → 社区自动跟随」是这个映射的数学必然，不靠人工同步逻辑。
 * 代码里没有任何一处硬编码「哪个科室属于哪个社区」，一切查
 * {@code departments[].community}（部分科室已有该值）。
 *
 * <p>注意：本服务算出的 {@code allowedCommunities}/{@code allowedDepartments}
 * 会写回 {@link UserManagementService.UserRecord}，但那是为了兼容既有数据过滤代码
 * 而做的快照，不是源数据，每次保存都会重新派生覆盖。
 * 两者里只有 {@code allowedCommunities} 真正参与过滤（报表 {@code resolveCommunities}
 * 与大屏都只认社区）；{@code allowedDepartments} 目前全仓零消费点，规则如下：
 * 现阶段不做科室级数据管控，界面文案已统一注明「数据过滤为二期」，别再当成已生效的约束。
 */
@Service
public class AffiliationService {

    private static final Logger log = LoggerFactory.getLogger(AffiliationService.class);

    /** 自定义覆盖的规则标识（不对应任何职务） */
    public static final String RULE_CUSTOM = "CUSTOM";

    /** 一次性存量回填标记文件名（位于 data/ 目录，存在即表示迁移已执行） */
    private static final String BACKFILL_MARKER = ".affiliation-backfill-v1";

    private final UserManagementService userService;
    private final RolePermissionService roleService;
    private final PositionService positionService;
    private final DepartmentService departmentService;
    private final CommunityService communityService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Value("${app.users.data-file:}")
    private String usersDataFile;

    /** personnel.json 的 工号→科室 索引（懒加载，仅迁移时使用） */
    private volatile Map<String, String> personnelDeptCache;

    public AffiliationService(UserManagementService userService,
                              RolePermissionService roleService,
                              PositionService positionService,
                              DepartmentService departmentService,
                              CommunityService communityService,
                              AuditService auditService,
                              ObjectMapper objectMapper) {
        this.userService = userService;
        this.roleService = roleService;
        this.positionService = positionService;
        this.departmentService = departmentService;
        this.communityService = communityService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    private Path dataFile(String name) {
        String appHome = System.getProperty("APP_HOME", System.getProperty("user.dir"));
        String dataDir = usersDataFile != null && !usersDataFile.isEmpty()
            ? Paths.get(usersDataFile).getParent().toString()
            : Paths.get(appHome, "data").toString();
        return Paths.get(dataDir, name);
    }

    // ==================================================================
    // 〇、存量回填迁移（一次性、幂等）
    // ==================================================================

    /**
     * 把存量账号的归属源字段补齐。
     *
     * <p>归属功能上线前，账号记录里没有「归属科室」「职务」这两个源字段。
     * 回填规则：
     * <ul>
     *   <li>{@code department} ← {@code personnel.json} 的 {@code department}（按工号对应）。
     *       核对结论：{@code personnel.department} 与 {@code allowedDepartments[0]}
     *       逐条核对一致，因此回填不会改变任何人的可见范围。</li>
     *   <li>{@code position} ← 统一 {@code POS_STAFF}。职务信息源表不存在，
     *       需要管理员按实际情况调整（{@code personnel.title} 是职称，不能当职务用）。</li>
     * </ul>
     *
     * <p>关键：不改动现有数据范围。回填后逐条比对「现有 allowedXXX」与
     * 「按职务规则派生值」，一致则留 {@code scopeOverride=false}（今后自动跟随职务），
     * 不一致则标记 {@code scopeOverride=true} 保留原值，避免迁移把某个人的可见范围
     * 悄悄放大或缩小。
     */
    @PostConstruct
    void backfillAffiliation() {
        Path marker = dataFile(BACKFILL_MARKER);
        if (Files.exists(marker)) return;

        Map<String, DepartmentService.Department> depts = deptIndex();
        Map<String, String> personnelDept = personnelDeptIndex();
        String admin = userService.getAdminUsername();

        int filledDept = 0, filledPos = 0, markedOverride = 0, total = 0;
        for (UserManagementService.UserRecord r : userService.allRecords()) {
            if (r.username == null || r.username.equals(admin)) continue;
            total++;

            String dept = r.department;
            if (dept == null || dept.isBlank()) {
                dept = personnelDept.get(r.username);
                if (dept != null && !dept.isBlank()) filledDept++;
            }
            String pos = r.position;
            if (pos == null || pos.isBlank()) {
                pos = PositionService.DEFAULT_POSITION;
                filledPos++;
            }

            Boolean override = r.scopeOverride;
            if (override == null) {
                DepartmentService.Department d = dept == null ? null : depts.get(dept);
                String comm = d == null ? null : d.community;
                String rule = positionService.scopeRule(pos);

                List<String> expectComm = new ArrayList<>();
                List<String> expectDept = new ArrayList<>();
                if (PositionService.SCOPE_SELF_COMMUNITY.equals(rule)) {
                    if (comm != null) expectComm.add(comm);
                } else if (!PositionService.SCOPE_ALL.equals(rule)) {
                    if (comm != null) expectComm.add(comm);
                    if (dept != null) expectDept.add(dept);
                }
                if (!sameSet(r.allowedCommunities, expectComm)
                    || !sameSet(r.allowedDepartments, expectDept)) {
                    override = Boolean.TRUE;
                    markedOverride++;
                }
            }
            userService.setAffiliationSource(r.username, dept, pos, override);
        }

        userService.persist();
        try {
            Files.writeString(marker,
                "affiliation-backfill-v1 appliedAt=" + Instant.now()
                    + " total=" + total
                    + " filledDepartment=" + filledDept
                    + " filledPosition=" + filledPos
                    + " markedScopeOverride=" + markedOverride
                    + System.lineSeparator(),
                StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("写入归属回填标记失败，下次启动将重试: {}", marker.toAbsolutePath(), e);
        }
        log.info("[迁移] 工号归属回填完成: 共 {} 人，回填归属科室 {} 人，回填职务 {} 人，"
                + "因现有范围与派生值不一致而标记为显式覆盖 {} 人",
            total, filledDept, filledPos, markedOverride);
    }

    /** personnel.json 的 工号→科室 索引（懒加载） */
    private Map<String, String> personnelDeptIndex() {
        Map<String, String> cached = personnelDeptCache;
        if (cached != null) return cached;

        Map<String, String> m = new HashMap<>();
        Path p = dataFile("personnel.json");
        if (Files.exists(p)) {
            try {
                List<Map<String, Object>> list = objectMapper.readValue(
                    Files.readString(p), new TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> row : list) {
                    Object code = row.get("code");
                    Object dept = row.get("department");
                    if (code != null && dept != null) {
                        m.put(String.valueOf(code), String.valueOf(dept));
                    }
                }
            } catch (Exception e) {
                log.error("读取人员字典失败（本次回填将跳过归属科室）: {}", p.toAbsolutePath(), e);
            }
        }
        personnelDeptCache = m;
        return m;
    }

    /** 两个集合是否等价（null 与空列表视为相同；忽略顺序） */
    private static boolean sameSet(List<String> a, List<String> b) {
        List<String> x = a == null ? new ArrayList<>() : new ArrayList<>(a);
        List<String> y = b == null ? new ArrayList<>() : new ArrayList<>(b);
        Collections.sort(x);
        Collections.sort(y);
        return x.equals(y);
    }

    // ==================================================================
    // 一、派生
    // ==================================================================

    /** 派生结果（只读视图，供接口直接返回） */
    public static class Derived {
        public String department;
        public String departmentName;
        public String community;
        public String communityName;
        public String position;
        public String positionName;
        public String roleId;
        public String roleName;
        public String scopeRule;
        public String scopeRuleLabel;
        public List<String> communities = new ArrayList<>();
        public List<String> departments = new ArrayList<>();
        public List<String> permissions = new ArrayList<>();
        public List<Map<String, String>> issues = new ArrayList<>();
        public boolean hasError;

        public String getDepartment() { return department; }
        public String getDepartmentName() { return departmentName; }
        public String getCommunity() { return community; }
        public String getCommunityName() { return communityName; }
        public String getPosition() { return position; }
        public String getPositionName() { return positionName; }
        public String getRoleId() { return roleId; }
        public String getRoleName() { return roleName; }
        public String getScopeRule() { return scopeRule; }
        public String getScopeRuleLabel() { return scopeRuleLabel; }
        public List<String> getCommunities() { return communities; }
        public List<String> getDepartments() { return departments; }
        public List<String> getPermissions() { return permissions; }
        public List<Map<String, String>> getIssues() { return issues; }
        public boolean isHasError() { return hasError; }
    }

    /** 单条派生（自行建索引，适合单次调用） */
    public Derived derive(String department, String position, String roleId, Boolean scopeOverride,
                          List<String> allowedCommunities, List<String> allowedDepartments,
                          boolean superAdmin) {
        return deriveInternal(department, position, roleId, scopeOverride,
            allowedCommunities, allowedDepartments, superAdmin, deptIndex(), roleIndex());
    }

    /**
     * 派生核心实现。
     *
     * @param depts 科室编码→科室 的索引（批量调用时复用，避免 N×M 次查找）
     * @param roles 角色ID→角色 的索引
     */
    private Derived deriveInternal(String department, String position, String roleId,
                                   Boolean scopeOverride,
                                   List<String> allowedCommunities, List<String> allowedDepartments,
                                   boolean superAdmin,
                                   Map<String, DepartmentService.Department> depts,
                                   Map<String, RolePermissionService.Role> roles) {
        Derived d = new Derived();
        d.department = department;
        d.position = position;
        d.roleId = roleId;

        DepartmentService.Department dept = department == null ? null : depts.get(department);
        d.departmentName = dept == null ? null : dept.name;

        PositionService.Position pos = positionService.get(position);
        d.positionName = pos == null ? null : pos.name;

        RolePermissionService.Role role = roleId == null ? null : roles.get(roleId);
        d.roleName = role == null ? null : role.name;
        if (role != null && role.permissions != null) {
            d.permissions = new ArrayList<>(role.permissions);
        }

        // ---- 派生①：归属社区（由科室的 community 字段决定）----
        String comm = null;
        if (!superAdmin && dept != null) comm = dept.community;
        d.community = comm;
        if (comm != null) {
            CommunityService.Community c = communityService.getByCode(comm);
            d.communityName = c != null ? c.name : comm;
        }

        // ---- 派生②：数据范围（显式覆盖 > 职务规则）----
        List<String> scopeComm = new ArrayList<>();
        List<String> scopeDept = new ArrayList<>();
        String rule;
        if (superAdmin) {
            rule = PositionService.SCOPE_ALL;
        } else if (Boolean.TRUE.equals(scopeOverride)) {
            rule = RULE_CUSTOM;
            if (allowedCommunities != null) scopeComm.addAll(allowedCommunities);
            if (allowedDepartments != null) scopeDept.addAll(allowedDepartments);
        } else {
            rule = pos != null && pos.scopeRule != null
                ? pos.scopeRule : PositionService.SCOPE_SELF_DEPT;
            if (PositionService.SCOPE_ALL.equals(rule)) {
                // 不限：两个列表都留空
            } else if (PositionService.SCOPE_SELF_COMMUNITY.equals(rule)) {
                if (comm != null) scopeComm.add(comm);
            } else {
                if (comm != null) scopeComm.add(comm);
                if (department != null) scopeDept.add(department);
            }
        }
        d.scopeRule = rule;
        d.scopeRuleLabel = RULE_CUSTOM.equals(rule)
            ? "自定义覆盖" : PositionService.scopeRuleLabel(rule);
        d.communities = scopeComm;
        d.departments = scopeDept;

        // ---- 校验 ----
        if (superAdmin) {
            d.issues.add(issue("warn",
                "内置超级管理员：账号由环境变量 ADMIN_USERNAME 指定，不参与归属分配，归属不可变更"));
        } else {
            if (department == null || department.isBlank()) {
                d.issues.add(issue("warn", "未设置归属科室，数据范围将为空"));
            } else if (dept == null) {
                d.issues.add(issue("error", "科室编码在字典中不存在：" + department));
            } else if (dept.community == null) {
                d.issues.add(issue("warn", "科室「" + dept.name
                    + "」未挂社区（行政/共享岗），社区范围将不受限"));
            }
            /* 科室已停用：**允许保留**（历史上已挂在上面的工号不能因此凭空失联），
             * 但必须给提示，否则管理员会不知情地把工号新挂到已停用科室上。
             * 独立于上面的 if/else 链：停用科室也可能同时没挂社区，两条提示都该给。 */
            if (dept != null && !dept.enabled) {
                d.issues.add(issue("warn", "科室「" + dept.name
                    + "」已停用，建议改到启用中的科室"));
            }
            if (Boolean.TRUE.equals(scopeOverride) && scopeComm.isEmpty() && scopeDept.isEmpty()) {
                d.issues.add(issue("warn", "已开启自定义覆盖但未勾选任何社区/科室，将表示「不限」"));
            }
            if (role == null) {
                d.issues.add(issue("error", "角色不存在：" + roleId));
            } else if (RolePermissionService.R_SCREEN.equals(roleId)) {
                /* 「大屏查看」是**合法的可分配角色**（大屏专用工号），不再拒绝。
                 * 之所以给提示而不给错误：它的权限集只有 screen.view，
                 * 分配后该工号登录会直达大屏、看不到其他菜单，管理员应当知情。
                 *
                 * 注意区分：M-1 缺陷的教训是「role_med_admin / role_user 不得携带 screen.view」
                 * （会让工号级大屏开关被角色通道无条件短路），那条防线由
                 * RolePermissionService 的内置角色定义保证，与本处是否允许分配无关。 */
                d.issues.add(issue("warn",
                    "「大屏查看」是通道型角色：该工号登录后将直达数据大屏，看不到其他菜单"));
            }
        }
        for (Map<String, String> i : d.issues) {
            if ("error".equals(i.get("level"))) { d.hasError = true; break; }
        }
        return d;
    }

    private static Map<String, String> issue(String level, String text) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("text", text);
        return m;
    }

    private Map<String, DepartmentService.Department> deptIndex() {
        Map<String, DepartmentService.Department> m = new HashMap<>();
        for (DepartmentService.Department d : departmentService.listAll()) {
            m.put(d.code, d);
        }
        return m;
    }

    private Map<String, RolePermissionService.Role> roleIndex() {
        Map<String, RolePermissionService.Role> m = new HashMap<>();
        for (RolePermissionService.Role r : roleService.listRoles()) {
            m.put(r.id, r);
        }
        return m;
    }

    // ==================================================================
    // 二、查询
    // ==================================================================

    /** 归属列表（含派生结果），供管理界面表格使用 */
    public List<Map<String, Object>> list() {
        Map<String, DepartmentService.Department> depts = deptIndex();
        Map<String, RolePermissionService.Role> roles = roleIndex();
        String admin = userService.getAdminUsername();

        List<Map<String, Object>> out = new ArrayList<>();
        for (UserManagementService.UserRecord r : userService.allRecords()) {
            boolean superAdmin = r.username != null && r.username.equals(admin);
            Derived d = deriveInternal(r.department, r.position,
                userService.resolveRoleId(r.username), r.scopeOverride,
                r.allowedCommunities, r.allowedDepartments, superAdmin, depts, roles);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", r.username);
            m.put("name", r.name);
            m.put("superAdmin", superAdmin);
            m.put("department", d.department);
            m.put("departmentName", d.departmentName);
            m.put("community", d.community);
            m.put("communityName", d.communityName);
            m.put("position", d.position);
            m.put("positionName", d.positionName);
            m.put("roleId", d.roleId);
            m.put("roleName", d.roleName);
            m.put("scopeRule", d.scopeRule);
            m.put("scopeRuleLabel", d.scopeRuleLabel);
            m.put("communities", d.communities);
            m.put("departments", d.departments);
            m.put("scopeOverride", Boolean.TRUE.equals(r.scopeOverride));
            out.add(m);
        }
        return out;
    }

    /** 单个工号的归属详情（含派生结果与校验问题） */
    public Map<String, Object> detail(String username) {
        UserManagementService.UserRecord r = findRecord(username);
        if (r == null) throw new IllegalArgumentException("用户不存在: " + username);

        boolean superAdmin = username.equals(userService.getAdminUsername());
        Derived d = derive(r.department, r.position, userService.resolveRoleId(username),
            r.scopeOverride, r.allowedCommunities, r.allowedDepartments, superAdmin);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", r.username);
        m.put("name", r.name);
        m.put("superAdmin", superAdmin);
        m.put("derived", d);
        m.put("scopeOverride", Boolean.TRUE.equals(r.scopeOverride));
        return m;
    }

    /**
     * 全部字典 + 权限点清单，供前端一次性拉取后本地渲染
     * （避免每选一个工号都请求一次字典）。
     */
    public Map<String, Object> dictionaries() {
        Map<String, Object> m = new LinkedHashMap<>();

        List<Map<String, Object>> comms = new ArrayList<>();
        for (CommunityService.Community c : communityService.listAll()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", c.code);
            x.put("name", c.name);
            x.put("enabled", c.enabled);
            comms.add(x);
        }
        m.put("communities", comms);

        List<Map<String, Object>> depts = new ArrayList<>();
        for (DepartmentService.Department d : departmentService.listAll()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", d.code);
            x.put("name", d.name);
            x.put("community", d.community);
            x.put("enabled", d.enabled);
            depts.add(x);
        }
        m.put("departments", depts);

        List<Map<String, Object>> roles = new ArrayList<>();
        for (RolePermissionService.Role r : roleService.listRoles()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", r.id);
            x.put("name", r.name);
            x.put("builtin", r.builtin);
            x.put("permissions", r.permissions);
            roles.add(x);
        }
        m.put("roles", roles);

        List<Map<String, Object>> positions = new ArrayList<>();
        for (PositionService.Position p : positionService.listAll()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", p.code);
            x.put("name", p.name);
            x.put("defaultRoleId", p.defaultRoleId);
            x.put("scopeRule", p.scopeRule);
            x.put("scopeRuleLabel", PositionService.scopeRuleLabel(p.scopeRule));
            x.put("builtin", p.builtin);
            x.put("remark", p.remark);
            positions.add(x);
        }
        m.put("positions", positions);

        List<Map<String, Object>> perms = new ArrayList<>();
        for (String[] p : RolePermissionService.ALL_PERMISSIONS) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", p[0]);
            x.put("name", p[1]);
            perms.add(x);
        }
        m.put("permissions", perms);

        m.put("adminUsername", userService.getAdminUsername());
        return m;
    }

    // ==================================================================
    // 三、保存
    // ==================================================================

    /**
     * 保存工号归属。
     *
     * <p>入参只接收源字段；数据范围由本方法重新派生后落库，
     * 不接受前端直接传来的 {@code allowedCommunities}/{@code allowedDepartments}
     * （除非显式开启覆盖），避免前端伪造数据权限。
     *
     * @return 保存后的派生结果
     * @throws IllegalArgumentException 校验失败或命中保护规则
     */
    public Derived save(String operator, String username,
                        String department, String position, String roleId,
                        Boolean scopeOverride,
                        List<String> overrideCommunities, List<String> overrideDepartments) {
        UserManagementService.UserRecord existing = findRecord(username);
        if (existing == null) throw new IllegalArgumentException("用户不存在: " + username);

        boolean superAdmin = username.equals(userService.getAdminUsername());

        /* 角色留空时按职务带出默认角色，与前端「改职务自动带出角色」的联动保持一致，
         * 也保证接口被直接调用（不经前端）时行为一致。 */
        String effectiveRoleId = roleId;
        if (effectiveRoleId == null || effectiveRoleId.isBlank()) {
            effectiveRoleId = positionService.defaultRoleId(position);
        }

        /* 先派生，再校验。范围必须是「服务端算出来的」，
         * 前端传来的 override 只在显式开启覆盖时才被采信。 */
        Derived d = derive(department, position, effectiveRoleId, scopeOverride,
            overrideCommunities, overrideDepartments, superAdmin);

        if (d.hasError) {
            List<String> errs = new ArrayList<>();
            for (Map<String, String> i : d.issues) {
                if ("error".equals(i.get("level"))) errs.add(i.get("text"));
            }
            throw new IllegalArgumentException(String.join("；", errs));
        }

        userService.setAffiliationRaw(operator, username, department, position,
            effectiveRoleId, scopeOverride, d.communities, d.departments);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("username", username);
        fields.put("department", department);
        fields.put("community", d.community);
        fields.put("position", position);
        fields.put("roleId", effectiveRoleId);
        fields.put("scopeRule", d.scopeRule);
        fields.put("communities", d.communities);
        fields.put("departments", d.departments);
        try {
            auditService.audit("affiliation.update", fields);
        } catch (Exception e) {
            // 审计失败不应影响业务结果
            log.warn("写入归属变更审计失败: username={}", username, e);
        }

        log.info("[归属] operator={} username={} 科室={} 社区={} 职务={} 角色={} 范围规则={} 社区范围={} 科室范围={}",
            operator, username, department, d.community, position, effectiveRoleId,
            d.scopeRule, d.communities, d.departments);
        return d;
    }

    private UserManagementService.UserRecord findRecord(String username) {
        if (username == null) return null;
        for (UserManagementService.UserRecord r : userService.allRecords()) {
            if (username.equals(r.username)) return r;
        }
        return null;
    }
}
