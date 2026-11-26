import { createHash, randomUUID } from "node:crypto";
import { resolveApiRoute, validatePlanParams } from "./routes";
import { addCircleInviteFragmentUrl, addPetRecoverySecretUrl, addSecretUrl, createReadSecret, hashReadSecret, normalizeMemberDispatch } from "./rpc";
import { ApiFault, type ApiServices, type JsonRecord, type RpcEnvelope } from "./types";
import { isDateTime, readBearerSecret, validateBodyForRoute } from "./validation";

const MAX_JSON_BODY_BYTES = 256 * 1024;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function stripServerOnlyFields(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stripServerOnlyFields);
  if (value === null || typeof value !== "object") return value;
  const safe: JsonRecord = {};
  for (const [key, entry] of Object.entries(value)) {
    if (key !== "photoObjectPath" && key !== "inviteToken") safe[key] = stripServerOnlyFields(entry);
  }
  return safe;
}

function makeResponse(status: number, body: unknown, publicResponse = false, extraHeaders?: HeadersInit): Response {
  const headers = new Headers(extraHeaders);
  headers.set("Content-Type", "application/json; charset=utf-8");
  headers.set("Cache-Control", "private, no-store");
  headers.set("Vary", "Authorization");
  headers.set("X-Content-Type-Options", "nosniff");
  headers.set("Referrer-Policy", "no-referrer");
  if (publicResponse) headers.set("X-Robots-Tag", "noindex, nofollow, noarchive");
  return new Response(JSON.stringify(stripServerOnlyFields(body)), { status, headers });
}

function apiError(status: number, code: string, message: string, extraHeaders?: HeadersInit): Response {
  return makeResponse(status, { error: { code, message } }, false, extraHeaders);
}

function guestError(status: number, code: string, message: string): Response {
  return makeResponse(status, { error: { code, message } }, true);
}

function badRequest(message: string): never {
  throw new ApiFault(400, "INVALID_REQUEST", message);
}

function readParams(pathname: string): string[] {
  if (!pathname.startsWith("/api/v1/")) return [];
  const encoded = pathname.slice("/api/v1/".length).replace(/\/$/, "");
  if (!encoded) return [];
  try {
    return encoded.split("/").map((segment) => decodeURIComponent(segment));
  } catch {
    throw new ApiFault(400, "INVALID_REQUEST", "Malformed path");
  }
}

