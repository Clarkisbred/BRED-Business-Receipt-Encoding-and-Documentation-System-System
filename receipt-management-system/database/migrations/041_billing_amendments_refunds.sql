-- RMS 041: authorized draft amendments and audited payment refunds.

alter table public.project_billings
  add column if not exists amendment_reason text,
  add column if not exists amended_from uuid references public.project_billings(id) on delete set null,
  add column if not exists amended_at timestamptz;

create table if not exists public.project_billing_refunds (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references public.companies(id) on delete restrict,
  billing_id uuid not null,
  refund_date date not null,
  amount numeric(18,2) not null check (amount > 0),
  reference_number text,
  reason text not null,
  created_by uuid references public.profiles(id) on delete set null,
  idempotency_key uuid not null,
  created_at timestamptz not null default now(),
  constraint project_billing_refund_billing_scope_fkey
    foreign key (billing_id, company_id)
    references public.project_billings(id, company_id) on delete restrict,
  constraint project_billing_refund_idempotency_scope_unique
    unique (company_id, idempotency_key)
);

create index if not exists project_billing_refund_billing_idx
  on public.project_billing_refunds(company_id, billing_id, refund_date);

alter table public.project_billing_refunds enable row level security;
drop policy if exists project_billing_refunds_read on public.project_billing_refunds;
drop policy if exists project_billing_refunds_write on public.project_billing_refunds;
create policy project_billing_refunds_read on public.project_billing_refunds for select to authenticated
  using (is_active_user() and (is_creator() or company_id = current_company_id()));
create policy project_billing_refunds_write on public.project_billing_refunds for all to authenticated
  using (is_active_user() and is_admin() and (is_creator() or company_id = current_company_id()))
  with check (is_active_user() and is_admin() and (is_creator() or company_id = current_company_id()));
grant select on public.project_billing_refunds to authenticated;
revoke insert, update, delete on public.project_billing_refunds from authenticated;

