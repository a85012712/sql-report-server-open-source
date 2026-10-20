---
name: 区间费用分类构成
description: 所选区间内各费用分类的收入构成（按社区），如西药费/检查费/治疗费等
params:
  - name: start_date
    label: 起始日期
    type: date
    required: true
  - name: end_date
    label: 结束日期
    type: date
    required: true
interval-minutes: 60
---

-- 示例大屏指标：费用分类构成（前端取 TOP7 + 其他）。
-- ★ 输出列名是前端大屏的契约，替换成现场 SQL 时请保留这些列名。
select
  community as 社区,
  category as 分类,
  sum(amount) as 金额
from (
  select community, category, amount
    from demo_visit
   where trunc(visit_time) between to_date(:start_date, 'yyyy-mm-dd') and to_date(:end_date, 'yyyy-mm-dd')
  union all
  select community, category, amount
    from demo_inpatient
   where trunc(admit_time) between to_date(:start_date, 'yyyy-mm-dd') and to_date(:end_date, 'yyyy-mm-dd')
)
where category is not null
group by community, category
order by community, 金额 desc
