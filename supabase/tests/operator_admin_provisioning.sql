begin;
select extensions.plan(15);

select extensions.ok(not has_function_privilege('service_role',
  'private.opentrack_operator_provision_admin(uuid,uuid,uuid,text,text,text)'::regprocedure, 'execute'),
  'the web runtime service role cannot promote a platform administrator');
select extensions.ok(not has_function_privilege('anon',
  'private.opentrack_operator_provision_admin(uuid,uuid,uuid,text,text,text)'::regprocedure, 'execute'),
  'anonymous clients cannot invoke operator provisioning');
select extensions.ok(not has_function_privilege('authenticated',
  'private.opentrack_operator_provision_admin(uuid,uuid,uuid,text,text,text)'::regprocedure, 'execute'),
  'member sessions cannot invoke operator provisioning');
select extensions.ok(has_function_privilege('postgres',
  'private.opentrack_operator_provision_admin(uuid,uuid,uuid,text,text,text)'::regprocedure, 'execute'),
  'the project database owner can invoke operator provisioning');
select extensions.ok(not has_table_privilege('service_role', 'private.member_accounts', 'insert')
  and not has_table_privilege('service_role', 'private.member_accounts', 'update')
  and not has_table_privilege('service_role', 'private.member_accounts', 'delete'),
  'the web runtime service role cannot write platform account mappings directly');
select extensions.ok(not has_table_privilege('service_role', 'private.operator_admin_provision_audit', 'select')
  and not has_table_privilege('service_role', 'private.operator_admin_provision_audit', 'insert'),
  'the operator audit table is not exposed to the web runtime');
select extensions.ok((select relrowsecurity from pg_class where oid='private.operator_admin_provision_audit'::regclass),
  'operator audit storage has row-level security enabled');

insert into auth.users(id, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
values('11111111-1111-4111-8111-111111111111','existing-admin@opentrack360.invalid','',now(),'{}'::jsonb,'{}'::jsonb,now(),now());
insert into private.member_accounts(auth_user_id, username_normalized, display_name, auth_email, status, is_platform_admin, must_rotate_password)
values('11111111-1111-4111-8111-111111111111','existingadmin','Existing Admin','existing-admin@opentrack360.invalid','active',true,false);
update private.bootstrap_state set consumed_at=now(), claim_id=null, claimed_at=null where singleton;

insert into auth.users(id, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
values(
  '22222222-2222-4222-8222-222222222222',
  'opentrack360-release-operator-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa@opentrack360.invalid',
  '', now(), '{}'::jsonb,
  jsonb_build_object('opentrack_username','release-op-aaaaaaaa','opentrack_operator_request_id','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),
  now(), now()
);

with provisioned as materialized (
  select private.opentrack_operator_provision_admin(
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    '99999999-9999-4999-8999-999999999999',
    '22222222-2222-4222-8222-222222222222',
    'release-op-aaaaaaaa',
    'OpenTrack360 Release Test Operator',
    'oxfcxucpzrvbifefnwvp'
  ) as result
)
select extensions.is(result->>'ok','true','project-owner command provisions a new operator account') from provisioned;

select extensions.throws_ok($$select private.opentrack_operator_provision_admin(
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    '22222222-2222-4222-8222-222222222222',
    '22222222-2222-4222-8222-222222222222',
    'release-op-bbbbbbbb',
    'OpenTrack360 Release Test Operator',
    'oxfcxucpzrvbifefnwvp'
  )$$,
  '22023',
  'operator subject must not be the target Auth user',
  'a database operator cannot promote their own Auth user');

select extensions.ok(exists(select 1 from private.member_accounts where auth_user_id='22222222-2222-4222-8222-222222222222'
  and username_normalized='release-op-aaaaaaaa' and status='active' and is_platform_admin and must_rotate_password
  and not sharing_enabled and not activity_sharing_enabled),
  'new operator is active, platform-scoped, and required to rotate its initial password');
select extensions.ok(exists(select 1 from private.operator_admin_provision_audit where request_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
  and operator_subject_id='99999999-9999-4999-8999-999999999999' and project_ref='oxfcxucpzrvbifefnwvp'
  and target_auth_user_id='22222222-2222-4222-8222-222222222222' and target_username='release-op-aaaaaaaa'
  and database_role='postgres'),
  'audit records the verified operator subject, project, database role, target, and request id');

with replayed as materialized (
  select private.opentrack_operator_provision_admin(
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    '99999999-9999-4999-8999-999999999999',
    '22222222-2222-4222-8222-222222222222',
    'release-op-aaaaaaaa',
    'OpenTrack360 Release Test Operator',
    'oxfcxucpzrvbifefnwvp'
  ) as result
)
select extensions.is(result->>'idempotent','true','repeating the same request returns its original account') from replayed;

select extensions.is((select count(*)::integer from private.member_accounts where is_platform_admin and status='active'),2,
  'the existing administrator remains active and exactly one new administrator is added');
select extensions.is((select count(*)::integer from private.operator_admin_provision_audit where request_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),1,
  'an idempotent replay does not duplicate the operator audit event');
select extensions.ok(exists(select 1 from private.member_accounts where auth_user_id='11111111-1111-4111-8111-111111111111'
  and status='active' and is_platform_admin and not must_rotate_password)
  and (select consumed_at is not null and claim_id is null from private.bootstrap_state where singleton),
  'existing admin and consumed first-admin bootstrap state are unchanged');

select * from extensions.finish();
rollback;
