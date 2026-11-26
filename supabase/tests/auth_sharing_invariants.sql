begin;
select extensions.plan(98);

-- This pgTAP file runs in one connection and one rollback transaction, so a
-- second connection cannot see its uncommitted fixtures. It verifies the
-- post-revocation terminal response below, but a true overlapping read/revoke
-- race needs an external two-session integration test using committed
-- synthetic fixtures. That harness should exercise both orders: a read holding
-- the account/share locks makes revoke wait and linearizes first; a revoke that
-- holds them first makes the later read return 410 without location. Both RPCs
-- lock member_accounts before guest_shares, so the competing calls cannot
-- invert their lock order.

select extensions.ok(not has_schema_privilege('anon','private','usage'),'private schema is not visible to anonymous Supabase clients');
select extensions.ok(not has_table_privilege('anon','private.member_accounts','select'),'anonymous clients cannot read identity mapping');
select extensions.ok(not has_table_privilege('authenticated','private.location_samples','select'),'authenticated direct clients cannot read location history');
select extensions.ok(not has_table_privilege('authenticated','private.admin_audit_events','insert'),'authenticated direct clients cannot forge admin audit events');
select extensions.ok(not has_function_privilege('anon','public.opentrack_api_dispatch(uuid,uuid,text,jsonb,bigint)','execute'),'anonymous clients cannot execute member dispatch');
select extensions.ok(has_function_privilege('service_role','public.opentrack_api_dispatch(uuid,uuid,text,jsonb,bigint)','execute'),'server service role can execute member dispatch');
select extensions.ok(not has_function_privilege('authenticated','public.opentrack_claim_username(uuid,uuid,text,text,text,uuid)','execute'),'members cannot invoke platform account provisioning');
select extensions.ok(not exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='private' and c.relkind='r' and not c.relrowsecurity),'RLS is enabled on every private table');
select extensions.ok(not exists(select 1 from storage.buckets where id='pet-photos' and public),'pet photo storage bucket is private');

insert into auth.users(id,email,encrypted_password,raw_app_meta_data,raw_user_meta_data,created_at,updated_at)
values('11111111-1111-4111-8111-111111111111','admin-bootstrap@opentrack360.invalid','', '{}'::jsonb,'{}'::jsonb,now(),now());

