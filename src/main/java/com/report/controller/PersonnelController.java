package com.report.controller;

import com.report.service.PersonnelService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 人员字典管理（personnel.manage 权限），含从 HIS 一键导入（源表见 app.his-import.personnel-sql）。
 */
@RestController
@RequestMapping("/api/admin/personnel")
public class PersonnelController {

    private final PersonnelService personnelService;

    public PersonnelController(PersonnelService personnelService) {
        this.personnelService = personnelService;
    }

    /** 全部人员（含停用） */
    @GetMapping
    @PreAuthorize("@perm.has('personnel.manage')")
    public List<PersonnelService.Person> list() {
        return personnelService.listAll();
    }

    /** 下一个可用人员工号（优先补空缺；新增弹窗默认值，可手改） */
    @GetMapping("/next-code")
    @PreAuthorize("@perm.has('personnel.manage')")
    public Map<String, String> nextCode() {
        return Map.of("nextCode", personnelService.getNextCode());
    }

    @PostMapping
    @PreAuthorize("@perm.has('personnel.manage')")
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        try {
            PersonnelService.Person d = personnelService.create(
                str(body.get("code")), str(body.get("name")), str(body.get("gender")),
                str(body.get("title")), str(body.get("department")), str(body.get("phone")));
            return ResponseEntity.ok(Map.of("status", "ok", "person", d));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "创建人员失败"));
        }
    }

    @PutMapping("/{code}")
    @PreAuthorize("@perm.has('personnel.manage')")
    public ResponseEntity<?> update(@PathVariable String code, @RequestBody Map<String, Object> body) {
        try {
            PersonnelService.Person d = personnelService.update(
                code, str(body.get("name")), str(body.get("gender")), str(body.get("title")),
                str(body.get("department")), str(body.get("phone")),
                body.get("enabled") instanceof Boolean ? (Boolean) body.get("enabled") : null);
            return ResponseEntity.ok(Map.of("status", "ok", "person", d));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "更新人员失败"));
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }

    /** 从 HIS 人员表一键导入（幂等；按科室编码匹配科室字典） */
    @PostMapping("/import-his")
    @PreAuthorize("@perm.has('personnel.manage')")
    public ResponseEntity<?> importFromHis() {
        try {
            int[] r = personnelService.importFromHis();
            return ResponseEntity.ok(Map.of(
                "status", "ok",
                "message", "导入完成：新增 " + r[0] + " 人，更新 " + r[1] + " 人"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "从 HIS 导入失败: " + e.getMessage()));
        }
    }
}
