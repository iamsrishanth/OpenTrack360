import assert from "node:assert/strict";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const exampleRoot = resolve(repositoryRoot, "contracts", "examples");
const lineEnding = process.platform === "win32" ? "\r\n" : "\n";
const remoteMode = process.argv.includes("--remote");
const outputPath = remoteMode
  ? resolve(tmpdir(), "opentrack360-drive-end-contracts.sql")
  : resolve(repositoryRoot, "supabase", "tests", "drive_end_contract_examples.sql");

function readJson(filename) {
  return JSON.parse(readFileSync(resolve(exampleRoot, filename), "utf8"));
}

function elapsedSeconds(later, earlier) {
  return (Date.parse(later) - Date.parse(earlier)) / 1000;
}

function distanceMeters(left, right) {
  const toRadians = (degrees) => degrees * Math.PI / 180;
  const latitudeDelta = toRadians(right.latitude - left.latitude);
  const longitudeDelta = toRadians(right.longitude - left.longitude);
  const leftLatitude = toRadians(left.latitude);
  const rightLatitude = toRadians(right.latitude);
  const haversine = Math.sin(latitudeDelta / 2) ** 2 +
    Math.cos(leftLatitude) * Math.cos(rightLatitude) * Math.sin(longitudeDelta / 2) ** 2;
  return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, haversine)));
}

function validateFixture(fixture) {
  const { request, response, expectedTransitionSeconds } = fixture;
  assert.equal(request.eventType, "drive_end");
  assert.equal(response.clientEventId, request.clientEventId);
  assert.ok(Array.isArray(request.evidenceFixes));

  const fixes = [...request.evidenceFixes].sort((left, right) => Date.parse(left.capturedAt) - Date.parse(right.capturedAt));
  const moving = fixes.filter((fix) => fix.speedMps !== null && fix.speedMps >= 5 && fix.speedMps <= 55 && fix.accuracyMeters <= 50);
  const stationary = fixes.filter((fix) => fix.speedMps !== null && fix.speedMps <= 1.5 && fix.accuracyMeters <= 50);
  assert.ok(moving.length >= 2, fixture.name + " needs at least two credible moving fixes");
  assert.ok(stationary.length >= 2, fixture.name + " needs at least two credible stationary fixes");

  const movementSpan = elapsedSeconds(moving.at(-1).capturedAt, moving[0].capturedAt);
  const movementDistance = distanceMeters(moving[0], moving.at(-1));
  const stationarySpan = elapsedSeconds(stationary.at(-1).capturedAt, stationary[0].capturedAt);
  const transition = elapsedSeconds(stationary[0].capturedAt, moving.at(-1).capturedAt);
  const stationaryGaps = stationary.slice(1).map((fix, index) => elapsedSeconds(fix.capturedAt, stationary[index].capturedAt));
  const maxStationaryDistance = Math.max(...stationary.map((fix) => distanceMeters(stationary[0], fix)));
  const firstStationaryAt = Date.parse(stationary[0].capturedAt);
  const lastStationaryAt = Date.parse(stationary.at(-1).capturedAt);
  const hasBadFixInsideStop = fixes.some((fix) => {
    const capturedAt = Date.parse(fix.capturedAt);
    return capturedAt >= firstStationaryAt && capturedAt <= lastStationaryAt &&
      (fix.speedMps === null || fix.speedMps > 1.5 || fix.accuracyMeters > 50);
  });

  assert.ok(movementSpan >= 30, fixture.name + " movement span must be at least 30 seconds");
  assert.ok(movementDistance >= 150, fixture.name + " must demonstrate at least 150 metres of movement");
  assert.ok(movementDistance <= 55 * movementSpan + 100, fixture.name + " movement must stay within the credible speed bound");
  assert.ok(stationarySpan >= 300, fixture.name + " must provide five minutes of stationary evidence");
  assert.ok(stationaryGaps.every((gap) => gap <= 90), fixture.name + " stationary fixes must have no gap over 90 seconds");
  assert.ok(maxStationaryDistance <= 75, fixture.name + " stationary fixes must remain within 75 metres");
  assert.equal(hasBadFixInsideStop, false, fixture.name + " must not contain a bad fix during the stop window");
  assert.equal(transition, expectedTransitionSeconds);

  if (response.status === "confirmed") {
    assert.ok(transition <= 90, fixture.name + " may confirm only when movement-to-stop continuity is at most 90 seconds");
    assert.equal(response.reason, "drive_end_confirmed");
    assert.ok(response.endedAt);
  } else {
    assert.ok(transition > 90, fixture.name + " negative case must fail only the 90-second continuity rule");
    assert.equal(response.status, "pending");
    assert.equal(response.reason, "insufficient_evidence");
    assert.equal(response.endedAt, null);
  }
}

