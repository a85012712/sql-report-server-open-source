package com.report.controller;

import com.report.service.PositionService;
import com.report.service.RolePermissionService;
import com.report.service.UserManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色管理（roles.manage 权限）：角色 CRUD + 权限点清单。
 *
 * <p>角色是**唯一事实来源**：用户管理的角色下拉、归属管理的角色下拉、
 * 角色管理页的角色列表，三者读的都是同一份角色数据，
 * 不再各自过滤或硬编码，避免出现「一个页面有、另一个页面没有」的不一致。
 *
 * <p>同一份数据分**两个视图**（与分组 {@code /groups/options} vs {@code /groups} 同构）：
 * <ul>
 *   <li>{@code GET /api/admin/roles}，管理视图，含 {@code permissions} 与使用情况，
 *       要求 {@code roles.manage}，供「角色管理」页使用；</li>
 *   <li>{@code GET /api/admin/roles/options}，字典视图，只有 {@code id/name/builtin}，
 *       登录即可，供各处的角色下拉使用。</li>
 * </ul>
 * 早先只统一到管理视图，结果是「只有 users.manage 的角色进得了用户管理页、
 * 却拿不到角色下拉的数据」（403 静默降级为空）， 统一数据源**不等于**统一权限要求。
 */
@RestController
@RequestMapping("/api/admin/roles")
public class RoleController {

    private final RolePermissionService rolePermissionService;
    private final UserManagementService userManagementService;
    private final PositionService positionService;

    public RoleController(RolePermissionService rolePermissionService,
                          UserManagementService userManagementService,
                          PositionService positionService) {
        this.rolePermissionService = rolePermissionService;
        this.userManagementService = userManagementService;
        this.positionService = positionService;
    }

    /** 权限点清单（角色编辑弹窗勾选用）。
     *  返回 {@code [{id, label, group}]}，数组顺序即显示顺序，
     *  分组名由后端声明（前端按首现顺序聚合，不硬编码清单），
     *  新增权限点不会出现前端漏项导致「打开配置弹窗看不到勾选框、保存时静默删权限」。 */
    @GetMapping("/permissions")
    @PreAuthorize("@perm.has('roles.manage')")
    public List<Map<String, String>> permissions() {
        List<Map<String, String>> list = new ArrayList<>();
        for (String[] p : RolePermissionService.ALL_PERMISSIONS) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", p[0]);
            m.put("label", p[1]);
            m.put("group", p.length > 2 ? p[2] : "其它");
            list.add(m);
        }
        return list;
    }

    /**
     * 角色列表。
     *
     * <p>在角色本体之外附带**使用情况**（{@code usageCount} / {@code usedByPositions}），
     * 让「角色管理 → 用户管理 → 归属管理」的联动**看得见**：
     * 管理员在改/删角色前就能知道影响面，而不是等后端拒绝才知道有人在用。
     *
     * <p>使用情况是**算出来的**，不写进 {@code Role} 实体，否则会被一并落盘到
     * {@code data/roles.json}，形成冗余且会过期的数据。
     */
    @GetMapping
    @PreAuthorize("@perm.has('roles.manage')")
    public List<Map<String, Object>> list() {
        // 各角色被多少工号挂载
        Map<String, Integer> userCount = new LinkedHashMap<>();
        for (UserManagementService.UserRecord r : userManagementService.allRecords()) {
            String rid = userManagementService.resolveRoleId(r.username);
            if (rid != null && !rid.isBlank()) {
                userCount.merge(rid, 1, Integer::sum);
            }
        }
        // 各角色被哪些职务用作默认角色（职务 → 角色 的带出关系）
        Map<String, List<String>> usedByPositions = new LinkedHashMap<>();
        for (PositionService.Position p : positionService.listAll()) {
            if (p.defaultRoleId == null || p.defaultRoleId.isBlank()) continue;
            usedByPositions.computeIfAbsent(p.defaultRoleId, k -> new ArrayList<>()).add(p.name);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (RolePermissionService.Role role : rolePermissionService.listRoles()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", role.id);
            m.put("name", role.name);
            m.put("builtin", role.builtin);
            m.put("permissions", role.permissions);
            m.put("usageCount", userCount.getOrDefault(role.id, 0));
            m.put("usedByPositions", usedByPositions.getOrDefault(role.id, new ArrayList<>()));
            out.add(m);
        }
        return out;
    }

    /**
     * 角色字典：{@code [{id, name, builtin}]}，供各处角色下拉填充。
     *
     * <p>与 {@code /api/admin/groups/options}、{@code /api/admin/communities/enabled}、
     * {@code /api/admin/departments/enabled} 同属「字典端点」这一层：
     * 字典是「读来填下拉」的，不该要求 manage 权限。
     *
     * <p>为什么必须有这个端点：侧栏「用户管理」菜单声明的是
     * {@code data-perm="users.manage"}，即「有 users.manage 就能用这个页面」。
     * 但该页三处（用户列表的「角色」列、新增用户弹窗、修改归属弹窗）原先都在读
     * {@code GET /api/admin/roles}（要求 {@code roles.manage}），
     * 一个「只有 {@code users.manage}」的自定义角色进得来这个页面，三处却全部 403
     * 并静默降级为空数组：角色列一片空白、角色下拉框全空，于是根本建不了新用户，
     * 界面上还没有任何提示说明原因。菜单说「有 users.manage 就能用」，页面却暗地里还要
     * {@code roles.manage}，又是一处「同一件事按不同依据判断」。
     *
     * <p>只返回 {@code id / name / builtin}，不吐 {@code permissions} 与
     * {@code usageCount / usedByPositions} 等管理侧信息（本端点登录即可访问）。
     */
    @GetMapping("/options")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> options() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RolePermissionService.Role role : rolePermissionService.listRoles()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", role.id);
            m.put("name", role.name);
            m.put("builtin", role.builtin);
            out.add(m);
        }
        return out;
    }

    @PostMapping
    @PreAuthorize("@perm.has('roles.manage')")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        try {
            String name = body.get("name") != null ? body.get("name").toString().trim() : "";
            if (name.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "角色名称不能为空"));
            RolePermissionService.Role r = rolePermissionService.create(name, extractPerms(body));
            return ResponseEntity.ok(Map.of("status", "ok", "role", r));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "创建角色失败"));
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('roles.manage')")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body,
                                    @AuthenticationPrincipal UserDetails currentUser) {
        try {
            if (currentUser == null) {
                // 理论上到不了这里（@PreAuthorize 已要求已认证主体），到此即拒，
                // 不给 RolePermissionService#update 的「operator 为 null = 内部调用」留口子。
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "未识别到操作人"));
            }
            String name = body.get("name") != null ? body.get("name").toString().trim() : null;
            RolePermissionService.Role r = rolePermissionService.update(id, name, extractPerms(body),
                currentUser.getUsername());
            return ResponseEntity.ok(Map.of("status", "ok", "role", r));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "更新角色失败"));
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.has('roles.manage')")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            rolePermissionService.delete(id);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "删除角色失败"));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractPerms(Map<String, Object> body) {
        Object perms = body.get("permissions");
        List<String> out = new ArrayList<>();
        if (perms instanceof List) {
            for (Object p : (List<Object>) perms) out.add(p.toString());
        }
        return out;
    }
}
