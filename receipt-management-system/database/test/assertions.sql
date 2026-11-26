-- Behavioural checks for the RMS schema. Every check prints "ok - ..." or aborts with an error.
create schema tests;
grant usage on schema tests to authenticated;

create function tests.expect_error(stmt text, needle text) returns void language plpgsql as $$
begin
  begin
    execute stmt;
  exception when others then
    if position(lower(needle) in lower(sqlerrm)) = 0 then
      raise exception 'FAIL expected error containing "%" but got "%" for: %', needle, sqlerrm, stmt;
    end if;
    raise notice 'ok - raises "%"', needle;
    return;
  end;
  raise exception 'FAIL no error raised (wanted "%") for: %', needle, stmt;
end $$;

create function tests.expect_true(cond boolean, msg text) returns void language plpgsql as $$
begin
  if cond is not true then raise exception 'FAIL %', msg; end if;
  raise notice 'ok - %', msg;
end $$;

create function tests.login(u uuid) returns void language sql as
$$ select set_config('request.jwt.claim.sub', coalesce(u::text, ''), false) $$;
grant execute on all functions in schema tests to authenticated;
grant usage on schema tests to service_role;
grant execute on all functions in schema tests to service_role;

-- fixtures (as superuser)
insert into auth.users(id, email) values
  ('a0000000-0000-0000-0000-000000000001', 'admin@x.test'),
  ('e0000000-0000-0000-0000-000000000002', 'enc@x.test'),
  ('f0000000-0000-0000-0000-000000000003', 'off@x.test'),
  ('70000000-0000-0000-0000-000000000004', 'pending@x.test'),
  ('80000000-0000-0000-0000-000000000005', 'reject@x.test'),
  ('90000000-0000-0000-0000-000000000006', 'new@x.test'),
  ('c0000000-0000-0000-0000-000000000007', 'creator@x.test'),
  ('d0000000-0000-0000-0000-000000000008', 'owner@newco.test');
insert into companies(id, name, is_active)
values ('11111111-0000-0000-0000-000000000001', 'Acme Ltd', true),
       ('33333333-0000-0000-0000-000000000003', 'Beta Ltd', true);
insert into profiles(id, full_name, email, role, is_active, account_status, company_id) values
  ('a0000000-0000-0000-0000-000000000001', 'Ada Admin', 'admin@x.test', 'admin', true, 'APPROVED', '11111111-0000-0000-0000-000000000001'),
  ('e0000000-0000-0000-0000-000000000002', 'Eli Encoder', 'enc@x.test', 'encoder', true, 'APPROVED', '11111111-0000-0000-0000-000000000001'),
  ('f0000000-0000-0000-0000-000000000003', 'Off Boarded', 'off@x.test', 'encoder', false, 'APPROVED', '11111111-0000-0000-0000-000000000001'),
  ('70000000-0000-0000-0000-000000000004', 'Pending Person', 'pending@x.test', 'encoder', false, 'PENDING', '11111111-0000-0000-0000-000000000001'),
  ('80000000-0000-0000-0000-000000000005', 'Rejected Person', 'reject@x.test', 'encoder', false, 'PENDING', '11111111-0000-0000-0000-000000000001'),
  ('c0000000-0000-0000-0000-000000000007', 'System Creator', 'creator@x.test', 'creator', true, 'APPROVED', '33333333-0000-0000-0000-000000000003');
insert into suppliers(id, business_name, tin, company_id) values
  ('11111111-0000-0000-0000-000000000001', 'Acme Hardware', '123-456-789-000', '11111111-0000-0000-0000-000000000001'),
  ('11111111-0000-0000-0000-000000000002', 'Beta Supplies', '987-654-321-000', '11111111-0000-0000-0000-000000000001'),
  ('33333333-0000-0000-0000-000000000003', 'Cross Company Supplies', '555-555-555-555', '33333333-0000-0000-0000-000000000003');
insert into projects(id, name, code, company_id)
values ('22222222-0000-0000-0000-000000000001', 'Bridge', 'BR-01', '11111111-0000-0000-0000-000000000001');

