-- RMS 040: individual construction billing transactions and approval controls.

alter table public.project_billings
  add column if not exists billing_type text not null default 'Progress',
  add column if not exists billing_mode text not null default 'period',
  add column if not exists description text not null default '',
  add column if not exists cumulative_gross_amount numeric(18,2) not null default 0,
  add column if not exists vat_base numeric(18,2) not null default 0,
  add column if not exists net_of_vat numeric(18,2) not null default 0,
  add column if not exists withholding_vat_rate numeric(9,6) not null default 0,
  add column if not exists withholding_vat_base numeric(18,2) not null default 0,
  add column if not exists withholding_vat_amount numeric(18,2) not null default 0,
  add column if not exists withholding_income_tax_rate numeric(9,6) not null default 0,
  add column if not exists withholding_income_tax_base numeric(18,2) not null default 0,
  add column if not exists withholding_income_tax_amount numeric(18,2) not null default 0,
  add column if not exists other_tax_withholdings numeric(18,2) not null default 0,
  add column if not exists total_additions numeric(18,2) not null default 0,
  add column if not exists total_tax_deductions numeric(18,2) not null default 0,
  add column if not exists total_construction_deductions numeric(18,2) not null default 0,
  add column if not exists total_other_deductions numeric(18,2) not null default 0,
  add column if not exists previous_collections numeric(18,2) not null default 0,
  add column if not exists revised_contract_amount_snapshot numeric(18,2) not null default 0,
  add column if not exists remaining_contract_balance numeric(18,2) not null default 0,
  add column if not exists payment_status text not null default 'Unpaid',
  add column if not exists created_by uuid references public.profiles(id) on delete set null,
  add column if not exists last_updated_by uuid references public.profiles(id) on delete set null,
  add column if not exists last_updated_at timestamptz,
  add column if not exists approved_by uuid references public.profiles(id) on delete set null,
  add column if not exists approval_date timestamptz,
  add column if not exists reversal_reason text,
  add column if not exists reversed_at timestamptz,
  add column if not exists idempotency_key uuid,
  add constraint project_billing_advanced_rates_check
    check (billing_type in ('Progress','Mobilization','Final','Other')
      and billing_mode in ('period','cumulative')
      and vat_rate between 0 and 100
      and withholding_vat_rate between 0 and 100
      and withholding_income_tax_rate between 0 and 100
      and vat_base >= 0 and net_of_vat >= 0
      and cumulative_gross_amount >= 0
      and withholding_vat_base >= 0 and withholding_vat_amount >= 0
      and withholding_income_tax_base >= 0 and withholding_income_tax_amount >= 0
      and other_tax_withholdings >= 0
      and total_additions >= 0 and total_tax_deductions >= 0
      and total_construction_deductions >= 0 and total_other_deductions >= 0
      and previous_collections >= 0 and revised_contract_amount_snapshot >= 0
      and remaining_contract_balance >= 0
      and payment_status in ('Unpaid','Partial','Paid'));

update public.project_billings
set vat_base = gross_amount,
    cumulative_gross_amount = gross_amount,
    net_of_vat = greatest(0, gross_amount - vat_amount),
    last_updated_at = created_at
where vat_base = 0 and gross_amount > 0;

create unique index if not exists project_billing_idempotency_scope_idx
  on public.project_billings(company_id, idempotency_key)
  where idempotency_key is not null;

create table if not exists public.project_billing_configurations (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references public.companies(id) on delete restrict,
  project_id uuid not null references public.projects(id) on delete cascade,
  contract_id uuid references public.project_contracts(id) on delete cascade,
  settings jsonb not null default '{}'::jsonb,
  updated_by uuid references public.profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint project_billing_configuration_object_check
    check (jsonb_typeof(settings) = 'object')
);

create unique index if not exists project_billing_configuration_scope_idx
  on public.project_billing_configurations(company_id, project_id, coalesce(contract_id, '00000000-0000-0000-0000-000000000000'::uuid));

create table if not exists public.project_billing_sequences (
  company_id uuid not null references public.companies(id) on delete restrict,
  project_id uuid not null references public.projects(id) on delete cascade,
  next_number bigint not null default 1 check (next_number > 0),
  primary key (company_id, project_id)
);

create table if not exists public.project_billing_adjustments (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references public.companies(id) on delete restrict,
  billing_id uuid not null,
  adjustment_type text not null,
  calculation_type text not null default 'fixed',
  calculation_basis text not null default 'current_period',
  basis_amount numeric(18,2) not null default 0,
  rate numeric(9,6) not null default 0,
  amount numeric(18,2) not null,
  details jsonb not null default '{}'::jsonb,
  approval_status text not null default 'Pending',
  created_by uuid references public.profiles(id) on delete set null,
  approved_by uuid references public.profiles(id) on delete set null,
  approved_at timestamptz,
  approval_note text,
  idempotency_key uuid,
  created_at timestamptz not null default now(),
  constraint project_billing_adjustment_billing_scope_fkey
    foreign key (billing_id, company_id)
    references public.project_billings(id, company_id) on delete restrict,
  constraint project_billing_adjustment_type_check
    check (adjustment_type in (
      'mobilization_recovery','retention','previous_billing_adjustment',
      'materials_on_site','liquidated_damages','backcharge',
      'other_deduction','other_addition','discount','other_withholding'
    )),
  constraint project_billing_adjustment_calculation_check
    check (calculation_type in ('fixed','percentage','manual')
      and calculation_basis in (
        'current_period','cumulative','contract_amount','custom','not_applicable'
      )
      and basis_amount >= 0 and rate between 0 and 100 and amount >= 0
      and jsonb_typeof(details) = 'object'
      and approval_status in ('Pending','Approved','Rejected','Reversed')),
  constraint project_billing_adjustment_idempotency_scope_unique
    unique (company_id, idempotency_key)
);

create index if not exists project_billing_adjustment_billing_idx
  on public.project_billing_adjustments(company_id, billing_id, created_at);
create index if not exists project_billing_adjustment_status_idx
  on public.project_billing_adjustments(company_id, approval_status);
create unique index if not exists project_billing_previous_reference_unique_idx
  on public.project_billing_adjustments(company_id, (details->>'previous_billing_id'))
  where adjustment_type = 'previous_billing_adjustment'
    and approval_status in ('Pending','Approved');

