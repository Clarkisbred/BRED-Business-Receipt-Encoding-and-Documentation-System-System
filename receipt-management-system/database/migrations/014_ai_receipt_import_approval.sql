-- AI imports use the existing receipt save RPC, with an explicit administrator and company gate.
create or replace function approve_ai_import_receipt(p jsonb) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  v_company_id uuid := current_company_id();
  v_supplier_id uuid := nullif(p->>'supplier_id', '')::uuid;
  v_supplier_company_id uuid;
  v_receipt_id uuid := nullif(p->>'id', '')::uuid;
  v_operation text := coalesce(nullif(p->>'operation', ''), 'create');
  v_attachment jsonb;
  v_storage_path text;
  v_payload jsonb;
begin
  if not is_active_user() then raise exception 'Not allowed.'; end if;
  if not exists (
    select 1 from profiles
    where id = auth.uid() and is_active and account_status = 'APPROVED' and role = 'admin'
  ) then raise exception 'Administrator role required for AI receipt import.'; end if;
  if v_company_id is null then
    raise exception 'Your administrator account is not associated with a company.';
  end if;
  if v_operation not in ('create', 'attachments') then
    raise exception 'Unsupported AI receipt import operation.';
  end if;
  if (v_operation = 'create' and v_receipt_id is not null)
     or (v_operation = 'attachments' and v_receipt_id is null) then
    raise exception 'AI receipt imports can only create new receipts or attach files to an approved receipt.';
  end if;

  if v_operation = 'attachments' then
    select supplier_id into v_supplier_id from receipts
    where id = v_receipt_id and company_id = v_company_id and encoded_by = auth.uid();
    if v_supplier_id is null then
      raise exception 'The imported receipt is not available in your company.';
    end if;
    if jsonb_typeof(coalesce(p->'attachments', '[]'::jsonb)) is distinct from 'array' then
      raise exception 'Attachment metadata is invalid.';
    end if;
    for v_attachment in select * from jsonb_array_elements(coalesce(p->'attachments', '[]'::jsonb)) loop
      v_storage_path := coalesce(v_attachment->>'storage_path', '');
      if v_storage_path not like v_receipt_id::text || '/%'
         or not exists (
           select 1 from storage.objects
           where bucket_id = 'receipts' and name = v_storage_path
         ) then
        raise exception 'The attachment must be uploaded to this private receipt first.';
      end if;
    end loop;
  elsif v_supplier_id is null then
    raise exception 'Choose a supplier in your company.';
  end if;

  select company_id into v_supplier_company_id from suppliers where id = v_supplier_id;
  if v_supplier_company_id is distinct from v_company_id then
    raise exception 'Choose a supplier in your company.';
  end if;

  if v_operation = 'create' then
    -- The caller cannot supply receipt ownership or pre-existing attachment metadata.
    v_payload := (p - 'id' - 'company_id' - 'attachments' - 'operation')
      || jsonb_build_object('attachments', '[]'::jsonb);
  else
    v_payload := p - 'company_id' - 'operation';
  end if;
  v_receipt_id := save_receipt(v_payload);

  if v_operation = 'create' then
    insert into activity_log (user_id, user_name, company_id, action, detail)
    select auth.uid(), full_name, v_company_id, 'ai_receipt_import.approved', 'AI receipt import approved'
    from profiles where id = auth.uid();
    insert into activity_log (user_id, user_name, company_id, action, detail)
    select auth.uid(), full_name, v_company_id, 'receipt.ai_import.created', 'Receipt created through AI import'
    from profiles where id = auth.uid();
  end if;

  return v_receipt_id;
end $$;

revoke all on function approve_ai_import_receipt(jsonb) from public, anon, authenticated;
grant execute on function approve_ai_import_receipt(jsonb) to authenticated;
