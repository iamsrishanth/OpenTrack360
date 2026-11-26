import { authFaultResponse, requireMember } from "@/src/server/auth/member";
import { getAdminSupabase, parseJsonObject } from "@/src/server/auth/supabase";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export async function POST(request: Request): Promise<Response> {
  try {
    const member = await requireMember(request);
    const body = await parseJsonObject(request);
    const clientRequestId = typeof body?.clientRequestId === "string" ? body.clientRequestId : "";
    if (!UUID.test(clientRequestId)) {
      return Response.json({ error: { code: "invalid_request", message: "A valid clientRequestId is required" } }, {
        status: 400,
        headers: { "Cache-Control": "no-store" },
      });
    }
    const { data, error } = await getAdminSupabase().rpc("opentrack_stop_sharing", {
      p_user_id: member.userId,
      p_session_id: member.sessionId,
      p_client_request_id: clientRequestId,
    });
    if (error) return Response.json({ error: { code: "service_unavailable", message: "Sharing service unavailable" } }, {
      status: 503,
      headers: { "Cache-Control": "no-store" },
    });
    const result = data as { httpStatus?: number; body?: unknown } | null;
    return Response.json(result?.body ?? {}, { status: result?.httpStatus ?? 500, headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    return authFaultResponse(error);
  }
}
