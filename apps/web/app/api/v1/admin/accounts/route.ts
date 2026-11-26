import { randomBytes, randomUUID } from "node:crypto";
import { authFaultResponse, requireAdminCsrf, requireAdminSession } from "@/src/server/auth/member";
import { apiError, getAdminSupabase, normalizeUsername, parseJsonObject } from "@/src/server/auth/supabase";

const NO_STORE = { "Cache-Control": "no-store" };

function authEmailDomain(): string {
  const domain = process.env.OPENTRACK_AUTH_EMAIL_DOMAIN?.trim().toLowerCase();
  if (!domain || domain.length > 253 || !/^[a-z0-9.-]+$/.test(domain) || domain.startsWith(".") || domain.endsWith(".") || domain.includes("..")) {
    throw new Error("Missing or invalid OPENTRACK_AUTH_EMAIL_DOMAIN");
  }
  return domain;
}

function temporaryPassword(): string {
  return randomBytes(32).toString("base64url");
}

function resultResponse(envelope: unknown): Response {
  const result = envelope as { httpStatus?: number; body?: unknown } | null;
  return Response.json(result?.body ?? {}, { status: result?.httpStatus ?? 500, headers: NO_STORE });
}

async function abortProvision(admin: ReturnType<typeof getAdminSupabase>, requestId: string, leaseToken: string, actorId: string): Promise<void> {
  try { await admin.rpc("opentrack_abort_account", { p_request_id: requestId, p_lease_token: leaseToken, p_actor_user_id: actorId }); } catch { /* best-effort saga rollback */ }
}

async function deleteAuthUser(admin: ReturnType<typeof getAdminSupabase>, userId: string): Promise<boolean> {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const { error } = await admin.auth.admin.deleteUser(userId);
      if (!error) return true;
    } catch {
      // The response may be lost after Auth has already deleted the user; retry
      // the same idempotent delete before deciding whether to preserve the claim.
    }
  }
  return false;
}

async function rollbackIfStillProvisioning(
  admin: ReturnType<typeof getAdminSupabase>, accountId: string, requestId: string, leaseToken: string, actorId: string, authUserId: string,
): Promise<void> {
  const { data, error } = await admin.rpc("opentrack_admin_get_account", { p_account_id: accountId, p_actor_user_id: actorId });
  if (error) return;
  const envelope = data as { httpStatus?: number; body?: Record<string, unknown> } | null;
  if (envelope?.httpStatus === 200 && envelope.body?.status === "active") return;
  if (envelope?.httpStatus !== 200 && envelope?.httpStatus !== 404) return;
  const authDeleted = await deleteAuthUser(admin, authUserId);
  // Keep the username claim in provisioning state when Auth cleanup fails. It
  // remains unloginable and reserved for operator follow-up instead of becoming
  // an untracked Auth user plus a reusable username.
  if (!authDeleted) return;
  await abortProvision(admin, requestId, leaseToken, actorId);
}

export async function GET(request: Request): Promise<Response> {
  try {
    const member = await requireAdminSession(request);
    const url = new URL(request.url);
    const rawLimit = Number(url.searchParams.get("limit") ?? "100");
    const limit = Number.isInteger(rawLimit) && rawLimit > 0 && rawLimit <= 200 ? rawLimit : 100;
    const cursor = url.searchParams.get("cursor");
    if (cursor && !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(cursor)) {
      return apiError(400, "invalid_request", "Invalid account cursor");
    }
    const { data, error } = await getAdminSupabase().rpc("opentrack_admin_list_accounts", {
      p_actor_user_id: member.userId,
      p_limit: limit,
      p_cursor: cursor,
    });
    if (error) return apiError(503, "service_unavailable", "Account service unavailable");
    return resultResponse(data);
  } catch (error) {
    return authFaultResponse(error);
  }
}

