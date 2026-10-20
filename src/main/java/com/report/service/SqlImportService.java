package com.report.service;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 导入/预览参数转换工具（从 AdminController 抽出，便于维护与测试）。
 * 不修改业务 scripts 目录中的既有报表 SQL 文件内容逻辑以外的脚本。
 */
@Service
public class SqlImportService {

    private static final Pattern SQL_BRACKET_PATTERN = Pattern.compile("\\[([^\\]]+)\\]");
    private static final Pattern SQL_COLON_PATTERN = Pattern.compile(":([a-zA-Z_\\u4e00-\\u9fa5][a-zA-Z0-9_\\u4e00-\\u9fa5]*)");

    public static class ParamInfo {
        public String name;     // 参数名（英文）
        public String label;    // 显示名（中文）
        public String type;     // 类型
        public String original; // 原始文本

        ParamInfo(String name, String label, String type, String original) {
            this.name = name;
            this.label = label;
            this.type = type;
            this.original = original;
        }
    }

    public static class ConvertedSql {
        public String convertedSql;
        public List<ParamInfo> params;
        public List<String> notes = new ArrayList<>();  // 转换说明

        ConvertedSql(String sql, List<ParamInfo> params) {
            this.convertedSql = sql;
            this.params = params;
        }
    }

    /** 脚本预处理结果：从数据库客户端可直接运行的脚本 → 单条 SELECT */
    public static class PreprocessResult {
        public String sql;          // 预处理后的SQL（error非空时为null）
        public String error;        // 不可转换原因（明确提示）
        public List<String> notes;  // 转换说明（前端展示给用户）

        PreprocessResult(String sql, String error, List<String> notes) {
            this.sql = sql;
            this.error = error;
            this.notes = notes != null ? notes : new ArrayList<>();
        }
    }

