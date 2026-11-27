-- RMS 020: construction financial, company-scope, and offline safeguards.

do $$
begin
  alter table public.project_liquidated_damages
    add constraint project_liquidated_damages_rate_check
    check (rate_per_day >= 0),
    add constraint project_liquidated_damages_cap_check
    check (cap_amount is null or cap_amount >= 0);
exception when duplicate_object then null;
end $$;

do $$
begin
  alter table public.advance_payment_recoveries
    add constraint advance_recovery_amount_check
    check (advance_amount >= 0 and recovered_amount >= 0 and recovered_amount <= advance_amount);
exception when duplicate_object then null;
end $$;

do $$
begin
  alter table public.retention_ledger
    add constraint retention_amount_check
    check (held_amount >= 0 and released_amount >= 0 and released_amount <= held_amount);
exception when duplicate_object then null;
end $$;

do $$
begin
  alter table public.withholding_tax_received
    add constraint withholding_received_amount_check
    check (tax_base >= 0 and rate >= 0 and amount_withheld >= 0),
    add constraint withholding_received_status_check
    check (status in ('Not Yet Received','Received','Verified'));
exception when duplicate_object then null;
end $$;

do $$
begin
  alter table public.withholding_tax_issued
    add constraint withholding_issued_amount_check
    check (tax_base >= 0 and rate >= 0 and amount_withheld >= 0),
    add constraint withholding_issued_status_check
    check (status in ('Draft','Issued','Cancelled'));
exception when duplicate_object then null;
end $$;

do $$
begin
  alter table public.subcontract_payments
    add constraint subcontract_payment_amount_check
    check (certified_amount >= 0 and paid_amount >= 0 and retained_amount >= 0
           and withholding_amount >= 0);
exception when duplicate_object then null;
end $$;

do $$
begin
  alter table public.cash_advances
    add constraint cash_advance_amount_check
    check (amount >= 0 and liquidated_amount >= 0 and liquidated_amount <= amount
           or liquidated_amount > amount and authorized_override_reason is not null);
exception when duplicate_object then null;
end $$;

create index if not exists company_licenses_expiry_idx
  on public.company_licenses(company_id, valid_to);
create index if not exists company_permits_expiry_idx
  on public.company_permits(company_id, valid_to);
create index if not exists project_securities_expiry_idx
  on public.project_securities(company_id, valid_to);
create index if not exists project_insurance_expiry_idx
  on public.project_insurance(company_id, valid_to);
create index if not exists project_warranties_expiry_idx
  on public.project_warranties(company_id, end_date);
create index if not exists tax_calendar_due_idx
  on public.tax_calendar_items(company_id, due_date);
create index if not exists offline_queue_company_status_idx
  on public.offline_queue(company_id, status, queued_at);

create or replace function enforce_construction_company_scope()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_project_company uuid;
  v_supplier_company uuid;
begin
  if to_jsonb(new) ? 'project_id'
     and (to_jsonb(new)->>'project_id') is not null then
    select company_id into v_project_company
    from projects where id = (to_jsonb(new)->>'project_id')::uuid;
    if v_project_company is null or v_project_company <> new.company_id then
      raise exception 'Project and construction record must belong to the same company.';
    end if;
  end if;
  if to_jsonb(new) ? 'supplier_id' then
    select company_id into v_supplier_company
    from suppliers where id = (to_jsonb(new)->>'supplier_id')::uuid;
    if v_supplier_company is not null and v_supplier_company <> new.company_id then
      raise exception 'Supplier and construction record must belong to the same company.';
    end if;
  end if;
  return new;
end $$;

do $$
declare v_table text;
begin
  foreach v_table in array array[
    'company_licenses','company_permits','professional_credentials','project_securities',
    'project_insurance','project_time_extensions','project_liquidated_damages',
    'project_material_tests','project_warranties','advance_payment_recoveries',
    'retention_ledger','withholding_tax_received','withholding_tax_issued','subcontracts',
    'subcontract_items','subcontract_payments','equipment','equipment_usage',
    'equipment_maintenance','cash_advances','safety_records','bids','cost_estimates',
    'cost_estimate_items','project_contract_amendments','project_price_adjustments',
    'project_activities','project_inspections','project_documents','project_closeout_records',
    'billing_attachment_requirements','billing_attachment_overrides','tax_calendar_items',
    'petty_cash_funds','petty_cash_transactions','safety_programs',
    'safety_toolbox_meetings','offline_queue'
  ] loop
    execute format('drop trigger if exists trg_%I_company_scope on public.%I', v_table, v_table);
    execute format('create trigger trg_%I_company_scope before insert or update on public.%I
      for each row execute function enforce_construction_company_scope()', v_table, v_table);

    execute format('drop policy if exists %I_write on public.%I', v_table, v_table);
    execute format('create policy %I_write on public.%I for all to authenticated
      using (is_active_user() and is_admin() and (is_creator() or company_id = current_company_id()))
      with check (is_active_user() and is_admin() and (is_creator() or company_id = current_company_id()))',
      v_table, v_table);
  end loop;
end $$;
