import { getAdminSupabase, apiError, parseJsonObject } from "@/src/server/auth/supabase";
import { jwtIssuedAt, jwtOAuthClientId, jwtSessionId } from "@/src/server/auth/member";
import { configuredOAuthClientId, OPEN_TRACK_OAUTH_REDIRECT_URI } from "@/src/server/auth/oauth";

const NO_STORE = { "Cache-Control": "no-store" };
const unauthorized = () => apiError(401, "unauthorized", "OAuth sign-in could not be completed");

export async function POST(request: Request): Promise<Response> {
  try {
    const body = await parseJsonObject(request);
    const code = typeof body?.code === "string" ? body.code : "";
    const codeVerifier = typeof body?.codeVerifier === "string" ? body.codeVerifier : "";
    if (code.length < 1 || code.length > 4096 || /[\u0000-\u001f\u007f]/.test(code) ||
        !/^[A-Za-z0-9._~-]{43,128}$/.test(codeVerifier)) {
      return apiError(400, "invalid_request", "Invalid OAuth authorization response");
    }

    const clientId = configuredOAuthClientId();
    const supabaseUrl = process.env.SUPABASE_URL?.trim().replace(/\/$/, "");
    if (!clientId || !supabaseUrl) return apiError(503, "oauth_unavailable", "OpenTrack360 sign-in is temporarily unavailable");

    const tokenResponse = await fetch(`${supabaseUrl}/auth/v1/oauth/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        code,
        redirect_uri: OPEN_TRACK_OAUTH_REDIRECT_URI,
        client_id: clientId,
        code_verifier: codeVerifier,
      }),
      cache: "no-store",
    });
    const tokenPayload = await tokenResponse.json().catch(() => null) as Record<string, unknown> | null;
    const accessToken = typeof tokenPayload?.access_token === "string" ? tokenPayload.access_token : "";
    const refreshToken = typeof tokenPayload?.refresh_token === "string" ? tokenPayload.refresh_token : "";
    if (!tokenResponse.ok || !accessToken || !refreshToken || tokenPayload?.token_type !== "bearer") return unauthorized();

    const sessionId = jwtSessionId(accessToken);
    const issuedAt = jwtIssuedAt(accessToken);
    if (!sessionId || issuedAt === null || jwtOAuthClientId(accessToken) !== clientId) return unauthorized();

    const admin = getAdminSupabase();
    const { data: userResult, error: userError } = await admin.auth.getUser(accessToken);
    if (userError || !userResult.user) return unauthorized();

    const claimsPart = accessToken.split(".")[1];
    const claims = claimsPart ? JSON.parse(Buffer.from(claimsPart, "base64url").toString("utf8")) as Record<string, unknown> : {};
    if (claims.sub !== userResult.user.id) return unauthorized();

    const { data: registered, error: registerError } = await admin.rpc("opentrack_register_oauth_session", {
      p_user_id: userResult.user.id,
      p_session_id: sessionId,
      p_token_issued_at: issuedAt,
    });
    const member = registered as Record<string, unknown> | null;
    if (registerError || member?.allowed !== true) {
      await admin.auth.admin.signOut(accessToken, "local").catch(() => undefined);
      return unauthorized();
    }

    return Response.json({
      accessToken,
      refreshToken,
      tokenType: "Bearer",
      expiresInSeconds: typeof tokenPayload.expires_in === "number" ? tokenPayload.expires_in : 3600,
      user: {
        id: userResult.user.id,
        username: String(member.username ?? ""),
        mustRotatePassword: member.mustRotatePassword === true,
      },
    }, { headers: NO_STORE });
  } catch {
    return unauthorized();
  }
}