create function tests.payload(no text, supplier uuid, price text default '100.00', discount text default '0') returns jsonb
language sql as $$
  select jsonb_build_object('receipt_no', no, 'receipt_date', '2026-11-01', 'receipt_type', 'official_receipt',
    'supplier_id', supplier, 'project_id', '22222222-0000-0000-0000-000000000001',
    'discount', discount::numeric, 'vat', 0, 'additional_charges', 0,
    'items', jsonb_build_array(jsonb_build_object('description', 'Nails', 'quantity', 2, 'unit_price', price::numeric),
                               jsonb_build_object('description', 'Hammer', 'quantity', 1, 'unit_price', 50)),
    'attachments', jsonb_build_array(jsonb_build_object('file_name', 'scan.pdf', 'storage_path', 'x/' || lower(btrim(no)) || '-' || supplier || '.pdf')))
$$;
grant execute on function tests.payload(text, uuid, text, text) to authenticated;

set role authenticated;

-- ===== encoder =====
select tests.login('e0000000-0000-0000-0000-000000000002');
select tests.expect_true((select count(*) from profiles) = 1, 'encoder can read only their own profile');
select tests.expect_true((select save_receipt(tests.payload('OR-001', '11111111-0000-0000-0000-000000000001'))) is not null, 'encoder can save a receipt');
select tests.expect_error($$select approve_ai_import_receipt(tests.payload('AI-ENC', '11111111-0000-0000-0000-000000000001'))$$,
  'administrator role required');
select tests.expect_true((select count(*) from receipt_items) = 2 and (select count(*) from receipt_attachments) = 1, 'items and attachment rows were stored with the receipt');
select tests.expect_true((select encoded_by from receipts where receipt_no = 'OR-001') = 'e0000000-0000-0000-0000-000000000002', 'encoded_by is the caller');
select tests.expect_true((select save_receipt(jsonb_set(tests.payload('OR-001', '11111111-0000-0000-0000-000000000001', '300'), '{id}', to_jsonb((select id from receipts where receipt_no = 'OR-001')::text)))) = (select id from receipts where receipt_no = 'OR-001'), 'editing a receipt keeps its id and does not clash with itself');
select tests.expect_true((select count(*) from receipt_items where receipt_id = (select id from receipts where receipt_no = 'OR-001')) = 2 and (select unit_price from receipt_items where description = 'Nails') = 300, 'editing replaces the items');
select tests.expect_error($$select save_receipt(tests.payload('  or-001 ', '11111111-0000-0000-0000-000000000001'))$$, 'Duplicate receipt');
select tests.expect_true((select save_receipt(tests.payload('OR-001', '11111111-0000-0000-0000-000000000002'))) is not null, 'same receipt no. is fine for a different supplier');
select tests.expect_error($$select save_receipt(tests.payload('OR-NEG', '11111111-0000-0000-0000-000000000001', '-5'))$$, 'negative');
select tests.expect_error($$select save_receipt(tests.payload('OR-DISC', '11111111-0000-0000-0000-000000000001', '100', '999'))$$, 'Discount');
select tests.expect_error($$select save_receipt(jsonb_set(tests.payload('OR-EMPTY', '11111111-0000-0000-0000-000000000001'), '{items}', '[]'))$$, 'at least one item');
select tests.expect_error($$select log_activity('receipt.delete', 'forged')$$, 'permission denied');
select tests.expect_error($$insert into receipts(receipt_no, receipt_date, supplier_id) values ('DIRECT', current_date, '11111111-0000-0000-0000-000000000001')$$, 'permission denied');
select tests.expect_error($$select delete_receipt((select id from receipts limit 1))$$, 'administrator');
select tests.expect_error($$select admin_update_profile('e0000000-0000-0000-0000-000000000002', 'X', 'admin', true)$$, 'administrator');
select tests.expect_true((select count(*) from activity_log) = 0, 'encoder cannot read the activity log');
select tests.expect_error($$select * from creator_message_history()$$, 'Creator role required');
select tests.expect_error($$select creator_broadcast(array['c0000000-0000-0000-0000-000000000007']::uuid[], 'Notice')$$,
  'active Creator account is required');
