import { createHash, randomBytes } from "node:crypto";

export const ADMIN_GOOGLE_CALLBACK_PATH = "/api/v1/admin/google/callback";
export const ADMIN_GOOGLE_START_PATH = "/api/v1/admin/google/start";
export const ADMIN_GOOGLE_PKCE_COOKIE = "ot_admin_google_pkce";
export const ADMIN_GOOGLE_PKCE_TTL_SECONDS = 600;
export const OPEN_TRACK_PRODUCTION_ORIGIN = "https://track.srishanth.me";

type SupabaseGoogleIdentity = {
  provider?: unknown;
  identity_data?: { email?: unknown; email_verified?: unknown } | null;
};

export type SupabaseGoogleUser = {
  id?: unknown;
  email?: unknown;
  email_confirmed_at?: unknown;
  identities?: SupabaseGoogleIdentity[] | null;
};

export function configuredAdminGoogleEmail(): string | null {
  const value = process.env.OPENTRACK_ADMIN_GOOGLE_EMAIL?.trim().toLowerCase();
  if (!value || value.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return null;
  return value;
}

export function isAuthorizedGoogleAdminIdentity(
  user: SupabaseGoogleUser | null | undefined,
  allowedEmail = configuredAdminGoogleEmail(),
): boolean {
  if (!user || !allowedEmail || typeof user.email !== "string" ||
      typeof user.email_confirmed_at !== "string" || !user.email_confirmed_at) return false;

  const email = user.email.trim().toLowerCase();
  if (email !== allowedEmail) return false;

  return (user.identities ?? []).some((identity) =>
    identity.provider === "google" &&
    identity.identity_data?.email_verified === true &&
    typeof identity.identity_data.email === "string" &&
    identity.identity_data.email.trim().toLowerCase() === allowedEmail,
  );
}

export function googleProviderEnabledInSettings(settings: unknown): boolean {
  if (!settings || typeof settings !== "object" || Array.isArray(settings)) return false;
  const external = (settings as Record<string, unknown>).external;
  if (!external || typeof external !== "object" || Array.isArray(external)) return false;
  const google = (external as Record<string, unknown>).google;
  return !!google && typeof google === "object" && !Array.isArray(google) &&
    (google as Record<string, unknown>).enabled === true;
}

export function isAllowedAdminGoogleOrigin(origin: string, environment = process.env): boolean {
  if (origin === OPEN_TRACK_PRODUCTION_ORIGIN) {
    return environment.NODE_ENV !== "production" || environment.VERCEL_ENV === "production";
  }
  if (environment.NODE_ENV === "production" || environment.VERCEL_ENV === "preview") return false;
  return origin === "http://localhost:3000" || origin === "http://127.0.0.1:3000";
}

export function adminGoogleCallbackUrl(requestUrl: string, environment = process.env): string | null {
  try {
    const url = new URL(requestUrl);
    if (!isAllowedAdminGoogleOrigin(url.origin, environment)) return null;
    return new URL(ADMIN_GOOGLE_CALLBACK_PATH, url.origin).toString();
  } catch {
    return null;
  }
}

export function createGooglePkcePair(): { verifier: string; challenge: string } {
  const verifier = randomBytes(32).toString("base64url");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  return { verifier, challenge };
}

export function buildGoogleAuthorizationUrl(input: {
  supabaseUrl: string;
  callbackUrl: string;
  challenge: string;
}): string {
  const supabase = new URL(input.supabaseUrl);
  const callback = new URL(input.callbackUrl);
  if ((!supabase.hostname.endsWith(".supabase.co") && supabase.hostname !== "127.0.0.1" && supabase.hostname !== "localhost") ||
      !["https:", "http:"].includes(supabase.protocol) ||
      callback.pathname !== ADMIN_GOOGLE_CALLBACK_PATH || callback.search || callback.hash || callback.username || callback.password ||
      !isAllowedAdminGoogleOrigin(callback.origin) ||
      !/^[A-Za-z0-9_-]{43,128}$/.test(input.challenge)) {
    throw new Error("Invalid Google sign-in configuration");
  }

  const authorize = new URL("/auth/v1/authorize", supabase);
  authorize.searchParams.set("provider", "google");
  authorize.searchParams.set("redirect_to", callback.toString());
  authorize.searchParams.set("scopes", "openid email profile");
  authorize.searchParams.set("code_challenge", input.challenge);
  authorize.searchParams.set("code_challenge_method", "s256");
  authorize.searchParams.set("prompt", "select_account");
  return authorize.toString();
}

export function readAdminGooglePkceVerifier(request: Request): string | null {
  const header = request.headers.get("cookie") ?? "";
  for (const part of header.split(";")) {
    const separator = part.indexOf("=");
    if (separator < 0 || part.slice(0, separator).trim() !== ADMIN_GOOGLE_PKCE_COOKIE) continue;
    try {
      const verifier = decodeURIComponent(part.slice(separator + 1).trim());
      return /^[A-Za-z0-9_-]{43,128}$/.test(verifier) ? verifier : null;
    } catch {
      return null;
    }
  }
  return null;
}

export async function isAdminGoogleProviderAvailable(): Promise<boolean> {
  if (!configuredAdminGoogleEmail() || process.env.VERCEL_ENV === "preview") return false;
  const supabaseUrl = process.env.SUPABASE_URL?.trim().replace(/\/$/, "");
  const publishableKey = process.env.SUPABASE_PUBLISHABLE_KEY?.trim();
  if (!supabaseUrl || !publishableKey) return false;

  try {
    const response = await fetch(`${supabaseUrl}/auth/v1/settings`, {
      headers: { apikey: publishableKey, Authorization: `Bearer ${publishableKey}` },
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok) return false;
    return googleProviderEnabledInSettings(await response.json().catch(() => null));
  } catch {
    return false;
  }
}
