-- RMS 043: simplified VAT-exclusive withholding snapshots and remittance tracking.

alter table public.project_billings
  add column if not exists vat_exclusive_base numeric(18,2) not null default 0,
  add column if not exists total_withholding numeric(18,2) not null default 0,
  add column if not exists net_billing numeric(18,2) not null default 0,
  add column if not exists final_payable numeric(18,2) not null default 0,
  add column if not exists withholding_status text not null default 'Pending',
  add column if not exists withholding_remitted numeric(18,2) not null default 0,
  add column if not exists withholding_remaining numeric(18,2) not null default 0,
  add constraint project_billing_withholding_status_check
    check (withholding_status in ('Pending','Partially Paid','Paid')),
  add constraint project_billing_withholding_snapshot_check
    check (vat_exclusive_base >= 0 and total_withholding >= 0
      and net_billing >= 0 and final_payable >= 0
      and withholding_remitted >= 0 and withholding_remaining >= 0);

update public.project_billings
set withholding_vat_rate = case
      when withholding_vat_rate = 0 then 5
      else withholding_vat_rate end,
    withholding_income_tax_rate = case
      when withholding_income_tax_rate = 0 then 2
      else withholding_income_tax_rate end
where withholding_vat_amount = 0 and withholding_income_tax_amount = 0;

create table if not exists public.project_billing_withholding_remittances (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references public.companies(id) on delete restrict,
  billing_id uuid not null,
  remittance_date date not null,
  amount numeric(18,2) not null check (amount > 0),
  payment_reference text,
  proof_document_path text,
  confirmed_by uuid references public.profiles(id) on delete set null,
  confirmed_at timestamptz,
  created_by uuid references public.profiles(id) on delete set null,
  idempotency_key uuid not null,
  created_at timestamptz not null default now(),
  constraint project_billing_withholding_remittance_scope_fkey
    foreign key (billing_id, company_id)
    references public.project_billings(id, company_id) on delete restrict,
  constraint project_billing_withholding_remittance_idempotency_unique
    unique (company_id, idempotency_key)
);

create index if not exists project_billing_withholding_remittance_billing_idx
  on public.project_billing_withholding_remittances(company_id,billing_id,remittance_date);

create or replace function public.normalize_project_billing_withholding()
returns trigger
language plpgsql
as $$
declare
  v_base numeric(18,2);
  v_vat numeric(18,2);
  v_income numeric(18,2);
  v_total numeric(18,2);
begin
  v_base := round(coalesce(new.gross_amount,0) / 1.12, 2);
  v_vat := round(v_base * coalesce(new.withholding_vat_rate,0) / 100, 2);
  v_income := round(v_base * coalesce(new.withholding_income_tax_rate,0) / 100, 2);
  v_total := round(v_vat + v_income, 2);

  new.vat_exclusive_base := v_base;
  new.vat_base := v_base;
  new.vat_rate := 12;
  new.vat_amount := 0;
  new.net_of_vat := v_base;
  new.withholding_vat_base := v_base;
  new.withholding_vat_amount := v_vat;
  new.withholding_income_tax_base := v_base;
  new.withholding_income_tax_amount := v_income;
  new.other_tax_withholdings := 0;
  new.withholding_amount := v_total;
  new.total_tax_deductions := v_total;
  new.total_withholding := v_total;
  new.net_billing := greatest(0, round(coalesce(new.gross_amount,0) - v_total,2));
  new.final_payable := new.net_billing;
  new.withholding_remaining := greatest(0,
    v_total - coalesce(new.withholding_remitted,0));
  if coalesce(new.withholding_remitted,0) <= 0 then
    new.withholding_status := 'Pending';
  elsif coalesce(new.withholding_remitted,0) >= v_total then
    new.withholding_status := 'Paid';
  else
    new.withholding_status := 'Partially Paid';
  end if;
  new.net_amount := greatest(0, new.net_billing);
  return new;
end $$;

drop trigger if exists normalize_project_billing_withholding_trigger on public.project_billings;
create trigger normalize_project_billing_withholding_trigger
before insert or update of gross_amount, withholding_vat_rate,
  withholding_income_tax_rate, withholding_remitted
on public.project_billings
for each row execute function public.normalize_project_billing_withholding();

create or replace function public.refresh_project_billing_totals(p_billing_id uuid)
returns void
language plpgsql security definer set search_path = public as $$
declare
  v_billing project_billings%rowtype;
  v_additions numeric(18,2);
  v_construction numeric(18,2);
  v_other numeric(18,2);
  v_net numeric(18,2);
