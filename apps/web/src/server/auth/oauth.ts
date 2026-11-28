import { createServerClient } from "@supabase/ssr";
import { cookies } from "next/headers";
import type { SupabaseClient } from "@supabase/supabase-js";
import { AuthFault, requireMember, type MemberContext } from "./member";

export type OAuthAuthorizationDetails = {
  authorization_id: string;
  redirect_uri: string;
  client: { id: string; name: string; uri: string; logo_uri: string };
  user: { id: string; email: string };
  scope: string;
};

export const OPEN_TRACK_OAUTH_REDIRECT_URI = "https://track.srishanth.me/oauth/callback";
export const OPEN_TRACK_OAUTH_SCOPES = ["openid"] as const;

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`Missing server environment variable: ${name}`);
  return value;
}

export function configuredOAuthClientId(): string | null {
  const value = process.env.SUPABASE_OAUTH_ANDROID_CLIENT_ID?.trim();
  return value && value.length <= 128 ? value : null;
}

export function isAllowedOAuthScope(scope: string): boolean {
  const requested = scope.trim().split(/\s+/).filter(Boolean);
  return requested.length === OPEN_TRACK_OAUTH_SCOPES.length && requested[0] === "openid";
}

export function isAllowedOAuthRedirectUri(value: string): boolean {
  try {
    const url = new URL(value);
    return `${url.origin}${url.pathname}` === OPEN_TRACK_OAUTH_REDIRECT_URI &&
      url.search.length === 0 && url.hash.length === 0;
  } catch {
    return false;
  }
}

export function isAllowedOAuthRedirectUrl(value: string): boolean {
  try {
    const url = new URL(value);
    if (`${url.origin}${url.pathname}` !== OPEN_TRACK_OAUTH_REDIRECT_URI ||
        url.username || url.password || url.hash) return false;

    const allowedParameters = new Set(["code", "state", "error", "error_description"]);
    const seenParameters = new Set<string>();
    for (const [name] of url.searchParams) {
      if (!allowedParameters.has(name) || seenParameters.has(name)) return false;
      seenParameters.add(name);
    }

    const hasCode = url.searchParams.has("code");
    const hasError = url.searchParams.has("error");
    const hasErrorDescription = url.searchParams.has("error_description");
    return hasCode !== hasError && (!hasErrorDescription || hasError);
  } catch {
    return false;
  }
}

export function validateOAuthAuthorization(
  details: OAuthAuthorizationDetails,
  member: MemberContext,
): boolean {
  const clientId = configuredOAuthClientId();
  return clientId !== null &&
    details.client.id === clientId &&
    details.user.id === member.userId &&
    isAllowedOAuthRedirectUri(details.redirect_uri) &&
    isAllowedOAuthScope(details.scope);
}

export async function createOAuthCookieClient(): Promise<SupabaseClient> {
  const cookieStore = await cookies();
  const secure = process.env.NODE_ENV === "production";

  return createServerClient(required("SUPABASE_URL"), required("SUPABASE_PUBLISHABLE_KEY"), {
    auth: { autoRefreshToken: true, persistSession: true, detectSessionInUrl: false },
    cookieOptions: {
      name: "ot_oauth_session",
      path: "/",
      sameSite: "lax",
      secure,
      httpOnly: true,
      maxAge: 60 * 60 * 12,
    },
    cookies: {
      getAll() {
        return cookieStore.getAll();
      },
      setAll(cookiesToSet) {
        cookiesToSet.forEach(({ name, value, options }) => cookieStore.set(name, value, options));
      },
    },
  });
}

export async function readOAuthMember(request: Request): Promise<{
  supabase: SupabaseClient;
  session: { access_token: string; refresh_token: string } | null;
  member: MemberContext | null;
}> {
  const supabase = await createOAuthCookieClient();
  const { data, error } = await supabase.auth.getSession();
  if (error || !data.session) return { supabase, session: null, member: null };

  const headers = new Headers(request.headers);
  headers.set("authorization", `Bearer ${data.session.access_token}`);
  const member = await requireMember(new Request(request.url, { method: "GET", headers }), {
    allowPasswordRotation: true,
  });

  return {
    supabase,
    session: {
      access_token: data.session.access_token,
      refresh_token: data.session.refresh_token,
    },
    member,
  };
}

export function oauthUnavailable(): Response {
  return Response.json(
    { error: { code: "oauth_unavailable", message: "OpenTrack360 sign-in is temporarily unavailable" } },
    { status: 503, headers: { "Cache-Control": "private, no-store, max-age=0" } },
  );
}

export function oauthResponse(body: unknown, status = 200): Response {
  return Response.json(body, {
    status,
    headers: {
      "Cache-Control": "private, no-store, max-age=0",
      "Pragma": "no-cache",
      "Expires": "0",
    },
  });
}

export function oauthCsrfFault(): AuthFault {
  return new AuthFault(403, "csrf_failed", "Request validation failed");
}
