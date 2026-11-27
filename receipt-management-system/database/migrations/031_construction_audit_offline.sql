-- RMS 031: construction audit trail and offline queue safeguards.

create table if not exists public.construction_audit_log (
  id bigint generated always as identity primary key,
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  actor_user_id uuid references profiles(id) on delete set null,
  table_name text not null,
  record_id text,
  action text not null check (action in ('INSERT','UPDATE','DELETE')),
  before_data jsonb,
  after_data jsonb,
  at timestamptz not null default now()
);

alter table public.construction_audit_log enable row level security;
drop policy if exists construction_audit_read on public.construction_audit_log;
create policy construction_audit_read on public.construction_audit_log
  for select to authenticated
  using (is_active_user() and is_admin()
         and (is_creator() or company_id = current_company_id()));
drop policy if exists construction_audit_insert on public.construction_audit_log;
create policy construction_audit_insert on public.construction_audit_log
  for insert to authenticated
  with check (is_active_user() and (is_creator() or company_id = current_company_id()));
grant select, insert on public.construction_audit_log to authenticated;

create or replace function audit_construction_change()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_row jsonb := to_jsonb(case when tg_op = 'DELETE' then old else new end);
  v_company_id uuid := (v_row->>'company_id')::uuid;
begin
  insert into public.construction_audit_log(
    company_id, actor_user_id, table_name, record_id, action, before_data, after_data
  )
  values(
    v_company_id,
    auth.uid(),
    tg_table_name,
    case when v_row ? 'id' then v_row->>'id' else null end,
    tg_op,
    case when tg_op in ('UPDATE','DELETE') then to_jsonb(old) else null end,
    case when tg_op in ('INSERT','UPDATE') then to_jsonb(new) else null end
  );
  if tg_op = 'DELETE' then return old; end if;
  return new;
end $$;

alter table public.offline_queue
  add column if not exists sync_attempts integer not null default 0,
  add column if not exists last_error text,
  add column if not exists server_record_id uuid;

alter table public.offline_queue
  drop constraint if exists offline_queue_status_check,
  add constraint offline_queue_status_check
  check (status in ('Queued','Syncing','Synced','Failed','Cancelled'));

alter table public.offline_queue
  add constraint offline_queue_sync_attempt_check
  check (sync_attempts >= 0);

do $$
declare v_table text;
begin
  foreach v_table in array array[
    'project_contracts','project_boq_items','project_variation_orders',
    'project_accomplishments','project_milestones','project_schedule_snapshots',
    'project_billings','project_billing_lines','project_billing_deductions',
    'project_billing_collections','project_expense_ledger','project_payables',
    'project_payments','project_pnl_snapshots','purchase_requisitions',
    'purchase_orders','goods_receipts','material_stock','material_stock_movements',
    'project_workers','project_attendance','project_labor_costs',
    'equipment_allocations','equipment_fuel_logs','equipment_cost_postings',
    'project_map_markers','project_risks','project_risk_updates',
    'project_quality_ncr','project_corrective_actions','project_closeout_checklist',
    'project_punch_items','project_handover_records','report_runs','generated_documents'
  ] loop
    execute format('drop trigger if exists trg_%I_audit on public.%I', v_table, v_table);
    execute format('create trigger trg_%I_audit
      after insert or update or delete on public.%I
      for each row execute function audit_construction_change()', v_table, v_table);
  end loop;

  foreach v_table in array array[
    'project_contracts','project_boq_items','project_variation_orders',
    'project_variation_items','project_accomplishments','project_milestones',
    'project_schedule_snapshots','project_billings','project_billing_lines',
    'project_billing_deductions','project_billing_collections',
    'project_billing_attachments','project_expense_ledger','project_payables',
    'project_payments','project_cash_movements','project_pnl_snapshots',
    'purchase_requisitions','purchase_requisition_items','purchase_orders',
    'purchase_order_items','goods_receipts','goods_receipt_items',
    'material_stock','material_stock_movements','labor_wage_configurations',
    'project_workers','project_attendance','project_labor_costs',
    'equipment_allocations','equipment_fuel_logs','equipment_cost_postings',
    'equipment_down_time','project_map_markers','project_risks',
    'project_risk_updates','project_quality_ncr','project_corrective_actions',
    'project_closeout_checklist','project_punch_items','project_handover_records',
    'report_runs','generated_documents','offline_queue'
  ] loop
    execute format('drop trigger if exists trg_%I_company_scope on public.%I', v_table, v_table);
    execute format('create trigger trg_%I_company_scope before insert or update on public.%I
      for each row execute function enforce_construction_company_scope()', v_table, v_table);
  end loop;
end $$;
