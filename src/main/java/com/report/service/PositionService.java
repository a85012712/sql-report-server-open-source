package com.report.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 职务字典服务（{@code data/positions.json}）。
 *
 * <p>职务是「工号归属」的一部分，它决定两件事：
 * <ul>
 *   <li>{@code defaultRoleId}，该职务默认带出的角色（进而决定权限点）</li>
 *   <li>{@code scopeRule}   ，该职务默认的数据范围规则</li>
 * </ul>
 *
 * <p>注意：{@code personnel.json} 的 {@code title} 字段是卫生技术职称
 * （医师 / 主治医师 / 主任医师…），不是行政职务，不可用来填这里。
 *
 * @see AffiliationService 派生引擎
 */
@Service
public class PositionService {

    private static final Logger log = LoggerFactory.getLogger(PositionService.class);

    /** 范围规则：本科室（含其所属社区） */
    public static final String SCOPE_SELF_DEPT = "SELF_DEPT";
    /** 范围规则：本社区全部科室 */
    public static final String SCOPE_SELF_COMMUNITY = "SELF_COMMUNITY";
    /** 范围规则：不限 */
    public static final String SCOPE_ALL = "ALL";

    /** 内置职务编码 */
    public static final String POS_STAFF = "POS_STAFF";
    public static final String POS_DEPT_HEAD = "POS_DEPT_HEAD";
    public static final String POS_COMM_HEAD = "POS_COMM_HEAD";
    public static final String POS_MED_ADMIN = "POS_MED_ADMIN";

    /** 默认职务（存量账号回填用） */
    public static final String DEFAULT_POSITION = POS_STAFF;

    private final ObjectMapper objectMapper;

    @Value("${app.users.data-file:}")
    private String usersDataFile;

