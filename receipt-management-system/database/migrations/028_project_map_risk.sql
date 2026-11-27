-- RMS 028: live project map markers and construction risk monitoring.

create table if not exists public.project_map_markers (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  marker_name text not null,
  latitude numeric(10,7) not null,
  longitude numeric(10,7) not null,
  marker_type text not null default 'Site',
  status text not null default 'Active',
  description text,
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  constraint project_map_marker_coordinates_check
    check (latitude between -90 and 90 and longitude between -180 and 180),
  constraint project_map_marker_type_check
    check (marker_type in ('Site','Office','Camp','Storage','Checkpoint','Milestone','Hazard')),
  constraint project_map_marker_status_check
    check (status in ('Active','Delayed','At Risk','Stopped','Completed','Archived'))
);

create table if not exists public.project_risks (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  risk_reference text not null,
  title text not null,
  category text not null,
  description text,
  probability integer not null,
  impact integer not null,
  score integer not null,
  level text not null,
  owner uuid references profiles(id) on delete set null,
  target_date date,
  status text not null default 'Open',
  residual_probability integer,
  residual_impact integer,
  residual_score integer,
  mitigation text,
  contingency text,
  created_at timestamptz not null default now(),
  constraint project_risk_probability_check
    check (probability between 1 and 5 and impact between 1 and 5),
  constraint project_risk_residual_check
    check ((residual_probability is null and residual_impact is null)
           or (residual_probability between 1 and 5 and residual_impact between 1 and 5)),
  constraint project_risk_status_check
    check (status in ('Open','Monitoring','Mitigating','Escalated','Closed','Accepted'))
);

create table if not exists public.project_risk_updates (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  risk_id uuid not null references project_risks(id) on delete cascade,
  update_date date not null,
  probability integer,
  impact integer,
  status text,
  notes text,
  attachment_path text,
  created_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  constraint project_risk_update_probability_check
    check (probability is null or probability between 1 and 5),
  constraint project_risk_update_impact_check
    check (impact is null or impact between 1 and 5)
);

create index if not exists project_map_marker_project_idx
  on public.project_map_markers(company_id, project_id);
create index if not exists project_risk_project_status_idx
  on public.project_risks(company_id, project_id, status);
create index if not exists project_risk_level_idx
  on public.project_risks(company_id, level);

do $$
declare v_table text;
begin
  foreach v_table in array array[
    'project_map_markers','project_risks','project_risk_updates'
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
