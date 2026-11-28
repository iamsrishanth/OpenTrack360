begin;

alter table private.member_accounts
  add column admin_auth_method text not null default 'password'
    check (admin_auth_method in ('password', 'google'));

create table private.operator_google_admin_provision_audit (
  request_id uuid primary key,
  operator_subject_id uuid not null,
  project_ref text not null check (project_ref = 'oxfcxucpzrvbifefnwvp'),
  target_account_id uuid not null unique references private.member_accounts(account_id),
  target_auth_user_id uuid not null unique references auth.users(id),
  google_email text not null unique,
  target_username text not null unique,
  database_role text not null check (database_role = 'postgres'),
  created_at timestamptz not null default now()
);
alter table private.operator_google_admin_provision_audit enable row level security;
revoke all on table private.operator_google_admin_provision_audit from public, anon, authenticated, service_role;
revoke insert, update, delete on table private.member_accounts from service_role;

create or replace function public.opentrack_lookup_login(p_username_normalized text)
returns jsonb language sql security definer set search_path = '' as $$
  select jsonb_build_object(
    'userId', a.auth_user_id,
    'accountId', a.account_id,
    'username', a.username_normalized,
    'authEmail', a.auth_email,
    'disabled', a.status <> 'active' or a.reset_pending,
    'mustRotatePassword', a.must_rotate_password,
    'sessionEpoch', a.session_epoch,
    'sharingEpoch', a.sharing_epoch,
    'isPlatformAdmin', a.is_platform_admin,
    'adminAuthMethod', a.admin_auth_method
  )
  from private.member_accounts a
  where a.username_normalized = p_username_normalized and a.auth_user_id is not null
$$;