    private final Map<String, Position> positions = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public PositionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        loadPositions();
    }

    private Path positionsFile() {
        String appHome = System.getProperty("APP_HOME", System.getProperty("user.dir"));
        String dataDir = usersDataFile != null && !usersDataFile.isEmpty()
            ? Paths.get(usersDataFile).getParent().toString()
            : Paths.get(appHome, "data").toString();
        return Paths.get(dataDir, "positions.json");
    }

    private void loadPositions() {
        Path path = positionsFile();
        lock.writeLock().lock();
        try {
            positions.clear();
            if (Files.exists(path)) {
                try {
                    String json = Files.readString(path);
                    List<Position> list = objectMapper.readValue(json, new TypeReference<List<Position>>() {});
                    for (Position p : list) {
                        if (p.code != null && !p.code.isBlank()) positions.put(p.code, p);
                    }
                    log.info("职务字典加载完成: {} 个职务", positions.size());
                } catch (Exception e) {
                    log.error("解析职务数据失败，使用内置职务: {}", path.toAbsolutePath(), e);
                }
            } else {
                log.info("职务字典文件不存在，使用内置职务: {}", path.toAbsolutePath());
            }

            boolean dirty = !Files.exists(path);
            // 保证内置职务存在（新部署或文件损坏时兜底）
            dirty |= ensureBuiltin(POS_STAFF, "普通员工", "role_user", SCOPE_SELF_DEPT,
                "默认职务：数据范围限于本科室及其所属社区");
            // 删除 role_dept_head（科主任角色，与「普通用户」权限相同）。
            //   这两个职务现直接挂 role_user。注意：仅改这里的字面量**不够** ，
            //   ensureBuiltin 不覆盖已存在的职务，存量 positions.json 仍指向已删除的角色，
            //   于是「按职务带出角色」会带出一个不存在的角色。故下面有一次性迁移。
            dirty |= ensureBuiltin(POS_DEPT_HEAD, "科主任", "role_user", SCOPE_SELF_DEPT,
                "科室负责人：范围同本科室");
            dirty |= ensureBuiltin(POS_COMM_HEAD, "社区主任", "role_user", SCOPE_SELF_COMMUNITY,
                "社区负责人：可见本社区全部科室");
            dirty |= ensureBuiltin(POS_MED_ADMIN, "医管中心", "role_med_admin", SCOPE_ALL,
                "医管中心：不限社区与科室");

            // 一次性迁移：把指向已删除角色 role_dept_head 的**存量**职务改挂 role_user
            dirty |= migrateRemovedDeptHeadRole(path);

            if (dirty) {
                savePositions();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 一次性迁移（幂等：由 data/.positions-dept-head-removed-v1 标记保证只执行一次）。
     *
     * <p>背景：内置角色 {@code role_dept_head} 已删除（与「普通用户」权限相同）。
     * 但 {@link #ensureBuiltin} **不覆盖**已存在的职务，所以存量 {@code positions.json} 里
     * 「科主任」「社区主任」的 {@code defaultRoleId} 仍指向那个**已不存在的角色** ，
     * 「按职务带出角色」会带出一个查不到的角色，用户落进兜底分支（只剩 {@code dashboard.view}）。
     *
     * <p>这正是本项目反复出现的那个类：改了代码默认值，却没管存量数据。
     * 只把字母量改掉、指望 {@code ensureBuiltin} 生效是错的，它只在职务**缺失**时才建。
     *
     * @return 是否有实际变更（需要回写 positions.json）
     */
    private boolean migrateRemovedDeptHeadRole(Path positionsPath) {
        Path dataDir = positionsPath.getParent();
        if (dataDir == null) return false;
        Path marker = dataDir.resolve(".positions-dept-head-removed-v1");
        if (Files.exists(marker)) return false;

        boolean changed = false;
        for (Position p : positions.values()) {
            if ("role_dept_head".equals(p.defaultRoleId)) {
                p.defaultRoleId = "role_user";
                changed = true;
                log.warn("[迁移] 职务 {} 的默认角色由已删除的 role_dept_head 改挂 role_user", p.code);
            }
        }
        try {
            java.nio.file.Files.writeString(marker,
                "dept-head-removed-v1 appliedAt=" + java.time.Instant.now() + System.lineSeparator(),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("写入职务迁移标记失败，下次启动将重试: {}", marker.toAbsolutePath(), e);
        }
        return changed;
    }

    /** 仅在不存在时创建，不覆盖管理员后续的调整 */
    private boolean ensureBuiltin(String code, String name, String defaultRoleId,
                                  String scopeRule, String remark) {        if (positions.containsKey(code)) return false;
        Position p = new Position();
        p.code = code;
        p.name = name;
        p.defaultRoleId = defaultRoleId;
        p.scopeRule = scopeRule;
        p.builtin = true;
        p.remark = remark;
        positions.put(code, p);
        log.info("[初始化] 补建内置职务: {} ({})", name, code);
        return true;
    }

    private void savePositions() {
        Path path = positionsFile();
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(path.toFile(), new ArrayList<>(positions.values()));
        } catch (Exception e) {
            log.error("保存职务字典失败: {}", path.toAbsolutePath(), e);
        }
    }

    // ==================== 查询 API ====================

    public List<Position> listAll() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(positions.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Position get(String code) {
        if (code == null) return null;
        lock.readLock().lock();
        try {
            return positions.get(code);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean exists(String code) {
        return get(code) != null;
    }

    /** 职务默认角色；职务不存在时回退到普通用户 */
    public String defaultRoleId(String code) {
        Position p = get(code);
        return p != null && p.defaultRoleId != null ? p.defaultRoleId : "role_user";
    }

    /** 职务的范围规则；职务不存在时按最保守的「本科室」处理 */
    public String scopeRule(String code) {
        Position p = get(code);
        return p != null && p.scopeRule != null ? p.scopeRule : SCOPE_SELF_DEPT;
    }

    public static String scopeRuleLabel(String rule) {
        if (SCOPE_ALL.equals(rule)) return "不限";
        if (SCOPE_SELF_COMMUNITY.equals(rule)) return "随职务 · 本社区";
        if (SCOPE_SELF_DEPT.equals(rule)) return "随职务 · 本科室";
        return rule;
    }

    /**
     * 职务字典（用于持久化与接口返回）
     */
    public static class Position {
        public String code;
        public String name;
        public String defaultRoleId;
        public String scopeRule;
        public boolean builtin;
        public String remark;

        public Position() {}

        public String getCode() { return code; }
        public String getName() { return name; }
        public String getDefaultRoleId() { return defaultRoleId; }
        public String getScopeRule() { return scopeRule; }
        public boolean isBuiltin() { return builtin; }
        public String getRemark() { return remark; }

        public void setCode(String code) { this.code = code; }
        public void setName(String name) { this.name = name; }
        public void setDefaultRoleId(String defaultRoleId) { this.defaultRoleId = defaultRoleId; }
        public void setScopeRule(String scopeRule) { this.scopeRule = scopeRule; }
        public void setBuiltin(boolean builtin) { this.builtin = builtin; }
        public void setRemark(String remark) { this.remark = remark; }

        /** 可选的范围规则清单，供前端下拉 */
    }
}
