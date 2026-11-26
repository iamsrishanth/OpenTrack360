import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { createApiDispatcher } from "../../src/server/api/dispatcher";
import { createDefaultApiServices } from "../../src/server/api/rpc";
import type { ApiServices, MemberContext, RpcEnvelope } from "../../src/server/api/types";

const member: MemberContext = {
  userId: "11111111-1111-4111-8111-111111111111",
  username: "aarav",
  sessionId: "22222222-2222-4222-8222-222222222222",
  mustRotatePassword: false,
  sharingEpoch: 12,
};

const sample = {
  sampleId: "10c0f632-7ad5-4d4e-8ae4-f72e98e9678c",
  capturedAt: "2026-11-26T10:00:00Z",
  latitude: 19.076,
  longitude: 72.8777,
  accuracyMeters: 12.5,
  speedMps: 0,
};

function ok(body: unknown, httpStatus = 200): RpcEnvelope {
  return { httpStatus, body };
}

function services(overrides: Partial<ApiServices> = {}): ApiServices {
  return {
    requireMember: async () => member,
    dispatchMember: async () => ok({ ok: true }),
    uploadLocationBatch: async () => ok({ serverTime: "2026-11-26T10:00:11Z", accepted: [sample.sampleId], duplicates: [], rejected: [] }),
    createShare: async (_member, _input, _secret, _hash) => ok({ id: "f28bd481-5c3c-4ea1-9fa1-0384703c23b0", mode: "drive_end", status: "active", startedAt: "2026-11-26T10:00:00Z", createdAt: "2026-11-26T10:00:00Z", expiresAt: null, endCondition: "drive end" }, 201),
    readShare: async () => ok({ status: "active", serverTime: "2026-11-26T10:00:10Z", viewValidUntil: "2026-11-26T10:00:40Z", latest: { latitude: 19.076, longitude: 72.8777, accuracyMeters: 12.5, capturedAt: "2026-11-26T10:00:08Z", speedMps: 0 } }),
    submitShareEvent: async () => ok({ clientEventId: "33333333-3333-4333-8333-333333333333", status: "pending", serverTime: "2026-11-26T10:00:10Z", endedAt: null, reason: "insufficient_evidence" }),
    readPetRecovery: async () => ok({ petName: "Milo", species: "dog", ownerMessage: "Please call the owner", contactRoute: null }),
    submitPetSighting: async () => ok({ ok: true }, 202),
    createPetRecoveryLink: async () => ok({ id: "55555555-5555-4555-8555-555555555555", createdAt: "2026-11-26T10:00:00Z", expiresAt: "2026-12-03T10:00:00Z" }, 201),
    createPetPhotoUpload: async () => ok({ uploadUrl: "https://storage.invalid/signed", objectPath: "pet/id/photo.jpg", expiresAt: "2026-11-26T10:05:00Z" }, 201),
    ...overrides,
  };
}

