import { randomBytes, randomUUID } from "node:crypto";
import { authFaultResponse, requireAdminCsrf, requireAdminSession } from "@/src/server/auth/member";
import { apiError, getAdminSupabase } from "@/src/server/auth/supabase";

type Context = { params: Promise<{ accountId: string }> };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export async function POST(request: Request, context: Context): Promise<Response> {
  try {
    requireAdminCsrf(request);
    const member = await requireAdminSession(request);
    const { accountId } = await context.params;
    if (!UUID.test(accountId)) return apiError(404, "not_found", "Not found");
    const admin = getAdminSupabase();
    const { data: accountData, error: accountError } = await admin.rpc("opentrack_admin_get_account", {
      p_account_id: accountId,
      p_actor_user_id: member.userId,
    });
    if (accountError) return apiError(503, "service_unavailable", "Account service unavailable");
    const accountEnvelope = accountData as { httpStatus?: number; body?: Record<string, unknown> } | null;
    if ((accountEnvelope?.httpStatus ?? 500) !== 200) return Response.json(accountEnvelope?.body ?? {}, { status: accountEnvelope?.httpStatus ?? 500, headers: { "Cache-Control": "no-store" } });
    const account = accountEnvelope?.body ?? {};
    const userId = typeof account.userId === "string" ? account.userId : "";
    if (!userId) return apiError(404, "not_found", "Not found");

    const requestId = randomUUID();
    const { data: beginData, error: beginError } = await admin.rpc("opentrack_admin_begin_reset", {
      p_request_id: requestId,
      p_account_id: accountId,
      p_actor_user_id: member.userId,
    });
    if (beginError) return apiError(503, "service_unavailable", "Password reset could not be started");
    const beginEnvelope = beginData as { httpStatus?: number; body?: unknown } | null;
    if ((beginEnvelope?.httpStatus ?? 500) !== 200) return Response.json(beginEnvelope?.body ?? {}, { status: beginEnvelope?.httpStatus ?? 500, headers: { "Cache-Control": "no-store" } });

    const tempPassword = randomBytes(32).toString("base64url");
    const { error: passwordError } = await admin.auth.admin.updateUserById(userId, { password: tempPassword });
    if (passwordError) {
      await admin.rpc("opentrack_admin_fail_reset", { p_request_id: requestId, p_account_id: accountId, p_actor_user_id: member.userId });
      return apiError(503, "reset_failed", "Password reset did not complete; start a new reset");
    }
    const { data: completedData, error: completeError } = await admin.rpc("opentrack_admin_complete_reset", {
      p_request_id: requestId,
      p_account_id: accountId,
      p_actor_user_id: member.userId,
    });
    if (completeError) {
      await admin.rpc("opentrack_admin_fail_reset", { p_request_id: requestId, p_account_id: accountId, p_actor_user_id: member.userId });
      return apiError(503, "reset_failed", "Password reset did not complete; start a new reset");
    }
    const completed = completedData as { httpStatus?: number; body?: Record<string, unknown> } | null;
    if ((completed?.httpStatus ?? 500) !== 200) {
      await admin.rpc("opentrack_admin_fail_reset", { p_request_id: requestId, p_account_id: accountId, p_actor_user_id: member.userId });
      return Response.json(completed?.body ?? {}, { status: completed?.httpStatus ?? 500, headers: { "Cache-Control": "no-store" } });
    }
    const completedBody = completed?.body ?? {};
    return Response.json({
      accountId,
      username: account.username,
      temporaryPassword: tempPassword,
      mustRotatePassword: true,
      sessionsRevoked: completedBody.sessionsRevoked === true,
      sharingStopped: completedBody.sharingStopped === true,
      sharingEpoch: completedBody.sharingEpoch ?? null,
    }, {
      headers: { "Cache-Control": "no-store" },
    });
  } catch (error) {
    return authFaultResponse(error);
  }
}
