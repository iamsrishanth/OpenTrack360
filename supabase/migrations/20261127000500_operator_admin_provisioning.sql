begin;

-- The web runtime's service role must not be able to change platform roles by
-- writing the identity mapping table directly. Application flows use the
-- SECURITY DEFINER API functions; the out-of-band operator command connects as
-- the project database owner.
revoke insert, update, delete on table private.member_accounts from service_role;

create table private.operator_admin_provision_audit (
  request_id uuid primary key,
  operator_subject_id uuid not null,
  project_ref text not null check (project_ref = 'oxfcxucpzrvbifefnwvp'),
  target_account_id uuid not null unique references private.member_accounts(account_id),
  target_auth_user_id uuid not null unique references auth.users(id),
  target_username text not null unique,
  database_role text not null check (database_role = 'postgres'),
  created_at timestamptz not null default now()
);
alter table private.operator_admin_provision_audit enable row level security;
revoke all on table private.operator_admin_provision_audit from public, anon, authenticated, service_role;

create or replace function private.opentrack_operator_provision_admin(
  p_request_id uuid,
  p_operator_subject_id uuid,
  p_target_user_id uuid,
  p_target_username text,
  p_display_name text,
  p_project_ref text
)
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_expected_username text;
  v_expected_email text;
  v_metadata jsonb;
  v_email_confirmed_at timestamptz;
  v_existing private.operator_admin_provision_audit%rowtype;
  v_account_id uuid;
begin
  if current_user <> 'postgres' or session_user <> 'postgres' then
    raise exception 'operator provisioning requires the postgres database management role' using errcode = '42501';
  end if;
  if p_project_ref is distinct from 'oxfcxucpzrvbifefnwvp' then
    raise exception 'operator provisioning target project mismatch' using errcode = '22023';
  end if;
  if p_request_id is null or p_operator_subject_id is null or p_target_user_id is null then
    raise exception 'operator, request, and target user identifiers are required' using errcode = '22023';
  end if;
  if p_operator_subject_id = p_target_user_id then
    raise exception 'operator subject must not be the target Auth user' using errcode = '22023';
  end if;

  v_expected_username := 'release-op-' || left(replace(p_request_id::text, '-', ''), 8);
  v_expected_email := 'opentrack360-release-operator-' || p_request_id::text || '@opentrack360.invalid';
  if p_target_username is distinct from v_expected_username
     or p_target_username !~ '^[a-z0-9][a-z0-9._-]{1,30}[a-z0-9]$'
     or p_display_name is distinct from 'OpenTrack360 Release Test Operator' then
    raise exception 'operator account identity does not match its request' using errcode = '22023';
  end if;

  perform pg_advisory_xact_lock(hashtextextended('opentrack360:operator-admin:' || p_request_id::text, 0));
  select * into v_existing
    from private.operator_admin_provision_audit
    where request_id = p_request_id;
  if found then
    if v_existing.operator_subject_id = p_operator_subject_id
       and v_existing.project_ref = p_project_ref
       and v_existing.target_auth_user_id = p_target_user_id
       and v_existing.target_username = p_target_username then
      return jsonb_build_object('ok', true, 'idempotent', true,
        'accountId', v_existing.target_account_id, 'username', v_existing.target_username,
        'mustRotatePassword', true, 'requestId', p_request_id);
    end if;
    raise exception 'operator request id was already used for a different account' using errcode = '23505';
  end if;

  -- The Auth identity is created through Supabase Auth Admin before this SQL
  -- step. Match its request-scoped email and metadata so no existing identity
  -- can be promoted accidentally.
  select u.raw_user_meta_data, u.email_confirmed_at
    into v_metadata, v_email_confirmed_at
    from auth.users u
    where u.id = p_target_user_id and u.email = v_expected_email;
  if not found or v_email_confirmed_at is null
     or v_metadata ->> 'opentrack_username' is distinct from v_expected_username
     or v_metadata ->> 'opentrack_operator_request_id' is distinct from p_request_id::text then
    raise exception 'new Auth identity does not match this operator request' using errcode = '22023';
  end if;

  if exists (
    select 1 from private.member_accounts a
    where a.auth_user_id = p_target_user_id
       or a.username_normalized = v_expected_username
       or a.auth_email = v_expected_email
  ) then
    raise exception 'operator identity is already linked to an account' using errcode = '23505';
  end if;

  -- Do not reopen bootstrap and do not update any existing administrator.
  if not exists (select 1 from private.bootstrap_state where singleton and consumed_at is not null)
     or not exists (select 1 from private.member_accounts where is_platform_admin and status = 'active') then
    raise exception 'the first administrator bootstrap must remain consumed' using errcode = '55000';
  end if;

  insert into private.member_accounts(
    auth_user_id, username_normalized, display_name, auth_email,
    status, is_platform_admin, must_rotate_password, sharing_enabled, activity_sharing_enabled
  ) values (
    p_target_user_id, v_expected_username, p_display_name, v_expected_email,
    'active', true, true, false, false
  ) returning account_id into v_account_id;

  insert into private.operator_admin_provision_audit(
    request_id, operator_subject_id, project_ref, target_account_id,
    target_auth_user_id, target_username, database_role
  ) values (
    p_request_id, p_operator_subject_id, p_project_ref, v_account_id,
    p_target_user_id, v_expected_username, current_user
  );

  return jsonb_build_object('ok', true, 'idempotent', false,
    'accountId', v_account_id, 'username', v_expected_username,
    'mustRotatePassword', true, 'requestId', p_request_id);
end;
$$;

revoke all on function private.opentrack_operator_provision_admin(uuid, uuid, uuid, text, text, text)
  from public, anon, authenticated, service_role;
grant execute on function private.opentrack_operator_provision_admin(uuid, uuid, uuid, text, text, text)
  to postgres;

commit;
