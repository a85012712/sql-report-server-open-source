# 报表 SQL 目录

本目录存放**报表定义文件**：每个 `.sql` 文件 = 一段 YAML 头 + 一段查询 SQL。
系统启动后扫描本目录，按文件名（去掉 `.sql`）作为报表 ID。

```
scripts/          报表 SQL（报表中心用）
scripts/screen/   大屏指标 SQL（数据大屏用，文件名固定，见下）
```

> 本仓库只带**示例报表**，表名统一用 `demo_*` 演示表。
> 接入现场库时，请把示例 SQL 换成你自己的查询，**保留 YAML 头与输出列名**。

## 报表文件格式

```sql
---
name: 报表名称
description: 一句话描述
params:
  - name: start_date
    label: 起始日期
    type: date          # date / text / number / select
    required: true
  - name: community
    label: 社区
    type: select
    required: false
    default: "%"
    source: "list:A,B,C,D,E,%"
    placeholder: "如: A 或 % 全部"
---

SELECT ... FROM your_table
WHERE 时间字段 BETWEEN to_date(:start_date || ' 00:00:00', 'yyyy-mm-dd hh24:mi:ss')
                   AND to_date(:end_date   || ' 23:59:59', 'yyyy-mm-dd hh24:mi:ss')
  AND 社区字段 LIKE :community
```

- 参数用 `:param_name` 绑定（PreparedStatement，防注入）。
- **社区筛选约定**：参数名固定为 `community`，SQL 里必须真的绑定它
  （如 `AND community LIKE :community`）。后端会按登录账号的数据权限
  强制改写该参数值；受限账号遇到**没绑定** `community` 的报表会直接拒查（fail-closed）。
- 输出列名即报表表头，可写中文别名。
- 只允许 `SELECT`（含 `WITH ... SELECT`），写操作会被拒绝。

## 大屏指标文件（scripts/screen/）

数据大屏的 5 个面板**按文件名取数**，文件名不能改：

| 文件 | 供数面板 | 必需输出列 |
|---|---|---|
| `range_kpi.sql` | 顶部 KPI（门诊人次/在院/入出院/收入） | 社区、门诊人次、对比区间门诊人次、当前在院人数、入院人数、出院人数、门诊收入、住院收入、收入合计 |
| `range_margin.sql` | 毛利与客单价 | 社区、收入合计、门诊收入、住院收入、毛利额、药品销售、对比区间毛利额、对比区间药品销售、门诊人次 |
| `range_daily.sql` | 门诊/收入趋势、出入院趋势 | 日期、社区、门诊人次、门诊收入、住院收入、入院人数、出院人数 |
| `range_hourly.sql` | 分时门诊 | 社区、小时、门诊人次 |
| `range_category.sql` | 费用分类构成 | 社区、分类、金额 |

- 所有大屏指标都按**社区**维度返回，社区列名固定为 `社区`。
- YAML 头里声明了参数（`start_date`/`end_date`/`prev_start_date`/`prev_end_date`）
  的指标，服务端会把所选时间区间绑定进去；不声明参数即为"全量指标"。
- `interval-minutes` 控制该指标的刷新间隔（分钟）。

## 示例用的演示表

示例 SQL 假设库里存在下面两张演示表（**仅为演示，实际请替换成你的表**）：

```sql
-- 门诊就诊明细
CREATE TABLE demo_visit (
  community    VARCHAR2(8),      -- 社区编码
  dept_code    VARCHAR2(16),     -- 科室编码
  patient_id   VARCHAR2(32),     -- 患者标识
  visit_time   DATE,             -- 就诊时间
  category     VARCHAR2(32),     -- 费用分类（西药费/检查费/治疗费…）
  item_name    VARCHAR2(64),     -- 项目名称
  amount       NUMBER(14,2),     -- 金额
  cost         NUMBER(14,2),     -- 成本
  drug_amount  NUMBER(14,2)      -- 其中药品金额
);

-- 住院记录
CREATE TABLE demo_inpatient (
  community      VARCHAR2(8),
  dept_code      VARCHAR2(16),
  patient_id     VARCHAR2(32),
  admit_time     DATE,           -- 入院时间
  discharge_time DATE,           -- 出院时间
  status         VARCHAR2(2),    -- '1' = 在院
  category       VARCHAR2(32),
  amount         NUMBER(14,2),
  cost           NUMBER(14,2),
  drug_amount    NUMBER(14,2)
);
```

## 一键导入

系统内置「SQL 一键导入」：在报表管理页粘贴数据库客户端能直接运行的完整脚本，
系统会自动去分号/剥离 COMMIT、把硬编码日期与 `[参数]`/`&变量` 参数化，
转换后先做只读校验再入库。
