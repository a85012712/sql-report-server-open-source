package com.report.controller;

import com.report.model.DataSourceInfo;
import com.report.service.DataSourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/datasources")
@PreAuthorize("@perm.has('datasource.manage')")
public class DataSourceController {


    private final DataSourceService dss;

    public DataSourceController(DataSourceService dss) {
        this.dss = dss;
    }

    @GetMapping
    public ResponseEntity<List<DataSourceInfo>> getAll() {
        return ResponseEntity.ok(dss.getAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable String id) {
        DataSourceInfo ds = dss.getById(id);
        if (ds == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ds);
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody DataSourceInfo ds) {
        if (ds.getName() == null || ds.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "名称不能为空"));
        }
        try {
            return ResponseEntity.ok(dss.add(ds));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody DataSourceInfo ds) {
        try {
            return ResponseEntity.ok(dss.update(id, ds));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            boolean deleted = dss.delete(id);
            if (deleted) {
                return ResponseEntity.ok(Map.of("message", "删除成功"));
            }
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String id) {
        try {
            return ResponseEntity.ok(dss.testConnection(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testNew(@RequestBody DataSourceInfo ds) {
        return ResponseEntity.ok(dss.testConn(ds));
    }

    @PostMapping("/{id}/set-default")
    public ResponseEntity<?> setDefault(@PathVariable String id) {
        try {
            dss.setDefault(id);
            return ResponseEntity.ok(Map.of("message", "已设置默认"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/default-id")
    public ResponseEntity<Map<String, String>> getDefaultId() {
        return ResponseEntity.ok(Map.of("id", dss.getDefaultId()));
    }
}