function request(path: string, method = "GET", body?: unknown, headers: Record<string, string> = {}): Request {
  return new Request(`https://track.srishanth.me/api/v1${path}`, {
    method,
    headers: { ...(body === undefined ? {} : { "content-type": "application/json" }), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function json(response: Response): Promise<any> {
  return await response.json();
}

test("Circle map requests forward the Circle ID for database membership and precision checks", async () => {
  const calls: Array<{ operation: string; args: Record<string, any> }> = [];
  const dispatch = createApiDispatcher(services({
    dispatchMember: async (_member, operation, args) => {
      calls.push({ operation, args });
      return ok({ data: [], serverTime: "2026-11-26T10:00:00Z" });
    },
  }));
  const response = await dispatch(request("/circles/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/map", "GET", undefined, { authorization: "Bearer session" }));
  assert.equal(response.status, 200);
  assert.equal(calls[0]?.operation, "getCircleMap");
  assert.deepEqual(calls[0]?.args.path, { circleId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" });
  assert.equal(response.headers.get("cache-control"), "private, no-store");
});

test("Circle sharing accepts only the per-Circle switch and returns the RPC confirmation unchanged", async () => {
  let calls = 0;
  const expected = { circleId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", sharingEnabled: false, updatedAt: "2026-11-26T10:00:00Z" };
  const dispatch = createApiDispatcher(services({
    dispatchMember: async (_member, operation, args) => {
      calls++;
      assert.equal(operation, "setCircleSharing");
      assert.deepEqual(args.path, { circleId: expected.circleId });
      assert.deepEqual(args.body, { sharingEnabled: false });
      return ok(expected);
    },
  }));
  const bad = await dispatch(request(`/circles/${expected.circleId}/sharing`, "PUT", { sharingEnabled: false, sharingEpoch: 12 }, { authorization: "Bearer session" }));
  assert.equal(bad.status, 400);
  assert.equal(calls, 0);
  const response = await dispatch(request(`/circles/${expected.circleId}/sharing`, "PUT", { sharingEnabled: false }, { authorization: "Bearer session" }));
  assert.equal(response.status, 200);
  assert.deepEqual(await json(response), expected);
});

test("coordination, Bubble, safety, pet routine, and terminal routes dispatch typed contract payloads", async () => {
  const circleId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const eventId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  const bubbleId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
  const petId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
  const routineId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
  const reportId = "ffffffff-ffff-4fff-8fff-ffffffffffff";
  const tripId = "99999999-9999-4999-8999-999999999999";
  const sosId = "88888888-8888-4888-8888-888888888888";
  const clientId = "77777777-7777-4777-8777-777777777777";
  const createdAt = "2026-11-26T10:00:00Z";
  const inviteExpiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
  const oneTimeInviteToken = "invite-secret-test-token-value-123456";
  const inviteCreatedAt = "2026-11-26T10:00:00Z";
  const tripStopResult = { id: tripId, status: "ended", startedAt: createdAt, endedAt: "2026-11-26T10:30:00Z", endReason: "arrived" };
  const bubbleEndResult = { ended: true, endedAt: "2026-11-26T10:05:00Z" };
  const cases: Array<{ path: string; method: string; operation: string; body?: Record<string, unknown>; params?: Record<string, string>; status: number; response?: unknown }> = [
    { path: `/circles/${circleId}/checkins`, method: "POST", operation: "createCheckin", params: { circleId }, status: 201, body: { clientEventId: clientId, label: "Arrived", latitude: 19.076, longitude: 72.8777, accuracyMeters: 10, capturedAt: createdAt } },
    { path: `/circles/${circleId}/quick-notes`, method: "POST", operation: "createQuickNote", params: { circleId }, status: 201, body: { clientEventId: clientId, text: "At the gate" } },
    { path: `/circles/${circleId}/invites`, method: "POST", operation: "createCircleInvite", params: { circleId }, status: 201, body: { clientRequestId: clientId, role: "member", expiresAt: inviteExpiresAt, maxUses: 1 }, response: { id: eventId, circleId, url: `https://track.srishanth.me/i#${oneTimeInviteToken}`, role: "member", expiresAt: inviteExpiresAt, maxUses: 1, createdAt: inviteCreatedAt } },
    { path: `/circles/${circleId}/members/${member.userId}`, method: "PATCH", operation: "updateCircleMember", params: { circleId, memberId: member.userId }, status: 200, body: { precision: "approximate" } },
    { path: `/circles/${circleId}/events`, method: "POST", operation: "createEvent", params: { circleId }, status: 201, body: { clientEventId: clientId, title: "Dinner", startsAt: createdAt, endsAt: null, notes: null, inviteeMemberIds: [member.userId] } },
    { path: `/circles/${circleId}/events/${eventId}/rsvp`, method: "PUT", operation: "updateEventRsvp", params: { circleId, eventId }, status: 200, body: { response: "yes" } },
    { path: `/circles/${circleId}/activity-sharing`, method: "PUT", operation: "setActivitySharing", params: { circleId }, status: 200, body: { activitySharingEnabled: true } },
    { path: `/circles/${circleId}/activity-sharing`, method: "GET", operation: "getActivitySharing", params: { circleId }, status: 200 },
    { path: `/circles/${circleId}/morning-check-ins`, method: "POST", operation: "createMorningCheckIn", params: { circleId }, status: 201, body: { clientEventId: clientId, greeting: "Good morning" } },
    { path: `/circles/${circleId}/bubbles`, method: "POST", operation: "createBubble", params: { circleId }, status: 201, body: { clientEventId: clientId, latitude: 19.076, longitude: 72.8777, radiusMeters: 150, durationMinutes: 60 } },
    { path: `/circles/${circleId}/bubbles`, method: "GET", operation: "listBubbles", params: { circleId }, status: 200 },
    { path: `/circles/${circleId}/bubbles/${bubbleId}`, method: "DELETE", operation: "endBubble", params: { circleId, bubbleId }, status: 200, response: bubbleEndResult },
    { path: `/circles/${circleId}/emergency-contacts`, method: "POST", operation: "inviteEmergencyContact", params: { circleId }, status: 201, body: { clientRequestId: clientId, recipientUsername: "nisha" } },
    { path: `/emergency-contact-invites/${eventId}/accept`, method: "POST", operation: "acceptEmergencyContactInvite", params: { inviteId: eventId }, status: 200 },
    { path: `/circles/${circleId}/sos/${sosId}`, method: "GET", operation: "getSosAlert", params: { circleId, sosId }, status: 200 },
    { path: `/pets/${petId}/routines`, method: "POST", operation: "createPetRoutine", params: { petId }, status: 201, body: { clientRequestId: clientId, name: "Morning meal", kind: "feeding", notes: null, dueAt: null, recurrence: { frequency: "daily", interval: 1, localTime: "08:30", timeZone: "Asia/Kolkata" } } },
    { path: `/pets/${petId}/routines/${routineId}/complete`, method: "POST", operation: "completePetRoutine", params: { petId, routineId }, status: 201, body: { clientEventId: clientId } },
    { path: `/pets/${petId}/recovery-link`, method: "DELETE", operation: "revokePetRecoveryLink", params: { petId }, status: 200 },
    { path: `/lost-pets/${reportId}`, method: "DELETE", operation: "revokeLostPetReport", params: { reportId }, status: 200 },
    { path: `/circles/${circleId}/trips/${tripId}/stop`, method: "POST", operation: "stopTrip", params: { circleId, tripId }, status: 200, body: { clientRequestId: clientId, endReason: "arrived" }, response: tripStopResult },
    { path: "/notifications", method: "PATCH", operation: "acknowledgeNotifications", status: 200, body: { notificationIds: [eventId, bubbleId] } },
  ];
  const calls: Array<{ operation: string; args: Record<string, any> }> = [];
  const dispatch = createApiDispatcher(services({
    dispatchMember: async (_context, operation, args) => {
      calls.push({ operation, args });
      if (operation === "createCircleInvite") return ok({ id: eventId, circleId, inviteToken: oneTimeInviteToken, role: "member", expiresAt: inviteExpiresAt, maxUses: 1, createdAt: inviteCreatedAt }, 201);
      if (operation === "endBubble") return ok(bubbleEndResult);
      if (operation === "stopTrip") return ok(tripStopResult);
      return ok({ operation });
    },
  }));

  for (const item of cases) {
    const response = await dispatch(request(item.path, item.method, item.body, { authorization: "Bearer session" }));
    const call = calls.at(-1);
    assert.equal(response.status, item.status, `${item.method} ${item.path}`);
    assert.equal(call?.operation, item.operation, `${item.method} ${item.path} operation`);
    assert.deepEqual(call?.args.path, item.params ?? {}, `${item.method} ${item.path} path params`);
    if (item.body) assert.deepEqual(call?.args.body, item.body, `${item.method} ${item.path} body`);
    if (item.response) assert.deepEqual(await json(response), item.response, `${item.method} ${item.path} response`);
  }
  assert.deepEqual(await json(await dispatch(request(`/circles/${circleId}/bubbles/${bubbleId}`, "DELETE", undefined, { authorization: "Bearer session" }))), bubbleEndResult);
});

test("new typed route bodies reject unknown fields and schema violations before database dispatch", async () => {
  const circleId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const petId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
  const eventId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  const id = "77777777-7777-4777-8777-777777777777";
  const createdAt = "2026-11-26T10:00:00Z";
  const placeId = "55555555-5555-4555-8555-555555555555";
  const invalid: Array<{ path: string; method: string; body: Record<string, unknown> }> = [
    { path: "/circles", method: "POST", body: { name: "Weekend", extra: true } },
    { path: "/circles", method: "POST", body: { name: "   " } },
    { path: "/circles", method: "POST", body: { name: "Temporary", kind: "temporary" } },
    { path: `/circles/${circleId}/driving/trips/${eventId}`, method: "PATCH", body: { classification: "pilot" } },
    { path: `/circles/${circleId}/driving/trips/${eventId}`, method: "PATCH", body: { classification: "driver", confirmedDrive: true } },
    { path: "/privacy/deletion", method: "POST", body: { reason: "other", confirmationPhrase: "DELETE" } },
    { path: "/privacy/deletion", method: "POST", body: { reason: "later", confirmationPhrase: "DELETE MY ACCOUNT" } },
    { path: "/privacy/deletion", method: "POST", body: { reason: "privacy_concern", details: "x".repeat(501), confirmationPhrase: "DELETE MY ACCOUNT" } },
    { path: "/circle-invites/accept", method: "POST", body: { inviteToken: "short" } },
    { path: "/circle-invites/accept", method: "POST", body: { inviteToken: "x".repeat(32), extra: true } },
    { path: `/circle-invites/accept?inviteToken=${"x".repeat(32)}`, method: "POST", body: { inviteToken: "x".repeat(32) } },
    { path: `/circles/${circleId}`, method: "PATCH", body: {} },
    { path: `/circles/${circleId}`, method: "PATCH", body: { name: "Family", extra: true } },
    { path: `/circles/${circleId}`, method: "PATCH", body: { name: "" } },
    { path: `/circles/${circleId}/invites`, method: "POST", body: { clientRequestId: id, extra: true } },
    { path: `/circles/${circleId}/invites`, method: "POST", body: { clientRequestId: id, role: "owner" } },
    { path: `/circles/${circleId}/invites`, method: "POST", body: { clientRequestId: id, expiresAt: new Date(Date.now() - 60_000).toISOString() } },
    { path: `/circles/${circleId}/invites`, method: "POST", body: { clientRequestId: id, maxUses: 101 } },
    { path: `/circles/${circleId}/members/${member.userId}`, method: "PATCH", body: {} },
    { path: `/circles/${circleId}/members/${member.userId}`, method: "PATCH", body: { role: "member", precision: "approximate" } },
    { path: `/circles/${circleId}/members/${member.userId}`, method: "PATCH", body: { role: "owner" } },
    { path: `/circles/${circleId}/members/${member.userId}`, method: "PATCH", body: { precision: "approximate", sharingEnabled: false } },
    { path: "/notifications", method: "PATCH", body: { notificationIds: [] } },
    { path: "/notifications", method: "PATCH", body: { notificationIds: [id, id] } },
    { path: "/notifications", method: "PATCH", body: { notificationIds: ["not-a-uuid"] } },
    { path: "/notifications", method: "PATCH", body: { notificationIds: Array.from({ length: 101 }, (_, index) => `${String(index + 1).padStart(8, "0")}-0000-4000-8000-000000000000`) } },
    { path: `/circles/${circleId}/checkins`, method: "POST", body: { clientEventId: id, label: "Here", latitude: 19, longitude: 72, accuracyMeters: 10, capturedAt: createdAt, extra: true } },
    { path: `/circles/${circleId}/quick-notes`, method: "POST", body: { clientEventId: id, text: "x".repeat(281) } },
    { path: `/circles/${circleId}/events`, method: "POST", body: { clientEventId: id, title: "Meet", startsAt: createdAt, inviteeMemberIds: [member.userId, member.userId] } },
    { path: `/circles/${circleId}/events/${eventId}/rsvp`, method: "PUT", body: { response: "later" } },
    { path: `/circles/${circleId}/activity-sharing`, method: "PUT", body: { activitySharingEnabled: "yes" } },
    { path: `/circles/${circleId}/morning-check-ins`, method: "POST", body: { clientEventId: id, greeting: "x".repeat(121) } },
    { path: `/circles/${circleId}/bubbles`, method: "POST", body: { clientEventId: id, latitude: 19, longitude: 72, radiusMeters: 49, durationMinutes: 60 } },
    { path: `/circles/${circleId}/emergency-contacts`, method: "POST", body: { clientRequestId: id, recipientUsername: "ab" } },
    { path: `/pets/${petId}/routines`, method: "POST", body: { clientRequestId: id, name: "Food", kind: "feeding", recurrence: { frequency: "daily", interval: 1, extra: "no" } } },
    { path: `/pets/${petId}/routines/${eventId}/complete`, method: "POST", body: { clientEventId: "not-a-uuid" } },
    { path: `/circles/${circleId}/trips/${eventId}/stop`, method: "POST", body: { clientRequestId: id, endReason: "traffic" } },
    { path: `/circles/${circleId}/trips`, method: "POST", body: { recipientMemberIds: [member.userId], confirmedDrive: "yes" } },
    { path: `/circles/${circleId}/trips`, method: "POST", body: { recipientMemberIds: [member.userId], destinationLabel: 123 } },
    { path: `/circles/${circleId}/places/${placeId}`, method: "PATCH", body: {} },
    { path: `/circles/${circleId}/places/${placeId}`, method: "PATCH", body: { alertOnArrival: "yes" } },
    { path: `/circles/${circleId}/places/${placeId}/rules`, method: "POST", body: { kind: "unknown" } },
    { path: `/circles/${circleId}/places/${placeId}/rules`, method: "POST", body: { kind: "no_show", recurrence: { frequency: "daily", interval: 1, extra: true } } },
    { path: `/circles/${circleId}/places/${placeId}/rules/${eventId}`, method: "PATCH", body: { kind: "arrival", memberIds: ["bad-id"] } },
    { path: `/pets/${petId}`, method: "PATCH", body: {} },
    { path: `/pets/${petId}`, method: "PATCH", body: { unknown: true } },
    { path: `/pets/${petId}`, method: "PATCH", body: { circleIds: [member.userId, member.userId] } },
    { path: `/pets/${petId}`, method: "PATCH", body: { photoObjectPath: `eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee/11111111-2222-4333-8444-555555555555.jpg` } },
    { path: `/pets/${petId}`, method: "PATCH", body: { photoObjectPath: `${petId}/../private.jpg` } },
    { path: "/guardian-consents", method: "POST", body: { childMemberId: "bad-id", guardianUsername: "nisha" } },
    { path: "/guardian-consents", method: "POST", body: { childMemberId: member.userId, guardianUsername: "nisha", relationship: "friend" } },
    { path: "/guardian-consents", method: "POST", body: { childMemberId: member.userId, guardianUsername: "nisha", extra: true } },
  ];
  let calls = 0;
  const dispatch = createApiDispatcher(services({ dispatchMember: async () => { calls++; return ok({}); } }));
  for (const item of invalid) {
    const response = await dispatch(request(item.path, item.method, item.body, { authorization: "Bearer session" }));
    assert.equal(response.status, 400, `${item.method} ${item.path}`);
    assert.equal((await json(response)).error.code, "INVALID_REQUEST");
  }
  assert.equal(calls, 0);
});

test("latest Circle, Place, pet, rule, privacy, and guardian routes dispatch contract-shaped bodies", async () => {
  const circleId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const placeId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  const petId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
  const ruleId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
  const clientPhotoPath = `${petId}/11111111-2222-4333-8444-555555555555.webp`;
  const inviteToken = "invitation-token-test-1234567890123456";
  const cases: Array<{ path: string; method: string; body: Record<string, unknown>; operation: string; params: Record<string, string>; status?: number }> = [
    { path: "/circles", method: "POST", operation: "createCircle", params: {}, body: { name: "Weekend Circle", kind: "lightweight" } },
    { path: `/circles/${circleId}/trips`, method: "POST", operation: "startTrip", params: { circleId }, body: { recipientMemberIds: [member.userId], confirmedDrive: true } },
    { path: `/circles/${circleId}/driving/trips/${ruleId}`, method: "PATCH", operation: "correctDriveClassification", params: { circleId, tripId: ruleId }, body: { classification: "passenger" } },
    { path: `/circles/${circleId}`, method: "PATCH", operation: "updateCircle", params: { circleId }, body: { name: "Home Circle" } },
    { path: `/circles/${circleId}/places/${placeId}`, method: "PATCH", operation: "updatePlace", params: { circleId, placeId }, body: { radiusMeters: 250 } },
    { path: `/circles/${circleId}/places/${placeId}/rules`, method: "POST", operation: "createPlaceRule", params: { circleId, placeId }, body: { kind: "no_show", expectedAt: "2026-11-26T15:00:00Z", graceMinutes: 20, recurrence: null } },
    { path: `/circles/${circleId}/places/${placeId}/rules/${ruleId}`, method: "PATCH", operation: "updatePlaceRule", params: { circleId, placeId, ruleId }, body: { kind: "arrival", memberIds: [], recurrence: { frequency: "weekly", interval: 2, weekdays: [1, 3], localTime: "08:30", timeZone: "Asia/Kolkata" } } },
    { path: `/pets/${petId}`, method: "PATCH", operation: "updatePet", params: { petId }, body: { photoObjectPath: clientPhotoPath } },
    { path: "/privacy/deletion", method: "POST", operation: "requestAccountDeletion", params: {}, status: 202, body: { reason: "privacy_concern", details: "Please remove my account data.", confirmationPhrase: "DELETE MY ACCOUNT" } },
    { path: "/guardian-consents", method: "POST", operation: "requestGuardianConsent", params: {}, body: { childMemberId: member.userId, guardianUsername: "nisha", relationship: "parent" } },
    { path: "/circle-invites/accept", method: "POST", operation: "acceptCircleInvite", params: {}, status: 200, body: { inviteToken } },
  ];
  const calls: Array<{ operation: string; args: Record<string, any> }> = [];
  const dispatch = createApiDispatcher(services({
    dispatchMember: async (_context, operation, args) => {
      calls.push({ operation, args });
      return ok({ operation });
    },
  }));
  for (const item of cases) {
    const response = await dispatch(request(item.path, item.method, item.body, { authorization: "Bearer session" }));
    assert.equal(response.status, item.status ?? (item.method === "POST" ? 201 : 200));
    const expectedBody = item.operation === "acceptCircleInvite"
      ? { inviteTokenHash: createHash("sha256").update(inviteToken).digest("hex") }
      : item.body;
    assert.deepEqual(calls.at(-1), { operation: item.operation, args: { path: item.params, query: {}, body: expectedBody } });
    if (item.operation === "acceptCircleInvite") {
      assert.equal(JSON.stringify(calls.at(-1)).includes(inviteToken), false);
    }
  }
});

test("pet list and mutations sign private photo paths without forwarding member bearer", async () => {
  const petId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
  const circleId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const objectPath = `${petId}/11111111-2222-4333-8444-555555555555.webp`;
  const encodedObjectPath = objectPath.split("/").map(encodeURIComponent).join("/");
  const memberBearer = "member-api-token";
  const secretKey = "secret-api-test-key";
  const previousUrl = process.env.SUPABASE_URL;
  const previousPublicUrl = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const previousSecretKey = process.env.SUPABASE_SECRET_KEY;
  const originalFetch = globalThis.fetch;
  const storageAuthorizations: Array<string | null> = [];
  const storageApiKeys: Array<string | null> = [];
  const operations: string[] = [];

  process.env.SUPABASE_URL = "https://supabase.test";
  process.env.SUPABASE_SECRET_KEY = secretKey;
  delete process.env.NEXT_PUBLIC_SUPABASE_URL;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const headers = new Headers(init?.headers);
    if (url.includes("/rest/v1/rpc/opentrack_api_dispatch")) {
      const args = JSON.parse(String(init?.body));
      const operation = String(args.p_operation);
      operations.push(operation);
      const profile = { id: petId, name: "Milo", species: "dog", photoObjectPath: objectPath, circleIds: [circleId], recoveryEnabled: true, createdAt: "2026-11-26T10:00:00Z" };
      const ownerProfile = { ...profile, recoveryFields: ["petName", "photo", "ownerMessage"], ownerMessage: "Please call", contactRoute: null };
      const body = operation === "listPets" ? { data: [profile], nextCursor: null } : ownerProfile;
      return new Response(JSON.stringify({ httpStatus: operation === "createPet" ? 201 : 200, body }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    if (url.includes("/storage/v1/object/sign/pet-photos/")) {
      storageAuthorizations.push(headers.get("authorization"));
      storageApiKeys.push(headers.get("apikey"));
      return new Response(JSON.stringify({ signedURL: `/storage/v1/object/sign/pet-photos/${encodedObjectPath}?token=temporary-read` }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    throw new Error("Unexpected upstream request");
  }) as typeof fetch;

  try {
    const dispatch = createApiDispatcher(createDefaultApiServices(async () => member));
    const responses = [
      await dispatch(request("/pets", "GET", undefined, { authorization: `Bearer ${memberBearer}` })),
      await dispatch(request(`/pets/${petId}`, "GET", undefined, { authorization: `Bearer ${memberBearer}` })),
      await dispatch(request("/pets", "POST", { name: "Milo", circleIds: [circleId] }, { authorization: `Bearer ${memberBearer}` })),
      await dispatch(request(`/pets/${petId}`, "PATCH", { photoObjectPath: objectPath }, { authorization: `Bearer ${memberBearer}` })),
    ];
    assert.deepEqual(operations, ["listPets", "getPet", "createPet", "updatePet"]);
    assert.equal(storageAuthorizations.length, 4);
    assert.ok(storageAuthorizations.every((authorization) => authorization === null));
    assert.ok(storageApiKeys.every((key) => key === secretKey));
    assert.ok(storageAuthorizations.every((authorization) => String(authorization) !== `Bearer ${memberBearer}`));

    const [listResponse, detailResponse, createResponse, updateResponse] = responses;
    const listBody = await json(listResponse);
    const detailBody = await json(detailResponse);
    const createBody = await json(createResponse);
    const updateBody = await json(updateResponse);
    const expectedUrl = `https://supabase.test/storage/v1/object/sign/pet-photos/${encodedObjectPath}?token=temporary-read`;
    assert.equal(listResponse.status, 200);
    assert.equal(detailResponse.status, 200);
    assert.equal(createResponse.status, 201);
    assert.equal(updateResponse.status, 200);
    assert.equal(listBody.data[0].photoUrl, expectedUrl);
    assert.equal(detailBody.photoUrl, expectedUrl);
    assert.equal(createBody.photoUrl, expectedUrl);
    assert.equal(updateBody.photoUrl, expectedUrl);
    assert.equal(listBody.data[0].recoveryFields, undefined);
    assert.equal(listBody.data[0].ownerMessage, undefined);
    assert.deepEqual(detailBody.recoveryFields, ["petName", "photo", "ownerMessage"]);
    assert.equal(detailBody.ownerMessage, "Please call");
    for (const body of [listBody, detailBody, createBody, updateBody]) {
      const serialized = JSON.stringify(body);
      assert.equal(serialized.includes("photoObjectPath"), false);
    }
    for (const pet of [listBody.data[0], detailBody, createBody, updateBody]) {
      const publicFieldsWithoutPhotoUrl = Object.fromEntries(Object.entries(pet).filter(([key]) => key !== "photoUrl"));
      assert.equal(JSON.stringify(publicFieldsWithoutPhotoUrl).includes(objectPath), false);
    }
  } finally {
    globalThis.fetch = originalFetch;
    if (previousUrl === undefined) delete process.env.SUPABASE_URL;
    else process.env.SUPABASE_URL = previousUrl;
    if (previousPublicUrl === undefined) delete process.env.NEXT_PUBLIC_SUPABASE_URL;
    else process.env.NEXT_PUBLIC_SUPABASE_URL = previousPublicUrl;
    if (previousSecretKey === undefined) delete process.env.SUPABASE_SECRET_KEY;
    else process.env.SUPABASE_SECRET_KEY = previousSecretKey;
  }
});

test("pet photo upload authorization sends only content metadata before minting a private Storage URL", async () => {
  const petId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
  const secretKey = "secret-api-upload-test";
  const previousUrl = process.env.SUPABASE_URL;
  const previousPublicUrl = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const previousSecretKey = process.env.SUPABASE_SECRET_KEY;
  const originalFetch = globalThis.fetch;
  const rpcCalls: Array<Record<string, any>> = [];
  let storageAuthorization: string | null = null;
  let storageApiKey: string | null = null;
  let storageUrl = "";

  process.env.SUPABASE_URL = "https://supabase.test";
  process.env.SUPABASE_SECRET_KEY = secretKey;
  delete process.env.NEXT_PUBLIC_SUPABASE_URL;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const headers = new Headers(init?.headers);
    if (url.includes("/rest/v1/rpc/opentrack_api_dispatch")) {
      rpcCalls.push(JSON.parse(String(init?.body)) as Record<string, any>);
      return new Response(JSON.stringify({ httpStatus: 200, body: { authorized: true } }), { status: 200, headers: { "content-type": "application/json" } });
    }
    if (url.includes("/storage/v1/object/upload/sign/pet-photos/")) {
      storageUrl = url;
      storageAuthorization = headers.get("authorization");
      storageApiKey = headers.get("apikey");
      return new Response(JSON.stringify({ signedURL: "/storage/v1/object/upload/sign/pet-photos/upload?token=temporary-upload" }), { status: 200, headers: { "content-type": "application/json" } });
    }
    throw new Error("Unexpected upstream request");
  }) as typeof fetch;

  try {
    const dispatch = createApiDispatcher(createDefaultApiServices(async () => member));
    const response = await dispatch(request(`/pets/${petId}/photo`, "POST", { contentType: "image/jpeg", sizeBytes: 45_000 }, { authorization: "Bearer member-api-token" }));
    const result = await json(response);
    assert.equal(response.status, 201);
    assert.equal(rpcCalls[0]?.p_operation, "createPetPhotoUpload");
    assert.deepEqual(rpcCalls[0]?.p_args, { path: { petId }, query: {}, body: { contentType: "image/jpeg", sizeBytes: 45_000 } });
    assert.equal(storageAuthorization, null);
    assert.equal(storageApiKey, secretKey);
    assert.ok(storageUrl.includes(`/storage/v1/object/upload/sign/pet-photos/${petId}/`));
    assert.ok(result.objectPath.startsWith(`${petId}/`));
    assert.equal(result.uploadUrl, "https://supabase.test/storage/v1/object/upload/sign/pet-photos/upload?token=temporary-upload");
  } finally {
    globalThis.fetch = originalFetch;
    if (previousUrl === undefined) delete process.env.SUPABASE_URL;
    else process.env.SUPABASE_URL = previousUrl;
    if (previousPublicUrl === undefined) delete process.env.NEXT_PUBLIC_SUPABASE_URL;
    else process.env.NEXT_PUBLIC_SUPABASE_URL = previousPublicUrl;
    if (previousSecretKey === undefined) delete process.env.SUPABASE_SECRET_KEY;
    else process.env.SUPABASE_SECRET_KEY = previousSecretKey;
  }
});

test("API JSON serialization strips internal pet storage paths at every depth", async () => {
  const petId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
  const privatePath = `${petId}/private-photo.jpg`;
  const dispatch = createApiDispatcher(services({
    dispatchMember: async () => ok({ data: { petId, photoObjectPath: privatePath, inviteToken: "one-time-token" }, diagnostics: { photoObjectPath: privatePath } }),
  }));
  const response = await dispatch(request(`/pets/${petId}`, "GET", undefined, { authorization: "Bearer session" }));
  const serialized = JSON.stringify(await json(response));
  assert.equal(response.status, 200);
  assert.equal(serialized.includes("photoObjectPath"), false);
  assert.equal(serialized.includes(privatePath), false);
  assert.equal(serialized.includes("inviteToken"), false);
  assert.equal(serialized.includes("one-time-token"), false);
});

test("pet recovery link creation returns only the one-time fragment URL contract shape", async () => {
  let observed: { secret: string; hash: string } | null = null;
  const petId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
  const tokenId = "55555555-5555-4555-8555-555555555555";
  const createdAt = "2026-11-26T10:00:00Z";
  const expiresAt = "2026-12-03T10:00:00Z";
  const dispatch = createApiDispatcher(services({
    createPetRecoveryLink: async (_context, _petId, secret, hash) => {
      observed = { secret, hash };
      return ok({ id: tokenId, createdAt, expiresAt, secretHash: hash, photoObjectPath: "private" }, 201);
    },
  }));
  const response = await dispatch(request(`/pets/${petId}/recovery-link`, "POST", undefined, { authorization: "Bearer session" }));
  const result = await json(response);
  const credential = observed!;
  assert.equal(response.status, 201);
  assert.deepEqual(Object.keys(result).sort(), ["createdAt", "expiresAt", "id", "url"]);
  assert.deepEqual(result, { id: tokenId, url: `https://track.srishanth.me/p/${petId}#${credential.secret}`, createdAt, expiresAt });
  assert.equal(credential.secret.length, 43);
  assert.equal(credential.hash, createHash("sha256").update(credential.secret).digest("hex"));
  assert.equal(JSON.stringify(result).includes(credential.hash), false);
  assert.equal(result.url.includes(`?secret=${credential.secret}`), false);
});

test("password-rotation gate blocks member data with the contract 428 response", async () => {
  const dispatch = createApiDispatcher(services({
    requireMember: async () => { throw Object.assign(new Error("internal"), { status: 428, code: "password_rotation_required" }); },
  }));
  const response = await dispatch(request("/circles", "GET", undefined, { authorization: "Bearer session" }));
  assert.equal(response.status, 428);
  assert.deepEqual(await json(response), { error: { code: "PASSWORD_ROTATION_REQUIRED", message: "Change your password to continue" } });
});

test("location batch rejects repeated IDs and stale epochs before persistence", async () => {
  const submissions: Array<{ context: MemberContext; clientEpoch: number; samples: Record<string, unknown>[] }> = [];
  let calls = 0;
  const dispatch = createApiDispatcher(services({
    uploadLocationBatch: async (context, clientEpoch, samples) => { calls++; submissions.push({ context, clientEpoch, samples }); return ok({ serverTime: "2026-11-26T10:00:11Z", accepted: [sample.sampleId], duplicates: [], rejected: [] }); },
  }));
  const duplicate = await dispatch(request("/locations/batch", "POST", { sharingEpoch: 12, samples: [sample, sample] }, { authorization: "Bearer session" }));
  assert.equal(duplicate.status, 400);
  assert.equal(calls, 0);
  const missingEpoch = await dispatch(request("/locations/batch", "POST", { samples: [sample] }, { authorization: "Bearer session" }));
  assert.equal(missingEpoch.status, 400);
  const stale = await dispatch(request("/locations/batch", "POST", { sharingEpoch: 11, samples: [sample] }, { authorization: "Bearer session" }));
  assert.equal(stale.status, 409);
  assert.deepEqual(await json(stale), { error: { code: "SHARING_EPOCH_MISMATCH", message: "Refresh the member profile and discard queued samples from an older sharing epoch." } });
  assert.equal(calls, 0);
  const accepted = await dispatch(request("/locations/batch", "POST", { sharingEpoch: 12, samples: [sample] }, { authorization: "Bearer session" }));
  assert.equal(accepted.status, 200);
  assert.equal(calls, 1);
  assert.equal(submissions[0]?.context.sharingEpoch, 12);
  assert.equal(submissions[0]?.clientEpoch, 12);
  assert.equal(submissions[0]?.samples[0]?.speedMps, 0);
});

test("public share credentials are accepted only from Authorization and active fixes preserve zero speed", async () => {
  const secret = "a".repeat(43);
  let receivedHash: string | null = null;
  const dispatch = createApiDispatcher(services({
    readShare: async (_id, hash) => { receivedHash = hash; return ok({ status: "active", serverTime: "2026-11-26T10:00:10Z", viewValidUntil: "2026-11-26T10:00:40Z", latest: { ...sample, capturedAt: sample.capturedAt } }); },
  }));
  const id = "f28bd481-5c3c-4ea1-9fa1-0384703c23b0";
  const queryOnly = await dispatch(request(`/public/shares/${id}?secret=${secret}`));
  assert.equal(queryOnly.status, 404);
  const missing = await dispatch(request(`/public/shares/${id}`));
  assert.equal(missing.status, 404);
  assert.equal(receivedHash, null);
  const response = await dispatch(request(`/public/shares/${id}`, "GET", undefined, { authorization: `Bearer ${secret}` }));
  assert.equal(response.status, 200);
  assert.equal(receivedHash, createHash("sha256").update(secret).digest("hex"));
  assert.notEqual(receivedHash, secret);
  assert.equal((await json(response)).latest.speedMps, 0);
  assert.equal(response.headers.get("cache-control"), "private, no-store");
  assert.equal(response.headers.get("referrer-policy"), "no-referrer");
});

test("an already-open guest read strips location immediately after the backend ends a share", async () => {
  let reads = 0;
  const dispatch = createApiDispatcher(services({
    readShare: async () => {
      reads++;
      if (reads === 1) return ok({ status: "active", serverTime: "2026-11-26T10:00:10Z", viewValidUntil: "2026-11-26T10:00:40Z", latest: sample });
      return { httpStatus: 410, body: { status: "ended", latest: sample } };
    },
  }));
  const req = () => request("/public/shares/f28bd481-5c3c-4ea1-9fa1-0384703c23b0", "GET", undefined, { authorization: `Bearer ${"b".repeat(43)}` });
  const active = await dispatch(req());
  assert.equal(active.status, 200);
  assert.ok((await json(active)).latest);
  const ended = await dispatch(req());
  assert.equal(ended.status, 410);
  assert.deepEqual(await json(ended), { error: { code: "GONE", message: "This link has ended" } });
  assert.equal(ended.headers.get("cache-control"), "private, no-store");
  assert.equal(ended.headers.get("referrer-policy"), "no-referrer");
});

test("share read keeps RPC 410 distinct from invalid-secret 404 and strips terminal locations", async () => {
  const originalFetch = globalThis.fetch;
  const oldSupabaseUrl = process.env.SUPABASE_URL;
  const oldSecretKey = process.env.SUPABASE_SECRET_KEY;
  const secret = "d".repeat(43);
  const shareId = "f28bd481-5c3c-4ea1-9fa1-0384703c23b0";
  process.env.SUPABASE_URL = "https://supabase.invalid";
  process.env.SUPABASE_SECRET_KEY = "test-secret-key";

  try {
    for (const [rpcStatus, expectedStatus, expectedCode] of [[404, 404, "NOT_FOUND"], [410, 410, "GONE"]] as const) {
      globalThis.fetch = async (input, init) => {
        assert.equal(String(input), "https://supabase.invalid/rest/v1/rpc/opentrack_share_read");
        assert.equal(init?.method, "POST");
        const args = JSON.parse(String(init?.body)) as { p_share_id: string; p_secret_hash: string };
        assert.equal(args.p_share_id, shareId);
        assert.equal(args.p_secret_hash, createHash("sha256").update(secret).digest("hex"));
        assert.equal(args.p_secret_hash.includes(secret), false);
        return new Response(JSON.stringify({ httpStatus: rpcStatus, body: { status: "ended", latest: sample } }), {
          status: 200,
          headers: { "content-type": "application/json" },
        });
      };
      const dispatch = createApiDispatcher(createDefaultApiServices(async () => member));
      const response = await dispatch(request(`/public/shares/${shareId}`, "GET", undefined, { authorization: `Bearer ${secret}` }));
      assert.equal(response.status, expectedStatus);
      assert.deepEqual(await json(response), { error: { code: expectedCode, message: expectedCode === "GONE" ? "This link has ended" : "Not found" } });
      assert.equal(response.headers.get("cache-control"), "private, no-store");
    }
  } finally {
    globalThis.fetch = originalFetch;
    if (oldSupabaseUrl === undefined) delete process.env.SUPABASE_URL;
    else process.env.SUPABASE_URL = oldSupabaseUrl;
    if (oldSecretKey === undefined) delete process.env.SUPABASE_SECRET_KEY;
    else process.env.SUPABASE_SECRET_KEY = oldSecretKey;
  }
});

test("share creation returns a one-time fragment URL while forwarding only its digest", async () => {
  const captured: { value?: { secret: string; hash: string; input: Record<string, unknown> } } = {};
  const dispatch = createApiDispatcher(services({
    createShare: async (_context, input, secret, hash) => {
      captured.value = { secret, hash, input };
      return ok({ id: "f28bd481-5c3c-4ea1-9fa1-0384703c23b0", mode: "drive_end", status: "active", startedAt: "2026-11-26T10:00:00Z", createdAt: "2026-11-26T10:00:00Z", expiresAt: null, endCondition: "drive end" }, 201);
    },
  }));
  const response = await dispatch(request("/shares", "POST", { mode: "drive_end", confirmedDrive: true }, { authorization: "Bearer session" }));
  const result = await json(response);
  const received = captured.value!;
  assert.equal(response.status, 201);
  assert.equal(received.secret.length, 43);
  assert.equal(received.hash, createHash("sha256").update(received.secret).digest("hex"));
  assert.deepEqual(received.input, { mode: "drive_end", confirmedDrive: true });
  assert.equal(result.url, `https://track.srishanth.me/s/f28bd481-5c3c-4ea1-9fa1-0384703c23b0#${received.secret}`);
  assert.equal(JSON.stringify(result).includes(received.hash), false);
});

test("share RPC sends all eight arguments with confirmed-drive boolean and hash-only secret", async () => {
  const originalFetch = globalThis.fetch;
  const oldSupabaseUrl = process.env.SUPABASE_URL;
  const oldSecretKey = process.env.SUPABASE_SECRET_KEY;
  const upstream: Array<{ url: string; text: string; args: Record<string, unknown> }> = [];
  const shareIds = [
    "f28bd481-5c3c-4ea1-9fa1-0384703c23b0",
    "f28bd481-5c3c-4ea1-9fa1-0384703c23b1",
  ];
  process.env.SUPABASE_URL = "https://supabase.invalid";
  process.env.SUPABASE_SECRET_KEY = "test-secret-key";

  try {
    globalThis.fetch = async (input, init) => {
      const url = String(input);
      const text = String(init?.body);
      const args = JSON.parse(text) as Record<string, unknown>;
      upstream.push({ url, text, args });
      const id = shareIds[upstream.length - 1];
      assert.ok(id);
      return new Response(JSON.stringify({
        httpStatus: 201,
        body: {
          id,
          mode: "drive_end",
          status: "active",
          startedAt: "2026-11-26T10:00:00Z",
          createdAt: "2026-11-26T10:00:00Z",
          expiresAt: null,
          endCondition: "After confirmed drive end",
        },
      }), { status: 200, headers: { "content-type": "application/json" } });
    };

    const dispatch = createApiDispatcher(createDefaultApiServices(async () => member));
    const createBodies = [
      { mode: "drive_end", confirmedDrive: true },
      { mode: "drive_end" },
    ];
    const created: Array<{ id: string; url: URL }> = [];

    for (const body of createBodies) {
      const response = await dispatch(request("/shares", "POST", body, { authorization: "Bearer session" }));
      const result = await json(response);
      assert.equal(response.status, 201);
      assert.equal(typeof result.id, "string");
      created.push({ id: result.id, url: new URL(result.url) });
    }

    assert.equal(upstream.length, 2);
    for (let index = 0; index < upstream.length; index++) {
      const call = upstream[index]!;
      const clearSecret = created[index]!.url.hash.slice(1);
      assert.equal(call.url, "https://supabase.invalid/rest/v1/rpc/opentrack_share_create");
      assert.deepEqual(Object.keys(call.args).sort(), [
        "p_confirmed_drive",
        "p_destination",
        "p_expires_at",
        "p_mode",
        "p_secret_hash",
        "p_session_id",
        "p_sharing_epoch",
        "p_user_id",
      ]);
      assert.equal(call.args.p_user_id, member.userId);
      assert.equal(call.args.p_session_id, member.sessionId);
      assert.equal(call.args.p_sharing_epoch, member.sharingEpoch);
      assert.equal(call.args.p_mode, "drive_end");
      assert.equal(call.args.p_expires_at, null);
      assert.equal(call.args.p_destination, null);
      assert.equal(call.args.p_confirmed_drive, index === 0);
      assert.equal(call.args.p_secret_hash, createHash("sha256").update(clearSecret, "utf8").digest("hex"));
      assert.match(String(call.args.p_secret_hash), /^[0-9a-f]{64}$/);
      assert.equal(call.url.includes(clearSecret), false);
      assert.equal(call.text.includes(clearSecret), false);
      assert.equal(created[index]!.url.origin, "https://track.srishanth.me");
      assert.equal(created[index]!.url.pathname, `/s/${created[index]!.id}`);
      assert.equal(created[index]!.url.search, "");
      assert.ok(clearSecret.length > 0);
    }
  } finally {
    globalThis.fetch = originalFetch;
    if (oldSupabaseUrl === undefined) delete process.env.SUPABASE_URL;
    else process.env.SUPABASE_URL = oldSupabaseUrl;
    if (oldSecretKey === undefined) delete process.env.SUPABASE_SECRET_KEY;
    else process.env.SUPABASE_SECRET_KEY = oldSecretKey;
  }
});

test("drive-end share confirmation is a typed optional boolean and rejects other modes", async () => {
  let calls = 0;
  const dispatch = createApiDispatcher(services({
    createShare: async () => { calls++; return ok({ id: "f28bd481-5c3c-4ea1-9fa1-0384703c23b0" }, 201); },
  }));
  for (const body of [{ mode: "drive_end" }, { mode: "drive_end", confirmedDrive: false }, { mode: "drive_end", confirmedDrive: true }]) {
    const response = await dispatch(request("/shares", "POST", body, { authorization: "Bearer session" }));
    assert.equal(response.status, 201);
  }
  const invalidType = await dispatch(request("/shares", "POST", { mode: "drive_end", confirmedDrive: "yes" }, { authorization: "Bearer session" }));
  const wrongMode = await dispatch(request("/shares", "POST", { mode: "time", expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(), confirmedDrive: true }, { authorization: "Bearer session" }));
  assert.equal(invalidType.status, 400);
  assert.equal(wrongMode.status, 400);
  assert.equal(calls, 3);
});

test("share event evidence is delegated for server confirmation, not ended locally", async () => {
  const events: Record<string, any>[] = [];
  const dispatch = createApiDispatcher(services({
    submitShareEvent: async (_context, _id, value) => { events.push(value); return ok({ clientEventId: value.clientEventId, status: "pending", serverTime: "2026-11-26T10:00:10Z", endedAt: null, reason: "insufficient_evidence" }); },
  }));
  const body = { clientEventId: "33333333-3333-4333-8333-333333333333", eventType: "arrival", observedAt: "2026-11-26T10:00:10Z", evidenceFixes: [sample] };
  const response = await dispatch(request("/shares/f28bd481-5c3c-4ea1-9fa1-0384703c23b0/events", "POST", body, { authorization: "Bearer session" }));
  assert.equal(response.status, 200);
  assert.equal((await json(response)).status, "pending");
  assert.equal(events[0]?.clientEventId, body.clientEventId);
  const invalid = await dispatch(request("/shares/f28bd481-5c3c-4ea1-9fa1-0384703c23b0/events", "POST", { ...body, evidenceFixes: [] }, { authorization: "Bearer session" }));
  assert.equal(invalid.status, 400);
});

test("software-only pet recovery requires the fragment secret and strips non-approved fields", async () => {
  let receivedHash: string | null = null;
  const secret = "c".repeat(43);
  const dispatch = createApiDispatcher(services({
    readPetRecovery: async (_id, hash) => {
      receivedHash = hash;
      return ok({ petName: "Milo", species: "dog", ownerMessage: "Please call the owner", contactRoute: "in-app relay", circleIds: ["aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"], phoneNumber: "private" });
    },
  }));
  const petId = "44444444-4444-4444-8444-444444444444";
  const missing = await dispatch(request(`/public/pets/${petId}`));
  assert.equal(missing.status, 404);
  assert.equal(receivedHash, null);
  const response = await dispatch(request(`/public/pets/${petId}`, "GET", undefined, { authorization: `Bearer ${secret}` }));
  const card = await json(response);
  assert.equal(response.status, 200);
  assert.equal(receivedHash, createHash("sha256").update(secret).digest("hex"));
  assert.equal(card.petName, "Milo");
  assert.equal(card.phoneNumber, undefined);
  assert.equal(card.circleIds, undefined);
});

test("unknown routes and admin paths do not fall through to member dispatch", async () => {
  let calls = 0;
  const dispatch = createApiDispatcher(services({ dispatchMember: async () => { calls++; return ok({}); } }));
  const unknown = await dispatch(request("/not-a-route", "GET", undefined, { authorization: "Bearer session" }));
  const admin = await dispatch(request("/admin/accounts", "GET", undefined, { authorization: "Bearer session" }));
  assert.equal(unknown.status, 404);
  assert.equal(admin.status, 404);
  assert.equal(calls, 0);
});

test("SOS and pet sighting location property groups match the OpenAPI contract", async () => {
  let memberCalls = 0;
  let publicCalls = 0;
  const dispatch = createApiDispatcher(services({
    dispatchMember: async () => { memberCalls++; return ok({ id: "88888888-8888-4888-8888-888888888888" }, 202); },
    submitPetSighting: async () => { publicCalls++; return ok({ ok: true }, 202); },
  }));
  const circleId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const petId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  const secret = "p".repeat(43);
  const sosBase = { clientEventId: "cccccccc-cccc-4ccc-8ccc-cccccccccccc", cancelCountdownSeconds: 5 };
  const sosRequests = [
    { ...sosBase },
    { ...sosBase, latitude: null, longitude: null, accuracyMeters: null },
    { ...sosBase, latitude: 19.076, longitude: 72.8777, accuracyMeters: 12.5 },
  ];
  for (const body of sosRequests) {
    const response = await dispatch(request(`/circles/${circleId}/sos`, "POST", body, { authorization: "Bearer session" }));
    assert.equal(response.status, 202);
  }
  for (const body of [
    { ...sosBase, latitude: null },
    { ...sosBase, latitude: 19.076, longitude: 72.8777 },
    { ...sosBase, latitude: null, longitude: null, accuracyMeters: 12.5 },
    { ...sosBase, latitude: 19.076, longitude: null, accuracyMeters: null },
  ]) {
    const response = await dispatch(request(`/circles/${circleId}/sos`, "POST", body, { authorization: "Bearer session" }));
    assert.equal(response.status, 400);
  }

  const sightingBase = { message: "I saw this pet near the park." };
  const sightingRequests = [
    sightingBase,
    { ...sightingBase, latitude: null, longitude: null },
    { ...sightingBase, latitude: 19.076, longitude: 72.8777 },
    { ...sightingBase, latitude: 19.076, longitude: 72.8777, accuracyMeters: 25 },
    { ...sightingBase, accuracyMeters: 25 },
  ];
  for (const body of sightingRequests) {
    const response = await dispatch(request(`/public/pets/${petId}/sightings`, "POST", body, { authorization: `Bearer ${secret}` }));
    assert.equal(response.status, 202);
  }
  for (const body of [
    { ...sightingBase, latitude: null },
    { ...sightingBase, latitude: 19.076 },
    { ...sightingBase, latitude: null, longitude: 72.8777 },
  ]) {
    const response = await dispatch(request(`/public/pets/${petId}/sightings`, "POST", body, { authorization: `Bearer ${secret}` }));
    assert.equal(response.status, 400);
  }
  assert.equal(memberCalls, 3);
  assert.equal(publicCalls, 5);
});

test("typed optional place flags, finder contact routes, and lost pet messages reject wrong JSON types", async () => {
  const circleId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const dispatch = createApiDispatcher(services({ dispatchMember: async () => ok({ id: "dddddddd-dddd-4ddd-8ddd-dddddddddddd" }, 201) }));
  const invalidPlace = await dispatch(request(`/circles/${circleId}/places`, "POST", {
    name: "Home", latitude: 19.076, longitude: 72.8777, radiusMeters: 150, alertOnArrival: "yes",
  }, { authorization: "Bearer session" }));
  assert.equal(invalidPlace.status, 400);

  const secret = "q".repeat(43);
  const invalidSighting = await dispatch(request("/public/pets/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb/sightings", "POST", {
    message: "I saw this pet.", contactRoute: false,
  }, { authorization: `Bearer ${secret}` }));
  assert.equal(invalidSighting.status, 400);

  const invalidLostPetMessage = await dispatch(request("/lost-pets", "POST", {
    petId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", recipientCircleIds: [circleId],
    message: 42, expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
  }, { authorization: "Bearer session" }));
  assert.equal(invalidLostPetMessage.status, 400);
});
