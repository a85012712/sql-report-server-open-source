package com.report.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * SpEL 权限点判定，供 @PreAuthorize("@perm.has('xxx')") 使用。
 * 权限点清单见 RolePermissionService.ALL_PERMISSIONS；内置 admin 超管直通。
 */
@Component("perm")
public class PermissionService {

    private final RolePermissionService rolePermissionService;

    public PermissionService(RolePermissionService rolePermissionService) {
        this.rolePermissionService = rolePermissionService;
    }

    public boolean has(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return false;
        }
        return rolePermissionService.has(auth.getName(), permission);
    }
}
