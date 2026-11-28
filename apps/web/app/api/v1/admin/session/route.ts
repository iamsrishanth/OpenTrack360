import { randomBytes } from "node:crypto";
import { AuthFault, authFaultResponse, readAdminCookie, requireAdminCsrf, requireAdminSession, requireSameOrigin } from "@/src/server/auth/member";
import { apiError, getAdminSupabase, getPublicSupabase, normalizeUsername, parseJsonObject } from "@/src/server/auth/supabase";
import { jwtSessionId } from "@/src/server/auth/member";
import { isAdminGoogleProviderAvailable } from "@/src/server/auth/admin-google-oauth";

const ADMIN_COOKIE_PATH = "/api/v1/admin";
const ACCESS_AGE = 60 * 60;
const REFRESH_AGE = 60 * 60 * 24 * 30;

function cookie(name: string, value: string, options: { maxAge: number; path: string; httpOnly: boolean }): string {
  return `${name}=${encodeURIComponent(value)}; Path=${options.path}; Max-Age=${options.maxAge}; Secure; SameSite=Strict${options.httpOnly ? "; HttpOnly" : ""}`;
}

function expiredCookie(name: string, path: string, httpOnly = true): string {
  return `${name}=; Path=${path}; Max-Age=0; Secure; SameSite=Strict${httpOnly ? "; HttpOnly" : ""}`;
}

export async function GET(request: Request): Promise<Response> {
  const current = readAdminCookie(request, "ot_admin_csrf");
  const csrfToken = current && current.length >= 32 ? current : randomBytes(32).toString("base64url");
  const headers = new Headers({ "Cache-Control": "no-store" });
  if (csrfToken !== current) headers.append("Set-Cookie", cookie("ot_admin_csrf", csrfToken, { maxAge: REFRESH_AGE, path: "/", httpOnly: false }));
  const googleOAuthEnabled = await isAdminGoogleProviderAvailable();
  return Response.json({ csrfToken, googleOAuthEnabled }, { headers });
}

export async function POST(request: Request): Promise<Response> {
  try {
    requireSameOrigin(request);
    const body = await parseJsonObject(request);
    const username = normalizeUsername(body?.username);
    const password = typeof body?.password === "string" ? body.password : "";
    if (!username || password.length < 1 || password.length > 128) return apiError(401, "unauthorized", "Invalid credentials");

    const admin = getAdminSupabase();
    const { data: loginAllowed, error: limitError } = await admin.rpc("opentrack_login_allowed", { p_username_normalized: username });
    if (limitError) return apiError(503, "service_unavailable", "Authentication service unavailable");
    if (loginAllowed !== true) return apiError(429, "rate_limited", "Too many sign-in attempts. Try again later.");
    const { data: identity, error: lookupError } = await admin.rpc("opentrack_lookup_login", { p_username_normalized: username });
    if (lookupError) return apiError(503, "service_unavailable", "Authentication service unavailable");
    const internal = identity as Record<string, unknown> | null;
    if (internal?.isPlatformAdmin !== true || internal.adminAuthMethod === "google") {
      return apiError(401, "unauthorized", "Invalid credentials");
    }
    const email = typeof internal?.authEmail === "string" ? internal.authEmail : "not-a-member@invalid.invalid";
    const auth = getPublicSupabase();
    const { data, error } = await auth.auth.signInWithPassword({ email, password });
    if (error || !data.session || !data.user || internal?.disabled === true || internal?.userId !== data.user.id || internal?.isPlatformAdmin !== true) {
      return apiError(401, "unauthorized", "Invalid credentials");
    }
    const sessionId = jwtSessionId(data.session.access_token);
    if (!sessionId) return apiError(401, "unauthorized", "Invalid credentials");
    const { data: registration, error: registerError } = await admin.rpc("opentrack_register_member_session", {
      p_user_id: data.user.id,
      p_session_id: sessionId,
    });
    const member = registration as Record<string, unknown> | null;
    if (registerError || member?.allowed !== true || member.isPlatformAdmin !== true) {
      await admin.auth.admin.signOut(data.session.access_token, "local").catch(() => undefined);
      return apiError(401, "unauthorized", "Invalid credentials");
    }

    const headers = new Headers({ "Cache-Control": "no-store" });
    headers.append("Set-Cookie", cookie("ot_admin_access", data.session.access_token, { maxAge: Math.min(data.session.expires_in ?? ACCESS_AGE, ACCESS_AGE), path: ADMIN_COOKIE_PATH, httpOnly: true }));
    headers.append("Set-Cookie", cookie("ot_admin_refresh", data.session.refresh_token, { maxAge: REFRESH_AGE, path: ADMIN_COOKIE_PATH, httpOnly: true }));
    return Response.json({ authenticated: true, username, requiresPasswordRotation: member.mustRotatePassword === true }, { headers });
  } catch (error) {
    return authFaultResponse(error);
  }
}

export async function DELETE(request: Request): Promise<Response> {
  try {
    requireAdminCsrf(request);
    const member = await requireAdminSession(request, { allowPasswordRotation: true });
    const accessToken = readAdminCookie(request, "ot_admin_access");
    if (accessToken) await getAdminSupabase().auth.admin.signOut(accessToken, "local").catch(() => undefined);
    const { error } = await getAdminSupabase().rpc("opentrack_revoke_member_session", {
      p_user_id: member.userId,
      p_session_id: member.sessionId,
    });
    if (error) return apiError(503, "service_unavailable", "Authentication service unavailable");
    const headers = new Headers({ "Cache-Control": "no-store" });
    headers.append("Set-Cookie", expiredCookie("ot_admin_access", ADMIN_COOKIE_PATH));
    headers.append("Set-Cookie", expiredCookie("ot_admin_refresh", ADMIN_COOKIE_PATH));
    return Response.json({ revoked: true }, { headers });
  } catch (error) {
    return authFaultResponse(error);
  }
}
