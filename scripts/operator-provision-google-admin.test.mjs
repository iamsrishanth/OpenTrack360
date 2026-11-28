import assert from "node:assert/strict";
import test from "node:test";
import {
  buildOperatorSql,
  extractJsonObject,
  googleAdminRequestId,
  googleAdminUsername,
  isRequestScopedGoogleUser,
  isVerifiedGoogleUser,
  localSecretMatchesLatestServerKey,
  normalizeGoogleEmail,
  parseArguments,
} from "./operator-provision-google-admin.mjs";

test("Google admin identity normalizes email and derives a constrained username", () => {
  assert.equal(normalizeGoogleEmail("  Admin.User@Example.com  "), "admin.user@example.com");
  assert.equal(googleAdminUsername("Admin.User@Example.com"), "admin.user.admin");
  assert.equal(normalizeGoogleEmail("not-an-email"), null);
});

test("Google admin request identifier is deterministic and project scoped", () => {
  const first = googleAdminRequestId("admin@example.com");
  assert.match(first, /^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(first, googleAdminRequestId("ADMIN@example.com"));
  assert.notEqual(first, googleAdminRequestId("other@example.com"));
  assert.throws(() => googleAdminRequestId("admin@example.com", "wrong-project"));
});

test("production execution requires explicit project and rotation flags", () => {
  assert.throws(() => parseArguments(["--execute", "--email", "admin@example.com", "--project-ref", "oxfcxucpzrvbifefnwvp"]), /key-rotation-verified/);
  assert.throws(() => parseArguments(["--execute", "--email", "admin@example.com", "--project-ref", "wrong"]), /Project reference/);
  assert.equal(parseArguments(["--email", "admin@example.com"]).execute, false);
  assert.equal(parseArguments(["--execute", "--email", "admin@example.com", "--project-ref", "oxfcxucpzrvbifefnwvp", "--key-rotation-verified"]).execute, true);
});

test("operator SQL calls only the postgres-scoped Google grant function", () => {
  const sql = buildOperatorSql({
    requestId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    operatorSubjectId: "99999999-9999-4999-8999-999999999999",
    targetUserId: "22222222-2222-4222-8222-222222222222",
    email: "admin@example.com",
    username: "admin.admin",
  });
  assert.match(sql, /private\.opentrack_operator_add_google_admin/);
  assert.match(sql, /admin@example\.com/);
  assert.throws(() => buildOperatorSql({
    requestId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    operatorSubjectId: "99999999-9999-4999-8999-999999999999",
    targetUserId: "22222222-2222-4222-8222-222222222222",
    email: "admin@example.com",
    username: "other.admin",
  }), /outside the requested project scope/);
});

test("provisioner accepts only a request-scoped or verified Google identity", () => {
  const user = {
    email: "admin@example.com",
    email_confirmed_at: "2026-01-01T00:00:00Z",
    user_metadata: {
      opentrack_google_admin_email: "admin@example.com",
      opentrack_google_admin_request_id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    },
    identities: [],
  };
  assert.equal(isRequestScopedGoogleUser(user, "admin@example.com", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), true);
  assert.equal(isRequestScopedGoogleUser(user, "other@example.com", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), false);
  assert.equal(isVerifiedGoogleUser(user, "admin@example.com"), false);
  assert.equal(isVerifiedGoogleUser({
    ...user,
    identities: [{ provider: "google", identity_data: { email: "admin@example.com", email_verified: true } }],
  }, "admin@example.com"), true);
});

test("Supabase CLI JSON extraction handles nested output", () => {
  assert.deepEqual(extractJsonObject("notice\n{\"rows\":[{\"result\":{\"ok\":true}}]}\n"), {
    rows: [{ result: { ok: true } }],
  });
});

test("operator provisioner accepts only the latest named project server key", () => {
  const output = `Target: OpenTrack360\n${JSON.stringify({
    target: { project_ref: "oxfcxucpzrvbifefnwvp" },
    keys: [
      { type: "secret", name: "default", prefix: "sb_secret_old", inserted_at: "2026-11-26T00:00:00Z" },
      { type: "secret", name: "opentrack360_server_20261127", prefix: "sb_secret_new", inserted_at: "2026-11-27T00:00:00Z" },
    ],
  })}`;
  assert.equal(localSecretMatchesLatestServerKey("sb_secret_new_actual_secret_value", output), true);
  assert.equal(localSecretMatchesLatestServerKey("sb_secret_old_actual_secret_value", output), false);
  assert.equal(localSecretMatchesLatestServerKey("sb_secret_new_actual_secret_value", output.replace("oxfcxucpzrvbifefnwvp", "wrong-project")), false);
});
