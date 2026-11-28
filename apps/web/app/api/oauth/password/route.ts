import { AuthFault, authFaultResponse, requireSameOrigin } from "@/src/server/auth/member";
import { changeMemberPassword } from "@/src/server/auth/password";
import { oauthCsrfFault, oauthResponse, readOAuthMember } from "@/src/server/auth/oauth";
import { apiError, parseJsonObject } from "@/src/server/auth/supabase";

export async function POST(request: Request): Promise<Response> {
  try {
    requireSameOrigin(request);
    const { supabase, session, member } = await readOAuthMember(request);
    if (!session || !member) return oauthResponse({ error: { code: "unauthorized", message: "Sign in to continue" } }, 401);
    if (!member.mustRotatePassword) return apiError(409, "rotation_not_required", "Sign in again before continuing");

    const body = await parseJsonObject(request);
    const currentPassword = typeof body?.currentPassword === "string" ? body.currentPassword : "";
    const newPassword = typeof body?.newPassword === "string" ? body.newPassword : "";
    if (!currentPassword || newPassword.length < 12 || newPassword.length > 128 || currentPassword === newPassword) {
      return apiError(400, "invalid_request", "Choose a new password of at least 12 characters.");
    }

    const headers = new Headers({
      authorization: `Bearer ${session.access_token}`,
      "content-type": "application/json",
    });
    const result = await changeMemberPassword(new Request(new URL("/api/v1/me/password", request.url), {
      method: "POST",
      headers,
      body: JSON.stringify({ currentPassword, newPassword }),
    }));
    if (result.ok) await supabase.auth.signOut({ scope: "local" }).catch(() => undefined);
    return result;
  } catch (error) {
    if (error instanceof AuthFault && error.status === 403) return authFaultResponse(oauthCsrfFault());
    return authFaultResponse(error);
  }
}
