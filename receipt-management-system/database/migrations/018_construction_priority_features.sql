-- RMS 018: Philippine construction priority features.
-- Every table is company-scoped, uses numeric money values, and relies on RLS.

create table if not exists public.company_licenses (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  license_type text not null,
  license_number text not null,
  category text,
  classification text,
  valid_from date,
  valid_to date not null,
  renewal_date date,
  document_path text,
  created_at timestamptz not null default now()
);

create table if not exists public.company_permits (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  permit_type text not null,
  reference_number text not null,
  valid_from date,
  valid_to date not null,
  document_path text,
  created_at timestamptz not null default now()
);

create table if not exists public.professional_credentials (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  user_id uuid references profiles(id) on delete set null,
  prc_license_number text not null,
  professional_seal_number text,
  role_on_project text,
  valid_to date not null,
  document_path text,
  created_at timestamptz not null default now()
);

create table if not exists public.project_securities (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  security_type text not null,
  amount numeric(18,2) not null default 0,
  issuer text,
  reference_number text,
  valid_from date,
  valid_to date not null,
  status text not null default 'Active',
  document_path text,
  created_at timestamptz not null default now()
);

create table if not exists public.project_insurance (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  insurance_type text not null,
  amount numeric(18,2) not null default 0,
  issuer text,
  reference_number text,
  valid_from date,
  valid_to date not null,
  status text not null default 'Active',
  document_path text,
  created_at timestamptz not null default now()
);

create table if not exists public.project_time_extensions (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  days_requested integer not null,
  days_approved integer not null default 0,
  approval_status text not null default 'Pending',
  approval_date date,
  original_completion_date date not null,
  revised_completion_date date,
  reason text
);

create table if not exists public.project_liquidated_damages (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  rate_per_day numeric(18,2) not null,
  cap_amount numeric(18,2),
  approved boolean not null default false
);

create table if not exists public.project_material_tests (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  test_type text not null,
  sample_reference text,
  test_date date not null,
  location text,
  result text,
  pass_fail text not null,
  laboratory text,
  attachment_path text
);

create table if not exists public.project_warranties (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  warranty_type text not null,
  start_date date not null,
  end_date date not null,
  certificate_path text
);

create table if not exists public.advance_payment_recoveries (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  advance_amount numeric(18,2) not null,
  recovery_method text not null,
  recovered_amount numeric(18,2) not null default 0
);

create table if not exists public.retention_ledger (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  billing_id uuid,
  held_amount numeric(18,2) not null default 0,
  released_amount numeric(18,2) not null default 0,
  effective_date date not null,
  reason text
);

create table if not exists public.withholding_tax_received (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  billing_id uuid,
  client_name text not null,
  tax_base numeric(18,2) not null,
  rate numeric(9,6) not null,
  amount_withheld numeric(18,2) not null,
  period_start date not null,
  certificate_reference text,
  status text not null default 'Not Yet Received',
  date_received date
);

create table if not exists public.withholding_tax_issued (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  supplier_id uuid references suppliers(id) on delete restrict,
  tax_base numeric(18,2) not null,
  rate numeric(9,6) not null,
  amount_withheld numeric(18,2) not null,
  period_start date not null,
  certificate_reference text,
  status text not null default 'Draft'
);

create table if not exists public.subcontracts (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  supplier_id uuid not null references suppliers(id) on delete restrict,
  scope text not null,
  subcontract_amount numeric(18,2) not null,
  start_date date,
  end_date date,
  status text not null default 'Active'
);

create table if not exists public.subcontract_items (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  subcontract_id uuid not null references subcontracts(id) on delete cascade,
  description text not null,
  quantity numeric(18,4) not null default 0,
  unit text,
  unit_price numeric(18,2) not null default 0
);

create table if not exists public.subcontract_payments (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  subcontract_id uuid not null references subcontracts(id) on delete cascade,
  certified_amount numeric(18,2) not null default 0,
  paid_amount numeric(18,2) not null default 0,
  retained_amount numeric(18,2) not null default 0,
  withholding_amount numeric(18,2) not null default 0,
  payment_date date
);

create table if not exists public.equipment (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  name text not null,
  equipment_type text,
  code text,
  ownership text not null default 'Owned',
  owner_supplier_id uuid references suppliers(id) on delete set null,
  rate numeric(18,2) not null default 0,
  rate_unit text not null default 'day',
  status text not null default 'Active'
);

create table if not exists public.equipment_usage (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  equipment_id uuid not null references equipment(id) on delete cascade,
  project_id uuid not null references projects(id) on delete cascade,
  usage_date date not null,
  quantity numeric(18,4) not null default 0,
  fuel_consumed numeric(18,4) not null default 0,
  operator_name text,
  approved boolean not null default false
);

create table if not exists public.equipment_maintenance (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  equipment_id uuid not null references equipment(id) on delete cascade,
  maintenance_date date not null,
  cost numeric(18,2) not null default 0,
  description text,
  approved boolean not null default false
);

create table if not exists public.cash_advances (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid references projects(id) on delete set null,
  issued_to text not null,
  amount numeric(18,2) not null,
  issued_date date not null,
  purpose text,
  liquidated_amount numeric(18,2) not null default 0,
  authorized_override_reason text
);

create table if not exists public.safety_records (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  record_date date not null,
  location text,
  description text not null,
  severity text not null,
  corrective_action text,
  status text not null default 'Open'
);

alter table public.company_licenses enable row level security;
alter table public.company_permits enable row level security;
alter table public.professional_credentials enable row level security;
alter table public.project_securities enable row level security;
alter table public.project_insurance enable row level security;
alter table public.project_time_extensions enable row level security;
alter table public.project_liquidated_damages enable row level security;
alter table public.project_material_tests enable row level security;
alter table public.project_warranties enable row level security;
alter table public.advance_payment_recoveries enable row level security;
alter table public.retention_ledger enable row level security;
alter table public.withholding_tax_received enable row level security;
alter table public.withholding_tax_issued enable row level security;
alter table public.subcontracts enable row level security;
alter table public.subcontract_items enable row level security;
alter table public.subcontract_payments enable row level security;
alter table public.equipment enable row level security;
alter table public.equipment_usage enable row level security;
alter table public.equipment_maintenance enable row level security;
alter table public.cash_advances enable row level security;
alter table public.safety_records enable row level security;

do $$
declare v_table text;
declare v_policy text;
begin
  foreach v_table in array array[
    'company_licenses','company_permits','professional_credentials','project_securities',
    'project_insurance','project_time_extensions','project_liquidated_damages',
    'project_material_tests','project_warranties','advance_payment_recoveries',
    'retention_ledger','withholding_tax_received','withholding_tax_issued','subcontracts',
    'subcontract_items','subcontract_payments','equipment','equipment_usage',
    'equipment_maintenance','cash_advances','safety_records'
  ] loop
    execute format('drop policy if exists %I_read on public.%I', v_table, v_table);
    execute format('drop policy if exists %I_write on public.%I', v_table, v_table);
    execute format('create policy %I_read on public.%I for select to authenticated using (is_active_user() and (is_creator() or company_id = current_company_id()))', v_table, v_table);
    execute format('create policy %I_write on public.%I for all to authenticated using (is_active_user() and (is_creator() or company_id = current_company_id())) with check (is_active_user() and (is_creator() or company_id = current_company_id()))', v_table, v_table);
    execute format('grant select, insert, update, delete on public.%I to authenticated', v_table);
  end loop;
end $$;
