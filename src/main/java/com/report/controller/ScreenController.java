package com.report.controller;

import com.report.service.RolePermissionService;
import com.report.service.ScreenRange;
import com.report.service.ScreenService;
import com.report.service.UserManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 可视化平台（数据大屏）：
 * - GET /screen           大屏页面（权限校验：无权限重定向回首页 /，再由前端按权限落点）
 * - GET /api/screen/data  大屏数据（默认区间走内存缓存，毫秒级；未预热的自定义区间
 *                         实时查一次并缓存，受并发与最大跨度约束）+ 社区数据权限过滤
 * - POST /api/screen/refresh 管理员手动立即刷新
 *
 * <p>可见社区范围：由工号上的「大屏号」决定，见
 * {@link UserManagementService#getScreenCommunities(String)}：
 * 大屏号 = ALL → 全部社区；= 社区代码 → 仅该社区；未配置 → 跟随「可查看社区」。
 * 大屏权限默认开启，管理员可在用户管理页关闭或调整大屏号。
 */
@Controller
public class ScreenController {

    private final ScreenService screenService;
    private final UserManagementService userManagementService;
    private final RolePermissionService rolePermissionService;

    public ScreenController(ScreenService screenService,
                            UserManagementService userManagementService,
                            RolePermissionService rolePermissionService) {
        this.screenService = screenService;
        this.userManagementService = userManagementService;
        this.rolePermissionService = rolePermissionService;
    }

    @GetMapping("/screen")
    public ModelAndView screenPage(@AuthenticationPrincipal UserDetails user) {
        if (user == null || !rolePermissionService.hasScreenPermission(user.getUsername())) {
            // 无权限直接访问该URL：回首页，由前端按该账号权限决定落到哪个菜单
            return new ModelAndView("redirect:/");
        }
        return new ModelAndView("screen");
    }

    /**
     * 大屏数据。
     *
     * <p>区间：{@code range} 为预设名（today/7d/30d/thisMonth/lastMonth，缺省 30d）；
     * 传 {@code start}+{@code end}（yyyy-MM-dd）则为自定义区间，此时 range 被忽略。
     * 区间非法直接返回 400 并说明原因，静默回退到默认区间会让用户以为自己看到的
     * 就是所选区间，那是最糟糕的失败方式。
     */
    @GetMapping("/api/screen/data")
    @ResponseBody
    public ResponseEntity<?> screenData(@AuthenticationPrincipal UserDetails user,
                                        @RequestParam(required = false) String community,
                                        @RequestParam(required = false) String range,
                                        @RequestParam(required = false) String start,
                                        @RequestParam(required = false) String end) {
        String username = user != null ? user.getUsername() : null;
        if (username == null || !rolePermissionService.hasScreenPermission(username)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "无可视化平台访问权限", "code", "NO_SCREEN_PERMISSION"));
        }
        ScreenRange screenRange;
        try {
            screenRange = ScreenRange.resolve(range, start, end, LocalDate.now());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage(), "code", "BAD_RANGE"));
        }
        // 大屏可见社区：null=不限（全部）；空列表=无可见社区（大屏不展示任何社区数据）。
        // 解析规则见 UserManagementService#getScreenCommunities（大屏号优先，其次跟随可查看社区）
        List<String> allowed = userManagementService.getScreenCommunities(username);
        return ResponseEntity.ok(Map.of("status", "ok",
            "data", screenService.buildScreenData(community, allowed, screenRange)));
    }

    @PostMapping("/api/screen/refresh")
    @ResponseBody
    public ResponseEntity<?> refresh(@AuthenticationPrincipal UserDetails user) {
        boolean isAdmin = user != null && (rolePermissionService.isSuperAdmin(user.getUsername())
            || rolePermissionService.has(user.getUsername(), RolePermissionService.P_REPORT_MANAGE));
        if (!isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "仅管理员可手动刷新"));
        }
        try {
            screenService.refreshNow();
            return ResponseEntity.ok(Map.of("status", "ok", "message", "刷新完成"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "刷新失败: " + e.getMessage()));
        }
    }
}
