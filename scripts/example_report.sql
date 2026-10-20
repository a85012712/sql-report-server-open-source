---
name: 示例报表-门诊就诊明细
description: 演示报表格式：按日期与社区查询门诊就诊明细（请按现场库表结构改写）
params:
  - name: start_date
    label: 起始日期
    type: date
    required: true
  - name: end_date
    label: 结束日期
    type: date
    required: true
  - name: community
    label: 社区
    type: select
    required: false
    default: "%"
    source: "list:A,B,C,D,E,%"
    placeholder: "如: A 或 % 全部"
---

-- 示例报表：演示「参数 + 社区筛选 + 明细输出」的写法。
-- ★ 社区参数必须真的绑进 SQL（受限账号靠它做数据隔离，不绑定会被拒查）。
SELECT
    v.community      AS 社区,
    v.dept_code      AS 科室,
    v.visit_time     AS 就诊时间,
    v.patient_id     AS 患者标识,
    v.category       AS 费用分类,
    v.item_name      AS 项目名称,
    v.amount         AS 金额,
    v.cost           AS 成本,
    v.amount - v.cost AS 毛利
FROM demo_visit v
WHERE v.visit_time BETWEEN to_date(:start_date || ' 00:00:00', 'yyyy-mm-dd hh24:mi:ss')
                       AND to_date(:end_date   || ' 23:59:59', 'yyyy-mm-dd hh24:mi:ss')
  AND v.community LIKE :community
ORDER BY v.visit_time