delete from suppliers where id = '11111111-0000-0000-0000-000000000002';
select tests.expect_true((select count(*) from suppliers) = 2, 'encoder delete of a supplier removes nothing');
select tests.expect_true((select count(*) from companies) = 1, 'encoder cannot see another company');
insert into bug_reports(title, description) values ('Missing button', 'The save button did not respond.');
select tests.expect_true((select count(*) from bug_reports) = 1, 'reporter can read their own submitted report');
insert into storage.objects(bucket_id, name) values ('bug-reports', auth.uid()::text || '/screen.png');
select tests.expect_true((select count(*) from storage.objects where bucket_id = 'bug-reports') = 1,
  'reporter can upload an optional image');
insert into suppliers(business_name, tin) values ('Gamma', ' 555 ');
select tests.expect_error($$insert into suppliers(business_name, tin) values ('Gamma 2', '555')$$, 'duplicate key');
insert into storage.objects(bucket_id, name)
values ('receipts', (select id::text || '/or-001.pdf' from receipts where receipt_no = 'OR-001' limit 1));
select tests.expect_true((select count(*) from storage.objects where bucket_id = 'receipts') = 1,
  'encoder can upload to the receipts bucket');
delete from storage.objects where bucket_id = 'receipts';
select tests.expect_true((select count(*) from storage.objects where bucket_id = 'receipts') = 1,
  'encoder cannot delete stored files');

-- ===== inactive user =====
select tests.login('f0000000-0000-0000-0000-000000000003');
select tests.expect_true((select count(*) from receipts) = 0, 'inactive user sees no receipts');
select tests.expect_error($$select save_receipt(tests.payload('OR-OFF', '11111111-0000-0000-0000-000000000001'))$$, 'not allowed');
select tests.expect_true((select count(*) from profiles) = 1, 'inactive user can still read only their own profile');

-- ===== pending user =====
select tests.login('70000000-0000-0000-0000-000000000004');
select tests.expect_true((select count(*) from profiles) = 1, 'pending user can read only their own profile');
select tests.expect_true((select count(*) from receipts) = 0, 'pending user sees no receipts');
select tests.expect_error($$select save_receipt(tests.payload('OR-PENDING', '11111111-0000-0000-0000-000000000001'))$$, 'not allowed');

