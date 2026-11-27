-- RMS 032: keep bid/estimate relationships within one company.

create unique index if not exists bids_id_company_scope_idx
  on public.bids(id, company_id);

create unique index if not exists cost_estimates_id_company_scope_idx
  on public.cost_estimates(id, company_id);

alter table public.cost_estimates
  drop constraint if exists cost_estimates_bid_id_fkey;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'cost_estimates_bid_company_scope_fkey'
      and conrelid = 'public.cost_estimates'::regclass
  ) then
    alter table public.cost_estimates
      add constraint cost_estimates_bid_company_scope_fkey
      foreign key (bid_id, company_id)
      references public.bids(id, company_id)
      on delete set null (bid_id);
  end if;
end $$;

alter table public.cost_estimate_items
  drop constraint if exists cost_estimate_items_estimate_id_fkey;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'cost_estimate_items_estimate_company_scope_fkey'
      and conrelid = 'public.cost_estimate_items'::regclass
  ) then
    alter table public.cost_estimate_items
      add constraint cost_estimate_items_estimate_company_scope_fkey
      foreign key (estimate_id, company_id)
      references public.cost_estimates(id, company_id)
      on delete cascade;
  end if;
end $$;
