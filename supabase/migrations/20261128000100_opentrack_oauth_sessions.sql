alter table private.member_accounts
  add column if not exists session_epoch_started_at timestamptz not null default date_trunc('second', now());

update private.member_accounts
set session_epoch_started_at = date_trunc('second', now())
where session_epoch_started_at is null;

create or replace function private.mark_session_epoch_started()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.session_epoch is distinct from old.session_epoch then
    -- JWT iat is an integer Unix timestamp. Advance the boundary to the next
    -- whole second so a token issued immediately before the epoch change
    -- cannot be enrolled as a new OAuth session.
    new.session_epoch_started_at := date_trunc('second', clock_timestamp()) + interval '1 second';
  end if;
  return new;
end;
$$;

drop trigger if exists member_accounts_mark_session_epoch_started on private.member_accounts;
create trigger member_accounts_mark_session_epoch_started
before update of session_epoch on private.member_accounts
for each row execute function private.mark_session_epoch_started();

revoke all on function private.mark_session_epoch_started() from public, anon, authenticated;

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
    'isPlatformAdmin', a.is_platform_admin
  );
end;
$$;

revoke all on function public.opentrack_register_oauth_session(uuid, uuid, bigint) from public, anon, authenticated;
grant execute on function public.opentrack_register_oauth_session(uuid, uuid, bigint) to service_role;