function sqlLiteral(value) {
  return "'" + String(value).replaceAll("'", "''") + "'";
}

function sqlJson(value, index) {
  const delimiter = "$fixture" + index + "$";
  assert.ok(!JSON.stringify(value).includes(delimiter));
  return delimiter + JSON.stringify(value) + delimiter + "::jsonb";
}

const fixtures = [
  {
    name: "nine_minute_gap",
    title: "nine-minute movement-to-stop gap",
    request: readJson("share-event-drive-end.request.json"),
    response: readJson("share-event-drive-end.pending.response.json"),
    expectedTransitionSeconds: 540,
    tokenHash: "d".repeat(64),
  },
  {
    name: "valid_five_minute_stop",
    title: "valid five-minute stationary window",
    request: readJson("share-event-drive-end.confirmed.request.json"),
    response: readJson("share-event-drive-end.confirmed.response.json"),
    expectedTransitionSeconds: 60,
    tokenHash: "e".repeat(64),
  },
];

for (const fixture of fixtures) validateFixture(fixture);

const values = fixtures.map((fixture, index) => "  (" + [
  sqlLiteral(fixture.name),
  sqlLiteral(fixture.tokenHash),
  sqlLiteral(fixture.response.status),
  sqlLiteral(fixture.response.reason),
  sqlJson(fixture.request, index),
].join(", ") + ")").join(",\n");

const setupSql = [
  "-- Generated from the public contract request/response examples by scripts/generate-drive-end-pgtap.mjs.",
  "-- All fixture rows and function side effects are rolled back at the end of this test.",
  "begin;",
  "create temporary table drive_end_fixture_cases (",
  "  case_name text primary key,",
  "  owner_id uuid not null default gen_random_uuid(),",
  "  session_id uuid not null default gen_random_uuid(),",
  "  trip_id uuid not null default gen_random_uuid(),",
  "  share_id uuid not null default gen_random_uuid(),",
  "  token_hash text not null unique,",
  "  expected_status text not null,",
  "  expected_reason text not null,",
  "  trip_started_at timestamptz,",
  "  share_started_at timestamptz,",
  "  request_payload jsonb not null",
  ");",
  "insert into drive_end_fixture_cases(case_name,token_hash,expected_status,expected_reason,request_payload) values",
  values + ";",
  "",
  "-- Rebase fixed example times onto now() and allocate independent sample/event IDs per run.",
  "update drive_end_fixture_cases c",
  "set request_payload = jsonb_build_object(",
  "  'clientEventId',gen_random_uuid()::text,",
  "  'eventType',c.request_payload->>'eventType',",
  "  'observedAt',now(),",
  "  'evidenceFixes',(select jsonb_agg(jsonb_build_object(",
  "    'sampleId',gen_random_uuid()::text,",
  "    'capturedAt',now()-((c.request_payload->>'observedAt')::timestamptz-(fix->>'capturedAt')::timestamptz),",
  "    'latitude',(fix->>'latitude')::double precision,",
  "    'longitude',(fix->>'longitude')::double precision,",
  "    'accuracyMeters',(fix->>'accuracyMeters')::double precision,",
  "    'speedMps',case when fix->'speedMps'='null'::jsonb then null else (fix->>'speedMps')::double precision end",
  "  ) order by ordinality) from jsonb_array_elements(c.request_payload->'evidenceFixes') with ordinality as e(fix,ordinality))",
  ");",
  "update drive_end_fixture_cases c",
  "set share_started_at=(select min((fix->>'capturedAt')::timestamptz)",
  "      from jsonb_array_elements(c.request_payload->'evidenceFixes') as e(fix)",
  "      where (fix->>'speedMps')::double precision<=1.5),",
  "    trip_started_at=(select min((fix->>'capturedAt')::timestamptz)-interval '1 minute'",
  "      from jsonb_array_elements(c.request_payload->'evidenceFixes') as e(fix)",
  "      where (fix->>'speedMps')::double precision between 5 and 55);",
  "",
  "insert into auth.users(id,email,encrypted_password,raw_app_meta_data,raw_user_meta_data,created_at,updated_at)",
  "select owner_id,'drive-fixture-'||replace(owner_id::text,'-','')||'@opentrack360.invalid','', '{}'::jsonb,'{}'::jsonb,now(),now()",
  "from drive_end_fixture_cases;",
  "insert into private.member_accounts(account_id,auth_user_id,username_normalized,display_name,auth_email,status,is_platform_admin,must_rotate_password,session_epoch,sharing_epoch)",
  "select gen_random_uuid(),owner_id,'fx'||substr(replace(owner_id::text,'-',''),1,28),'Drive fixture '||case_name,",
  "  'drive-fixture-'||replace(owner_id::text,'-','')||'@opentrack360.invalid','active',false,false,1,1",
  "from drive_end_fixture_cases;",
  "insert into private.member_sessions(session_id,user_id,session_epoch)",
  "select session_id,owner_id,1 from drive_end_fixture_cases;",
  "insert into private.trips(id,user_id,circle_id,recipients,status,confirmed_drive,drive_classification,started_at,started_session_id,sharing_epoch)",
  "select trip_id,owner_id,null,'{}'::uuid[],'active',true,'driver',trip_started_at,session_id,1",
  "from drive_end_fixture_cases;",
  "insert into private.guest_shares(id,owner_id,token_hash,mode,status,sharing_epoch,started_at,trip_id)",
  "select share_id,owner_id,token_hash,'drive_end','active',1,share_started_at,trip_id",
  "from drive_end_fixture_cases;",
  "create temporary table drive_end_fixture_results as",
  "select c.case_name,c.expected_status,c.expected_reason,c.owner_id,c.session_id,c.trip_id,c.share_id,c.token_hash,",
  "  public.opentrack_share_event(c.owner_id,c.session_id,c.share_id,c.request_payload) as result",
  "from drive_end_fixture_cases c;",
  "create temporary table drive_end_fixture_guest_reads as",
  "select c.case_name,public.opentrack_share_read(c.share_id,c.token_hash) as result",
  "from drive_end_fixture_cases c;",
  "",
].join(lineEnding) + lineEnding;

