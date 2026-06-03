# HIS报表查询系统

基于 Spring Boot 3.2.5 + JDK 21 + Oracle 的医院内部报表查询与数据管理平台。

## 功能特性

- **SQL报表系统** - YAML配置 + SQL语句，支持参数化查询、分页、排序
- **多数据源管理** - Web界面管理数据库连接，支持按报表绑定不同数据源
- **用户管理** - 工号+姓名，初始密码666999，首次登录强制改密
- **分组权限控制** - 用户分组 → 报表分组过滤，支持报表隐藏
- **跨库查询** - 通过Oracle DB Link支持跨服务器数据关联查询
- **Excel导出** - 流式导出，支持20000行大数据量，中文列宽自适应
- **安全防护** - SQL注入防护、CSRF、请求限流、XSS防护、CSP白名单
- **审计日志** - 操作记录5级分类，支持导出
- **健康检查** - /actuator/health + Prometheus监控端点
- **社区筛选** - 所有报表支持按社区（A/B/C/D/E）下拉筛选

## 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | Spring Boot 3.2.5 / JDK 21 |
| 安全 | Spring Security (CSRF + RBAC + CORS) |
| 数据库 | Oracle JDBC + HikariCP 连接池 |
| Excel | Apache POI 5.2.5 (SXSSFWorkbook) |
| 前端 | Thymeleaf + 原生JS + RemixIcon |
| 监控 | Micrometer + Prometheus |

## 快速开始

1. 确保已安装 JDK 21+
2. 复制 `.env.example` 为 `.env`，填写数据库连接和管理员密码
3. 双击 `启动服务.bat` 启动（Windows）或 `java -jar sql-report-server-1.0.0.jar`（Linux）
4. 访问 http://localhost:8080
5. 使用 admin + .env中配置的密码登录

## 项目结构

```
sql-report-server-open-source/
├── src/                           # 源代码
│   └── main/
│       ├── java/com/report/       # Java源码
│       └── resources/             # 前端+配置
├── pom.xml                        # Maven构建文件
├── .env.example                   # 环境变量模板
├── .gitignore
├── scripts/                       # 报表SQL示例
├── data/                          # 运行时数据
└── 部署说明.md
```

## 添加报表

在 `scripts/` 目录创建 `.sql` 文件：

```sql
---
name: 报表名称
description: 描述
group: default
params:
  - name: start_date
    label: 起始日期
    type: date
    required: true
  - name: community
    label: 社区
    type: select
    required: false
    default: "%"
    source: "list:A,B,C,D,E,%"
---

SELECT * FROM 表名
WHERE 日期字段 BETWEEN :start_date||',00:00:00' AND :end_date||',23:59:59'
  AND 社区字段 LIKE :community
```

## 开发者

- YAO
