import {
  ADMIN_GOOGLE_CALLBACK_PATH,
  ADMIN_GOOGLE_PKCE_COOKIE,
  ADMIN_GOOGLE_PKCE_TTL_SECONDS,
  adminGoogleCallbackUrl,
  buildGoogleAuthorizationUrl,
  createGooglePkcePair,
  isAdminGoogleProviderAvailable,
} from "@/src/server/auth/admin-google-oauth";
import { apiError } from "@/src/server/auth/supabase";

const NO_STORE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  "Pragma": "no-cache",
  "Referrer-Policy": "no-referrer",
  "X-Robots-Tag": "noindex, nofollow, noarchive",
};

function failedRedirect(request: Request): Response {
  const target = new URL("/admin", new URL(request.url).origin);
  target.searchParams.set("auth", "google-unavailable");
  return new Response(null, { status: 303, headers: { ...NO_STORE_HEADERS, Location: target.toString() } });
}

export async function GET(request: Request): Promise<Response> {
  try {
    const callbackUrl = adminGoogleCallbackUrl(request.url);
    const supabaseUrl = process.env.SUPABASE_URL?.trim().replace(/\/$/, "");
    if (!callbackUrl || !supabaseUrl || !(await isAdminGoogleProviderAvailable())) return failedRedirect(request);

    const { verifier, challenge } = createGooglePkcePair();
    const authorizationUrl = buildGoogleAuthorizationUrl({ supabaseUrl, callbackUrl, challenge });
    const secure = process.env.NODE_ENV === "production";
    const response = new Response(null, { status: 303, headers: { ...NO_STORE_HEADERS, Location: authorizationUrl } });
    response.headers.append("Set-Cookie",
      `${ADMIN_GOOGLE_PKCE_COOKIE}=${encodeURIComponent(verifier)}; Path=${ADMIN_GOOGLE_CALLBACK_PATH}; Max-Age=${ADMIN_GOOGLE_PKCE_TTL_SECONDS}; HttpOnly; SameSite=Lax${secure ? "; Secure" : ""}`,
    );
    return response;
  } catch {
    return apiError(503, "oauth_unavailable", "Google sign-in is temporarily unavailable");
  }
}
