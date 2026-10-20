package com.report.controller;

import com.report.service.AnnouncementService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 信息发布栏接口：
 * - GET    /api/announcements        全员可见（启用中的公告）
 * - POST   /api/announcements        发布（announce.manage）
 * - PUT    /api/announcements/{id}   编辑/置顶/启停（announce.manage）
 * - DELETE /api/announcements/{id}   删除（announce.manage）
 */
@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {

    private final AnnouncementService announcementService;

    public AnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<AnnouncementService.Announcement> list() {
        return announcementService.listVisible();
    }

    @PostMapping
    @PreAuthorize("@perm.has('announce.manage')")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body,
                                    @AuthenticationPrincipal UserDetails user) {
        try {
            AnnouncementService.Announcement a = announcementService.create(
                str(body.get("title")), str(body.get("content")), str(body.get("type")),
                Boolean.TRUE.equals(body.get("pinned")),
                user != null ? user.getUsername() : "system");
            return ResponseEntity.ok(Map.of("status", "ok", "announcement", a));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "发布失败"));
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('announce.manage')")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            AnnouncementService.Announcement a = announcementService.update(
                id, str(body.get("title")), str(body.get("content")), str(body.get("type")),
                body.get("pinned") instanceof Boolean ? (Boolean) body.get("pinned") : null,
                body.get("enabled") instanceof Boolean ? (Boolean) body.get("enabled") : null);
            return ResponseEntity.ok(Map.of("status", "ok", "announcement", a));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "更新失败"));
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.has('announce.manage')")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            announcementService.delete(id);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "删除失败"));
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }
}
