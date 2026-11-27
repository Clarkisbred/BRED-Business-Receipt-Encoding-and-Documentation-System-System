-- RMS 036: tax calendar and billing aging operational views.

alter table public.tax_calendar_items
  add constraint tax_calendar_frequency_check
  check (frequency in ('Monthly','Quarterly','Semi-Annual','Annual','One-Time')),
  add constraint tax_calendar_status_check
  check (status in ('Pending','Due Soon','Overdue','Filed','Paid','Cancelled'));

create unique index if not exists tax_calendar_name_due_company_idx
  on public.tax_calendar_items(company_id, lower(btrim(name)), due_date);

create or replace view public.project_billing_aging
with (security_invoker=true)
as
select
  id,
  company_id,
  project_id,
  billing_number,
  coalesce(submitted_at::date, billing_date) as submitted_date,
  due_date,
  status,
  gross_amount,
  net_amount,
  collected_amount,
  greatest(net_amount - collected_amount, 0) as outstanding_amount
from public.project_billings;

grant select on public.project_billing_aging to authenticated;
