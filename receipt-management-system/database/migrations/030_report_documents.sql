-- RMS 030: finalized construction report runs and generated documents.

create table if not exists public.report_runs (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid references projects(id) on delete set null,
  report_name text not null,
  report_type text not null,
  status text not null default 'Draft',
  period_start date,
  period_end date,
  parameters jsonb not null default '{}'::jsonb,
  generated_by uuid references profiles(id) on delete set null,
  generated_at timestamptz not null default now(),
  finalized_at timestamptz,
  finalized_by uuid references profiles(id) on delete set null,
  remarks text,
  constraint report_run_status_check
    check (status in ('Draft','Approved','Finalized','Cancelled'))
);

create table if not exists public.generated_documents (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid references projects(id) on delete set null,
  report_run_id uuid references report_runs(id) on delete set null,
  document_name text not null,
  document_type text not null,
  document_path text,
  status text not null default 'Generated',
  generated_by uuid references profiles(id) on delete set null,
  generated_at timestamptz not null default now(),
  constraint generated_document_type_check
    check (document_type in ('CSV','XLSX','PDF','JSON')),
  constraint generated_document_status_check
    check (status in ('Generated','Reviewed','Finalized','Superseded'))
);

create index if not exists report_runs_company_project_idx
  on public.report_runs(company_id, project_id, generated_at desc);
create index if not exists generated_documents_company_project_idx
  on public.generated_documents(company_id, project_id, generated_at desc);

do $$
declare v_table text;
begin
  foreach v_table in array array['report_runs','generated_documents'] loop
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
