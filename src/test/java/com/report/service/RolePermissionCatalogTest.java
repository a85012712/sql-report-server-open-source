package com.report.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限点目录的不变量守卫。
 *
 * <p>背景：角色配置弹窗的权限勾选清单由前端渲染。早先前端硬编码了分组清单，
 * 只覆盖 12 个权限点，漏掉 {@code personnel.manage}（人员管理）与
 * {@code announce.manage}（信息发布）。后果不是"少显示一个复选框"这么简单：
 * 打开「科主任」配置弹窗时看不到「人员管理」，直接点保存会用"已勾选的"覆盖权限集，
 * 把 personnel.manage 静默删掉，角色被悄悄改坏。
 *
 * <p>修法是把分组声明收归后端（{@code ALL_PERMISSIONS} 的第三列），前端只按
 * {@code group} 字段聚合。本测试守住这个不变量，防止以后新增权限点时再出现漏项。
 */
class RolePermissionCatalogTest {

    /** 每个权限点都必须带分组，且分组名非空，否则前端会把它归到「其它」甚至漏掉 */
    @Test
    void everyPermissionDeclaresAGroup() {
        List<String> missing = new ArrayList<>();
        for (String[] p : RolePermissionService.ALL_PERMISSIONS) {
            if (p.length < 3 || p[2] == null || p[2].isBlank()) {
                missing.add(p[0]);
            }
        }
        Assertions.assertTrue(missing.isEmpty(),
            "以下权限点未声明分组（前端将无法归类，可能导致配置弹窗漏项）: " + missing);
    }

    /** 权限点 code 不得重复，重复会让勾选框互相覆盖 */
    @Test
    void permissionCodesAreUnique() {
        Set<String> seen = new LinkedHashSet<>();
        List<String> dup = new ArrayList<>();
        for (String[] p : RolePermissionService.ALL_PERMISSIONS) {
            if (!seen.add(p[0])) dup.add(p[0]);
        }
        Assertions.assertTrue(dup.isEmpty(), "权限点 code 重复: " + dup);
    }

    /** 权限点显示名不得重复（界面上会分不清） */
    @Test
    void permissionLabelsAreUnique() {
        Set<String> seen = new LinkedHashSet<>();
        List<String> dup = new ArrayList<>();
        for (String[] p : RolePermissionService.ALL_PERMISSIONS) {
            if (!seen.add(p[1])) dup.add(p[1]);
        }
        Assertions.assertTrue(dup.isEmpty(), "权限点显示名重复: " + dup);
    }

    /**
     * 所有出现的分组都必须在 {@link RolePermissionService#PERMISSION_GROUPS} 里登记，
     * 保证前端按声明顺序渲染时不会把某个分组排到末尾。
     */
    @Test
    void allGroupsAreRegisteredInDisplayOrder() {
        List<String> unregistered = new ArrayList<>();
        for (String[] p : RolePermissionService.ALL_PERMISSIONS) {
            if (p.length > 2 && !RolePermissionService.PERMISSION_GROUPS.contains(p[2])) {
                unregistered.add(p[0] + " → " + p[2]);
            }
        }
        Assertions.assertTrue(unregistered.isEmpty(),
            "以下权限点的分组未登记在 PERMISSION_GROUPS 中: " + unregistered);
    }

    /**
     * 分组数 + 总数要与"前端渲染出来的复选框数"一致。
     *
     * <p>这是对本次缺陷最直接的回归断言：把「分组聚合」的模拟结果与原始清单比对，
     * 数量不等即说明有权限点会在界面上消失。
     */
    @Test
    void groupingCoversEveryPermissionExactlyOnce() {
        // 模拟前端：按 group 字段聚合（保持首现顺序）
        List<List<String>> grouped = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String[] p : RolePermissionService.ALL_PERMISSIONS) {
            String g = p.length > 2 ? p[2] : "其它";
            int idx = names.indexOf(g);
            if (idx < 0) { names.add(g); grouped.add(new ArrayList<>()); idx = names.size() - 1; }
            grouped.get(idx).add(p[0]);
        }
        int rendered = grouped.stream().mapToInt(List::size).sum();
        Assertions.assertEquals(RolePermissionService.ALL_PERMISSIONS.size(), rendered,
            "分组渲染出的复选框数(" + rendered + ") != 权限点总数("
                + RolePermissionService.ALL_PERMISSIONS.size() + ")，界面上会有权限点看不到");

        // 且每个权限点恰好出现一次
        Set<String> flat = new LinkedHashSet<>();
        grouped.forEach(flat::addAll);
        Assertions.assertEquals(RolePermissionService.ALL_PERMISSIONS.size(), flat.size(),
            "分组聚合后出现重复或遗漏");
    }

    /**
     * 已删除的 {@code role_dept_head} 不得再被**补建**。
     *
     * <p>该角色已删除：它的权限点与「普通用户」
     * （{@code role_user}）一字不差，已失去区分度；「科主任」「社区主任」两个职务
     * 现直接挂 {@code role_user}。
     *
     * <p>本测试守的是「删除会不会悄悄回滚」：本类是**纯静态单测**（不起 Spring 上下文），
     * 所以用**读源码**的方式断言，检查 {@code loadRoles()} 里没有再把
     * {@code R_DEPT_HEAD_REMOVED} 传进 {@code ensureBuiltin} 补建。
     * 如果哪天有人手滑加回去，全新部署又会凭空多出一个没有区分度的角色，
     * 而管理员的删除操作**看起来成功了**，这正是本项目反复出现的那个类：
     * 「代码默认值」与「运行数据」两套依据。
     *
     * <p>运行时层面（角色确实不在 roles.json / 职务确实挂 role_user）由
     * {@code AffiliationIntegrationTest} 覆盖，两者互补，不是重复。
     */
    @Test
    void removedDeptHeadRoleIsNotRecreatedInCode() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/report/service/RolePermissionService.java"));
        // 只查「补建」这一种用法：ensureBuiltin(... R_DEPT_HEAD...)
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("ensureBuiltin\\s*\\([^)]*R_DEPT_HEAD", java.util.regex.Pattern.DOTALL)
            .matcher(src);
        Assertions.assertFalse(m.find(),
            "role_dept_head 已废弃删除，loadRoles() 不该再补建它，"
                + "否则全新部署会把它凭空造回来，管理员的删除看起来成功但实际会回滚");
    }

    /**
     * 权限点目录里必须始终有 personnel.manage。
     *
     * <p>这是当年那个缺陷的守卫：权限点目录只覆盖 12 项、漏掉 personnel.manage 与另一项，
     * 于是角色编辑弹窗里**没有这个勾选框**，打开某个角色弹窗一保存就把该权限静默删掉了。
     *
     * <p>注意：注意本测试断言的是**目录**（{@code ALL_PERMISSIONS}），
     * 不是「某个角色必须含该权限」，早先的方法名
     * {@code deptHeadRoleStillCarriesPersonnelManage} 与断言不符（名不副实），已改名。
     * 「人员管理」只对系统管理员开放，受限角色里不该再有这个权限点。
     */
    @Test
    void permissionCatalogIncludesPersonnelManage() {
        Assertions.assertTrue(
            RolePermissionService.ALL_PERMISSIONS.stream()
                .anyMatch(p -> RolePermissionService.P_PERSONNEL.equals(p[0])),
            "权限点目录里必须有 personnel.manage");
        Assertions.assertTrue(RolePermissionService.PERMISSION_GROUPS.size() >= 3,
            "分组至少应覆盖 查看类/管理类/业务管理");
    }
}
