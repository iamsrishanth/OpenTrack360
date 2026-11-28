begin;

create temporary table oauth_session_fixture (
  user_id uuid primary key default gen_random_uuid(),
  first_session_id uuid not null default gen_random_uuid(),
  stale_session_id uuid not null default gen_random_uuid(),
  active_session_id uuid not null default gen_random_uuid(),
  rotate_session_id uuid not null default gen_random_uuid(),
  initial_iat bigint,
  fresh_iat bigint
);
insert into oauth_session_fixture default values;

insert into auth.users(id, email, encrypted_password, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
select user_id,
  'oauth-epoch-' || replace(user_id::text, '-', '') || '@opentrack360.invalid',
  '', '{}'::jsonb, '{}'::jsonb, now(), now()
from oauth_session_fixture;

insert into private.member_accounts(auth_user_id, username_normalized, display_name, auth_email, status, is_platform_admin, must_rotate_password)
select user_id,
  'oe' || substr(replace(user_id::text, '-', ''), 1, 28),
  'OAuth epoch fixture',
  'oauth-epoch-' || replace(user_id::text, '-', '') || '@opentrack360.invalid',
  'active', false, false
from oauth_session_fixture;

update oauth_session_fixture f
set initial_iat = floor(extract(epoch from a.session_epoch_started_at))::bigint
from private.member_accounts a
where a.auth_user_id = f.user_id;

select extensions.plan(8);
select extensions.ok(has_function_privilege('service_role',
  'public.opentrack_register_oauth_session(uuid,uuid,bigint)'::regprocedure, 'execute'),
  'the server role can register a validated OAuth session');
select extensions.ok(not has_function_privilege('anon',
  'public.opentrack_register_oauth_session(uuid,uuid,bigint)'::regprocedure, 'execute'),
  'anonymous callers cannot register OAuth sessions');
select extensions.ok(not has_function_privilege('authenticated',
  'public.opentrack_register_oauth_session(uuid,uuid,bigint)'::regprocedure, 'execute'),
  'member tokens cannot register OAuth sessions directly');
select extensions.is((public.opentrack_register_oauth_session(f.user_id,f.first_session_id,f.initial_iat)->>'allowed'),
  'true','a current OAuth token can register at the current epoch boundary')
from oauth_session_fixture f;

update private.member_accounts a
set session_epoch = session_epoch + 1
from oauth_session_fixture f
where a.auth_user_id = f.user_id;

update oauth_session_fixture f
set fresh_iat = floor(extract(epoch from a.session_epoch_started_at))::bigint
from private.member_accounts a
where a.auth_user_id = f.user_id;

select extensions.is((public.opentrack_register_oauth_session(f.user_id,f.stale_session_id,f.initial_iat)->>'allowed'),
  'false','a token issued before a new session epoch cannot be enrolled')
from oauth_session_fixture f;
select extensions.is((public.opentrack_register_oauth_session(f.user_id,f.active_session_id,f.fresh_iat)->>'allowed'),
  'true','a new OAuth token at the active epoch can be enrolled')
from oauth_session_fixture f;

update private.member_accounts a set must_rotate_password = true
from oauth_session_fixture f where a.auth_user_id = f.user_id;
select extensions.is((public.opentrack_register_oauth_session(f.user_id,f.rotate_session_id,f.fresh_iat)->>'mustRotatePassword'),
  'true','OAuth session enrollment reports mandatory password rotation without opening member data')
from oauth_session_fixture f;

update private.member_sessions s set revoked_at = now()
from oauth_session_fixture f
where s.session_id = f.active_session_id and s.user_id = f.user_id;
select extensions.is((public.opentrack_register_oauth_session(f.user_id,f.active_session_id,f.fresh_iat)->>'allowed'),
  'false','a revoked OAuth session cannot be registered again')
from oauth_session_fixture f;

select * from extensions.finish();
rollback;
