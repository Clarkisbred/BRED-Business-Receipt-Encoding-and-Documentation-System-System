-- RMS 044: company-scoped royalty records, withholding returns, and audit history.
-- Client names are snapshots from the selected contract/project or authorized manual entry;
-- this schema does not introduce a separate client table.

alter table public.suppliers
  add column if not exists tax_classification text not null default 'UNCLASSIFIED';

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conrelid = 'public.suppliers'::regclass
      and conname = 'suppliers_tax_classification_check'
  ) then
    alter table public.suppliers
      add constraint suppliers_tax_classification_check
      check (tax_classification in ('VAT_REGISTERED','NON_VAT','VAT_EXEMPT','UNCLASSIFIED'));
  end if;
end $$;

create unique index if not exists suppliers_id_company_scope_idx
  on public.suppliers(id, company_id);
create unique index if not exists projects_id_company_scope_idx
  on public.projects(id, company_id);
create unique index if not exists project_contracts_id_project_company_scope_idx
  on public.project_contracts(id, project_id, company_id);

create table if not exists public.royalty_company_settings (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default public.current_company_id()
    references public.companies(id) on delete restrict,
  default_vat_rate numeric(9,6),
  default_royalty_rate numeric(9,6) not null default 3,
  default_royalty_basis text not null default 'VAT_EXCLUSIVE',
  default_withholding_rate numeric(9,6) not null default 2,
  default_withholding_basis text not null default 'VAT_EXCLUSIVE',
  default_additional_withholding_rate numeric(9,6),
  default_additional_withholding_basis text not null default 'VAT_EXCLUSIVE',
  default_ewt_rate numeric(9,6),
  default_ewt_basis text not null default 'VAT_EXCLUSIVE',
  default_retention_rate numeric(9,6),
  default_retention_basis text not null default 'VAT_EXCLUSIVE',
  deduction_confirmation_threshold numeric(18,2) not null default 10000,
  updated_by uuid references public.profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint royalty_settings_rates_check check (
    (default_vat_rate is null or default_vat_rate between 0 and 100)
    and default_royalty_rate between 0 and 100
    and default_withholding_rate between 0 and 100
    and (default_additional_withholding_rate is null
      or default_additional_withholding_rate between 0 and 100)
    and (default_ewt_rate is null or default_ewt_rate between 0 and 100)
    and (default_retention_rate is null or default_retention_rate between 0 and 100)
    and deduction_confirmation_threshold >= 0
  ),
  constraint royalty_settings_basis_check check (
    default_royalty_basis in ('BILLING_AMOUNT','VAT_EXCLUSIVE')
    and default_withholding_basis in ('BILLING_AMOUNT','VAT_EXCLUSIVE')
    and default_additional_withholding_basis in ('BILLING_AMOUNT','VAT_EXCLUSIVE')
    and default_ewt_basis in ('BILLING_AMOUNT','VAT_EXCLUSIVE')
    and default_retention_basis in ('BILLING_AMOUNT','VAT_EXCLUSIVE')
  ),
  constraint royalty_settings_company_unique unique (company_id)
);

