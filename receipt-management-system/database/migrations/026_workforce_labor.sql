-- RMS 026: workforce, attendance and configurable labor costs.

create table if not exists public.labor_wage_configurations (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  region text not null,
  effective_from date not null,
  effective_to date,
  daily_minimum_wage numeric(18,2) not null,
  hourly_reference numeric(18,2),
  overtime_multiplier numeric(9,6) not null default 1.25,
  holiday_multiplier numeric(9,6) not null default 2.00,
  thirteenth_month_enabled boolean not null default true,
  sss_enabled boolean not null default false,
  philhealth_enabled boolean not null default false,
  pagibig_enabled boolean not null default false,
  created_at timestamptz not null default now(),
  constraint wage_minimum_check check (daily_minimum_wage >= 0),
  constraint wage_hourly_check check (hourly_reference is null or hourly_reference >= 0),
  constraint wage_multipliers_check
    check (overtime_multiplier >= 0 and holiday_multiplier >= 0)
);

create table if not exists public.project_workers (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid references projects(id) on delete set null,
  worker_name text not null,
  worker_code text,
  role text,
  daily_rate numeric(18,2) not null default 0,
  hourly_rate numeric(18,2) not null default 0,
  sss_number text,
  philhealth_number text,
  pagibig_number text,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  constraint worker_rates_check check (daily_rate >= 0 and hourly_rate >= 0)
);

create table if not exists public.project_attendance (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  worker_id uuid not null references project_workers(id) on delete restrict,
  attendance_date date not null,
  time_in time,
  time_out time,
  regular_hours numeric(9,4) not null default 0,
  overtime_hours numeric(9,4) not null default 0,
  holiday_hours numeric(9,4) not null default 0,
  present boolean not null default true,
  remarks text,
  approved boolean not null default false,
  approved_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  constraint attendance_hours_check
    check (regular_hours >= 0 and overtime_hours >= 0 and holiday_hours >= 0)
);

create table if not exists public.project_labor_costs (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  period_start date not null,
  period_end date not null,
  regular_pay numeric(18,2) not null default 0,
  overtime_pay numeric(18,2) not null default 0,
  holiday_pay numeric(18,2) not null default 0,
  total_pay numeric(18,2) not null default 0,
  approved boolean not null default false,
  approved_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  constraint labor_cost_amount_check
    check (regular_pay >= 0 and overtime_pay >= 0
           and holiday_pay >= 0 and total_pay >= 0)
);

create unique index if not exists labor_wage_region_effective_idx
  on public.labor_wage_configurations(company_id, region, effective_from);
create unique index if not exists project_worker_code_company_idx
  on public.project_workers(company_id, lower(btrim(worker_code)))
  where worker_code is not null;
create unique index if not exists project_attendance_worker_date_idx
  on public.project_attendance(company_id, project_id, worker_id, attendance_date);

do $$
declare v_table text;
begin
  foreach v_table in array array[
    'labor_wage_configurations','project_workers','project_attendance','project_labor_costs'
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
