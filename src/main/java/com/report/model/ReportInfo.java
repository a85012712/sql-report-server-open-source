package com.report.model;

import java.util.List;
import java.util.Map;

/**
 * 报表信息
 */
public class ReportInfo {
    private String id;          // 报表ID（文件名去扩展名）
    private String name;        // 报表名称
    private String description; // 描述
    private String group;       // 所属分组ID
    private List<ParamDef> params; // 参数定义列表
    private String sql;         // SQL语句
    private String file;        // 源文件名

    // getter/setter
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public List<ParamDef> getParams() { return params; }
    public void setParams(List<ParamDef> params) { this.params = params; }
    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }
    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }

    /**
     * 参数定义
     */
    public static class ParamDef {
        private String name;        // 参数名
        private String label;       // 显示名称
        private String type;        // 类型: date, text, number, select
        private boolean required;   // 是否必填
        private String defaultValue; // 默认值
        private String placeholder; // 占位提示
        private String source;      // 下拉数据源 (sql:... 或 list:...)

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getLabel() { return label != null ? label : name; }
        public void setLabel(String label) { this.label = label; }
        public String getType() { return type != null ? type : "text"; }
        public void setType(String type) { this.type = type; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public String getDefaultValue() { return defaultValue; }
        public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
        public String getPlaceholder() { return placeholder; }
        public void setPlaceholder(String placeholder) { this.placeholder = placeholder; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }

    /**
     * 查询结果
     */
    public static class QueryResult {
        private List<String> columns;
        private List<List<String>> rows;
        private long total;
        private int page;
        private int pageSize;
        private String error;

        public List<String> getColumns() { return columns; }
        public void setColumns(List<String> columns) { this.columns = columns; }
        public List<List<String>> getRows() { return rows; }
        public void setRows(List<List<String>> rows) { this.rows = rows; }
        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
        public int getPageSize() { return pageSize; }
        public void setPageSize(int pageSize) { this.pageSize = pageSize; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}