begin
  select * into v_billing from project_billings where id=p_billing_id for update;
  if not found then raise exception 'The billing record was not found.'; end if;
  select
    coalesce(sum(amount) filter (where adjustment_type in ('materials_on_site','other_addition')),0),
    coalesce(sum(amount) filter (where adjustment_type in (
      'mobilization_recovery','retention','previous_billing_adjustment',
      'liquidated_damages','backcharge','discount'
    )),0),
    coalesce(sum(amount) filter (where adjustment_type in ('other_deduction','other_withholding')),0)
  into v_additions,v_construction,v_other
  from project_billing_adjustments
  where billing_id=p_billing_id and company_id=v_billing.company_id
    and approval_status='Approved';
  v_net := greatest(0, v_billing.net_billing + v_additions - v_construction - v_other);
  update project_billings set
    total_additions=v_additions,
    total_construction_deductions=v_construction,
    total_other_deductions=v_other,
    final_payable=v_net,
    net_amount=v_net,
    last_updated_by=auth.uid(),
    last_updated_at=now()
  where id=p_billing_id;
end $$;

create or replace function public.record_project_billing_withholding_remittance(
  p_billing_id uuid,
  p_amount numeric,
  p_remittance_date date,
  p_payment_reference text,
  p_proof_document_path text,
  p_idempotency_key uuid
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_billing project_billings%rowtype;
  v_remitted numeric(18,2);
begin
  if not is_active_user() or not is_admin() then
    raise exception 'Only a Creator or company Admin can record tax remittance.';
  end if;
  if p_amount <= 0 or p_idempotency_key is null then
    raise exception 'Enter a positive remittance amount and unique request key.';
  end if;
  select * into v_billing
  from project_billings
  where id = p_billing_id and company_id = current_company_id()
  for update;
  if not found then raise exception 'The billing record was not found.'; end if;

  if exists (
    select 1 from project_billing_withholding_remittances
    where company_id = v_billing.company_id and idempotency_key = p_idempotency_key
  ) then
    return jsonb_build_object('id',p_billing_id,'idempotent',true);
  end if;

  select coalesce(sum(amount),0) into v_remitted
  from project_billing_withholding_remittances
  where company_id = v_billing.company_id and billing_id = p_billing_id;
  if v_remitted + p_amount > v_billing.total_withholding then
    raise exception 'Remittance cannot exceed the total withholding.';
  end if;

  insert into project_billing_withholding_remittances(
    company_id,billing_id,remittance_date,amount,payment_reference,
    proof_document_path,confirmed_by,confirmed_at,created_by,idempotency_key
  ) values (
    v_billing.company_id,p_billing_id,coalesce(p_remittance_date,current_date),
    round(p_amount,2),nullif(btrim(p_payment_reference),''),
    nullif(btrim(p_proof_document_path),''),auth.uid(),now(),auth.uid(),p_idempotency_key
  );
  v_remitted := v_remitted + round(p_amount,2);
  update project_billings
  set withholding_remitted = v_remitted,
      withholding_remaining = greatest(0,total_withholding-v_remitted),
      withholding_status = case
        when v_remitted <= 0 then 'Pending'
        when v_remitted >= total_withholding then 'Paid'
        else 'Partially Paid'
      end,
      last_updated_by = auth.uid(),
      last_updated_at = now()
  where id = p_billing_id;
  insert into project_billing_events(company_id,billing_id,actor_id,event_type,event_data)
  values (v_billing.company_id,p_billing_id,auth.uid(),'withholding_remittance_recorded',
    jsonb_build_object('amount',round(p_amount,2),
      'payment_reference',nullif(btrim(p_payment_reference),''),
      'remittance_date',coalesce(p_remittance_date,current_date)));
  return jsonb_build_object('id',p_billing_id,'amount',round(p_amount,2),
    'total_remitted',v_remitted);
end $$;

alter table public.project_billing_withholding_remittances enable row level security;
drop policy if exists project_billing_withholding_remittances_read on public.project_billing_withholding_remittances;
create policy project_billing_withholding_remittances_read
  on public.project_billing_withholding_remittances for select to authenticated
  using (is_active_user() and (is_creator() or company_id=current_company_id()));
revoke insert,update,delete on public.project_billing_withholding_remittances from authenticated;
grant select on public.project_billing_withholding_remittances to authenticated;
revoke all on function public.record_project_billing_withholding_remittance(uuid,numeric,date,text,text,uuid) from public,anon;
grant execute on function public.record_project_billing_withholding_remittance(uuid,numeric,date,text,text,uuid) to authenticated;