-- Review operations are service-role-only and transactional.
reset role;
set role service_role;
select tests.expect_error($$select review_account('a0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'APPROVE')$$, 'cannot review their own');
select review_account('a0000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000004', 'APPROVE');
select review_account('a0000000-0000-0000-0000-000000000001', '80000000-0000-0000-0000-000000000005', 'REJECT');
select register_account_profile('90000000-0000-0000-0000-000000000006', 'New Applicant',
  'new@x.test', '11111111-0000-0000-0000-000000000001');
select tests.expect_true((select account_status = 'PENDING' and not is_active
  and company_id = '11111111-0000-0000-0000-000000000001' from profiles
  where id = '90000000-0000-0000-0000-000000000006'),
  'registration creates a pending encoder profile for the selected company');
reset role;

set role authenticated;
select tests.login('70000000-0000-0000-0000-000000000004');
select tests.expect_true((select account_status from profiles where id = auth.uid()) = 'APPROVED'
  and (select is_active from profiles where id = auth.uid()), 'approval activates the pending account');
select tests.expect_true((select count(*) from receipts) > 0, 'approved user can access receipts');

select tests.login('80000000-0000-0000-0000-000000000005');
select tests.expect_true((select account_status from profiles where id = auth.uid()) = 'REJECTED'
  and not (select is_active from profiles where id = auth.uid()), 'rejection preserves an inactive rejected account');
select tests.expect_true((select count(*) from receipts) = 0, 'rejected user sees no receipts');

-- ===== admin =====
select tests.login('a0000000-0000-0000-0000-000000000001');
select tests.expect_true((select count(*) from profiles) = 6, 'approved admin can list company account statuses');
select tests.expect_true((select count(*) from companies) = 1, 'admin can see only their own company');
select tests.expect_true((select count(*) from activity_log where action = 'receipt.create') = 2, 'admin sees receipt.create log rows');
select tests.expect_true((select count(*) from activity_log where action like 'suppliers.%') >= 1, 'supplier changes are logged by trigger');
select tests.expect_error($$delete from suppliers where id = '11111111-0000-0000-0000-000000000001'$$, 'violates foreign key');
select tests.expect_error($$select admin_update_profile('a0000000-0000-0000-0000-000000000001', 'Ada', 'encoder', true)$$, 'last active administrator');
select tests.expect_error($$select admin_update_profile('a0000000-0000-0000-0000-000000000001', 'Ada', 'admin', false)$$, 'last active administrator');
select admin_update_profile('e0000000-0000-0000-0000-000000000002', 'Eli E.', 'encoder', true);
select tests.expect_true((select full_name from profiles where id = 'e0000000-0000-0000-0000-000000000002') = 'Eli E.', 'admin can rename a user');
select tests.expect_true((select array_length(delete_receipt((select id from receipts where receipt_no = 'OR-001' and supplier_id = '11111111-0000-0000-0000-000000000001')), 1)) = 1, 'admin delete_receipt returns the storage paths');
select tests.expect_true((select count(*) from receipts) = 1 and (select count(*) from receipt_items) = 2, 'delete cascades items of the deleted receipt only');
delete from storage.objects where bucket_id = 'receipts';
select tests.expect_true((select count(*) from storage.objects where bucket_id = 'receipts') = 0,
  'admin can delete stored files');
select tests.expect_true((select count(*) from activity_log where action = 'receipt.delete') = 1, 'receipt.delete is logged');
select tests.expect_true((select approve_ai_import_receipt(jsonb_set(
  jsonb_set(tests.payload('AI-001', '11111111-0000-0000-0000-000000000001'),
    '{company_id}', to_jsonb('33333333-0000-0000-0000-000000000003'::text), true),
  '{attachments}', '[]'::jsonb)) is not null, 'administrator can approve an AI import through the secured RPC');
select tests.expect_true((select company_id = '11111111-0000-0000-0000-000000000001'
  from receipts where receipt_no = 'AI-001'), 'AI import company comes from authenticated supplier scope, not the payload');
select tests.expect_true((select count(*) from receipt_attachments
  where receipt_id = (select id from receipts where receipt_no = 'AI-001')) = 0,
  'AI import cannot attach metadata before a successful receipt save');
insert into storage.objects(bucket_id, name)
select 'receipts', id::text || '/ai-source.pdf'
from receipts where receipt_no = 'AI-001';
select tests.expect_true((select approve_ai_import_receipt(jsonb_set(
  jsonb_set(jsonb_set(tests.payload('AI-001', '11111111-0000-0000-0000-000000000001'),
    '{id}', to_jsonb((select id from receipts where receipt_no = 'AI-001')::text), true),
    '{operation}', '"attachments"'::jsonb, true),
  '{attachments}', jsonb_build_array(jsonb_build_object(
    'file_name', 'ai-source.pdf',
    'storage_path', (select id::text || '/ai-source.pdf' from receipts where receipt_no = 'AI-001'))), true))
  = (select id from receipts where receipt_no = 'AI-001')),
  'AI import links only an existing private object to its saved receipt');
select tests.expect_true((select count(*) from receipt_attachments
  where receipt_id = (select id from receipts where receipt_no = 'AI-001')) = 1,
  'attachment metadata is linked after server-side receipt and object checks');
select tests.expect_error($$select approve_ai_import_receipt(tests.payload('AI-CROSS', '33333333-0000-0000-0000-000000000003'))$$,
  'supplier in your company');
select tests.expect_error($$select approve_ai_import_receipt(jsonb_set(
  tests.payload('AI-UPDATE', '11111111-0000-0000-0000-000000000001'),
  '{id}', to_jsonb('aaaaaaaa-0000-0000-0000-000000000001'::text), true))$$,
  'only create new receipts or attach files');
select tests.expect_true((select count(*) from activity_log
  where action = 'ai_receipt_import.approved') = 1, 'AI import approval is audited without receipt contents');
select tests.expect_true((select count(*) from activity_log
  where action = 'receipt.ai_import.created') = 1, 'AI-created receipt is audited');

-- ===== creator =====
select tests.login('c0000000-0000-0000-0000-000000000007');
select tests.expect_true(is_admin() and is_creator(), 'Creator has administrative permissions');
select tests.expect_error($$select approve_ai_import_receipt(tests.payload('AI-CREATOR', '33333333-0000-0000-0000-000000000003'))$$,
  'administrator role required');
select tests.expect_true((select count(*) from companies) = 3, 'Creator can see every company');
select tests.expect_true((select count(*) from suppliers) = 4, 'Creator can see data across companies');
select tests.expect_true((select count(*) from profiles) = 7, 'Creator can see every account');
select tests.expect_true((select count(*) from bug_reports) = 1, 'Creator can see submitted bug reports');
select tests.expect_true((select count(*) from storage.objects where bucket_id = 'bug-reports') = 1,
  'Creator can view submitted report images');
update companies set name = 'Beta Ltd Updated'
where id = '33333333-0000-0000-0000-000000000003';
select tests.expect_true((select name from companies where id = '33333333-0000-0000-0000-000000000003') = 'Beta Ltd Updated',
  'Creator can edit a company across company scopes');
select tests.expect_error($$delete from companies where id = '33333333-0000-0000-0000-000000000003'$$,
  'violates foreign key');
select tests.expect_true(creator_broadcast(array[
  'a0000000-0000-0000-0000-000000000001'::uuid,
  'e0000000-0000-0000-0000-000000000002'::uuid
], 'System notice') = 2, 'Creator broadcast is saved once per recipient');
select tests.expect_true((select count(*) from custom_messages where is_broadcast and body = 'System notice') = 2,
  'broadcast rows are available to recipients and history');
insert into custom_messages(recipient_id, body)
values ('a0000000-0000-0000-0000-000000000001', 'Keep this conversation');
select tests.expect_true((select recipient_name from creator_message_history()
  where body = 'Keep this conversation') = 'Ada Admin', 'Creator history includes cross-company chats');

reset role;
delete from auth.users where id = 'a0000000-0000-0000-0000-000000000001';
set role authenticated;
select tests.login('c0000000-0000-0000-0000-000000000007');
select tests.expect_true((select recipient_id is null and recipient_name = 'Deleted user'
  and body = 'Keep this conversation' from creator_message_history()
  where body = 'Keep this conversation'), 'account deletion preserves anonymized chat history');

reset role;
set role service_role;
select register_company_admin_profile('d0000000-0000-0000-0000-000000000008',
  'New Company Admin', 'owner@newco.test', 'New Company');
select tests.expect_true((select role = 'admin' and account_status = 'APPROVED' and is_active
  and company_id = (select id from companies where name = 'New Company')
  from profiles where id = 'd0000000-0000-0000-0000-000000000008'),
  'public Admin registration creates an active account and company');
reset role;

select tests.expect_true((select relrowsecurity from pg_class where oid = 'public.ducky_ai_memory'::regclass),
  'Ducky memory has row-level security enabled');
select tests.expect_true(not has_table_privilege('anon', 'public.ducky_ai_memory', 'SELECT')
  and not has_table_privilege('authenticated', 'public.ducky_ai_memory', 'SELECT')
  and has_table_privilege('service_role', 'public.ducky_ai_memory', 'SELECT,INSERT,UPDATE,DELETE'),
  'only the authenticated Edge Function service role can access Ducky memory');

select 'ALL ASSERTIONS PASSED' as result;
