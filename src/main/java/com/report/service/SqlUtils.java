package com.report.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL工具类 - 统一SQL处理逻辑，消除重复代码
 */
public final class SqlUtils {

    private SqlUtils() {}

    private static final Pattern BLOCKED_KEYWORDS = Pattern.compile(
        "\\b(DROP|DELETE|TRUNCATE|ALTER|CREATE|GRANT|REVOKE|INSERT|UPDATE|MERGE|EXEC|EXECUTE|CALL|DECLARE)\\b" +
        "|\\b(DBMS_|UTL_|CTXSYS)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DANGEROUS_FUNCTIONS = Pattern.compile(
        "\\b(UTL_HTTP|UTL_FILE|UTL_TCP|UTL_SMTP|UTL_MAIL|DBMS_JOB|DBMS_SCHEDULER|DBMS_PIPE|DBMS_ALERT|DBMS_LOCK|DBMS_RANDOM|DBMS_CRYPTO|DBMS_OBFUSCATION|DBMS_SQL|EXECUTE IMMEDIATE|OPEN_CURSOR)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PARAM_PATTERN = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");

    /**
     * 管理员"导入/测试 SQL"时额外拦截的关键字。
     * 注意：不包含 BEGIN/END，因为 SELECT 里普遍存在 CASE...END，加入会导致正常报表被误拦。
     * 报表执行路径只做基础校验，管理员预检路径在此基础上额外收紧。
     */
    private static final Pattern EXTRA_DANGEROUS_KEYWORDS = Pattern.compile(
        "\\b(COMMIT|ROLLBACK|SAVEPOINT|ANALYZE|TABLESPACE|DBA_)\\b",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 剥离SQL注释（保留字符串常量不动）
     * 支持：-- 单行注释、/* ... *\/ 块注释、'...' 字符串常量
     */
    public static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        int len = sql.length();
        while (i < len) {
            // 跳过单行注释 --
            if (i + 1 < len && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < len && sql.charAt(i) != '\n') i++;
                if (i < len) { sb.append('\n'); i++; }
                continue;
            }
            // 跳过块注释 /* ... */
            if (i + 1 < len && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                if (i + 1 < len) i += 2; // 跳过 */
                sb.append(' ');
                continue;
            }
            // 跳过单引号字符串 '...'（支持 '' 转义）
            if (sql.charAt(i) == '\'') {
                sb.append(sql.charAt(i)); i++;
                while (i < len) {
                    sb.append(sql.charAt(i));
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                            i++; sb.append(sql.charAt(i));
                        } else {
                            break;
                        }
                    }
                    i++;
                }
                i++;
                continue;
            }
            sb.append(sql.charAt(i));
            i++;
        }
        return sb.toString().trim();
    }

    /**
     * 将单引号字符串字面量的内容替换为空格（保留引号本身），
     * 用于关键字/多语句安全校验，避免字面量内容被误判为危险关键字。
     */
    public static String maskStringLiterals(String sql) {
        if (sql == null) return null;
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                sb.append('\''); i++;
                while (i < len) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                            i += 2; // '' 转义，跳过
                            continue;
                        } else {
                            sb.append('\''); i++;
                            break;
                        }
                    } else {
                        sb.append(' '); // 挖空字面量内容
                        i++;
                    }
                }
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * 校验SQL是否安全（只允许SELECT查询）
     * @return null表示安全，否则返回错误信息
     */
    public static String validateSelectOnly(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "SQL语句不能为空";
        }

        String cleanedSql = stripComments(sql);
        String upper = cleanedSql.toUpperCase().trim();

        // 必须以SELECT或WITH开头
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            return "只允许 SELECT 查询";
        }

        // 关键词/危险函数/多语句检查用"挖空字符串字面量"后的版本，
        // 避免报表里出现 '...DROP...' 之类的字面量/别名被误判为危险操作。
        String maskedSql = maskStringLiterals(cleanedSql);
        String maskedUpper = maskedSql.toUpperCase();

        // 检查黑名单关键字
        Matcher m = BLOCKED_KEYWORDS.matcher(maskedUpper);
        if (m.find()) {
            return "SQL包含禁止的操作: " + m.group();
        }

        // 检查危险函数
        Matcher funcMatcher = DANGEROUS_FUNCTIONS.matcher(maskedUpper);
        if (funcMatcher.find()) {
            return "SQL包含禁止的函数: " + funcMatcher.group();
        }

        // 检查是否有多个SQL语句（分号分割）
        String withoutTrailingSemicolon = maskedSql.trim().replaceAll(";\\s*$", "");
        if (withoutTrailingSemicolon.contains(";")) {
            return "不允许多条SQL语句";
        }

        // 检查括号匹配
        int parenCount = 0;
        boolean inStr = false;
        for (int ci = 0; ci < cleanedSql.length(); ci++) {
            char c = cleanedSql.charAt(ci);
            if (c == '\'') {
                if (ci + 1 < cleanedSql.length() && cleanedSql.charAt(ci + 1) == '\'') {
                    ci++;
                    continue;
                }
                inStr = !inStr;
                continue;
            }
            if (inStr) continue;
            if (c == '(') parenCount++;
            if (c == ')') parenCount--;
            if (parenCount < 0) {
                return "SQL语法错误：括号不匹配";
            }
        }
        if (parenCount != 0) {
            return "SQL语法错误：括号不匹配";
        }

        return null;
    }

    /**
     * 管理员导入/测试 SQL 时在基础校验之上的额外拦截。
     * @return null 表示安全，否则返回错误信息
     */
    public static String validateExtraDangerous(String sql) {
        if (sql == null || sql.trim().isEmpty()) return null;
        String masked = maskStringLiterals(sql);
        Matcher m = EXTRA_DANGEROUS_KEYWORDS.matcher(masked);
        if (m.find()) {
            return "SQL包含禁止的操作: " + m.group();
        }
        String upper = masked.toUpperCase();
        if (upper.contains("SYS.") || upper.contains("SYSTEM.")) {
            return "SQL不允许访问系统表";
        }
        return null;
    }

    /** 从完整脚本内容（YAML front matter + SQL）中提取 SQL 部分；无 YAML 头则原样返回。 */
    public static String extractSql(String fullContent) {
        if (fullContent == null) return null;
        Matcher m = YAML_BLOCK_PATTERN.matcher(fullContent);
        if (m.matches()) return m.group(2);
        return fullContent;
    }

    private static final Pattern YAML_BLOCK_PATTERN = Pattern.compile(
        "^\\s*---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL);

    /** 扫描回调：原样文本段走 {@link #literal}，识别出的占位符走 {@link #param}。 */
    private interface ParamSink {
        default void literal(String text) { }
        void param(String name);
    }

    /**
     * 引号感知的 {@code :param} 扫描器，全类只有这一份状态机。
     *
     * <p>为什么必须只有一份：{@link #prepareNamed} 先用
     * {@link #extractParamNamesInOrder} 数出参数名、再用
     * {@link #replaceParamsWithPlaceholders} 生成 {@code ?}，然后按位置绑定。
     * 两者对「哪个 {@code :x} 算占位符」的答案必须逐字一致，否则轻则数量不符直接
     * SQLException，重则把值绑到别的条件上，查询照样返回 200，数据却是错的。
     * 以前是同一台状态机抄两遍，一致性靠"复制粘贴没跑偏"维持。
     *
     * <p>语义与原来两处保持逐字节相同：{@code ''} 视成一对转义引号整体吃掉（不切换状态）、
     * 双引号内不算占位符、{@code :=} 不算占位符。
     * 注意：已知的既有怪异一并保留、没顺手改：连续两个冒号只跳过前一个，
     * 所以 {@code a::text} 里的 {@code text} 仍会被识别成占位符。改它会动到已上线报表的
     * 参数个数，属于「要单独验证的语义变更」，不属于合并重构。
     */
    private static void scanNamedParams(String sql, ParamSink sink) {
        StringBuilder buf = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (c == '\'' && !inDoubleQuote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    buf.append(c).append(sql.charAt(i + 1));
                    i++;
                    continue;
                }
                inSingleQuote = !inSingleQuote;
                buf.append(c);
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                buf.append(c);
                continue;
            }
            if (inSingleQuote || inDoubleQuote) {
                buf.append(c);
                continue;
            }

            if (c == ':' && i + 1 < sql.length()) {
                if (sql.charAt(i + 1) == ':' || sql.charAt(i + 1) == '=') {
                    buf.append(c);
                    continue;
                }
                Matcher m = PARAM_PATTERN.matcher(sql.substring(i));
                if (m.lookingAt()) {
                    if (buf.length() > 0) {
                        sink.literal(buf.toString());
                        buf.setLength(0);
                    }
                    sink.param(m.group(1));
                    i += m.end() - 1;
                    continue;
                }
            }
            buf.append(c);
        }
        if (buf.length() > 0) sink.literal(buf.toString());
    }

    /**
     * 按出现顺序提取 :param 名称（不去重，便于按序绑定；字符串字面量内跳过）。
     */
    public static List<String> extractParamNamesInOrder(String sql) {
        List<String> names = new ArrayList<>();
        if (sql == null) return names;
        scanNamedParams(sql, names::add);
        return names;
    }

    /**
     * 将 :param_name 替换为 ?（跳过字符串字面量）。
     */
    public static String replaceParamsWithPlaceholders(String sql) {
        if (sql == null) return null;
        StringBuilder result = new StringBuilder(sql.length());
        scanNamedParams(sql, new ParamSink() {
            @Override public void literal(String text) { result.append(text); }
            @Override public void param(String name) { result.append('?'); }
        });
        return result.toString();
    }

    /**
     * 将 :param SQL 转为 PreparedStatement 并按出现顺序绑定。
     */
    public static PreparedStatement prepareNamed(Connection conn, String sql,
                                                 Map<String, String> bindings,
                                                 int queryTimeoutSeconds,
                                                 int fetchSize) throws SQLException {
        List<String> paramNames = extractParamNamesInOrder(sql);
        String processed = replaceParamsWithPlaceholders(sql);
        PreparedStatement ps = conn.prepareStatement(processed);
        if (fetchSize > 0) {
            ps.setFetchSize(fetchSize);
        }
        if (queryTimeoutSeconds > 0) {
            ps.setQueryTimeout(queryTimeoutSeconds);
        }
        for (int i = 0; i < paramNames.size(); i++) {
            String name = paramNames.get(i);
            String value = bindings != null ? bindings.get(name) : null;
            if (value == null) {
                ps.setNull(i + 1, Types.VARCHAR);
            } else {
                ps.setString(i + 1, value);
            }
        }
        return ps;
    }
}
