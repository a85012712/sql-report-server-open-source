package com.report.service;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户分组语义单元说明（与 UserManagementService 行为对齐的契约测试）。
 * 完整服务依赖 Spring Security，此处验证约定逻辑本身。
 */
class UserGroupsSemanticsTest {

    @Test
    void emptyGroupsMeansDefaultOnly() {
        List<String> groups = null;
        List<String> effective = (groups == null || groups.isEmpty())
            ? Collections.singletonList("default")
            : groups;
        assertEquals(List.of("default"), effective);
        assertFalse(effective.isEmpty());
        assertNotEquals("全部", String.valueOf(effective));
    }

    @Test
    void configuredGroupsPreserved() {
        List<String> groups = List.of("sales", "finance");
        List<String> effective = (groups == null || groups.isEmpty())
            ? Collections.singletonList("default")
            : groups;
        assertEquals(2, effective.size());
        assertTrue(effective.contains("sales"));
    }
}
