create or replace function public.opentrack_login_allowed(p_username_normalized text)
returns boolean
language plpgsql security definer set search_path = ''
as $$
declare
  v_digest bytea := extensions.digest(convert_to(coalesce(p_username_normalized, ''), 'UTF8'), 'sha256');
  v_row private.login_rate_limits%rowtype;
begin
  insert into private.login_rate_limits(username_digest, window_started_at, attempts)
  values (v_digest, now(), 1)
  on conflict(username_digest) do update set
    window_started_at = case when private.login_rate_limits.window_started_at < now() - interval '15 minutes' then now() else private.login_rate_limits.window_started_at end,
    attempts = case when private.login_rate_limits.window_started_at < now() - interval '15 minutes' then 1 else private.login_rate_limits.attempts + 1 end,
    blocked_until = case when private.login_rate_limits.window_started_at < now() - interval '15 minutes' then null
                         when private.login_rate_limits.attempts >= 5 then now() + interval '15 minutes'
                         else private.login_rate_limits.blocked_until end
  returning * into v_row;
  return v_row.attempts <= 5 and (v_row.blocked_until is null or v_row.blocked_until <= now());
end;
$$;

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
    'isPlatformAdmin', a.is_platform_admin
  )
  from private.member_accounts a
  where a.username_normalized = p_username_normalized and a.auth_user_id is not null
$$;

create or replace function public.opentrack_lookup_auth_email_by_user(p_user_id uuid)
returns text language sql security definer set search_path = '' as $$
  select auth_email from private.member_accounts where auth_user_id = p_user_id and status = 'active'
$$;

create or replace function public.opentrack_auth_user_id_by_email(p_auth_email text)
returns uuid language sql security definer set search_path = '' as $$
  select id from auth.users where lower(email)=lower(p_auth_email) limit 1
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
    -- A refresh token from a session revoked by password rotation/reset/disable
    -- must never be able to register itself again at the new session epoch.
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
    'isPlatformAdmin', a.is_platform_admin);
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
    'isPlatformAdmin', a.is_platform_admin);
end;
$$;

create or replace function public.opentrack_get_member_profile(p_user_id uuid, p_session_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare a private.member_accounts%rowtype;
begin
  select * into a from private.member_accounts where auth_user_id=p_user_id and status='active';
  if a.account_id is null or not exists(select 1 from private.member_sessions s where s.session_id=p_session_id and s.user_id=p_user_id and s.session_epoch=a.session_epoch and s.revoked_at is null) then
    return jsonb_build_object('httpStatus',401,'body',jsonb_build_object('error',jsonb_build_object('code','unauthorized','message','Unauthorized')));
  end if;
  if a.must_rotate_password then
    return jsonb_build_object('httpStatus',428,'body',jsonb_build_object('error',jsonb_build_object('code','password_rotation_required','message','Password rotation required')));
  end if;
  return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('id',a.auth_user_id,'username',a.username_normalized,
    'displayName',a.display_name,'mustRotatePassword',a.must_rotate_password,'sharingEpoch',a.sharing_epoch));
end;
$$;

create or replace function public.opentrack_revoke_member_session(p_user_id uuid, p_session_id uuid)
returns boolean language plpgsql security definer set search_path = '' as $$
begin
  update private.member_sessions set revoked_at = coalesce(revoked_at, now())
  where user_id = p_user_id and session_id = p_session_id;
  return true;
end;
$$;

