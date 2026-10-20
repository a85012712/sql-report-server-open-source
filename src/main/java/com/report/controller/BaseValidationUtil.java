package com.report.controller;

import java.util.Optional;

/**
 * 通用参数校验工具类
 * 提供控制器间共享的校验逻辑，返回 Optional 便于统一错误处理
 */
public final class BaseValidationUtil {

    private BaseValidationUtil() {}

    /**
     * 统一密码策略：至少8位，且包含字母与数字
     */
    public static Optional<String> validatePassword(String password) {
        if (password == null || password.length() < 8) {
            return Optional.of("密码长度不能少于8位");
        }
        if (password.length() > 64) {
            return Optional.of("密码长度不能超过64位");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            return Optional.of("密码必须同时包含字母和数字");
        }
        return Optional.empty();
    }

    public static Optional<String> validateUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return Optional.of("用户名不能为空");
        }
        if (username.length() < 3 || username.length() > 20) {
            return Optional.of("用户名长度必须在3-20个字符之间");
        }
        if (!username.matches("^[a-zA-Z0-9_]+$")) {
            return Optional.of("用户名只能包含字母、数字和下划线");
        }
        return Optional.empty();
    }

    public static Optional<String> validateRole(String role) {
        if (role == null || (!"USER".equals(role) && !"ADMIN".equals(role))) {
            return Optional.of("角色只能是 USER 或 ADMIN");
        }
        return Optional.empty();
    }

    public static Optional<String> validateGroupId(String id) {
        if (id == null || id.trim().isEmpty()) {
            return Optional.of("分组ID不能为空");
        }
        if (id.length() > 50) {
            return Optional.of("分组ID过长，最多50个字符");
        }
        if (!id.matches("^[a-zA-Z0-9_]+$")) {
            return Optional.of("分组ID只能包含字母、数字和下划线");
        }
        return Optional.empty();
    }

    public static Optional<String> validateGroupName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.of("分组名称不能为空");
        }
        if (name.length() > 100) {
            return Optional.of("分组名称过长，最多100个字符");
        }
        return Optional.empty();
    }

    public static Optional<String> validateNotEmpty(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            return Optional.of(fieldName + "不能为空");
        }
        return Optional.empty();
    }
}
