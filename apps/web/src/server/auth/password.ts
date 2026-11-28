import { AuthFault, authFaultResponse, requireMember } from "./member";
import { apiError, getAdminSupabase, getPublicSupabase, parseJsonObject } from "./supabase";

export async function changeMemberPassword(request: Request): Promise<Response> {
  try {
    const member = await requireMember(request, { allowPasswordRotation: true });
    const body = await parseJsonObject(request);
    const currentPassword = typeof body?.currentPassword === "string" ? body.currentPassword : "";
    const newPassword = typeof body?.newPassword === "string" ? body.newPassword : "";
    if (!currentPassword || newPassword.length < 12 || newPassword.length > 128 || currentPassword === newPassword) {
      return apiError(400, "invalid_request", "Choose a new password of at least 12 characters.");
    }

    const admin = getAdminSupabase();
    const { data: loginAllowed, error: limitError } = await admin.rpc("opentrack_login_allowed", {
      p_username_normalized: member.username,
    });
    if (limitError) return apiError(503, "service_unavailable", "Authentication service unavailable");
    if (loginAllowed !== true) return apiError(429, "rate_limited", "Too many attempts. Try again later.");

    const { data: email, error: emailError } = await admin.rpc("opentrack_lookup_auth_email_by_user", {
      p_user_id: member.userId,
    });
    if (emailError || typeof email !== "string") return apiError(503, "service_unavailable", "Authentication service unavailable");
    const publicClient = getPublicSupabase();
    const { data: verified, error: passwordError } = await publicClient.auth.signInWithPassword({
      email,
      password: currentPassword,
    });
    if (passwordError || verified.user?.id !== member.userId) {
      return apiError(401, "unauthorized", "Current password is incorrect");
    }

    const { error: updateError } = await admin.auth.admin.updateUserById(member.userId, { password: newPassword });
    if (updateError) return apiError(400, "invalid_request", "Password could not be changed");

    const { data: changed, error: stateError } = await admin.rpc("opentrack_rotate_password_state", {
      p_user_id: member.userId,
      p_session_id: member.sessionId,
    });
    if (stateError || (changed as Record<string, unknown> | null)?.changed !== true) {
      await admin.auth.admin.signOut(
        /^Bearer\s+(.+)$/i.exec(request.headers.get("authorization") ?? "")?.[1] ?? "",
        "global",
      ).catch(() => undefined);
      throw new AuthFault(503, "service_unavailable", "Session update failed; sign in again");
    }

    const accessToken = /^Bearer\s+(.+)$/i.exec(request.headers.get("authorization") ?? "")?.[1];
    if (accessToken) await admin.auth.admin.signOut(accessToken, "global").catch(() => undefined);
    return Response.json({ changed: true, sessionsRevoked: true, reauthenticationRequired: true }, {
      headers: { "Cache-Control": "no-store" },
    });
  } catch (error) {
    return authFaultResponse(error);
  }
}
