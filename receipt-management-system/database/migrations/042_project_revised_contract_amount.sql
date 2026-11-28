-- RMS 042: editable revised contract amounts for project records.

alter table public.projects
  add column if not exists revised_contract_amount numeric(18,2);

update public.projects
set revised_contract_amount = contract_amount
where revised_contract_amount is null;

alter table public.projects
  alter column revised_contract_amount set default 0,
  alter column revised_contract_amount set not null;