create table if not exists public.project_billing_retention_releases (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references public.companies(id) on delete restrict,
  project_id uuid not null references public.projects(id) on delete restrict,
  contract_id uuid references public.project_contracts(id) on delete set null,
  amount numeric(18,2) not null check (amount > 0),
  release_date date not null,
  reference_number text,
  document_path text,
  notes text,
  created_by uuid references public.profiles(id) on delete set null,
  idempotency_key uuid not null,
  created_at timestamptz not null default now(),
  constraint project_billing_retention_release_scope_unique
    unique (company_id, idempotency_key)
);

create index if not exists project_billing_retention_release_project_idx
  on public.project_billing_retention_releases(company_id, project_id, release_date);

create table if not exists public.project_billing_events (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null references public.companies(id) on delete restrict,
  billing_id uuid not null,
  actor_id uuid references public.profiles(id) on delete set null,
  event_type text not null,
  event_data jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  constraint project_billing_event_billing_scope_fkey
    foreign key (billing_id, company_id)
    references public.project_billings(id, company_id) on delete restrict,
  constraint project_billing_event_data_check
    check (jsonb_typeof(event_data) = 'object')
);

create index if not exists project_billing_event_billing_idx
  on public.project_billing_events(company_id, billing_id, created_at);

alter table public.project_billing_collections
  add column if not exists payment_method text,
  add column if not exists supporting_document_path text,
  add column if not exists created_by uuid references public.profiles(id) on delete set null,
  add column if not exists idempotency_key uuid;
alter table public.project_billing_attachments
  add column if not exists created_by uuid references public.profiles(id) on delete set null,
  add column if not exists idempotency_key uuid;
create unique index if not exists project_billing_collection_idempotency_idx
  on public.project_billing_collections(company_id, idempotency_key)
  where idempotency_key is not null;
create unique index if not exists project_billing_attachment_idempotency_idx
  on public.project_billing_attachments(company_id, idempotency_key)
  where idempotency_key is not null;

drop policy if exists receipts_files_read on storage.objects;
drop policy if exists receipts_files_insert on storage.objects;
drop policy if exists receipts_files_delete on storage.objects;
create policy receipts_files_read on storage.objects for select to authenticated
  using (bucket_id = 'receipts' and public.is_active_user() and (
    public.is_creator()
    or exists (
      select 1 from public.receipts r
      where r.id::text = split_part(storage.objects.name,'/',1)
        and r.company_id = public.current_company_id()
    )
    or exists (
      select 1 from public.project_billings b
      where b.id::text = split_part(storage.objects.name,'/',1)
        and b.company_id = public.current_company_id()
    )
  ));
create policy receipts_files_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'receipts' and public.is_active_user() and (
    public.is_creator()
    or exists (
      select 1 from public.receipts r
      where r.id::text = split_part(storage.objects.name,'/',1)
        and r.company_id = public.current_company_id()
    )
    or exists (
      select 1 from public.project_billings b
      where b.id::text = split_part(storage.objects.name,'/',1)
        and b.company_id = public.current_company_id()
    )
  ));
create policy receipts_files_delete on storage.objects for delete to authenticated
  using (bucket_id = 'receipts' and public.is_active_user() and public.is_admin() and (
    public.is_creator()
    or exists (
      select 1 from public.receipts r
      where r.id::text = split_part(storage.objects.name,'/',1)
        and r.company_id = public.current_company_id()
    )
    or exists (
      select 1 from public.project_billings b
      where b.id::text = split_part(storage.objects.name,'/',1)
        and b.company_id = public.current_company_id()
    )
  ));

