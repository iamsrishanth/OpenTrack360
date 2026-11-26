import { getAdminSupabase } from "@/src/server/auth/supabase";
import { authFaultResponse, requireMember } from "@/src/server/auth/member";

export async function GET(request: Request): Promise<Response> {
  try {
    const member = await requireMember(request);
    const admin = getAdminSupabase();
    const { data, error } = await admin.rpc("opentrack_get_member_profile", {
      p_user_id: member.userId,
      p_session_id: member.sessionId,
    });
    if (error) return Response.json({ error: { code: "service_unavailable", message: "Profile service unavailable" } }, { status: 503, headers: { "Cache-Control": "no-store" } });
    const result = data as { httpStatus?: number; body?: unknown } | null;
    return Response.json(result?.body ?? {}, { status: result?.httpStatus ?? 500, headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    return authFaultResponse(error);
  }
}