create table if not exists public.royalty_records (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default public.current_company_id()
    references public.companies(id) on delete restrict,
  supplier_id uuid,
  supplier_tax_classification text not null default 'UNCLASSIFIED',
  client_name text not null,
  project_id uuid,
  contract_id uuid,
  contract_name text not null default '',
  contract_amount numeric(18,2) not null default 0,
  billing_date date not null,
  billing_amount numeric(18,2) not null,
  vat_treatment text not null,
  vat_rate numeric(9,6) not null default 0,
  vat_amount numeric(18,2) not null default 0,
  input_vat_amount numeric(18,2) not null default 0,
  vat_exclusive_amount numeric(18,2) not null default 0,
  gross_billing_amount numeric(18,2) not null default 0,
  royalty_rate numeric(9,6) not null,
  royalty_basis text not null,
  royalty_base numeric(18,2) not null default 0,
  royalty_amount numeric(18,2) not null default 0,
  withholding_rate numeric(9,6) not null,
  withholding_basis text not null,
  withholding_base numeric(18,2) not null default 0,
  withholding_amount numeric(18,2) not null default 0,
  additional_withholding_rate numeric(9,6),
  additional_withholding_basis text not null default 'VAT_EXCLUSIVE',
  additional_withholding_amount numeric(18,2) not null default 0,
  ewt_rate numeric(9,6),
  ewt_basis text not null default 'VAT_EXCLUSIVE',
  ewt_amount numeric(18,2) not null default 0,
  retention_rate numeric(9,6),
  retention_basis text not null default 'VAT_EXCLUSIVE',
  retention_amount numeric(18,2) not null default 0,
  total_deductions numeric(18,2) not null default 0,
  initial_net_amount numeric(18,2) not null default 0,
  returned_amount numeric(18,2) not null default 0,
  outstanding_return numeric(18,2) not null default 0,
  final_net_amount numeric(18,2) not null default 0,
  status text not null default 'pending',
  remarks text not null default '',
  created_by uuid references public.profiles(id) on delete set null,
  updated_by uuid references public.profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  idempotency_key uuid not null,
  constraint royalty_record_id_company_unique unique (id, company_id),
  constraint royalty_record_idempotency_unique unique (company_id, idempotency_key),
  constraint royalty_record_supplier_company_fkey
    foreign key (supplier_id, company_id)
    references public.suppliers(id, company_id) on delete restrict,
  constraint royalty_record_project_company_fkey
    foreign key (project_id, company_id)
    references public.projects(id, company_id) on delete restrict,
  constraint royalty_record_contract_project_company_fkey
    foreign key (contract_id, project_id, company_id)
    references public.project_contracts(id, project_id, company_id) on delete restrict,
  constraint royalty_record_contract_requires_project
    check (contract_id is null or project_id is not null),
  constraint royalty_record_classification_check check (
    supplier_tax_classification in ('VAT_REGISTERED','NON_VAT','VAT_EXEMPT','UNCLASSIFIED')
  ),
  constraint royalty_record_vat_treatment_check check (
    vat_treatment in ('VAT_INCLUSIVE','VAT_EXCLUSIVE','NON_VAT','VAT_EXEMPT','UNCLASSIFIED')
  ),
  constraint royalty_record_bases_check check (
    royalty_basis in ('BILLING_AMOUNT','VAT_EXCLUSIVE')
    and withholding_basis in ('BILLING_AMOUNT','VAT_EXCLUSIVE')
    and additional_withholding_basis in ('BILLING_AMOUNT','VAT_EXCLUSIVE')
    and ewt_basis in ('BILLING_AMOUNT','VAT_EXCLUSIVE')
    and retention_basis in ('BILLING_AMOUNT','VAT_EXCLUSIVE')
  ),
  constraint royalty_record_rates_check check (
    vat_rate between 0 and 100
    and royalty_rate between 0 and 100
    and withholding_rate between 0 and 100
    and (additional_withholding_rate is null or additional_withholding_rate between 0 and 100)
    and (ewt_rate is null or ewt_rate between 0 and 100)
    and (retention_rate is null or retention_rate between 0 and 100)
  ),
  constraint royalty_record_amounts_check check (
    contract_amount >= 0 and billing_amount >= 0 and vat_amount >= 0
    and input_vat_amount >= 0 and vat_exclusive_amount >= 0 and gross_billing_amount >= 0
    and royalty_base >= 0 and royalty_amount >= 0 and withholding_base >= 0
    and withholding_amount >= 0 and additional_withholding_amount >= 0
    and ewt_amount >= 0 and retention_amount >= 0 and total_deductions >= 0
    and initial_net_amount >= 0 and returned_amount >= 0
    and outstanding_return >= 0 and final_net_amount >= 0
  ),
  constraint royalty_record_return_limit_check
    check (returned_amount + outstanding_return = withholding_amount),
  constraint royalty_record_status_check check (status in ('pending','completed')),
  constraint royalty_record_text_limits check (
    char_length(btrim(client_name)) between 1 and 200
    and char_length(contract_name) <= 200
    and char_length(remarks) <= 2000
  )
);

create index if not exists royalty_records_company_date_idx
  on public.royalty_records(company_id, billing_date desc, created_at desc);
create index if not exists royalty_records_company_client_idx
  on public.royalty_records(company_id, lower(client_name));
create index if not exists royalty_records_company_project_idx
  on public.royalty_records(company_id, project_id, billing_date desc);

create table if not exists public.royalty_returns (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null references public.companies(id) on delete restrict,
  record_id uuid not null,
  amount numeric(18,2) not null check (amount > 0),
  return_date date not null,
  reference text not null default '',
  remarks text not null default '',
  created_by uuid references public.profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  idempotency_key uuid not null,
  constraint royalty_return_record_company_fkey
    foreign key (record_id, company_id)
    references public.royalty_records(id, company_id) on delete restrict,
  constraint royalty_return_idempotency_unique unique (company_id, idempotency_key),
  constraint royalty_return_text_limits check (
    char_length(reference) <= 120 and char_length(remarks) <= 1000
  )
);

