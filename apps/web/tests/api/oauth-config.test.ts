import assert from "node:assert/strict";
import test from "node:test";
import { GET } from "../../app/api/v1/auth/oauth-config/route";

function preserveEnv(names: string[]) {
  const prior = new Map(names.map((name) => [name, process.env[name]]));
  return () => {
    for (const [name, value] of prior) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  };
}

test("OAuth client configuration stays unavailable until a public client is registered", async () => {
  const restore = preserveEnv(["SUPABASE_OAUTH_ANDROID_CLIENT_ID", "SUPABASE_URL"]);
  delete process.env.SUPABASE_OAUTH_ANDROID_CLIENT_ID;
  delete process.env.SUPABASE_URL;
  try {
    const response = await GET();
    assert.equal(response.status, 404);
    assert.equal((await response.json()).error.code, "not_found");
  } finally {
    restore();
  }
});

test("OAuth client configuration publishes only the public PKCE settings", async () => {
  const restore = preserveEnv(["SUPABASE_OAUTH_ANDROID_CLIENT_ID", "SUPABASE_URL", "NEXT_PUBLIC_SITE_URL"]);
  process.env.SUPABASE_OAUTH_ANDROID_CLIENT_ID = "public-client-fixture";
  process.env.SUPABASE_URL = "https://project-ref.supabase.co/";
  process.env.NEXT_PUBLIC_SITE_URL = "https://track.srishanth.me/";
  try {
    const response = await GET();
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      issuer: "https://project-ref.supabase.co/auth/v1",
      authorizationEndpoint: "https://project-ref.supabase.co/auth/v1/oauth/authorize",
      tokenExchangeEndpoint: "https://track.srishanth.me/api/v1/auth/oauth/exchange",
      refreshEndpoint: "https://track.srishanth.me/api/v1/auth/refresh",
      clientId: "public-client-fixture",
      redirectUri: "https://track.srishanth.me/oauth/callback",
      responseType: "code",
      codeChallengeMethod: "S256",
      scopes: ["openid"],
    });
  } finally {
    restore();
  }
});
