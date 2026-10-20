package com.report.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlUtilsTest {

    @Test
    void extractParamNamesInOrder() {
        String sql = "SELECT * FROM t WHERE a = :start_date AND b = :end_date AND c = :start_date";
        List<String> names = SqlUtils.extractParamNamesInOrder(sql);
        assertEquals(List.of("start_date", "end_date", "start_date"), names);
    }

    @Test
    void skipParamsInsideStringLiterals() {
        String sql = "SELECT ':not_a_param' AS x FROM t WHERE id = :id";
        List<String> names = SqlUtils.extractParamNamesInOrder(sql);
        assertEquals(List.of("id"), names);
        String replaced = SqlUtils.replaceParamsWithPlaceholders(sql);
        assertTrue(replaced.contains("':not_a_param'"));
        assertTrue(replaced.contains("?"));
        assertFalse(replaced.contains(":id"));
    }

    @Test
    void replaceMultipleParams() {
        String sql = "SELECT 1 FROM dual WHERE x = :a AND y = :b";
        assertEquals("SELECT 1 FROM dual WHERE x = ? AND y = ?",
            SqlUtils.replaceParamsWithPlaceholders(sql));
    }

    /**
     * 绑定契约：{@code extractParamNamesInOrder} 数出来的名字个数，必须等于
     * {@code replaceParamsWithPlaceholders} 吐出的 {@code ?} 个数。
     *
     * <p>{@code prepareNamed} 先取前者当绑定顺序、再用后者建 PreparedStatement、
     * 最后按位置 setString。两个数一旦不一致，轻则参数个数不符直接 SQLException，
     * 重则把值绑到别的条件上，查询返回 200、数据却是别人的，最难发现。
     *
     * <p>这两处过去是同一台引号感知状态机抄了两遍，一致性只靠「复制粘贴没跑偏」维持。
     * 现在底层已合并成一个扫描器，本测试把契约钉死，防止将来又被拆回两份。
     */
    @Test
    void nameCountAlwaysMatchesPlaceholderCount() {
        String[] tricky = {
            "SELECT * FROM t WHERE a=:x AND b=:y",
            "SELECT ':no' , :yes FROM dual",                         // 单引号内的冒号不算
            "SELECT 'it''s :here' AS s, :real AS r FROM dual",       // '' 转义后仍认得到真参数
            "SELECT b:=c, :ok FROM dual",                            // := 不算占位符
            "SELECT \":quoted:x\" FROM t WHERE id = :id",            // 双引号标识符内不算
            ":first and :last",                                      // 首尾都是参数
            "SELECT :p",
            "SELECT x FROM t WHERE y = 'unterminated :param",        // 引号没闭合：两处都当在串内
            "",
            "no params here",
            "SELECT :a, :a, :a",                                     // 重名不去重
            // 报表里的真实写法：冒号参数紧挨着 || 拼出的字符串字面量
            "WHERE d BETWEEN :start_date||',00:00:00' AND :end_date||',23:59:59'",
            "WHERE a = :p1::text AND b = :p2",                       // :: 的既有怪异性为契约仍成立
        };
        for (String sql : tricky) {
            int names = SqlUtils.extractParamNamesInOrder(sql).size();
            long qmarks = SqlUtils.replaceParamsWithPlaceholders(sql).chars()
                .filter(ch -> ch == '?').count();
            assertEquals(names, qmarks, "名字个数必须等于 ? 个数，否则按位置绑定会错位：" + sql);
        }
    }

    /** 合并扫描器后必须逐字保持原有识别结果（这几条是「改坏了立刻红」的钉桩）。 */
    @Test
    void recognitionResultsArePinned() {
        assertEquals(List.of("text"), SqlUtils.extractParamNamesInOrder("SELECT a::text"));
        assertEquals(List.of(), SqlUtils.extractParamNamesInOrder("SELECT 'a :b c'"));
        assertEquals(List.of("real"),
            SqlUtils.extractParamNamesInOrder("SELECT 'it''s :here' , :real FROM dual"));
        assertEquals("SELECT 'it''s :here' , ? FROM dual",
            SqlUtils.replaceParamsWithPlaceholders("SELECT 'it''s :here' , :real FROM dual"));
        assertNull(SqlUtils.replaceParamsWithPlaceholders(null));
        assertEquals(List.of(), SqlUtils.extractParamNamesInOrder(null));
    }

    @Test
    void validateSelectOnly() {
        assertNull(SqlUtils.validateSelectOnly("SELECT 1 FROM dual"));
        assertNotNull(SqlUtils.validateSelectOnly("DELETE FROM t"));
        assertNotNull(SqlUtils.validateSelectOnly("SELECT 1; DROP TABLE t"));
    }

    @Test
    void stripComments() {
        String sql = "SELECT 1 -- comment\nFROM dual /* block */";
        String cleaned = SqlUtils.stripComments(sql);
        assertFalse(cleaned.contains("comment"));
        assertFalse(cleaned.contains("block"));
        assertTrue(cleaned.toUpperCase().contains("SELECT"));
    }

    @Test
    void extractSql_fromYamlFrontMatter() {
        String content = "---\nname: t\ndescription: d\n---\n\nSELECT 1 FROM dual";
        assertEquals("SELECT 1 FROM dual", SqlUtils.extractSql(content).trim());
    }

    @Test
    void extractSql_withoutYamlReturnsOriginal() {
        assertEquals("SELECT 1", SqlUtils.extractSql("SELECT 1"));
        assertNull(SqlUtils.extractSql(null));
    }

    @Test
    void validateExtraDangerous_blocksCommitAndSystemSchema() {
        assertNotNull(SqlUtils.validateExtraDangerous("SELECT 1 FROM t WHERE x=1 COMMIT"));
        assertNotNull(SqlUtils.validateExtraDangerous("SELECT * FROM SYS.USER$"));
        assertNotNull(SqlUtils.validateExtraDangerous("SELECT * FROM SYSTEM.LOGSTDBY$PARAMS"));
        assertNull(SqlUtils.validateExtraDangerous("SELECT 1 FROM dual"));
    }

    @Test
    void validateExtraDangerous_allowsCaseEnd() {
        // SELECT 里普遍存在 CASE...END，额外关键字清单不得包含 END/BEGIN，否则正常报表被误拦
        assertNull(SqlUtils.validateExtraDangerous(
            "SELECT CASE WHEN 1=1 THEN 'a' ELSE 'b' END AS X FROM DUAL"));
    }
}