create index if not exists royalty_returns_company_record_date_idx
  on public.royalty_returns(company_id, record_id, return_date, created_at);

create table if not exists public.royalty_record_events (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null references public.companies(id) on delete restrict,
  record_id uuid,
  actor_id uuid references public.profiles(id) on delete set null,
  actor_name text not null default 'User',
  action text not null,
  summary text not null default '',
  event_data jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  constraint royalty_record_event_scope_fkey
    foreign key (record_id, company_id)
    references public.royalty_records(id, company_id) on delete restrict,
  constraint royalty_record_event_data_check check (jsonb_typeof(event_data) = 'object')
);

create index if not exists royalty_record_events_company_record_idx
  on public.royalty_record_events(company_id, record_id, created_at);

alter table public.royalty_company_settings enable row level security;
alter table public.royalty_records enable row level security;
alter table public.royalty_returns enable row level security;
alter table public.royalty_record_events enable row level security;

drop policy if exists royalty_company_settings_read on public.royalty_company_settings;
create policy royalty_company_settings_read
  on public.royalty_company_settings for select to authenticated
  using (public.is_active_user() and company_id = public.current_company_id());
drop policy if exists royalty_records_read on public.royalty_records;
create policy royalty_records_read
  on public.royalty_records for select to authenticated
  using (public.is_active_user() and company_id = public.current_company_id());
drop policy if exists royalty_returns_read on public.royalty_returns;
create policy royalty_returns_read
  on public.royalty_returns for select to authenticated
  using (public.is_active_user() and company_id = public.current_company_id());
drop policy if exists royalty_record_events_read on public.royalty_record_events;
create policy royalty_record_events_read
  on public.royalty_record_events for select to authenticated
  using (public.is_active_user() and company_id = public.current_company_id());

revoke all on public.royalty_company_settings, public.royalty_records,
  public.royalty_returns, public.royalty_record_events from anon, authenticated;
grant select on public.royalty_company_settings, public.royalty_records,
  public.royalty_returns, public.royalty_record_events to authenticated;

create or replace function public.save_royalty_company_settings(p_settings jsonb)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_company_id uuid := public.current_company_id();
  v_row public.royalty_company_settings%rowtype;
  v_actor_name text;
  v_settings jsonb;
begin
  if not public.is_active_user() or not public.is_admin() then
    raise exception 'Only a Creator or company Admin can manage royalty settings.';
  end if;
  if jsonb_typeof(p_settings) is distinct from 'object' then
    raise exception 'Royalty settings must be an object.';
  end if;
  if v_company_id is null then raise exception 'The account is not assigned to a company.'; end if;

  insert into public.royalty_company_settings (
    company_id, default_vat_rate, default_royalty_rate, default_royalty_basis,
    default_withholding_rate, default_withholding_basis,
    default_additional_withholding_rate, default_additional_withholding_basis,
    default_ewt_rate, default_ewt_basis, default_retention_rate, default_retention_basis,
    deduction_confirmation_threshold, updated_by, updated_at
  ) values (
    v_company_id,
    nullif(p_settings->>'default_vat_rate','')::numeric,
    coalesce(nullif(p_settings->>'default_royalty_rate','')::numeric, 3),
    coalesce(nullif(p_settings->>'default_royalty_basis',''), 'VAT_EXCLUSIVE'),
    coalesce(nullif(p_settings->>'default_withholding_rate','')::numeric, 2),
    coalesce(nullif(p_settings->>'default_withholding_basis',''), 'VAT_EXCLUSIVE'),
    nullif(p_settings->>'default_additional_withholding_rate','')::numeric,
    coalesce(nullif(p_settings->>'default_additional_withholding_basis',''), 'VAT_EXCLUSIVE'),
    nullif(p_settings->>'default_ewt_rate','')::numeric,
    coalesce(nullif(p_settings->>'default_ewt_basis',''), 'VAT_EXCLUSIVE'),
    nullif(p_settings->>'default_retention_rate','')::numeric,
    coalesce(nullif(p_settings->>'default_retention_basis',''), 'VAT_EXCLUSIVE'),
    coalesce(nullif(p_settings->>'deduction_confirmation_threshold','')::numeric, 10000),
    auth.uid(), now()
  )
  on conflict (company_id) do update set
    default_vat_rate = excluded.default_vat_rate,
    default_royalty_rate = excluded.default_royalty_rate,
    default_royalty_basis = excluded.default_royalty_basis,
    default_withholding_rate = excluded.default_withholding_rate,
    default_withholding_basis = excluded.default_withholding_basis,
    default_additional_withholding_rate = excluded.default_additional_withholding_rate,
    default_additional_withholding_basis = excluded.default_additional_withholding_basis,
    default_ewt_rate = excluded.default_ewt_rate,
    default_ewt_basis = excluded.default_ewt_basis,
    default_retention_rate = excluded.default_retention_rate,
    default_retention_basis = excluded.default_retention_basis,
    deduction_confirmation_threshold = excluded.deduction_confirmation_threshold,
    updated_by = auth.uid(),
    updated_at = now()
  returning * into v_row;

  select full_name into v_actor_name from public.profiles where id = auth.uid();
  insert into public.royalty_record_events(
    company_id, actor_id, actor_name, action, summary, event_data
  ) values (
    v_company_id, auth.uid(), coalesce(v_actor_name, 'User'),
    'company_settings_updated', 'Company royalty defaults updated.',
    jsonb_build_object('settings_id', v_row.id)
  );
  v_settings := to_jsonb(v_row);
  return v_settings;
