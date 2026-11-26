import { apiError, getAdminSupabase, getPublicSupabase, normalizeUsername, parseJsonObject } from "@/src/server/auth/supabase";
import { jwtSessionId } from "@/src/server/auth/member";

const NO_STORE = { "Cache-Control": "no-store" };
const genericUnauthorized = () => apiError(401, "unauthorized", "Invalid username or password");

export async function POST(request: Request): Promise<Response> {
  try {
    const body = await parseJsonObject(request);
    const username = normalizeUsername(body?.username);
    const password = typeof body?.password === "string" ? body.password : "";
    if (!username || password.length < 1 || password.length > 128) return genericUnauthorized();

    const admin = getAdminSupabase();
    const { data: allowed, error: rateLimitError } = await admin.rpc("opentrack_login_allowed", {
      p_username_normalized: username,
    });
    if (rateLimitError) return apiError(503, "service_unavailable", "Authentication service unavailable");
    if (allowed !== true) return apiError(429, "rate_limited", "Too many sign-in attempts. Try again later.");

    const { data: identity, error: lookupError } = await admin.rpc("opentrack_lookup_login", {
      p_username_normalized: username,
    });
    if (lookupError) return apiError(503, "service_unavailable", "Authentication service unavailable");
    const internal = identity as Record<string, unknown> | null;
    const email = typeof internal?.authEmail === "string" ? internal.authEmail : "not-a-member@invalid.invalid";
    const client = getPublicSupabase();
    const { data, error } = await client.auth.signInWithPassword({ email, password });
    if (error || !data.session || !data.user || internal?.disabled === true || internal?.userId !== data.user.id) {
      return genericUnauthorized();
    }

    const sessionId = jwtSessionId(data.session.access_token);
    if (!sessionId) {
      await client.auth.signOut({ scope: "local" }).catch(() => undefined);
      return genericUnauthorized();
    }
    const { data: registered, error: registerError } = await admin.rpc("opentrack_register_member_session", {
      p_user_id: data.user.id,
      p_session_id: sessionId,
    });
    const member = registered as Record<string, unknown> | null;
    if (registerError || member?.allowed !== true) {
      await admin.auth.admin.signOut(data.session.access_token, "local").catch(() => undefined);
      return genericUnauthorized();
    }

    return Response.json({
      accessToken: data.session.access_token,
      refreshToken: data.session.refresh_token,
      tokenType: "Bearer",
      expiresInSeconds: data.session.expires_in ?? 3600,
      user: {
        id: data.user.id,
        username: String(member.username ?? username),
        mustRotatePassword: member.mustRotatePassword === true,
      },
    }, { status: 200, headers: NO_STORE });
  } catch {
    return apiError(500, "internal_error", "Request could not be completed");
  }
}
