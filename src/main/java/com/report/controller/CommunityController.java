package com.report.controller;

import com.report.service.CommunityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 社区字典管理（communities.manage 权限）。
 */
@RestController
@RequestMapping("/api/admin/communities")
public class CommunityController {

    private final CommunityService communityService;

    public CommunityController(CommunityService communityService) {
        this.communityService = communityService;
    }

    /** 全部社区（含停用，管理页用） */
    @GetMapping
    @PreAuthorize("@perm.has('communities.manage')")
    public List<CommunityService.Community> list() {
        return communityService.listAll();
    }

    /**
     * 启用中的社区（登录即可访问）：
     * 各处"社区"下拉的统一选项来源（科室管理/用户数据权限等），避免要求 communities.manage 权限。
     */
    @GetMapping("/enabled")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, String>> enabled() {
        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (CommunityService.Community c : communityService.listAll()) {
            if (c.enabled) {
                Map<String, String> m = new java.util.LinkedHashMap<>();
                m.put("code", c.code);
                m.put("name", c.name);
                out.add(m);
            }
        }
        return out;
    }

    @PostMapping
    @PreAuthorize("@perm.has('communities.manage')")
    public ResponseEntity<?> create(@RequestBody Map<String, String> body,
                                    @AuthenticationPrincipal UserDetails user) {
        try {
            CommunityService.Community c = communityService.create(body.get("code"), body.get("name"));
            return ResponseEntity.ok(Map.of("status", "ok", "community", c));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "创建社区失败"));
        }
    }

    @PutMapping("/{code}")
    @PreAuthorize("@perm.has('communities.manage')")
    public ResponseEntity<?> update(@PathVariable String code, @RequestBody Map<String, Object> body,
                                    @AuthenticationPrincipal UserDetails user) {
        try {
            String name = body.get("name") != null ? body.get("name").toString() : null;
            Boolean enabled = body.get("enabled") instanceof Boolean ? (Boolean) body.get("enabled") : null;
            CommunityService.Community c = communityService.update(code, name, enabled);
            return ResponseEntity.ok(Map.of("status", "ok", "community", c));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "更新社区失败"));
        }
    }
}