end $$;

create or replace function public.save_royalty_record(p_id uuid, p_payload jsonb)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_company_id uuid := public.current_company_id();
  v_id uuid;
  v_request_key uuid;
  v_existing public.royalty_records%rowtype;
  v_is_existing boolean := false;
  v_supplier_id uuid;
  v_supplier_classification text := 'UNCLASSIFIED';
  v_project_id uuid;
  v_contract_id uuid;
  v_contract_project_id uuid;
  v_contract_name text := '';
  v_contract_client text;
  v_contract_amount numeric(18,2) := 0;
  v_client_name text;
  v_billing_date date;
  v_billing_amount numeric(18,2);
  v_treatment text;
  v_vat_rate numeric(9,6);
  v_declared_vat numeric(18,2);
  v_input_vat numeric(18,2);
  v_vat_exclusive numeric(18,2);
  v_gross numeric(18,2);
  v_royalty_rate numeric(9,6);
  v_royalty_basis text;
  v_royalty_base numeric(18,2);
  v_royalty_amount numeric(18,2);
  v_withholding_rate numeric(9,6);
  v_withholding_basis text;
  v_withholding_base numeric(18,2);
  v_withholding_amount numeric(18,2);
  v_additional_rate numeric(9,6);
  v_additional_basis text;
  v_additional_amount numeric(18,2);
  v_ewt_rate numeric(9,6);
  v_ewt_basis text;
  v_ewt_amount numeric(18,2);
  v_retention_rate numeric(9,6);
  v_retention_basis text;
  v_retention_amount numeric(18,2);
  v_total_deductions numeric(18,2);
  v_initial_net numeric(18,2);
  v_returned numeric(18,2) := 0;
  v_outstanding numeric(18,2);
  v_final_net numeric(18,2);
  v_status text;
  v_remarks text;
  v_actor_name text;
  v_row public.royalty_records%rowtype;
