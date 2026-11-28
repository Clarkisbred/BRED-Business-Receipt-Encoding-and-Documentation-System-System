-- RMS 039: persist receipt expense classifications for filtering and summaries.
alter table receipts
  add column if not exists classification text not null default 'UNCLASSIFIED';

update receipts set classification = 'UNCLASSIFIED'
  where upper(btrim(classification)) = 'UNCLASSIFIED';

update receipts set classification = upper(btrim(classification))
  where classification is distinct from upper(btrim(classification));

create or replace function save_receipt(p jsonb) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  v_id uuid := nullif(p->>'id', '')::uuid;
  v_company_id uuid;
  v_existing boolean;
  v_no text := btrim(coalesce(p->>'receipt_no', ''));
  v_supplier uuid := nullif(p->>'supplier_id', '')::uuid;
  v_project uuid := nullif(p->>'project_id', '')::uuid;
  v_discount numeric := coalesce((p->>'discount')::numeric, 0);
  v_vat numeric := coalesce((p->>'vat')::numeric, 0);
  v_charges numeric := coalesce((p->>'additional_charges')::numeric, 0);
  v_ewt_rate numeric := coalesce((p->>'ewt_rate')::numeric, 0);
  v_payment_method text := coalesce(nullif(p->>'payment_method', ''), 'Cash');
  v_classification text := upper(btrim(coalesce(nullif(p->>'classification', ''), 'UNCLASSIFIED')));
  v_subtotal numeric := 0;
  v_item jsonb;
  v_att jsonb;
  v_line integer := 0;
begin
  if not is_active_user() then raise exception 'Not allowed.'; end if;
  if v_no = '' then raise exception 'Receipt number is required.'; end if;
  if p->>'receipt_date' is null then raise exception 'Receipt date is required.'; end if;
  if v_supplier is null then raise exception 'Choose a supplier.'; end if;
  if v_ewt_rate < 0 or v_ewt_rate > 100 then raise exception 'EWT percentage must be from 0 to 100.'; end if;
  if v_payment_method not in ('Cash', 'Card', 'E-wallet') then raise exception 'Choose Cash, Card, or E-wallet.'; end if;
  if v_classification = '' then raise exception 'Enter a category.'; end if;

  v_existing := v_id is not null and exists (select 1 from receipts where id = v_id);
  if v_existing then
    select company_id into v_company_id from receipts where id = v_id;
    if not is_creator() and v_company_id <> current_company_id() then raise exception 'Not allowed.'; end if;
  else
    select company_id into v_company_id from suppliers where id = v_supplier;
    if v_company_id is null then raise exception 'Choose a supplier in your company.'; end if;
    if not is_creator() and v_company_id <> current_company_id() then raise exception 'Not allowed.'; end if;
  end if;
  if not exists (select 1 from suppliers where id = v_supplier and company_id = v_company_id) then
    raise exception 'Choose a supplier in the receipt''s company.';
  end if;
  if v_project is not null and not exists (
    select 1 from projects where id = v_project and company_id = v_company_id
  ) then raise exception 'Choose a project in the receipt''s company.'; end if;
  if exists (
    select 1 from receipts r
      join suppliers existing_supplier on existing_supplier.id = r.supplier_id
      join suppliers selected_supplier on selected_supplier.id = v_supplier
    where r.company_id = v_company_id
      and lower(btrim(r.receipt_no)) = lower(btrim(v_no))
      and lower(btrim(existing_supplier.tin)) = lower(btrim(selected_supplier.tin))
      and (not v_existing or r.id <> v_id)
  ) then raise exception 'Duplicate receipt: % already exists for this supplier.', v_no; end if;
  if jsonb_typeof(p->'items') is distinct from 'array' or jsonb_array_length(p->'items') = 0 then
    raise exception 'Add at least one item.';
  end if;
  if v_discount < 0 or v_vat < 0 or v_charges < 0 then raise exception 'Discount, VAT and additional charges cannot be negative.'; end if;
  for v_item in select * from jsonb_array_elements(p->'items') loop
    if btrim(coalesce(v_item->>'description', '')) = '' then raise exception 'Every item needs a description.'; end if;
    if coalesce((v_item->>'quantity')::numeric, 0) <= 0 then raise exception 'Item quantity must be greater than zero.'; end if;
    if coalesce((v_item->>'unit_price')::numeric, -1) < 0 then raise exception 'Item unit price cannot be negative.'; end if;
    v_subtotal := v_subtotal + round((v_item->>'quantity')::numeric * (v_item->>'unit_price')::numeric, 2);
  end loop;
  if v_discount > v_subtotal then raise exception 'Discount cannot be more than the subtotal.'; end if;
  begin
    if v_existing then
      update receipts set receipt_no = v_no, receipt_date = (p->>'receipt_date')::date,
        receipt_type = coalesce(nullif(p->>'receipt_type', ''), 'official_receipt')::receipt_type,
        supplier_id = v_supplier, project_id = v_project, discount = v_discount, vat = v_vat,
        additional_charges = v_charges, notes = coalesce(p->>'notes', ''),
        payment_method = v_payment_method, ewt_rate = v_ewt_rate, classification = v_classification,
        modified_at = now()
        where id = v_id;
    else
      insert into receipts (id, company_id, receipt_no, receipt_date, receipt_type, supplier_id, project_id,
        discount, vat, additional_charges, notes, payment_method, ewt_rate, classification, encoded_by)
      values (coalesce(v_id, gen_random_uuid()), v_company_id, v_no, (p->>'receipt_date')::date,
        coalesce(nullif(p->>'receipt_type', ''), 'official_receipt')::receipt_type, v_supplier, v_project,
        v_discount, v_vat, v_charges, coalesce(p->>'notes', ''), v_payment_method, v_ewt_rate,
        v_classification, auth.uid())
        returning id into v_id;
    end if;
  exception when unique_violation then
    raise exception 'Duplicate receipt: % already exists for this supplier.', v_no;
  end;
  delete from receipt_items where receipt_id = v_id;
  for v_item in select * from jsonb_array_elements(p->'items') loop
    v_line := v_line + 1;
    insert into receipt_items (receipt_id, line_no, description, quantity, unit, unit_price)
    values (v_id, v_line, btrim(v_item->>'description'), (v_item->>'quantity')::numeric,
      coalesce(nullif(btrim(v_item->>'unit'), ''), 'pc'), (v_item->>'unit_price')::numeric);
  end loop;
  delete from receipt_attachments where receipt_id = v_id;
  for v_att in select * from jsonb_array_elements(coalesce(p->'attachments', '[]'::jsonb)) loop
    insert into receipt_attachments (receipt_id, file_name, storage_path)
    values (v_id, v_att->>'file_name', v_att->>'storage_path');
  end loop;
  insert into activity_log (user_id, user_name, company_id, action, detail)
  select auth.uid(), full_name, v_company_id, case when v_existing then 'receipt.update' else 'receipt.create' end, v_no
  from profiles where id = auth.uid();
  return v_id;
end $$;
