---
name: 区间经营指标
description: 按社区：所选区间门诊人次（整段去重）、上一等长区间对比、门诊/住院收入、期末在院与区间入出院
params:
  - name: start_date
    label: 起始日期
    type: date
    required: true
  - name: end_date
    label: 结束日期
    type: date
    required: true
  - name: prev_start_date
    label: 上一等长区间起始
    type: date
    required: true
  - name: prev_end_date
    label: 上一等长区间结束
    type: date
    required: true
interval-minutes: 10
---

-- 示例大屏指标（演示表 demo_visit / demo_inpatient，见 scripts/README.md）。
-- ★ 输出列名是前端大屏的契约，替换成现场 SQL 时请保留这些列名。
--
-- 「门诊人次」用整段 count(distinct 患者) 去重，不是每日去重数相加：
-- 同一患者区间内多次就诊只算 1 人次。
select
  s.community as 社区,
  nvl(v.rc, 0) as 门诊人次,
  nvl(v.rc_prev, 0) as 对比区间门诊人次,
  nvl(h.zy_now, 0) as 当前在院人数,
  nvl(h.ry, 0) as 入院人数,
  nvl(h.cy, 0) as 出院人数,
  nvl(v.amt, 0) as 门诊收入,
  nvl(h.amt, 0) as 住院收入,
  nvl(v.amt, 0) + nvl(h.amt, 0) as 收入合计
from (
  select community from demo_visit
  union
  select community from demo_inpatient
) s
left join (
  select community,
         count(distinct case when trunc(visit_time)
                 between to_date(:start_date, 'yyyy-mm-dd') and to_date(:end_date, 'yyyy-mm-dd')
                 then patient_id end) as rc,
         count(distinct case when trunc(visit_time)
                 between to_date(:prev_start_date, 'yyyy-mm-dd') and to_date(:prev_end_date, 'yyyy-mm-dd')
                 then patient_id end) as rc_prev,
         sum(case when trunc(visit_time)
                 between to_date(:start_date, 'yyyy-mm-dd') and to_date(:end_date, 'yyyy-mm-dd')
                 then amount else 0 end) as amt
    from demo_visit
   group by community
) v on v.community = s.community
left join (
  select community,
         count(distinct case when status = '1' then patient_id end) as zy_now,
         count(distinct case when trunc(admit_time)
                 between to_date(:start_date, 'yyyy-mm-dd') and to_date(:end_date, 'yyyy-mm-dd')
                 then patient_id end) as ry,
         count(distinct case when trunc(discharge_time)
                 between to_date(:start_date, 'yyyy-mm-dd') and to_date(:end_date, 'yyyy-mm-dd')
                 then patient_id end) as cy,
         sum(case when trunc(admit_time)
                 between to_date(:start_date, 'yyyy-mm-dd') and to_date(:end_date, 'yyyy-mm-dd')
                 then amount else 0 end) as amt
    from demo_inpatient
   group by community
) h on h.community = s.community
order by s.community
