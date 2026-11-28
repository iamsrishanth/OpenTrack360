import { configuredOAuthClientId, OPEN_TRACK_OAUTH_REDIRECT_URI, OPEN_TRACK_OAUTH_SCOPES } from "@/src/server/auth/oauth";
import { apiError } from "@/src/server/auth/supabase";

export async function GET(): Promise<Response> {
  const clientId = configuredOAuthClientId();
  const supabaseUrl = process.env.SUPABASE_URL?.trim().replace(/\/$/, "");
  if (!clientId || !supabaseUrl) return apiError(404, "not_found", "OAuth sign-in is not configured");

  const authBase = `${supabaseUrl}/auth/v1`;
  const origin = (process.env.NEXT_PUBLIC_SITE_URL ?? "https://track.srishanth.me").replace(/\/$/, "");
  return Response.json({
    issuer: authBase,
    authorizationEndpoint: `${authBase}/oauth/authorize`,
    tokenExchangeEndpoint: `${origin}/api/v1/auth/oauth/exchange`,
    refreshEndpoint: `${origin}/api/v1/auth/refresh`,
    clientId,
    redirectUri: OPEN_TRACK_OAUTH_REDIRECT_URI,
    responseType: "code",
    codeChallengeMethod: "S256",
    scopes: [...OPEN_TRACK_OAUTH_SCOPES],
  }, { headers: { "Cache-Control": "public, max-age=3600, must-revalidate" } });
}
