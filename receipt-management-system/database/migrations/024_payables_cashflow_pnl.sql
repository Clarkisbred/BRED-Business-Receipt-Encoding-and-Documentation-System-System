-- RMS 024: project expenses, payables, cash flow and Profit & Loss.

create table if not exists public.project_expense_ledger (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid references projects(id) on delete set null,
  receipt_id uuid references receipts(id) on delete set null,
  supplier_id uuid references suppliers(id) on delete set null,
  expense_category text not null,
  expense_date date not null,
  description text,
  amount numeric(18,2) not null,
  approved boolean not null default false,
  approved_by uuid references profiles(id) on delete set null,
  approved_at timestamptz,
  created_at timestamptz not null default now(),
  constraint project_expense_amount_check check (amount >= 0)
);

create table if not exists public.project_payables (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid references projects(id) on delete set null,
  supplier_id uuid references suppliers(id) on delete restrict,
  invoice_number text not null,
  invoice_date date not null,
  due_date date,
  description text,
  gross_amount numeric(18,2) not null,
  withholding_amount numeric(18,2) not null default 0,
  paid_amount numeric(18,2) not null default 0,
  status text not null default 'Draft',
  approved boolean not null default false,
  created_at timestamptz not null default now(),
  constraint payable_amount_check
    check (gross_amount >= 0 and withholding_amount >= 0 and paid_amount >= 0),
  constraint payable_status_check
    check (status in ('Draft','Submitted','Approved','Partially Paid','Paid','Overdue','Cancelled'))
);

create table if not exists public.project_payments (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid references projects(id) on delete set null,
  payable_id uuid references project_payables(id) on delete set null,
  supplier_id uuid references suppliers(id) on delete set null,
  payment_date date not null,
  amount numeric(18,2) not null,
  withholding_amount numeric(18,2) not null default 0,
  payment_method text not null default 'Bank',
  reference_number text,
  remarks text,
  approved boolean not null default false,
  approved_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  constraint project_payment_amount_check
    check (amount >= 0 and withholding_amount >= 0)
);

create table if not exists public.project_cash_movements (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid references projects(id) on delete set null,
  movement_date date not null,
  movement_type text not null,
  category text,
  amount numeric(18,2) not null,
  reference_number text,
  remarks text,
  approved boolean not null default false,
  created_at timestamptz not null default now(),
  constraint cash_movement_type_check
    check (movement_type in ('Opening Balance','Collection','Payment','Advance','Transfer','Adjustment')),
  constraint cash_movement_amount_check check (amount >= 0)
);

create table if not exists public.project_pnl_snapshots (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid references projects(id) on delete set null,
  period_start date not null,
  period_end date not null,
  revenue numeric(18,2) not null default 0,
  approved_expenses numeric(18,2) not null default 0,
  profit numeric(18,2) not null default 0,
  margin_percent numeric(9,4),
  status text not null,
  approved boolean not null default false,
  approved_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  constraint pnl_amount_check
    check (revenue >= 0 and approved_expenses >= 0),
  constraint pnl_status_check
    check (status in ('Profit','Loss','Break-even'))
);

create unique index if not exists project_payable_invoice_company_idx
  on public.project_payables(company_id, supplier_id, lower(btrim(invoice_number)));
create index if not exists project_expense_date_idx
  on public.project_expense_ledger(company_id, project_id, expense_date);
create index if not exists project_payable_due_idx
  on public.project_payables(company_id, due_date);
create index if not exists project_cash_movement_date_idx
  on public.project_cash_movements(company_id, project_id, movement_date);

do $$
declare v_table text;
begin
  foreach v_table in array array[
    'project_expense_ledger','project_payables','project_payments',
    'project_cash_movements','project_pnl_snapshots'
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