select extensions.is(public.opentrack_begin_admin_bootstrap('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),true,'first administrator bootstrap claim succeeds');
select extensions.is((public.opentrack_finish_admin_bootstrap('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','11111111-1111-4111-8111-111111111111','rootadmin','Root Admin','admin-bootstrap@opentrack360.invalid')->>'ok')::boolean,true,'bootstrap activates the first platform administrator');
select extensions.is(public.opentrack_begin_admin_bootstrap('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'),false,'bootstrap is one-time after the first admin is active');

update private.member_accounts set must_rotate_password=false,sharing_enabled=true where auth_user_id='11111111-1111-4111-8111-111111111111';
select extensions.is((public.opentrack_register_member_session('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111')->>'allowed')::boolean,true,'active admin gets a registered server session');
insert into private.member_sessions(session_id,user_id,session_epoch)
select 'eeeeeeee-1111-4111-8111-111111111111',auth_user_id,session_epoch
from private.member_accounts where auth_user_id='11111111-1111-4111-8111-111111111111';

select extensions.is((public.opentrack_claim_username('cccccccc-cccc-4ccc-8ccc-cccccccccccc','dddddddd-dddd-4ddd-8ddd-dddddddddddd','memberone','Member One','member-one@opentrack360.invalid','11111111-1111-4111-8111-111111111111')->>'httpStatus')::integer,201,'platform admin can claim a unique member username');
select extensions.ok(exists(select 1 from jsonb_array_elements(public.opentrack_admin_list_accounts('11111111-1111-4111-8111-111111111111',100,null)->'body'->'data') as item(value)
  where item.value->>'username'='memberone' and item.value->>'status'='provisioning'),'admin account list exposes a reserved provisioning row for cleanup visibility');
select extensions.is((public.opentrack_claim_username('cccccccc-cccc-4ccc-8ccc-cccccccccccc','eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee','memberone','Member One','member-one-retry@opentrack360.invalid','11111111-1111-4111-8111-111111111111')->>'httpStatus')::integer,409,'a concurrent replay cannot start a second provisioning worker');
select extensions.is((public.opentrack_claim_username('ffffffff-ffff-4fff-8fff-ffffffffffff','12121212-1212-4121-8121-121212121212','memberone','Other Name','other@opentrack360.invalid','11111111-1111-4111-8111-111111111111')->>'httpStatus')::integer,409,'normalized username uniqueness is safe across request ids');

insert into auth.users(id,email,encrypted_password,raw_app_meta_data,raw_user_meta_data,created_at,updated_at)
values('22222222-2222-4222-8222-222222222222','member-one@opentrack360.invalid','', '{}'::jsonb,'{}'::jsonb,now(),now());
select extensions.is((public.opentrack_finalize_account('cccccccc-cccc-4ccc-8ccc-cccccccccccc','dddddddd-dddd-4ddd-8ddd-dddddddddddd','22222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111')->>'httpStatus')::integer,201,'provisioning finalizes with the matching server lease');
select extensions.is((public.opentrack_claim_username('cccccccc-cccc-4ccc-8ccc-cccccccccccc','12121212-1212-4121-8121-121212121212','memberone','Member One','member-one@opentrack360.invalid','11111111-1111-4111-8111-111111111111')->>'httpStatus')::integer,409,'a completed provisioning replay cannot silently reset the password');
select extensions.is((public.opentrack_claim_username('abababab-abab-4aba-8aba-abababababab','cdcdcdcd-cdcd-4cdc-8cdc-cdcdcdcdcdcd','membertwo','Member Two','member-two@opentrack360.invalid','22222222-2222-4222-8222-222222222222')->>'httpStatus')::integer,403,'a Circle or regular member cannot provision platform accounts');
select extensions.is((public.opentrack_admin_get_account((select account_id from private.member_accounts where auth_user_id='22222222-2222-4222-8222-222222222222'),'11111111-1111-4111-8111-111111111111')->>'httpStatus')::integer,200,'platform admin account lookup is available without map privileges');
select extensions.ok(position('latitude' in public.opentrack_admin_list_accounts('11111111-1111-4111-8111-111111111111',100,null)::text)=0,'admin account list has no location or map data');

insert into private.circles(id,name,created_by) values('abababab-abab-4aba-8aba-abababababab','Reset Consent Circle','11111111-1111-4111-8111-111111111111');
insert into private.circle_members(circle_id,user_id,role,sharing_enabled,activity_sharing_enabled)
values('abababab-abab-4aba-8aba-abababababab','22222222-2222-4222-8222-222222222222','member',true,true);
update private.member_accounts set must_rotate_password=false where auth_user_id='22222222-2222-4222-8222-222222222222';
select extensions.is((public.opentrack_register_member_session('22222222-2222-4222-8222-222222222222','bbbbbbbb-2222-4222-8222-222222222222')->>'allowed')::boolean,true,'member session registers before reset');
insert into private.guest_shares(id,owner_id,token_hash,mode,sharing_epoch,expires_at)
values('babababa-baba-4bab-8bab-babababababa','22222222-2222-4222-8222-222222222222',repeat('8',64),'time',1,now()+interval '1 hour');
insert into private.location_samples(sample_id,user_id,session_id,sharing_epoch,captured_at,latitude,longitude,accuracy_meters,speed_mps)
values('cdcdcdcd-cdcd-4cdc-8dcd-cdcdcdcdcdcd','22222222-2222-4222-8222-222222222222','bbbbbbbb-2222-4222-8222-222222222222',1,now(),19,72,8,2);
insert into private.latest_locations(user_id,sample_id,captured_at,latitude,longitude,accuracy_meters,speed_mps,sharing_epoch)
values('22222222-2222-4222-8222-222222222222','cdcdcdcd-cdcd-4cdc-8dcd-cdcdcdcdcdcd',now(),19,72,8,2,1);
insert into private.trips(id,user_id,circle_id,recipients,status,confirmed_drive)
values('dededede-dede-4ded-8ded-dededededede','22222222-2222-4222-8222-222222222222','abababab-abab-4aba-8aba-abababababab',array[]::uuid[],'active',true);
select extensions.is((public.opentrack_admin_begin_reset('23232323-2323-4232-8232-232323232323',(select account_id from private.member_accounts where auth_user_id='22222222-2222-4222-8222-222222222222'),'11111111-1111-4111-8111-111111111111')->>'httpStatus')::integer,200,'admin reset starts and immediately revokes existing sessions');
select extensions.is((select sharing_epoch from private.member_accounts where auth_user_id='22222222-2222-4222-8222-222222222222'),2::bigint,'admin reset advances sharing epoch to invalidate existing links');
select extensions.ok((select not sharing_enabled and not activity_sharing_enabled from private.circle_members where circle_id='abababab-abab-4aba-8aba-abababababab' and user_id='22222222-2222-4222-8222-222222222222'),
  'admin reset disables Circle and activity consent');
select extensions.ok((select status='ended' and end_reason='admin_password_reset' from private.guest_shares where id='babababa-baba-4bab-8bab-babababababa')
  and (public.opentrack_share_read('babababa-baba-4bab-8bab-babababababa',repeat('8',64))->>'httpStatus')::integer=410,
  'admin reset revokes active guest links');
select extensions.is((select status from private.trips where id='dededede-dede-4ded-8ded-dededededede'),'ended','admin reset ends active trips');
select extensions.ok(not exists(select 1 from private.latest_locations where user_id='22222222-2222-4222-8222-222222222222'),'admin reset clears latest location');
select extensions.is((public.opentrack_validate_member_session('22222222-2222-4222-8222-222222222222','bbbbbbbb-2222-4222-8222-222222222222')->>'allowed')::boolean,false,'reset-pending session is denied');
with reset_result as (select public.opentrack_admin_complete_reset('23232323-2323-4232-8232-232323232323',
  (select account_id from private.member_accounts where auth_user_id='22222222-2222-4222-8222-222222222222'),'11111111-1111-4111-8111-111111111111') result)
select extensions.ok((result->>'httpStatus')::integer=200 and (result->'body'->>'sharingStopped')::boolean
  and (result->'body'->>'sharingEpoch')::bigint=2,'admin reset completes after password update and returns sharing-stopped state') from reset_result;
select extensions.is((public.opentrack_register_member_session('22222222-2222-4222-8222-222222222222','bbbbbbbb-2222-4222-8222-222222222222')->>'allowed')::boolean,false,'a revoked refresh session cannot re-register after reset');
select extensions.is((public.opentrack_register_member_session('22222222-2222-4222-8222-222222222222','cccccccc-2222-4222-8222-222222222222')->>'allowed')::boolean,true,'a fresh login session can register after reset');
select extensions.is((public.opentrack_admin_begin_reset('24242424-2424-4242-8242-242424242424',(select account_id from private.member_accounts where auth_user_id='22222222-2222-4222-8222-222222222222'),'11111111-1111-4111-8111-111111111111')->>'httpStatus')::integer,200,'a later admin reset can start after the first one completes');
select extensions.is(public.opentrack_admin_fail_reset('24242424-2424-4242-8242-242424242424',(select account_id from private.member_accounts where auth_user_id='22222222-2222-4222-8222-222222222222'),'11111111-1111-4111-8111-111111111111'),true,'failed external password reset can release the pending reset gate');
select extensions.is((select reset_pending from private.member_accounts where auth_user_id='22222222-2222-4222-8222-222222222222'),false,'failed reset does not leave the account permanently locked');
select extensions.is((public.opentrack_register_member_session('22222222-2222-4222-8222-222222222222','dddddddd-2222-4222-8222-222222222222')->>'allowed')::boolean,true,'fresh login can register after a failed reset cleanup');
update private.member_accounts set must_rotate_password=false where auth_user_id='22222222-2222-4222-8222-222222222222';

insert into private.circles(id,name,created_by) values
 ('33333333-3333-4333-8333-333333333333','Member Circle','11111111-1111-4111-8111-111111111111'),
 ('44444444-4444-4444-8444-444444444444','Admin Circle','11111111-1111-4111-8111-111111111111');
insert into private.circle_members(circle_id,user_id,role,sharing_enabled) values
 ('33333333-3333-4333-8333-333333333333','22222222-2222-4222-8222-222222222222','member',true),
 ('44444444-4444-4444-8444-444444444444','11111111-1111-4111-8111-111111111111','admin',true);
select extensions.ok((public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','startTrip',
  jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444'),'query','{}'::jsonb,
    'body',jsonb_build_object('recipientMemberIds',jsonb_build_array('11111111-1111-4111-8111-111111111111'),'confirmedDrive',true)),1)->>'httpStatus')::integer=201
  and public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','listTrips',
    jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444'),'query','{}'::jsonb),1)->'body'->'data'->0->>'confirmedDrive'='true'
  and exists(select 1 from private.trips where user_id='11111111-1111-4111-8111-111111111111' and status='active'
    and confirmed_drive and started_session_id='aaaaaaaa-1111-4111-8111-111111111111' and sharing_epoch=1),
  'an explicit start-trip confirmation is returned and bound to its member session and sharing epoch');
