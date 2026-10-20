package com.report.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.report.util.AtomicFileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 角色与权限点管理。
 *
 * - 角色（含权限点集合）持久化于 data/roles.json，可在"角色管理"页维护；
 * - 内置 4 角色可改配置不可删除；内置 admin 超管永远直通；
 * - 权限点判定实时读取内存字典（改角色即时生效），前端菜单随下次加载刷新；
 * - 大屏权限双通道：超管 ‖ 角色含 screen.view ‖ 工号开关 screenEnabled。
 *   「医管中心」「普通用户」为批量分配角色，默认不含 screen.view ，
 *   若让它们携带该权限点，则工号级开关 screenEnabled 将完全失效（历史缺陷 M-1）；
 * - 存量兼容：UserRecord.roleId 为空时按旧 role 字段映射（ADMIN→系统管理员，USER→普通用户）；
 * - 数据权限（可查看社区/科室）不挂角色、挂工号（见 UserManagementService / UserRecord）。
 */
@Service
public class RolePermissionService {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionService.class);

    // ============ 权限点常量 ============
    public static final String P_DASHBOARD = "dashboard.view";
    public static final String P_SCREEN = "screen.view";
    public static final String P_REPORTS = "reports.view";
    public static final String P_REPORT_EXPORT = "report.export";
    public static final String P_USERS = "users.manage";
    public static final String P_ROLES = "roles.manage";
    public static final String P_COMMUNITIES = "communities.manage";
    public static final String P_DEPARTMENTS = "departments.manage";
    public static final String P_GROUPS = "groups.manage";
    public static final String P_REPORT_MANAGE = "report.manage";
    public static final String P_LOGS = "logs.view";
    public static final String P_DATASOURCE = "datasource.manage";
    public static final String P_PERSONNEL = "personnel.manage";
    public static final String P_ANNOUNCE = "announce.manage";

    /** 权限点分组名（后端声明，前端按 {@link #PERMISSION_GROUPS} 的顺序渲染）。
     *  必须声明在 {@link #ALL_PERMISSIONS} 之前，Java 不允许静态初始化器前向引用。 */
    public static final String G_VIEW = "查看类";
    public static final String G_MANAGE = "管理类";
    public static final String G_BUSINESS = "业务管理";

    /** 分组显示顺序；出现未登记的分组时由前端追加到末尾，保证不漏项 */
    public static final List<String> PERMISSION_GROUPS = Collections.unmodifiableList(
        Arrays.asList(G_VIEW, G_MANAGE, G_BUSINESS));

    /** 全部权限点（角色编辑弹窗的勾选清单，按显示顺序）。
     *  每项为 {@code {code, 显示名, 分组}}，分组由后端声明，前端只负责渲染。
     *  这样新增权限点时不会出现「前端硬编码清单漏项 → 打开配置弹窗看不到该勾选框
     *  → 直接保存把权限静默删掉」这类缺陷（实际发生过：漏了
     *  {@code personnel.manage} 与 {@code announce.manage}）。 */
    public static final List<String[]> ALL_PERMISSIONS = Collections.unmodifiableList(Arrays.asList(
        new String[]{P_DASHBOARD, "工作台", G_VIEW},
        new String[]{P_SCREEN, "可视化大屏", G_VIEW},
        new String[]{P_REPORTS, "报表中心", G_VIEW},
        new String[]{P_REPORT_EXPORT, "导出Excel", G_VIEW},
        new String[]{P_LOGS, "系统日志", G_VIEW},
        new String[]{P_USERS, "用户管理", G_MANAGE},
        new String[]{P_ROLES, "角色管理", G_MANAGE},
        new String[]{P_COMMUNITIES, "社区管理", G_MANAGE},
        new String[]{P_DEPARTMENTS, "科室管理", G_MANAGE},
        new String[]{P_GROUPS, "分组管理", G_MANAGE},
        new String[]{P_REPORT_MANAGE, "报表管理/SQL导入", G_MANAGE},
        new String[]{P_DATASOURCE, "数据源管理", G_MANAGE},
        new String[]{P_PERSONNEL, "人员管理", G_BUSINESS},
        new String[]{P_ANNOUNCE, "信息发布", G_BUSINESS}
    ));

    // 内置角色 ID
    public static final String R_SYS_ADMIN = "role_sys_admin";
    public static final String R_MED_ADMIN = "role_med_admin";
    public static final String R_USER = "role_user";
    public static final String R_SCREEN = "role_screen";

    /**
     * 已废弃的内置角色 ID：{@code role_dept_head}（科主任 / 社区主任）。
     *
     * <p>该角色已删除。原因：它的权限点曾是
     * {@code dashboard.view + reports.view + report.export}，与 {@link #R_USER}（普通用户）
     * **一字不差**，角色已失去区分度，只剩「职务带出时的挂载点」这一个作用，
     * 而职务本来就能直接带出角色。删掉后「科主任」「社区主任」两个职务
     * 直接挂 {@link #R_USER}（见 {@code PositionService}）。
     *
     * <p>为什么保留这个常量而不删干净：它是一次性迁移的**清理目标** ，
     * 存量数据里如果还有以它挂载的角色 ID，必须能被认出来并改挂到 {@link #R_USER}，
     * 否则那些工号会落进「角色不存在」的兜底分支（只剩 {@code dashboard.view}）。
     * 迁移完成后本常量仅用于「不再补建它」这个判断。
     *
     * @see #migrateRemovedDeptHeadRole(String)
     */
    public static final String R_DEPT_HEAD_REMOVED = "role_dept_head";

    /** 一次性存量迁移标记文件名（位于 data/ 目录，存在即表示迁移已执行） */
    private static final String ROLES_MIGRATION_MARKER = ".roles-migrated-v1";
    /** 删除 role_dept_head 的一次性迁移标记（与 D 目标不同，故单独一个标记） */
    private static final String ROLES_DEPT_HEAD_REMOVAL_MARKER = ".roles-dept-head-removed-v1";

    private final UserManagementService userManagementService;
    private final ObjectMapper objectMapper;

    @Value("${app.users.data-file:}")
    private String usersDataFile;

    private final Map<String, Role> roles = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public RolePermissionService(UserManagementService userManagementService, ObjectMapper objectMapper) {
        this.userManagementService = userManagementService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        loadRoles();
    }

    private Path rolesFile() {
        String appHome = System.getProperty("APP_HOME", System.getProperty("user.dir"));
        String dataDir = usersDataFile != null && !usersDataFile.isEmpty()
            ? Paths.get(usersDataFile).getParent().toString()
            : Paths.get(appHome, "data").toString();
        return Paths.get(dataDir, "roles.json");
    }

    private void loadRoles() {
        Path path = rolesFile();
        lock.writeLock().lock();
        try {
            roles.clear();
            if (Files.exists(path)) {
                try {
                    String json = Files.readString(path);
                    List<Role> list = objectMapper.readValue(json, new TypeReference<List<Role>>() {});
                    for (Role r : list) {
                        roles.put(r.id, r);
                    }
                    log.info("角色字典加载完成: {} 个角色", roles.size());
                } catch (Exception e) {
                    log.error("解析角色数据失败，使用内置角色: {}", path.toAbsolutePath(), e);
                }
            }
            boolean dirty = !Files.exists(path);

            // 保证内置角色存在（新部署或文件损坏时兜底）
            // 注意：「医管中心」「普通用户」是批量分配角色，默认不含 screen.view，
            // 否则工号级大屏开关 screenEnabled 会被角色通道无条件短路（缺陷 M-1）
            // 必须把返回值并入 dirty，否则新建的内置角色不会落盘 ，
            //   重启后又会用默认值重新补建，管理员在界面上的调整会丢失。
            dirty |= ensureBuiltin(R_SYS_ADMIN, "系统管理员", allPermissionList(), true);
            dirty |= ensureBuiltin(R_MED_ADMIN, "医管中心",
                Arrays.asList(P_DASHBOARD, P_REPORTS, P_REPORT_EXPORT), true);
            dirty |= ensureBuiltin(R_USER, "普通用户",
                Arrays.asList(P_DASHBOARD, P_REPORTS, P_REPORT_EXPORT), true);
            dirty |= ensureBuiltin(R_SCREEN, "大屏查看",
                Collections.singletonList(P_SCREEN), true);
            // **不再补建 role_dept_head**。
            //   它的权限点曾与 R_USER 一字不差，已失去区分度；「科主任」「社区主任」两个职务
            //   现直接挂 R_USER。存量数据里若还残留这个角色，由 migrateRemovedDeptHeadRole 清理。
            //
            //   注意：这里**只是不再创建**它，还不够，已删除的角色必须真的从 roles.json 里消失，
            //      否则它仍会出现在「角色管理」和归属管理的下拉里，管理员能继续分配它。
            //      这正是本项目反复出现的那个类：**"不新建" ≠ "已删除"**。

            // 内置系统管理员始终拥有全部权限点（新增权限点后存量数据自动补全）
            Role sys = roles.get(R_SYS_ADMIN);
            if (sys != null && sys.permissions.size() != ALL_PERMISSIONS.size()) {
                sys.permissions = allPermissionList();
                log.info("[收敛] 内置角色 {} 权限点已补全为 {} 项", R_SYS_ADMIN, ALL_PERMISSIONS.size());
                dirty = true;
            }

            // 一次性存量迁移：剥离批量角色上误配的 screen.view（见方法注释）
            dirty |= migrateLegacyScreenPermission(path);

            // 一次性存量迁移：删除已废弃的 role_dept_head，并把仍挂载它的工号改挂 R_USER
            dirty |= migrateRemovedDeptHeadRole(path);

            if (dirty) {
                saveRoles();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 一次性存量迁移（幂等：由 data/.roles-migrated-v1 标记文件保证只执行一次）。
     *
     * 背景（缺陷 M-1）：历史版本中内置角色「医管中心」「普通用户」默认携带 screen.view，
     * 而这两个角色被批量分配给全部存量账号，
     * 使得 {@link #hasScreenPermission} 的角色通道恒为真，工号级开关 screenEnabled 形同虚设。
     *
     * 迁移动作：从这两个角色移除 screen.view，大屏访问改由
     * 「超管 ‖ role_screen 角色 ‖ 工号开关」三者之一决定。
     *
     * @return 是否有实际变更（需要回写 roles.json）
     */
    private boolean migrateLegacyScreenPermission(Path rolesPath) {
        Path dataDir = rolesPath.getParent();
        if (dataDir == null) return false;
        Path marker = dataDir.resolve(ROLES_MIGRATION_MARKER);
        if (Files.exists(marker)) return false;

        boolean changed = false;
        for (String id : new String[]{R_MED_ADMIN, R_USER}) {
            Role r = roles.get(id);
            if (r == null || r.permissions == null) continue;
            if (r.permissions.contains(P_SCREEN)) {
                List<String> next = new ArrayList<>(r.permissions);
                next.remove(P_SCREEN);
                r.permissions = next;
                changed = true;
                log.warn("[迁移] 内置角色 {} 移除 screen.view：该角色为批量分配角色，"
                    + "保留此权限点会使工号级大屏开关 screenEnabled 完全失效", id);
            }
        }
        try {
            Files.writeString(marker,
                "screen-perm-v1 appliedAt=" + java.time.Instant.now() + System.lineSeparator(),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("写入角色迁移标记失败，下次启动将重试: {}", marker.toAbsolutePath(), e);
        }
        return changed;
    }

    /**
     * 一次性存量迁移（幂等：由 data/.roles-dept-head-removed-v1 标记文件保证只执行一次）。
     *
     * <p>已废弃的内置角色 {@code role_dept_head}（科主任）已删除。
     * 它的权限点（{@code dashboard.view + reports.view + report.export}）与
     * {@link #R_USER} **一字不差**，角色已失去区分度；「科主任」「社区主任」两个职务
     * 现直接挂 {@link #R_USER}（见 {@code PositionService}）。
     *
     * <p>方法做两件事，缺一不可：
     * <ol>
     *   <li>把仍挂载该角色的工号改挂 {@link #R_USER} ，
     *       否则它们会落进 {@link #permissionsOf} 的「roleId 找不到角色」兜底分支，
     *       权限从 3 项缩到 1 项（只剩 {@code dashboard.view}），**用户的报表入口会静默消失**。
     *       注意：本方法执行时该角色**已从 {@code roles} 里被移除**（见 {@link #loadRoles}），
     *       所以这一步**不能**靠"读旧角色的权限再平移"，必须显式用 R_USER。</li>
     *   <li>把它从角色表里删掉，只"不再补建"是不够的，
     *       残留条目仍会出现在角色管理与归属管理的下拉里，管理员能继续分配它。</li>
     * </ol>
     *
     * <p>为什么先改工号、后删角色：两步都会在同一个 {@code @PostConstruct} 里完成，
     * 且顺序不影响正确性（本方法显式用 R_USER，不依赖旧角色是否存在）；
     * 但先改工号更安全，万一后续步骤抛错，用户至少已经落到了有效角色上。
     *
     * @return 是否有实际变更（需要回写 roles.json）
     */
    private boolean migrateRemovedDeptHeadRole(Path rolesPath) {
        Path dataDir = rolesPath.getParent();
        if (dataDir == null) return false;
        Path marker = dataDir.resolve(ROLES_DEPT_HEAD_REMOVAL_MARKER);
        if (Files.exists(marker)) return false;

        boolean changed = false;

        // ① 把仍挂该角色的工号改挂 R_USER（先做，保证用户始终落在有效角色上）
        //    走 reassignRole 而不是 setUserScope，后者会覆盖这批工号已有的社区/科室范围
        int moved = userManagementService.reassignRole(R_DEPT_HEAD_REMOVED, R_USER);
        if (moved > 0) {
            changed = true;
            log.warn("[迁移] 已废弃角色 {} 下的 {} 个工号改挂 {}（角色已删除，不再有区分度）",
                R_DEPT_HEAD_REMOVED, moved, R_USER);
        }

        // ② 从角色表里彻底删除该角色
        Role removed = roles.remove(R_DEPT_HEAD_REMOVED);
        if (removed != null) {
            changed = true;
            log.warn("[迁移] 已删除废弃内置角色 {}（原 {} 项权限，与 {} 完全相同）",
                R_DEPT_HEAD_REMOVED, removed.permissions == null ? 0 : removed.permissions.size(), R_USER);
        }

        try {
            Files.writeString(marker,
                "dept-head-removed-v1 moved=" + moved + " appliedAt="
                    + java.time.Instant.now() + System.lineSeparator(),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("写入角色迁移标记失败，下次启动将重试: {}", marker.toAbsolutePath(), e);
        }
        return changed;
    }

    /**
     * 确保内置角色存在（仅在缺失时创建，不覆盖管理员后续的调整）。
     *
     * @return 是否实际新建了角色（调用方须并入 dirty，否则不会落盘）
     */
    private boolean ensureBuiltin(String id, String name, List<String> perms, boolean builtin) {
        if (roles.containsKey(id)) return false;
        Role r = new Role();
        r.id = id;
        r.name = name;
        r.builtin = builtin;
        r.permissions = new ArrayList<>(perms);
        roles.put(id, r);
        log.info("[初始化] 补建内置角色: {} ({})", name, id);
        return true;
    }

    private synchronized void saveRoles() {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(roles.values());
            AtomicFileWriter.writeString(rolesFile(), json);
        } catch (IOException e) {
            log.error("保存角色数据失败", e);
        }
    }

    // ============ 权限判定 ============

    /** 是否超管（内置 admin，直通所有权限） */
    public boolean isSuperAdmin(String username) {
        return userManagementService.isSuperAdminAccount(username);
    }

    /** 用户拥有的权限点集合（含存量 role 兼容映射；超管返回全部） */
    public Set<String> permissionsOf(String username) {
        if (isSuperAdmin(username)) {
            Set<String> all = new LinkedHashSet<>();
            for (String[] p : ALL_PERMISSIONS) all.add(p[0]);
            return all;
        }
        String roleId = resolveRoleId(username);
        Role role = roleId == null ? null : roles.get(roleId);
        if (role == null) {
            // 找不到角色：至少给工作台，避免锁死
            return new LinkedHashSet<>(Collections.singletonList(P_DASHBOARD));
        }
        return new LinkedHashSet<>(role.permissions);
    }

    public boolean has(String username, String permission) {
        return permissionsOf(username).contains(permission);
    }

    /** 大屏权限双通道：角色 screen.view 或 工号 screenEnabled 开关 或 超管 */
    public boolean hasScreenPermission(String username) {
        if (isSuperAdmin(username)) return true;
        if (has(username, P_SCREEN)) return true;
        return userManagementService.hasScreenPermission(username);
    }

    /** 解析用户角色ID：显式 roleId 优先；旧 role 字段兼容映射（ADMIN→系统管理员） */
    public String resolveRoleId(String username) {
        return userManagementService.resolveRoleId(username);
    }

    // ============ 角色维护 API ============

    public List<Role> listRoles() {
        lock.readLock().lock();
        try {
            List<Role> list = new ArrayList<>(roles.values());
            // 脱敏不需要，权限点即字符串清单
            return list;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Role getById(String id) {
        lock.readLock().lock();
        try {
            return roles.get(id);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Role create(String name, List<String> permissions) {
        String id = "role_" + System.currentTimeMillis();
        lock.writeLock().lock();
        try {
            Role r = new Role();
            r.id = id;
            r.name = name;
            r.builtin = false;
            r.permissions = normalize(permissions);
            roles.put(id, r);
            saveRoles();
            log.info("[AUDIT] action=createRole, roleId={}, name={}, permissions={}", id, name, r.permissions);
            return r;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 修改角色的权限点清单。
     *
     * <p>越权防护：操作人不得修改自己当前所持的角色。否则只有 {@code roles.manage}
     * 一个权限点的账号，把自己的角色原地加上 {@code datasource.manage} 等全部权限点即成超管 ，
     * 与「把自己的 roleId 改成 role_sys_admin」是同一个逃逸格。
     * 超管不受此限（它本就持有全部权限点，改自己的角色不产生新增权限）。
     */
    public Role update(String id, String name, List<String> permissions, String operator) {
        lock.writeLock().lock();
        try {
            Role r = roles.get(id);
            if (r == null) throw new IllegalArgumentException("角色不存在");
            if (operator != null && !isSuperAdmin(operator)
                && id.equals(resolveRoleId(operator))) {
                throw new IllegalArgumentException("不能修改自己当前所持的角色，请改用其他角色或由他人为你分配");
            }
            if (name != null && !name.isBlank()) r.name = name.trim();
            r.permissions = normalize(permissions);
            saveRoles();
            log.info("[AUDIT] action=updateRole, operator={}, roleId={}, name={}, permissions={}",
                operator, id, r.name, r.permissions);
            return r;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void delete(String id) {
        if (isBuiltin(id)) throw new IllegalArgumentException("内置角色不可删除");
        long users = userManagementService.countUsersWithRole(id);
        if (users > 0) throw new IllegalArgumentException("该角色下还有 " + users + " 个用户，请先转移再删除");
        lock.writeLock().lock();
        try {
            if (roles.remove(id) == null) throw new IllegalArgumentException("角色不存在");
            saveRoles();
            log.info("[AUDIT] action=deleteRole, roleId={}", id);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isBuiltin(String id) {
        Role r = roles.get(id);
        return r != null && r.builtin;
    }

    private List<String> normalize(List<String> permissions) {
        Set<String> valid = new LinkedHashSet<>();
        if (permissions != null) {
            for (String p : permissions) {
                for (String[] def : ALL_PERMISSIONS) {
                    if (def[0].equals(p)) { valid.add(p); break; }
                }
            }
        }
        return new ArrayList<>(valid);
    }

    private static List<String> allPermissionList() {
        List<String> all = new ArrayList<>();
        for (String[] p : ALL_PERMISSIONS) all.add(p[0]);
        return all;
    }

    // ============ 数据结构 ============

    public static class Role {
        public String id;
        public String name;
        public boolean builtin;
        public List<String> permissions = new ArrayList<>();
    }
}
