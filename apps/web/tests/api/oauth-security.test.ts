import assert from "node:assert/strict";
import test from "node:test";
import { jwtIssuedAt, jwtOAuthClientId, jwtSessionId } from "../../src/server/auth/member";
import {
  isAllowedOAuthRedirectUri,
  isAllowedOAuthRedirectUrl,
  isAllowedOAuthScope,
} from "../../src/server/auth/oauth";

function tokenWithClaims(claims: Record<string, unknown>): string {
  const header = Buffer.from(JSON.stringify({ alg: "none", typ: "JWT" })).toString("base64url");
  const payload = Buffer.from(JSON.stringify(claims)).toString("base64url");
  return `${header}.${payload}.fixture-signature`;
}

test("OAuth token claim helpers read only expected claim shapes", () => {
  const sessionId = "11111111-1111-4111-8111-111111111111";
  const token = tokenWithClaims({ session_id: sessionId, client_id: "public-client", iat: 1795824000 });

  assert.equal(jwtSessionId(token), sessionId);
  assert.equal(jwtOAuthClientId(token), "public-client");
  assert.equal(jwtIssuedAt(token), 1795824000);
  assert.equal(jwtOAuthClientId(tokenWithClaims({ client_id: 9 })), null);
  assert.equal(jwtIssuedAt(tokenWithClaims({ iat: "1795824000" })), null);
  assert.equal(jwtSessionId("not-a-jwt"), null);
});

test("OAuth consent accepts only the registered callback and OpenID scope", () => {
  assert.equal(isAllowedOAuthRedirectUri("https://track.srishanth.me/oauth/callback"), true);
  assert.equal(isAllowedOAuthRedirectUri("https://track.srishanth.me/oauth/callback?next=/"), false);
  assert.equal(isAllowedOAuthRedirectUri("http://track.srishanth.me/oauth/callback"), false);
  assert.equal(isAllowedOAuthRedirectUri("https://attacker.example/oauth/callback"), false);
  assert.equal(isAllowedOAuthScope("openid"), true);
  assert.equal(isAllowedOAuthScope("openid email"), false);
  assert.equal(isAllowedOAuthScope("email"), false);
});

test("OAuth consent redirects only to a code or denial on the exact callback", () => {
  assert.equal(isAllowedOAuthRedirectUrl("https://track.srishanth.me/oauth/callback?code=one-time&state=state"), true);
  assert.equal(isAllowedOAuthRedirectUrl("https://track.srishanth.me/oauth/callback?error=access_denied&state=state"), true);
  assert.equal(isAllowedOAuthRedirectUrl("https://track.srishanth.me/oauth/callback?error=access_denied&error_description=Denied&state=state"), true);
  assert.equal(isAllowedOAuthRedirectUrl("https://track.srishanth.me/oauth/callback?code=one-time&error=access_denied"), false);
  assert.equal(isAllowedOAuthRedirectUrl("https://track.srishanth.me/oauth/callback?error_description=Denied"), false);
  assert.equal(isAllowedOAuthRedirectUrl("https://track.srishanth.me/oauth/callback?code=one-time&next=https://attacker.example"), false);
  assert.equal(isAllowedOAuthRedirectUrl("https://user@track.srishanth.me/oauth/callback?code=one-time"), false);
  assert.equal(isAllowedOAuthRedirectUrl("https://track.srishanth.me/oauth/callback?code=one&code=two"), false);
  assert.equal(isAllowedOAuthRedirectUrl("https://attacker.example/oauth/callback?code=one-time"), false);
});
