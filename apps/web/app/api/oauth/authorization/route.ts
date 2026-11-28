import { AuthFault, authFaultResponse, requireSameOrigin } from "@/src/server/auth/member";
import {
  configuredOAuthClientId,
  isAllowedOAuthRedirectUrl,
  oauthCsrfFault,
  oauthResponse,
  oauthUnavailable,
  readOAuthMember,
  validateOAuthAuthorization,
  type OAuthAuthorizationDetails,
} from "@/src/server/auth/oauth";
import { apiError, parseJsonObject } from "@/src/server/auth/supabase";

function readAuthorizationId(body: Record<string, unknown> | null): string | null {
  const value = body?.authorizationId;
  return typeof value === "string" && value.length >= 8 && value.length <= 200 && /^[A-Za-z0-9_-]+$/.test(value)
    ? value
    : null;
}

export async function POST(request: Request): Promise<Response> {
  try {
    requireSameOrigin(request);
    if (!configuredOAuthClientId()) return oauthUnavailable();
    const authorizationId = readAuthorizationId(await parseJsonObject(request));
    if (!authorizationId) return apiError(400, "invalid_request", "This sign-in request is invalid or expired");

    const { supabase, member } = await readOAuthMember(request);
    if (!member) return oauthResponse({ error: { code: "unauthorized", message: "Sign in to continue" } }, 401);
    if (member.mustRotatePassword) {
      return oauthResponse({ error: { code: "password_rotation_required", message: "Change your temporary password to continue", username: member.username } }, 428);
    }

    const { data, error } = await supabase.auth.oauth.getAuthorizationDetails(authorizationId);
    if (error || !data) return apiError(400, "invalid_request", "This sign-in request is invalid or expired");

    if ("redirect_url" in data) {
      if (!isAllowedOAuthRedirectUrl(data.redirect_url)) return apiError(400, "invalid_request", "This sign-in request is not supported");
      return oauthResponse({ status: "redirect", redirectUrl: data.redirect_url });
    }

    const details = data as OAuthAuthorizationDetails;
    if (!validateOAuthAuthorization(details, member)) {
      return apiError(400, "invalid_request", "This sign-in request is not supported");
    }

    return oauthResponse({
      status: "consent",
      clientName: "OpenTrack360 for Android",
      clientUri: "https://track.srishanth.me",
      redirectUri: details.redirect_uri,
      scopes: ["openid"],
      username: member.username,
    });
  } catch (error) {
    if (error instanceof AuthFault && error.status === 403) return authFaultResponse(oauthCsrfFault());
    return authFaultResponse(error);
  }
}
