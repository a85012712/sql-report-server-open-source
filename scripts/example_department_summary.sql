---
name: 示例报表-科室工作量汇总
description: 演示报表格式：按社区+科室汇总门诊工作量与收入
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
  - name: min_amount
    label: 最低收入
    type: number
    required: false
    default: "0"
    placeholder: "留空则不筛选"
---

-- 示例报表：演示「数值型参数」的写法（:min_amount 直接当数字用）。
SELECT
    v.community                  AS 社区,
    v.dept_code                  AS 科室,
    count(distinct v.patient_id) AS 就诊人次,
    count(*)                     AS 项目笔数,
    sum(v.amount)                AS 收入,
    round(sum(v.amount) / nullif(count(distinct v.patient_id), 0), 2) AS 人均费用
FROM demo_visit v
WHERE v.visit_time BETWEEN to_date(:start_date || ' 00:00:00', 'yyyy-mm-dd hh24:mi:ss')
                       AND to_date(:end_date   || ' 23:59:59', 'yyyy-mm-dd hh24:mi:ss')
  AND v.community LIKE :community
GROUP BY v.community, v.dept_code
HAVING sum(v.amount) >= to_number(:min_amount)
ORDER BY v.community, 收入 DESC
