---
name: 区间分时门诊
description: 区间内按「社区 + 小时」汇总的门诊人次（各日同时段合计）。单日区间时即当日 24 小时曲线
params:
  - name: start_date
    label: 起始日期
    type: date
    required: true
  - name: end_date
    label: 结束日期
    type: date
    required: true
interval-minutes: 10
---

-- 示例大屏指标：分时曲线。
-- 聚合必须下推到 SQL：30天×24小时×多社区会轻易撞上服务端 2000 行上限被静默截断。
-- ★ 输出列名是前端大屏的契约，替换成现场 SQL 时请保留这些列名。
with hrs as (
  select level - 1 as hr from dual connect by level <= 24
),
s as (
  select distinct community from demo_visit
),
per_day_hour as (
  select community,
         to_number(to_char(visit_time, 'hh24')) as hr,
         trunc(visit_time) as stat_date,
         count(distinct patient_id) as rc
    from demo_visit
   where trunc(visit_time) between to_date(:start_date, 'yyyy-mm-dd') and to_date(:end_date, 'yyyy-mm-dd')
   group by community, to_number(to_char(visit_time, 'hh24')), trunc(visit_time)
)
select
  s.community as 社区,
  lpad(to_char(h.hr), 2, '0') as 小时,
  nvl(sum(k.rc), 0) as 门诊人次
from s
cross join hrs h
left join per_day_hour k on k.community = s.community and k.hr = h.hr
group by s.community, h.hr
order by s.community, h.hr
