-- RMS 019: remaining construction roadmap foundations.

create table if not exists public.bids (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  bid_reference text not null,
  agency_or_client text not null,
  project_description text,
  abc_amount numeric(18,2) not null default 0,
  bid_amount numeric(18,2),
  bid_opening_date date,
  result text not null default 'Submitted',
  project_id uuid references projects(id) on delete set null,
  notice_of_award_date date,
  contract_signing_date date,
  notice_to_proceed_date date,
  contract_duration_days integer,
  created_at timestamptz not null default now()
);

create table if not exists public.cost_estimates (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  bid_id uuid references bids(id) on delete set null,
  project_id uuid references projects(id) on delete set null,
  name text not null,
  original_budget numeric(18,2) not null default 0,
  approved boolean not null default false,
  approved_at timestamptz,
  created_at timestamptz not null default now()
);

create table if not exists public.cost_estimate_items (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  estimate_id uuid not null references cost_estimates(id) on delete cascade,
  boq_code text,
  description text not null,
  quantity numeric(18,4) not null default 0,
  unit text,
  materials numeric(18,2) not null default 0,
  labor numeric(18,2) not null default 0,
  equipment numeric(18,2) not null default 0,
  overhead numeric(18,2) not null default 0,
  contingency numeric(18,2) not null default 0,
  profit numeric(18,2) not null default 0,
  taxes numeric(18,2) not null default 0
);

create table if not exists public.project_contract_amendments (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  amendment_number text not null,
  description text not null,
  amount_effect numeric(18,2) not null default 0,
  duration_effect_days integer not null default 0,
  approval_status text not null default 'Pending',
  document_path text
);

create table if not exists public.project_price_adjustments (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  formula text not null,
  effective_from date not null,
  effective_to date,
  approval_status text not null default 'Pending'
);

create table if not exists public.project_activities (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  activity_name text not null,
  planned_start date,
  planned_finish date,
  planned_progress numeric(9,4) not null default 0,
  actual_progress numeric(9,4) not null default 0,
  approved_accomplishment boolean not null default false
);

create table if not exists public.project_inspections (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  request_date date not null,
  result text,
  deficiency_notice text,
  corrective_action text,
  closure_date date,
  attachment_path text
);

create table if not exists public.project_documents (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  category text not null,
  document_name text not null,
  version text,
  document_date date,
  document_path text
);

create table if not exists public.project_closeout_records (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  certificate_type text not null,
  certificate_date date,
  signatories text,
  attachment_path text
);

create table if not exists public.billing_attachment_requirements (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  contract_type text not null,
  requirement_name text not null,
  required boolean not null default true
);

create table if not exists public.billing_attachment_overrides (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  billing_id uuid not null,
  authorized_by uuid references profiles(id) on delete set null,
  reason text not null
);

create table if not exists public.tax_calendar_items (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  name text not null,
  frequency text not null,
  due_date date not null,
  status text not null default 'Pending',
  proof_path text
);

create table if not exists public.petty_cash_funds (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid references projects(id) on delete set null,
  fund_name text not null,
  opening_balance numeric(18,2) not null default 0
);

create table if not exists public.petty_cash_transactions (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  fund_id uuid not null references petty_cash_funds(id) on delete cascade,
  transaction_type text not null,
  amount numeric(18,2) not null,
  transaction_date date not null,
  receipt_id uuid references receipts(id) on delete set null,
  notes text
);

create table if not exists public.safety_programs (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  program_reference text not null,
  approval_date date,
  safety_officer_id uuid references profiles(id) on delete set null
);

create table if not exists public.safety_toolbox_meetings (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  meeting_date date not null,
  topic text not null,
  attendees text,
  attachment_path text
);

create table if not exists public.offline_queue (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  idempotency_key text not null,
  entity_type text not null,
  payload jsonb not null,
  status text not null default 'Queued',
  queued_at timestamptz not null default now(),
  synced_at timestamptz
);

create unique index if not exists offline_queue_company_key_unique
  on public.offline_queue(company_id, idempotency_key);

alter table public.offline_queue
  add constraint offline_queue_status_check
  check (status in ('Queued','Syncing','Synced','Failed'));

do $$
declare v_table text;
begin
  foreach v_table in array array[
    'bids','cost_estimates','cost_estimate_items','project_contract_amendments',
    'project_price_adjustments','project_activities','project_inspections',
    'project_documents','project_closeout_records','billing_attachment_requirements',
    'billing_attachment_overrides','tax_calendar_items','petty_cash_funds',
    'petty_cash_transactions','safety_programs','safety_toolbox_meetings','offline_queue'
  ] loop
    execute format('alter table public.%I enable row level security', v_table);
    execute format('drop policy if exists %I_read on public.%I', v_table, v_table);
    execute format('drop policy if exists %I_write on public.%I', v_table, v_table);
    execute format('create policy %I_read on public.%I for select to authenticated using (is_active_user() and (is_creator() or company_id = current_company_id()))', v_table, v_table);
    execute format('create policy %I_write on public.%I for all to authenticated using (is_active_user() and (is_creator() or company_id = current_company_id())) with check (is_active_user() and (is_creator() or company_id = current_company_id()))', v_table, v_table);
    execute format('grant select, insert, update, delete on public.%I to authenticated', v_table);
  end loop;
end $$;