const pgTapSql = [
  "select extensions.plan(12);",
  "select extensions.is((select (result->>'httpStatus')::integer from drive_end_fixture_results where case_name='nine_minute_gap'),200,'nine-minute gap request reaches the actual drive-end validator');",
  "select extensions.is((select (result->>'httpStatus')::integer from drive_end_fixture_results where case_name='valid_five_minute_stop'),200,'valid five-minute stop request reaches the actual drive-end validator');",
  "select extensions.is((select result->'body'->>'status' from drive_end_fixture_results where case_name='nine_minute_gap'),'pending','nine-minute transition remains pending');",
  "select extensions.is((select result->'body'->>'reason' from drive_end_fixture_results where case_name='nine_minute_gap'),'insufficient_evidence','nine-minute transition has insufficient evidence');",
  "select extensions.is((select result->'body'->>'status' from drive_end_fixture_results where case_name='valid_five_minute_stop'),'confirmed','60-second transition and five-minute stop confirms');",
  "select extensions.is((select result->'body'->>'reason' from drive_end_fixture_results where case_name='valid_five_minute_stop'),'drive_end_confirmed','valid stop returns the drive-end reason');",
  "select extensions.ok((select (result->>'httpStatus')::integer=200 and result->'body'->>'status'='waiting' and result->'body'->'latest'='null'::jsonb",
  "  from drive_end_fixture_guest_reads where case_name='nine_minute_gap'),'guest read remains available after the insufficient-evidence case');",
  "select extensions.ok((select (result->>'httpStatus')::integer=410 and not (result->'body' ? 'latest') and not (result->'body' ? 'latitude') and not (result->'body' ? 'longitude') and not (result->'body' ? 'speedMps')",
  "  from drive_end_fixture_guest_reads where case_name='valid_five_minute_stop'),'confirmed event ends guest access without returning location or speed');",
  "select extensions.is((select status from private.trips where id=(select trip_id from drive_end_fixture_results where case_name='nine_minute_gap')),'active','insufficient evidence does not end the drive');",
  "select extensions.is((select status from private.trips where id=(select trip_id from drive_end_fixture_results where case_name='valid_five_minute_stop')),'ended','confirmed drive-end ends its linked trip');",
  "select extensions.is((select status from private.guest_shares where id=(select share_id from drive_end_fixture_results where case_name='nine_minute_gap')),'active','insufficient evidence keeps the share active');",
  "select extensions.is((select status from private.guest_shares where id=(select share_id from drive_end_fixture_results where case_name='valid_five_minute_stop')),'ended','confirmed drive-end ends the share');",
  "select * from extensions.finish();",
].join(lineEnding) + lineEnding;

