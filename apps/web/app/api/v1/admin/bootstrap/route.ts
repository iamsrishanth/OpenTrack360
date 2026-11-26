import { createHash, timingSafeEqual } from "node:crypto";
import { apiError, getAdminSupabase, normalizeUsername, parseJsonObject } from "@/src/server/auth/supabase";

const NO_STORE = { "Cache-Control": "no-store" };

function secretMatches(candidate: string, expected: string): boolean {
  const left = Buffer.from(candidate, "utf8");
  const right = Buffer.from(expected, "utf8");
  return left.length === right.length && timingSafeEqual(left, right);
}

function uuidFromSecret(secret: string): string {
  const bytes = createHash("sha256").update("opentrack360-first-admin:").update(secret).digest().subarray(0, 16);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function emailDomain(): string | null {
  const domain = process.env.OPENTRACK_AUTH_EMAIL_DOMAIN?.trim().toLowerCase();
  if (!domain || domain.length > 253 || !/^[a-z0-9.-]+$/.test(domain) || domain.startsWith(".") || domain.endsWith(".") || domain.includes("..")) return null;
  return domain;
}

export async function POST(request: Request): Promise<Response> {
  const configuredSecret = process.env.OPENTRACK_ADMIN_BOOTSTRAP_SECRET;
  const authHeader = request.headers.get("authorization") ?? "";
  const suppliedSecret = /^Bearer ([A-Za-z0-9._~-]{32,512})$/.exec(authHeader)?.[1] ?? "";
  if (!configuredSecret || configuredSecret.length < 32 || !secretMatches(suppliedSecret, configuredSecret)) {
    return apiError(404, "not_found", "Not found");
  }

  const body = await parseJsonObject(request);
  const username = normalizeUsername(body?.username);
  const displayName = typeof body?.displayName === "string" ? body.displayName.trim() : username;
  const initialPassword = typeof body?.temporaryPassword === "string" ? body.temporaryPassword : "";
  if (!username || !displayName || displayName.length > 80 || initialPassword.length < 16 || initialPassword.length > 128) {
    return apiError(400, "invalid_request", "Provide a username, display name, and temporary password of at least 16 characters");
  }

  const domain = emailDomain();
  if (!domain) return apiError(503, "configuration_required", "Account provisioning is not configured");
  const claimId = uuidFromSecret(configuredSecret);
  const authEmail = `bootstrap-${claimId.replaceAll("-", "")}@${domain}`;
  const admin = getAdminSupabase();
  const { data: claimed, error: claimError } = await admin.rpc("opentrack_begin_admin_bootstrap", { p_claim_id: claimId });
  if (claimError) return apiError(503, "service_unavailable", "Bootstrap service unavailable");
  if (claimed !== true) return apiError(409, "bootstrap_unavailable", "Bootstrap is already claimed or completed");

  let userId = "";
  const { data: created, error: createError } = await admin.auth.admin.createUser({
    email: authEmail,
    password: initialPassword,
    email_confirm: true,
    user_metadata: { opentrack_username: username },
  });
  if (!createError && created.user) userId = created.user.id;
  else {
    const { data: existingId, error: lookupError } = await admin.rpc("opentrack_auth_user_id_by_email", { p_auth_email: authEmail });
    if (!lookupError && typeof existingId === "string") userId = existingId;
  }
  if (!userId) {
    await admin.rpc("opentrack_abort_admin_bootstrap", { p_claim_id: claimId });
    return apiError(503, "bootstrap_failed", "Administrator could not be provisioned; retry with the same bootstrap secret");
  }

  const { error: passwordError } = await admin.auth.admin.updateUserById(userId, {
    password: initialPassword,
    email_confirm: true,
    user_metadata: { opentrack_username: username },
  });
  if (passwordError) {
    await admin.rpc("opentrack_abort_admin_bootstrap", { p_claim_id: claimId });
    return apiError(503, "bootstrap_failed", "Administrator could not be provisioned; retry with the same bootstrap secret");
  }

  const { data: finished, error: finishError } = await admin.rpc("opentrack_finish_admin_bootstrap", {
    p_claim_id: claimId,
    p_user_id: userId,
    p_username_normalized: username,
    p_display_name: displayName,
    p_auth_email: authEmail,
  });
  const result = finished as { ok?: boolean; accountId?: string } | null;
  if (!finishError && result?.ok === true) {
    return Response.json({ accountId: result.accountId, username, temporaryPassword: initialPassword, mustRotatePassword: true }, { status: 201, headers: NO_STORE });
  }

  // The RPC can commit before its response is lost. Check the private identity
  // mapping before deleting Auth state or releasing the one-time claim.
  const { data: linkedEmail, error: linkError } = await admin.rpc("opentrack_lookup_auth_email_by_user", { p_user_id: userId });
  if (!linkError && linkedEmail === authEmail) {
    return apiError(503, "bootstrap_response_lost", "Bootstrap may have completed; sign in with the supplied temporary password");
  }
  await admin.auth.admin.deleteUser(userId).catch(() => undefined);
  await admin.rpc("opentrack_abort_admin_bootstrap", { p_claim_id: claimId });
  return apiError(503, "bootstrap_failed", "Administrator could not be provisioned; retry with the same bootstrap secret");
}