create or replace function public.opentrack_register_member_session(p_user_id uuid, p_session_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  a private.member_accounts%rowtype;
  s private.member_sessions%rowtype;
begin
  select * into a from private.member_accounts where auth_user_id = p_user_id for update;
  if not found or a.status <> 'active' or a.reset_pending then
    return jsonb_build_object('allowed', false);
  end if;
  select * into s from private.member_sessions where session_id=p_session_id for update;
  if found then
    if s.user_id <> p_user_id or s.revoked_at is not null or s.session_epoch <> a.session_epoch then
      return jsonb_build_object('allowed', false);
    end if;
    update private.member_sessions set last_seen_at=now() where session_id=p_session_id;
  else
    insert into private.member_sessions(session_id,user_id,session_epoch) values(p_session_id,p_user_id,a.session_epoch);
  end if;
  update private.member_accounts set last_login_at = now(), updated_at = now() where account_id = a.account_id;
  return jsonb_build_object('allowed', true, 'userId', p_user_id, 'username', a.username_normalized,
    'sessionId', p_session_id, 'mustRotatePassword', a.must_rotate_password,
    'sessionEpoch', a.session_epoch, 'sharingEpoch', a.sharing_epoch,
    'isPlatformAdmin', a.is_platform_admin, 'adminAuthMethod', a.admin_auth_method);
end;
$$;

create or replace function public.opentrack_validate_member_session(p_user_id uuid, p_session_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  a private.member_accounts%rowtype;
  s private.member_sessions%rowtype;
begin
  select * into a from private.member_accounts where auth_user_id = p_user_id;
  select * into s from private.member_sessions where session_id = p_session_id and user_id = p_user_id;
  if a.account_id is null or s.session_id is null or a.status <> 'active' or a.reset_pending or s.revoked_at is not null or s.session_epoch <> a.session_epoch then
    return jsonb_build_object('allowed', false);
  end if;
  update private.member_sessions set last_seen_at = now() where session_id = p_session_id;
  return jsonb_build_object('allowed', true, 'userId', p_user_id, 'username', a.username_normalized,
    'sessionId', p_session_id, 'mustRotatePassword', a.must_rotate_password,
    'sessionEpoch', a.session_epoch, 'sharingEpoch', a.sharing_epoch,
    'isPlatformAdmin', a.is_platform_admin, 'adminAuthMethod', a.admin_auth_method);
end;
$$;

create or replace function public.opentrack_register_oauth_session(
  p_user_id uuid,
  p_session_id uuid,
  p_token_issued_at bigint
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  a private.member_accounts%rowtype;
  s private.member_sessions%rowtype;
  v_epoch_started_at bigint;
begin
  if p_token_issued_at is null or p_token_issued_at <= 0 then
    return jsonb_build_object('allowed', false);
  end if;
  select * into a from private.member_accounts where auth_user_id = p_user_id for update;
  if not found or a.status <> 'active' or a.reset_pending then
    return jsonb_build_object('allowed', false);
  end if;
  v_epoch_started_at := floor(extract(epoch from a.session_epoch_started_at))::bigint;
  if p_token_issued_at < v_epoch_started_at then
    return jsonb_build_object('allowed', false);
  end if;
  select * into s from private.member_sessions where session_id = p_session_id for update;
  if found then
    if s.user_id <> p_user_id or s.revoked_at is not null or s.session_epoch <> a.session_epoch then
      return jsonb_build_object('allowed', false);
    end if;
    update private.member_sessions set last_seen_at = now() where session_id = p_session_id;
  else
    insert into private.member_sessions(session_id, user_id, session_epoch)
    values (p_session_id, p_user_id, a.session_epoch);
  end if;
  update private.member_accounts set last_login_at = now(), updated_at = now() where account_id = a.account_id;
  return jsonb_build_object(
    'allowed', true,
    'userId', p_user_id,
    'username', a.username_normalized,
    'sessionId', p_session_id,
    'mustRotatePassword', a.must_rotate_password,
    'sessionEpoch', a.session_epoch,
    'sharingEpoch', a.sharing_epoch,
    'isPlatformAdmin', a.is_platform_admin,
    'adminAuthMethod', a.admin_auth_method
  );
end;
$$;

create or replace function public.opentrack_admin_list_accounts(p_actor_user_id uuid, p_limit integer default 100, p_cursor uuid default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_data jsonb; v_limit integer := least(greatest(coalesce(p_limit,100),1),200); v_has_more boolean; v_next uuid;
begin
  if not exists(select 1 from private.member_accounts where auth_user_id=p_actor_user_id and is_platform_admin and status='active') then
    return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden')));
  end if;
  with page as (
    select a.*, row_number() over(order by a.account_id desc) as rn
    from private.member_accounts a
    where not a.is_platform_admin and (a.auth_user_id is not null or a.status='provisioning')
      and (p_cursor is null or a.account_id < p_cursor)
    order by a.account_id desc limit v_limit + 1
  )
  select coalesce(jsonb_agg(jsonb_build_object('accountId',p.account_id,'username',p.username_normalized,'displayName',p.display_name,
      'status',p.status,'mustRotatePassword',p.must_rotate_password,'createdAt',p.created_at) order by p.account_id desc) filter(where p.rn <= v_limit),'[]'::jsonb),
    coalesce(bool_or(p.rn > v_limit),false),
    (array_agg(p.account_id order by p.account_id desc) filter(where p.rn <= v_limit))[v_limit]
  into v_data,v_has_more,v_next from page p;
  return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',case when v_has_more then v_next::text else null end));
end;
$$;

create or replace function public.opentrack_admin_begin_reset(p_request_id uuid, p_account_id uuid, p_actor_user_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare a private.member_accounts%rowtype;
begin
  if not exists(select 1 from private.member_accounts where auth_user_id = p_actor_user_id and is_platform_admin and status='active') then
    return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden')));
  end if;
  select * into a from private.member_accounts where account_id=p_account_id and status='active' for update;
  if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
  if a.is_platform_admin then
    return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','platform_admin_protected','message','Platform administrator accounts cannot be reset here')));
  end if;
  if a.reset_pending then
    return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','reset_in_progress','message','A password reset is already in progress')));
  end if;
  update private.member_accounts set reset_pending=true, session_epoch=session_epoch+1, sharing_epoch=sharing_epoch+1,
    sharing_enabled=false, activity_sharing_enabled=false, updated_at=now() where account_id=p_account_id returning * into a;
  update private.member_sessions set revoked_at=coalesce(revoked_at,now()) where user_id=a.auth_user_id;
  update private.circle_members set sharing_enabled=false,activity_sharing_enabled=false,updated_at=now()
    where user_id=a.auth_user_id and membership_state='active';
  update private.guest_shares set status='ended',ended_at=coalesce(ended_at,now()),end_reason='admin_password_reset'
    where owner_id=a.auth_user_id and status='active';
  update private.trips set status='ended',ended_at=coalesce(ended_at,now()),end_reason='admin_password_reset'
    where user_id=a.auth_user_id and status='active';
  delete from private.latest_locations where user_id=a.auth_user_id;
  insert into private.admin_reset_requests(request_id, account_id, created_by) values(p_request_id,p_account_id,p_actor_user_id);
  return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('accountId',a.account_id,'userId',a.auth_user_id,'authEmail',a.auth_email,'username',a.username_normalized));
end;
$$;

create or replace function private.opentrack_operator_add_google_admin(
  p_request_id uuid,
  p_operator_subject_id uuid,
  p_target_user_id uuid,
  p_google_email text,
  p_username text,
  p_project_ref text
)
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_email text := lower(trim(coalesce(p_google_email, '')));
  v_local_part text;
  v_expected_username text;
  v_user_email text;
  v_confirmed_at timestamptz;
  v_user_metadata jsonb;
  v_existing private.operator_google_admin_provision_audit%rowtype;
  v_account_id uuid;
begin
  if current_user <> 'postgres' or session_user <> 'postgres' then
    raise exception 'Google administrator provisioning requires the postgres database management role' using errcode = '42501';
  end if;
  if p_project_ref is distinct from 'oxfcxucpzrvbifefnwvp' then
    raise exception 'Google administrator provisioning target project mismatch' using errcode = '22023';
  end if;
  if p_request_id is null or p_operator_subject_id is null or p_target_user_id is null or p_operator_subject_id = p_target_user_id then
    raise exception 'operator, request, and target user identifiers are required' using errcode = '22023';
  end if;
  if v_email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' then
    raise exception 'a valid Google email is required' using errcode = '22023';
  end if;

  v_local_part := regexp_replace(split_part(v_email, '@', 1), '[^a-z0-9._-]+', '.', 'g');
  v_local_part := regexp_replace(v_local_part, '^[._-]+|[._-]+$', '', 'g');
  v_local_part := left(v_local_part, 24);
  if length(v_local_part) < 2 then v_local_part := 'google.admin'; end if;
  v_expected_username := v_local_part || '.admin';
  if p_username is distinct from v_expected_username
     or p_username !~ '^[a-z0-9][a-z0-9._-]{1,30}[a-z0-9]$' then
    raise exception 'Google administrator username does not match the requested email' using errcode = '22023';
  end if;

  perform pg_advisory_xact_lock(hashtextextended('opentrack360:google-admin:' || v_email, 0));
  select * into v_existing from private.operator_google_admin_provision_audit where request_id = p_request_id;
  if found then
    if v_existing.operator_subject_id = p_operator_subject_id and v_existing.project_ref = p_project_ref
       and v_existing.target_auth_user_id = p_target_user_id and v_existing.google_email = v_email
       and v_existing.target_username = p_username then
      return jsonb_build_object('ok', true, 'idempotent', true, 'accountId', v_existing.target_account_id,
        'username', v_existing.target_username, 'requestId', p_request_id);
    end if;
    raise exception 'operator request id was already used for a different Google administrator' using errcode = '23505';
  end if;

  select lower(u.email), u.email_confirmed_at, u.raw_user_meta_data
    into v_user_email, v_confirmed_at, v_user_metadata
    from auth.users u where u.id = p_target_user_id;
  if not found or v_user_email is distinct from v_email or v_confirmed_at is null then
    raise exception 'target Auth identity must have the requested verified email' using errcode = '22023';
  end if;
  if not (
    (v_user_metadata ->> 'opentrack_google_admin_email' = v_email
      and v_user_metadata ->> 'opentrack_google_admin_request_id' = p_request_id::text)
    or exists (
      select 1 from auth.identities i
      where i.user_id = p_target_user_id and i.provider = 'google'
        and lower(i.identity_data ->> 'email') = v_email
        and i.identity_data ->> 'email_verified' = 'true'
    )
  ) then
    raise exception 'target Auth identity is neither request-scoped nor verified by Google' using errcode = '22023';
  end if;

  if exists (select 1 from private.member_accounts a where a.auth_user_id = p_target_user_id
      or lower(a.auth_email) = v_email or a.username_normalized = p_username) then
    raise exception 'target Auth identity is already linked to a member account' using errcode = '23505';
  end if;
  if exists (select 1 from private.operator_google_admin_provision_audit a where a.google_email = v_email) then
    raise exception 'this Google email already has an operator-provisioned administrator' using errcode = '23505';
  end if;
  if not exists (select 1 from private.bootstrap_state where singleton and consumed_at is not null)
     or not exists (select 1 from private.member_accounts where is_platform_admin and status = 'active') then
    raise exception 'the first administrator bootstrap must remain consumed' using errcode = '55000';
  end if;

  insert into private.member_accounts(
    auth_user_id, username_normalized, display_name, auth_email,
    status, is_platform_admin, must_rotate_password, sharing_enabled,
    activity_sharing_enabled, admin_auth_method
  ) values (
    p_target_user_id, p_username, 'Google Administrator', v_email,
    'active', true, false, false, false, 'google'
  ) returning account_id into v_account_id;

  insert into private.operator_google_admin_provision_audit(
    request_id, operator_subject_id, project_ref, target_account_id,
    target_auth_user_id, google_email, target_username, database_role
  ) values (
    p_request_id, p_operator_subject_id, p_project_ref, v_account_id,
    p_target_user_id, v_email, p_username, current_user
  );

  insert into private.admin_audit_events(actor_user_id, actor_username, target_account_id, event_type, metadata)
  values (p_operator_subject_id, 'supabase-cli-operator', v_account_id,
    'account.google_admin_provisioned', jsonb_build_object('requestId',p_request_id,'projectRef',p_project_ref,'authMethod','google'));

  return jsonb_build_object('ok', true, 'idempotent', false, 'accountId', v_account_id,
    'username', p_username, 'requestId', p_request_id);
end;
$$;

revoke all on function private.opentrack_operator_add_google_admin(uuid, uuid, uuid, text, text, text)
  from public, anon, authenticated, service_role;
grant execute on function private.opentrack_operator_add_google_admin(uuid, uuid, uuid, text, text, text)
  to postgres;

commit;
