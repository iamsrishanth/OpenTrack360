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

export async function POST(request: Request): Promise<Response> {
  try {
    requireSameOrigin(request);
    if (!configuredOAuthClientId()) return oauthUnavailable();

    const body = await parseJsonObject(request);
    const authorizationId = typeof body?.authorizationId === "string" && body.authorizationId.length >= 8 &&
      body.authorizationId.length <= 200 && /^[A-Za-z0-9_-]+$/.test(body.authorizationId)
      ? body.authorizationId
      : null;
    const decision = body?.decision === "approve" || body?.decision === "deny" ? body.decision : null;
    if (!authorizationId || !decision) return apiError(400, "invalid_request", "This sign-in request is invalid or expired");

    const { supabase, member } = await readOAuthMember(request);
    if (!member) return oauthResponse({ error: { code: "unauthorized", message: "Sign in to continue" } }, 401);
    if (member.mustRotatePassword) {
      return oauthResponse({ error: { code: "password_rotation_required", message: "Change your temporary password to continue" } }, 428);
    }

    const { data: details, error: detailsError } = await supabase.auth.oauth.getAuthorizationDetails(authorizationId);
    if (detailsError || !details) return apiError(400, "invalid_request", "This sign-in request is invalid or expired");
    if ("redirect_url" in details) {
      if (!isAllowedOAuthRedirectUrl(details.redirect_url)) return apiError(400, "invalid_request", "This sign-in request is not supported");
      return oauthResponse({ redirectUrl: details.redirect_url });
    }
    if (!validateOAuthAuthorization(details as OAuthAuthorizationDetails, member)) {
      return apiError(400, "invalid_request", "This sign-in request is not supported");
    }

    const result = decision === "approve"
      ? await supabase.auth.oauth.approveAuthorization(authorizationId, { skipBrowserRedirect: true })
      : await supabase.auth.oauth.denyAuthorization(authorizationId, { skipBrowserRedirect: true });
    const redirectUrl = result.data?.redirect_url;
    if (result.error || !redirectUrl || !isAllowedOAuthRedirectUrl(redirectUrl)) {
      return apiError(400, "invalid_request", "This sign-in request is invalid or expired");
    }
    return oauthResponse({ redirectUrl });
  } catch (error) {
    if (error instanceof AuthFault && error.status === 403) return authFaultResponse(oauthCsrfFault());
    return authFaultResponse(error);
  }
}
