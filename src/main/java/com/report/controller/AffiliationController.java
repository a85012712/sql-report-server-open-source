package com.report.controller;

import com.report.service.AffiliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 工号归属管理接口。
 *
 * <pre>
 *   GET  /api/admin/affiliations/dictionaries      全部字典 + 权限点清单（前端一次拉取）
 *   GET  /api/admin/affiliations                   归属列表（含派生结果）
 *   GET  /api/admin/affiliations/{username}        单个工号的归属详情
 *   PUT  /api/admin/affiliations/{username}        保存归属
 * </pre>
 *
 * <p>权限点沿用 {@code users.manage}（与用户管理同一权限边界）。
 *
 * <p>安全约定：保存接口只接收源字段
 * （科室 / 职务 / 角色 / 是否覆盖 + 覆盖时的社区科室清单），
 * 数据范围一律由服务端重新派生，不采信前端算出的结果。
 */
@RestController
@RequestMapping("/api/admin/affiliations")
public class AffiliationController {

    private static final Logger log = LoggerFactory.getLogger(AffiliationController.class);

    private final AffiliationService affiliationService;

    public AffiliationController(AffiliationService affiliationService) {
        this.affiliationService = affiliationService;
    }

    /** 字典与权限点清单 */
    @GetMapping("/dictionaries")
    @PreAuthorize("@perm.has('users.manage')")
    public ResponseEntity<?> dictionaries() {
        return ResponseEntity.ok(affiliationService.dictionaries());
    }

    /** 归属列表（含派生结果） */
    @GetMapping
    @PreAuthorize("@perm.has('users.manage')")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(affiliationService.list());
    }

    /** 单个工号归属详情 */
    @GetMapping("/{username}")
    @PreAuthorize("@perm.has('users.manage')")
    public ResponseEntity<?> detail(@PathVariable String username) {
        try {
            return ResponseEntity.ok(affiliationService.detail(username));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** 保存归属 */
    @PutMapping("/{username}")
    @PreAuthorize("@perm.has('users.manage')")
    public ResponseEntity<?> save(@PathVariable String username,
                                  @RequestBody Map<String, Object> body,
                                  @AuthenticationPrincipal UserDetails user) {
        String operator = user != null ? user.getUsername() : "unknown";
        try {
            AffiliationService.Derived d = affiliationService.save(
                operator,
                username,
                str(body, "department"),
                str(body, "position"),
                str(body, "roleId"),
                bool(body, "scopeOverride"),
                strList(body, "communities"),
                strList(body, "departments"));

            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("username", username);
            ok.put("derived", d);
            return ResponseEntity.ok(ok);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("保存工号归属失败: username={}", username, e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "保存失败：" + e.getMessage());
        }
    }

    // ==================== 入参解析 ====================

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isBlank() ? null : s.trim();
    }

    private static Boolean bool(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return Boolean.FALSE;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (!(v instanceof List<?> raw)) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            if (o == null) continue;
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message);
        return ResponseEntity.status(status).body(m);
    }
}
