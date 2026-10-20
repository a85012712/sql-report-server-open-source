---
name: 区间毛利与客单价
description: 按社区：所选区间收入、毛利额、药品销售与门诊人次（含上一等长区间毛利/药销，用于毛利率环比）
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
interval-minutes: 60
---

-- 示例大屏指标：毛利率 = 毛利额 / 药品销售（聚合后由服务端重算，口径一致）。
-- ★ 输出列名是前端大屏的契约，替换成现场 SQL 时请保留这些列名。
with base as (
  select community, visit_time as t, patient_id, 'mz' as kind, amount, cost, drug_amount
    from demo_visit
  union all
  select community, admit_time as t, patient_id, 'zy' as kind, amount, cost, drug_amount
    from demo_inpatient
),
cur as (
  select community,
         sum(amount) as 收入合计,
         sum(case when kind = 'mz' then amount else 0 end) as 门诊收入,
         sum(case when kind = 'zy' then amount else 0 end) as 住院收入,
         sum(amount - cost) as 毛利额,
         sum(drug_amount) as 药品销售,
         count(distinct case when kind = 'mz' then patient_id end) as 门诊人次
    from base
   where trunc(t) between to_date(:start_date, 'yyyy-mm-dd') and to_date(:end_date, 'yyyy-mm-dd')
   group by community
),
prev as (
  select community,
         sum(amount - cost) as 对比区间毛利额,
         sum(drug_amount) as 对比区间药品销售
    from base
   where trunc(t) between to_date(:prev_start_date, 'yyyy-mm-dd') and to_date(:prev_end_date, 'yyyy-mm-dd')
   group by community
)
select
  s.community as 社区,
  nvl(c.收入合计, 0) as 收入合计,
  nvl(c.门诊收入, 0) as 门诊收入,
  nvl(c.住院收入, 0) as 住院收入,
  nvl(c.毛利额, 0) as 毛利额,
  nvl(c.药品销售, 0) as 药品销售,
  nvl(p.对比区间毛利额, 0) as 对比区间毛利额,
  nvl(p.对比区间药品销售, 0) as 对比区间药品销售,
  nvl(c.门诊人次, 0) as 门诊人次
from (
  select community from demo_visit
  union
  select community from demo_inpatient
) s
left join cur c on c.community = s.community
left join prev p on p.community = s.community
order by s.community
