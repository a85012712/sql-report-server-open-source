package com.report.controller;

import com.report.service.DepartmentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 科室字典管理（departments.manage 权限），含从 HIS 一键导入。
 */
@RestController
@RequestMapping("/api/admin/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    /** 全部科室（含停用） */
    @GetMapping
    @PreAuthorize("@perm.has('departments.manage')")
    public List<DepartmentService.Department> list() {
        return departmentService.listAll();
    }

    /**
     * 启用中的科室（登录即可访问）：**各处"科室"下拉与科室名映射的统一选项来源**。
     *
     * <p>与 {@link CommunityController#enabled()} 对齐，那个方法早就确立了这条约定
     * （注释原文："各处'社区'下拉的统一选项来源（科室管理/用户数据权限等），
     * 避免要求 communities.manage 权限"），但科室一直缺这个端点，
     * 导致下列调用方只能去调需要 {@code departments.manage} 的管理接口 {@code GET /}：
     *
     * <ul>
     *   <li>人员管理页（只需 {@code personnel.manage}），{@code role_dept_head}
     *       （科主任）只有该权限，调 {@code GET /} 会 403，科室名映射为空
     *       → 科室列只显示编码、按科室名搜索失效、**人员编辑弹窗的科室下拉是空的**</li>
     *   <li>用户管理「新增用户」弹窗（只需 {@code users.manage}）</li>
     *   <li>用户管理「范围」弹窗（只需 {@code users.manage}）</li>
     * </ul>
     *
     * <p>只返回下拉所需的最小字段，不带 contact / phone，避免把管理侧信息
     * 暴露给任何已登录用户。
     */
    @GetMapping("/enabled")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, String>> enabled() {
        List<Map<String, String>> out = new java.util.ArrayList<>();
        // 「只取启用科室」这条谓词归 DepartmentService 所有，控制器不再自己写一遍
        for (DepartmentService.Department d : departmentService.listEnabled()) {
            Map<String, String> m = new java.util.LinkedHashMap<>();
            m.put("code", d.code);
            m.put("name", d.name);
            m.put("community", d.community);
            out.add(m);
        }
        return out;
    }

    @PostMapping
    @PreAuthorize("@perm.has('departments.manage')")
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        try {
            DepartmentService.Department d = departmentService.create(
                body.get("code"), body.get("name"), body.get("category"),
                body.get("community"), body.get("contact"), body.get("phone"));
            return ResponseEntity.ok(Map.of("status", "ok", "department", d));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "创建科室失败"));
        }
    }

    @PutMapping("/{code}")
    @PreAuthorize("@perm.has('departments.manage')")
    public ResponseEntity<?> update(@PathVariable String code, @RequestBody Map<String, Object> body) {
        try {
            String name = str(body.get("name"));
            String category = str(body.get("category"));
            String community = str(body.get("community"));
            String contact = str(body.get("contact"));
            String phone = str(body.get("phone"));
            Boolean enabled = body.get("enabled") instanceof Boolean ? (Boolean) body.get("enabled") : null;
            DepartmentService.Department d = departmentService.update(
                code, name, category, community, contact, phone, enabled);
            return ResponseEntity.ok(Map.of("status", "ok", "department", d));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "更新科室失败"));
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }

    /** 从 HIS 科室表一键导入（幂等：更新名称/联系人/电话，不动本地社区与启停） */
    @PostMapping("/import-his")
    @PreAuthorize("@perm.has('departments.manage')")
    public ResponseEntity<?> importFromHis() {
        try {
            int[] r = departmentService.importFromHis();
            return ResponseEntity.ok(Map.of(
                "status", "ok",
                "message", "导入完成：新增 " + r[0] + " 个，更新 " + r[1] + " 个"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "从 HIS 导入失败: " + e.getMessage()));
        }
    }
}
