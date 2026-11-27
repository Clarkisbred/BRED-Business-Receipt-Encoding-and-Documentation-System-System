-- RMS 022: approved progress, accomplishment and milestone tracking.

alter table public.project_activities
  add constraint project_activities_planned_progress_check
  check (planned_progress >= 0 and planned_progress <= 100),
  add constraint project_activities_actual_progress_check
  check (actual_progress >= 0 and actual_progress <= 100);

create table if not exists public.project_accomplishments (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  activity_id uuid references project_activities(id) on delete cascade,
  boq_item_id uuid references project_boq_items(id) on delete set null,
  accomplishment_date date not null,
  period_start date,
  period_end date,
  quantity numeric(18,4) not null default 0,
  progress_percent numeric(9,4) not null default 0,
  approved boolean not null default false,
  approved_by uuid references profiles(id) on delete set null,
  approved_at timestamptz,
  attachment_path text,
  remarks text,
  created_at timestamptz not null default now(),
  constraint progress_accomplishment_quantity_check check (quantity >= 0),
  constraint progress_accomplishment_percent_check
    check (progress_percent >= 0 and progress_percent <= 100)
);

create table if not exists public.project_milestones (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  milestone_name text not null,
  description text,
  due_date date not null,
  completed_date date,
  status text not null default 'Pending',
  planned_progress numeric(9,4) not null default 0,
  actual_progress numeric(9,4) not null default 0,
  approved_accomplishment boolean not null default false,
  attachment_path text,
  created_at timestamptz not null default now(),
  constraint milestone_status_check
    check (status in ('Pending','In Progress','Submitted','Approved','Delayed','Completed','Cancelled')),
  constraint milestone_planned_progress_check
    check (planned_progress >= 0 and planned_progress <= 100),
  constraint milestone_actual_progress_check
    check (actual_progress >= 0 and actual_progress <= 100)
);

create table if not exists public.project_schedule_snapshots (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  snapshot_date date not null,
  planned_progress numeric(9,4) not null default 0,
  actual_progress numeric(9,4) not null default 0,
  approved_accomplishment_percent numeric(9,4) not null default 0,
  approved_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  constraint schedule_snapshot_progress_check
    check (planned_progress >= 0 and planned_progress <= 100
           and actual_progress >= 0 and actual_progress <= 100
           and approved_accomplishment_percent >= 0
           and approved_accomplishment_percent <= 100)
);

create unique index if not exists project_milestone_name_company_idx
  on public.project_milestones(company_id, project_id, lower(btrim(milestone_name)));
create unique index if not exists project_schedule_snapshot_date_idx
  on public.project_schedule_snapshots(company_id, project_id, snapshot_date);

do $$
declare v_table text;
begin
  foreach v_table in array array[
    'project_accomplishments','project_milestones','project_schedule_snapshots'
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