create or replace function public.amend_project_billing(
  p_billing_id uuid,
  p_payload jsonb,
  p_reason text
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_billing project_billings%rowtype;
  v_company_id uuid := current_company_id();
  v_gross numeric(18,2);
  v_current_gross numeric(18,2);
  v_vat_base numeric(18,2);
  v_vat_amount numeric(18,2);
  v_net_of_vat numeric(18,2);
  v_wht_vat numeric(18,2);
  v_wht_income numeric(18,2);
  v_vat_rate numeric(9,6);
  v_wht_vat_rate numeric(9,6);
  v_wht_income_rate numeric(9,6);
  v_other_tax numeric(18,2);
  v_item jsonb;
  v_details jsonb;
begin
  if not is_active_user() or not is_admin() then
    raise exception 'Only a Creator or company Admin can amend a billing.';
  end if;
  if nullif(btrim(p_reason),'') is null then
    raise exception 'Enter a reason for amending this billing.';
  end if;
  select * into v_billing
  from project_billings
  where id = p_billing_id and company_id = v_company_id
  for update;
  if not found then raise exception 'The billing record was not found.'; end if;
  if v_billing.status <> 'Draft' then
    raise exception 'Only a Draft billing can be amended directly.';
  end if;
  if v_billing.collected_amount > 0 or exists (
    select 1 from project_billing_collections
    where company_id = v_company_id and billing_id = p_billing_id
  ) then
    raise exception 'A billing with payments cannot be amended directly.';
  end if;
  if nullif(p_payload->>'project_id','')::uuid is distinct from v_billing.project_id
    or nullif(p_payload->>'contract_id','')::uuid is distinct from v_billing.contract_id then
    raise exception 'The billing amendment must remain on the original project and contract.';
  end if;

  v_gross := coalesce(nullif(p_payload->>'gross_amount','')::numeric, 0);
  if v_gross <= 0 then raise exception 'Gross billing must be greater than zero.'; end if;
  if coalesce(nullif(p_payload->>'billing_mode',''), v_billing.billing_mode) = 'cumulative' then
    if v_gross <= v_billing.previous_billed then
      raise exception 'Cumulative gross billing must exceed previously approved billings.';
    end if;
    v_current_gross := v_gross - v_billing.previous_billed;
  else
    v_current_gross := v_gross;
  end if;
  v_vat_rate := coalesce(nullif(p_payload->>'vat_rate','')::numeric, v_billing.vat_rate);
  v_wht_vat_rate := coalesce(nullif(p_payload->>'withholding_vat_rate','')::numeric, v_billing.withholding_vat_rate);
  v_wht_income_rate := coalesce(nullif(p_payload->>'withholding_income_tax_rate','')::numeric, v_billing.withholding_income_tax_rate);
  v_vat_base := coalesce(nullif(p_payload->>'vat_base','')::numeric, v_gross);
  v_other_tax := coalesce(nullif(p_payload->>'other_tax_withholdings','')::numeric, v_billing.other_tax_withholdings);
  v_vat_amount := round(v_vat_base * v_vat_rate / 100, 2);
  v_net_of_vat := greatest(0, v_current_gross - v_vat_amount);
  v_wht_vat := round(coalesce(nullif(p_payload->>'withholding_vat_base','')::numeric, v_vat_base)
    * v_wht_vat_rate / 100, 2);
  v_wht_income := round(coalesce(nullif(p_payload->>'withholding_income_tax_base','')::numeric, v_vat_base)
    * v_wht_income_rate / 100, 2);

  update project_billings
  set billing_date = coalesce(nullif(p_payload->>'billing_date','')::date, billing_date),
      period_start = nullif(p_payload->>'period_start','')::date,
      period_end = nullif(p_payload->>'period_end','')::date,
      due_date = nullif(p_payload->>'due_date','')::date,
      billing_type = coalesce(nullif(p_payload->>'billing_type',''), billing_type),
      billing_mode = coalesce(nullif(p_payload->>'billing_mode',''), billing_mode),
      description = coalesce(p_payload->>'description', ''),
      gross_amount = v_current_gross,
      cumulative_gross_amount = case when coalesce(nullif(p_payload->>'billing_mode',''), billing_mode) = 'cumulative'
        then v_gross else coalesce(v_billing.previous_billed,0) + v_gross end,
      vat_rate = v_vat_rate,
      vat_base = v_vat_base,
      vat_amount = v_vat_amount,
      net_of_vat = v_net_of_vat,
      withholding_vat_rate = v_wht_vat_rate,
      withholding_vat_base = coalesce(nullif(p_payload->>'withholding_vat_base','')::numeric, v_vat_base),
      withholding_vat_amount = v_wht_vat,
      withholding_income_tax_rate = v_wht_income_rate,
      withholding_income_tax_base = coalesce(nullif(p_payload->>'withholding_income_tax_base','')::numeric, v_vat_base),
      withholding_income_tax_amount = v_wht_income,
      other_tax_withholdings = v_other_tax,
      withholding_amount = v_wht_vat + v_wht_income + v_other_tax,
      total_tax_deductions = v_wht_vat + v_wht_income + v_other_tax,
      amendment_reason = btrim(p_reason),
      amended_from = v_billing.id,
      amended_at = now(),
      last_updated_by = auth.uid(),
      last_updated_at = now()
  where id = p_billing_id;

  update project_billing_adjustments
  set approval_status = 'Reversed',
      approved_by = null,
      approved_at = null,
      approval_note = btrim(p_reason),
      idempotency_key = null
  where company_id = v_company_id and billing_id = p_billing_id
    and approval_status in ('Pending','Approved');

  if jsonb_typeof(p_payload->'adjustments') = 'array' then
    for v_item in select value from jsonb_array_elements(p_payload->'adjustments') loop
      v_details := coalesce(v_item->'details','{}'::jsonb);
      if jsonb_typeof(v_details) <> 'object' then v_details := '{}'::jsonb; end if;
      insert into project_billing_adjustments(
        company_id, billing_id, adjustment_type, calculation_type, calculation_basis,
        basis_amount, rate, amount, details, approval_status, created_by, idempotency_key
      ) values (
        v_company_id, p_billing_id, coalesce(nullif(v_item->>'adjustment_type',''),'other_deduction'),
        coalesce(nullif(v_item->>'calculation_type',''),'fixed'),
        coalesce(nullif(v_item->>'calculation_basis',''),'current_period'),
        coalesce(nullif(v_item->>'basis_amount','')::numeric,0),
        coalesce(nullif(v_item->>'rate','')::numeric,0),
        greatest(0, coalesce(nullif(v_item->>'amount','')::numeric,0)),
        v_details, 'Pending', auth.uid(),
        nullif(v_item->>'idempotency_key','')::uuid
      );
    end loop;
  end if;

  insert into project_billing_events(company_id,billing_id,actor_id,event_type,event_data)
  values (v_company_id,p_billing_id,auth.uid(),'billing_amended',
    jsonb_build_object('reason',btrim(p_reason),'gross_amount',v_gross));
  perform refresh_project_billing_totals(p_billing_id);
  return jsonb_build_object('id',p_billing_id,'status','Draft','amended',true);
end $$;

create or replace function public.refund_project_billing_payment(
  p_billing_id uuid,
  p_amount numeric,
  p_refund_date date,
  p_reference_number text,
  p_reason text,
  p_idempotency_key uuid
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_billing project_billings%rowtype;
  v_collected numeric(18,2);
  v_refunded numeric(18,2);
begin
  if not is_active_user() or not is_admin() then
    raise exception 'Only a Creator or company Admin can record a payment refund.';
  end if;
  if p_amount <= 0 or p_idempotency_key is null or nullif(btrim(p_reason),'') is null then
    raise exception 'Enter a positive refund amount, reason, and unique request key.';
  end if;
  select * into v_billing
  from project_billings
  where id = p_billing_id and company_id = current_company_id()
  for update;
  if not found then raise exception 'The billing record was not found.'; end if;
  select coalesce(sum(amount),0) into v_refunded
  from project_billing_refunds
  where company_id = v_billing.company_id and billing_id = p_billing_id;
  select coalesce(sum(amount),0) into v_collected
  from project_billing_collections
  where company_id = v_billing.company_id and billing_id = p_billing_id;
  if exists (
    select 1 from project_billing_refunds
    where company_id = v_billing.company_id and idempotency_key = p_idempotency_key
  ) then
    if not exists (
      select 1 from project_billing_refunds
      where company_id = v_billing.company_id and idempotency_key = p_idempotency_key
        and billing_id = p_billing_id and amount = round(p_amount,2)
    ) then
      raise exception 'A refund request key cannot be reused for a different transaction.';
    end if;
    select coalesce(sum(amount),0) into v_refunded
    from project_billing_refunds
    where company_id = v_billing.company_id and billing_id = p_billing_id;
    select coalesce(sum(amount),0) into v_collected
    from project_billing_collections
    where company_id = v_billing.company_id and billing_id = p_billing_id;
    v_collected := greatest(0, v_collected - v_refunded);
    return jsonb_build_object('id',p_billing_id,'idempotent',true,
      'collected_amount',v_collected);
  end if;
  if p_amount > greatest(0, v_collected - v_refunded) then
    raise exception 'Refund cannot exceed the collected amount.';
  end if;

  insert into project_billing_refunds(
    company_id,billing_id,refund_date,amount,reference_number,reason,
    created_by,idempotency_key
  ) values (
    v_billing.company_id,p_billing_id,coalesce(p_refund_date,current_date),
    round(p_amount,2),nullif(btrim(p_reference_number),''),
    btrim(p_reason),auth.uid(),p_idempotency_key
  );
  select coalesce(sum(amount),0) into v_collected
  from project_billing_collections
  where company_id = v_billing.company_id and billing_id = p_billing_id;
  select coalesce(sum(amount),0) into v_refunded
  from project_billing_refunds
  where company_id = v_billing.company_id and billing_id = p_billing_id;
  v_collected := greatest(0, v_collected - v_refunded);
  update project_billings
  set collected_amount = greatest(0,v_collected),
      payment_status = case when v_collected <= 0 then 'Unpaid'
                            when v_collected < net_amount then 'Partial'
                            else 'Paid' end,
      last_updated_by = auth.uid(),
      last_updated_at = now()
  where id = p_billing_id;
  insert into project_billing_events(company_id,billing_id,actor_id,event_type,event_data)
  values (v_billing.company_id,p_billing_id,auth.uid(),'payment_refunded',
    jsonb_build_object('amount',p_amount,'reason',btrim(p_reason),
      'reference',nullif(btrim(p_reference_number),'')));
  return jsonb_build_object('id',p_billing_id,'refund_amount',p_amount,
    'collected_amount',greatest(0,v_collected));
end $$;

revoke all on function public.amend_project_billing(uuid,jsonb,text) from public, anon;
revoke all on function public.refund_project_billing_payment(uuid,numeric,date,text,text,uuid) from public, anon;
grant execute on function public.amend_project_billing(uuid,jsonb,text) to authenticated;
grant execute on function public.refund_project_billing_payment(uuid,numeric,date,text,text,uuid) to authenticated;