with unconfirmed_trip as (
  select public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','startTrip',
    jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444'),'query','{}'::jsonb,
      'body',jsonb_build_object('recipientMemberIds',jsonb_build_array('11111111-1111-4111-8111-111111111111'))),1) result)
select extensions.ok((result->>'httpStatus')::integer=201 and result->'body'->>'confirmedDrive'='false',
  'an omitted trip-start confirmation stays false in the response') from unconfirmed_trip;
select extensions.ok(exists(select 1 from private.trips where user_id='11111111-1111-4111-8111-111111111111'
  and circle_id='44444444-4444-4444-8444-444444444444' and status='active' and confirmed_drive),'confirmed drive state is persisted for event validation');
with stopped_trip as (
  select public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','stopTrip',
    jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444','tripId',
        (select id::text from private.trips where user_id='11111111-1111-4111-8111-111111111111' and circle_id='44444444-4444-4444-8444-444444444444' and status='active' and confirmed_drive order by started_at desc limit 1)),
      'query','{}'::jsonb,'body',jsonb_build_object('clientRequestId','23232323-2323-4232-8232-232323232323','endReason','other')),1) result)
select extensions.ok((result->>'httpStatus')::integer=200 and result->'body'->>'confirmedDrive'='true',
  'trip stop preserves the server-confirmed driver state in its terminal response') from stopped_trip;
select extensions.is((public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','createCircleInvite',
  jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444'),'query','{}'::jsonb,
    'body',jsonb_build_object('clientRequestId','abababab-1111-4111-8111-111111111111','role','member','expiresAt',now()+interval '1 day','maxUses',1)),1)->>'httpStatus')::integer,201,'Circle admin can create one-time invitation');
select extensions.is((public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','createCircleInvite',
  jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444'),'query','{}'::jsonb,
    'body',jsonb_build_object('clientRequestId','abababab-1111-4111-8111-111111111111','role','member','expiresAt',now()+interval '1 day','maxUses',1)),1)->>'httpStatus')::integer,409,'replayed Circle invite request cannot reissue its secret');
select extensions.ok((select length(token_hash)=64 from private.circle_invites where client_request_id='abababab-1111-4111-8111-111111111111')
  and not (public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','listCircleInvites',
    jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444'),'query','{}'::jsonb),1)->'body'->'data'->0 ? 'inviteToken'),
  'invite list stores only a digest and never repeats its secret');
select extensions.is((public.opentrack_api_dispatch('22222222-2222-4222-8222-222222222222','dddddddd-2222-4222-8222-222222222222','acceptCircleInvite',
  jsonb_build_object('path','{}'::jsonb,'query','{}'::jsonb,'body',jsonb_build_object('inviteTokenHash',(select token_hash from private.circle_invites where client_request_id='abababab-1111-4111-8111-111111111111'))),3)->>'httpStatus')::integer,200,
  'Circle invite acceptance looks up the server-supplied digest and derives the invitee from session identity');
select extensions.is((public.opentrack_api_dispatch('22222222-2222-4222-8222-222222222222','dddddddd-2222-4222-8222-222222222222','acceptCircleInvite',
  jsonb_build_object('path','{}'::jsonb,'query','{}'::jsonb,'body',jsonb_build_object('inviteTokenHash',(select token_hash from private.circle_invites where client_request_id='abababab-1111-4111-8111-111111111111'))),3)->>'httpStatus')::integer,404,
  'Circle invite is one-use and cannot be accepted again');
select extensions.is((public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','createPet',
  jsonb_build_object('path','{}'::jsonb,'query','{}'::jsonb,'body',jsonb_build_object('name','Milo','species','dog','circleIds',jsonb_build_array('44444444-4444-4444-8444-444444444444'),
    'recoveryEnabled',true,'recoveryFields',jsonb_build_array('petName','species','photo','ownerMessage','contactRoute'),'ownerMessage','Call my family','contactRoute','in_app')),1)->'body'->>'ownerMessage'),'Call my family',
  'pet creation persists owner-selected recovery content');
select extensions.is((public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','getPet',
  jsonb_build_object('path',jsonb_build_object('petId',(select id from private.pets where owner_id='11111111-1111-4111-8111-111111111111' and name='Milo')),'query','{}'::jsonb),1)->'body'->>'contactRoute'),'in_app',
  'owner-only getPet returns recovery settings');
select extensions.is((public.opentrack_api_dispatch('22222222-2222-4222-8222-222222222222','dddddddd-2222-4222-8222-222222222222','getPet',
  jsonb_build_object('path',jsonb_build_object('petId',(select id from private.pets where owner_id='11111111-1111-4111-8111-111111111111' and name='Milo')),'query','{}'::jsonb),3)->>'httpStatus')::integer,404,
  'shared Circle members cannot use owner-only getPet');
select extensions.ok((public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','createPetPhotoUpload',
  jsonb_build_object('path',jsonb_build_object('petId',(select id from private.pets where owner_id='11111111-1111-4111-8111-111111111111' and name='Milo')),'query','{}'::jsonb,
    'body',jsonb_build_object('contentType','image/jpeg','sizeBytes',100)),1)->>'httpStatus')::integer=201
  and (select photo_object_path is null from private.pets where owner_id='11111111-1111-4111-8111-111111111111' and name='Milo'),
  'photo upload authorization does not attach an object before upload');
