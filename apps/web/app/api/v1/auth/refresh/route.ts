import { apiError, getAdminSupabase, getPublicSupabase, parseJsonObject } from "@/src/server/auth/supabase";
import { jwtSessionId } from "@/src/server/auth/member";

export async function POST(request: Request): Promise<Response> {
  try {
    const body = await parseJsonObject(request);
    const refreshToken = typeof body?.refreshToken === "string" ? body.refreshToken : "";
    if (refreshToken.length < 20 || refreshToken.length > 4096) return apiError(401, "unauthorized", "Unauthorized");
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
