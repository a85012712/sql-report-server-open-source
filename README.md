# HIS报表查询系统

基于 **Spring Boot 3.5 + JDK 21 + Oracle** 的轻量级医院内部报表查询与数据管理平台，
含数据可视化大屏与统一权限管理。

> 本项目在早期公开版本（v1.0.0，Spring Boot 3.2.5）基础上重构：保留原有的 SQL 报表能力，
> 补齐了角色权限体系、工号归属管理、数据可视化大屏、操作审计与前端资源全本地化。
>
> English version: [GITHUB_README.md](GITHUB_README.md)

## 功能特性

- **SQL 报表系统** — YAML 头 + SQL 语句定义报表，支持参数化查询、分页、排序、中文列别名
- **SQL 一键导入转换** — 直接粘贴数据库客户端能运行的完整脚本，自动去分号 / 剥离 COMMIT、
  把硬编码日期与 `[参数]` / `:参数` / `&变量` 参数化，转换后先做只读校验再入库
- **数据可视化大屏** — 门诊量 / 住院 / 收入 / 毛利率 / 客单价 / 分时客流 / 费用分类 / 出入院趋势等经营指标；
  后端定时主动查询并缓存，大屏秒开不打库；支持按社区一键切换、按工号授权开通。
  **取数失败时保留上一次真实数据并显式标注「数据未更新」，绝不回落演示数据**
- **多数据源管理** — Web 界面管理数据库连接，支持按报表绑定不同数据源
- **用户管理** — 工号 + 姓名，工号可自动续写，初始密码首次登录强制修改
- **角色与权限** — 内置角色 + 自定义角色，14 个权限点划分到菜单与操作；
  前端元素统一用 `data-perm` 声明，后端按权限点鉴权
- **数据权限** — 工号绑定可查看社区，报表下拉 / 查询 / 导出 / 大屏由系统层强制过滤，
  报表脚本零修改；受限账号 **fail-closed**：报表 SQL 未真正绑定社区参数时直接拒查
- **字典管理** — 社区 / 科室 / 人员字典，科室与人员支持从 HIS 一键导入（幂等）
- **跨库查询** — 通过 Oracle DB Link 支持跨服务器数据关联查询
- **Excel 导出** — 流式导出，支持 20000 行大数据量，中文列宽自适应
- **安全防护** — SQL 注入防护、CSRF、请求限流（`/api/**` 分档 + 登录按 IP 限速）、
  XSS 渲染侧转义、CSP（本地资源、**零 CDN**）、越权与自提权防护、审计 IP 不可伪造
- **操作审计** — 操作记录分级留痕，每条补齐 `user` / `clientIp` / `peerAddr`，支持导出
- **健康检查** — `/actuator/health` 公开；Prometheus 指标端点需登录后访问（附 Grafana 面板模板）

## 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | Spring Boot 3.5.16 / JDK 21 |
| 安全 | Spring Security（CSRF + RBAC + CORS） |
| 调度 | Spring `@Scheduled`（大屏定时查询，单线程串行） |
| 数据库 | Oracle JDBC（ojdbc11）+ HikariCP 连接池（多数据源动态管理） |
| Excel | Apache POI（SXSSFWorkbook 流式导出） |
| 前端 | Thymeleaf + 原生 JS + RemixIcon + ECharts 5（**全部本地化，零 CDN**） |
| 监控 | Micrometer + Prometheus |
| 构建 | Maven 3.9+ |

## 快速开始

1. 安装 JDK 21+
2. 复制 `.env.example` 为 `.env`，填写数据库连接与管理员密码
3. Windows 双击 `启动服务.bat`；Linux/macOS 执行 `java -jar target/sql-report-server-<版本>.jar`
4. 访问 <http://localhost:8090>
5. 用 `admin` + `.env` 中配置的密码登录

```bash
# 编译打包
mvn clean package -DskipTests

# 启动（环境变量方式，Linux/macOS）
export DB_URL='jdbc:oracle:thin:@<HOST>:<PORT>:<SID>'
export DB_USERNAME='<USER>'
export DB_PASSWORD='<PASSWORD>'
export ADMIN_PASSWORD='<至少8位>'
java -jar target/sql-report-server-1.5.0-20261020.jar
```

## 项目结构

```
sql-report-server-open-source/
├── src/
│   ├── main/java/com/report/          # Java 源码
│   │   ├── config/                    # 安全 / 数据源 / 审计 / 指标 配置
│   │   ├── controller/                # REST 接口
│   │   ├── service/                   # 业务服务
│   │   ├── model/                     # 数据模型
│   │   ├── interceptor/               # 限流拦截器
│   │   └── util/                      # 工具类
│   ├── main/resources/
│   │   ├── application.yml            # 主配置
│   │   ├── templates/                 # 页面（登录 / 主界面 / 大屏）
│   │   └── static/                    # 前端资源（CSS/JS/图标，全部本地化）
│   └── test/                          # 单元与集成测试
├── scripts/                           # 报表 SQL（示例）
│   └── screen/                        # 大屏指标 SQL（文件名固定）
├── pom.xml                            # Maven 构建文件
├── .env.example                       # 环境变量模板
├── 启动服务.bat                        # Windows 启动脚本
├── 启动前自检.bat                      # 启动前环境自检
├── 清理.bat                            # 清理旧版本包 / 历史日志
├── 部署说明.md                         # 部署指南
└── LICENSE
```

运行时会自动创建 `data/`（数据与配置）与 `logs/`（日志），二者均不入库。

## 添加报表

在 `scripts/` 目录新建 `.sql` 文件（详见 [scripts/README.md](scripts/README.md)）：

```sql
---
name: 报表名称
description: 报表描述
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

SELECT * FROM your_table
WHERE 时间字段 BETWEEN to_date(:start_date || ' 00:00:00', 'yyyy-mm-dd hh24:mi:ss')
                   AND to_date(:end_date   || ' 23:59:59', 'yyyy-mm-dd hh24:mi:ss')
  AND 社区字段 LIKE :community
```

- 参数类型：`date` / `text` / `number` / `select`
- **社区筛选约定**：参数名固定为 `community`，且 SQL 里必须真的绑定它 ——
  受限账号的数据隔离靠这一条，不绑定会被直接拒查
- 只允许 `SELECT`（含 `WITH ... SELECT`），写操作会被拒绝

## 数据源配置

真实主机、端口与账号一律不写入本仓库，全部走环境变量（见 `.env.example`）：

| 变量 | 说明 |
|------|------|
| `DB_URL` | `jdbc:oracle:thin:@<HOST>:<PORT>:<SID>` 或 `@//<HOST>:<PORT>/<SERVICE>` |
| `DB_USERNAME` / `DB_PASSWORD` | 业务库账号；口令可填 `enc:` 前缀的 AES-256-GCM 密文 |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | 内置超管账号（`ADMIN_PASSWORD` 未配置时启动失败） |
| `SERVER_PORT` | 服务端口，默认 8090 |

也可登录后在「数据源管理」页添加/切换多数据源，支持按报表绑定。

## 许可

[MIT License](LICENSE)

## 开发者

- YAO