create or replace function public.opentrack_rotate_password_state(p_user_id uuid, p_session_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare a private.member_accounts%rowtype;
begin
  update private.member_accounts set must_rotate_password = false,
    session_epoch = session_epoch + 1, updated_at = now()
  where auth_user_id = p_user_id and status = 'active' returning * into a;
  if not found then return jsonb_build_object('changed', false); end if;
  update private.member_sessions set revoked_at = coalesce(revoked_at, now()) where user_id = p_user_id;
  return jsonb_build_object('changed', true, 'sessionsRevoked', true, 'reauthenticationRequired', true);
end;
$$;

create or replace function public.opentrack_claim_username(
  p_request_id uuid, p_lease_token uuid, p_username_normalized text, p_display_name text, p_auth_email text, p_actor_user_id uuid
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_account private.member_accounts%rowtype;
begin
  if not exists(select 1 from private.member_accounts where auth_user_id = p_actor_user_id and is_platform_admin and status = 'active') then
    return jsonb_build_object('httpStatus', 403, 'body', jsonb_build_object('error', jsonb_build_object('code','forbidden','message','Forbidden')));
  end if;
  if p_username_normalized !~ '^[a-z0-9][a-z0-9._-]{1,30}[a-z0-9]$' or length(p_display_name) not between 1 and 80 then
    return jsonb_build_object('httpStatus', 400, 'body', jsonb_build_object('error', jsonb_build_object('code','invalid_request','message','Invalid account details')));
  end if;
  if p_request_id is null or p_lease_token is null then
    return jsonb_build_object('httpStatus', 400, 'body', jsonb_build_object('error', jsonb_build_object('code','invalid_request','message','A request id is required')));
  end if;
  perform pg_advisory_xact_lock(hashtextextended(p_request_id::text, 0));
  select a.* into v_account
    from private.account_provision_requests r join private.member_accounts a using(account_id)
    where r.request_id = p_request_id;
  if found then
    return jsonb_build_object('httpStatus', 409, 'body', jsonb_build_object('error', jsonb_build_object(
      'code',case when v_account.status = 'active' then 'request_completed' else 'request_in_progress' end,
      'message',case when v_account.status = 'active' then 'This request already completed; use the dedicated password reset operation.' else 'This request is already being processed.' end)));
  end if;
  insert into private.member_accounts(username_normalized, display_name, auth_email, created_by)
  values (p_username_normalized, p_display_name, p_auth_email, p_actor_user_id)
  returning * into v_account;
  insert into private.account_provision_requests(request_id, account_id, created_by, lease_token)
  values (p_request_id, v_account.account_id, p_actor_user_id, p_lease_token);
  insert into private.admin_audit_events(actor_user_id, actor_username, target_account_id, event_type, metadata)
  values (p_actor_user_id, (select username_normalized from private.member_accounts where auth_user_id = p_actor_user_id),
    v_account.account_id, 'account.provision_started', jsonb_build_object('requestId',p_request_id));
  return jsonb_build_object('httpStatus', 201, 'body', jsonb_build_object(
    'accountId',v_account.account_id,'authEmail',v_account.auth_email,'username',v_account.username_normalized,
    'status',v_account.status,'leaseToken',p_lease_token));
exception when unique_violation then
  return jsonb_build_object('httpStatus', 409, 'body', jsonb_build_object('error', jsonb_build_object('code','conflict','message','Username is already provisioned')));
end;
$$;

create or replace function public.opentrack_finalize_account(p_request_id uuid, p_lease_token uuid, p_auth_user_id uuid, p_actor_user_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_account private.member_accounts%rowtype; v_lease uuid; v_completed timestamptz;
begin
  if not exists(select 1 from private.member_accounts where auth_user_id = p_actor_user_id and is_platform_admin and status = 'active') then
    return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden')));
  end if;
  select a.* into v_account
    from private.account_provision_requests r join private.member_accounts a using(account_id)
    where r.request_id = p_request_id for update of r, a;
  if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
  select r.lease_token, r.completed_at into v_lease, v_completed
    from private.account_provision_requests r where r.request_id = p_request_id;
  if v_lease is distinct from p_lease_token then
    return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','provision_lease_lost','message','Provisioning request is no longer current')));
  end if;
  if v_completed is not null then
    if v_account.status = 'active' and v_account.auth_user_id = p_auth_user_id then
      return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('accountId',v_account.account_id,'username',v_account.username_normalized,'status','active'));
    end if;
    return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','provision_conflict','message','Provisioning request is already complete')));
  end if;
  if v_account.status = 'active' and v_account.auth_user_id = p_auth_user_id then
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('accountId',v_account.account_id,'username',v_account.username_normalized,'status','active'));
  end if;
  update private.member_accounts set auth_user_id = p_auth_user_id, status = 'active', updated_at = now()
    where account_id = v_account.account_id and status = 'provisioning';
  update private.account_provision_requests set completed_at = now() where request_id = p_request_id;
  insert into private.admin_audit_events(actor_user_id, actor_username, target_account_id, event_type, metadata)
  values (p_actor_user_id, (select username_normalized from private.member_accounts where auth_user_id = p_actor_user_id),
    v_account.account_id, 'account.provisioned', '{}'::jsonb);
  return jsonb_build_object('httpStatus',201,'body',jsonb_build_object('accountId',v_account.account_id,'username',v_account.username_normalized,'status','active'));
exception when unique_violation then
  return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','conflict','message','Account already exists')));
end;
$$;

create or replace function public.opentrack_abort_account(p_request_id uuid, p_lease_token uuid, p_actor_user_id uuid)
returns boolean language plpgsql security definer set search_path = '' as $$
declare v_account_id uuid;
begin
  if not exists(select 1 from private.member_accounts where auth_user_id = p_actor_user_id and is_platform_admin and status = 'active') then
    return false;
  end if;
  select account_id into v_account_id from private.account_provision_requests where request_id = p_request_id and lease_token = p_lease_token and completed_at is null for update;
  if v_account_id is null then return true; end if;
  delete from private.account_provision_requests where request_id = p_request_id and lease_token = p_lease_token and completed_at is null;
  delete from private.member_accounts where account_id = v_account_id and status = 'provisioning' and auth_user_id is null;
  insert into private.admin_audit_events(actor_user_id, actor_username, target_account_id, event_type, metadata)
  values (p_actor_user_id, (select username_normalized from private.member_accounts where auth_user_id = p_actor_user_id),
    v_account_id, 'account.provision_aborted', jsonb_build_object('requestId',p_request_id));
  return true;
end;
$$;

create or replace function public.opentrack_admin_get_account(p_account_id uuid, p_actor_user_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare a private.member_accounts%rowtype;
begin
  if not exists(select 1 from private.member_accounts where auth_user_id = p_actor_user_id and is_platform_admin and status='active') then
    return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden')));
  end if;
  select * into a from private.member_accounts where account_id = p_account_id and auth_user_id is not null;
  if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
  return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('accountId',a.account_id,'userId',a.auth_user_id,
    'authEmail',a.auth_email,'username',a.username_normalized,'displayName',a.display_name,'status',a.status,
    'mustRotatePassword',a.must_rotate_password,'isPlatformAdmin',a.is_platform_admin));
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

create or replace function public.opentrack_admin_complete_reset(p_request_id uuid, p_account_id uuid, p_actor_user_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare a private.member_accounts%rowtype;
begin
  if not exists(select 1 from private.member_accounts where auth_user_id = p_actor_user_id and is_platform_admin and status='active') then
    return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden')));
  end if;
  if not exists(select 1 from private.admin_reset_requests where request_id=p_request_id and account_id=p_account_id and completed_at is null) then
    return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','reset_request_invalid','message','Password reset request is no longer current')));
  end if;
  select * into a from private.member_accounts where account_id=p_account_id and status='active' for update;
  if not found or a.reset_pending is false then
    return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found')));
  end if;
  update private.member_accounts set reset_pending=false, must_rotate_password=true, updated_at=now() where account_id=p_account_id returning * into a;
  update private.admin_reset_requests set completed_at=now() where request_id=p_request_id and account_id=p_account_id;
  insert into private.admin_audit_events(actor_user_id, actor_username, target_account_id, event_type, metadata)
  values (p_actor_user_id, (select username_normalized from private.member_accounts where auth_user_id=p_actor_user_id),
    p_account_id, 'password.reset', jsonb_build_object('requestId',p_request_id));
  return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('accountId',a.account_id,'username',a.username_normalized,
    'mustRotatePassword',true,'sessionsRevoked',true,'sharingStopped',true,'sharingEpoch',a.sharing_epoch));
end;
$$;

create or replace function public.opentrack_admin_fail_reset(p_request_id uuid, p_account_id uuid, p_actor_user_id uuid)
returns boolean language plpgsql security definer set search_path = '' as $$
declare v_user_id uuid;
begin
  if not exists(select 1 from private.member_accounts where auth_user_id=p_actor_user_id and is_platform_admin and status='active') then
    return false;
  end if;
  select auth_user_id into v_user_id from private.member_accounts where account_id=p_account_id and status='active' for update;
  if v_user_id is null then return false; end if;
  update private.admin_reset_requests set completed_at=now()
    where request_id=p_request_id and account_id=p_account_id and completed_at is null;
  if not found then return true; end if;
  update private.member_accounts set reset_pending=false, must_rotate_password=true, updated_at=now()
    where account_id=p_account_id and reset_pending;
  insert into private.admin_audit_events(actor_user_id,actor_username,target_account_id,event_type,metadata)
  values(p_actor_user_id,(select username_normalized from private.member_accounts where auth_user_id=p_actor_user_id),
    p_account_id,'password.reset_failed',jsonb_build_object('requestId',p_request_id));
  return true;
end;
$$;

create or replace function public.opentrack_admin_disable_account(p_account_id uuid, p_actor_user_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare a private.member_accounts%rowtype;
begin
  if not exists(select 1 from private.member_accounts where auth_user_id = p_actor_user_id and is_platform_admin and status='active') then
    return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden')));
  end if;
  if exists(select 1 from private.member_accounts where account_id=p_account_id and is_platform_admin) then
    return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','platform_admin_protected','message','Platform administrator accounts cannot be disabled here')));
  end if;
  update private.member_accounts set status='disabled', disabled_at=coalesce(disabled_at,now()),
    session_epoch=session_epoch+1, sharing_epoch=sharing_epoch+1, sharing_enabled=false, activity_sharing_enabled=false, updated_at=now()
    where account_id=p_account_id and status <> 'disabled' returning * into a;
  if not found then
    select * into a from private.member_accounts where account_id=p_account_id;
    if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
    return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('disabled',true,'alreadyDisabled',true));
  end if;
  update private.member_sessions set revoked_at=coalesce(revoked_at,now()) where user_id=a.auth_user_id;
  update private.circle_members set sharing_enabled=false,activity_sharing_enabled=false,membership_state='removed',updated_at=now() where user_id=a.auth_user_id;
  update private.guest_shares set status='ended',ended_at=now(),end_reason='owner_disabled' where owner_id=a.auth_user_id and status='active';
  update private.trips set status='ended',ended_at=now(),end_reason='owner_disabled' where user_id=a.auth_user_id and status='active';
  delete from private.latest_locations where user_id=a.auth_user_id;
  insert into private.admin_audit_events(actor_user_id,actor_username,target_account_id,event_type)
  values(p_actor_user_id,(select username_normalized from private.member_accounts where auth_user_id=p_actor_user_id),p_account_id,'account.disabled');
  return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('disabled',true,'sessionsRevoked',true,'sharingStopped',true));
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
    from private.member_accounts a where (a.auth_user_id is not null or a.status='provisioning') and (p_cursor is null or a.account_id < p_cursor)
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

