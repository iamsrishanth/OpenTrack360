import assert from "node:assert/strict";
import test from "node:test";
import {
  ACCOUNT_DISPLAY_NAME,
  PROJECT_REF,
  buildAccountIdentity,
  buildPromotionSql,
  extractJsonObject,
  getProjectRefFromUrl,
  parseArguments,
  parseLocalEnv,
} from "./operator-provision-admin.mjs";

const requestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const operatorSubjectId = "99999999-9999-4999-8999-999999999999";
const targetUserId = "22222222-2222-4222-8222-222222222222";

test("project URL parsing accepts only the expected Supabase URL form", () => {
  assert.equal(getProjectRefFromUrl(`https://${PROJECT_REF}.supabase.co`), PROJECT_REF);
  assert.equal(getProjectRefFromUrl(`http://${PROJECT_REF}.supabase.co`), null);
  assert.equal(getProjectRefFromUrl("https://example.com"), null);
  assert.equal(getProjectRefFromUrl("not a url"), null);
});

test("local env parsing ignores comments and unwraps quoted values", () => {
  const contents = [
    "# ignored",
    "SUPABASE_URL=https://example.supabase.co",
    'SUPABASE_SECRET_KEY="sb_secret_test-value"',
    "INVALID LINE",
  ].join("\n");
  assert.deepEqual(parseLocalEnv(contents), {
    SUPABASE_URL: "https://example.supabase.co",
    SUPABASE_SECRET_KEY: "sb_secret_test-value",
  });
  assert.deepEqual(parseLocalEnv(contents, ["SUPABASE_URL"]), {
    SUPABASE_URL: "https://example.supabase.co",
  });
});

test("production execution requires the exact project and explicit rotation signal", () => {
  assert.deepEqual(parseArguments(["--project-ref", PROJECT_REF]), {
    execute: false,
    keyRotationVerified: false,
    projectRef: PROJECT_REF,
    help: false,
  });
  assert.throws(() => parseArguments(["--execute"]), /exact --project-ref/);
  assert.throws(() => parseArguments(["--execute", "--project-ref", PROJECT_REF]), /--key-rotation-verified/);
  assert.throws(() => parseArguments(["--execute", "--project-ref", "wrong", "--key-rotation-verified"]), /Project reference/);
  assert.deepEqual(parseArguments(["--execute", "--project-ref", PROJECT_REF, "--key-rotation-verified"]), {
    execute: true,
    keyRotationVerified: true,
    projectRef: PROJECT_REF,
    help: false,
  });
});

test("request identity is deterministic and isolated to the dedicated synthetic prefix", () => {
  const identity = buildAccountIdentity(requestId);
  assert.equal(identity.username, "release-op-aaaaaaaa");
  assert.equal(identity.displayName, ACCOUNT_DISPLAY_NAME);
  assert.equal(identity.authEmail, `opentrack360-release-operator-${requestId}@opentrack360.invalid`);
  assert.equal(identity.userMetadata.opentrack_operator_request_id, requestId);
  assert.equal(identity.userMetadata.opentrack_username, identity.username);
  assert.throws(() => buildAccountIdentity("not-a-uuid"), /Invalid operator request id/);
});

test("promotion SQL is request-scoped and rejects altered identity or project values", () => {
  const sql = buildPromotionSql({
    requestId,
    operatorSubjectId,
    targetUserId,
    username: "release-op-aaaaaaaa",
    displayName: ACCOUNT_DISPLAY_NAME,
    projectRef: PROJECT_REF,
  });
  assert.match(sql, /private\.opentrack_operator_provision_admin/);
  assert.match(sql, new RegExp(requestId));
  assert.match(sql, new RegExp(operatorSubjectId));
  assert.match(sql, new RegExp(targetUserId));
  assert.throws(() => buildPromotionSql({
    requestId,
    operatorSubjectId,
    targetUserId,
    username: "existing-admin",
    displayName: ACCOUNT_DISPLAY_NAME,
    projectRef: PROJECT_REF,
  }), /outside the dedicated release scope/);
  assert.throws(() => buildPromotionSql({
    requestId,
    operatorSubjectId,
    targetUserId,
    username: "release-op-aaaaaaaa",
    displayName: ACCOUNT_DISPLAY_NAME,
    projectRef: "another-project",
  }), /Project reference/);
});

test("Supabase CLI JSON extraction handles logs and nested JSON strings", () => {
  const parsed = extractJsonObject('Connecting...\n{"rows":[{"result":{"ok":true,"message":"value with } brace"}}]}\nDone');
  assert.deepEqual(parsed, { rows: [{ result: { ok: true, message: "value with } brace" } }] });
  assert.throws(() => extractJsonObject("no JSON here"), /did not return JSON/);
  assert.throws(() => extractJsonObject('{"broken":'), /incomplete JSON/);
});