    /**
     * 脚本预处理：把"在数据库客户端（PL/SQL Developer、SQL*Plus、DBeaver 等）能直接运行的脚本"
     * 清洗为单条 SELECT 查询。处理内容：
     * - 末尾分号、SQL*Plus 单独的 "/" 执行行
     * - 客户端尾部辅助语句（COMMIT/ROLLBACK/EXIT/SET/SPOOL 等）自动剥离
     * - 多条语句：取第一条 SELECT（其余忽略并说明）
     * - PL/SQL 匿名块（DECLARE/BEGIN）与非查询语句：明确报错
     */
    public PreprocessResult preprocessScript(String rawSql) {
        List<String> notes = new ArrayList<>();
        String sql = normalizeFullwidthChars(rawSql);
        sql = sql.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (sql.isEmpty()) {
            return new PreprocessResult(null, "SQL内容不能为空", notes);
        }

        List<String> stmts = splitStatements(sql);
        if (stmts.isEmpty()) {
            return new PreprocessResult(null, "未识别到有效的SQL语句", notes);
        }

        // 过滤客户端辅助命令（COMMIT/SET/SPOOL 等），保留查询类语句
        List<String> kept = new ArrayList<>();
        int dropped = 0;
        for (String s : stmts) {
            String head = firstKeyword(s);
            if (head == null) continue;
            if (head.matches("(?i)COMMIT|ROLLBACK|EXIT|WHENEVER|SET|SPOOL|PROMPT|DESCRIBE|DESC|BREAK|COLUMN|TTITLE|BTITLE|REMARK")) {
                dropped++;
                continue;
            }
            kept.add(s);
        }
        if (dropped > 0) {
            notes.add("已自动剥离 " + dropped + " 条客户端辅助命令（COMMIT/SET 等，报表系统只执行只读查询）");
        }
        if (kept.isEmpty()) {
            return new PreprocessResult(null, "未找到查询语句（脚本中只有 COMMIT/SET 等辅助命令）", notes);
        }

        // 只保留第一条查询类语句
        String first = kept.get(0).trim();
        if (kept.size() > 1) {
            notes.add("脚本包含 " + kept.size() + " 条语句，已取第一条 SELECT 查询（其余 " + (kept.size() - 1)
                + " 条已忽略；如需多条请分别导入）");
        }

        // PL/SQL 与非查询检测
        String upper = first.toUpperCase();
        if (upper.startsWith("DECLARE") || upper.startsWith("BEGIN")) {
            return new PreprocessResult(null, "不支持 PL/SQL 匿名块（DECLARE/BEGIN）。请提供单条 SELECT 查询语句", notes);
        }
        String head = firstKeyword(first);
        if (!"SELECT".equals(head) && !"WITH".equals(head)) {
            return new PreprocessResult(null, "只支持 SELECT 查询语句，当前语句以 [" + head + "] 开头", notes);
        }

        // 去掉末尾分号与 SQL*Plus 单独的 "/"
        String out = first;
        while (out.endsWith(";")) out = out.substring(0, out.length() - 1).trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1).trim();
        if (out.isEmpty()) {
            return new PreprocessResult(null, "SQL语句为空", notes);
        }
        if (!out.equals(first)) {
            notes.add("已去除语句末尾的分号与 SQL*Plus 执行符（/）");
        }
        return new PreprocessResult(out, null, notes);
    }

    /** 字符串感知的语句切分：单引号字符串（含''转义）、-- 行注释、块注释内的分号不切分 */
    static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inStr = false, inLine = false, inBlock = false;
        int i = 0, n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : 0;
            if (inLine) {
                cur.append(ch);
                if (ch == '\n') inLine = false;
                i++;
                continue;
            }
            if (inBlock) {
                cur.append(ch);
                if (ch == '*' && next == '/') { cur.append(next); i += 2; inBlock = false; continue; }
                i++;
                continue;
            }
            if (inStr) {
                cur.append(ch);
                if (ch == '\'') {
                    if (next == '\'') { cur.append(next); i += 2; continue; }
                    inStr = false;
                }
                i++;
                continue;
            }
            if (ch == '\'') { inStr = true; cur.append(ch); i++; continue; }
            if (ch == '-' && next == '-') { inLine = true; cur.append(ch); i++; continue; }
            if (ch == '/' && next == '*') { inBlock = true; cur.append(ch); cur.append(next); i += 2; continue; }
            if (ch == ';') {
                String s = cur.toString().trim();
                if (!s.isEmpty()) out.add(s);
                cur.setLength(0);
                i++;
                continue;
            }
            cur.append(ch);
            i++;
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    /** 取语句第一个关键字（剥离注释后） */
    static String firstKeyword(String stmt) {
        String s = com.report.service.SqlUtils.stripComments(stmt).trim();
        if (s.isEmpty()) return null;
        int i = 0, n = s.length();
        while (i < n && !Character.isLetter(s.charAt(i))) i++;
        int j = i;
        while (j < n && Character.isLetterOrDigit(s.charAt(j))) j++;
        return i < j ? s.substring(i, j).toUpperCase() : null;
    }

    /** 语法层警告分析（SELECT * / 无 WHERE） */
    public List<String> analyzeWarnings(String sql) {
        List<String> warnings = new ArrayList<>();
        try {
            String noComments = com.report.service.SqlUtils.stripComments(sql);
            String masked = com.report.service.SqlUtils.maskStringLiterals(noComments);
            String upper = masked.toUpperCase();
            if (upper.matches("(?s)\\s*SELECT\\s+\\*.*")) {
                warnings.add("使用了 SELECT *，建议明确列出所需字段，减少数据传输量");
            }
            if (!upper.contains("WHERE")) {
                warnings.add("查询无 WHERE 过滤条件，将扫描全部数据，建议增加日期/社区等过滤条件");
            }
        } catch (Exception ignore) { }
        return warnings;
    }

    /**
     * 自动转换SQL参数
     *
     * 支持三种输入格式：
     * 1. [中文参数名] 格式 → :english_name
     * 2. 硬编码日期 'YYYY-MM-DD,HH:MM:SS' → :start_date||',00:00:00' / :end_date||',23:59:59'
     * 3. 冒号参数 :param_name → 保持不变（已是系统格式）
     *
     * 同时执行：
     * - 去掉 @ZYXYHIS 等 DB Link
     * - 修复 substr(col, 0, → substr(col, 1,
     */
    public ConvertedSql convertSqlParameters(String rawSql) {
        List<ParamInfo> params = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String sql = rawSql;
        Set<String> usedNames = new HashSet<>();
        Set<String> seenOriginals = new LinkedHashSet<>();

        // ===== 预处理：去掉DB Link =====
        sql = sql.replaceAll("(?i)@\\w+", "");

        // ===== 预处理：修复 substr(col, 0, → substr(col, 1, =====
        sql = sql.replaceAll("(?i)(SUBSTR\\s*\\(\\s*\\w+\\s*,\\s*)0(\\s*,)", "$11$2");

        // 先剥离注释，避免注释中的内容被误提取
        String sqlWithoutComments = com.report.service.SqlUtils.stripComments(rawSql);
        // 去掉DB Link后的去注释版本
        String cleanComments = sqlWithoutComments.replaceAll("(?i)@\\w+", "");

        // ===== 新增：自动识别硬编码日期 =====
        // 匹配 'YYYY-MM-DD,HH:MM:SS' 或 'YYYY-MM-DD HH:MM:SS' 格式
        java.util.regex.Pattern datePattern = java.util.regex.Pattern.compile(
            "'(\\d{4}-\\d{2}-\\d{2})[ ,](\\d{2}:\\d{2}:\\d{2})'");
        java.util.regex.Matcher dateMatcher = datePattern.matcher(sql);

        // 收集所有出现的日期
        java.util.List<String[]> datePairs = new ArrayList<>(); // [日期字符串, 完整匹配]
        while (dateMatcher.find()) {
            String dateStr = dateMatcher.group(1);
            String fullMatch = dateMatcher.group(0);
            datePairs.add(new String[]{dateStr, fullMatch});
        }

        // 如果检测到日期，按日期值分组配对（起始日期和结束日期）
        if (!datePairs.isEmpty()) {
            java.util.Set<String> uniqueDates = new java.util.LinkedHashSet<>();
            for (String[] dp : datePairs) {
                uniqueDates.add(dp[0]);
            }

            // 将日期值排序，第一个作为start_date，最后一个作为end_date
            java.util.List<String> sortedDates = new ArrayList<>(uniqueDates);

            if (sortedDates.size() >= 2) {
                String startDate = sortedDates.get(0);
                String endDate = sortedDates.get(sortedDates.size() - 1);

                // 添加起始日期参数
                if (!usedNames.contains("start_date")) {
                    usedNames.add("start_date");
                    params.add(new ParamInfo("start_date", "起始日期", "date", "'" + startDate + "'"));
                }
                // 添加结束日期参数
                if (!usedNames.contains("end_date")) {
                    usedNames.add("end_date");
                    params.add(new ParamInfo("end_date", "结束日期", "date", "'" + endDate + "'"));
                }

                // 替换所有日期字符串为参数
                for (String[] dp : datePairs) {
                    String dateVal = dp[0];
                    String fullMatch = dp[1];
                    String paramName = dateVal.equals(startDate) ? "start_date" : "end_date";
                    // 根据时间部分判断用 ,00:00:00 还是 ,23:59:59
                    String timeReplacement;
                    if (fullMatch.contains("23:59:59")) {
                        timeReplacement = ":" + paramName + "||',23:59:59'";
                    } else {
                        timeReplacement = ":" + paramName + "||',00:00:00'";
                    }
                    sql = sql.replace(fullMatch, timeReplacement);
                }
            } else if (sortedDates.size() == 1) {
                // 只有一个日期值，当作起始日期
                String dateVal = sortedDates.get(0);
                if (!usedNames.contains("start_date")) {
                    usedNames.add("start_date");
                    params.add(new ParamInfo("start_date", "起始日期", "date", "'" + dateVal + "'"));
                }
                for (String[] dp : datePairs) {
                    String fullMatch = dp[1];
                    sql = sql.replace(fullMatch, ":start_date||',00:00:00'");
                }
            }
        }

        // ===== SQL*Plus 替换变量 &var / &&var / &amp;var → :param =====
        Matcher ampMatcher = Pattern.compile("&(?:amp;)?&?([A-Za-z_\\u4e00-\\u9fa5][A-Za-z0-9_\\u4e00-\\u9fa5]*)")
            .matcher(cleanComments);
        List<String[]> ampVars = new ArrayList<>(); // [原始文本, 参数名]
        Set<String> seenAmp = new LinkedHashSet<>();
        while (ampMatcher.find()) {
            String original = ampMatcher.group(0);
            String rawName = ampMatcher.group(1);
            if (rawName.matches("(?i)null|all|none|true|false|select|from|where|and|or")) continue;
            if (seenAmp.contains(original)) continue;
            seenAmp.add(original);

            String paramName;
            String paramLabel = rawName;
            if (rawName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                paramName = rawName;
            } else {
                paramName = chineseToParamName(rawName);
            }
            String baseName = paramName;
            int suffix = 1;
            while (usedNames.contains(paramName)) {
                paramName = baseName + "_" + suffix++;
            }
            usedNames.add(paramName);
            String paramType = inferParamType(rawName);
            params.add(new ParamInfo(paramName, paramLabel, paramType, original));
            ampVars.add(new String[]{original, paramName});
        }
        if (!ampVars.isEmpty()) {
            notes.add("已把 " + ampVars.size() + " 个 SQL*Plus 替换变量（&变量）转换为 :参数 形式");
            for (String[] av : ampVars) {
                sql = sql.replace(av[0], ":" + av[1]);
            }
        }

        // ===== 原有逻辑：匹配 [参数名] 格式 =====
        Matcher matcher = SQL_BRACKET_PATTERN.matcher(cleanComments);

        while (matcher.find()) {
            String original = matcher.group(0);
            String paramNameRaw = matcher.group(1);

            if (paramNameRaw.length() < 2 || paramNameRaw.matches("(?i)null|all|none|true|false|select|from|where|and|or")) continue;
            if (seenOriginals.contains(original)) continue;
            seenOriginals.add(original);

            String paramName;
            String paramLabel = paramNameRaw;
            if (paramNameRaw.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                paramName = paramNameRaw;
            } else {
                paramName = chineseToParamName(paramNameRaw);
            }

            String baseName = paramName;
            int suffix = 1;
            while (usedNames.contains(paramName)) {
                paramName = baseName + "_" + suffix++;
            }
            usedNames.add(paramName);

            String paramType = inferParamType(paramNameRaw);
            params.add(new ParamInfo(paramName, paramLabel, paramType, original));
        }

        // 执行替换（按原始文本长度倒序）
        params.sort((a, b) -> b.original.length() - a.original.length());

        for (ParamInfo param : params) {
            String replacement = ":" + param.name;
            String toCharPattern = "(?i)TO_CHAR\\(\\s*'" + Pattern.quote(param.original) + "'\\s*,\\s*'[^']*'\\s*\\)";
            sql = sql.replaceAll(toCharPattern, replacement);
            String toDatePattern = "(?i)TO_DATE\\(\\s*'" + Pattern.quote(param.original) + "'\\s*,\\s*'[^']*'\\s*\\)";
            sql = sql.replaceAll(toDatePattern, replacement);
            sql = sql.replace("'" + param.original + "'", replacement);
            sql = sql.replace(param.original, replacement);
        }

        // 二次清理：移除残留的 to_char/to_date 包装
        sql = sql.replaceAll("(?i)TO_CHAR\\(\\s*(:[a-zA-Z_][a-zA-Z0-9_]*)\\s*,\\s*'[^']*'\\s*\\)", "$1");
        sql = sql.replaceAll("(?i)TO_DATE\\(\\s*(:[a-zA-Z_][a-zA-Z0-9_]*)\\s*,\\s*'[^']*'\\s*\\)", "$1");

        // 检测已有的 :param 格式参数
        Matcher colonMatcher = SQL_COLON_PATTERN.matcher(cleanComments);
        while (colonMatcher.find()) {
            String existingParam = colonMatcher.group(1);
            if (usedNames.contains(existingParam)) continue;
            if (existingParam.matches("\\d+")) continue;
            if (existingParam.matches("(?i)start_date|end_date|start_time|end_time")) continue;

            if (existingParam.matches(".*[\\u4e00-\\u9fa5].*")) {
                String newName = chineseToParamName(existingParam);
                if (!usedNames.contains(newName)) {
                    usedNames.add(newName);
                    String paramType = inferParamType(existingParam);
                    params.add(new ParamInfo(newName, existingParam, paramType, ":" + existingParam));
                    sql = sql.replace(":" + existingParam, ":" + newName);
                }
            }
        }
        ConvertedSql __result = new ConvertedSql(sql, params);
        __result.notes = notes;
        return __result;
    }

    /**
     * 修正全角字符为半角（从网页/富文本复制SQL时常出现）
     * 只修正括号、逗号、分号、冒号、引号等SQL标点，不动中文字符
     */
    public String normalizeFullwidthChars(String sql) {
        if (sql == null) return null;
        StringBuilder sb = new StringBuilder(sql.length());
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            switch (c) {
                case '（': sb.append('('); break;  // （
                case '）': sb.append(')'); break;  // ）
                case '，': sb.append(','); break;  // ，
                case '；': sb.append(';'); break;  // ；
                case '：': sb.append(':'); break;  // ：
                case '＝': sb.append('='); break;  // ＝
                case '！': sb.append('!'); break;  // ！
                case '＜': sb.append('<'); break;  // ＜
                case '＞': sb.append('>'); break;  // ＞
                case '「': sb.append('\''); break; // 「
                case '」': sb.append('\''); break; // 」
                case '‘': sb.append('\''); break; // '
                case '’': sb.append('\''); break; // '
                case '“': sb.append('"'); break;  // "
                case '”': sb.append('"'); break;  // "
                case '、': sb.append(','); break;  // 、
                case '．': sb.append('.'); break;  // ．
                case '～': sb.append('~'); break;  // ～
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 中文参数名转英文参数名
     */
    private String chineseToParamName(String chinese) {
        // 常见映射
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("起始日期", "start_date");
        mappings.put("结束日期", "end_date");
        mappings.put("开始日期", "start_date");
        mappings.put("截止日期", "end_date");
        mappings.put("上期起始日期", "prev_start_date");
        mappings.put("上期结束日期", "prev_end_date");
        mappings.put("起始时间", "start_time");
        mappings.put("结束时间", "end_time");
        mappings.put("年度", "year");
        mappings.put("月份", "month");
        mappings.put("年份", "year");
        mappings.put("社区", "community");
        mappings.put("社区代码", "community");
        mappings.put("科室", "dept");
        mappings.put("科室代码", "dept_code");
        mappings.put("科室编码", "dept_code");
        mappings.put("科室名称", "dept_name");
        mappings.put("医生", "doctor");
        mappings.put("医生姓名", "doctor_name");
        mappings.put("姓名", "name");
        mappings.put("人员姓名", "person_name");
        mappings.put("身份证号", "id_card");
        mappings.put("身份证", "id_card");
        mappings.put("人员类别", "staff_category");
        mappings.put("职工居民类别", "staff_category");
        mappings.put("门诊住院类型", "visit_type");
        mappings.put("医疗机构", "institution");
        mappings.put("医疗机构名称", "institution_name");
        mappings.put("状态", "status");
        mappings.put("类型", "type");
        mappings.put("药品", "drug");
        mappings.put("药品名称", "drug_name");
        mappings.put("开始时间", "start_time");
        mappings.put("截止时间", "end_time");
        mappings.put("查询日期", "query_date");
        mappings.put("统计日期", "stat_date");
        mappings.put("操作员", "operator");
        mappings.put("结算操作员", "operator");
        mappings.put("住院号", "admission_no");
        mappings.put("门诊号", "outpatient_no");

        // 精确匹配
        if (mappings.containsKey(chinese)) return mappings.get(chinese);

        // 模糊匹配
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            if (chinese.contains(entry.getKey())) return entry.getValue();
        }

        // 兜底：拼音首字母或p+序号
        StringBuilder sb = new StringBuilder("p");
        for (char c : chinese.toCharArray()) {
            if (c >= '0' && c <= '9') sb.append(c);
            else if (c >= 'a' && c <= 'z') sb.append(c);
            else if (c >= 'A' && c <= 'Z') sb.append(Character.toLowerCase(c));
        }
        String fallback = sb.toString();
        if (fallback.equals("p") || fallback.length() < 3) fallback = "param_" + Math.abs(chinese.hashCode() % 10000);
        return fallback;
    }

    /**
     * 根据参数名推断类型
     */
    private String inferParamType(String name) {
        if (name.contains("日期") || name.contains("date") || name.contains("Date")) return "date";
        if (name.contains("时间") || name.contains("time") || name.contains("Time")) return "text";
        if (name.contains("年度") || name.contains("年份") || name.contains("year")) return "text";
        if (name.contains("月份") || name.contains("month")) return "select";
        if (name.contains("类型") || name.contains("类别") || name.contains("状态")) return "select";
        return "text";
    }

}
