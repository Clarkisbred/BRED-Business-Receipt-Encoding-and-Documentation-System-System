-- RMS 034: tax expense, petty cash, labor, and safety controls.

alter table public.petty_cash_funds
  add constraint petty_cash_opening_balance_check
  check (opening_balance >= 0);

alter table public.petty_cash_transactions
  add constraint petty_cash_transaction_amount_check
  check (amount >= 0),
  add constraint petty_cash_transaction_type_check
  check (transaction_type in ('Replenishment','Expense','Advance','Liquidation','Adjustment'));

alter table public.safety_records
  add constraint safety_record_severity_check
  check (severity in ('Low','Medium','High','Critical')),
  add constraint safety_record_status_check
  check (status in ('Open','In Progress','Resolved','Closed'));

alter table public.project_attendance
  add constraint attendance_time_check
  check (time_in is null or time_out is null or time_out >= time_in);

alter table public.project_labor_costs
  add constraint labor_cost_period_check
  check (period_end >= period_start),
  add constraint labor_cost_total_check
  check (total_pay = regular_pay + overtime_pay + holiday_pay);

alter table public.project_workers
  add constraint project_worker_identity_check
  check (btrim(worker_name) <> '');

alter table public.withholding_tax_issued
  add constraint withholding_tax_issued_status_check
  check (status in ('Draft','Issued','Cancelled')),
  add constraint withholding_tax_issued_values_check
  check (tax_base >= 0 and rate >= 0 and rate <= 100 and amount_withheld >= 0);

create unique index if not exists petty_cash_fund_id_company_scope_idx
  on public.petty_cash_funds(id, company_id);
create unique index if not exists project_worker_id_company_scope_idx
  on public.project_workers(id, company_id);

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'petty_cash_transaction_fund_scope_fkey'
      and conrelid = 'public.petty_cash_transactions'::regclass
  ) then
    alter table public.petty_cash_transactions
      drop constraint if exists petty_cash_transactions_fund_id_fkey,
      add constraint petty_cash_transaction_fund_scope_fkey
      foreign key (fund_id, company_id)
      references public.petty_cash_funds(id, company_id)
      on delete cascade;
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'project_attendance_worker_scope_fkey'
      and conrelid = 'public.project_attendance'::regclass
  ) then
    alter table public.project_attendance
      drop constraint if exists project_attendance_worker_id_fkey,
      add constraint project_attendance_worker_scope_fkey
      foreign key (worker_id, company_id)
      references public.project_workers(id, company_id)
      on delete restrict;
  end if;
end $$;