do $$
declare v_table text;
begin
  foreach v_table in array array[
    'project_billing_configurations','project_billing_sequences',
    'project_billing_adjustments','project_billing_retention_releases',
    'project_billing_events'
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

do $$
declare v_table text;
begin
  foreach v_table in array array[
    'project_billings','project_billing_lines','project_billing_deductions',
    'project_billing_collections','project_billing_attachments',
    'project_billing_configurations','project_billing_sequences',
    'project_billing_adjustments','project_billing_retention_releases',
    'project_billing_events'
  ] loop
    execute format('drop policy if exists %I_write on public.%I', v_table, v_table);
    execute format('revoke insert, update, delete on public.%I from authenticated', v_table);
  end loop;
end $$;

create or replace function public.save_project_billing_configuration(
  p_project_id uuid, p_contract_id uuid, p_settings jsonb
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_company_id uuid := current_company_id();
  v_result public.project_billing_configurations;
  v_advance numeric(18,2);
  v_retention_cap numeric(18,2);
  v_retention_cap_rate numeric(9,6);
  v_retention_cap_type text;
  v_retention_cap_basis text;
begin
  if not is_active_user() or not is_admin() then
    raise exception 'Only a Creator or company Admin can configure billing.';
  end if;
  if jsonb_typeof(p_settings) <> 'object' then
    raise exception 'Billing configuration must be an object.';
  end if;
  if jsonb_typeof(p_settings->'enabled_adjustments') <> 'object' then
    raise exception 'Choose which adjustment types are enabled.';
  end if;
  v_advance := coalesce(nullif(p_settings->>'mobilization_advance_amount','')::numeric,0);
  v_retention_cap := coalesce(nullif(p_settings->>'retention_cap_amount','')::numeric,0);
  v_retention_cap_rate := coalesce(nullif(p_settings->>'retention_cap_rate','')::numeric,0);
  v_retention_cap_type := coalesce(nullif(p_settings->>'retention_cap_type',''),'fixed');
  v_retention_cap_basis := coalesce(nullif(p_settings->>'retention_cap_basis',''),'contract_amount');
  if v_advance < 0 or v_retention_cap < 0 or v_retention_cap_rate < 0
      or v_retention_cap_rate > 100
      or v_retention_cap_type not in ('fixed','percentage')
      or v_retention_cap_basis not in ('contract_amount','custom') then
    raise exception 'Mobilization advance and retention cap cannot be negative.';
  end if;
  if v_retention_cap_type = 'percentage' and v_retention_cap_basis = 'custom'
      and coalesce(nullif(p_settings->>'retention_cap_basis_amount','')::numeric,0) <= 0 then
    raise exception 'Enter a positive custom basis for the percentage retention cap.';
  end if;
  if not exists (
    select 1 from projects p
    where p.id = p_project_id and p.company_id = v_company_id
  ) then
    raise exception 'The selected project is not available in this company.';
  end if;
  if p_contract_id is not null and not exists (
    select 1 from project_contracts c
    where c.id = p_contract_id and c.project_id = p_project_id
      and c.company_id = v_company_id
  ) then
    raise exception 'The selected contract does not belong to this project.';
  end if;
  insert into project_billing_configurations(
    company_id, project_id, contract_id, settings, updated_by, updated_at
  ) values (v_company_id, p_project_id, p_contract_id, p_settings, auth.uid(), now())
  on conflict (company_id, project_id,
    (coalesce(contract_id, '00000000-0000-0000-0000-000000000000'::uuid)))
  do update set settings = excluded.settings, updated_by = excluded.updated_by, updated_at = now()
  returning * into v_result;
  return to_jsonb(v_result);
end $$;

create or replace function public.create_project_billing(p_payload jsonb)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_company_id uuid := current_company_id();
  v_project_id uuid := nullif(p_payload->>'project_id','')::uuid;
  v_contract_id uuid := nullif(p_payload->>'contract_id','')::uuid;
  v_billing_id uuid := gen_random_uuid();
  v_billing_number text;
  v_sequence bigint;
  v_gross numeric(18,2);
  v_vat_rate numeric(9,6) := coalesce(nullif(p_payload->>'vat_rate','')::numeric, 0);
  v_vat_base numeric(18,2);
  v_vat_amount numeric(18,2);
  v_wht_vat_rate numeric(9,6) := coalesce(nullif(p_payload->>'withholding_vat_rate','')::numeric, 0);
  v_wht_vat_base numeric(18,2) := coalesce(nullif(p_payload->>'withholding_vat_base','')::numeric, 0);
  v_wht_income_rate numeric(9,6) := coalesce(nullif(p_payload->>'withholding_income_tax_rate','')::numeric, 0);
  v_wht_income_base numeric(18,2) := coalesce(nullif(p_payload->>'withholding_income_tax_base','')::numeric, 0);
  v_wht_vat numeric(18,2);
  v_wht_income numeric(18,2);
  v_other_tax numeric(18,2) := coalesce(nullif(p_payload->>'other_tax_withholdings','')::numeric, 0);
  v_mode text := coalesce(nullif(p_payload->>'billing_mode',''), 'period');
  v_billing_type text := coalesce(nullif(p_payload->>'billing_type',''), 'Progress');
  v_net_of_vat numeric(18,2);
  v_config jsonb;
  v_item jsonb;
  v_type text;
  v_calc_type text;
  v_basis text;
  v_basis_amount numeric(18,2);
  v_rate numeric(9,6);
  v_amount numeric(18,2);
  v_details jsonb;
  v_request_key uuid;
  v_previous_billing_id uuid;
  v_revised_contract_amount numeric(18,2) := 0;
  v_previous_billed numeric(18,2) := 0;
  v_previous_collections numeric(18,2) := 0;
  v_input_gross numeric(18,2);
  v_cumulative_gross numeric(18,2);
begin
  if not is_active_user() or not is_admin() then
    raise exception 'Only a Creator or company Admin can create a billing.';
  end if;
  v_request_key := nullif(p_payload->>'idempotency_key','')::uuid;
  if v_request_key is null then raise exception 'A unique billing request key is required.'; end if;
  if exists (
    select 1 from project_billings
    where company_id = v_company_id and idempotency_key = v_request_key
  ) then
    if not exists (
      select 1 from project_billings
      where company_id = v_company_id and idempotency_key = v_request_key
        and project_id = v_project_id
        and contract_id is not distinct from nullif(p_payload->>'contract_id','')::uuid
        and billing_mode = coalesce(nullif(p_payload->>'billing_mode',''),'period')
        and case when billing_mode = 'cumulative'
          then cumulative_gross_amount = coalesce(nullif(p_payload->>'gross_amount','')::numeric,0)
          else gross_amount = coalesce(nullif(p_payload->>'gross_amount','')::numeric,0) end
    ) then raise exception 'A billing request key cannot be reused for a different transaction.'; end if;
    return (
      select jsonb_build_object('id',id,'billing_number',billing_number,'idempotent',true)
      from project_billings where company_id = v_company_id and idempotency_key = v_request_key
    );
  end if;
  if jsonb_typeof(p_payload) <> 'object' or v_project_id is null then
    raise exception 'Choose a project and provide a billing record.';
  end if;
  if v_mode not in ('period','cumulative') then
    raise exception 'Choose period or cumulative billing mode.';
  end if;
  if v_billing_type not in ('Progress','Mobilization','Final','Other') then
    raise exception 'Choose a valid billing type.';
  end if;
  perform 1 from projects p where p.id = v_project_id and p.company_id = v_company_id for update;
  if not found then raise exception 'The selected project is not available in this company.'; end if;
  if v_contract_id is not null and not exists (
    select 1 from project_contracts c
    where c.id = v_contract_id and c.project_id = v_project_id
      and c.company_id = v_company_id
  ) then
    raise exception 'The selected contract does not belong to this project.';
  end if;
  select coalesce(c.contract_amount, p.contract_amount, 0) into v_revised_contract_amount
  from projects p left join project_contracts c
    on c.id = v_contract_id and c.company_id = v_company_id
  where p.id = v_project_id and p.company_id = v_company_id;
  select v_revised_contract_amount + coalesce(sum(amount_effect),0)
    into v_revised_contract_amount
  from project_variation_orders
  where company_id = v_company_id and project_id = v_project_id
    and status = 'Approved'
    and (v_contract_id is null or contract_id is not distinct from v_contract_id);
  select coalesce(sum(c.amount),0) into v_previous_collections
  from project_billing_collections c
  join project_billings b on b.id = c.billing_id and b.company_id = c.company_id
  where b.company_id = v_company_id and b.project_id = v_project_id
    and b.status in ('Approved','Certified','Paid','Overdue')
    and (v_contract_id is null or b.contract_id is not distinct from v_contract_id);
  select coalesce(sum(gross_amount),0) into v_previous_billed
  from project_billings
  where company_id = v_company_id and project_id = v_project_id
    and status in ('Approved','Certified','Paid','Overdue')
    and (v_contract_id is null or contract_id is not distinct from v_contract_id);
  v_input_gross := coalesce(nullif(p_payload->>'gross_amount','')::numeric, 0);
  if v_input_gross <= 0 then raise exception 'Gross billing must be greater than zero.'; end if;
  if v_mode = 'cumulative' then
    if v_input_gross <= v_previous_billed then
      raise exception 'Cumulative gross billing must exceed previously approved billings.';
    end if;
    v_gross := v_input_gross - v_previous_billed;
    v_cumulative_gross := v_input_gross;
  else
    v_gross := v_input_gross;
    v_cumulative_gross := v_previous_billed + v_gross;
  end if;
  v_vat_base := coalesce(nullif(p_payload->>'vat_base','')::numeric, v_gross);
  if v_vat_rate < 0 or v_vat_rate > 100 or v_vat_base < 0
      or v_wht_vat_rate < 0 or v_wht_vat_rate > 100 or v_wht_vat_base < 0
      or v_wht_income_rate < 0 or v_wht_income_rate > 100 or v_wht_income_base < 0
      or v_other_tax < 0 then
    raise exception 'Billing tax rates and bases must be non-negative and rates cannot exceed 100%%.';
  end if;
  v_vat_amount := round(v_vat_base * v_vat_rate / 100, 2);
  v_net_of_vat := greatest(0, v_gross - v_vat_amount);
  v_wht_vat := round(v_wht_vat_base * v_wht_vat_rate / 100, 2);
  v_wht_income := round(v_wht_income_base * v_wht_income_rate / 100, 2);
  select settings into v_config
  from project_billing_configurations
  where company_id = v_company_id and project_id = v_project_id
    and (contract_id is not distinct from v_contract_id or contract_id is null)
  order by contract_id nulls last
  limit 1;

  insert into project_billing_sequences(company_id, project_id, next_number)
  values (
    v_company_id, v_project_id,
    greatest(1, coalesce((
      select max(substring(billing_number from '([0-9]+)$')::bigint) + 1
      from project_billings
      where company_id = v_company_id and project_id = v_project_id
        and billing_number ~ '[0-9]+$'
    ), 1))
  )
  on conflict (company_id, project_id) do nothing;
  update project_billing_sequences
  set next_number = next_number + 1
  where company_id = v_company_id and project_id = v_project_id
  returning next_number - 1 into v_sequence;
  v_billing_number := 'BILL-' || lpad(v_sequence::text, 3, '0');
  if exists (
    select 1 from project_billings
    where company_id = v_company_id and project_id = v_project_id
      and lower(btrim(billing_number)) = lower(v_billing_number)
  ) then
    raise exception 'A billing number conflict was detected. Refresh and try again.';
  end if;

  insert into project_billings(
    id, company_id, project_id, contract_id, billing_number, billing_date,
    period_start, period_end, due_date, description, billing_type, billing_mode,
    cumulative_gross_amount,
    gross_amount, previous_billed, current_accomplishment, vat_rate, vat_base,
    vat_amount, net_of_vat, withholding_vat_rate, withholding_vat_base,
    withholding_vat_amount, withholding_income_tax_rate, withholding_income_tax_base,
    withholding_income_tax_amount, other_tax_withholdings, withholding_amount,
    total_tax_deductions, previous_collections, revised_contract_amount_snapshot,
    remaining_contract_balance, net_amount, collected_amount, payment_status, status,
    created_by, last_updated_by, last_updated_at, idempotency_key
  ) values (
    v_billing_id, v_company_id, v_project_id, v_contract_id, v_billing_number,
    coalesce(nullif(p_payload->>'billing_date','')::date, current_date),
    nullif(p_payload->>'period_start','')::date, nullif(p_payload->>'period_end','')::date,
    nullif(p_payload->>'due_date','')::date,
    coalesce(p_payload->>'description',''), v_billing_type, v_mode,
    v_cumulative_gross,
    v_gross, v_previous_billed,
    v_gross,
    v_vat_rate, v_vat_base, v_vat_amount, v_net_of_vat,
    v_wht_vat_rate, v_wht_vat_base, v_wht_vat,
    v_wht_income_rate, v_wht_income_base, v_wht_income, v_other_tax,
    v_wht_vat + v_wht_income + v_other_tax,
    v_wht_vat + v_wht_income + v_other_tax,
    v_previous_collections, v_revised_contract_amount,
    greatest(0, v_revised_contract_amount - v_previous_billed - v_gross),
    greatest(0, v_gross - v_wht_vat - v_wht_income - v_other_tax),
    0, 'Unpaid', 'Draft', auth.uid(), auth.uid(), now(), v_request_key
  );

  if jsonb_typeof(p_payload->'lines') = 'array' then
    for v_item in select value from jsonb_array_elements(p_payload->'lines') loop
      if coalesce(nullif(v_item->>'description',''),'') = '' then
        raise exception 'Every billing line needs a description.';
      end if;
      insert into project_billing_lines(
        company_id, billing_id, description, previous_quantity, current_quantity,
        cumulative_quantity, unit, unit_price, amount
      ) values (
        v_company_id, v_billing_id, v_item->>'description',
        coalesce(nullif(v_item->>'previous_quantity','')::numeric,0),
        coalesce(nullif(v_item->>'current_quantity','')::numeric,0),
        coalesce(nullif(v_item->>'cumulative_quantity','')::numeric,0),
        nullif(v_item->>'unit',''),
        coalesce(nullif(v_item->>'unit_price','')::numeric,0),
        round(coalesce(nullif(v_item->>'current_quantity','')::numeric,0)
          * coalesce(nullif(v_item->>'unit_price','')::numeric,0),2)
      );
    end loop;
  end if;

  if jsonb_typeof(p_payload->'adjustments') = 'array' then
    for v_item in select value from jsonb_array_elements(p_payload->'adjustments') loop
      v_type := coalesce(v_item->>'adjustment_type','');
      v_calc_type := coalesce(v_item->>'calculation_type','fixed');
      v_basis := coalesce(v_item->>'calculation_basis','current_period');
      v_basis_amount := coalesce(nullif(v_item->>'basis_amount','')::numeric,0);
      v_rate := coalesce(nullif(v_item->>'rate','')::numeric,0);
      v_details := case when jsonb_typeof(v_item->'details') = 'object'
        then v_item->'details' else '{}'::jsonb end;
      if v_type not in (
        'mobilization_recovery','retention','previous_billing_adjustment',
        'materials_on_site','liquidated_damages','backcharge',
        'other_deduction','other_addition','discount','other_withholding'
      ) then raise exception 'Choose a supported billing adjustment type.'; end if;
      if v_calc_type not in ('fixed','percentage','manual') or v_basis_amount < 0
          or v_rate < 0 or v_rate > 100 then
        raise exception 'The adjustment calculation is invalid.';
      end if;
      v_amount := case when v_calc_type = 'percentage'
        then round(v_basis_amount * v_rate / 100,2)
        else coalesce(nullif(v_item->>'amount','')::numeric,0) end;
      if v_amount < 0 then raise exception 'Adjustment amounts cannot be negative.'; end if;
      if coalesce((v_config->'enabled_adjustments'->>v_type)::boolean, false) is not true then
        raise exception 'Enable this adjustment in the project billing configuration before using it.';
      end if;
      if v_type = 'previous_billing_adjustment' then
        v_previous_billing_id := nullif(v_details->>'previous_billing_id','')::uuid;
        if v_previous_billing_id is null or not exists (
          select 1 from project_billings prior
          where prior.id = v_previous_billing_id and prior.company_id = v_company_id
            and prior.project_id = v_project_id
            and prior.status in ('Approved','Certified','Paid','Overdue')
        ) then
          raise exception 'Select a previous billing from this project for the cumulative adjustment.';
        end if;
      end if;
      insert into project_billing_adjustments(
        company_id, billing_id, adjustment_type, calculation_type,
        calculation_basis, basis_amount, rate, amount, details,
        approval_status, created_by, idempotency_key
      ) values (
        v_company_id, v_billing_id, v_type, v_calc_type, v_basis,
        v_basis_amount, v_rate, v_amount, v_details, 'Pending', auth.uid(),
        nullif(v_item->>'idempotency_key','')::uuid
      );
    end loop;
  end if;

  insert into project_billing_events(company_id, billing_id, actor_id, event_type, event_data)
  values (v_company_id, v_billing_id, auth.uid(), 'created',
    jsonb_build_object('billing_number',v_billing_number,'gross_amount',v_gross));
  return jsonb_build_object('id',v_billing_id,'billing_number',v_billing_number);
end $$;

create or replace function public.decide_project_billing_adjustment(
  p_adjustment_id uuid, p_approve boolean, p_note text default null
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_adjustment project_billing_adjustments%rowtype;
  v_billing project_billings%rowtype;
  v_config jsonb;
  v_cap numeric(18,2);
  v_used numeric(18,2);
  v_cap_basis numeric(18,2);
  v_cap_rate numeric(9,6);
  v_cap_type text;
  v_cap_basis_type text;
  v_cap_scope_contract uuid;
  v_supporting_path text;
begin
  if not is_active_user() or not is_admin() then
    raise exception 'Only a Creator or company Admin can approve billing adjustments.';
  end if;
  select * into v_adjustment from project_billing_adjustments
  where id = p_adjustment_id and company_id = current_company_id() for update;
  if not found then raise exception 'The billing adjustment was not found.'; end if;
  if v_adjustment.approval_status <> 'Pending' then
    raise exception 'This adjustment has already been reviewed.';
  end if;
  select * into v_billing from project_billings
  where id = v_adjustment.billing_id and company_id = current_company_id() for update;
  if not found or v_billing.status = 'Cancelled' then
    raise exception 'The related billing is unavailable or cancelled.';
  end if;
  if p_approve then
    perform 1 from projects
    where id = v_billing.project_id and company_id = v_billing.company_id
    for update;
    select settings, contract_id into v_config, v_cap_scope_contract from project_billing_configurations
    where company_id = v_billing.company_id and project_id = v_billing.project_id
      and (contract_id is not distinct from v_billing.contract_id or contract_id is null)
    order by contract_id nulls last
    limit 1;
    v_supporting_path := nullif(btrim(v_adjustment.details->>'supporting_document_path'),'');
    if v_supporting_path is not null and not exists (
      select 1 from project_billing_attachments
      where company_id = v_billing.company_id and billing_id = v_billing.id
        and document_path = v_supporting_path
    ) then
      raise exception 'The supporting document must be attached to this billing before approval.';
    end if;
    if v_adjustment.adjustment_type in (
      'materials_on_site','liquidated_damages','backcharge','other_deduction','other_addition'
    ) and v_supporting_path is null and not exists (
      select 1 from project_billing_attachments
      where company_id = v_billing.company_id and billing_id = v_billing.id
    ) then
      raise exception 'Attach a supporting document to this billing before approving this adjustment.';
    end if;
    if v_adjustment.adjustment_type = 'mobilization_recovery' then
      v_cap := coalesce(nullif(v_config->>'mobilization_advance_amount','')::numeric,0);
      select coalesce(sum(amount),0) into v_used
      from project_billing_adjustments a
      join project_billings b on b.id = a.billing_id and b.company_id = a.company_id
      where a.company_id = v_billing.company_id and b.project_id = v_billing.project_id
        and (v_cap_scope_contract is null or b.contract_id is not distinct from v_cap_scope_contract)
        and b.status <> 'Cancelled'
        and a.adjustment_type = 'mobilization_recovery' and a.approval_status = 'Approved';
      v_used := v_used + coalesce((
        select sum(recovered_amount) from advance_payment_recoveries
        where company_id = v_billing.company_id and project_id = v_billing.project_id
      ),0);
      if v_adjustment.amount > greatest(0, v_cap - v_used) then
        raise exception 'Mobilization recovery exceeds the remaining configured advance.';
      end if;
    elsif v_adjustment.adjustment_type = 'retention' then
      v_cap := coalesce(nullif(v_config->>'retention_cap_amount','')::numeric,0);
      v_cap_type := coalesce(nullif(v_config->>'retention_cap_type',''),'fixed');
      if v_cap_type = 'percentage' then
        v_cap_basis_type := coalesce(nullif(v_config->>'retention_cap_basis',''),'contract_amount');
        v_cap_rate := coalesce(nullif(v_config->>'retention_cap_rate','')::numeric,0);
        if v_cap_basis_type = 'contract_amount' then
          select coalesce(c.contract_amount, p.contract_amount, 0)
            + coalesce((
              select sum(vo.amount_effect) from project_variation_orders vo
              where vo.company_id = v_billing.company_id
                and vo.project_id = v_billing.project_id
                and vo.status = 'Approved'
                and (v_cap_scope_contract is null or vo.contract_id is not distinct from v_cap_scope_contract)
            ),0)
          into v_cap_basis
          from projects p left join project_contracts c
            on c.id = v_cap_scope_contract and c.company_id = v_billing.company_id
          where p.id = v_billing.project_id and p.company_id = v_billing.company_id;
        else
          v_cap_basis := coalesce(nullif(v_config->>'retention_cap_basis_amount','')::numeric,0);
        end if;
        v_cap := round(v_cap_basis * v_cap_rate / 100,2);
      end if;
      if v_cap <= 0 then
        raise exception 'Set a positive retention cap in the project billing configuration before approving retention.';
      end if;
      select coalesce(sum(amount),0) into v_used
      from project_billing_adjustments a
      join project_billings b on b.id = a.billing_id and b.company_id = a.company_id
      where a.company_id = v_billing.company_id and b.project_id = v_billing.project_id
        and (v_cap_scope_contract is null or b.contract_id is not distinct from v_cap_scope_contract)
        and b.status <> 'Cancelled'
        and a.adjustment_type = 'retention' and a.approval_status = 'Approved';
      if v_cap > 0 and v_adjustment.amount > greatest(0, v_cap - v_used) then
        raise exception 'Retention withholding exceeds the configured cap.';
      end if;
    end if;
  end if;
  update project_billing_adjustments
  set approval_status = case when p_approve then 'Approved' else 'Rejected' end,
      approved_by = auth.uid(), approved_at = now(), approval_note = nullif(btrim(p_note),'')
  where id = p_adjustment_id;
  perform public.refresh_project_billing_totals(v_billing.id);
  insert into project_billing_events(company_id, billing_id, actor_id, event_type, event_data)
  values (v_billing.company_id, v_billing.id, auth.uid(),
    case when p_approve then 'adjustment_approved' else 'adjustment_rejected' end,
    jsonb_build_object('adjustment_id',p_adjustment_id,'amount',v_adjustment.amount,'note',p_note));
  return jsonb_build_object('id',p_adjustment_id,'approval_status',
    case when p_approve then 'Approved' else 'Rejected' end);
end $$;

create or replace function public.refresh_project_billing_totals(p_billing_id uuid)
returns void
language plpgsql security definer set search_path = public as $$
declare
  v_billing project_billings%rowtype;
  v_additions numeric(18,2);
  v_construction numeric(18,2);
  v_other numeric(18,2);
  v_tax numeric(18,2);
  v_net numeric(18,2);
begin
  select * into v_billing from project_billings where id = p_billing_id for update;
  if not found then raise exception 'The billing record was not found.'; end if;
  select
    coalesce(sum(amount) filter (where adjustment_type in ('materials_on_site','other_addition')),0),
    coalesce(sum(amount) filter (where adjustment_type in (
      'mobilization_recovery','retention','previous_billing_adjustment',
      'liquidated_damages','backcharge','discount'
    )),0),
    coalesce(sum(amount) filter (where adjustment_type in ('other_deduction','other_withholding')),0)
  into v_additions, v_construction, v_other
  from project_billing_adjustments
  where billing_id = p_billing_id and company_id = v_billing.company_id
    and approval_status = 'Approved';
  v_tax := v_billing.withholding_vat_amount + v_billing.withholding_income_tax_amount
    + v_billing.other_tax_withholdings;
  v_net := v_billing.gross_amount + v_additions - v_tax - v_construction - v_other;
  if v_net < 0 then raise exception 'Approved deductions exceed the billing amount.'; end if;
  update project_billings set
    total_additions = v_additions,
    total_tax_deductions = v_tax,
    total_construction_deductions = v_construction,
    total_other_deductions = v_other,
    net_amount = v_net,
    last_updated_by = auth.uid(),
    last_updated_at = now()
  where id = p_billing_id;
end $$;

create or replace function public.record_project_billing_payment(
  p_billing_id uuid, p_amount numeric, p_payment_date date, p_payment_method text,
  p_reference_number text, p_bank_reference text, p_supporting_document_path text,
  p_remarks text, p_idempotency_key uuid
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_billing project_billings%rowtype;
  v_collected numeric(18,2);
begin
  if not is_active_user() or not is_admin() then
    raise exception 'Only a Creator or company Admin can record a billing payment.';
  end if;
  if p_amount is null or p_amount <= 0 or p_idempotency_key is null then
    raise exception 'Enter a positive payment amount and a unique request key.';
  end if;
  select * into v_billing from project_billings
  where id = p_billing_id and company_id = current_company_id() for update;
  if not found or v_billing.status not in ('Approved','Certified','Paid') then
    raise exception 'Only an approved billing can receive a payment.';
  end if;
  if exists (select 1 from project_billing_collections
      where company_id = v_billing.company_id and idempotency_key = p_idempotency_key) then
    if not exists (select 1 from project_billing_collections
      where company_id = v_billing.company_id and idempotency_key = p_idempotency_key
        and billing_id = p_billing_id and amount = p_amount) then
      raise exception 'A payment request key cannot be reused for a different transaction.';
    end if;
    return jsonb_build_object('idempotent',true,'billing_id',p_billing_id);
  end if;
  if p_amount > v_billing.net_amount - v_billing.collected_amount then
    raise exception 'Payment exceeds the outstanding billing balance.';
  end if;
  insert into project_billing_collections(
    company_id, billing_id, collection_date, amount, payment_method,
    reference_number, bank_reference, supporting_document_path, remarks,
    created_by, idempotency_key
  ) values (
    v_billing.company_id, p_billing_id, coalesce(p_payment_date,current_date),
    p_amount, nullif(btrim(p_payment_method),''), nullif(btrim(p_reference_number),''),
    nullif(btrim(p_bank_reference),''), nullif(btrim(p_supporting_document_path),''),
    nullif(btrim(p_remarks),''), auth.uid(), p_idempotency_key
  );
  select coalesce(sum(amount),0) into v_collected
  from project_billing_collections
  where company_id = v_billing.company_id and billing_id = p_billing_id;
  update project_billings set
    collected_amount = v_collected,
    payment_status = case when v_collected >= net_amount then 'Paid'
      when v_collected > 0 then 'Partial' else 'Unpaid' end,
    last_updated_by = auth.uid(), last_updated_at = now()
  where id = p_billing_id;
  insert into project_billing_events(company_id, billing_id, actor_id, event_type, event_data)
  values (v_billing.company_id, p_billing_id, auth.uid(), 'payment_recorded',
    jsonb_build_object('amount',p_amount,'payment_method',p_payment_method,'reference',p_reference_number));
  return jsonb_build_object('billing_id',p_billing_id,'collected_amount',v_collected);
end $$;

create or replace function public.attach_project_billing_documents(
  p_billing_id uuid, p_documents jsonb
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_company_id uuid := current_company_id();
  v_billing project_billings%rowtype;
  v_document jsonb;
  v_name text;
  v_path text;
  v_type text;
  v_key uuid;
  v_added integer := 0;
begin
  if not is_active_user() or not is_admin() then
    raise exception 'Only a Creator or company Admin can attach billing documents.';
  end if;
  if p_documents is null or jsonb_typeof(p_documents) <> 'array' or jsonb_array_length(p_documents) = 0 then
    raise exception 'Choose at least one billing document.';
  end if;
  select * into v_billing from project_billings
  where id = p_billing_id and company_id = v_company_id for update;
  if not found or v_billing.status = 'Cancelled' then
    raise exception 'Documents cannot be attached to a cancelled or unavailable billing.';
  end if;
  if jsonb_array_length(p_documents) > 10 then
    raise exception 'A billing may receive at most 10 files per upload.';
  end if;
  for v_document in select value from jsonb_array_elements(p_documents) loop
    v_name := nullif(btrim(v_document->>'name'),'');
    v_path := nullif(btrim(v_document->>'document_path'),'');
    v_type := nullif(btrim(v_document->>'attachment_type'),'');
    v_key := nullif(v_document->>'idempotency_key','')::uuid;
    if v_name is null or length(v_name) > 255 or v_path is null or v_key is null
      or v_path !~ '^[A-Za-z0-9._/-]+$' or position('..' in v_path) > 0
      or left(v_path,length(p_billing_id::text)+1) <> (p_billing_id::text || '/')
      or lower(v_name) !~ '\.(png|jpe?g|pdf)$'
      or v_type not in ('application/pdf','image/png','image/jpeg')
      or (lower(v_name) ~ '\.pdf$' and v_type <> 'application/pdf')
      or (lower(v_name) ~ '\.png$' and v_type <> 'image/png')
      or (lower(v_name) ~ '\.jpe?g$' and v_type <> 'image/jpeg') then
      raise exception 'A billing document has invalid metadata or storage path.';
    end if;
    if not exists (
      select 1 from storage.objects
      where bucket_id = 'receipts' and name = v_path
    ) then
      raise exception 'Upload each billing document before attaching it.';
    end if;
    if exists (
      select 1 from project_billing_attachments
      where company_id = v_company_id and idempotency_key = v_key
    ) then
      if not exists (
        select 1 from project_billing_attachments
        where company_id = v_company_id and idempotency_key = v_key
          and billing_id = p_billing_id and document_path = v_path
          and attachment_name = v_name
      ) then
        raise exception 'A document request key cannot be reused for a different file.';
      end if;
      continue;
    end if;
    insert into project_billing_attachments(
      company_id, billing_id, attachment_name, document_path, attachment_type,
      created_by, idempotency_key
    ) values (
      v_company_id, p_billing_id, v_name, v_path, v_type, auth.uid(), v_key
    );
    v_added := v_added + 1;
  end loop;
  if v_added > 0 then
    insert into project_billing_events(company_id, billing_id, actor_id, event_type, event_data)
    values (v_company_id, p_billing_id, auth.uid(), 'documents_attached',
      jsonb_build_object('count',v_added));
  end if;
  return jsonb_build_object('billing_id',p_billing_id,'documents_added',v_added);
end $$;

create or replace function public.release_project_billing_retention(
  p_project_id uuid, p_contract_id uuid, p_amount numeric, p_release_date date,
  p_reference_number text, p_document_path text, p_notes text, p_idempotency_key uuid
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_company_id uuid := current_company_id();
  v_withheld numeric(18,2);
  v_released numeric(18,2);
begin
  if not is_active_user() or not is_admin() then
    raise exception 'Only a Creator or company Admin can release retention.';
  end if;
  if p_amount is null or p_amount <= 0 or p_idempotency_key is null then
    raise exception 'Enter a positive retention release amount and a unique request key.';
  end if;
  perform 1 from projects where id = p_project_id and company_id = v_company_id for update;
  if not found then raise exception 'The selected project is not available in this company.'; end if;
  if p_contract_id is not null and not exists (
    select 1 from project_contracts
    where id = p_contract_id and project_id = p_project_id and company_id = v_company_id
  ) then raise exception 'The selected contract does not belong to this project.'; end if;
  if exists (select 1 from project_billing_retention_releases
    where company_id = v_company_id and idempotency_key = p_idempotency_key) then
    if not exists (select 1 from project_billing_retention_releases
      where company_id = v_company_id and idempotency_key = p_idempotency_key
        and project_id = p_project_id
        and contract_id is not distinct from p_contract_id and amount = p_amount) then
      raise exception 'A retention request key cannot be reused for a different transaction.';
    end if;
    return jsonb_build_object('idempotent',true,'project_id',p_project_id);
  end if;
  select coalesce(sum(a.amount),0) into v_withheld
  from project_billing_adjustments a
  join project_billings b on b.id = a.billing_id and b.company_id = a.company_id
  where a.company_id = v_company_id and b.project_id = p_project_id
    and (p_contract_id is null or b.contract_id is not distinct from p_contract_id)
    and a.adjustment_type = 'retention' and a.approval_status = 'Approved'
    and b.status in ('Approved','Certified','Paid','Overdue');
  v_withheld := v_withheld + coalesce((
    select sum(r.held_amount)
    from retention_ledger r
    left join project_billings b on b.id = r.billing_id and b.company_id = r.company_id
    where r.company_id = v_company_id and r.project_id = p_project_id
      and (p_contract_id is null or b.contract_id is not distinct from p_contract_id)
  ),0);
  select coalesce(sum(amount),0) into v_released
  from project_billing_retention_releases
  where company_id = v_company_id and project_id = p_project_id
    and (p_contract_id is null or contract_id is not distinct from p_contract_id);
  v_released := v_released + coalesce((
    select sum(r.released_amount)
    from retention_ledger r
    left join project_billings b on b.id = r.billing_id and b.company_id = r.company_id
    where r.company_id = v_company_id and r.project_id = p_project_id
      and (p_contract_id is null or b.contract_id is not distinct from p_contract_id)
  ),0);
  if p_amount > greatest(0, v_withheld - v_released) then
    raise exception 'Retention release exceeds the outstanding retained balance.';
  end if;
  insert into project_billing_retention_releases(
    company_id, project_id, contract_id, amount, release_date, reference_number,
    document_path, notes, created_by, idempotency_key
  ) values (
    v_company_id, p_project_id, p_contract_id, p_amount, coalesce(p_release_date,current_date),
    nullif(btrim(p_reference_number),''), nullif(btrim(p_document_path),''),
    nullif(btrim(p_notes),''), auth.uid(), p_idempotency_key
  );
  return jsonb_build_object('project_id',p_project_id,'total_retention_withheld',v_withheld,
    'total_retention_released',v_released + p_amount,
    'outstanding_retention',v_withheld - v_released - p_amount);
end $$;

create or replace function public.decide_project_billing(
  p_billing_id uuid, p_approve boolean, p_note text default null
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_billing project_billings%rowtype;
begin
  if not is_active_user() or not is_admin() then
    raise exception 'Only a Creator or company Admin can approve a billing.';
  end if;
  select * into v_billing from project_billings
  where id = p_billing_id and company_id = current_company_id() for update;
  if not found or v_billing.status not in ('Draft','Submitted','Under Review') then
    raise exception 'This billing cannot be approved in its current state.';
  end if;
  if exists (
    select 1 from project_billing_adjustments
    where company_id = v_billing.company_id and billing_id = p_billing_id
      and approval_status = 'Pending'
  ) then raise exception 'Review every pending adjustment before approving this billing.'; end if;
  update project_billings
  set status = case when p_approve then 'Approved' else 'Cancelled' end,
      approved_by = case when p_approve then auth.uid() else approved_by end,
      approval_date = case when p_approve then now() else approval_date end,
      reversal_reason = case when not p_approve then nullif(btrim(p_note),'') else reversal_reason end,
      reversed_at = case when not p_approve then now() else reversed_at end,
      last_updated_by = auth.uid(), last_updated_at = now()
  where id = p_billing_id;
  insert into project_billing_events(company_id, billing_id, actor_id, event_type, event_data)
  values (v_billing.company_id, p_billing_id, auth.uid(),
    case when p_approve then 'billing_approved' else 'billing_reversed' end,
    jsonb_build_object('note',p_note));
  return jsonb_build_object('id',p_billing_id,'status',case when p_approve then 'Approved' else 'Cancelled' end);
end $$;

create or replace function public.reverse_project_billing(
  p_billing_id uuid, p_reason text
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_billing project_billings%rowtype;
begin
  if not is_active_user() or not is_admin() then
    raise exception 'Only a Creator or company Admin can reverse a billing.';
  end if;
  if nullif(btrim(p_reason),'') is null then
    raise exception 'Enter a reason for reversing this billing.';
  end if;
  select * into v_billing from project_billings
  where id = p_billing_id and company_id = current_company_id() for update;
  if not found or v_billing.status not in ('Approved','Certified') then
    raise exception 'Only an approved unpaid billing can be reversed.';
  end if;
  if v_billing.collected_amount > 0 or exists (
    select 1 from project_billing_collections
    where company_id = v_billing.company_id and billing_id = p_billing_id
  ) then
    raise exception 'This billing has payment records. Resolve the payments before reversing it.';
  end if;
  if exists (
    select 1 from project_billing_retention_releases r
    where r.company_id = v_billing.company_id and r.project_id = v_billing.project_id
      and (r.contract_id is null or r.contract_id is not distinct from v_billing.contract_id)
  ) and exists (
    select 1 from project_billing_adjustments a
    where a.company_id = v_billing.company_id and a.billing_id = p_billing_id
      and a.adjustment_type = 'retention' and a.approval_status = 'Approved'
  ) then
    raise exception 'Retention has already been released for this project or contract.';
  end if;
  update project_billings
  set status = 'Cancelled', reversal_reason = btrim(p_reason),
      reversed_at = now(), last_updated_by = auth.uid(), last_updated_at = now()
  where id = p_billing_id;
  insert into project_billing_events(company_id, billing_id, actor_id, event_type, event_data)
  values (v_billing.company_id, p_billing_id, auth.uid(), 'billing_reversed',
    jsonb_build_object('reason',btrim(p_reason),'previous_status',v_billing.status));
  return jsonb_build_object('id',p_billing_id,'status','Cancelled');
end $$;

revoke all on function public.refresh_project_billing_totals(uuid) from public, anon, authenticated;
grant execute on function public.refresh_project_billing_totals(uuid) to service_role;
revoke all on function public.save_project_billing_configuration(uuid,uuid,jsonb) from public, anon;
revoke all on function public.create_project_billing(jsonb) from public, anon;
revoke all on function public.decide_project_billing_adjustment(uuid,boolean,text) from public, anon;
revoke all on function public.record_project_billing_payment(uuid,numeric,date,text,text,text,text,text,uuid) from public, anon;
revoke all on function public.attach_project_billing_documents(uuid,jsonb) from public, anon;
revoke all on function public.release_project_billing_retention(uuid,uuid,numeric,date,text,text,text,uuid) from public, anon;
revoke all on function public.decide_project_billing(uuid,boolean,text) from public, anon;
revoke all on function public.reverse_project_billing(uuid,text) from public, anon;
grant execute on function public.save_project_billing_configuration(uuid,uuid,jsonb) to authenticated;
grant execute on function public.create_project_billing(jsonb) to authenticated;
grant execute on function public.decide_project_billing_adjustment(uuid,boolean,text) to authenticated;
grant execute on function public.record_project_billing_payment(uuid,numeric,date,text,text,text,text,text,uuid) to authenticated;
grant execute on function public.attach_project_billing_documents(uuid,jsonb) to authenticated;
grant execute on function public.release_project_billing_retention(uuid,uuid,numeric,date,text,text,text,uuid) to authenticated;
grant execute on function public.decide_project_billing(uuid,boolean,text) to authenticated;
grant execute on function public.reverse_project_billing(uuid,text) to authenticated;