select extensions.is((public.opentrack_api_dispatch('22222222-2222-4222-8222-222222222222','dddddddd-2222-4222-8222-222222222222','createPetPhotoUpload',
  jsonb_build_object('path',jsonb_build_object('petId',(select id from private.pets where owner_id='11111111-1111-4111-8111-111111111111' and name='Milo')),'query','{}'::jsonb,
    'body',jsonb_build_object('contentType','image/jpeg','sizeBytes',100)),3)->>'httpStatus')::integer,403,
  'only the pet owner can create a photo upload');
select extensions.is((public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','updatePet',
  jsonb_build_object('path',jsonb_build_object('petId',(select id from private.pets where owner_id='11111111-1111-4111-8111-111111111111' and name='Milo')),'query','{}'::jsonb,
    'body',jsonb_build_object('photoObjectPath',(select id::text from private.pets where owner_id='11111111-1111-4111-8111-111111111111' and name='Milo')||'/missing.jpg')),1)->>'httpStatus')::integer,400,
  'pet update cannot attach a photo object until the scoped upload exists');
update private.pets set photo_object_path=id::text||'/owner-photo.jpg' where owner_id='11111111-1111-4111-8111-111111111111' and name='Milo';
with updated_pet as (select public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','updatePet',
  jsonb_build_object('path',jsonb_build_object('petId',(select id from private.pets where owner_id='11111111-1111-4111-8111-111111111111' and name='Milo')),'query','{}'::jsonb,
    'body',jsonb_build_object('ownerMessage','Updated owner message','contactRoute',null,'recoveryFields',jsonb_build_array('petName','species','ownerMessage'))),1) result)
select extensions.ok(result->'body'->>'ownerMessage'='Updated owner message'
  and result->'body'->>'photoObjectPath'=(select id::text||'/owner-photo.jpg' from private.pets where owner_id='11111111-1111-4111-8111-111111111111' and name='Milo'),
  'owner update returns the private photo path even when photo is not recovery-approved') from updated_pet;
insert into private.pet_recovery_tokens(pet_id,token_hash)
select id,repeat('a',64) from private.pets where owner_id='11111111-1111-4111-8111-111111111111' and name='Milo';
select extensions.ok((public.opentrack_pet_recovery_read((select id from private.pets where owner_id='11111111-1111-4111-8111-111111111111' and name='Milo'),repeat('a',64))->>'httpStatus')::integer=200
  and public.opentrack_pet_recovery_read((select id from private.pets where owner_id='11111111-1111-4111-8111-111111111111' and name='Milo'),repeat('a',64))->'body'->'photoObjectPath'='null'::jsonb
  and public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','listPets',jsonb_build_object('path','{}'::jsonb,'query','{}'::jsonb),1)->'body'->'data'->0->'photoObjectPath'='null'::jsonb,
  'unselected recovery photos stay private from both recovery QR and shared pet list');
