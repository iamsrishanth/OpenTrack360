import { getAdminSupabase } from "./supabase";

export type MemberContext = {
  userId: string;
  username: string;
  sessionId: string;
  mustRotatePassword: boolean;
  sharingEpoch: number;
  isPlatformAdmin: boolean;
};

export class AuthFault extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "AuthFault";
    this.status = status;
    this.code = code;
  }
}

function unauthorized(): AuthFault {
  return new AuthFault(401, "unauthorized", "Unauthorized");
}

export function jwtSessionId(token: string): string | null {
  try {
    const part = token.split(".")[1];
    if (!part) return null;
    const claims = JSON.parse(Buffer.from(part, "base64url").toString("utf8")) as Record<string, unknown>;
    const id = claims.session_id ?? claims.sid;
    return typeof id === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id)
      ? id
      : null;
  } catch {
    return null;
  }
}

export async function requireMember(
  request: Request,
  options: { allowPasswordRotation?: boolean } = {},
): Promise<MemberContext> {
  const authorization = request.headers.get("authorization") ?? "";
  const match = /^Bearer\s+([A-Za-z0-9._~-]+)$/i.exec(authorization);
  if (!match) throw unauthorized();
  const token = match[1];
  const sessionId = jwtSessionId(token);
  if (!sessionId) throw unauthorized();

  const admin = getAdminSupabase();
  const { data: userResult, error: userError } = await admin.auth.getUser(token);
  if (userError || !userResult.user) throw unauthorized();

  const { data: sessionResult, error: sessionError } = await admin.rpc("opentrack_validate_member_session", {
    p_user_id: userResult.user.id,
    p_session_id: sessionId,
  });
  if (sessionError) throw new AuthFault(503, "service_unavailable", "Authentication service unavailable");
  const session = sessionResult as Record<string, unknown> | null;
  if (!session || session.allowed !== true) throw unauthorized();

  const context: MemberContext = {
    userId: userResult.user.id,
    username: String(session.username ?? ""),
    sessionId,
    mustRotatePassword: session.mustRotatePassword === true,
    sharingEpoch: Number(session.sharingEpoch ?? 0),
    isPlatformAdmin: session.isPlatformAdmin === true,
  };
  if (!context.username || !Number.isSafeInteger(context.sharingEpoch) || context.sharingEpoch < 1) throw unauthorized();
  if (context.mustRotatePassword && options.allowPasswordRotation !== true) {
    throw new AuthFault(428, "password_rotation_required", "Password rotation required");
  }
  return context;
}

export function readAdminCookie(request: Request, name: "ot_admin_access" | "ot_admin_refresh" | "ot_admin_csrf"): string | null {
  const cookieHeader = request.headers.get("cookie") ?? "";
  for (const part of cookieHeader.split(";")) {
    const separator = part.indexOf("=");
    if (separator < 0) continue;
    if (part.slice(0, separator).trim() !== name) continue;
    try {
      return decodeURIComponent(part.slice(separator + 1).trim());
    } catch {
      return null;
    }
  }
  return null;
}

export async function requireAdminSession(
  request: Request,
  options: { allowPasswordRotation?: boolean } = {},
): Promise<MemberContext> {
  const token = readAdminCookie(request, "ot_admin_access");
  if (!token) throw unauthorized();
  const headers = new Headers(request.headers);
  headers.set("authorization", `Bearer ${token}`);
  const context = await requireMember(new Request(request.url, { method: "GET", headers }), options);
  if (!context.isPlatformAdmin) throw new AuthFault(403, "forbidden", "Forbidden");
  return context;
}

export function requireSameOrigin(request: Request): void {
  const origin = request.headers.get("origin");
  if (!origin || origin !== new URL(request.url).origin) {
    throw new AuthFault(403, "forbidden", "Forbidden");
  }
}

export function requireAdminCsrf(request: Request): void {
  requireSameOrigin(request);
  const csrfCookie = readAdminCookie(request, "ot_admin_csrf");
  const csrfHeader = request.headers.get("x-csrf-token");
  if (!csrfCookie || !csrfHeader || csrfCookie.length < 32 || csrfCookie !== csrfHeader) {
    throw new AuthFault(403, "csrf_failed", "Request validation failed");
  }
}

export function authFaultResponse(error: unknown): Response {
  if (error instanceof AuthFault) {
    return Response.json({ error: { code: error.code, message: error.message } }, {
      status: error.status,
      headers: { "Cache-Control": "no-store" },
    });
  }
  return Response.json({ error: { code: "internal_error", message: "Request could not be completed" } }, {
    status: 500,
    headers: { "Cache-Control": "no-store" },
  });
}
