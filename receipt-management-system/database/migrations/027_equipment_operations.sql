-- RMS 027: equipment allocation, usage, fuel, maintenance and approved costs.

alter table public.equipment
  add constraint equipment_rate_check check (rate >= 0),
  add constraint equipment_ownership_check check (ownership in ('Owned','Rented','Leased')),
  add constraint equipment_status_check check (status in ('Active','Idle','In Use','Maintenance','Retired','Disposed'));

alter table public.equipment_usage
  add constraint equipment_usage_quantity_check
    check (quantity >= 0 and fuel_consumed >= 0);

alter table public.equipment_maintenance
  add constraint equipment_maintenance_cost_check check (cost >= 0);

create table if not exists public.equipment_allocations (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  equipment_id uuid not null references equipment(id) on delete cascade,
  project_id uuid references projects(id) on delete set null,
  allocated_from date not null,
  allocated_to date,
  purpose text,
  operator_name text,
  status text not null default 'Active',
  approved boolean not null default false,
  created_at timestamptz not null default now(),
  constraint equipment_allocation_status_check
    check (status in ('Planned','Active','Returned','Cancelled'))
);

create table if not exists public.equipment_fuel_logs (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  equipment_id uuid not null references equipment(id) on delete cascade,
  project_id uuid references projects(id) on delete set null,
  usage_id uuid references equipment_usage(id) on delete set null,
  receipt_id uuid references receipts(id) on delete set null,
  fuel_date date not null,
  liters numeric(18,4) not null,
  cost numeric(18,2) not null default 0,
  supplier_id uuid references suppliers(id) on delete set null,
  odometer_reading numeric(18,2),
  remarks text,
  approved boolean not null default false,
  created_at timestamptz not null default now(),
  constraint equipment_fuel_check check (liters >= 0 and cost >= 0)
);

create table if not exists public.equipment_cost_postings (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  equipment_id uuid not null references equipment(id) on delete cascade,
  project_id uuid not null references projects(id) on delete cascade,
  usage_id uuid references equipment_usage(id) on delete set null,
  period_start date not null,
  period_end date not null,
  rental_cost numeric(18,2) not null default 0,
  fuel_cost numeric(18,2) not null default 0,
  maintenance_cost numeric(18,2) not null default 0,
  total_cost numeric(18,2) not null default 0,
  posted_to_expense boolean not null default false,
  approved boolean not null default false,
  approved_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  constraint equipment_posting_cost_check
    check (rental_cost >= 0 and fuel_cost >= 0
           and maintenance_cost >= 0 and total_cost >= 0)
);

create table if not exists public.equipment_down_time (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  equipment_id uuid not null references equipment(id) on delete cascade,
  project_id uuid references projects(id) on delete set null,
  down_date date not null,
  restored_date date,
  reason text not null,
  maintenance_id uuid references equipment_maintenance(id) on delete set null,
  status text not null default 'Open',
  constraint equipment_down_status_check check (status in ('Open','Resolved','Cancelled'))
);

create index if not exists equipment_usage_project_date_idx
  on public.equipment_usage(company_id, project_id, usage_date);
create index if not exists equipment_maintenance_date_idx
  on public.equipment_maintenance(company_id, maintenance_date);
create index if not exists equipment_cost_posting_period_idx
  on public.equipment_cost_postings(company_id, project_id, period_start, period_end);

do $$
declare v_table text;
begin
  foreach v_table in array array[
    'equipment_usage','equipment_maintenance','equipment_allocations',
    'equipment_fuel_logs','equipment_cost_postings','equipment_down_time'
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