create or replace function public.opentrack_admin_list_audit(p_actor_user_id uuid, p_limit integer default 100, p_cursor bigint default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_data jsonb; v_limit integer := least(greatest(coalesce(p_limit,100),1),200); v_has_more boolean; v_next bigint;
begin
  if not exists(select 1 from private.member_accounts where auth_user_id=p_actor_user_id and is_platform_admin and status='active') then
    return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','forbidden','message','Forbidden')));
  end if;
  with page as (
    select e.*, row_number() over(order by e.audit_id desc) as rn
    from private.admin_audit_events e where p_cursor is null or e.audit_id < p_cursor
    order by e.audit_id desc limit v_limit + 1
  )
  select coalesce(jsonb_agg(jsonb_build_object('id',p.audit_id,'actorUsername',p.actor_username,'targetAccountId',p.target_account_id,
      'eventType',p.event_type,'metadata',p.metadata,'createdAt',p.created_at) order by p.audit_id desc) filter(where p.rn <= v_limit),'[]'::jsonb),
    coalesce(bool_or(p.rn > v_limit),false),
    (array_agg(p.audit_id order by p.audit_id desc) filter(where p.rn <= v_limit))[v_limit]
  into v_data,v_has_more,v_next from page p;
  return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',case when v_has_more then v_next::text else null end));
end;
$$;

create or replace function public.opentrack_begin_admin_bootstrap(p_claim_id uuid)
returns boolean language plpgsql security definer set search_path = '' as $$
declare v_count integer;
begin
  perform pg_advisory_xact_lock(hashtext('opentrack360:first-admin-bootstrap'));
  select count(*) into v_count from private.member_accounts where is_platform_admin and status='active';
  if v_count > 0 then return false; end if;
  update private.bootstrap_state set claim_id=p_claim_id, claimed_at=now()
    where singleton and consumed_at is null and (claim_id is null or claimed_at < now()-interval '10 minutes');
  return found;
end;
$$;

create or replace function public.opentrack_finish_admin_bootstrap(p_claim_id uuid, p_user_id uuid,
  p_username_normalized text, p_display_name text, p_auth_email text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare a private.member_accounts%rowtype;
begin
  perform pg_advisory_xact_lock(hashtext('opentrack360:first-admin-bootstrap'));
  if not exists(select 1 from private.bootstrap_state where singleton and claim_id=p_claim_id and consumed_at is null) then
    return jsonb_build_object('ok',false);
  end if;
  insert into private.member_accounts(auth_user_id,username_normalized,display_name,auth_email,status,is_platform_admin,must_rotate_password)
  values(p_user_id,p_username_normalized,p_display_name,p_auth_email,'active',true,true) returning * into a;
  update private.bootstrap_state set consumed_at=now() where singleton;
  insert into private.admin_audit_events(actor_user_id,actor_username,target_account_id,event_type)
  values(p_user_id,a.username_normalized,a.account_id,'admin.bootstrap');
  return jsonb_build_object('ok',true,'accountId',a.account_id,'username',a.username_normalized);
exception when unique_violation then
  return jsonb_build_object('ok',false);
end;
$$;

create or replace function public.opentrack_abort_admin_bootstrap(p_claim_id uuid)
returns boolean language plpgsql security definer set search_path = '' as $$
begin
  update private.bootstrap_state set claim_id=null,claimed_at=null
    where singleton and claim_id=p_claim_id and consumed_at is null;
  return true;
end;
$$;

revoke execute on all functions in schema public from public, anon, authenticated;
alter default privileges in schema public revoke execute on functions from public, anon, authenticated;
grant execute on function public.opentrack_login_allowed(text) to service_role;
grant execute on function public.opentrack_lookup_login(text) to service_role;
grant execute on function public.opentrack_lookup_auth_email_by_user(uuid) to service_role;
grant execute on function public.opentrack_auth_user_id_by_email(text) to service_role;
grant execute on function public.opentrack_register_member_session(uuid,uuid) to service_role;
grant execute on function public.opentrack_validate_member_session(uuid,uuid) to service_role;
grant execute on function public.opentrack_get_member_profile(uuid,uuid) to service_role;
grant execute on function public.opentrack_revoke_member_session(uuid,uuid) to service_role;
grant execute on function public.opentrack_rotate_password_state(uuid,uuid) to service_role;
grant execute on function public.opentrack_claim_username(uuid,uuid,text,text,text,uuid) to service_role;
grant execute on function public.opentrack_finalize_account(uuid,uuid,uuid,uuid) to service_role;
grant execute on function public.opentrack_abort_account(uuid,uuid,uuid) to service_role;
grant execute on function public.opentrack_admin_get_account(uuid,uuid) to service_role;
grant execute on function public.opentrack_admin_begin_reset(uuid,uuid,uuid) to service_role;
grant execute on function public.opentrack_admin_complete_reset(uuid,uuid,uuid) to service_role;
grant execute on function public.opentrack_admin_fail_reset(uuid,uuid,uuid) to service_role;
grant execute on function public.opentrack_admin_disable_account(uuid,uuid) to service_role;
grant execute on function public.opentrack_admin_list_accounts(uuid,integer,uuid) to service_role;
grant execute on function public.opentrack_admin_list_audit(uuid,integer,bigint) to service_role;
grant execute on function public.opentrack_begin_admin_bootstrap(uuid) to service_role;
grant execute on function public.opentrack_finish_admin_bootstrap(uuid,uuid,text,text,text) to service_role;
grant execute on function public.opentrack_abort_admin_bootstrap(uuid) to service_role;
