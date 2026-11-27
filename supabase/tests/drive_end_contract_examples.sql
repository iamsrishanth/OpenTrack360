-- Generated from the public contract request/response examples by scripts/generate-drive-end-pgtap.mjs.
-- All fixture rows and function side effects are rolled back at the end of this test.
begin;
create temporary table drive_end_fixture_cases (
  case_name text primary key,
  owner_id uuid not null default gen_random_uuid(),
  session_id uuid not null default gen_random_uuid(),
  trip_id uuid not null default gen_random_uuid(),
  share_id uuid not null default gen_random_uuid(),
  token_hash text not null unique,
  expected_status text not null,
  expected_reason text not null,
  trip_started_at timestamptz,
  share_started_at timestamptz,
  request_payload jsonb not null
);
insert into drive_end_fixture_cases(case_name,token_hash,expected_status,expected_reason,request_payload) values
  ('nine_minute_gap', 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', 'pending', 'insufficient_evidence', $fixture0${"clientEventId":"d0d0d0d0-1111-4111-8111-d0d0d0d0d0d0","eventType":"drive_end","observedAt":"2026-11-26T10:05:10Z","evidenceFixes":[{"sampleId":"d1d1d1d1-1111-4111-8111-d1d1d1d1d1d1","capturedAt":"2026-11-26T09:50:00Z","latitude":19.076,"longitude":72.877,"accuracyMeters":12,"speedMps":9.2},{"sampleId":"d2d2d2d2-1111-4111-8111-d2d2d2d2d2d2","capturedAt":"2026-11-26T09:51:00Z","latitude":19.081,"longitude":72.877,"accuracyMeters":10,"speedMps":9.2},{"sampleId":"d3d3d3d3-1111-4111-8111-d3d3d3d3d3d3","capturedAt":"2026-11-26T10:00:00Z","latitude":19.081,"longitude":72.877,"accuracyMeters":10,"speedMps":0},{"sampleId":"d4d4d4d4-1111-4111-8111-d4d4d4d4d4d4","capturedAt":"2026-11-26T10:01:00Z","latitude":19.081,"longitude":72.877,"accuracyMeters":10,"speedMps":0},{"sampleId":"d5d5d5d5-1111-4111-8111-d5d5d5d5d5d5","capturedAt":"2026-11-26T10:02:00Z","latitude":19.081,"longitude":72.877,"accuracyMeters":10,"speedMps":0},{"sampleId":"d6d6d6d6-1111-4111-8111-d6d6d6d6d6d6","capturedAt":"2026-11-26T10:03:00Z","latitude":19.081,"longitude":72.877,"accuracyMeters":10,"speedMps":0},{"sampleId":"d7d7d7d7-1111-4111-8111-d7d7d7d7d7d7","capturedAt":"2026-11-26T10:04:00Z","latitude":19.081,"longitude":72.877,"accuracyMeters":10,"speedMps":0},{"sampleId":"d8d8d8d8-1111-4111-8111-d8d8d8d8d8d8","capturedAt":"2026-11-26T10:05:00Z","latitude":19.081,"longitude":72.877,"accuracyMeters":10,"speedMps":0}]}$fixture0$::jsonb),
  ('valid_five_minute_stop', 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee', 'confirmed', 'drive_end_confirmed', $fixture1${"clientEventId":"e0e0e0e0-2222-4222-8222-e0e0e0e0e0e0","eventType":"drive_end","observedAt":"2026-11-26T10:05:10Z","evidenceFixes":[{"sampleId":"e1e1e1e1-2222-4222-8222-e1e1e1e1e1e1","capturedAt":"2026-11-26T09:58:00Z","latitude":19.076,"longitude":72.877,"accuracyMeters":12,"speedMps":9.2},{"sampleId":"e2e2e2e2-2222-4222-8222-e2e2e2e2e2e2","capturedAt":"2026-11-26T09:59:00Z","latitude":19.081,"longitude":72.877,"accuracyMeters":10,"speedMps":9.2},{"sampleId":"e3e3e3e3-2222-4222-8222-e3e3e3e3e3e3","capturedAt":"2026-11-26T10:00:00Z","latitude":19.081,"longitude":72.877,"accuracyMeters":10,"speedMps":0},{"sampleId":"e4e4e4e4-2222-4222-8222-e4e4e4e4e4e4","capturedAt":"2026-11-26T10:01:00Z","latitude":19.081,"longitude":72.877,"accuracyMeters":10,"speedMps":0},{"sampleId":"e5e5e5e5-2222-4222-8222-e5e5e5e5e5e5","capturedAt":"2026-11-26T10:02:00Z","latitude":19.081,"longitude":72.877,"accuracyMeters":10,"speedMps":0},{"sampleId":"e6e6e6e6-2222-4222-8222-e6e6e6e6e6e6","capturedAt":"2026-11-26T10:03:00Z","latitude":19.081,"longitude":72.877,"accuracyMeters":10,"speedMps":0},{"sampleId":"e7e7e7e7-2222-4222-8222-e7e7e7e7e7e7","capturedAt":"2026-11-26T10:04:00Z","latitude":19.081,"longitude":72.877,"accuracyMeters":10,"speedMps":0},{"sampleId":"e8e8e8e8-2222-4222-8222-e8e8e8e8e8e8","capturedAt":"2026-11-26T10:05:00Z","latitude":19.081,"longitude":72.877,"accuracyMeters":10,"speedMps":0}]}$fixture1$::jsonb);

-- Rebase fixed example times onto now() and allocate independent sample/event IDs per run.
update drive_end_fixture_cases c
set request_payload = jsonb_build_object(
  'clientEventId',gen_random_uuid()::text,
  'eventType',c.request_payload->>'eventType',
  'observedAt',now(),
  'evidenceFixes',(select jsonb_agg(jsonb_build_object(
    'sampleId',gen_random_uuid()::text,
    'capturedAt',now()-((c.request_payload->>'observedAt')::timestamptz-(fix->>'capturedAt')::timestamptz),
    'latitude',(fix->>'latitude')::double precision,
    'longitude',(fix->>'longitude')::double precision,
    'accuracyMeters',(fix->>'accuracyMeters')::double precision,
    'speedMps',case when fix->'speedMps'='null'::jsonb then null else (fix->>'speedMps')::double precision end
  ) order by ordinality) from jsonb_array_elements(c.request_payload->'evidenceFixes') with ordinality as e(fix,ordinality))
);
update drive_end_fixture_cases c
set share_started_at=(select min((fix->>'capturedAt')::timestamptz)
      from jsonb_array_elements(c.request_payload->'evidenceFixes') as e(fix)
      where (fix->>'speedMps')::double precision<=1.5),
    trip_started_at=(select min((fix->>'capturedAt')::timestamptz)-interval '1 minute'
      from jsonb_array_elements(c.request_payload->'evidenceFixes') as e(fix)
      where (fix->>'speedMps')::double precision between 5 and 55);

insert into auth.users(id,email,encrypted_password,raw_app_meta_data,raw_user_meta_data,created_at,updated_at)
select owner_id,'drive-fixture-'||replace(owner_id::text,'-','')||'@opentrack360.invalid','', '{}'::jsonb,'{}'::jsonb,now(),now()
from drive_end_fixture_cases;
insert into private.member_accounts(account_id,auth_user_id,username_normalized,display_name,auth_email,status,is_platform_admin,must_rotate_password,session_epoch,sharing_epoch)
select gen_random_uuid(),owner_id,'fx'||substr(replace(owner_id::text,'-',''),1,28),'Drive fixture '||case_name,
  'drive-fixture-'||replace(owner_id::text,'-','')||'@opentrack360.invalid','active',false,false,1,1
from drive_end_fixture_cases;
insert into private.member_sessions(session_id,user_id,session_epoch)
select session_id,owner_id,1 from drive_end_fixture_cases;
insert into private.trips(id,user_id,circle_id,recipients,status,confirmed_drive,drive_classification,started_at,started_session_id,sharing_epoch)
select trip_id,owner_id,null,'{}'::uuid[],'active',true,'driver',trip_started_at,session_id,1
from drive_end_fixture_cases;
insert into private.guest_shares(id,owner_id,token_hash,mode,status,sharing_epoch,started_at,trip_id)
select share_id,owner_id,token_hash,'drive_end','active',1,share_started_at,trip_id
from drive_end_fixture_cases;
create temporary table drive_end_fixture_results as
select c.case_name,c.expected_status,c.expected_reason,c.owner_id,c.session_id,c.trip_id,c.share_id,c.token_hash,
  public.opentrack_share_event(c.owner_id,c.session_id,c.share_id,c.request_payload) as result
from drive_end_fixture_cases c;
create temporary table drive_end_fixture_guest_reads as
select c.case_name,public.opentrack_share_read(c.share_id,c.token_hash) as result
from drive_end_fixture_cases c;

select extensions.plan(12);
select extensions.is((select (result->>'httpStatus')::integer from drive_end_fixture_results where case_name='nine_minute_gap'),200,'nine-minute gap request reaches the actual drive-end validator');
select extensions.is((select (result->>'httpStatus')::integer from drive_end_fixture_results where case_name='valid_five_minute_stop'),200,'valid five-minute stop request reaches the actual drive-end validator');
select extensions.is((select result->'body'->>'status' from drive_end_fixture_results where case_name='nine_minute_gap'),'pending','nine-minute transition remains pending');
select extensions.is((select result->'body'->>'reason' from drive_end_fixture_results where case_name='nine_minute_gap'),'insufficient_evidence','nine-minute transition has insufficient evidence');
select extensions.is((select result->'body'->>'status' from drive_end_fixture_results where case_name='valid_five_minute_stop'),'confirmed','60-second transition and five-minute stop confirms');
select extensions.is((select result->'body'->>'reason' from drive_end_fixture_results where case_name='valid_five_minute_stop'),'drive_end_confirmed','valid stop returns the drive-end reason');
select extensions.ok((select (result->>'httpStatus')::integer=200 and result->'body'->>'status'='waiting' and result->'body'->'latest'='null'::jsonb
  from drive_end_fixture_guest_reads where case_name='nine_minute_gap'),'guest read remains available after the insufficient-evidence case');
select extensions.ok((select (result->>'httpStatus')::integer=410 and not (result->'body' ? 'latest') and not (result->'body' ? 'latitude') and not (result->'body' ? 'longitude') and not (result->'body' ? 'speedMps')
  from drive_end_fixture_guest_reads where case_name='valid_five_minute_stop'),'confirmed event ends guest access without returning location or speed');
select extensions.is((select status from private.trips where id=(select trip_id from drive_end_fixture_results where case_name='nine_minute_gap')),'active','insufficient evidence does not end the drive');
select extensions.is((select status from private.trips where id=(select trip_id from drive_end_fixture_results where case_name='valid_five_minute_stop')),'ended','confirmed drive-end ends its linked trip');
select extensions.is((select status from private.guest_shares where id=(select share_id from drive_end_fixture_results where case_name='nine_minute_gap')),'active','insufficient evidence keeps the share active');
select extensions.is((select status from private.guest_shares where id=(select share_id from drive_end_fixture_results where case_name='valid_five_minute_stop')),'ended','confirmed drive-end ends the share');
select * from extensions.finish();
rollback;
