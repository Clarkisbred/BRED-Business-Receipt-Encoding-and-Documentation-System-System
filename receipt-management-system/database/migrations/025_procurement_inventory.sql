-- RMS 025: procurement, purchase orders, goods receipts and inventory.

create table if not exists public.purchase_requisitions (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid references projects(id) on delete set null,
  requisition_number text not null,
  requested_by uuid references profiles(id) on delete set null,
  request_date date not null,
  required_date date,
  description text,
  status text not null default 'Draft',
  approved boolean not null default false,
  approved_by uuid references profiles(id) on delete set null,
  approved_at timestamptz,
  created_at timestamptz not null default now(),
  constraint requisition_status_check
    check (status in ('Draft','Submitted','Approved','Partially Ordered','Ordered','Cancelled','Closed'))
);

create table if not exists public.purchase_requisition_items (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  requisition_id uuid not null references purchase_requisitions(id) on delete cascade,
  description text not null,
  quantity numeric(18,4) not null,
  unit text,
  estimated_unit_price numeric(18,2) not null default 0,
  boq_item_id uuid references project_boq_items(id) on delete set null,
  constraint requisition_item_check
    check (quantity >= 0 and estimated_unit_price >= 0)
);

create table if not exists public.purchase_orders (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid references projects(id) on delete set null,
  requisition_id uuid references purchase_requisitions(id) on delete set null,
  supplier_id uuid not null references suppliers(id) on delete restrict,
  po_number text not null,
  order_date date not null,
  expected_date date,
  subtotal numeric(18,2) not null default 0,
  discount numeric(18,2) not null default 0,
  tax numeric(18,2) not null default 0,
  freight numeric(18,2) not null default 0,
  total_amount numeric(18,2) not null default 0,
  received_amount numeric(18,2) not null default 0,
  status text not null default 'Draft',
  approved boolean not null default false,
  approved_by uuid references profiles(id) on delete set null,
  attachment_path text,
  created_at timestamptz not null default now(),
  constraint purchase_order_amount_check
    check (subtotal >= 0 and discount >= 0 and tax >= 0 and freight >= 0
           and total_amount >= 0 and received_amount >= 0),
  constraint purchase_order_status_check
    check (status in ('Draft','Submitted','Approved','Partially Received','Received','Closed','Cancelled'))
);

create table if not exists public.purchase_order_items (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  po_id uuid not null references purchase_orders(id) on delete cascade,
  requisition_item_id uuid references purchase_requisition_items(id) on delete set null,
  description text not null,
  quantity numeric(18,4) not null,
  received_quantity numeric(18,4) not null default 0,
  unit text,
  unit_price numeric(18,2) not null,
  amount numeric(18,2) not null default 0,
  constraint purchase_order_item_check
    check (quantity >= 0 and received_quantity >= 0 and unit_price >= 0 and amount >= 0)
);

create table if not exists public.goods_receipts (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  po_id uuid not null references purchase_orders(id) on delete cascade,
  project_id uuid references projects(id) on delete set null,
  receipt_number text not null,
  receipt_date date not null,
  received_by uuid references profiles(id) on delete set null,
  status text not null default 'Draft',
  approved boolean not null default false,
  attachment_path text,
  created_at timestamptz not null default now(),
  constraint goods_receipt_status_check
    check (status in ('Draft','Submitted','Inspected','Accepted','Rejected','Cancelled'))
);

create table if not exists public.goods_receipt_items (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  receipt_id uuid not null references goods_receipts(id) on delete cascade,
  po_item_id uuid not null references purchase_order_items(id) on delete restrict,
  quantity_received numeric(18,4) not null,
  accepted_quantity numeric(18,4) not null default 0,
  rejected_quantity numeric(18,4) not null default 0,
  unit text,
  unit_cost numeric(18,2) not null,
  remarks text,
  constraint goods_receipt_item_check
    check (quantity_received >= 0 and accepted_quantity >= 0
           and rejected_quantity >= 0 and unit_cost >= 0)
);

create table if not exists public.material_stock (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  project_id uuid references projects(id) on delete set null,
  material_code text not null,
  description text not null,
  unit text not null,
  quantity numeric(18,4) not null default 0,
  weighted_cost numeric(18,2) not null default 0,
  warehouse text,
  status text not null default 'Active',
  created_at timestamptz not null default now(),
  constraint material_stock_quantity_check check (quantity >= 0),
  constraint material_stock_cost_check check (weighted_cost >= 0)
);

create table if not exists public.material_stock_movements (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null default current_company_id() references companies(id) on delete restrict,
  stock_id uuid not null references material_stock(id) on delete cascade,
  project_id uuid references projects(id) on delete set null,
  movement_date date not null,
  movement_type text not null,
  quantity numeric(18,4) not null,
  unit_cost numeric(18,2) not null default 0,
  reference_number text,
  from_location text,
  to_location text,
  receipt_id uuid references receipts(id) on delete set null,
  remarks text,
  approved boolean not null default false,
  approved_by uuid references profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  constraint stock_movement_type_check
    check (movement_type in ('Purchase Receipt','Issue','Transfer In','Transfer Out',
                             'Return to Supplier','Adjustment','Opening Balance')),
  constraint stock_movement_quantity_check check (quantity >= 0),
  constraint stock_movement_cost_check check (unit_cost >= 0)
);

create unique index if not exists purchase_requisition_number_company_idx
  on public.purchase_requisitions(company_id, lower(btrim(requisition_number)));
create unique index if not exists purchase_order_number_company_idx
  on public.purchase_orders(company_id, lower(btrim(po_number)));
create unique index if not exists goods_receipt_number_company_idx
  on public.goods_receipts(company_id, lower(btrim(receipt_number)));
create unique index if not exists material_stock_code_company_idx
  on public.material_stock(company_id, project_id, lower(btrim(material_code)));

do $$
declare v_table text;
begin
  foreach v_table in array array[
    'purchase_requisitions','purchase_requisition_items','purchase_orders',
    'purchase_order_items','goods_receipts','goods_receipt_items',
    'material_stock','material_stock_movements'
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
