---
name: 示例报表-社区收入汇总
description: 演示报表格式：按社区汇总所选区间的就诊人次、收入、成本与毛利
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

-- 示例报表：演示「分组汇总 + 中文列别名」的写法。
SELECT
    v.community                 AS 社区,
    count(distinct v.patient_id) AS 就诊人次,
    sum(v.amount)               AS 收入,
    sum(v.cost)                 AS 成本,
    sum(v.amount) - sum(v.cost) AS 毛利
FROM demo_visit v
WHERE v.visit_time BETWEEN to_date(:start_date || ' 00:00:00', 'yyyy-mm-dd hh24:mi:ss')
                       AND to_date(:end_date   || ' 23:59:59', 'yyyy-mm-dd hh24:mi:ss')
  AND v.community LIKE :community
GROUP BY v.community
ORDER BY v.community
