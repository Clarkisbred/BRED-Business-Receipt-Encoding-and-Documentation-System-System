-- RMS 006: public registration requests and administrator approval.
alter table profiles add column if not exists account_status text;
alter table profiles add column if not exists company_id uuid;
update profiles set account_status = 'APPROVED' where account_status is null;
alter table profiles alter column account_status set default 'PENDING';
alter table profiles alter column account_status set not null;
do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'profiles_account_status_check'
      and conrelid = 'public.profiles'::regclass
  ) then
    alter table profiles add constraint profiles_account_status_check
      check (account_status in ('PENDING', 'APPROVED', 'REJECTED'));
  end if;
end $$;
do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'profiles_company_id_fkey'
      and conrelid = 'public.profiles'::regclass
  ) then
    alter table profiles add constraint profiles_company_id_fkey
      foreign key (company_id) references companies(id) on delete set null;
  end if;
end $$;
create unique index if not exists profiles_email_normalized_key on profiles (lower(btrim(email)));
create or replace function is_active_user() returns boolean
language sql stable security definer set search_path = public as
$$ select exists (
  select 1 from profiles
  where id = auth.uid() and is_active and account_status = 'APPROVED'
) $$;
create or replace function is_admin() returns boolean
language sql stable security definer set search_path = public as
$$ select exists (
  select 1 from profiles
  where id = auth.uid() and is_active and account_status = 'APPROVED' and role = 'admin'
) $$;
drop policy if exists profiles_read on profiles;
drop policy if exists profiles_read_own_or_admin on profiles;
create policy profiles_read_own_or_admin on profiles for select to authenticated
  using (id = auth.uid() or is_admin());
drop function if exists register_account_profile(uuid, text, text);
create or replace function register_account_profile(p_id uuid, p_full_name text, p_email text, p_company_id uuid)
returns void
language plpgsql security definer set search_path = public as $$
begin
  if p_id is null or btrim(coalesce(p_full_name, '')) = '' or btrim(coalesce(p_email, '')) = '' then
    raise exception 'Registration details are required.';
  end if;
  if p_company_id is null or not exists (
    select 1 from companies where id = p_company_id and is_active
  ) then
    raise exception 'Choose an available company.';
  end if;

  insert into profiles (id, full_name, email, role, is_active, account_status, company_id)
  values (p_id, btrim(p_full_name), lower(btrim(p_email)), 'encoder', false, 'PENDING', p_company_id);

  insert into activity_log (user_id, user_name, action, detail)
  values (p_id, btrim(p_full_name), 'account.register', lower(btrim(p_email)));
end $$;
create or replace function review_account(p_admin_id uuid, p_target_id uuid, p_action text)
returns void
language plpgsql security definer set search_path = public as $$
declare
  v_admin profiles;
  v_target profiles;
  v_status text;
begin
  select * into v_admin from profiles
  where id = p_admin_id and role = 'admin' and is_active and account_status = 'APPROVED';
  if not found then raise exception 'Administrator approval required.'; end if;
  if p_admin_id = p_target_id then raise exception 'Administrators cannot review their own account.'; end if;
  if upper(coalesce(p_action, '')) not in ('APPROVE', 'REJECT') then
    raise exception 'Choose approve or reject.';
  end if;

  select * into v_target from profiles where id = p_target_id for update;
  if not found then raise exception 'Registration not found.'; end if;
  if v_target.account_status <> 'PENDING' then raise exception 'This registration is no longer pending.'; end if;

  v_status := case when upper(p_action) = 'APPROVE' then 'APPROVED' else 'REJECTED' end;
  update profiles
  set account_status = v_status, is_active = (v_status = 'APPROVED')
  where id = p_target_id;

  insert into activity_log (user_id, user_name, action, detail)
  values (p_admin_id, v_admin.full_name,
          case when v_status = 'APPROVED' then 'account.approve' else 'account.reject' end,
          v_target.email || ' (' || v_target.id::text || ')');

  if v_target.is_active is distinct from (v_status = 'APPROVED') then
    insert into activity_log (user_id, user_name, action, detail)
    values (p_admin_id, v_admin.full_name, 'account.activation_changed',
            v_target.email || ' (' || v_target.id::text || '): '
              || case when v_status = 'APPROVED' then 'active' else 'inactive' end);
  end if;
end $$;
create or replace function admin_update_profile(p_id uuid, p_full_name text, p_role user_role, p_active boolean)
returns void
language plpgsql security definer set search_path = public as $$
declare v_target profiles;
begin
  if not is_admin() then raise exception 'Administrator role required.'; end if;
  if btrim(coalesce(p_full_name, '')) = '' then raise exception 'Full name is required.'; end if;
  select * into v_target from profiles where id = p_id for update;
  if not found then raise exception 'User not found.'; end if;
  if p_active and v_target.account_status <> 'APPROVED' then
    raise exception 'Only approved registrations can be activated.';
  end if;
  if v_target.role = 'admin' and v_target.is_active and (p_role <> 'admin' or not p_active)
     and (select count(*) from profiles where role = 'admin' and is_active and account_status = 'APPROVED') <= 1 then
    raise exception 'Cannot remove the last active administrator.';
  end if;
  update profiles set full_name = btrim(p_full_name), role = p_role, is_active = p_active where id = p_id;
  perform log_activity('user.update', v_target.email);
  if v_target.is_active is distinct from p_active then
    perform log_activity('account.activation_changed', v_target.email || ' (' || v_target.id::text || '): '
      || case when p_active then 'active' else 'inactive' end);
  end if;
end $$;
revoke all on function register_account_profile(uuid, text, text, uuid),
  review_account(uuid, uuid, text) from public, anon, authenticated;
grant execute on function register_account_profile(uuid, text, text, uuid),
  review_account(uuid, uuid, text) to service_role;
grant all on profiles, activity_log to service_role;
grant usage, select on all sequences in schema public to service_role;
