---
name: 示例报表
description: 报表SQL格式示例（请根据实际数据库修改）
group: default
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
---

SELECT
    :start_date AS 起始日期,
    :end_date AS 结束日期,
    t.community AS 社区,
    COUNT(*) AS 数量
FROM your_table t
WHERE t.date_field BETWEEN :start_date AND :end_date
  AND t.community LIKE :community
GROUP BY t.community
ORDER BY t.community
