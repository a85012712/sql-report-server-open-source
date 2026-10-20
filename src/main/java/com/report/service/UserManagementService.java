package com.report.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.stereotype.Service;

import com.report.util.AtomicFileWriter;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);

    /** 大屏号特殊取值：全部社区（大小写不敏感） */
    public static final String SCREEN_NO_ALL = "ALL";

    /**
     * 未配置「可查看社区」的账号，大屏默认可见全部社区的管理类角色。
     * 与 {@link RolePermissionService} 的内置角色 ID 保持一致（编译期常量，无循环依赖）。
     */
    private static final Set<String> SCREEN_DEFAULT_ALL_ROLES = Set.of(
        RolePermissionService.R_SYS_ADMIN, RolePermissionService.R_MED_ADMIN);

    private final InMemoryUserDetailsManager userDetailsManager;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final Map<String, UserRecord> userRecords = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 磁盘用户表不可信（主文件与 .bak 均解析失败，或恢复后仅拿到备份）。
     * 置位后拒绝一切落盘，防止把「只剩内置超管」的内存态写回磁盘覆盖真实账号。
     */
    private volatile boolean userStoreReadOnly = false;

    @Value("${app.security.admin.username:admin}")
    private String adminUsername;

    /**
     * 新建账号与「重置口令」时下发的初始口令。
     *
     * <p>默认 666999 是历史沿用的共享口令，便于管理员统一告知用户；
     * 但它同时是公开可猜的，因此做成可配置项 —— 对外部署时应当改掉
     * （{@code INITIAL_USER_PASSWORD} 环境变量，或 application.yml 的
     * {@code app.security.initial-password}）。
     */
    @Value("${app.security.initial-password:666999}")
    private String initialPassword;

    @Value("${app.users.data-file:}")
    private String usersDataFile;

    public UserManagementService(InMemoryUserDetailsManager userDetailsManager,
                                  PasswordEncoder passwordEncoder,
                                  ObjectMapper objectMapper) {
        this.userDetailsManager = userDetailsManager;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        // 记录管理员用户
        userRecords.put(adminUsername, new UserRecord(adminUsername, "ADMIN", true));

        // 如果未配置数据文件路径，使用相对于jar包的路径
        if (usersDataFile == null || usersDataFile.isEmpty()) {
            String appHome = System.getProperty("APP_HOME", System.getProperty("user.dir"));
            usersDataFile = Paths.get(appHome, "data", "users.json").toString();
            log.info("使用默认用户数据文件路径: {}", usersDataFile);
        }

        // 加载持久化的用户数据
        loadUsersFromDisk();

        // 一次性存量迁移：大屏权限由「默认关闭」改为「默认开启」（幂等，标记文件保证只跑一次）
        migrateScreenDefaultOn();
    }

    /**
     * 从磁盘加载用户数据
     */
    private void loadUsersFromDisk() {
        Path path = Paths.get(usersDataFile);
        if (!Files.exists(path)) {
            log.info("用户数据文件不存在，跳过加载: {}", path.toAbsolutePath());
            return;
        }

        List<UserRecord> records = readRecordsWithBackup(path);
        if (records == null) {
            // 主文件与 .bak 都不可用：readRecordsWithBackup 已置只读守卫，
            // 此处直接返回，绝不把「只剩内置超管」的内存态当成真实数据写回磁盘。
            return;
        }
        try {
            log.info("用户记录读取完成: {} 条, {}", records.size(), path.toAbsolutePath());

            int restored = 0;
            lock.writeLock().lock();
            try {
                for (UserRecord record : records) {
                    // 跳过管理员（已在init中记录，密码由SecurityConfig管理）
                    if (record.username.equals(adminUsername)) {
                        log.debug("跳过管理员用户: {}", record.username);
                        continue;
                    }

                    // 跳过已存在的用户
                    if (userRecords.containsKey(record.username)) {
                        log.debug("跳过已存在用户: {}", record.username);
                        continue;
                    }

                    // 恢复用户到内存
                    if (record.encodedPassword != null && !record.encodedPassword.isEmpty()) {
                        // 规范化 groups：null/空 → ["default"]，与运行时语义一致
                        if (!record.isAdmin && !"ADMIN".equalsIgnoreCase(record.role)
                            && (record.groups == null || record.groups.isEmpty())) {
                            record.groups = new ArrayList<>(Collections.singletonList("default"));
                        }
                        UserDetails user = User.withUsername(record.username)
                            .password(record.encodedPassword)
                            .roles(record.role)
                            .build();
                        userDetailsManager.createUser(user);
                        userRecords.put(record.username, record);
                        restored++;
                        log.info("恢复用户: {}, 角色: {}, 分组: {}", record.username, record.role, record.groups);
                    } else {
                        log.warn("跳过无密码用户: {}", record.username);
                    }
                }
            } finally {
                lock.writeLock().unlock();
            }

            log.info("用户持久化加载完成: 恢复{}个用户, 共{}个用户(含管理员)", restored, userRecords.size());
        } catch (Exception e) {
            // 解析已在 readRecordsWithBackup 内处理；走到这里说明应用记录时出了意外，
            // 同样按「磁盘状态不可信」处理，避免把半加载状态写回磁盘。
            log.error("应用用户数据失败: {}", path.toAbsolutePath(), e);
            userStoreReadOnly = true;
        }
    }

    /**
     * 读取用户记录；主文件损坏时回退到 {@code users.json.bak}。
     *
     * <p>缺陷：此前主文件解析失败只 {@code log.error} 后继续启动，内存里只剩内置超管，
     * 管理员随后任何一次操作都会把这份空表写回磁盘，全部账号一次断电就没了，
     * 而 {@code .bak} 从来没有任何代码读过。
     *
     * @return 记录列表；主文件与备份均不可读时返回 {@code null}，并已置只读守卫
     */
    private List<UserRecord> readRecordsWithBackup(Path path) {
        List<UserRecord> fromMain = tryReadRecords(path);
        if (fromMain != null) return fromMain;

        Path bak = Paths.get(path.toString() + ".bak");
        if (Files.exists(bak)) {
            List<UserRecord> fromBak = tryReadRecords(bak);
            if (fromBak != null) {
                log.error("用户数据主文件不可用，已从 {} 恢复 {} 条记录；请核对现场后再手动落盘",
                    bak.getFileName(), fromBak.size());
                userStoreReadOnly = true;
                return fromBak;
            }
            log.error("用户数据备份文件同样不可解析: {}", bak.toAbsolutePath());
        } else {
            log.error("用户数据主文件不可用且无备份文件: {}", bak.toAbsolutePath());
        }
        userStoreReadOnly = true;
        log.error("[严重] users.json 及其 .bak 均不可用：本次启动仅信任内置超管，"
            + "并已置为只读以防把空表写回磁盘。请人工从离线备份恢复后重启服务。");
        return null;
    }

    /** 读单个文件；不存在或解析失败返回 null（由调用方决定回退与守卫）。 */
    private List<UserRecord> tryReadRecords(Path file) {
        if (!Files.exists(file)) return null;
        try {
            String json = Files.readString(file);
            return objectMapper.readValue(json, new TypeReference<List<UserRecord>>() {});
        } catch (Exception e) {
            log.error("读取用户数据文件失败: {}", file.toAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 一次性存量迁移：把大屏权限由「默认关闭」改为「默认开启」。
     *
     * <p>背景：历史版本 {@code screenEnabled} 语义为「默认关闭」（旧数据缺字段或为 false），
     * 导致全部存量账号无法访问数据大屏。业务确认改为「默认开启」，
     * 且默认授予范围 = 该账号的「可查看社区」（见 {@link #getScreenCommunities}）。
     *
     * <p>迁移动作：把所有非管理员账号的 {@code screenEnabled} 置为 true。
     * 幂等性由 data/.screen-default-on-v1 标记文件保证（只执行一次，之后管理员的显式关闭不会被覆盖）。
     *
     * @return 是否发生实际变更
     */
    private boolean migrateScreenDefaultOn() {
        Path dataDir = Paths.get(usersDataFile).getParent();
        if (dataDir == null) return false;
        Path marker = dataDir.resolve(".screen-default-on-v1");
        if (Files.exists(marker)) return false;

        int changed = 0;
        lock.writeLock().lock();
        try {
            for (UserRecord r : userRecords.values()) {
                if (r.isAdmin || "ADMIN".equalsIgnoreCase(r.role)) continue;
                if (!Boolean.TRUE.equals(r.screenEnabled)) {
                    r.screenEnabled = Boolean.TRUE;
                    changed++;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (changed > 0) {
            saveUsersToDisk();
        }
        try {
            Files.writeString(marker,
                "screen-default-on-v1 appliedAt=" + java.time.Instant.now()
                    + " affected=" + changed + System.lineSeparator(),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("写入大屏默认开启迁移标记失败，下次启动将重试: {}", marker.toAbsolutePath(), e);
        }
        log.warn("[迁移] 大屏权限改为默认开启：{} 个账号由关闭置为开启（默认范围=各自可查看社区）", changed);
        return changed > 0;
    }

    /**
     * 保存用户数据到磁盘
     *
     * <p>磁盘状态不可信时（{@link #userStoreReadOnly}）拒绝写入：
     * 内存表此时只是残缺视图，落盘就等于把残缺视图变成既成事实。
     */
    private synchronized void saveUsersToDisk() {
        if (userStoreReadOnly) {
            log.error("[拒绝写入] 用户表当前不可信（数据文件损坏且未能从 .bak 完整恢复），"
                + "已跳过落盘以免覆盖真实账号。请从离线备份恢复 {} 后重启服务。", usersDataFile);
            return;
        }
        Path path = Paths.get(usersDataFile);
        String json;
        lock.readLock().lock();
        try {
            // 排除管理员（密码由SecurityConfig管理，不持久化）
            List<UserRecord> records = userRecords.values().stream()
                .filter(r -> !r.isAdmin)
                .collect(java.util.stream.Collectors.toList());
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(records);
            log.debug("用户数据准备保存: {}, 共{}个用户", path.toAbsolutePath(), records.size());
        } catch (Exception e) {
            log.error("序列化用户数据失败", e);
            return;
        } finally {
            lock.readLock().unlock();
        }
        try {
            AtomicFileWriter.writeString(path, json);
        } catch (IOException e) {
            log.error("保存用户数据失败: {}", usersDataFile, e);
        }
    }

    public boolean userExists(String username) {
        return userDetailsManager.userExists(username);
    }

    /**
     * 是否为内置超级管理员（环境变量 ADMIN_USERNAME 对应的账号）。
     * 超级管理员不可被变更/删除；普通管理员（后续创建、角色为 ADMIN）可正常变更。
     */
    private boolean isSuperAdmin(String username) {
        return username != null && adminUsername != null && adminUsername.equals(username);
    }

    /**
     * 生成随机临时口令的能力已随「重置统一用配置的初始口令」这一决策废弃
     * 原实现 {@code generateTemporaryPassword()}
     * 全项目零调用、且诱导出前端一句并不成立的文案（"留空自动生成临时密码"），已删除；
     * 真要恢复从 tag v1.3.5-20260929 之前的提交里取，别再造一个没人调的第二版本。
     */

    private void validatePasswordPolicy(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("密码长度不能少于8位");
        }
        if (password.length() > 64) {
            throw new IllegalArgumentException("密码长度不能超过64位");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new IllegalArgumentException("密码必须同时包含字母和数字");
        }
    }

    /**
     * 创建用户（返回初始密码，仅创建时展示）
     */
    public String createUser(String username, String name, String role) {
        return createUser(username, name, role, null, null, null);
    }

    /**
     * 创建用户（完整版：角色ID + 可查看社区/科室）
     * @return 初始密码（取配置项 app.security.initial-password；首次登录强制修改）
     */
    public String createUser(String username, String name, String role, String roleId,
                             List<String> allowedCommunities, List<String> allowedDepartments) {
        // 校验用户名
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (username.length() < 3 || username.length() > 20) {
            throw new IllegalArgumentException("用户名长度必须在3-20个字符之间");
        }
        if (!username.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("用户名只能包含字母、数字和下划线");
        }

        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("姓名不能为空");
        }

        // 角色处理：显式 roleId 优先（自动推导旧 role 字段）；否则必须显式 ADMIN/USER
        if (roleId != null && !roleId.isBlank()) {
            role = "role_sys_admin".equals(roleId.trim()) ? "ADMIN" : "USER";
        }
        if (!"USER".equals(role) && !"ADMIN".equals(role)) {
            throw new IllegalArgumentException("角色无效");
        }

        // 初始口令取配置项 app.security.initial-password（默认 666999）；
        // 仅用于首次登录，登录后强制修改；不走密码强度策略
        String tempPassword = initialPassword;
        String encodedPassword = passwordEncoder.encode(tempPassword);
        UserDetails newUser = User.withUsername(username)
            .password(encodedPassword)
            .roles(role)
            .build();

        userDetailsManager.createUser(newUser);

        // 记录用户信息（包含加密后的密码用于持久化）
        UserRecord record = new UserRecord(username, role, false);
        record.name = name.trim();
        record.encodedPassword = encodedPassword;
        record.mustChangePassword = true;
        // 大屏权限默认开启（大屏号留空 = 默认跟随该账号的「可查看社区」）
        record.screenEnabled = Boolean.TRUE;
        record.screenNo = null;
        // 新用户默认仅可访问 default 分组（与 getUserGroups 语义一致）
        record.groups = new ArrayList<>(Collections.singletonList("default"));
        if (roleId != null && !roleId.isBlank()) record.roleId = roleId.trim();
        if (allowedCommunities != null && !allowedCommunities.isEmpty()) {
            record.allowedCommunities = new ArrayList<>(allowedCommunities);
        }
        if (allowedDepartments != null && !allowedDepartments.isEmpty()) {
            record.allowedDepartments = new ArrayList<>(allowedDepartments);
        }
        userRecords.put(username, record);

        // 同步保存到磁盘
        saveUsersToDisk();

        return tempPassword;
    }

    /**
     * 修改密码
     */
    /**
     * 管理员修改指定用户密码。
     * @param forceChange 是否强制用户下次登录修改密码
     */
    public void changePassword(String username, String newPassword, boolean forceChange) {
        if (isSuperAdmin(username)) {
            throw new IllegalArgumentException("超级管理员账号不可变更");
        }
        validatePasswordPolicy(newPassword);

        String encodedPassword = passwordEncoder.encode(newPassword);
        userDetailsManager.loadUserByUsername(username);

        User.UserBuilder builder = User.withUsername(username)
            .password(encodedPassword)
            .roles(userRecords.getOrDefault(username, new UserRecord(username, "USER", false)).role);

        userDetailsManager.updateUser(builder.build());

        // 更新记录
        UserRecord record = userRecords.get(username);
        if (record != null) {
            record.encodedPassword = encodedPassword;
            record.mustChangePassword = forceChange;
            saveUsersToDisk();
        }

    }

    /**
     * 管理员代设密码：同样强制下次登录改密。
     *
     * <p>此前这里传 false，而重置弹窗统一写着「用户下次登录将强制修改」，
     * 于是指定密码那条分支的承诺是假的。管理员知道的那个口令应当只作为一次性交接口令，
     * 用户第一次登录就得换成只有自己知道的。要设一个「不强制改」的正式口令，
     * 走三参重载显式传 false（本仓库的测试正是这么用的）。
     */
    public void changePassword(String username, String newPassword) {
        changePassword(username, newPassword, true);
    }

    /**
     * 重置为配置的初始密码，并强制下次登录改密；返回明文密码（仅本次展示）
     */
    public String resetToTemporaryPassword(String username) {
        if (isSuperAdmin(username)) {
            throw new IllegalArgumentException("超级管理员账号不可变更");
        }
        String tempPassword = initialPassword;
        String encodedPassword = passwordEncoder.encode(tempPassword);
        userDetailsManager.loadUserByUsername(username);

        User.UserBuilder builder = User.withUsername(username)
            .password(encodedPassword)
            .roles(userRecords.getOrDefault(username, new UserRecord(username, "USER", false)).role);
        userDetailsManager.updateUser(builder.build());

        UserRecord record = userRecords.get(username);
        if (record != null) {
            record.encodedPassword = encodedPassword;
            record.mustChangePassword = true;
            saveUsersToDisk();
        }
        return tempPassword;
    }

    /**
     * 修改角色
     */
    public void changeRole(String username, String newRole) {
        if (isSuperAdmin(username)) {
            throw new IllegalArgumentException("超级管理员账号不可变更");
        }
        if (!"USER".equals(newRole) && !"ADMIN".equals(newRole)) {
            throw new IllegalArgumentException("角色只能是 USER 或 ADMIN");
        }

        UserDetails existing = userDetailsManager.loadUserByUsername(username);

        User.UserBuilder builder = User.withUsername(username)
            .password(existing.getPassword())
            .roles(newRole);

        userDetailsManager.updateUser(builder.build());

        // 更新记录
        UserRecord record = userRecords.get(username);
        if (record != null) {
            record.role = newRole;
            /* 必须同步 roleId：resolveRoleId 优先读 roleId，只改 legacy role 的话
             *   「把系统管理员降为普通用户」界面上显示成功、权限却一点没变，
             *   而且这份矛盾还会被 saveUsersToDisk 持久化到磁盘。 */
            record.roleId = "ADMIN".equals(newRole)
                ? RolePermissionService.R_SYS_ADMIN : RolePermissionService.R_USER;
            saveUsersToDisk();
        }

    }
    public void changeOwnPassword(String username, String oldPassword, String newPassword) {
        UserDetails user = userDetailsManager.loadUserByUsername(username);
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new IllegalArgumentException("当前密码不正确");
        }
        validatePasswordPolicy(newPassword);
        String ep = passwordEncoder.encode(newPassword);
        User.UserBuilder b = User.withUsername(username).password(ep)
            .roles(userRecords.getOrDefault(username, new UserRecord(username, "USER", false)).role);
        userDetailsManager.updateUser(b.build());
        UserRecord r = userRecords.get(username);
        if (r != null) {
            r.encodedPassword = ep;
            r.mustChangePassword = false;
            saveUsersToDisk();
        }
    }
    public boolean mustChangePassword(String username) { UserRecord r = userRecords.get(username); return r != null && r.mustChangePassword; }
    public String getUserName(String username) { UserRecord r = userRecords.get(username); return r != null ? r.name : null; }
    public String getNextId() {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (String key : userRecords.keySet()) { if (key.length() == 4 && key.matches("[0-9]+")) used.add(key); }
        for (int i = 1; i <= 9999; i++) { String id = String.valueOf(i); while (id.length() < 4) id = "0" + id; if (!used.contains(id)) return id; }
        return null;
    }


    /**
     * 删除用户
     */
    public void deleteUser(String username) {
        // 不允许删除管理员
        UserRecord record = userRecords.get(username);
        if (record != null && record.isAdmin) {
            throw new IllegalArgumentException("不能删除管理员用户");
        }

        userDetailsManager.deleteUser(username);
        userRecords.remove(username);

        // 保存到磁盘
        saveUsersToDisk();

    }

    /**
     * 列出所有用户
     */
    public List<Map<String, Object>> listUsers() {
        List<Map<String, Object>> users = new ArrayList<>();

        lock.readLock().lock();
        try {
            for (UserRecord record : userRecords.values()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("username", record.username);
                map.put("name", record.name != null ? record.name : "");
                map.put("role", record.role);
                map.put("mustChangePassword", record.mustChangePassword);
                map.put("isAdmin", record.isAdmin);
                // 大屏：开关（默认开启）+ 大屏号（空=跟随可查看社区）+ 实际可见社区（供列表回显）
                map.put("screenEnabled", hasScreenPermission(record.username));
                map.put("screenNo", record.screenNo == null ? "" : record.screenNo);
                map.put("screenScope", describeScreenCommunities(record.username));
                map.put("roleId", record.roleId != null && !record.roleId.isBlank()
                    ? record.roleId
                    : ("ADMIN".equalsIgnoreCase(record.role) ? "role_sys_admin" : "role_user"));
                map.put("allowedCommunities", record.allowedCommunities != null ? record.allowedCommunities : Collections.emptyList());
                map.put("allowedDepartments", record.allowedDepartments != null ? record.allowedDepartments : Collections.emptyList());
                // 与 getUserGroups 语义对齐：管理员 allAccess=true；普通用户至少 default
                boolean isAdminUser = record.isAdmin || "ADMIN".equalsIgnoreCase(record.role);
                map.put("allAccess", isAdminUser);
                if (isAdminUser) {
                    map.put("groups", Collections.emptyList());
                } else if (record.groups == null || record.groups.isEmpty()) {
                    map.put("groups", Collections.singletonList("default"));
                } else {
                    map.put("groups", record.groups);
                }
                users.add(map);
            }
        } finally {
            lock.readLock().unlock();
        }

        return users;
    }

    /**
     * 获取用户的可访问分组列表。
     * 语义：
     * - 管理员：null（表示全部）
     * - 普通用户 groups 为 null 或空：默认仅 ["default"]（与创建用户一致，前端勿显示“全部”）
     * - 普通用户已配置分组：返回配置列表
     */
    public List<String> getUserGroups(String username) {
        UserRecord record = userRecords.get(username);
        if (record == null) return Collections.singletonList("default");
        if (record.isAdmin || "ADMIN".equalsIgnoreCase(record.role)) return null; // null表示全部
        if (record.groups == null || record.groups.isEmpty()) {
            return Collections.singletonList("default");
        }
        return new ArrayList<>(record.groups);
    }

    /**
     * 设置用户的可访问分组。
     * null 或空列表统一落盘为 ["default"]，避免前端误显示“全部”。
     */
    public void setUserGroups(String username, List<String> groups) {
        UserRecord record = userRecords.get(username);
        if (record == null) {
            throw new IllegalArgumentException("用户不存在: " + username);
        }
        if (groups == null || groups.isEmpty()) {
            record.groups = new ArrayList<>(Collections.singletonList("default"));
        } else {
            record.groups = new ArrayList<>(groups);
        }
        saveUsersToDisk();
        log.info("[AUDIT] action=setUserGroups, username={}, groups={}", username, record.groups);
    }

    /**
     * 大屏访问开关是否开启（工号级）。
     *
     * <p>语义：默认开启。
     * 管理员在用户管理页显式关闭后才为 false；{@code screenEnabled} 为 null
     * （旧数据缺字段）同样视为开启。管理员/超管天然为 true。
     */
    public boolean hasScreenPermission(String username) {
        if (username == null) return false;
        UserRecord r = userRecords.get(username);
        if (r == null) return false;
        if (r.isAdmin || "ADMIN".equalsIgnoreCase(r.role)) return true;
        return !Boolean.FALSE.equals(r.screenEnabled);
    }

    /**
     * 设置大屏访问开关（工号级）。
     */
    public void setScreenPermission(String username, boolean enabled) {
        UserRecord record = userRecords.get(username);
        if (record == null) {
            throw new IllegalArgumentException("用户不存在: " + username);
        }
        if (record.isAdmin || "ADMIN".equalsIgnoreCase(record.role)) {
            throw new IllegalArgumentException("管理员账号天然拥有大屏权限，无需设置");
        }
        record.screenEnabled = enabled;
        saveUsersToDisk();
    }

    /** 该账号是否显式配置了大屏号（非空）。登录直达大屏的判定依据。 */
    public boolean hasExplicitScreenNo(String username) {
        UserRecord r = userRecords.get(username);
        return r != null && r.screenNo != null && !r.screenNo.isBlank();
    }

    /** 大屏号原始值：null/空 = 未配置（默认跟随「可查看社区」） */
    public String getScreenNo(String username) {
        UserRecord r = userRecords.get(username);
        return r == null ? null : r.screenNo;
    }

    /**
     * 设置大屏号（大屏可见社区范围的显式配置）。
     *
     * <p>取值约定：
     * <ul>
     *   <li>{@code null} / 空串 → 清除配置，回到「跟随该账号的可查看社区」</li>
     *   <li>{@code ALL} → 全部社区</li>
     *   <li>社区代码（如 {@code D}）→ 仅该社区</li>
     * </ul>
     * 只做格式归一化（去空格、大写），取值合法性由调用方（Controller）对照社区字典校验。
     */
    public void setScreenNo(String username, String screenNo) {
        UserRecord record = userRecords.get(username);
        if (record == null) {
            throw new IllegalArgumentException("用户不存在: " + username);
        }
        String normalized = normalizeScreenNo(screenNo);
        record.screenNo = normalized;
        saveUsersToDisk();
    }

    /** 大屏号归一化：空白 → null；ALL（忽略大小写）→ "ALL"；其余去空格并转大写 */
    public static String normalizeScreenNo(String screenNo) {
        if (screenNo == null) return null;
        String v = screenNo.trim();
        if (v.isEmpty()) return null;
        if (SCREEN_NO_ALL.equalsIgnoreCase(v)) return SCREEN_NO_ALL;
        return v.toUpperCase();
    }

    /**
     * 该账号在大屏上可见的社区集合。
     *
     * <p>解析顺序：
     * <ol>
     *   <li>管理员 / 超管 → {@code null}（不限，全部社区）</li>
     *   <li>已显式配置大屏号：{@code ALL} → {@code null}（不限）；社区代码 → 仅该社区</li>
     *   <li>未配置大屏号 → 跟随「可查看社区」（allowedCommunities）</li>
     *   <li>「可查看社区」也为空 → 管理类角色（系统管理员 / 医管中心）视为不限；
     *       其余角色返回空列表（= 无可见社区，大屏不展示任何社区数据）</li>
     * </ol>
     *
     * @return {@code null} 表示不限（全部社区）；空列表表示无任何可见社区；否则为可见社区代码
     */
    public List<String> getScreenCommunities(String username) {
        if (username == null) return Collections.emptyList();
        UserRecord r = userRecords.get(username);
        if (r == null) return Collections.emptyList();
        // 1) 管理员/超管不限
        if (r.isAdmin || "ADMIN".equalsIgnoreCase(r.role)) return null;
        // 2) 显式大屏号优先
        String no = normalizeScreenNo(r.screenNo);
        if (no != null) {
            if (SCREEN_NO_ALL.equals(no)) return null;
            return new ArrayList<>(Collections.singletonList(no));
        }
        // 3) 跟随「可查看社区」
        if (r.allowedCommunities != null && !r.allowedCommunities.isEmpty()) {
            return new ArrayList<>(r.allowedCommunities);
        }
        // 4) 未配置社区：按角色区分
        if (SCREEN_DEFAULT_ALL_ROLES.contains(resolveRoleId(username))) return null;
        return Collections.emptyList();
    }

    /** 大屏可见社区的展示文案（用户管理页回显用） */
    public String describeScreenCommunities(String username) {
        List<String> c = getScreenCommunities(username);
        if (c == null) return "全部社区";
        if (c.isEmpty()) return "无可见社区";
        return String.join("、", c);
    }

    /** 是否内置超级管理员账号（供 RolePermissionService 直通判定） */
    public boolean isSuperAdminAccount(String username) {
        return isSuperAdmin(username);
    }

    /** 解析用户角色ID：显式 roleId 优先；旧 role 字段兼容映射（ADMIN→系统管理员） */
    public String resolveRoleId(String username) {
        UserRecord r = userRecords.get(username);
        if (r == null) return null;
        if (r.roleId != null && !r.roleId.isBlank()) return r.roleId;
        return "ADMIN".equalsIgnoreCase(r.role) ? "role_sys_admin" : "role_user";
    }

    /** 统计挂在某角色下的用户数（删除角色前检查） */
    public long countUsersWithRole(String roleId) {
        long n = 0;
        for (UserRecord r : userRecords.values()) {
            if (roleId.equals(resolveRoleId(r.username))) n++;
        }
        return n;
    }

    /** 可查看社区（null/空 = 不限）；用于报表/大屏数据权限过滤 */
    public List<String> getAllowedCommunities(String username) {
        UserRecord r = userRecords.get(username);
        return (r == null || r.allowedCommunities == null || r.allowedCommunities.isEmpty())
            ? null : new ArrayList<>(r.allowedCommunities);
    }

    /** 可查看科室（null/空 = 不限） */
    public List<String> getAllowedDepartments(String username) {
        UserRecord r = userRecords.get(username);
        return (r == null || r.allowedDepartments == null || r.allowedDepartments.isEmpty())
            ? null : new ArrayList<>(r.allowedDepartments);
    }

    /** 更新用户的角色与数据权限（社区/科室）；同步刷新 Spring Security 中的角色 */
    public void setUserScope(String operator, String username, String roleId,
                             List<String> allowedCommunities, List<String> allowedDepartments) {
        if (isSuperAdmin(username)) {
            throw new IllegalArgumentException("超级管理员账号不可变更");
        }
        // 越权防护：只有 users.manage 的委托管理员，把 roleId 写成 role_sys_admin
        //   或把 allowedCommunities 清空（=不限）即可对自己提权。禁止自改，与
        //   RolePermissionService#update「不得改自己角色」构成同一道逃逸格封堵。
        if (operator != null && operator.equals(username)) {
            throw new IllegalArgumentException("不能变更自己的角色或数据权限，请由其他管理员操作");
        }
        UserRecord record = userRecords.get(username);
        if (record == null) throw new IllegalArgumentException("用户不存在: " + username);
        if (roleId == null || roleId.isBlank()) throw new IllegalArgumentException("角色不能为空");

        record.roleId = roleId.trim();
        // 数据权限：空列表统一置 null（=不限）
        record.allowedCommunities = (allowedCommunities == null || allowedCommunities.isEmpty())
            ? null : new ArrayList<>(allowedCommunities);
        record.allowedDepartments = (allowedDepartments == null || allowedDepartments.isEmpty())
            ? null : new ArrayList<>(allowedDepartments);
        // 兼容旧 role 字段与 Spring authority：系统管理员→ADMIN，其余→USER
        syncLegacyRole(record, username);
        saveUsersToDisk();
        log.info("[AUDIT] action=setUserScope, operator={}, username={}, roleId={}, communities={}, departments={}",
            operator, username, record.roleId, record.allowedCommunities, record.allowedDepartments);
    }

    /**
     * 保存工号归属：源字段（归属科室 / 职务 / 角色）+ 派生结果（可查看社区 / 科室）一次性落库。
     *
     * <p>入参只接收源字段，{@code allowedCommunities} / {@code allowedDepartments}
     * 是派生结果，由 {@link AffiliationService} 算好后传入，本方法只负责落库与保护校验。
     *
     * <p>之所以让派生结果仍然写回这两个老字段，是为了让既有的数据权限过滤代码
     * （报表查询、大屏）零改动，它们只认这两个字段。
     *
     * @throws IllegalArgumentException 命中保护规则或参数非法
     */
    public void setAffiliationRaw(String operator, String username,
                                  String department, String position, String roleId,
                                  Boolean scopeOverride,
                                  List<String> allowedCommunities, List<String> allowedDepartments) {
        if (isSuperAdmin(username)) {
            throw new IllegalArgumentException("内置超级管理员账号（环境变量 ADMIN_USERNAME 指定）不可变更归属");
        }
        // 与 setUserScope 同一条越权防护：归属保存会连带落 roleId 与派生数据权限，
        //   自改即可提权。内置超管始终可代为操作。
        if (operator != null && operator.equals(username)) {
            throw new IllegalArgumentException("不能变更自己的归属与角色，请由其他管理员操作");
        }
        UserRecord record = userRecords.get(username);
        if (record == null) throw new IllegalArgumentException("用户不存在: " + username);
        if (roleId == null || roleId.isBlank()) throw new IllegalArgumentException("角色不能为空");

        /* 降权保护：不允许把最后一个「普通」系统管理员改成别的角色。
         * 内置超管不计入，它由环境变量 ADMIN_USERNAME 指定、永远不会失去权限，
         *   只有界面创建的普通管理员才构成「无人可管理系统」的自锁风险。 */
        if ("role_sys_admin".equals(resolveRoleId(username))
            && !"role_sys_admin".equals(roleId.trim())
            && countOrdinarySysAdmins() <= 1) {
            throw new IllegalArgumentException(
                "这是系统内最后一个普通系统管理员，降权后将无人可管理用户与角色（内置超管不计入）");
        }

        record.department = blankToNull(department);
        record.position = blankToNull(position);
        record.roleId = roleId.trim();
        record.scopeOverride = Boolean.TRUE.equals(scopeOverride) ? Boolean.TRUE : null;
        // 数据权限：空列表统一置 null（=不限），与既有语义保持一致
        record.allowedCommunities = (allowedCommunities == null || allowedCommunities.isEmpty())
            ? null : new ArrayList<>(allowedCommunities);
        record.allowedDepartments = (allowedDepartments == null || allowedDepartments.isEmpty())
            ? null : new ArrayList<>(allowedDepartments);

        syncLegacyRole(record, username);
        saveUsersToDisk();
    }

    /** 兼容旧 role 字段与 Spring Security authority：系统管理员→ADMIN，其余→USER */
    private void syncLegacyRole(UserRecord record, String username) {
        String legacyRole = "role_sys_admin".equals(record.roleId) ? "ADMIN" : "USER";
        if (!legacyRole.equals(record.role) || record.isAdmin) {
            record.role = legacyRole;
            record.isAdmin = false;
            UserDetails existing = userDetailsManager.loadUserByUsername(username);
            User.UserBuilder builder = User.withUsername(username)
                .password(existing.getPassword())
                .roles(legacyRole);
            userDetailsManager.updateUser(builder.build());
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * 统计持有 {@code role_sys_admin} 的「普通」管理员数量（不含内置超管）。
     * 用于「最后一个系统管理员降权保护」。
     */
    public long countOrdinarySysAdmins() {
        long n = 0;
        for (UserRecord r : userRecords.values()) {
            if (isSuperAdmin(r.username)) continue;
            if ("role_sys_admin".equals(resolveRoleId(r.username))) n++;
        }
        return n;
    }

    /** 归属科室（源数据） */
    public String getDepartment(String username) {
        UserRecord r = userRecords.get(username);
        return r == null ? null : r.department;
    }

    /** 职务编码（源数据） */
    public String getPosition(String username) {
        UserRecord r = userRecords.get(username);
        return r == null ? null : r.position;
    }

    /**
     * 全部用户记录的只读快照（供归属管理等服务遍历）。
     * 调用方不得修改返回的对象，需要变更请走本类的写方法。
     */
    public List<UserRecord> allRecords() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(userRecords.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 内置超管账号名（环境变量 ADMIN_USERNAME） */
    public String getAdminUsername() {
        return adminUsername;
    }

    /**
     * 迁移专用：仅更新归属源字段与范围覆盖标记，
     * 不触碰 {@code allowedCommunities}/{@code allowedDepartments}。
     *
     * <p>用途是给存量账号回填归属（{@code department} / {@code position}），
     * 保证迁移过程不改变任何人的现有可见范围。
     */
    public void setAffiliationSource(String username, String department, String position,
                                     Boolean scopeOverride) {
        UserRecord r = userRecords.get(username);
        if (r == null) return;
        if (department != null && !department.isBlank()) r.department = department.trim();
        if (position != null && !position.isBlank()) r.position = position.trim();
        r.scopeOverride = Boolean.TRUE.equals(scopeOverride) ? Boolean.TRUE : null;
    }

    /** 把当前内存中的用户记录落盘（供迁移等批量场景调用一次） */
    public void persist() {
        saveUsersToDisk();
    }

    /**
     * 迁移专用：把所有仍挂在 {@code fromRoleId} 上的工号改挂 {@code toRoleId}，
     * 不触碰任何数据范围字段（{@code allowedCommunities} / {@code allowedDepartments}）。
     *
     * <p>用途：删除某个已废弃角色（如 {@code role_dept_head}）时，
     * 必须把它的持有者平移到替代角色上，否则它们会落进「roleId 找不到角色」的兜底分支
     * （只剩 {@code dashboard.view}），用户的报表入口会静默消失。
     *
     * <p>用本方法而不是 {@link #setUserScope}：后者要求传「数据范围」三个字段，
     * 会把这批工号已有的社区/科室范围一并覆盖，迁移不该改变任何人的可见范围。
     *
     * @return 被平移的工号数
     */
    public int reassignRole(String fromRoleId, String toRoleId) {
        if (fromRoleId == null || toRoleId == null || fromRoleId.equals(toRoleId)) return 0;
        int n = 0;
        lock.writeLock().lock();
        try {
            for (UserRecord r : userRecords.values()) {
                if (fromRoleId.equals(r.roleId)) {
                    r.roleId = toRoleId;
                    syncLegacyRole(r, r.username);
                    n++;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        // 注意：落盘放在**释放写锁之后**：saveUsersToDisk() 内部会取读锁，
        //    虽然 ReentrantReadWriteLock 允许「持写锁再取读锁」的降级，
        //    但把落盘放锁外更直观，也避免以后有人给 saveUsersToDisk 加写操作时踩坑。
        if (n > 0) saveUsersToDisk();
        return n;
    }

    /**
     * 用户记录（用于持久化）
     */
    public static class UserRecord {
        public String username;
        public String name;
        public String role;
        public boolean mustChangePassword;
        public boolean isAdmin;
        public String encodedPassword;  // 仅用于持久化
        public List<String> groups;     // 可访问的报表分组ID列表，null或空表示全部
        /**
         * 大屏访问开关。语义为「默认开启」：
         * null（旧数据缺字段）或 true = 开启；仅显式 false = 关闭。
         */
        public Boolean screenEnabled;
        /**
         * 大屏号：大屏可见社区范围的显式配置。
         * null/空 = 未配置（默认跟随 {@link #allowedCommunities}）；
         * {@link #SCREEN_NO_ALL} = 全部社区；社区代码 = 仅该社区。
         */
        public String screenNo;
        public String roleId;                 // 关联角色（roles.json），空=按旧role字段兼容映射
        public List<String> allowedCommunities; // 可查看社区代码，null或空=不限
        public List<String> allowedDepartments; // 可查看科室编码，null或空=不限（本期仅绑定）

        /**
         * 归属科室编码（源数据）， 与上面的 {@code allowedDepartments} 语义不同：
         * <ul>
         *   <li>{@code department} = 「这个人属于哪个科室」，是归属的一部分，决定归属社区</li>
         *   <li>{@code allowedDepartments} = 「这个人能看哪些科室的数据」，是派生结果</li>
         * </ul>
         * 例如社区主任：{@code department} 是本科室，但 {@code allowedDepartments} 为 null（不限），
         * 因为他的范围是整个社区。
         */
        public String department;

        /** 职务编码（源数据，见 positions.json）：决定默认角色与默认数据范围规则 */
        public String position;

        /**
         * 数据范围是否使用自定义覆盖（源数据）。
         * <ul>
         *   <li>{@code null} / {@code false}，按职务的 {@code scopeRule} 实时派生，
         *       此时 {@code allowedCommunities}/{@code allowedDepartments} 只是派生结果的快照</li>
         *   <li>{@code true}，直接用 {@code allowedCommunities}/{@code allowedDepartments}
         *       作为显式范围，不再跟随职务规则</li>
         * </ul>
         * 没有这个标记就无法区分「范围恰好等于职务派生值」和「手工指定了同样的值」，
         * 会导致界面把派生结果误认成人工覆盖。
         */
        public Boolean scopeOverride;

        public UserRecord() {}

        public UserRecord(String username, String role, boolean isAdmin) {
            this.username = username;
            this.role = role;
            this.isAdmin = isAdmin;
        }
    }
}
