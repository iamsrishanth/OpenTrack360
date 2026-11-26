import { apiError, getAdminSupabase, getPublicSupabase, parseJsonObject } from "@/src/server/auth/supabase";
import { AuthFault, authFaultResponse, requireMember } from "@/src/server/auth/member";

export async function POST(request: Request): Promise<Response> {
  try {
    const body = await parseJsonObject(request);
    const refreshToken = typeof body?.refreshToken === "string" ? body.refreshToken : "";
    if (refreshToken.length < 20 || refreshToken.length > 4096) return apiError(400, "invalid_request", "Invalid refresh token");
    const member = await requireMember(request, { allowPasswordRotation: true });
    const accessToken = /^Bearer\s+(.+)$/i.exec(request.headers.get("authorization") ?? "")?.[1];
    if (!accessToken) throw new AuthFault(401, "unauthorized", "Unauthorized");

    const auth = getPublicSupabase();
    await auth.auth.setSession({ access_token: accessToken, refresh_token: refreshToken });
    await auth.auth.signOut({ scope: "local" }).catch(() => undefined);
    const admin = getAdminSupabase();
    const { error } = await admin.rpc("opentrack_revoke_member_session", {
      p_user_id: member.userId,
      p_session_id: member.sessionId,
    });
    if (error) return apiError(503, "service_unavailable", "Authentication service unavailable");
    return Response.json({ revoked: true }, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    return authFaultResponse(error);
  }
}