export async function POST(request: Request): Promise<Response> {
  try {
    requireAdminCsrf(request);
    const member = await requireAdminSession(request);
    const body = await parseJsonObject(request);
    const username = normalizeUsername(body?.username);
    const displayName = typeof body?.displayName === "string" ? body.displayName.trim() : username;
    const requestId = typeof body?.requestId === "string" ? body.requestId : randomUUID();
    if (!username || !displayName || displayName.length > 80 || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(requestId)) {
      return apiError(400, "invalid_request", "Provide a valid username and display name");
    }

    const admin = getAdminSupabase();
    const leaseToken = randomUUID();
    let emailDomain: string;
    try { emailDomain = authEmailDomain(); } catch { return apiError(503, "configuration_required", "Account provisioning is not configured"); }
    const candidateEmail = `${randomBytes(24).toString("hex")}@${emailDomain}`;
    const tempPassword = temporaryPassword();
    const { data: claimedData, error: claimError } = await admin.rpc("opentrack_claim_username", {
      p_request_id: requestId,
      p_lease_token: leaseToken,
      p_username_normalized: username,
      p_display_name: displayName,
      p_auth_email: candidateEmail,
      p_actor_user_id: member.userId,
    });
    if (claimError) return apiError(503, "service_unavailable", "Account service unavailable");
    const claimedEnvelope = claimedData as { httpStatus?: number; body?: Record<string, unknown> } | null;
    const claimBody = claimedEnvelope?.body ?? {};
    if ((claimedEnvelope?.httpStatus ?? 500) !== 201) return resultResponse(claimedData);

    const accountId = String(claimBody.accountId ?? "");
    const authEmail = String(claimBody.authEmail ?? "");
    if (!accountId || !authEmail) return apiError(503, "service_unavailable", "Account service unavailable");

    let authUserId = "";
    const { data: created, error: createError } = await admin.auth.admin.createUser({
      email: authEmail,
      password: tempPassword,
      email_confirm: true,
      user_metadata: { opentrack_username: username },
    });
    if (!createError && created.user) {
      authUserId = created.user.id;
    } else {
      // Auth Admin responses can be ambiguous if the request reached Auth before a
      // network error. Resolve only this unguessable, request-scoped internal email.
      const { data: racedId, error: lookupError } = await admin.rpc("opentrack_auth_user_id_by_email", { p_auth_email: authEmail });
      authUserId = !lookupError && typeof racedId === "string" ? racedId : "";
      if (!authUserId) {
        // Auth may have committed despite a lost response. Keep the private claim
        // reserved until its opaque email can be checked, rather than allowing a
        // second username claim while an Auth identity may still exist.
        return apiError(503, "provision_cleanup_required", "Account provisioning is incomplete and reserved for operator cleanup");
      }
    }

    const { error: passwordError } = await admin.auth.admin.updateUserById(authUserId, { password: tempPassword, email_confirm: true });
    if (passwordError) {
      const authDeleted = await deleteAuthUser(admin, authUserId);
      if (authDeleted) await abortProvision(admin, requestId, leaseToken, member.userId);
      return apiError(503, authDeleted ? "provision_failed" : "provision_cleanup_required",
        authDeleted ? "Account could not be provisioned; retry with a new requestId" : "Account provisioning is incomplete and reserved for operator cleanup");
    }

    const { data: finalized, error: finalizeError } = await admin.rpc("opentrack_finalize_account", {
      p_request_id: requestId,
      p_lease_token: leaseToken,
      p_auth_user_id: authUserId,
      p_actor_user_id: member.userId,
    });
    if (finalizeError) {
      // A transport error may happen after Postgres committed. Check state before
      // compensating so an active account is never left pointing at a deleted user.
      await rollbackIfStillProvisioning(admin, accountId, requestId, leaseToken, member.userId, authUserId);
      return apiError(503, "provision_failed", "Account provisioning did not complete; use password reset if the account appears active");
    }
    const final = finalized as { httpStatus?: number; body?: Record<string, unknown> } | null;
    if (final?.httpStatus !== 200 && final?.httpStatus !== 201) {
      await rollbackIfStillProvisioning(admin, accountId, requestId, leaseToken, member.userId, authUserId);
      return resultResponse(finalized);
    }
    return Response.json({
      accountId,
      username,
      displayName,
      status: "active",
      mustRotatePassword: true,
      temporaryPassword: tempPassword,
      requestId,
    }, { status: 201, headers: NO_STORE });
  } catch (error) {
    return authFaultResponse(error);
  }
}