async function parseJsonBody(request: Request): Promise<unknown> {
  const contentType = (request.headers.get("content-type") || "").split(";", 1)[0].trim().toLowerCase();
  if (contentType !== "application/json" && !contentType.endsWith("+json")) {
    throw new ApiFault(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json");
  }
  const length = Number(request.headers.get("content-length") || 0);
  if (Number.isFinite(length) && length > MAX_JSON_BODY_BYTES) throw new ApiFault(413, "PAYLOAD_TOO_LARGE", "Request body is too large");
  const text = await request.text();
  if (new TextEncoder().encode(text).byteLength > MAX_JSON_BODY_BYTES) throw new ApiFault(413, "PAYLOAD_TOO_LARGE", "Request body is too large");
  if (!text) badRequest("A JSON request body is required");
  try {
    return JSON.parse(text) as unknown;
  } catch {
    badRequest("Request body must be valid JSON");
  }
}

function queryObject(url: URL): JsonRecord {
  const query: JsonRecord = {};
  const allowed = new Set(["from", "to", "limit", "cursor", "memberId"]);
  for (const [key, value] of url.searchParams.entries()) {
    if (key === "secret" || key === "token" || key.toLowerCase().includes("secret")) {
      throw new ApiFault(400, "INVALID_REQUEST", "Credentials are accepted only in the Authorization header");
    }
    if (!allowed.has(key)) continue;
    query[key] = value;
  }
  if (query.limit !== undefined) {
    if (!/^\d+$/.test(String(query.limit))) throw new ApiFault(400, "INVALID_REQUEST", "limit must be an integer");
    const limit = Number(query.limit);
    const max = url.pathname.endsWith("/notifications") ? 100 : 1000;
    if (limit < 1 || limit > max) throw new ApiFault(400, "INVALID_REQUEST", `limit must be between 1 and ${max}`);
    query.limit = limit;
  }
  if (query.limit === undefined && url.pathname.endsWith("/notifications")) query.limit = 50;
  if (query.limit === undefined && url.pathname.endsWith("/history")) query.limit = 500;
  if (typeof query.cursor === "string" && query.cursor.length > 512) throw new ApiFault(400, "INVALID_REQUEST", "cursor is too long");
  if (query.memberId !== undefined && !UUID_RE.test(String(query.memberId))) throw new ApiFault(400, "INVALID_REQUEST", "memberId must be a UUID");
  if (url.pathname.endsWith("/history")) {
    if (!isDateTime(query.from) || !isDateTime(query.to)) {
      throw new ApiFault(400, "INVALID_REQUEST", "from and to are required ISO-8601 timestamps");
    }
    if (Date.parse(query.from) >= Date.parse(query.to)) throw new ApiFault(400, "INVALID_REQUEST", "from must be before to");
    const days = (Date.parse(query.to) - Date.parse(query.from)) / 86_400_000;
    if (days > 90) throw new ApiFault(400, "INVALID_REQUEST", "History range cannot exceed 90 days");
  }
  return query;
}

function envelopeResponse(result: RpcEnvelope, fallbackStatus: number, publicResponse = false): Response {
  if (!Number.isInteger(result.httpStatus) || result.httpStatus < 200 || result.httpStatus > 599) {
    return apiError(502, "UPSTREAM_ERROR", "The request could not be completed");
  }
  const body = result.body ?? {};
  return makeResponse(result.httpStatus || fallbackStatus, body, publicResponse);
}

function authFailure(error: unknown): Response {
  if (error instanceof ApiFault) {
    return apiError(error.status, error.code, error.status === 401 ? "Unable to authenticate" : error.status === 428 ? "Change your password to continue" : error.message);
  }
  if (error !== null && typeof error === "object") {
    const value = error as { status?: unknown; code?: unknown };
    if (value.status === 428) return apiError(428, "PASSWORD_ROTATION_REQUIRED", "Change your password to continue");
    if (value.status === 401 || value.status === 403) return apiError(401, "UNAUTHORIZED", "Unable to authenticate");
  }
  return apiError(401, "UNAUTHORIZED", "Unable to authenticate");
}

function hasOwn(object: object, property: string): boolean {
  return Object.prototype.hasOwnProperty.call(object, property);
}

function validateDispatchResult(value: unknown, routeName: string): RpcEnvelope {
  if (value === null || typeof value !== "object" || !hasOwn(value, "httpStatus") || !hasOwn(value, "body")) {
    return { httpStatus: 503, body: { error: { code: "SERVICE_UNAVAILABLE", message: "Service temporarily unavailable" } } };
  }
  const result = value as RpcEnvelope;
  if (routeName === "readPublicShare" && result.httpStatus === 200) {
    const body = result.body as JsonRecord;
    if (!body || !["waiting", "active", "signal_lost"].includes(String(body.status)) ||
        typeof body.serverTime !== "string" || !Number.isFinite(Date.parse(body.serverTime)) ||
        typeof body.viewValidUntil !== "string" || !Number.isFinite(Date.parse(body.viewValidUntil)) ||
        !(body.latest === null || (body.latest && typeof body.latest === "object" && !Array.isArray(body.latest)))) {
      return { httpStatus: 503, body: { error: { code: "SERVICE_UNAVAILABLE", message: "Service temporarily unavailable" } } };
    }
    let latest: JsonRecord | null = null;
    if (body.status === "active" && body.latest !== null) {
      const fix = body.latest as JsonRecord;
      if (typeof fix.latitude !== "number" || !Number.isFinite(fix.latitude) || fix.latitude < -90 || fix.latitude > 90 ||
          typeof fix.longitude !== "number" || !Number.isFinite(fix.longitude) || fix.longitude < -180 || fix.longitude > 180 ||
          typeof fix.accuracyMeters !== "number" || !Number.isFinite(fix.accuracyMeters) || fix.accuracyMeters < 0 ||
          typeof fix.capturedAt !== "string" || !Number.isFinite(Date.parse(fix.capturedAt)) ||
          !(fix.speedMps === null || (typeof fix.speedMps === "number" && Number.isFinite(fix.speedMps) && fix.speedMps >= 0 && fix.speedMps <= 150))) {
        return { httpStatus: 503, body: { error: { code: "SERVICE_UNAVAILABLE", message: "Service temporarily unavailable" } } };
      }
      latest = {
        latitude: fix.latitude,
        longitude: fix.longitude,
        accuracyMeters: fix.accuracyMeters,
        capturedAt: fix.capturedAt,
        speedMps: fix.speedMps,
      };
    }
    result.body = { status: body.status, serverTime: body.serverTime, viewValidUntil: body.viewValidUntil, latest };
  }
  return result;
}

function shareDigestFromRequest(request: Request): { secret: string; hash: string } | null {
  const secret = readBearerSecret(request);
  return secret ? { secret, hash: hashReadSecret(secret) } : null;
}

function bodyObject(result: RpcEnvelope): JsonRecord | null {
  if (!result.body || typeof result.body !== "object" || Array.isArray(result.body)) return null;
  const body = result.body as JsonRecord;
  if (body.data && typeof body.data === "object" && !Array.isArray(body.data)) return body.data as JsonRecord;
  return body;
}

function safeGuestTerminal(result: RpcEnvelope): RpcEnvelope {
  if (result.httpStatus === 404) return { httpStatus: 404, body: { error: { code: "NOT_FOUND", message: "Not found" } } };
  if (result.httpStatus === 410) return { httpStatus: 410, body: { error: { code: "GONE", message: "This link has ended" } } };
  return result;
}

export function createApiDispatcher(services: ApiServices) {
  return async function dispatchApiRequest(request: Request): Promise<Response> {
    const requestId = randomUUID();
    try {
      const url = new URL(request.url);
      const segments = readParams(url.pathname);
      const match = resolveApiRoute(segments, request.method);
      if (!match.plan) {
        if (match.allowedMethods) return apiError(405, "METHOD_NOT_ALLOWED", "Method not allowed", { Allow: match.allowedMethods.join(", ") });
        return apiError(404, "NOT_FOUND", "Not found");
      }
      const plan = match.plan;
      try {
        validatePlanParams(plan);
      } catch {
        if (plan.auth !== "member") return guestError(404, "NOT_FOUND", "Not found");
        return apiError(400, "INVALID_REQUEST", "Path parameter is invalid");
      }
      if (plan.auth !== "member" && [...url.searchParams.keys()].some((key) => key === "secret" || key === "token" || key.toLowerCase().includes("secret"))) {
        return guestError(404, "NOT_FOUND", "Not found");
      }
      if (plan.operation === "acceptCircleInvite" && url.searchParams.size > 0) {
        return apiError(400, "INVALID_REQUEST", "Invitation credentials must be sent in the request body");
      }

      const query = queryObject(url);
      let credential: { secret: string; hash: string } | null = null;
      if (plan.auth !== "member") {
        credential = shareDigestFromRequest(request);
        if (!credential) return guestError(404, "NOT_FOUND", "Not found");
      }

      let body: JsonRecord | undefined;
      if (plan.body) body = validateBodyForRoute(plan.body, await parseJsonBody(request), plan.params);

      if (plan.auth === "share-secret" || plan.auth === "pet-secret") {
        if (!credential) return guestError(404, "NOT_FOUND", "Not found");
        if (plan.auth === "share-secret") {
          const raw = await services.readShare(plan.params.shareId, credential.hash);
          return envelopeResponse(safeGuestTerminal(validateDispatchResult(raw, plan.operation)), 200, true);
        }
        if (plan.operation === "readPetRecoveryCard") {
          const raw = await services.readPetRecovery(plan.params.petId, credential.hash);
          const safe = safeGuestTerminal(raw);
          if (safe.httpStatus === 200) {
            const card = safe.body as JsonRecord;
            if (!card || typeof card.petName !== "string" || typeof card.ownerMessage !== "string") return guestError(503, "SERVICE_UNAVAILABLE", "Service temporarily unavailable");
            const sanitized = {
              petName: card.petName,
              species: typeof card.species === "string" ? card.species : null,
              ...(typeof card.photoUrl === "string" ? { photoUrl: card.photoUrl } : {}),
              ownerMessage: card.ownerMessage.slice(0, 500),
              contactRoute: typeof card.contactRoute === "string" ? card.contactRoute : null,
            };
            return envelopeResponse({ httpStatus: 200, body: sanitized }, 200, true);
          }
          return envelopeResponse(safe, 200, true);
        }
        const raw = await services.submitPetSighting(plan.params.petId, credential.hash, body ?? {});
        return envelopeResponse(safeGuestTerminal(raw), 202, true);
      }

      let member: Awaited<ReturnType<ApiServices["requireMember"]>>;
      try {
        member = await services.requireMember(request, { allowPasswordRotation: false });
      } catch (error) {
        return authFailure(error);
      }

      let result: RpcEnvelope;
      if (plan.operation === "uploadLocationBatch") {
        const clientEpoch = body?.sharingEpoch;
        if (!Number.isSafeInteger(clientEpoch) || clientEpoch !== member.sharingEpoch) {
          return apiError(409, "SHARING_EPOCH_MISMATCH", "Refresh the member profile and discard queued samples from an older sharing epoch.");
        }
        result = await services.uploadLocationBatch(member, clientEpoch as number, (body?.samples as JsonRecord[]) ?? []);
      } else if (plan.operation === "createShare") {
        const secret = createReadSecret();
        result = await services.createShare(member, body ?? {}, secret.secret, secret.secretHash);
        if (result.httpStatus >= 200 && result.httpStatus < 300) {
          const created = bodyObject(result);
          if (!created || typeof created.id !== "string" || !UUID_RE.test(created.id)) {
            result = { httpStatus: 503, body: { error: { code: "SERVICE_UNAVAILABLE", message: "Service temporarily unavailable" } } };
          } else {
            result = addSecretUrl(result, "s", created.id, secret.secret);
          }
        }
      } else if (plan.operation === "submitShareEvent") {
        result = await services.submitShareEvent(member, plan.params.shareId, body ?? {});
      } else if (plan.operation === "createPetRecoveryLink") {
        const secret = createReadSecret();
        result = await services.createPetRecoveryLink(member, plan.params.petId, secret.secret, secret.secretHash);
        result = addPetRecoverySecretUrl(result, plan.params.petId, secret.secret);
      } else if (plan.operation === "createPetPhotoUpload") {
        result = await services.createPetPhotoUpload(member, plan.params.petId, body ?? {});
      } else if (plan.operation === "createCircleInvite") {
        const args: JsonRecord = { path: plan.params, query, body: body ?? {} };
        result = addCircleInviteFragmentUrl(normalizeMemberDispatch(await services.dispatchMember(member, plan.operation, args), plan.defaultStatus));
      } else if (plan.operation === "acceptCircleInvite") {
        const inviteToken = String(body?.inviteToken);
        const inviteTokenHash = createHash("sha256").update(inviteToken, "utf8").digest("hex");
        result = normalizeMemberDispatch(await services.dispatchMember(member, plan.operation, {
          path: {},
          query,
          body: { inviteTokenHash },
        }), plan.defaultStatus);
      } else {
        const args: JsonRecord = { path: plan.params, query };
        if (body !== undefined) args.body = body;
        result = normalizeMemberDispatch(await services.dispatchMember(member, plan.operation, args), plan.defaultStatus);
      }
      return envelopeResponse(result, plan.defaultStatus);
    } catch (error) {
      if (error instanceof ApiFault) return apiError(error.status, error.code, error.message);
      const headers = new Headers();
      headers.set("X-Request-Id", requestId);
      return makeResponse(500, { error: { code: "INTERNAL_ERROR", message: "The request could not be completed", requestId } }, false, headers);
    }
  };
}

export function publicResponseHeaders(): HeadersInit {
  return { "Cache-Control": "private, no-store", "Referrer-Policy": "no-referrer", "X-Robots-Tag": "noindex, nofollow, noarchive" };
}
