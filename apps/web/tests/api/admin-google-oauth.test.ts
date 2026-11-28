import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import {
  ADMIN_GOOGLE_CALLBACK_PATH,
  ADMIN_GOOGLE_PKCE_COOKIE,
  adminGoogleCallbackUrl,
  buildGoogleAuthorizationUrl,
  configuredAdminGoogleEmail,
  createGooglePkcePair,
  googleProviderEnabledInSettings,
  isAdminGoogleProviderAvailable,
  isAllowedAdminGoogleOrigin,
  isAuthorizedGoogleAdminIdentity,
  readAdminGooglePkceVerifier,
} from "../../src/server/auth/admin-google-oauth";

function preserveEnv(names: string[]) {
  const prior = new Map(names.map((name) => [name, process.env[name]]));
  return () => {
    for (const [name, value] of prior) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  };
}

test("Google provider availability is read from the Supabase Auth settings payload", () => {
  assert.equal(googleProviderEnabledInSettings({ external: { google: { enabled: true } } }), true);
  assert.equal(googleProviderEnabledInSettings({ external: { google: { enabled: false } } }), false);
  assert.equal(googleProviderEnabledInSettings({ external: { google: { enabled: "true" } } }), false);
  assert.equal(googleProviderEnabledInSettings(null), false);
});

test("admin Google PKCE uses a random 256-bit verifier and S256 challenge", () => {
  const first = createGooglePkcePair();
  const second = createGooglePkcePair();
  assert.match(first.verifier, /^[A-Za-z0-9_-]{43}$/);
  assert.notEqual(first.verifier, second.verifier);
  assert.equal(first.challenge, createHash("sha256").update(first.verifier).digest("base64url"));
  assert.match(first.challenge, /^[A-Za-z0-9_-]{43}$/);
});

test("Google authorization URL is fixed to the Supabase provider and admin callback", () => {
  const restore = preserveEnv(["NODE_ENV", "VERCEL_ENV"]);
  try {
    Reflect.set(process.env, "NODE_ENV", "test");
    delete process.env.VERCEL_ENV;
    const url = new URL(buildGoogleAuthorizationUrl({
      supabaseUrl: "https://project-ref.supabase.co",
      callbackUrl: `https://track.srishanth.me${ADMIN_GOOGLE_CALLBACK_PATH}`,
      challenge: "c".repeat(43),
    }));
    assert.equal(url.origin, "https://project-ref.supabase.co");
    assert.equal(url.pathname, "/auth/v1/authorize");
    assert.equal(url.searchParams.get("provider"), "google");
    assert.equal(url.searchParams.get("redirect_to"), `https://track.srishanth.me${ADMIN_GOOGLE_CALLBACK_PATH}`);
    assert.equal(url.searchParams.get("code_challenge_method"), "s256");
    assert.equal(url.searchParams.get("scopes"), "openid email profile");
    assert.equal(url.searchParams.get("prompt"), "select_account");
    assert.equal(url.searchParams.has("client_secret"), false);
    assert.throws(() => buildGoogleAuthorizationUrl({
      supabaseUrl: "https://project-ref.supabase.co",
      callbackUrl: "https://attacker.example/callback",
      challenge: "c".repeat(43),
    }));
  } finally {
    restore();
  }
});

test("Google admin callback origin rejects Vercel previews", () => {
  assert.equal(isAllowedAdminGoogleOrigin("https://track.srishanth.me", { NODE_ENV: "production", VERCEL_ENV: "production" }), true);
  assert.equal(isAllowedAdminGoogleOrigin("https://track.srishanth.me", { NODE_ENV: "production", VERCEL_ENV: "preview" }), false);
  assert.equal(adminGoogleCallbackUrl("https://track.srishanth.me/admin", { NODE_ENV: "production", VERCEL_ENV: "production" }),
    `https://track.srishanth.me${ADMIN_GOOGLE_CALLBACK_PATH}`);
  assert.equal(adminGoogleCallbackUrl("https://preview.vercel.app/admin", { NODE_ENV: "production", VERCEL_ENV: "preview" }), null);
  assert.equal(adminGoogleCallbackUrl("http://localhost:3000/admin", { NODE_ENV: "development", VERCEL_ENV: "development" }),
    `http://localhost:3000${ADMIN_GOOGLE_CALLBACK_PATH}`);
});