begin
  if not public.is_active_user() or not public.is_admin() then
    raise exception 'Only a Creator or company Admin can save royalty records.';
  end if;
  if jsonb_typeof(p_payload) is distinct from 'object' then
    raise exception 'Royalty record details must be an object.';
  end if;
  if v_company_id is null then raise exception 'The account is not assigned to a company.'; end if;

  v_id := p_id;
  v_request_key := coalesce(nullif(p_payload->>'idempotency_key','')::uuid, gen_random_uuid());
  v_supplier_id := nullif(p_payload->>'supplier_id','')::uuid;
  v_project_id := nullif(p_payload->>'project_id','')::uuid;
  v_contract_id := nullif(p_payload->>'contract_id','')::uuid;
  v_client_name := nullif(btrim(coalesce(p_payload->>'client_name','')), '');
  v_billing_date := nullif(p_payload->>'billing_date','')::date;
  v_billing_amount := round(coalesce(nullif(p_payload->>'billing_amount','')::numeric, 0), 2);
  v_treatment := coalesce(nullif(p_payload->>'vat_treatment',''), '');
  v_vat_rate := coalesce(nullif(p_payload->>'vat_rate','')::numeric, 0);
  v_declared_vat := round(coalesce(nullif(p_payload->>'vat_amount','')::numeric, 0), 2);
  v_royalty_rate := coalesce(nullif(p_payload->>'royalty_rate','')::numeric, 0);
  v_royalty_basis := coalesce(nullif(p_payload->>'royalty_basis',''), 'VAT_EXCLUSIVE');
  v_withholding_rate := coalesce(nullif(p_payload->>'withholding_rate','')::numeric, 0);
  v_withholding_basis := coalesce(nullif(p_payload->>'withholding_basis',''), 'VAT_EXCLUSIVE');
  v_additional_rate := nullif(p_payload->>'additional_withholding_rate','')::numeric;
  v_additional_basis := coalesce(nullif(p_payload->>'additional_withholding_basis',''), 'VAT_EXCLUSIVE');
  v_ewt_rate := nullif(p_payload->>'ewt_rate','')::numeric;
  v_ewt_basis := coalesce(nullif(p_payload->>'ewt_basis',''), 'VAT_EXCLUSIVE');
  v_retention_rate := nullif(p_payload->>'retention_rate','')::numeric;
  v_retention_basis := coalesce(nullif(p_payload->>'retention_basis',''), 'VAT_EXCLUSIVE');
  v_remarks := coalesce(p_payload->>'remarks','');

  if v_billing_date is null or extract(year from v_billing_date) < 1500 then
    raise exception 'Choose a valid billing date.';
  end if;
  if v_client_name is null or char_length(v_client_name) > 200 then
    raise exception 'Enter a client or payee name up to 200 characters.';
  end if;
  if v_billing_amount < 0 or v_declared_vat < 0 then
    raise exception 'Billing and VAT amounts cannot be negative.';
  end if;
  if v_remarks is null or char_length(v_remarks) > 2000 then
    raise exception 'Remarks can be up to 2000 characters.';
  end if;

  if v_supplier_id is not null then
    select upper(coalesce(tax_classification, 'UNCLASSIFIED'))
      into v_supplier_classification
    from public.suppliers
    where id = v_supplier_id and company_id = v_company_id;
    if not found then raise exception 'Choose a supplier in your company.'; end if;
  end if;
  if v_supplier_classification not in ('VAT_REGISTERED','NON_VAT','VAT_EXEMPT','UNCLASSIFIED') then
    v_supplier_classification := 'UNCLASSIFIED';
  end if;

  if v_project_id is not null and not exists (
    select 1 from public.projects
    where id = v_project_id and company_id = v_company_id
  ) then
    raise exception 'Choose a project in your company.';
  end if;
  if v_contract_id is not null then
    select project_id, contract_name, client_name, contract_amount
      into v_contract_project_id, v_contract_name, v_contract_client, v_contract_amount
    from public.project_contracts
    where id = v_contract_id and company_id = v_company_id;
    if not found then raise exception 'Choose a contract in your company.'; end if;
    if v_project_id is not null and v_project_id <> v_contract_project_id then
      raise exception 'The selected contract does not belong to the selected project.';
    end if;
    v_project_id := v_contract_project_id;
    if v_client_name is null then v_client_name := v_contract_client; end if;
  end if;
  if v_client_name is null and v_project_id is not null then
    select nullif(btrim(client_name), '') into v_client_name
    from public.projects where id = v_project_id and company_id = v_company_id;
  end if;
  if v_client_name is null then raise exception 'Enter a client or payee name.'; end if;

  if v_treatment not in ('VAT_INCLUSIVE','VAT_EXCLUSIVE','NON_VAT','VAT_EXEMPT','UNCLASSIFIED') then
    raise exception 'Choose a valid VAT treatment.';
  end if;
  if v_supplier_classification = 'NON_VAT' and v_treatment <> 'NON_VAT' then
    raise exception 'A Non-VAT supplier requires Non-VAT billing treatment.';
  elsif v_supplier_classification = 'VAT_EXEMPT' and v_treatment <> 'VAT_EXEMPT' then
    raise exception 'A VAT-exempt supplier requires VAT-exempt billing treatment.';
  elsif v_supplier_classification = 'VAT_REGISTERED'
    and v_treatment not in ('VAT_INCLUSIVE','VAT_EXCLUSIVE') then
    raise exception 'A VAT-registered supplier requires VAT-inclusive or VAT-exclusive treatment.';
  end if;

  if v_treatment = 'VAT_INCLUSIVE' then
    v_vat_exclusive := round(v_billing_amount / (1 + v_vat_rate / 100), 2);
    v_input_vat := round(v_billing_amount - v_vat_exclusive, 2);
    v_gross := v_billing_amount;
  elsif v_treatment = 'VAT_EXCLUSIVE' then
    v_vat_exclusive := v_billing_amount;
    v_input_vat := v_declared_vat;
    v_gross := round(v_billing_amount + v_input_vat, 2);
  else
    if v_declared_vat > 0 then
      raise exception 'VAT can only be recorded for VAT-inclusive or VAT-exclusive billing.';
    end if;
    v_vat_exclusive := v_billing_amount;
    v_input_vat := 0;
    v_gross := v_billing_amount;
  end if;

  v_royalty_base := case when v_royalty_basis = 'BILLING_AMOUNT'
    then v_billing_amount else v_vat_exclusive end;
  v_withholding_base := case when v_withholding_basis = 'BILLING_AMOUNT'
    then v_billing_amount else v_vat_exclusive end;
  v_royalty_amount := round(v_royalty_base * v_royalty_rate / 100, 2);
  v_withholding_amount := round(v_withholding_base * v_withholding_rate / 100, 2);
  v_additional_amount := case when v_additional_rate is null then 0
    else round((case when v_additional_basis = 'BILLING_AMOUNT'
      then v_billing_amount else v_vat_exclusive end) * v_additional_rate / 100, 2) end;
  v_ewt_amount := case when v_ewt_rate is null then 0
    else round((case when v_ewt_basis = 'BILLING_AMOUNT'
      then v_billing_amount else v_vat_exclusive end) * v_ewt_rate / 100, 2) end;
  v_retention_amount := case when v_retention_rate is null then 0
    else round((case when v_retention_basis = 'BILLING_AMOUNT'
      then v_billing_amount else v_vat_exclusive end) * v_retention_rate / 100, 2) end;
  v_total_deductions := round(v_royalty_amount + v_withholding_amount
    + v_additional_amount + v_ewt_amount + v_retention_amount, 2);
  if v_total_deductions > v_gross then
    raise exception 'Total deductions cannot exceed the gross billing amount.';
  end if;
  v_initial_net := round(v_gross - v_total_deductions, 2);

  if v_id is not null then
    select * into v_existing from public.royalty_records
    where id = v_id and company_id = v_company_id for update;
    if not found then raise exception 'The royalty record was not found in your company.'; end if;
    if v_existing.returned_amount > 0 or v_existing.status = 'completed' then
      raise exception 'A record with returns cannot be amended; preserve its audit history and create a correcting record.';
    end if;
    v_is_existing := true;
    v_returned := v_existing.returned_amount;
    v_request_key := v_existing.idempotency_key;
  else
    perform pg_advisory_xact_lock(hashtext(v_company_id::text || ':' || v_request_key::text));
    select * into v_existing from public.royalty_records
      where company_id = v_company_id and idempotency_key = v_request_key for update;
    if found then
      if v_existing.supplier_id is distinct from v_supplier_id
        or v_existing.project_id is distinct from v_project_id
        or v_existing.contract_id is distinct from v_contract_id
        or v_existing.client_name is distinct from v_client_name
        or v_existing.billing_date is distinct from v_billing_date
        or v_existing.billing_amount is distinct from v_billing_amount
        or v_existing.vat_treatment is distinct from v_treatment
        or v_existing.vat_rate is distinct from v_vat_rate
        or v_existing.vat_amount is distinct from v_input_vat
        or v_existing.royalty_rate is distinct from v_royalty_rate
        or v_existing.royalty_basis is distinct from v_royalty_basis
        or v_existing.withholding_rate is distinct from v_withholding_rate
        or v_existing.withholding_basis is distinct from v_withholding_basis
        or v_existing.additional_withholding_rate is distinct from v_additional_rate
        or v_existing.additional_withholding_basis is distinct from v_additional_basis
        or v_existing.ewt_rate is distinct from v_ewt_rate
        or v_existing.ewt_basis is distinct from v_ewt_basis
        or v_existing.retention_rate is distinct from v_retention_rate
        or v_existing.retention_basis is distinct from v_retention_basis
        or v_existing.remarks is distinct from v_remarks then
        raise exception 'A royalty request key cannot be reused for a different record.';
      end if;
      return jsonb_build_object('id', v_existing.id, 'idempotent', true);
    end if;
  end if;

  v_outstanding := round(v_withholding_amount - v_returned, 2);
  v_final_net := round(v_initial_net + v_returned, 2);
  v_status := case when v_outstanding = 0 then 'completed' else 'pending' end;
  select full_name into v_actor_name from public.profiles where id = auth.uid();

  if v_is_existing then
    update public.royalty_records set
      supplier_id = v_supplier_id,
      supplier_tax_classification = v_supplier_classification,
      client_name = v_client_name,
      project_id = v_project_id,
      contract_id = v_contract_id,
      contract_name = coalesce(v_contract_name, ''),
      contract_amount = case when v_contract_id is null
        then round(coalesce(nullif(p_payload->>'contract_amount','')::numeric, 0), 2)
        else v_contract_amount end,
      billing_date = v_billing_date,
      billing_amount = v_billing_amount,
      vat_treatment = v_treatment,
      vat_rate = v_vat_rate,
      vat_amount = v_input_vat,
      input_vat_amount = v_input_vat,
      vat_exclusive_amount = v_vat_exclusive,
      gross_billing_amount = v_gross,
      royalty_rate = v_royalty_rate,
      royalty_basis = v_royalty_basis,
      royalty_base = v_royalty_base,
      royalty_amount = v_royalty_amount,
      withholding_rate = v_withholding_rate,
      withholding_basis = v_withholding_basis,
      withholding_base = v_withholding_base,
      withholding_amount = v_withholding_amount,
      additional_withholding_rate = v_additional_rate,
      additional_withholding_basis = v_additional_basis,
      additional_withholding_amount = v_additional_amount,
      ewt_rate = v_ewt_rate,
      ewt_basis = v_ewt_basis,
      ewt_amount = v_ewt_amount,
      retention_rate = v_retention_rate,
      retention_basis = v_retention_basis,
      retention_amount = v_retention_amount,
      total_deductions = v_total_deductions,
      initial_net_amount = v_initial_net,
      returned_amount = v_returned,
      outstanding_return = v_outstanding,
      final_net_amount = v_final_net,
      status = v_status,
      remarks = v_remarks,
      updated_by = auth.uid(),
      updated_at = now()
    where id = v_id and company_id = v_company_id
    returning * into v_row;
  else
    insert into public.royalty_records (
      company_id, supplier_id, supplier_tax_classification, client_name,
      project_id, contract_id, contract_name, contract_amount,
      billing_date, billing_amount, vat_treatment, vat_rate, vat_amount,
      input_vat_amount, vat_exclusive_amount, gross_billing_amount,
      royalty_rate, royalty_basis, royalty_base, royalty_amount,
      withholding_rate, withholding_basis, withholding_base, withholding_amount,
      additional_withholding_rate, additional_withholding_basis, additional_withholding_amount,
      ewt_rate, ewt_basis, ewt_amount, retention_rate, retention_basis, retention_amount,
      total_deductions, initial_net_amount, returned_amount, outstanding_return,
      final_net_amount, status, remarks, created_by, updated_by, idempotency_key
    ) values (
      v_company_id, v_supplier_id, v_supplier_classification, v_client_name,
      v_project_id, v_contract_id, coalesce(v_contract_name, ''),
      case when v_contract_id is null
        then round(coalesce(nullif(p_payload->>'contract_amount','')::numeric, 0), 2)
        else v_contract_amount end,
      v_billing_date, v_billing_amount, v_treatment, v_vat_rate, v_input_vat,
      v_input_vat, v_vat_exclusive, v_gross,
      v_royalty_rate, v_royalty_basis, v_royalty_base, v_royalty_amount,
      v_withholding_rate, v_withholding_basis, v_withholding_base, v_withholding_amount,
      v_additional_rate, v_additional_basis, v_additional_amount,
      v_ewt_rate, v_ewt_basis, v_ewt_amount, v_retention_rate, v_retention_basis, v_retention_amount,
      v_total_deductions, v_initial_net, v_returned, v_outstanding,
      v_final_net, v_status, v_remarks, auth.uid(), auth.uid(), v_request_key
    ) returning * into v_row;
    v_id := v_row.id;
  end if;

  insert into public.royalty_record_events(
    company_id, record_id, actor_id, actor_name, action, summary, event_data
  ) values (
    v_company_id, v_id, auth.uid(), coalesce(v_actor_name, 'User'),
    case when v_is_existing then 'record_updated' else 'record_created' end,
    case when v_is_existing then 'Royalty record updated.' else 'Royalty record created.' end,
    jsonb_build_object(
      'billing_date', v_billing_date, 'billing_amount', v_billing_amount,
      'royalty_amount', v_royalty_amount, 'withholding_amount', v_withholding_amount
    )
  );
  return to_jsonb(v_row);
