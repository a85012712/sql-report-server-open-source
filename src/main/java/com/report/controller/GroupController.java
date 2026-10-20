package com.report.controller;

import com.report.service.GroupPermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin")
public class GroupController {


    private final GroupPermissionService groupPermissionService;

    public GroupController(GroupPermissionService groupPermissionService) {
        this.groupPermissionService = groupPermissionService;
    }

    @GetMapping("/groups/config")
    @PreAuthorize("@perm.has('groups.manage')")
    public Map<String, Object> getGroupConfig() {
        return groupPermissionService.getFullConfig();
    }

    /**
     * 报表分组字典（登录即可访问）：各处「报表分组」勾选框与名称映射的统一选项来源。
     *
     * <p>与 {@code /api/admin/communities/enabled}、{@code /api/admin/departments/enabled}
     * 同属「字典端点」这一层：字典是「读来填下拉」的，不该要求 manage 权限。
     *
     * <p>为什么必须有这个端点：{@code PUT /api/admin/users/{u}/groups}
     * （设置某工号的报表分组）只要求 {@code users.manage}，而 {@code GET /api/admin/groups}
     * 要求 {@code groups.manage}，一个「只有 {@code users.manage}」的自定义角色
     * 能写却不能读：编辑弹窗里勾选框全空，管理员一保存就把该工号原有的
     * 报表分组静默清空（又一处「覆盖式保存」）。
     *
     * <p>只返回 {@code id / name / sort}，不带 {@code assignments} / {@code hiddenReports}
     * / {@code customNames} 等管理侧配置（该端点登录即可访问，不吐管理信息）。
     */
    @GetMapping("/groups/options")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> listGroupOptions() {
        return groupPermissionService.getAllGroups();
    }

    /** 分组管理列表：含管理侧配置，供「分组管理」页使用。 */
    @GetMapping("/groups")
    @PreAuthorize("@perm.has('groups.manage')")
    public List<Map<String, Object>> listGroups() {
        return groupPermissionService.getAllGroups();
    }

    @PostMapping("/groups")
    @PreAuthorize("@perm.has('groups.manage')")
    public ResponseEntity<?> createGroup(@RequestBody Map<String, Object> body) {
        String id = (String) body.get("id");
        String name = (String) body.get("name");
        int sort = body.get("sort") instanceof Number ? ((Number) body.get("sort")).intValue() : 1;

        Optional<String> idError = BaseValidationUtil.validateGroupId(id);
        if (idError.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", idError.get()));
        }
        Optional<String> nameError = BaseValidationUtil.validateGroupName(name);
        if (nameError.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", nameError.get()));
        }

        try {
            groupPermissionService.createGroup(id, name, sort);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/groups/{id}")
    @PreAuthorize("@perm.has('groups.manage')")
    public ResponseEntity<?> updateGroup(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            String name = (String) body.get("name");
            Integer sort = body.get("sort") instanceof Number ? ((Number) body.get("sort")).intValue() : null;
            groupPermissionService.updateGroup(id, name, sort);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/groups/{id}")
    @PreAuthorize("@perm.has('groups.manage')")
    public ResponseEntity<?> deleteGroup(@PathVariable String id) {
        try {
            groupPermissionService.deleteGroup(id);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/groups/assignment/{reportId}")
    @PreAuthorize("@perm.has('groups.manage')")
    public ResponseEntity<?> setReportGroup(@PathVariable String reportId, @RequestBody Map<String, String> body) {
        try {
            groupPermissionService.setReportGroup(reportId, body.get("groupId"));
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/reports/{reportId}/rename")
    @PreAuthorize("@perm.has('groups.manage')")
    public ResponseEntity<?> renameReport(@PathVariable String reportId, @RequestBody Map<String, String> body) {
        String newName = body.get("name");
        Optional<String> nameError = BaseValidationUtil.validateNotEmpty(newName, "名称");
        if (nameError.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", nameError.get()));
        }
        if (newName.trim().length() > 200) {
            return ResponseEntity.badRequest().body(Map.of("error", "名称过长，最多200个字符"));
        }
        groupPermissionService.setCustomName(reportId, newName.trim());
        return ResponseEntity.ok(Map.of("status", "ok", "name", newName.trim()));
    }

}
