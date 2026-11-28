import { apiError, getAdminSupabase, getPublicSupabase, parseJsonObject } from "@/src/server/auth/supabase";
import { jwtIssuedAt, jwtOAuthClientId, jwtSessionId } from "@/src/server/auth/member";
import { configuredOAuthClientId } from "@/src/server/auth/oauth";

async function refreshOAuthSession(refreshToken: string, clientId: string): Promise<Response> {
  const expectedClientId = configuredOAuthClientId();
  const supabaseUrl = process.env.SUPABASE_URL?.trim().replace(/\/$/, "");
  if (!expectedClientId || clientId !== expectedClientId || !supabaseUrl) {
    return apiError(401, "unauthorized", "Unauthorized");
  }

  try {
    const response = await fetch(`${supabaseUrl}/auth/v1/oauth/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "refresh_token", refresh_token: refreshToken, client_id: clientId }),
      cache: "no-store",
    });
    const payload = await response.json().catch(() => null) as Record<string, unknown> | null;
    const accessToken = typeof payload?.access_token === "string" ? payload.access_token : "";
    const nextRefreshToken = typeof payload?.refresh_token === "string" ? payload.refresh_token : "";
    if (!response.ok || !accessToken || !nextRefreshToken || payload?.token_type !== "bearer" || jwtOAuthClientId(accessToken) !== clientId) {
      return apiError(401, "unauthorized", "Unauthorized");
    }

    const sessionId = jwtSessionId(accessToken);
    const issuedAt = jwtIssuedAt(accessToken);
    if (!sessionId || issuedAt === null) return apiError(401, "unauthorized", "Unauthorized");

    const admin = getAdminSupabase();
    const { data: userResult, error: userError } = await admin.auth.getUser(accessToken);
    if (userError || !userResult.user) return apiError(401, "unauthorized", "Unauthorized");
    const { data: registered, error: registerError } = await admin.rpc("opentrack_register_oauth_session", {
      p_user_id: userResult.user.id,
      p_session_id: sessionId,
      p_token_issued_at: issuedAt,
    });
    const member = registered as Record<string, unknown> | null;
    if (registerError || member?.allowed !== true) {
      await admin.auth.admin.signOut(accessToken, "local").catch(() => undefined);
      return apiError(401, "unauthorized", "Unauthorized");
    }

    return Response.json({
      accessToken,
      refreshToken: nextRefreshToken,
      tokenType: "Bearer",
      expiresInSeconds: typeof payload.expires_in === "number" ? payload.expires_in : 3600,
      user: {
        id: userResult.user.id,
        username: String(member.username ?? ""),
        mustRotatePassword: member.mustRotatePassword === true,
      },
    }, { headers: { "Cache-Control": "no-store" } });
  } catch {
    return apiError(401, "unauthorized", "Unauthorized");
  }
}

export async function POST(request: Request): Promise<Response> {
  try {
    const body = await parseJsonObject(request);
    const refreshToken = typeof body?.refreshToken === "string" ? body.refreshToken : "";
    if (refreshToken.length < 20 || refreshToken.length > 4096) return apiError(401, "unauthorized", "Unauthorized");
    if (body?.clientId !== undefined && typeof body.clientId !== "string") return apiError(400, "invalid_request", "Invalid refresh request");
    if (typeof body?.clientId === "string") {
      if (body.clientId.length < 1 || body.clientId.length > 128) return apiError(400, "invalid_request", "Invalid refresh request");
      return refreshOAuthSession(refreshToken, body.clientId);
    }
    const client = getPublicSupabase();
    const { data, error } = await client.auth.refreshSession({ refresh_token: refreshToken });
    if (error || !data.session || !data.user) return apiError(401, "unauthorized", "Unauthorized");

    const sessionId = jwtSessionId(data.session.access_token);
    if (!sessionId) return apiError(401, "unauthorized", "Unauthorized");
    const admin = getAdminSupabase();
    const { data: registered, error: registerError } = await admin.rpc("opentrack_register_member_session", {
      p_user_id: data.user.id,
      p_session_id: sessionId,
    });
    const member = registered as Record<string, unknown> | null;
    if (registerError || member?.allowed !== true) {
      await admin.auth.admin.signOut(data.session.access_token, "local").catch(() => undefined);
      return apiError(401, "unauthorized", "Unauthorized");
    }
    return Response.json({
      accessToken: data.session.access_token,
      refreshToken: data.session.refresh_token,
      tokenType: "Bearer",
      expiresInSeconds: data.session.expires_in ?? 3600,
      user: {
        id: data.user.id,
        username: String(member.username ?? ""),
        mustRotatePassword: member.mustRotatePassword === true,
      },
    }, { headers: { "Cache-Control": "no-store" } });
  } catch {
    return apiError(500, "internal_error", "Request could not be completed");
  }
}
