import { AuthFault, authFaultResponse, jwtSessionId, requireMember, requireSameOrigin } from "@/src/server/auth/member";
import { createOAuthCookieClient, oauthCsrfFault, oauthResponse, readOAuthMember } from "@/src/server/auth/oauth";
import { apiError, getAdminSupabase, getPublicSupabase, normalizeUsername, parseJsonObject } from "@/src/server/auth/supabase";

const genericUnauthorized = () => apiError(401, "unauthorized", "Invalid username or password");

export async function GET(request: Request): Promise<Response> {
  try {
    const { member } = await readOAuthMember(request);
    if (!member) return oauthResponse({ authenticated: false }, 401);
    return oauthResponse({
      authenticated: true,
      username: member.username,
      mustRotatePassword: member.mustRotatePassword,
    });
  } catch (error) {
    return authFaultResponse(error);
  }
}

export async function POST(request: Request): Promise<Response> {
  try {
    requireSameOrigin(request);
    const body = await parseJsonObject(request);
    const username = normalizeUsername(body?.username);
    const password = typeof body?.password === "string" ? body.password : "";
    if (!username || password.length < 1 || password.length > 128) return genericUnauthorized();

    const cookieClient = await createOAuthCookieClient();
    const { data: currentSession } = await cookieClient.auth.getSession();
    if (currentSession.session) {
      try {
        const headers = new Headers(request.headers);
        headers.set("authorization", `Bearer ${currentSession.session.access_token}`);
        await requireMember(new Request(request.url, { method: "GET", headers }), { allowPasswordRotation: true });
        return apiError(409, "already_signed_in", "Sign out before switching accounts");
      } catch (error) {
        if (error instanceof AuthFault && error.status === 503) return authFaultResponse(error);
        await cookieClient.auth.signOut({ scope: "local" }).catch(() => undefined);
      }
    }

    const admin = getAdminSupabase();
    const { data: allowed, error: rateLimitError } = await admin.rpc("opentrack_login_allowed", {
      p_username_normalized: username,
    });
    if (rateLimitError) return apiError(503, "service_unavailable", "Authentication service unavailable");
    if (allowed !== true) return apiError(429, "rate_limited", "Too many sign-in attempts. Try again later.");

    const { data: identity, error: lookupError } = await admin.rpc("opentrack_lookup_login", {
      p_username_normalized: username,
    });
    if (lookupError) return apiError(503, "service_unavailable", "Authentication service unavailable");
    const internal = identity as Record<string, unknown> | null;
    const email = typeof internal?.authEmail === "string" ? internal.authEmail : "not-a-member@invalid.invalid";
    const publicClient = getPublicSupabase();
    const { data, error } = await publicClient.auth.signInWithPassword({ email, password });
    if (error || !data.session || !data.user || internal?.disabled === true || internal?.userId !== data.user.id) {
      if (data.session?.access_token) await admin.auth.admin.signOut(data.session.access_token, "local").catch(() => undefined);
      return genericUnauthorized();
    }

    const sessionId = jwtSessionId(data.session.access_token);
    if (!sessionId) {
      await admin.auth.admin.signOut(data.session.access_token, "local").catch(() => undefined);
      return genericUnauthorized();
    }

    const { data: sessionRegistration, error: sessionError } = await admin.rpc("opentrack_register_member_session", {
      p_user_id: data.user.id,
      p_session_id: sessionId,
    });
    const member = sessionRegistration as Record<string, unknown> | null;
    if (sessionError || member?.allowed !== true) {
      await admin.auth.admin.signOut(data.session.access_token, "local").catch(() => undefined);
      return genericUnauthorized();
    }

    const { error: cookieError } = await cookieClient.auth.setSession({
      access_token: data.session.access_token,
      refresh_token: data.session.refresh_token,
    });
    if (cookieError) {
      try {
        await admin.rpc("opentrack_revoke_member_session", { p_user_id: data.user.id, p_session_id: sessionId });
      } catch {
        // Keep the sign-in response generic if rollback cleanup is temporarily unavailable.
      }
      await admin.auth.admin.signOut(data.session.access_token, "local").catch(() => undefined);
      return apiError(503, "service_unavailable", "Authentication service unavailable");
    }

    return oauthResponse({
      authenticated: true,
      username: String(member.username ?? username),
      mustRotatePassword: member.mustRotatePassword === true,
    });
  } catch (error) {
    if (error instanceof AuthFault && error.status === 403) return authFaultResponse(oauthCsrfFault());
    return authFaultResponse(error);
  }
}

export async function DELETE(request: Request): Promise<Response> {
  try {
    requireSameOrigin(request);
    const cookieClient = await createOAuthCookieClient();
    const { data } = await cookieClient.auth.getSession();
    if (data.session) {
      try {
        const headers = new Headers(request.headers);
        headers.set("authorization", `Bearer ${data.session.access_token}`);
        const member = await requireMember(new Request(request.url, { method: "GET", headers }), {
          allowPasswordRotation: true,
        });
        const admin = getAdminSupabase();
        const { error: revokeError } = await admin.rpc("opentrack_revoke_member_session", {
          p_user_id: member.userId,
          p_session_id: member.sessionId,
        });
        if (revokeError) return apiError(503, "service_unavailable", "Authentication service unavailable");
        await admin.auth.admin.signOut(data.session.access_token, "local").catch(() => undefined);
      } catch (error) {
        if (error instanceof AuthFault && error.status === 503) return authFaultResponse(error);
      }
    }
    await cookieClient.auth.signOut({ scope: "local" }).catch(() => undefined);
    return oauthResponse({ signedOut: true });
  } catch (error) {
    if (error instanceof AuthFault && error.status === 403) return authFaultResponse(oauthCsrfFault());
    return authFaultResponse(error);
  }
}