const remoteSql = [
  "do $drive_end_assertions$",
  "begin",
  "  if coalesce((select (result->>'httpStatus')::integer from drive_end_fixture_results where case_name='nine_minute_gap'),-1)<>200 then raise exception 'nine-minute gap request did not reach validator'; end if;",
  "  if coalesce((select (result->>'httpStatus')::integer from drive_end_fixture_results where case_name='valid_five_minute_stop'),-1)<>200 then raise exception 'valid stop request did not reach validator'; end if;",
  "  if coalesce((select result->'body'->>'status' from drive_end_fixture_results where case_name='nine_minute_gap'),'')<>'pending' then raise exception 'nine-minute gap was not pending'; end if;",
  "  if coalesce((select result->'body'->>'reason' from drive_end_fixture_results where case_name='nine_minute_gap'),'')<>'insufficient_evidence' then raise exception 'nine-minute gap reason was not insufficient_evidence'; end if;",
  "  if coalesce((select result->'body'->>'status' from drive_end_fixture_results where case_name='valid_five_minute_stop'),'')<>'confirmed' then raise exception 'valid five-minute stop was not confirmed'; end if;",
  "  if coalesce((select result->'body'->>'reason' from drive_end_fixture_results where case_name='valid_five_minute_stop'),'')<>'drive_end_confirmed' then raise exception 'valid stop reason was not drive_end_confirmed'; end if;",
  "  if not coalesce((select (result->>'httpStatus')::integer=200 and result->'body'->>'status'='waiting' and result->'body'->'latest'='null'::jsonb from drive_end_fixture_guest_reads where case_name='nine_minute_gap'),false) then raise exception 'guest read after insufficient evidence was not active and empty'; end if;",
  "  if not coalesce((select (result->>'httpStatus')::integer=410 and not (result->'body' ? 'latest') and not (result->'body' ? 'latitude') and not (result->'body' ? 'longitude') and not (result->'body' ? 'speedMps') from drive_end_fixture_guest_reads where case_name='valid_five_minute_stop'),false) then raise exception 'confirmed guest read did not return a terminal response without location'; end if;",
  "  if coalesce((select status from private.trips where id=(select trip_id from drive_end_fixture_results where case_name='nine_minute_gap')),'')<>'active' then raise exception 'insufficient evidence ended the drive'; end if;",
  "  if coalesce((select status from private.trips where id=(select trip_id from drive_end_fixture_results where case_name='valid_five_minute_stop')),'')<>'ended' then raise exception 'confirmed evidence did not end the drive'; end if;",
  "  if coalesce((select status from private.guest_shares where id=(select share_id from drive_end_fixture_results where case_name='nine_minute_gap')),'')<>'active' then raise exception 'insufficient evidence ended the share'; end if;",
  "  if coalesce((select status from private.guest_shares where id=(select share_id from drive_end_fixture_results where case_name='valid_five_minute_stop')),'')<>'ended' then raise exception 'confirmed evidence did not end the share'; end if;",
  "end;",
  "$drive_end_assertions$;",
  "select jsonb_build_object(",
  "  'negativeStatus',(select result->'body'->>'status' from drive_end_fixture_results where case_name='nine_minute_gap'),",
  "  'negativeGuestHttpStatus',(select (result->>'httpStatus')::integer from drive_end_fixture_guest_reads where case_name='nine_minute_gap'),",
  "  'confirmedStatus',(select result->'body'->>'status' from drive_end_fixture_results where case_name='valid_five_minute_stop'),",
  "  'confirmedGuestHttpStatus',(select (result->>'httpStatus')::integer from drive_end_fixture_guest_reads where case_name='valid_five_minute_stop')",
  ") as validator_results;",
].join(lineEnding) + lineEnding;

const sql = setupSql + (remoteMode ? remoteSql : pgTapSql) + "rollback;\n";

writeFileSync(outputPath, sql, "utf8");
console.log(remoteMode
  ? "Generated rollback-only remote drive-end validator SQL."
  : "Generated drive-end pgTAP cases from two contract fixtures.");
