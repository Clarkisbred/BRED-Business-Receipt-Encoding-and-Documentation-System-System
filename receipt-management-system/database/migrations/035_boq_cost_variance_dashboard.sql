-- RMS 035: BOQ budget-versus-actual dashboard aggregate.

create or replace view public.project_boq_cost_variance
with (security_invoker=true)
as
select
  company_id,
  project_id,
  sum(estimated_cost) as estimated_total,
  sum(actual_cost) as actual_total,
  sum(actual_cost) - sum(estimated_cost) as variance_peso,
  case
    when sum(estimated_cost) = 0 then null
    else round((sum(actual_cost) - sum(estimated_cost))
               / nullif(abs(sum(estimated_cost)), 0) * 100, 4)
  end as variance_percent,
  case
    when sum(actual_cost) - sum(estimated_cost) > 0 then 'Over budget'
    when sum(actual_cost) - sum(estimated_cost) < 0 then 'Under budget'
    else 'On budget'
  end as status
from public.project_boq_items
group by company_id, project_id;

grant select on public.project_boq_cost_variance to authenticated;
