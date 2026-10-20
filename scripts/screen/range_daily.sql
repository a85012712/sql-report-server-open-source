---
name: 区间逐日趋势
description: 所选区间内各社区逐日门诊人次/门诊收入/住院收入/入院/出院（日历骨架由区间天数驱动）
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

-- 示例大屏指标：逐日序列（趋势图用）。
-- ★ 输出列名是前端大屏的契约，替换成现场 SQL 时请保留这些列名。
with days as (
  select to_date(:start_date, 'yyyy-mm-dd') + level - 1 as stat_date
    from dual
  connect by level <= to_date(:end_date, 'yyyy-mm-dd') - to_date(:start_date, 'yyyy-mm-dd') + 1
),
s as (
  select community from demo_visit
  union
  select community from demo_inpatient
),
mz as (
  select community, trunc(visit_time) as stat_date,
         count(distinct patient_id) as rc, sum(amount) as sr
    from demo_visit
   where trunc(visit_time) between to_date(:start_date, 'yyyy-mm-dd') and to_date(:end_date, 'yyyy-mm-dd')
   group by community, trunc(visit_time)
),
ry as (
  select community, trunc(admit_time) as stat_date,
         count(distinct patient_id) as ry, sum(amount) as sr
    from demo_inpatient
   where trunc(admit_time) between to_date(:start_date, 'yyyy-mm-dd') and to_date(:end_date, 'yyyy-mm-dd')
   group by community, trunc(admit_time)
),
cy as (
  select community, trunc(discharge_time) as stat_date,
         count(distinct patient_id) as cy
    from demo_inpatient
   where trunc(discharge_time) between to_date(:start_date, 'yyyy-mm-dd') and to_date(:end_date, 'yyyy-mm-dd')
   group by community, trunc(discharge_time)
)
select
  to_char(d.stat_date, 'yyyy-mm-dd') as 日期,
  s.community as 社区,
  nvl(m.rc, 0) as 门诊人次,
  nvl(m.sr, 0) as 门诊收入,
  nvl(r.sr, 0) as 住院收入,
  nvl(r.ry, 0) as 入院人数,
  nvl(c.cy, 0) as 出院人数
from days d
cross join s
left join mz m on m.community = s.community and m.stat_date = d.stat_date
left join ry r on r.community = s.community and r.stat_date = d.stat_date
left join cy c on c.community = s.community and c.stat_date = d.stat_date
order by s.community, d.stat_date
