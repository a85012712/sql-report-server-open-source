package com.report.controller;
import com.report.model.DataSourceInfo;
import com.report.service.DataSourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/admin/datasources")
@PreAuthorize("hasRole('ADMIN')")
public class DataSourceController {
    @Autowired private DataSourceService dss;
    @GetMapping public ResponseEntity<List<DataSourceInfo>> getAll(){return ResponseEntity.ok(dss.getAll());}
    @GetMapping("/{id}") public ResponseEntity<?> getById(@PathVariable String id){DataSourceInfo ds=dss.getById(id);return ds==null?ResponseEntity.notFound().build():ResponseEntity.ok(ds);}
    @PostMapping public ResponseEntity<?> add(@RequestBody DataSourceInfo ds){if(ds.getName()==null||ds.getName().trim().isEmpty())return ResponseEntity.badRequest().body(Map.of("error","名称不能为空"));try{return ResponseEntity.ok(dss.add(ds));}catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}}
    @PutMapping("/{id}") public ResponseEntity<?> update(@PathVariable String id,@RequestBody DataSourceInfo ds){try{return ResponseEntity.ok(dss.update(id,ds));}catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}}
    @DeleteMapping("/{id}") public ResponseEntity<?> delete(@PathVariable String id){try{boolean d=dss.delete(id);return d?ResponseEntity.ok(Map.of("message","删除成功")):ResponseEntity.notFound().build();}catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}}
    @PostMapping("/{id}/test") public ResponseEntity<Map<String,Object>> test(@PathVariable String id){try{return ResponseEntity.ok(dss.testConnection(id));}catch(IllegalArgumentException e){Map<String,Object>err=new HashMap<>();err.put("success",false);err.put("message",e.getMessage());return ResponseEntity.badRequest().body(err);}}
    @PostMapping("/test") public ResponseEntity<Map<String,Object>> testNew(@RequestBody DataSourceInfo ds){return ResponseEntity.ok(dss.testConn(ds));}
    @PostMapping("/{id}/set-default") public ResponseEntity<?> setDefault(@PathVariable String id){try{dss.setDefault(id);return ResponseEntity.ok(Map.of("message","已设置默认"));}catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}}
    @GetMapping("/default-id") public ResponseEntity<Map<String,String>> getDefaultId(){return ResponseEntity.ok(Map.of("id",dss.getDefaultId()));}
}
