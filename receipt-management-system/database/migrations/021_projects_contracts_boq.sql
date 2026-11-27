-- RMS 021: projects, contracts, BOQ and variation orders.

alter table public.projects
  add column if not exists status text not null default 'Active',
  add column if not exists client_name text not null default '',
  add column if not exists contract_amount numeric(18,2) not null default 0,
  add column if not exists start_date date,
  add column if not exists original_completion_date date,
  add column if not exists revised_completion_date date,
  add column if not exists latitude numeric(10,7),
  add column if not exists longitude numeric(10,7),
  add column if not exists description text not null default '';

alter table public.projects
  add constraint projects_status_check
  check (status in ('Planning','Active','Suspended','Completed','Closed','Cancelled')),
  add constraint projects_contract_amount_check
  check (contract_amount >= 0);

create table if not exists public.project_contracts (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  contract_number text not null,
  contract_name text not null,
  client_name text not null,
  contract_amount numeric(18,2) not null default 0,
  notice_of_award_date date,
  contract_signing_date date,
  notice_to_proceed_date date,
  original_completion_date date,
  revised_completion_date date,
  contract_duration_days integer,
  status text not null default 'Draft',
  approved boolean not null default false,
  attachment_path text,
  created_at timestamptz not null default now(),
  constraint project_contract_amount_check check (contract_amount >= 0),
  constraint project_contract_status_check
    check (status in ('Draft','Submitted','Approved','Active','Suspended','Completed','Closed'))
);

create table if not exists public.project_boq_items (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  contract_id uuid references project_contracts(id) on delete cascade,
  item_code text not null,
  description text not null,
  unit text not null,
  quantity numeric(18,4) not null default 0,
  unit_price numeric(18,2) not null default 0,
  estimated_cost numeric(18,2) not null default 0,
  actual_cost numeric(18,2) not null default 0,
  parent_item_id uuid references project_boq_items(id) on delete set null,
  sort_order integer not null default 0,
  constraint boq_quantity_check check (quantity >= 0),
  constraint boq_unit_price_check check (unit_price >= 0),
  constraint boq_estimated_cost_check check (estimated_cost >= 0),
  constraint boq_actual_cost_check check (actual_cost >= 0)
);

create table if not exists public.project_variation_orders (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  contract_id uuid references project_contracts(id) on delete cascade,
  variation_number text not null,
  description text not null,
  reason text,
  amount_effect numeric(18,2) not null default 0,
  duration_effect_days integer not null default 0,
  status text not null default 'Draft',
  requested_date date,
  approval_date date,
  approved_by uuid references profiles(id) on delete set null,
  attachment_path text,
  created_at timestamptz not null default now(),
  constraint variation_status_check
    check (status in ('Draft','Submitted','Under Review','Approved','Rejected','Closed'))
);

create table if not exists public.project_variation_items (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  variation_id uuid not null references project_variation_orders(id) on delete cascade,
  boq_item_id uuid references project_boq_items(id) on delete set null,
  description text not null,
  quantity numeric(18,4) not null default 0,
  unit text,
  unit_price numeric(18,2) not null default 0,
  amount numeric(18,2) not null default 0,
  constraint variation_quantity_check check (quantity >= 0),
  constraint variation_unit_price_check check (unit_price >= 0)
);

create unique index if not exists project_contract_number_company_idx
  on public.project_contracts(company_id, lower(btrim(contract_number)));
create unique index if not exists project_boq_code_company_idx
  on public.project_boq_items(company_id, project_id, lower(btrim(item_code)));
create unique index if not exists project_variation_number_company_idx
  on public.project_variation_orders(company_id, project_id, lower(btrim(variation_number)));

do $$
declare v_table text;
begin
  foreach v_table in array array[
    'project_contracts','project_boq_items','project_variation_orders','project_variation_items'
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