end $$;

create or replace function public.record_royalty_return(p_record_id uuid, p_payload jsonb)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_company_id uuid := public.current_company_id();
  v_record public.royalty_records%rowtype;
  v_existing public.royalty_returns%rowtype;
  v_amount numeric(18,2);
  v_return_date date;
  v_reference text;
  v_remarks text;
  v_request_key uuid;
  v_total_returned numeric(18,2);
  v_outstanding numeric(18,2);
  v_actor_name text;
  v_return public.royalty_returns%rowtype;
begin
  if not public.is_active_user() or not public.is_admin() then
    raise exception 'Only a Creator or company Admin can record withholding returns.';
  end if;
  if jsonb_typeof(p_payload) is distinct from 'object' then
    raise exception 'Withholding return details must be an object.';
  end if;
  if v_company_id is null then raise exception 'The account is not assigned to a company.'; end if;
  v_amount := round(coalesce(nullif(p_payload->>'amount','')::numeric, 0), 2);
  v_return_date := nullif(p_payload->>'return_date','')::date;
  v_reference := coalesce(btrim(p_payload->>'reference'), '');
  v_remarks := coalesce(p_payload->>'remarks', '');
  v_request_key := nullif(p_payload->>'idempotency_key','')::uuid;
  if v_amount <= 0 or v_request_key is null or v_return_date is null
    or extract(year from v_return_date) < 1500 then
    raise exception 'Enter a positive return amount, valid date, and unique request key.';
  end if;
  if char_length(v_reference) > 120 or char_length(v_remarks) > 1000 then
    raise exception 'Return reference or remarks exceed the allowed length.';
  end if;

  perform pg_advisory_xact_lock(hashtext(v_company_id::text || ':' || v_request_key::text));
  select * into v_existing from public.royalty_returns
  where company_id = v_company_id and idempotency_key = v_request_key for update;
  if found then
    if v_existing.record_id <> p_record_id or v_existing.amount <> v_amount
      or v_existing.return_date <> v_return_date
      or v_existing.reference <> v_reference or v_existing.remarks <> v_remarks then
      raise exception 'A royalty return key cannot be reused for a different transaction.';
    end if;
    return jsonb_build_object('id', v_existing.id, 'record_id', p_record_id, 'idempotent', true);
  end if;

  select * into v_record from public.royalty_records
  where id = p_record_id and company_id = v_company_id for update;
  if not found then raise exception 'The royalty record was not found in your company.'; end if;
  if v_record.status = 'completed' or v_record.outstanding_return <= 0 then
    raise exception 'This royalty record has no outstanding withholding return.';
  end if;
  select coalesce(sum(amount), 0) into v_total_returned
  from public.royalty_returns
  where company_id = v_company_id and record_id = p_record_id;
  v_outstanding := round(v_record.withholding_amount - v_total_returned, 2);
  if v_amount > v_outstanding then
    raise exception 'Return amount cannot exceed the outstanding withholding.';
  end if;

  select full_name into v_actor_name from public.profiles where id = auth.uid();
  insert into public.royalty_returns(
    company_id, record_id, amount, return_date, reference, remarks,
    created_by, idempotency_key
  ) values (
    v_company_id, p_record_id, v_amount, v_return_date, v_reference,
    v_remarks, auth.uid(), v_request_key
  ) returning * into v_return;

  v_total_returned := round(v_total_returned + v_amount, 2);
  v_outstanding := round(v_record.withholding_amount - v_total_returned, 2);
  update public.royalty_records set
    returned_amount = v_total_returned,
    outstanding_return = v_outstanding,
    final_net_amount = round(initial_net_amount + v_total_returned, 2),
    status = case when v_outstanding = 0 then 'completed' else 'pending' end,
    updated_by = auth.uid(),
    updated_at = now()
  where id = p_record_id and company_id = v_company_id;

  insert into public.royalty_record_events(
    company_id, record_id, actor_id, actor_name, action, summary, event_data
  ) values (
    v_company_id, p_record_id, auth.uid(), coalesce(v_actor_name, 'User'),
    'return_recorded', 'Actual withholding return recorded.',
    jsonb_build_object('amount', v_amount, 'return_date', v_return_date, 'reference', v_reference)
  );
  return jsonb_build_object('id', v_return.id, 'record_id', p_record_id,
    'amount', v_amount, 'total_returned', v_total_returned,
    'outstanding_return', v_outstanding);
end $$;

revoke all on function public.save_royalty_company_settings(jsonb) from public, anon, authenticated;
revoke all on function public.save_royalty_record(uuid, jsonb) from public, anon, authenticated;
revoke all on function public.record_royalty_return(uuid, jsonb) from public, anon, authenticated;
grant execute on function public.save_royalty_company_settings(jsonb) to authenticated;
grant execute on function public.save_royalty_record(uuid, jsonb) to authenticated;
grant execute on function public.record_royalty_return(uuid, jsonb) to authenticated;
