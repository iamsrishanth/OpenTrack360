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
    const { data, error } = await admin.rpc("opentrack_admin_disable_account", {
      p_account_id: accountId,
      p_actor_user_id: member.userId,
    });
    if (error) return apiError(503, "service_unavailable", "Account service unavailable");
    const result = data as { httpStatus?: number; body?: Record<string, unknown> } | null;
    if ((result?.httpStatus ?? 500) !== 200) return Response.json(result?.body ?? {}, { status: result?.httpStatus ?? 500, headers: { "Cache-Control": "no-store" } });

    let authBanApplied = false;
    if (typeof account.userId === "string") {
      const { error: banError } = await admin.auth.admin.updateUserById(account.userId, { ban_duration: "876000h" });
      authBanApplied = !banError;
    }
    return Response.json({ ...(result?.body ?? { disabled: true }), authProviderBanApplied: authBanApplied }, {
      headers: { "Cache-Control": "no-store" },
    });
  } catch (error) {
    return authFaultResponse(error);
  }
}
