-- RMS 023: construction billing, collections and receivables.

create table if not exists public.project_billings (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  contract_id uuid references project_contracts(id) on delete set null,
  billing_number text not null,
  billing_date date not null,
  period_start date,
  period_end date,
  due_date date,
  submitted_at timestamptz,
  gross_amount numeric(18,2) not null default 0,
  previous_billed numeric(18,2) not null default 0,
  current_accomplishment numeric(18,2) not null default 0,
  retention_rate numeric(9,6) not null default 0,
  retention_amount numeric(18,2) not null default 0,
  advance_recovery numeric(18,2) not null default 0,
  liquidated_damages numeric(18,2) not null default 0,
  withholding_amount numeric(18,2) not null default 0,
  other_deductions numeric(18,2) not null default 0,
  net_amount numeric(18,2) not null default 0,
  collected_amount numeric(18,2) not null default 0,
  status text not null default 'Draft',
  override_reason text,
  override_authorized_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  constraint project_billing_status_check
    check (status in ('Draft','Submitted','Under Review','Approved','Certified','Paid','Cancelled','Overdue')),
  constraint project_billing_amount_check
    check (gross_amount >= 0 and previous_billed >= 0 and current_accomplishment >= 0
           and retention_amount >= 0 and advance_recovery >= 0
           and liquidated_damages >= 0 and withholding_amount >= 0
           and other_deductions >= 0 and net_amount >= 0 and collected_amount >= 0)
);

create table if not exists public.project_billing_lines (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  billing_id uuid not null references project_billings(id) on delete cascade,
  boq_item_id uuid references project_boq_items(id) on delete set null,
  description text not null,
  previous_quantity numeric(18,4) not null default 0,
  current_quantity numeric(18,4) not null default 0,
  cumulative_quantity numeric(18,4) not null default 0,
  unit text,
  unit_price numeric(18,2) not null default 0,
  amount numeric(18,2) not null default 0,
  constraint project_billing_line_quantity_check
    check (previous_quantity >= 0 and current_quantity >= 0
           and cumulative_quantity >= 0 and unit_price >= 0 and amount >= 0)
);

create table if not exists public.project_billing_deductions (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  billing_id uuid not null references project_billings(id) on delete cascade,
  deduction_type text not null,
  description text,
  amount numeric(18,2) not null default 0,
  approved boolean not null default false,
  approved_by uuid references profiles(id) on delete set null,
  approval_reason text,
  constraint project_billing_deduction_amount_check
    check (amount >= 0),
  constraint project_billing_deduction_type_check
    check (deduction_type in ('Retention','Advance Recovery','Liquidated Damages',
                              'Tax Withholding','Back Charge','Other Authorized'))
);

create table if not exists public.project_billing_collections (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  billing_id uuid not null references project_billings(id) on delete cascade,
  collection_date date not null,
  amount numeric(18,2) not null,
  withholding_amount numeric(18,2) not null default 0,
  reference_number text,
  bank_reference text,
  remarks text,
  created_at timestamptz not null default now(),
  constraint project_billing_collection_amount_check
    check (amount >= 0 and withholding_amount >= 0)
);

create table if not exists public.project_billing_attachments (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  billing_id uuid not null references project_billings(id) on delete cascade,
  attachment_name text not null,
  document_path text not null,
  attachment_type text,
  is_required boolean not null default false,
  created_at timestamptz not null default now()
);

create unique index if not exists project_billing_number_company_idx
  on public.project_billings(company_id, project_id, lower(btrim(billing_number)));
create index if not exists project_billing_due_idx
  on public.project_billings(company_id, due_date);
create index if not exists project_billing_status_idx
  on public.project_billings(company_id, status);
create index if not exists project_billing_collection_date_idx
  on public.project_billing_collections(company_id, collection_date);

do $$
declare v_table text;
begin
  foreach v_table in array array[
    'project_billings','project_billing_lines','project_billing_deductions',
    'project_billing_collections','project_billing_attachments'
  ] loop
    execute format('alter table public.%I enable row level security', v_table);
    execute format('drop policy if exists %I_read on public.%I', v_table, v_table);
    execute format('drop policy if exists %I_write on public.%I', v_table, v_table);
    execute format('create policy %I_read on public.%I for select to authenticated
      using (is_active_user() and (is_creator() or company_id = current_company_id()))', v_table, v_table);
    execute format('create policy %I_write on public.%I for all to authenticated
      using (is_active_user() and is_admin() and (is_creator() or company_id = current_company_id()))
      with check (is_active_user() and is_admin() and (is_creator() or company_id = current_company_id()))',
      v_table, v_table);
    execute format('grant select, insert, update, delete on public.%I to authenticated', v_table);
  end loop;
end $$;
