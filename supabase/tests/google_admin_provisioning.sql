begin;
select extensions.plan(19);

select extensions.ok(not has_function_privilege('service_role',
  'private.opentrack_operator_add_google_admin(uuid,uuid,uuid,text,text,text)'::regprocedure, 'execute'),
  'the web runtime cannot grant Google administrator roles');
select extensions.ok(not has_function_privilege('anon',
  'private.opentrack_operator_add_google_admin(uuid,uuid,uuid,text,text,text)'::regprocedure, 'execute'),
  'anonymous clients cannot invoke Google administrator provisioning');
select extensions.ok(not has_function_privilege('authenticated',
  'private.opentrack_operator_add_google_admin(uuid,uuid,uuid,text,text,text)'::regprocedure, 'execute'),
  'member sessions cannot invoke Google administrator provisioning');
select extensions.ok(has_function_privilege('postgres',
  'private.opentrack_operator_add_google_admin(uuid,uuid,uuid,text,text,text)'::regprocedure, 'execute'),
  'the project database owner can invoke Google administrator provisioning');
select extensions.ok((select relrowsecurity from pg_class where oid='private.operator_google_admin_provision_audit'::regclass),
  'operator audit storage has row-level security enabled');
select extensions.ok(not has_table_privilege('service_role', 'private.operator_google_admin_provision_audit', 'select')
  and not has_table_privilege('service_role', 'private.operator_google_admin_provision_audit', 'insert'),
  'the audit table is not exposed to the web runtime');
select extensions.ok(not has_table_privilege('service_role', 'private.member_accounts', 'insert')
  and not has_table_privilege('service_role', 'private.member_accounts', 'update')
  and not has_table_privilege('service_role', 'private.member_accounts', 'delete'),
  'the web runtime cannot write platform account mappings directly');

insert into auth.users(id, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
values('11111111-1111-4111-8111-111111111111','existing-admin@opentrack360.invalid','',now(),'{}'::jsonb,'{}'::jsonb,now(),now());
insert into private.member_accounts(auth_user_id, username_normalized, display_name, auth_email, status, is_platform_admin, must_rotate_password)
values('11111111-1111-4111-8111-111111111111','existingadmin','Existing Admin','existing-admin@opentrack360.invalid','active',true,false);
update private.bootstrap_state set consumed_at=now(), claim_id=null, claimed_at=null where singleton;

insert into auth.users(id, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
values(
  '22222222-2222-4222-8222-222222222222',
  'google-admin@example.com',
  '',
  now(),
  '{}'::jsonb,
  jsonb_build_object(
    'opentrack_google_admin_email','google-admin@example.com',
    'opentrack_google_admin_request_id','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
  ),
  now(),now()
);

with provisioned as materialized (
  select private.opentrack_operator_add_google_admin(
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    '99999999-9999-4999-8999-999999999999',
    '22222222-2222-4222-8222-222222222222',
    'google-admin@example.com',
    'google-admin.admin',
    'oxfcxucpzrvbifefnwvp'
  ) as result
)
select extensions.is(result->>'ok','true','the project owner can provision a Google-only administrator') from provisioned;

select extensions.ok(exists(select 1 from private.member_accounts
  where auth_user_id='22222222-2222-4222-8222-222222222222'
    and username_normalized='google-admin.admin' and status='active' and is_platform_admin
    and not must_rotate_password and admin_auth_method='google'),
  'the Google administrator is active and does not receive a password login');
select extensions.ok(exists(select 1 from private.member_accounts
  where auth_user_id='22222222-2222-4222-8222-222222222222'
    and not sharing_enabled and not activity_sharing_enabled),
  'the new administrator starts with location sharing disabled');
select extensions.ok(exists(select 1 from private.operator_google_admin_provision_audit
  where request_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
    and operator_subject_id='99999999-9999-4999-8999-999999999999'
    and project_ref='oxfcxucpzrvbifefnwvp'
    and target_auth_user_id='22222222-2222-4222-8222-222222222222'
    and google_email='google-admin@example.com'
    and target_username='google-admin.admin'
    and database_role='postgres'),
  'the operator audit records the verified subject, project, target, email, username, and database role');
select extensions.ok(exists(select 1 from private.admin_audit_events
  where actor_user_id='99999999-9999-4999-8999-999999999999'
    and actor_username='supabase-cli-operator'
    and event_type='account.google_admin_provisioned'),
  'the normal administration audit records the operator action');

with replayed as materialized (
  select private.opentrack_operator_add_google_admin(
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    '99999999-9999-4999-8999-999999999999',
    '22222222-2222-4222-8222-222222222222',
    'google-admin@example.com',
    'google-admin.admin',
    'oxfcxucpzrvbifefnwvp'
  ) as result
)
select extensions.is(result->>'idempotent','true','replaying the same operator request returns its existing account') from replayed;
select extensions.is((select count(*)::integer from private.operator_google_admin_provision_audit
  where request_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),1,
  'idempotent replay does not duplicate the operator audit row');
select extensions.is((select count(*)::integer from private.member_accounts where is_platform_admin and status='active'),2,
  'the original administrator remains and exactly one Google administrator is added');
select extensions.ok(exists(select 1 from private.member_accounts where auth_user_id='11111111-1111-4111-8111-111111111111'
  and status='active' and is_platform_admin and admin_auth_method='password')
  and (select consumed_at is not null and claim_id is null from private.bootstrap_state where singleton),
  'the existing administrator and consumed bootstrap are unchanged');
select extensions.is((public.opentrack_admin_list_accounts('11111111-1111-4111-8111-111111111111')->'body'->'data') @>
  '[{"username":"google-admin.admin"}]'::jsonb, false,
  'platform administrator identities are not exposed in the member account list');
select extensions.is((public.opentrack_admin_begin_reset(
  'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  (select account_id from private.member_accounts where auth_user_id='22222222-2222-4222-8222-222222222222'),
  '11111111-1111-4111-8111-111111111111'
)->>'httpStatus')::integer,409,
  'a platform administrator cannot be reset through member password recovery');
select extensions.throws_ok($$select private.opentrack_operator_add_google_admin(
    'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    '99999999-9999-4999-8999-999999999999',
    '22222222-2222-4222-8222-222222222222',
    'wrong-email@example.com',
    'wrong-email.admin',
    'oxfcxucpzrvbifefnwvp'
  )$$,
  '22023',
  'target Auth identity must have the requested verified email',
  'the operator function refuses to attach an Auth user to a different Google email');

select * from extensions.finish();
rollback;
