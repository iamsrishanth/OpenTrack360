import {
  ADMIN_GOOGLE_CALLBACK_PATH,
  ADMIN_GOOGLE_PKCE_COOKIE,
  OPEN_TRACK_PRODUCTION_ORIGIN,
  adminGoogleCallbackUrl,
  isAllowedAdminGoogleOrigin,
  isAuthorizedGoogleAdminIdentity,
  readAdminGooglePkceVerifier,
} from "@/src/server/auth/admin-google-oauth";
import { getAdminSupabase } from "@/src/server/auth/supabase";
import { jwtIssuedAt, jwtSessionId, jwtSubject } from "@/src/server/auth/member";

const ADMIN_COOKIE_PATH = "/api/v1/admin";
const NO_STORE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  "Pragma": "no-cache",
  "Referrer-Policy": "no-referrer",
  "X-Robots-Tag": "noindex, nofollow, noarchive",
};

function clearPendingCookie(): string {
  const secure = process.env.NODE_ENV === "production";
  return `${ADMIN_GOOGLE_PKCE_COOKIE}=; Path=${ADMIN_GOOGLE_CALLBACK_PATH}; Max-Age=0; HttpOnly; SameSite=Lax${secure ? "; Secure" : ""}`;
}

function adminCookie(name: string, value: string, maxAge: number): string {
  return `${name}=${encodeURIComponent(value)}; Path=${ADMIN_COOKIE_PATH}; Max-Age=${maxAge}; Secure; HttpOnly; SameSite=Strict`;
}

function failedRedirect(request: Request): Response {
  const requestUrl = new URL(request.url);
  const origin = requestUrl.origin === OPEN_TRACK_PRODUCTION_ORIGIN || isAllowedAdminGoogleOrigin(requestUrl.origin)
    ? requestUrl.origin
    : OPEN_TRACK_PRODUCTION_ORIGIN;
  const target = new URL("/admin", origin);
  target.searchParams.set("auth", "google-signin-failed");
  const response = new Response(null, { status: 303, headers: { ...NO_STORE_HEADERS, Location: target.toString() } });
  response.headers.append("Set-Cookie", clearPendingCookie());
  return response;
}

function validCode(value: string): boolean {
  return value.length > 0 && value.length <= 4096 && !/[\u0000-\u001f\u007f]/.test(value);
}

export async function GET(request: Request): Promise<Response> {
  const callbackUrl = adminGoogleCallbackUrl(request.url);
  if (!callbackUrl) return failedRedirect(request);

  const requestUrl = new URL(request.url);
  const codes = requestUrl.searchParams.getAll("code");
  const verifier = readAdminGooglePkceVerifier(request);
  if (codes.length !== 1 || !validCode(codes[0]) || !verifier || requestUrl.pathname !== ADMIN_GOOGLE_CALLBACK_PATH) {
    return failedRedirect(request);
  }

  const supabaseUrl = process.env.SUPABASE_URL?.trim().replace(/\/$/, "");
  const publishableKey = process.env.SUPABASE_PUBLISHABLE_KEY?.trim();
  if (!supabaseUrl || !publishableKey) return failedRedirect(request);

  try {
    const tokenResponse = await fetch(`${supabaseUrl}/auth/v1/token?grant_type=pkce`, {
      method: "POST",
      headers: {
        apikey: publishableKey,
        Authorization: `Bearer ${publishableKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ auth_code: codes[0], code_verifier: verifier }),
      cache: "no-store",
      signal: AbortSignal.timeout(10000),
    });
    const token = await tokenResponse.json().catch(() => null) as Record<string, unknown> | null;
    const accessToken = typeof token?.access_token === "string" ? token.access_token : "";
    const refreshToken = typeof token?.refresh_token === "string" ? token.refresh_token : "";
    if (!tokenResponse.ok || !accessToken || accessToken.length > 16384 || !refreshToken || refreshToken.length > 4096 ||
        typeof token?.token_type !== "string" || token.token_type.toLowerCase() !== "bearer") {
      return failedRedirect(request);
    }

    const sessionId = jwtSessionId(accessToken);
    const issuedAt = jwtIssuedAt(accessToken);
    if (!sessionId || issuedAt === null) return failedRedirect(request);

    const admin = getAdminSupabase();
    const { data: userResult, error: userError } = await admin.auth.getUser(accessToken);
    const user = userResult?.user;
    if (userError || !user || jwtSubject(accessToken) !== user.id || !isAuthorizedGoogleAdminIdentity(user)) {
      await admin.auth.admin.signOut(accessToken, "local").catch(() => undefined);
      return failedRedirect(request);
    }

    const { data: registered, error: registerError } = await admin.rpc("opentrack_register_oauth_session", {
      p_user_id: user.id,
      p_session_id: sessionId,
      p_token_issued_at: issuedAt,
    });
    const member = registered as Record<string, unknown> | null;
    if (registerError || member?.allowed !== true || member.isPlatformAdmin !== true ||
        member.adminAuthMethod !== "google" || member.mustRotatePassword === true) {
      await admin.auth.admin.signOut(accessToken, "local").catch(() => undefined);
      return failedRedirect(request);
    }

    const expiresIn = typeof token.expires_in === "number" && Number.isSafeInteger(token.expires_in) && token.expires_in > 0
      ? Math.min(token.expires_in, 60 * 60)
      : 60 * 60;
    const response = new Response(null, {
      status: 303,
      headers: { ...NO_STORE_HEADERS, Location: new URL("/admin", new URL(request.url).origin).toString() },
    });
    response.headers.append("Set-Cookie", clearPendingCookie());
    response.headers.append("Set-Cookie", adminCookie("ot_admin_access", accessToken, expiresIn));
    response.headers.append("Set-Cookie", adminCookie("ot_admin_refresh", refreshToken, 30 * 24 * 60 * 60));
    return response;
  } catch {
    return failedRedirect(request);
  }
}
