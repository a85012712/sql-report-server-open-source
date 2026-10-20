package com.report.controller;

import com.report.service.CommunityService;
import com.report.service.RolePermissionService;
import com.report.service.UserManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserManagementService userManagementService;
    private final RolePermissionService rolePermissionService;
    private final CommunityService communityService;
    private final com.report.service.PasswordResetEffects passwordResetEffects;

    public UserController(UserManagementService userManagementService,
                          RolePermissionService rolePermissionService,
                          CommunityService communityService,
                          com.report.service.PasswordResetEffects passwordResetEffects) {
        this.userManagementService = userManagementService;
        this.rolePermissionService = rolePermissionService;
        this.communityService = communityService;
        this.passwordResetEffects = passwordResetEffects;
    }

    @GetMapping("/current-user")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> currentUser(@AuthenticationPrincipal UserDetails user) {
        boolean isAdmin = user.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", user.getUsername());
        m.put("name", userManagementService.getUserName(user.getUsername()));
        m.put("role", isAdmin ? "ADMIN" : "USER");
        m.put("mustChangePassword", userManagementService.mustChangePassword(user.getUsername()));
        // screenEnabled = 工号开关的“原始状态”（用户管理页开关的回显依据；默认开启）
        // screenAccess  = 双通道合并后的“最终生效结果”（超管 ‖ 角色 screen.view ‖ 工号开关）
        // 缺陷 M-1：此前把最终结果当成原始状态返回，导致前端对未开通开关的账号也显示“已开通”
        m.put("screenEnabled", userManagementService.hasScreenPermission(user.getUsername()));
        m.put("screenAccess", rolePermissionService.hasScreenPermission(user.getUsername()));
        // 大屏号 + 实际可见社区范围。screenNo 统一返回字符串（未配置 = 空串），
        // 与 /api/admin/users 的字段语义保持一致，前端无需区分 null / 空串
        String screenNo = userManagementService.getScreenNo(user.getUsername());
        m.put("screenNo", screenNo == null ? "" : screenNo);
        m.put("screenScope", userManagementService.describeScreenCommunities(user.getUsername()));
        java.util.Set<String> perms = rolePermissionService.permissionsOf(user.getUsername());
        m.put("permissions", new java.util.ArrayList<>(perms));
        List<String> allowed = userManagementService.getAllowedCommunities(user.getUsername());
        m.put("allowedCommunities", allowed != null ? allowed : java.util.List.of()); // 空列表=不限
        com.report.service.RolePermissionService.Role role =
            rolePermissionService.getById(rolePermissionService.resolveRoleId(user.getUsername()));
        m.put("roleName", role != null ? role.name : (isAdmin ? "管理员" : "普通用户"));
        return m;
    }

    @GetMapping("/users")
    @PreAuthorize("@perm.has('users.manage')")
    public List<Map<String, Object>> listUsers() {
        return userManagementService.listUsers();
    }

    @PutMapping("/profile/password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> changeOwnPassword(@RequestBody Map<String, String> body,
                                                @AuthenticationPrincipal UserDetails currentUser) {
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("password");
        if (oldPassword == null || oldPassword.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入当前密码"));
        }
        Optional<String> pwdError = BaseValidationUtil.validatePassword(newPassword);
        if (pwdError.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", pwdError.get()));
        }
        try {
            userManagementService.changeOwnPassword(currentUser.getUsername(), oldPassword, newPassword);
            log.info("[AUDIT] action=changeOwnPassword, username={}", currentUser.getUsername());
            return ResponseEntity.ok(Map.of("status", "ok", "message", "密码修改成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("修改密码失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "修改密码失败"));
        }
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/users")
    @PreAuthorize("@perm.has('users.manage')")
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> body,
                                        @AuthenticationPrincipal UserDetails currentUser) {
        String username = (String) body.get("username");
        String name = (String) body.get("name");
        String role = body.getOrDefault("role", "USER").toString();

        // 获取分组列表
        List<String> groups = null;
        Object groupsObj = body.get("groups");
        if (groupsObj instanceof List) {
            groups = ((List<?>) groupsObj).stream()
                .map(Object::toString)
                .collect(Collectors.toList());
        }

        Optional<String> usernameError = BaseValidationUtil.validateUsername(username);
        if (usernameError.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", usernameError.get()));
        }
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "姓名不能为空"));
        }
        Optional<String> roleError = BaseValidationUtil.validateRole(role);
        if (roleError.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", roleError.get()));
        }
        if (userManagementService.userExists(username)) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名已存在"));
        }

        // 新增：角色ID + 可查看社区/科室（数据权限）
        String roleId = body.get("roleId") != null ? body.get("roleId").toString().trim() : null;
        List<String> allowedCommunities = toStringList(body.get("allowedCommunities"));
        List<String> allowedDepartments = toStringList(body.get("allowedDepartments"));

        try {
            String tempPassword = userManagementService.createUser(
                username, name.trim(), role, roleId, allowedCommunities, allowedDepartments);
            if (groups != null && !groups.isEmpty()) {
                userManagementService.setUserGroups(username, groups);
            }
            log.info("[AUDIT] action=adminCreateUser, operator={}, newUsername={}, name={}, role={}, roleId={}, communities={}, departments={}, groups={}",
                currentUser.getUsername(), username, name, role, roleId, allowedCommunities, allowedDepartments, groups);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "ok");
            resp.put("message", "用户创建成功。初始密码 " + tempPassword + "，首次登录强制修改");
            resp.put("tempPassword", tempPassword);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("创建用户失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "创建用户失败，请稍后再试"));
        }
    }

    /**
     * 管理员为某工号设置口令。
     *
     * <p>{@code forceChange}：缺省即 true，管理员不该长期持有用户的最终口令，
     * 用户首次登录必须自己改（与重置弹窗的承诺一致）。
     * 只有明确传 {@code false} 才设成「可直接使用的正式口令」，
     * 目前唯一的合法使用者是验证套件的账号初始化（它们本来就打算传 false，
     * 只是过去靠两参重载的隐含默认值蹭到了，没写在请求里）。
     */
    @PutMapping("/users/{username}/password")
    @PreAuthorize("@perm.has('users.manage')")
    public ResponseEntity<?> changePassword(@PathVariable String username,
                                            @RequestBody Map<String, Object> body,
                                            @AuthenticationPrincipal UserDetails currentUser,
                                            jakarta.servlet.http.HttpServletRequest request) {
        Object rawPassword = body.get("password");
        String newPassword = rawPassword instanceof String ? (String) rawPassword : null;
        Object rawForce = body.get("forceChange");
        boolean forceChange = rawForce == null || Boolean.parseBoolean(String.valueOf(rawForce));
        Optional<String> pwdError = BaseValidationUtil.validatePassword(newPassword);
        if (pwdError.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", pwdError.get()));
        }
        if (!userManagementService.userExists(username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在"));
        }

        try {
            userManagementService.changePassword(username, newPassword, forceChange);
            passwordResetEffects.afterPasswordReset(username, request);
            log.info("[AUDIT] action=adminChangePassword, operator={}, targetUser={}, forceChange={}",
                currentUser.getUsername(), username, forceChange);
            return ResponseEntity.ok(Map.of("status", "ok",
                "message", forceChange ? "密码已设置，用户下次登录须修改" : "密码已设置"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("修改密码失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "修改密码失败，请稍后再试"));
        }
    }

    @DeleteMapping("/users/{username}")
    @PreAuthorize("@perm.has('users.manage')")
    public ResponseEntity<?> deleteUser(@PathVariable String username,
                                        @AuthenticationPrincipal UserDetails currentUser) {
        if (username.equals(currentUser.getUsername())) {
            return ResponseEntity.badRequest().body(Map.of("error", "不能删除当前登录的用户"));
        }
        if (!userManagementService.userExists(username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在"));
        }

        try {
            userManagementService.deleteUser(username);
            log.info("[AUDIT] action=adminDeleteUser, operator={}, targetUser={}",
                currentUser.getUsername(), username);
            return ResponseEntity.ok(Map.of("status", "ok", "message", "用户删除成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("删除用户失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "删除用户失败，请稍后再试"));
        }
    }

    @PutMapping("/users/{username}/role")
    @PreAuthorize("@perm.has('users.manage')")
    public ResponseEntity<?> changeRole(@PathVariable String username,
                                        @RequestBody Map<String, String> body,
                                        @AuthenticationPrincipal UserDetails currentUser,
                                        jakarta.servlet.http.HttpServletRequest request) {
        if (username.equals(currentUser.getUsername())) {
            return ResponseEntity.badRequest().body(Map.of("error", "不能修改当前登录用户自己的角色"));
        }
        String role = body.get("role");
        Optional<String> roleError = BaseValidationUtil.validateRole(role);
        if (roleError.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", roleError.get()));
        }
        if (!userManagementService.userExists(username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在"));
        }

        try {
            String newPassword = body.get("password");
            if (newPassword != null && !newPassword.isEmpty()) {
                Optional<String> pwdError = BaseValidationUtil.validatePassword(newPassword);
                if (pwdError.isPresent()) {
                    return ResponseEntity.badRequest().body(Map.of("error", pwdError.get()));
                }
                userManagementService.changePassword(username, newPassword);
                // 这条入口也能顺带设口令，收尾动作必须与 /password 一致，
                // 否则「从角色弹窗里改密」就绕过了解锁与吊销会话
                passwordResetEffects.afterPasswordReset(username, request);
            }

            userManagementService.changeRole(username, role);
            log.info("[AUDIT] action=adminChangeRole, operator={}, targetUser={}, newRole={}",
                currentUser.getUsername(), username, role);
            return ResponseEntity.ok(Map.of("status", "ok", "message", "角色修改成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("修改角色失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "修改角色失败，请稍后再试"));
        }
    }

    @PutMapping("/users/{username}/groups")
    @PreAuthorize("@perm.has('users.manage')")
    public ResponseEntity<?> setUserGroups(@PathVariable String username, @RequestBody Map<String, Object> body) {
        if (!userManagementService.userExists(username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在"));
        }
        try {
            Object groupsObj = body.get("groups");
            List<String> groups = null;
            if (groupsObj instanceof List) {
                groups = ((List<?>) groupsObj).stream()
                    .map(Object::toString).collect(Collectors.toList());
            }
            userManagementService.setUserGroups(username, groups);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 设置可视化平台（数据大屏）权限
     */
    @PutMapping("/users/{username}/screen-permission")
    @PreAuthorize("@perm.has('users.manage')")
    public ResponseEntity<?> setScreenPermission(@PathVariable String username,
                                                 @RequestBody Map<String, Object> body,
                                                 @AuthenticationPrincipal UserDetails currentUser) {
        if (!userManagementService.userExists(username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在"));
        }
        Object enabledObj = body.get("enabled");
        if (!(enabledObj instanceof Boolean)) {
            return ResponseEntity.badRequest().body(Map.of("error", "参数enabled必须为true/false"));
        }
        try {
            userManagementService.setScreenPermission(username, (Boolean) enabledObj);
            log.info("[AUDIT] action=adminSetScreenPermission, operator={}, targetUser={}, enabled={}",
                currentUser.getUsername(), username, enabledObj);
            return ResponseEntity.ok(Map.of("status", "ok",
                "screenEnabled", userManagementService.hasScreenPermission(username)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("设置大屏权限失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "设置大屏权限失败"));
        }
    }


    /**
     * 设置工号的大屏配置（开关 + 大屏号）。
     *
     * <p>请求体（两个字段均可选，至少提供一个）：
     * <pre>
     * { "enabled": true,   "screenNo": "ALL" }   // 全部社区
     * { "enabled": true,   "screenNo": "D"   }   // 仅 D 社区
     * { "enabled": true,   "screenNo": ""    }   // 恢复默认（跟随该账号的「可查看社区」）
     * { "enabled": false                     }   // 仅关闭大屏访问
     * </pre>
     *
     * <p>大屏号语义见 {@link UserManagementService#getScreenCommunities(String)}。
     * 仅 admin（{@code users.manage} 权限点）可调用。
     */
    @PutMapping("/users/{username}/screen")
    @PreAuthorize("@perm.has('users.manage')")
    public ResponseEntity<?> setScreenSetting(@PathVariable String username,
                                              @RequestBody Map<String, Object> body,
                                              @AuthenticationPrincipal UserDetails currentUser) {
        if (!userManagementService.userExists(username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在"));
        }
        boolean enabledProvided = body.containsKey("enabled");
        boolean screenNoProvided = body.containsKey("screenNo");
        if (!enabledProvided && !screenNoProvided) {
            return ResponseEntity.badRequest().body(Map.of("error", "请至少提供 enabled 或 screenNo 之一"));
        }

        // 先校验大屏号取值，避免出现「开关已改、大屏号非法」的半成品状态
        String normalizedScreenNo = null;
        if (screenNoProvided) {
            Object raw = body.get("screenNo");
            normalizedScreenNo = UserManagementService.normalizeScreenNo(raw == null ? null : raw.toString());
            if (normalizedScreenNo != null
                && !UserManagementService.SCREEN_NO_ALL.equals(normalizedScreenNo)
                && communityService.getByCode(normalizedScreenNo) == null) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "大屏号无效：社区不存在 " + normalizedScreenNo));
            }
        }

        try {
            if (enabledProvided) {
                Object enabledObj = body.get("enabled");
                if (!(enabledObj instanceof Boolean)) {
                    return ResponseEntity.badRequest().body(Map.of("error", "参数enabled必须为true/false"));
                }
                userManagementService.setScreenPermission(username, (Boolean) enabledObj);
            }
            if (screenNoProvided) {
                userManagementService.setScreenNo(username, normalizedScreenNo);
            }
            log.info("[AUDIT] action=adminSetScreenSetting, operator={}, targetUser={}, enabledProvided={}, screenNo={}",
                currentUser.getUsername(), username, enabledProvided,
                userManagementService.getScreenNo(username));

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "ok");
            resp.put("screenEnabled", userManagementService.hasScreenPermission(username));
            resp.put("screenNo", userManagementService.getScreenNo(username));
            resp.put("screenScope", userManagementService.describeScreenCommunities(username));
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("设置大屏配置失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "设置大屏配置失败"));
        }
    }

    /** 大屏号可选项（社区字典，供前端下拉；代码 + 名称） */
    @GetMapping("/users/screen-no-options")
    @PreAuthorize("@perm.has('users.manage')")
    public List<Map<String, String>> screenNoOptions() {
        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (CommunityService.Community c : communityService.listAll()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("code", c.code);
            m.put("name", c.name + (c.enabled ? "" : "（已停用）"));
            out.add(m);
        }
        return out;
    }


    /** 下一个可用工号（4位递进补空缺），新建用户弹窗默认值 */
    @GetMapping("/users/next-id")
    @PreAuthorize("@perm.has('users.manage')")
    public Map<String, Object> nextId() {
        String next = userManagementService.getNextId();
        return Map.of("nextId", next != null ? next : "");
    }

    /**
     * 设置用户角色与数据权限（可查看社区/科室）。
     * 空列表 = 不限。
     */
    @PutMapping("/users/{username}/scope")
    @PreAuthorize("@perm.has('users.manage')")
    public ResponseEntity<?> setUserScope(@PathVariable String username,
                                          @RequestBody Map<String, Object> body,
                                          @AuthenticationPrincipal UserDetails currentUser) {
        try {
            String roleId = body.get("roleId") != null ? body.get("roleId").toString().trim() : null;
            List<String> communities = toStringList(body.get("allowedCommunities"));
            List<String> departments = toStringList(body.get("allowedDepartments"));
            userManagementService.setUserScope(currentUser.getUsername(), username, roleId, communities, departments);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("设置用户数据权限失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "设置用户数据权限失败"));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object obj) {
        List<String> out = new java.util.ArrayList<>();
        if (obj instanceof List) {
            for (Object o : (List<Object>) obj) out.add(o.toString());
        }
        return out;
    }
}
