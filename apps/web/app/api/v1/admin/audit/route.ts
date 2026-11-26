import { authFaultResponse, requireAdminSession } from "@/src/server/auth/member";
import { apiError, getAdminSupabase } from "@/src/server/auth/supabase";

export async function GET(request: Request): Promise<Response> {
  try {
    const member = await requireAdminSession(request);
    const url = new URL(request.url);
    const rawLimit = Number(url.searchParams.get("limit") ?? "100");
    const limit = Number.isInteger(rawLimit) && rawLimit > 0 && rawLimit <= 200 ? rawLimit : 100;
    const rawCursor = url.searchParams.get("cursor");
    if (rawCursor !== null && (!/^\d{1,19}$/.test(rawCursor) || BigInt(rawCursor) > 9_223_372_036_854_775_807n)) {
      return apiError(400, "invalid_request", "Invalid audit cursor");
    }
    const cursor = rawCursor;
    const { data, error } = await getAdminSupabase().rpc("opentrack_admin_list_audit", {
      p_actor_user_id: member.userId,
      p_limit: limit,
      p_cursor: cursor,
    });
    if (error) return apiError(503, "service_unavailable", "Account service unavailable");
    const result = data as { httpStatus?: number; body?: unknown } | null;
    return Response.json(result?.body ?? {}, {
      status: result?.httpStatus ?? 500,
      headers: { "Cache-Control": "no-store" },
    });
  } catch (error) {
    return authFaultResponse(error);
  }
}
