-- RMS 033: final VAT, attachment override, and receivable controls.

alter table public.project_billings
  add column if not exists vat_rate numeric(9,6) not null default 0,
  add column if not exists vat_amount numeric(18,2) not null default 0;

alter table public.project_billings
  add constraint project_billing_vat_check
  check (vat_rate >= 0 and vat_rate <= 100 and vat_amount >= 0),
  add constraint project_billing_receivable_check
  check (collected_amount <= net_amount);

alter table public.project_billings
  add column if not exists outstanding_amount numeric(18,2)
  generated always as (net_amount - collected_amount) stored;

create unique index if not exists project_billing_id_company_scope_idx
  on public.project_billings(id, company_id);
create unique index if not exists project_contract_id_company_scope_idx
  on public.project_contracts(id, company_id);

alter table public.project_billings
  drop constraint if exists project_billings_contract_id_fkey;

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'project_billing_line_billing_scope_fkey'
      and conrelid = 'public.project_billing_lines'::regclass
  ) then
    alter table public.project_billing_lines
      drop constraint if exists project_billing_lines_billing_id_fkey,
      add constraint project_billing_line_billing_scope_fkey
      foreign key (billing_id, company_id)
      references public.project_billings(id, company_id)
      on delete cascade;
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'project_billing_deduction_billing_scope_fkey'
      and conrelid = 'public.project_billing_deductions'::regclass
  ) then
    alter table public.project_billing_deductions
      drop constraint if exists project_billing_deductions_billing_id_fkey,
      add constraint project_billing_deduction_billing_scope_fkey
      foreign key (billing_id, company_id)
      references public.project_billings(id, company_id)
      on delete cascade;
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'project_billing_collection_billing_scope_fkey'
      and conrelid = 'public.project_billing_collections'::regclass
  ) then
    alter table public.project_billing_collections
      drop constraint if exists project_billing_collections_billing_id_fkey,
      add constraint project_billing_collection_billing_scope_fkey
      foreign key (billing_id, company_id)
      references public.project_billings(id, company_id)
      on delete cascade;
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'project_billing_attachment_billing_scope_fkey'
      and conrelid = 'public.project_billing_attachments'::regclass
  ) then
    alter table public.project_billing_attachments
      drop constraint if exists project_billing_attachments_billing_id_fkey,
      add constraint project_billing_attachment_billing_scope_fkey
      foreign key (billing_id, company_id)
      references public.project_billings(id, company_id)
      on delete cascade;
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'project_billing_contract_company_scope_fkey'
      and conrelid = 'public.project_billings'::regclass
  ) then
    alter table public.project_billings
      add constraint project_billing_contract_company_scope_fkey
      foreign key (contract_id, company_id)
      references public.project_contracts(id, company_id)
      on delete set null (contract_id);
  end if;
end $$;

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'billing_attachment_override_billing_scope_fkey'
      and conrelid = 'public.billing_attachment_overrides'::regclass
  ) then
    alter table public.billing_attachment_overrides
      add constraint billing_attachment_override_billing_scope_fkey
      foreign key (billing_id, company_id)
      references public.project_billings(id, company_id)
      on delete cascade;
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'retention_ledger_billing_scope_fkey'
      and conrelid = 'public.retention_ledger'::regclass
  ) then
    alter table public.retention_ledger
      add constraint retention_ledger_billing_scope_fkey
      foreign key (billing_id, company_id)
      references public.project_billings(id, company_id)
      on delete set null (billing_id);
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'withholding_received_billing_scope_fkey'
      and conrelid = 'public.withholding_tax_received'::regclass
  ) then
    alter table public.withholding_tax_received
      add constraint withholding_received_billing_scope_fkey
      foreign key (billing_id, company_id)
      references public.project_billings(id, company_id)
      on delete set null (billing_id);
  end if;
end $$;

create or replace function trg_log_master_data() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_row jsonb := to_jsonb(case when tg_op = 'DELETE' then old else new end);
  v_company_id uuid := nullif(v_row->>'company_id', '')::uuid;
begin
  insert into activity_log (user_id, user_name, company_id, action, detail)
  select auth.uid(), p.full_name, v_company_id,
    tg_table_name || '.' || lower(tg_op),
    coalesce(v_row->>'business_name', v_row->>'name', tg_table_name)
      || coalesce(' (' || nullif(coalesce(v_row->>'tin', v_row->>'code'), '') || ')', '')
  from profiles p where p.id = auth.uid();
  if tg_op = 'DELETE' then return old; end if;
  return new;
end $$;
