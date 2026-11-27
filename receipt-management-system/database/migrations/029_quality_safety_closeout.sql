-- RMS 029: quality, safety extensions and project closeout.

create table if not exists public.project_quality_ncr (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  ncr_number text not null,
  inspection_id uuid references project_inspections(id) on delete set null,
  boq_item_id uuid references project_boq_items(id) on delete set null,
  description text not null,
  raised_date date not null,
  severity text not null default 'Major',
  status text not null default 'Open',
  responsible_party text,
  due_date date,
  closure_date date,
  attachment_path text,
  created_at timestamptz not null default now(),
  constraint ncr_severity_check check (severity in ('Minor','Major','Critical')),
  constraint ncr_status_check check (status in ('Open','In Correction','Submitted','Accepted','Closed','Rejected'))
);

create table if not exists public.project_corrective_actions (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  ncr_id uuid references project_quality_ncr(id) on delete cascade,
  safety_id uuid references safety_records(id) on delete cascade,
  action_description text not null,
  assigned_to text,
  target_date date,
  completed_date date,
  verification_result text,
  verified_by uuid references profiles(id) on delete set null,
  status text not null default 'Open',
  attachment_path text,
  created_at timestamptz not null default now(),
  constraint corrective_action_status_check
    check (status in ('Open','In Progress','Submitted','Accepted','Closed','Rejected'))
);

create table if not exists public.project_closeout_checklist (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  item_name text not null,
  category text not null,
  required boolean not null default true,
  completed boolean not null default false,
  completed_date date,
  completed_by uuid references profiles(id) on delete set null,
  attachment_path text,
  remarks text,
  created_at timestamptz not null default now()
);

create table if not exists public.project_punch_items (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  punch_number text not null,
  location text,
  description text not null,
  responsible_party text,
  identified_date date not null,
  target_date date,
  closed_date date,
  status text not null default 'Open',
  attachment_path text,
  created_at timestamptz not null default now(),
  constraint punch_status_check check (status in ('Open','In Progress','Submitted','Closed','Rejected'))
);

create table if not exists public.project_handover_records (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid not null references projects(id) on delete cascade,
  handover_type text not null,
  handover_date date,
  handed_over_by text,
  accepted_by text,
  certificate_number text,
  certificate_path text,
  final_retention_release_requested boolean not null default false,
  status text not null default 'Draft',
  remarks text,
  created_at timestamptz not null default now(),
  constraint handover_status_check
    check (status in ('Draft','Submitted','Accepted','Rejected','Closed'))
);

create unique index if not exists ncr_number_company_project_idx
  on public.project_quality_ncr(company_id, project_id, lower(btrim(ncr_number)));
create unique index if not exists punch_number_company_project_idx
  on public.project_punch_items(company_id, project_id, lower(btrim(punch_number)));

do $$
declare v_table text;
begin
  foreach v_table in array array[
    'project_quality_ncr','project_corrective_actions','project_closeout_checklist',
    'project_punch_items','project_handover_records'
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