select extensions.is((public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','getActivitySharing',
  jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444'),'query','{}'::jsonb),1)->'body'->>'activitySharingEnabled'),'false','activity-sharing consent read is caller-scoped');
select extensions.is((public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','updateCircleMember',
  jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444','memberId','11111111-1111-4111-8111-111111111111'),'query','{}'::jsonb,'body',jsonb_build_object('sharingEnabled',false)),1)->>'httpStatus')::integer,400,'Circle member admin update cannot override sharing consent');
insert into private.notifications(id,recipient_user_id,circle_id,kind,delivery_state) values
 ('88888888-8888-4888-8888-888888888888','11111111-1111-4111-8111-111111111111','44444444-4444-4444-8444-444444444444','quick_note','queued'),
 ('99999999-9999-4999-8999-999999999999','22222222-2222-4222-8222-222222222222','33333333-3333-4333-8333-333333333333','quick_note','queued');
select extensions.is((public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','acknowledgeNotifications',
  jsonb_build_object('path','{}'::jsonb,'query','{}'::jsonb,'body',jsonb_build_object('notificationIds',jsonb_build_array('88888888-8888-4888-8888-888888888888','99999999-9999-4999-8999-999999999999'))),1)->'body'->>'acknowledgedCount'),'1','notification ack changes only caller-owned notifications and returns a count');
select extensions.is((select delivery_state from private.notifications where id='99999999-9999-4999-8999-999999999999'),'queued','acknowledging a foreign notification does not change its delivery state');
with created_alert as (select public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','createSosAlert',
  jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444'),'query','{}'::jsonb,
    'body',jsonb_build_object('clientEventId','31313131-3131-4313-8313-313131313131','cancelCountdownSeconds',5,'latitude',19.076,'longitude',72.8777,'accuracyMeters',12.5)),1) result)
select extensions.ok((result->>'httpStatus')::integer=202 and result->'body'->>'latitude'='19.076'
  and (public.opentrack_api_dispatch('22222222-2222-4222-8222-222222222222','dddddddd-2222-4222-8222-222222222222','getSosAlert',
    jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444','sosId',result->'body'->>'id'),'query','{}'::jsonb),3)->'body'->>'longitude')='72.8777'
  and (public.opentrack_api_dispatch('22222222-2222-4222-8222-222222222222','dddddddd-2222-4222-8222-222222222222','getSosAlert',
    jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444','sosId',result->'body'->>'id'),'query','{}'::jsonb),3)->'body'->>'accuracyMeters')='12.5'
  and not exists(select 1 from private.notifications where circle_id='44444444-4444-4444-8444-444444444444' and kind='sos' and body is not null),
  'SOS location is returned only to authorized Circle recipients and never placed in notification previews') from created_alert;
with sos as (
  select id from private.api_records where resource_type='sos_alert' and owner_id='11111111-1111-4111-8111-111111111111'
    and client_event_id='31313131-3131-4313-8313-313131313131'
), listed as (
  select public.opentrack_api_dispatch('22222222-2222-4222-8222-222222222222','dddddddd-2222-4222-8222-222222222222','listNotifications',
    jsonb_build_object('path','{}'::jsonb,'query','{}'::jsonb),3) result
)
select extensions.ok(exists(select 1 from private.notifications n join sos s on s.id=n.sos_id
      where n.recipient_user_id='22222222-2222-4222-8222-222222222222' and n.circle_id='44444444-4444-4444-8444-444444444444' and n.kind='sos')
  and exists(select 1 from jsonb_array_elements(listed.result->'body'->'data') item(value), sos s
      where item.value->>'kind'='sos' and item.value->>'circleId'='44444444-4444-4444-8444-444444444444' and item.value->>'sosId'=s.id::text),
  'recipient notification identifies the protected Circle SOS detail and acknowledgement route') from listed;
with sos as (
  select id from private.api_records where resource_type='sos_alert' and owner_id='11111111-1111-4111-8111-111111111111'
    and client_event_id='31313131-3131-4313-8313-313131313131'
), acknowledged as materialized (
  select public.opentrack_api_dispatch('22222222-2222-4222-8222-222222222222','dddddddd-2222-4222-8222-222222222222','acknowledgeSosAlert',
    jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444','sosId',(select id::text from sos)),'query','{}'::jsonb),3) result
), state as materialized (
  select n.delivery_state from private.notifications n join sos s on s.id=n.sos_id
    cross join acknowledged where n.recipient_user_id='22222222-2222-4222-8222-222222222222'
)
select extensions.ok((acknowledged.result->>'httpStatus')::integer=200 and state.delivery_state='acknowledged',
  'authenticated SOS acknowledgement updates the exact recipient notification') from acknowledged cross join state;
insert into private.api_records(resource_type,owner_id,circle_id,payload) values('checkin','22222222-2222-4222-8222-222222222222','33333333-3333-4333-8333-333333333333','{"label":"synthetic check-in"}'::jsonb);

select extensions.is((public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','getCircleMap',jsonb_build_object('path',jsonb_build_object('circleId','33333333-3333-4333-8333-333333333333'),'query','{}'::jsonb),1)->>'httpStatus')::integer,403,'platform admin without Circle membership cannot read Circle map');
select extensions.is(jsonb_array_length(public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','listCheckins',jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444'),'query','{}'::jsonb),1)->'body'->'data'),0,'Circle members cannot read check-ins from another Circle');
insert into private.guest_shares(id,owner_id,token_hash,mode,sharing_epoch,trip_id)
values('55555555-5555-4555-8555-555555555555','11111111-1111-4111-8111-111111111111',repeat('a',64),'drive_end',1,
  (select id from private.trips where user_id='11111111-1111-4111-8111-111111111111' and circle_id='44444444-4444-4444-8444-444444444444' and status='active' and confirmed_drive order by started_at desc limit 1));
insert into private.guest_shares(id,owner_id,token_hash,mode,sharing_epoch,started_at,trip_id)
values('10101010-1010-4101-8101-101010101010','11111111-1111-4111-8111-111111111111',repeat('b',64),'drive_end',1,now()-interval '1 hour',
  (select id from private.trips where user_id='11111111-1111-4111-8111-111111111111' and circle_id='44444444-4444-4444-8444-444444444444' and status='active' and confirmed_drive order by started_at desc limit 1));
insert into private.location_samples(sample_id,user_id,session_id,sharing_epoch,captured_at,latitude,longitude,accuracy_meters,speed_mps)
values('70707070-7070-4707-8707-707070707070','11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',1,now()-interval '2 hours',19,72,8,2);
insert into private.latest_locations(user_id,sample_id,captured_at,latitude,longitude,accuracy_meters,speed_mps,sharing_epoch)
values('11111111-1111-4111-8111-111111111111','70707070-7070-4707-8707-707070707070',now()-interval '2 hours',19,72,8,2,1);
select extensions.ok((public.opentrack_share_read('10101010-1010-4101-8101-101010101010',repeat('b',64))->'body'->>'status'='waiting')
  and (public.opentrack_share_read('10101010-1010-4101-8101-101010101010',repeat('b',64))->'body'->'latest'='null'::jsonb),
  'a share never returns a fix captured before its startedAt cutoff');

insert into private.guest_shares(id,owner_id,token_hash,mode,sharing_epoch,started_at,destination_latitude,destination_longitude,destination_radius_meters)
values('20202020-2020-4202-8202-202020202020','11111111-1111-4111-8111-111111111111',repeat('c',64),'destination',1,now()-interval '1 hour',19,72,150);
select extensions.ok(exists(
  select 1 from jsonb_array_elements(public.opentrack_share_list('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111')->'body'->'data') item(value)
  where item.value->>'id'='20202020-2020-4202-8202-202020202020'
    and item.value->'destination'=jsonb_build_object('latitude',19,'longitude',72,'radiusMeters',150)
    and not (item.value ? 'url') and not (item.value ? 'secret')),
  'sender share list restores destination monitoring data without any share secret');
select extensions.is((public.opentrack_share_event('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','20202020-2020-4202-8202-202020202020',
  jsonb_build_object('clientEventId','30303030-3030-4303-8303-303030303030','eventType','arrival','observedAt',now(),
    'evidenceFixes',jsonb_build_array(jsonb_build_object('sampleId','40404040-4040-4404-8404-404040404040','capturedAt',now()-interval '30 seconds','latitude',19,'longitude',72,'accuracyMeters',10,'speedMps',0))))->'body'->>'status'),'pending',
  'arrival with a single canonical fix remains pending');
select extensions.is((public.opentrack_share_event('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','20202020-2020-4202-8202-202020202020',
  jsonb_build_object('clientEventId','30303030-3030-4303-8303-303030303030','eventType','arrival','observedAt',now(),
    'evidenceFixes',jsonb_build_array(jsonb_build_object('sampleId','40404040-4040-4404-8404-404040404040','capturedAt',now()-interval '30 seconds','latitude',19,'longitude',72,'accuracyMeters',10,'speedMps',0))))->'body'->>'status'),'duplicate',
  'replaying the same owner share and event type returns duplicate status');
select extensions.is((public.opentrack_share_event('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','20202020-2020-4202-8202-202020202020',
  jsonb_build_object('clientEventId','32323232-3232-4323-8323-323232323232','eventType','arrival','observedAt',now(),
    'evidenceFixes',jsonb_build_array(
      jsonb_build_object('sampleId','42424242-4242-4424-8424-424242424241','capturedAt',now()-interval '90 seconds','latitude',19.0001,'longitude',72,'accuracyMeters',12,'speedMps',0),
      jsonb_build_object('sampleId','42424242-4242-4424-8424-424242424242','capturedAt',now()-interval '30 seconds','latitude',19.0001,'longitude',72,'accuracyMeters',12,'speedMps',0))))->'body'->>'status'),'confirmed',
  'arrival is confirmed by two canonical fixes at least 60 seconds apart inside the destination radius');
insert into private.guest_shares(id,owner_id,token_hash,mode,sharing_epoch,started_at,destination_latitude,destination_longitude,destination_radius_meters)
values('21212121-2121-4212-8212-212121212121','22222222-2222-4222-8222-222222222222',repeat('7',64),'destination',3,now()-interval '1 hour',19,72,150);
select extensions.is((public.opentrack_share_event('22222222-2222-4222-8222-222222222222','dddddddd-2222-4222-8222-222222222222','21212121-2121-4212-8212-212121212121',
  jsonb_build_object('clientEventId','30303030-3030-4303-8303-303030303030','eventType','arrival','observedAt',now(),
    'evidenceFixes',jsonb_build_array(jsonb_build_object('sampleId','41414141-4141-4414-8414-414141414141','capturedAt',now()-interval '30 seconds','latitude',19,'longitude',72,'accuracyMeters',10,'speedMps',0))))->>'httpStatus')::integer,409,
  'another account cannot reuse a global share-event id to read duplicate metadata');
insert into private.guest_shares(id,owner_id,token_hash,mode,sharing_epoch,started_at,destination_latitude,destination_longitude,destination_radius_meters)
values('23232323-2323-4323-8323-232323232323','22222222-2222-4222-8222-222222222222',repeat('6',64),'destination',3,now()-interval '10 seconds',19,72,150);
select extensions.is((public.opentrack_share_event('22222222-2222-4222-8222-222222222222','dddddddd-2222-4222-8222-222222222222','23232323-2323-4323-8323-232323232323',
  jsonb_build_object('clientEventId','33333333-3333-4333-8333-333333333333','eventType','arrival','observedAt',now(),
    'evidenceFixes',jsonb_build_array(jsonb_build_object('sampleId','43434343-4343-4343-8343-434343434343','capturedAt',now()-interval '30 seconds','latitude',19,'longitude',72,'accuracyMeters',10,'speedMps',0))))->>'httpStatus')::integer,400,
  'destination confirmation rejects fixes captured before the share began');

insert into private.trips(id,user_id,circle_id,recipients,status,confirmed_drive,started_at,started_session_id,sharing_epoch)
values('60606060-6060-4606-8606-606060606060','11111111-1111-4111-8111-111111111111','44444444-4444-4444-8444-444444444444',array[]::uuid[],'active',true,now()-interval '10 minutes','aaaaaaaa-1111-4111-8111-111111111111',1);
insert into private.guest_shares(id,owner_id,token_hash,mode,sharing_epoch,started_at,trip_id)
values('50505050-5050-4505-8505-505050505050','11111111-1111-4111-8111-111111111111',repeat('d',64),'drive_end',1,now()-interval '20 minutes','60606060-6060-4606-8606-606060606060');
select extensions.is((public.opentrack_share_event('11111111-1111-4111-8111-111111111111','eeeeeeee-1111-4111-8111-111111111111','50505050-5050-4505-8505-505050505050',
  jsonb_build_object('clientEventId','71717171-7171-4717-8717-717171717172','eventType','drive_end','observedAt',now(),
    'evidenceFixes',jsonb_build_array(jsonb_build_object('sampleId','81818181-8181-4818-8818-818181818181','capturedAt',now()-interval '30 seconds','latitude',19,'longitude',72,'accuracyMeters',10,'speedMps',0))))->>'httpStatus')::integer,409,
  'drive-end event cannot use a different active member session from the confirmed trip');
select extensions.is((public.opentrack_share_event('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','50505050-5050-4505-8505-505050505050',
  jsonb_build_object('clientEventId','70707070-7070-4707-8707-707070707071','eventType','drive_end','observedAt',now(),
    'evidenceFixes',jsonb_build_array(jsonb_build_object('sampleId','80808080-8080-4808-8808-808080808080','capturedAt',now()-interval '30 seconds','latitude',19,'longitude',72,'accuracyMeters',10,'speedMps',0))))->'body'->>'status'),'pending',
  'drive-end with one stationary fix cannot satisfy the five-minute evidence window');

insert into private.guest_shares(id,owner_id,token_hash,mode,sharing_epoch,expires_at)
values('90909090-9090-4909-8909-909090909090','11111111-1111-4111-8111-111111111111',repeat('e',64),'time',1,now()+interval '1 hour');
select extensions.is((public.opentrack_share_revoke('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','90909090-9090-4909-8909-909090909090')->>'httpStatus')::integer,200,'owner can revoke an active guest share');
select extensions.ok((public.opentrack_share_read('90909090-9090-4909-8909-909090909090',repeat('e',64))->>'httpStatus')::integer=410
  and not ((public.opentrack_share_read('90909090-9090-4909-8909-909090909090',repeat('e',64))->'body') ? 'latest'),
  'a guest read serialized after revocation is terminal and contains no position');

select extensions.is((public.opentrack_admin_disable_account((select account_id from private.member_accounts where auth_user_id='22222222-2222-4222-8222-222222222222'),'11111111-1111-4111-8111-111111111111')->>'httpStatus')::integer,200,'account disable revokes active sessions');
select extensions.is((public.opentrack_validate_member_session('22222222-2222-4222-8222-222222222222','dddddddd-2222-4222-8222-222222222222')->>'allowed')::boolean,false,'disabled accounts cannot validate a session');

insert into private.trips(id,user_id,circle_id,recipients,status,confirmed_drive)
values('66666666-6666-4666-8666-666666666666','11111111-1111-4111-8111-111111111111','44444444-4444-4444-8444-444444444444',array[]::uuid[],'active',true);
select extensions.is((public.opentrack_stop_sharing('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','77777777-7777-4777-8777-777777777777')->>'httpStatus')::integer,200,'global stop-sharing succeeds');
select extensions.is(public.opentrack_stop_sharing('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','77777777-7777-4777-8777-777777777777')->'body',
  (select response from private.client_commands where user_id='11111111-1111-4111-8111-111111111111' and client_request_id='77777777-7777-4777-8777-777777777777' and command_type='stop_sharing'),
  'replayed stop-sharing returns the original response and epoch');
select extensions.is((select sharing_epoch from private.member_accounts where auth_user_id='11111111-1111-4111-8111-111111111111'),2::bigint,'stop-sharing advances the epoch exactly once');
select extensions.ok(not exists(select 1 from private.latest_locations where user_id='11111111-1111-4111-8111-111111111111'),'stop-sharing immediately clears the latest fix');
select extensions.is((public.opentrack_share_read('55555555-5555-4555-8555-555555555555',repeat('a',64))->>'httpStatus')::integer,410,'stopped guest share returns terminal status');
select extensions.ok(not ((public.opentrack_share_read('55555555-5555-4555-8555-555555555555',repeat('a',64))->'body') ? 'latest'),'terminal guest response contains no location');
select extensions.is((public.opentrack_share_read('55555555-5555-4555-8555-555555555555',repeat('b',64))->>'httpStatus')::integer,404,'invalid guest secret returns not found');
select extensions.is((public.opentrack_location_batch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',1,'[]'::jsonb)->>'httpStatus')::integer,409,'location batch with stale epoch is rejected atomically');
select extensions.is((public.opentrack_get_member_profile('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111')->'body'->>'sharingEpoch')::bigint,2::bigint,'a newly connected device reads the advanced sharing epoch');
select extensions.is((public.opentrack_location_batch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,
  jsonb_build_array(jsonb_build_object('sampleId','12121212-1212-4212-8212-121212121212','capturedAt',now(),'latitude',19,'longitude',72,'accuracyMeters',10,'speedMps',0)))->>'httpStatus')::integer,403,
  'current-epoch location upload after stop is blocked until an explicit sharing source is enabled');
select extensions.is((public.opentrack_share_create('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,'time',now()+interval '1 hour',null,repeat('f',64))->>'httpStatus')::integer,201,
  'creating a new link after stop is an explicit consent source');
select extensions.ok(jsonb_array_length(public.opentrack_location_batch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,
  jsonb_build_array(jsonb_build_object('sampleId','13131313-1313-4313-8313-131313131313','capturedAt',now(),'latitude',19,'longitude',72,'accuracyMeters',10,'speedMps',0)))->'body'->'accepted')=1,
  'a current-epoch fix is accepted after explicit guest-share consent');
update private.guest_shares set status='ended',ended_at=now(),end_reason='test_replace_source' where token_hash=repeat('f',64);
select extensions.is((public.opentrack_share_create('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,'drive_end',null,null,repeat('9',64),false)->>'httpStatus')::integer,409,
  'drive-end share creation without confirmation is rejected when no active confirmed driver trip exists');
select extensions.is((public.opentrack_share_create('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,'drive_end',null,null,repeat('9',64),true)->>'httpStatus')::integer,201,
  'explicit drive confirmation starts a guest-only drive-end share');
select extensions.ok((select gs.trip_id=t.id and t.circle_id is null and cardinality(t.recipients)=0
    and t.status='active' and t.confirmed_drive and t.drive_classification='driver'
    from private.guest_shares gs join private.trips t on t.id=gs.trip_id where gs.token_hash=repeat('9',64)),
  'guest-only drive share is attached to a private confirmed trip with no Circle recipients');
select extensions.ok(not exists(select 1 from private.circle_members where user_id='11111111-1111-4111-8111-111111111111' and membership_state='active' and sharing_enabled)
    and jsonb_array_length(public.opentrack_location_batch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,
      jsonb_build_array(jsonb_build_object('sampleId','14141414-1414-4414-8414-141414141410','capturedAt',now(),'latitude',19,'longitude',72,'accuracyMeters',10,'speedMps',0)))->'body'->'accepted')=1
    and not exists(select 1 from jsonb_array_elements(public.opentrack_api_dispatch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111','listTrips',
      jsonb_build_object('path',jsonb_build_object('circleId','44444444-4444-4444-8444-444444444444'),'query','{}'::jsonb),2)->'body'->'data') as item(value)
        where item.value->>'id'=(select trip_id::text from private.guest_shares where token_hash=repeat('9',64))),
  'standalone guest drive sharing accepts fixes without Circle sharing consent or Circle recipients');
select public.opentrack_share_create('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,'drive_end',null,null,repeat('6',64),false);
select extensions.ok(coalesce((select first_share.trip_id=second_share.trip_id from private.guest_shares first_share join private.guest_shares second_share
      on first_share.trip_id=second_share.trip_id where first_share.token_hash=repeat('9',64) and second_share.token_hash=repeat('6',64)),false),
  'additional drive-end shares reuse the same active confirmed trip');
update private.trips set started_at=now()-interval '16 minutes'
  where id=(select trip_id from private.guest_shares where token_hash=repeat('9',64));
update private.guest_shares set started_at=now()-interval '10 minutes 30 seconds' where token_hash in (repeat('9',64),repeat('6',64));
insert into private.location_samples(sample_id,user_id,session_id,sharing_epoch,captured_at,latitude,longitude,accuracy_meters,speed_mps) values
 ('15151515-1515-4515-8515-151515151499','11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,now()-interval '12 minutes',19,72,10,12),
 ('15151515-1515-4515-8515-151515151500','11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,now()-interval '11 minutes',19.002,72,10,12),
 ('15151515-1515-4515-8515-151515151501','11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,now()-interval '10 minutes',19.002,72,10,0),
 ('15151515-1515-4515-8515-151515151502','11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,now()-interval '9 minutes',19.002,72,10,0),
 ('15151515-1515-4515-8515-151515151503','11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,now()-interval '8 minutes',19.002,72,10,0),
 ('15151515-1515-4515-8515-151515151504','11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,now()-interval '7 minutes',19.002,72,10,0),
 ('15151515-1515-4515-8515-151515151505','11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,now()-interval '6 minutes',19.002,72,10,0),
 ('15151515-1515-4515-8515-151515151506','11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,now()-interval '5 minutes',19.002,72,10,0);
select extensions.is((public.opentrack_share_event('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',
  (select id from private.guest_shares where token_hash=repeat('9',64)),
  jsonb_build_object('clientEventId','16161616-1616-4616-8616-161616161616','eventType','drive_end','observedAt',now(),'evidenceFixes',jsonb_build_array(
    jsonb_build_object('sampleId','15151515-1515-4515-8515-151515151501','capturedAt',now()-interval '10 minutes','latitude',19.002,'longitude',72,'accuracyMeters',10,'speedMps',0),
    jsonb_build_object('sampleId','15151515-1515-4515-8515-151515151502','capturedAt',now()-interval '9 minutes','latitude',19.002,'longitude',72,'accuracyMeters',10,'speedMps',0),
    jsonb_build_object('sampleId','15151515-1515-4515-8515-151515151503','capturedAt',now()-interval '8 minutes','latitude',19.002,'longitude',72,'accuracyMeters',10,'speedMps',0),
    jsonb_build_object('sampleId','15151515-1515-4515-8515-151515151504','capturedAt',now()-interval '7 minutes','latitude',19.002,'longitude',72,'accuracyMeters',10,'speedMps',0),
    jsonb_build_object('sampleId','15151515-1515-4515-8515-151515151505','capturedAt',now()-interval '6 minutes','latitude',19.002,'longitude',72,'accuracyMeters',10,'speedMps',0),
    jsonb_build_object('sampleId','15151515-1515-4515-8515-151515151506','capturedAt',now()-interval '5 minutes','latitude',19.002,'longitude',72,'accuracyMeters',10,'speedMps',0)))))->'body'->>'status','pending',
  'five minutes of stationary fixes cannot end a drive that began while already parked');
select extensions.is((public.opentrack_share_event('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',
  (select id from private.guest_shares where token_hash=repeat('9',64)),
  jsonb_build_object('clientEventId','17171717-1717-4717-8717-171717171717','eventType','drive_end','observedAt',now(),'evidenceFixes',jsonb_build_array(
    jsonb_build_object('sampleId','15151515-1515-4515-8515-151515151499','capturedAt',now()-interval '12 minutes','latitude',19,'longitude',72,'accuracyMeters',10,'speedMps',12),
    jsonb_build_object('sampleId','15151515-1515-4515-8515-151515151500','capturedAt',now()-interval '11 minutes','latitude',19.002,'longitude',72,'accuracyMeters',10,'speedMps',12),
    jsonb_build_object('sampleId','15151515-1515-4515-8515-151515151501','capturedAt',now()-interval '10 minutes','latitude',19.002,'longitude',72,'accuracyMeters',10,'speedMps',0),
    jsonb_build_object('sampleId','15151515-1515-4515-8515-151515151502','capturedAt',now()-interval '9 minutes','latitude',19.002,'longitude',72,'accuracyMeters',10,'speedMps',0),
    jsonb_build_object('sampleId','15151515-1515-4515-8515-151515151503','capturedAt',now()-interval '8 minutes','latitude',19.002,'longitude',72,'accuracyMeters',10,'speedMps',0),
    jsonb_build_object('sampleId','15151515-1515-4515-8515-151515151504','capturedAt',now()-interval '7 minutes','latitude',19.002,'longitude',72,'accuracyMeters',10,'speedMps',0),
    jsonb_build_object('sampleId','15151515-1515-4515-8515-151515151505','capturedAt',now()-interval '6 minutes','latitude',19.002,'longitude',72,'accuracyMeters',10,'speedMps',0),
    jsonb_build_object('sampleId','15151515-1515-4515-8515-151515151506','capturedAt',now()-interval '5 minutes','latitude',19.002,'longitude',72,'accuracyMeters',10,'speedMps',0)))))->'body'->>'status','confirmed',
  'drive-end confirmation requires credible movement before the continuous stationary window');
select extensions.ok((select status='ended' and end_reason='drive_end_confirmed' from private.trips where id=(select trip_id from private.guest_shares where token_hash=repeat('9',64)))
    and (select count(*)=2 from private.guest_shares where trip_id=(select trip_id from private.guest_shares where token_hash=repeat('9',64)) and mode='drive_end' and status='ended'),
  'confirmed drive-end event ends its exact trip and every active share attached to it');
with later_upload as (select public.opentrack_location_batch('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,
  jsonb_build_array(jsonb_build_object('sampleId','18181818-1818-4818-8818-181818181818','capturedAt',now()+interval '1 minute','latitude',35,'longitude',139,'accuracyMeters',10,'speedMps',20))) result)
select extensions.ok((result->>'httpStatus')::integer=403
    and not exists(select 1 from private.location_samples where sample_id='18181818-1818-4818-8818-181818181818')
    and (public.opentrack_share_read((select id from private.guest_shares where token_hash=repeat('9',64)),repeat('9',64))->>'httpStatus')::integer=410
    and not ((public.opentrack_share_read((select id from private.guest_shares where token_hash=repeat('9',64)),repeat('9',64))->'body') ? 'latest'),
  'a later moved upload after offline drive-end confirmation is rejected and never visible through the old share') from later_upload;
insert into private.trips(id,user_id,circle_id,recipients,status,confirmed_drive,drive_classification,started_at,started_session_id,sharing_epoch)
values('71717171-7171-4717-8717-717171717171','11111111-1111-4111-8111-111111111111','44444444-4444-4444-8444-444444444444','{}','active',true,'driver',now()-interval '5 minutes','aaaaaaaa-1111-4111-8111-111111111111',2);
select public.opentrack_share_create('11111111-1111-4111-8111-111111111111','aaaaaaaa-1111-4111-8111-111111111111',2,'drive_end',null,null,repeat('4',64),false);
select extensions.ok(exists(select 1 from private.guest_shares where token_hash=repeat('4',64) and trip_id='71717171-7171-4717-8717-717171717171')
    and (select count(*)=1 from private.trips where user_id='11111111-1111-4111-8111-111111111111' and circle_id='44444444-4444-4444-8444-444444444444' and status='active'),
  'drive-end share reuses an active confirmed Circle trip without creating a guest-only trip');

select * from extensions.finish();
rollback;