test("only the configured verified Google identity can enter the admin callback", () => {
  const allowedEmail = "google-admin@example.com";
  const googleUser = {
    email: allowedEmail,
    email_confirmed_at: "2026-11-28T00:00:00Z",
    identities: [{ provider: "google", identity_data: { email: allowedEmail, email_verified: true } }],
  };
  assert.equal(isAuthorizedGoogleAdminIdentity(googleUser, allowedEmail), true);
  assert.equal(isAuthorizedGoogleAdminIdentity({ ...googleUser, email: "other@gmail.com" }, allowedEmail), false);
  assert.equal(isAuthorizedGoogleAdminIdentity({ ...googleUser, email_confirmed_at: null }, allowedEmail), false);
  assert.equal(isAuthorizedGoogleAdminIdentity({ ...googleUser, identities: [{ provider: "email" }] }, allowedEmail), false);
  assert.equal(isAuthorizedGoogleAdminIdentity({
    ...googleUser,
    identities: [{ provider: "google", identity_data: { email: allowedEmail, email_verified: false } }],
  }, allowedEmail), false);
});

test("Google admin email configuration is normalized and never inferred", () => {
  const restore = preserveEnv(["OPENTRACK_ADMIN_GOOGLE_EMAIL"]);
  try {
    delete process.env.OPENTRACK_ADMIN_GOOGLE_EMAIL;
    assert.equal(configuredAdminGoogleEmail(), null);
    process.env.OPENTRACK_ADMIN_GOOGLE_EMAIL = "  Admin@Example.com  ";
    assert.equal(configuredAdminGoogleEmail(), "admin@example.com");
  } finally {
    restore();
  }
});

test("admin Google availability requires provider, allowlisted email, and production environment", async () => {
  const restore = preserveEnv([
    "OPENTRACK_ADMIN_GOOGLE_EMAIL", "SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY", "VERCEL_ENV", "NODE_ENV",
  ]);
  const previousFetch = globalThis.fetch;
  try {
    process.env.OPENTRACK_ADMIN_GOOGLE_EMAIL = "admin@example.com";
    process.env.SUPABASE_URL = "https://project-ref.supabase.co";
    process.env.SUPABASE_PUBLISHABLE_KEY = "public-key-fixture";
    process.env.VERCEL_ENV = "production";
    Reflect.set(process.env, "NODE_ENV", "production");
    globalThis.fetch = (async () => new Response(JSON.stringify({ external: { google: { enabled: true } } }), { status: 200 })) as typeof fetch;
    assert.equal(await isAdminGoogleProviderAvailable(), true);
    globalThis.fetch = (async () => new Response(JSON.stringify({ external: { google: { enabled: false } } }), { status: 200 })) as typeof fetch;
    assert.equal(await isAdminGoogleProviderAvailable(), false);
    process.env.VERCEL_ENV = "preview";
    assert.equal(await isAdminGoogleProviderAvailable(), false);
  } finally {
    globalThis.fetch = previousFetch;
    restore();
  }
});

test("PKCE callback cookie parser accepts one bounded URL-safe verifier", () => {
  const verifier = "v".repeat(43);
  const valid = new Request("https://track.srishanth.me/api/v1/admin/google/callback", {
    headers: { cookie: `unrelated=x; ${ADMIN_GOOGLE_PKCE_COOKIE}=${verifier}` },
  });
  const invalid = new Request("https://track.srishanth.me/api/v1/admin/google/callback", {
    headers: { cookie: `${ADMIN_GOOGLE_PKCE_COOKIE}=bad value` },
  });
  assert.equal(readAdminGooglePkceVerifier(valid), verifier);
  assert.equal(readAdminGooglePkceVerifier(invalid), null);
});
