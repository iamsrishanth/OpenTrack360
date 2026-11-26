import { authFaultResponse, readAdminCookie, requireAdminCsrf } from "@/src/server/auth/member";
import { apiError, getAdminSupabase, getPublicSupabase } from "@/src/server/auth/supabase";
import { jwtSessionId } from "@/src/server/auth/member";

const COOKIE_PATH = "/api/v1/admin";

function setCookie(name: string, value: string, maxAge: number): string {
  return `${name}=${encodeURIComponent(value)}; Path=${COOKIE_PATH}; Max-Age=${maxAge}; Secure; HttpOnly; SameSite=Strict`;
}

export async function POST(request: Request): Promise<Response> {
  try {
    requireAdminCsrf(request);
    const refreshToken = readAdminCookie(request, "ot_admin_refresh");
    if (!refreshToken) return apiError(401, "unauthorized", "Unauthorized");
    const auth = getPublicSupabase();
    const { data, error } = await auth.auth.refreshSession({ refresh_token: refreshToken });
    if (error || !data.session || !data.user) return apiError(401, "unauthorized", "Unauthorized");
    const sessionId = jwtSessionId(data.session.access_token);
    if (!sessionId) return apiError(401, "unauthorized", "Unauthorized");
    const admin = getAdminSupabase();
    const { data: registration, error: registerError } = await admin.rpc("opentrack_register_member_session", {
      p_user_id: data.user.id,
      p_session_id: sessionId,
    });
    const member = registration as Record<string, unknown> | null;
    if (registerError || member?.allowed !== true || member.isPlatformAdmin !== true) {
      await admin.auth.admin.signOut(data.session.access_token, "local").catch(() => undefined);
      return apiError(401, "unauthorized", "Unauthorized");
    }
    const headers = new Headers({ "Cache-Control": "no-store" });
    headers.append("Set-Cookie", setCookie("ot_admin_access", data.session.access_token, Math.min(data.session.expires_in ?? 3600, 3600)));
    headers.append("Set-Cookie", setCookie("ot_admin_refresh", data.session.refresh_token, 30 * 24 * 60 * 60));
    return Response.json({ authenticated: true, username: member.username, requiresPasswordRotation: member.mustRotatePassword === true }, { headers });
  } catch (error) {
    return authFaultResponse(error);
  }
}
