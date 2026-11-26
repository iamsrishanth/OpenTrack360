import { createHash, randomBytes, randomUUID } from "node:crypto";
import type { ApiServices, JsonRecord, MemberContext, RpcEnvelope } from "./types";

const SITE_ORIGIN = (process.env.NEXT_PUBLIC_SITE_URL || "https://track.srishanth.me").replace(/\/$/, "");
const PET_PHOTO_BUCKET = "pet-photos";
const PET_PHOTO_UPLOAD_TTL_SECONDS = 300;
const UPSTREAM_TIMEOUT_MS = 8_000;
const MAX_UPSTREAM_RESPONSE_BYTES = 1_048_576;

function genericFailure(status = 503): RpcEnvelope {
  return {
    httpStatus: status,
    body: { error: { code: status === 429 ? "RATE_LIMITED" : "SERVICE_UNAVAILABLE", message: status === 429 ? "Try again later" : "Service temporarily unavailable" } },
  };
}

function object(value: unknown): JsonRecord | null {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as JsonRecord : null;
}

function envelope(value: unknown, fallbackStatus: number): RpcEnvelope {
  const record = object(value);
  if (record && Number.isInteger(record.httpStatus) && typeof record.body === "object") {
    const status = record.httpStatus as number;
    if (status >= 200 && status <= 599) return { httpStatus: status, body: record.body };
  }
  return { httpStatus: fallbackStatus, body: value };
}

function supabaseConfig(): { url: string; secretKey: string } | null {
  const url = process.env.SUPABASE_URL || process.env.NEXT_PUBLIC_SUPABASE_URL;
  const secretKey = process.env.SUPABASE_SECRET_KEY;
  if (!url || !secretKey) return null;
  return { url: url.replace(/\/$/, ""), secretKey };
}

/** Service-role-only PostgREST RPC transport. Errors are deliberately not logged or echoed. */
export async function callPrivateRpc(name: string, args: JsonRecord): Promise<RpcEnvelope> {
  const config = supabaseConfig();
  if (!config) return genericFailure();
  try {
    const response = await fetch(`${config.url}/rest/v1/rpc/${encodeURIComponent(name)}`, {
      method: "POST",
      headers: {
        apikey: config.secretKey,
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: JSON.stringify(args),
      cache: "no-store",
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
    const contentLength = Number(response.headers.get("content-length") || 0);
    if (contentLength > MAX_UPSTREAM_RESPONSE_BYTES) return genericFailure();
    let payload: unknown = null;
    try {
      const text = await response.text();
      if (new TextEncoder().encode(text).byteLength > MAX_UPSTREAM_RESPONSE_BYTES) return genericFailure();
      if (text) payload = JSON.parse(text);
    } catch {
      return genericFailure();
    }
    if (!response.ok) return genericFailure(response.status === 429 ? 429 : 503);
    return envelope(payload, response.status);
  } catch {
    return genericFailure();
  }
}

function memberArgs(member: MemberContext, operation: string, args: JsonRecord): JsonRecord {
  return {
    p_user_id: member.userId,
    p_session_id: member.sessionId,
    p_operation: operation,
    p_args: args,
    p_sharing_epoch: member.sharingEpoch,
  };
}

export async function stopSharingRpc(member: MemberContext, clientRequestId: string): Promise<RpcEnvelope> {
  return callPrivateRpc("opentrack_stop_sharing", {
    p_user_id: member.userId,
    p_session_id: member.sessionId,
    p_client_request_id: clientRequestId,
  });
}

function bodyData(envelopeValue: RpcEnvelope): JsonRecord | null {
  const body = object(envelopeValue.body);
  if (!body) return null;
  return object(body.data) ?? body;
}

function errorEnvelope(status: number, code: string, message: string): RpcEnvelope {
  return { httpStatus: status, body: { error: { code, message } } };
}

function notFoundGuest(): RpcEnvelope {
  return errorEnvelope(404, "NOT_FOUND", "Not found");
}

function endedGuest(): RpcEnvelope {
  return errorEnvelope(410, "GONE", "This link has ended");
}

export function createReadSecret(): { secret: string; secretHash: string } {
  const secret = randomBytes(32).toString("base64url");
  return { secret, secretHash: createHash("sha256").update(secret, "utf8").digest("hex") };
}

export function hashReadSecret(secret: string): string {
  return createHash("sha256").update(secret, "utf8").digest("hex");
}

function isUuid(value: unknown): value is string {
  return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function metadata(envelopeValue: RpcEnvelope): JsonRecord | null {
  return bodyData(envelopeValue);
}

async function createSignedPetPhotoReadUrl(petId: string, objectPath: unknown): Promise<string | null | false> {
  if (objectPath === null || objectPath === undefined || objectPath === "") return null;
  if (typeof objectPath !== "string" || objectPath.startsWith("/") || objectPath.split("/").some((part) => !part || part === "." || part === "..")) return false;
  if (objectPath.split("/")[0] !== petId) return false;
  const config = supabaseConfig();
  if (!config) return false;
  const encodedPath = objectPath.split("/").map(encodeURIComponent).join("/");
  try {
    const response = await fetch(`${config.url}/storage/v1/object/sign/${PET_PHOTO_BUCKET}/${encodedPath}`, {
      method: "POST",
      headers: {
        apikey: config.secretKey,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ expiresIn: PET_PHOTO_UPLOAD_TTL_SECONDS }),
      cache: "no-store",
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
    if (!response.ok) return false;
    const signed = object(await response.json());
    if (!signed || typeof signed.signedURL !== "string") return false;
    return new URL(signed.signedURL, `${config.url}/`).toString();
  } catch {
    return false;
  }
}

function stripPetObjectPaths(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stripPetObjectPaths);
  const record = object(value);
  if (!record) return value;
  const safe: JsonRecord = {};
  for (const [key, entry] of Object.entries(record)) {
    if (key !== "photoObjectPath") safe[key] = stripPetObjectPaths(entry);
  }
  return safe;
}

async function signPetProfile(value: unknown, ownerDetail: boolean): Promise<JsonRecord | null> {
  const profile = object(value);
  if (!profile || !isUuid(profile.id) || typeof profile.name !== "string" ||
      !(profile.species === undefined || profile.species === null || typeof profile.species === "string") ||
      !Array.isArray(profile.circleIds) || !profile.circleIds.every(isUuid) || typeof profile.recoveryEnabled !== "boolean" ||
      (profile.createdAt !== undefined && (typeof profile.createdAt !== "string" || !Number.isFinite(Date.parse(profile.createdAt))))) return null;
  if (ownerDetail) {
    const allowedRecoveryFields = ["petName", "species", "photo", "ownerMessage", "contactRoute"];
    if (!Array.isArray(profile.recoveryFields) || !profile.recoveryFields.every((field) => typeof field === "string" && allowedRecoveryFields.includes(field)) ||
        new Set(profile.recoveryFields).size !== profile.recoveryFields.length ||
        !(profile.species === null || typeof profile.species === "string") ||
        typeof profile.ownerMessage !== "string" || [...profile.ownerMessage].length > 500 ||
        !(profile.contactRoute === null || (typeof profile.contactRoute === "string" && [...profile.contactRoute].length <= 200)) ||
        typeof profile.createdAt !== "string") return null;
  }
  const signedPhoto = await createSignedPetPhotoReadUrl(profile.id, profile.photoObjectPath);
  if (signedPhoto === false) return null;
  const safe: JsonRecord = { ...profile, photoUrl: signedPhoto };
  delete safe.photoObjectPath;
  if (!ownerDetail) {
    delete safe.recoveryFields;
    delete safe.ownerMessage;
    delete safe.contactRoute;
  }
  return safe;
}

async function signPetDispatchResult(operation: string, result: RpcEnvelope): Promise<RpcEnvelope> {
  if (result.httpStatus < 200 || result.httpStatus >= 300) {
    return { ...result, body: stripPetObjectPaths(result.body) };
  }
  const body = object(result.body);
  if (!body) return genericFailure();
  if (operation === "listPets") {
    if (!Array.isArray(body.data) || !(body.nextCursor === null || typeof body.nextCursor === "string")) return genericFailure();
    const signedProfiles = await Promise.all(body.data.map((profile) => signPetProfile(profile, false)));
    if (signedProfiles.some((profile) => profile === null)) return genericFailure();
    return { ...result, body: stripPetObjectPaths({ ...body, data: signedProfiles }) };
  }

  const nestedProfile = object(body.data);
  const profile = await signPetProfile(nestedProfile ?? body, true);
  if (!profile) return genericFailure();
  const safeBody = nestedProfile ? { ...body, data: profile } : profile;
  return { ...result, body: stripPetObjectPaths(safeBody) };
}

export function createDefaultApiServices(requireMember: ApiServices["requireMember"]): ApiServices {
  return {
    requireMember,
    async dispatchMember(member, operation, args) {
      if (operation === "listShares") {
        return callPrivateRpc("opentrack_share_list", {
          p_user_id: member.userId,
          p_session_id: member.sessionId,
        });
      }
      if (operation === "revokeShare") {
        const path = object(args.path);
        return callPrivateRpc("opentrack_share_revoke", {
          p_user_id: member.userId,
          p_session_id: member.sessionId,
          p_share_id: path?.shareId,
        });
      }
      const result = await callPrivateRpc("opentrack_api_dispatch", memberArgs(member, operation, args));
      if (operation === "listPets" || operation === "getPet" || operation === "createPet" || operation === "updatePet") {
        return signPetDispatchResult(operation, result);
      }
      return result;
    },
    async uploadLocationBatch(member, clientSharingEpoch, samples) {
      return callPrivateRpc("opentrack_location_batch", {
        p_user_id: member.userId,
        p_session_id: member.sessionId,
        p_sharing_epoch: clientSharingEpoch,
        p_samples: samples,
      });
    },
    async createShare(member, input, secret, secretHash) {
      const destination = object(input.destination);
      const result = await callPrivateRpc("opentrack_share_create", {
        p_user_id: member.userId,
        p_session_id: member.sessionId,
        p_sharing_epoch: member.sharingEpoch,
        p_mode: input.mode,
        p_expires_at: input.mode === "time" ? input.expiresAt : null,
        p_destination: input.mode === "destination" ? destination : null,
        p_confirmed_drive: input.confirmedDrive === true,
        p_secret_hash: secretHash,
      });
      if (result.httpStatus < 200 || result.httpStatus >= 300) return result;
      const body = metadata(result);
      if (!body || !isUuid(body.id)) return genericFailure();
      return { httpStatus: result.httpStatus, body: { ...body, url: `${SITE_ORIGIN}/s/${body.id}#${secret}` } };
    },
    async readShare(shareId, secretHash) {
      const result = await callPrivateRpc("opentrack_share_read", { p_share_id: shareId, p_secret_hash: secretHash });
      if (result.httpStatus === 404) return notFoundGuest();
      if (result.httpStatus === 410) return endedGuest();
      if (result.httpStatus !== 200) return genericFailure(result.httpStatus === 429 ? 429 : 503);
      const body = bodyData(result);
      if (!body || !["waiting", "active", "signal_lost"].includes(String(body.status)) ||
          typeof body.serverTime !== "string" || typeof body.viewValidUntil !== "string" || !(body.latest === null || object(body.latest))) {
        return genericFailure();
      }
      if (body.status !== "active" || body.latest === null) body.latest = null;
      return { httpStatus: 200, body };
    },
    async submitShareEvent(member, shareId, event) {
      return callPrivateRpc("opentrack_share_event", {
        p_user_id: member.userId,
        p_session_id: member.sessionId,
        p_share_id: shareId,
        p_event: event,
      });
    },
    async readPetRecovery(petId, secretHash) {
      const result = await callPrivateRpc("opentrack_pet_recovery_read", { p_pet_id: petId, p_secret_hash: secretHash });
      if (result.httpStatus === 404 || result.httpStatus === 410) return notFoundGuest();
      if (result.httpStatus !== 200) return genericFailure(result.httpStatus === 429 ? 429 : 503);
      const body = bodyData(result);
      if (!body) return genericFailure();
      const photoObjectPath = body.photoObjectPath ?? body.photoUrl;
      delete body.photoObjectPath;
      delete body.photoUrl;
      const signedPhoto = await createSignedPetPhotoReadUrl(petId, photoObjectPath);
      if (signedPhoto === false) return genericFailure();
      if (signedPhoto) body.photoUrl = signedPhoto;
      if (body.petName === null || body.petName === undefined) body.petName = "";
      if (body.ownerMessage === null || body.ownerMessage === undefined) body.ownerMessage = "";
      if (typeof body.petName !== "string" || typeof body.ownerMessage !== "string") return genericFailure();
      return { httpStatus: 200, body };
    },
    async submitPetSighting(petId, secretHash, sighting) {
      const result = await callPrivateRpc("opentrack_pet_sighting", {
        p_pet_id: petId,
        p_secret_hash: secretHash,
        p_sighting: sighting,
      });
      if (result.httpStatus === 404 || result.httpStatus === 410) return notFoundGuest();
      return result;
    },
    async createPetRecoveryLink(member, petId, secret, secretHash) {
      const created = await this.dispatchMember(member, "createPetRecoveryLink", {
        path: { petId },
        query: {},
        body: { secretHash },
      });
      if (created.httpStatus < 200 || created.httpStatus >= 300) return created;
      const body = metadata(created);
      if (!body || !isUuid(body.id) || typeof body.createdAt !== "string" || !Number.isFinite(Date.parse(body.createdAt)) ||
          !(body.expiresAt === null || (typeof body.expiresAt === "string" && Number.isFinite(Date.parse(body.expiresAt))))) return genericFailure();
      return {
        httpStatus: 201,
        body: { id: body.id, createdAt: body.createdAt, expiresAt: body.expiresAt },
      };
    },
    async createPetPhotoUpload(member, petId, input) {
      const extension = input.contentType === "image/png" ? "png" : input.contentType === "image/webp" ? "webp" : "jpg";
      const objectPath = `${petId}/${randomUUID()}.${extension}`;
      const authorized = await this.dispatchMember(member, "createPetPhotoUpload", {
        path: { petId },
        query: {},
        body: input,
      });
      if (authorized.httpStatus < 200 || authorized.httpStatus >= 300) return authorized;
      const config = supabaseConfig();
      if (!config) return genericFailure();
      const encodedPath = objectPath.split("/").map(encodeURIComponent).join("/");
      try {
        const response = await fetch(`${config.url}/storage/v1/object/upload/sign/${PET_PHOTO_BUCKET}/${encodedPath}`, {
          method: "POST",
          headers: {
            apikey: config.secretKey,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ expiresIn: PET_PHOTO_UPLOAD_TTL_SECONDS, upsert: false }),
          cache: "no-store",
          signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
        });
        if (!response.ok) return genericFailure(response.status === 429 ? 429 : 503);
        const signed = object(await response.json());
        if (!signed || typeof signed.signedURL !== "string") return genericFailure();
        const uploadUrl = new URL(signed.signedURL, `${config.url}/`).toString();
        return {
          httpStatus: 201,
          body: {
            uploadUrl,
            objectPath,
            expiresAt: new Date(Date.now() + PET_PHOTO_UPLOAD_TTL_SECONDS * 1000).toISOString(),
          },
        };
      } catch {
        return genericFailure();
      }
    },
  };
}

/** Compose one-time secret-bearing create response without ever storing the clear secret. */
export function addSecretUrl(result: RpcEnvelope, pathPrefix: "s" | "p", id: string, secret: string): RpcEnvelope {
  if (result.httpStatus < 200 || result.httpStatus >= 300) return result;
  const body = metadata(result);
  if (!body) return genericFailure();
  return { httpStatus: result.httpStatus, body: { ...body, url: `${SITE_ORIGIN}/${pathPrefix}/${id}#${secret}` } };
}

/** Return the contract's one-time pet-link shape without forwarding private RPC fields. */
export function addPetRecoverySecretUrl(result: RpcEnvelope, petId: string, secret: string): RpcEnvelope {
  if (result.httpStatus < 200 || result.httpStatus >= 300) return result;
  const body = metadata(result);
  if (!body || !isUuid(body.id) || typeof body.createdAt !== "string" || !Number.isFinite(Date.parse(body.createdAt)) ||
      !(body.expiresAt === null || (typeof body.expiresAt === "string" && Number.isFinite(Date.parse(body.expiresAt))))) {
    return genericFailure();
  }
  return {
    httpStatus: 201,
    body: {
      id: body.id,
      url: `${SITE_ORIGIN}/p/${petId}#${secret}`,
      createdAt: body.createdAt,
      expiresAt: body.expiresAt,
    },
  };
}

/** Convert the one-time SQL invite token to its fragment-only URL shape. */
export function addCircleInviteFragmentUrl(result: RpcEnvelope): RpcEnvelope {
  if (result.httpStatus < 200 || result.httpStatus >= 300) return result;
  const body = metadata(result);
  if (!body || !isUuid(body.id) || !isUuid(body.circleId) ||
      typeof body.inviteToken !== "string" || body.inviteToken.length < 32 || !/^[A-Za-z0-9_-]+$/.test(body.inviteToken) ||
      (body.role !== "admin" && body.role !== "member") ||
      typeof body.expiresAt !== "string" || !Number.isFinite(Date.parse(body.expiresAt)) ||
      !Number.isInteger(body.maxUses) || (body.maxUses as number) < 1 || (body.maxUses as number) > 100 ||
      typeof body.createdAt !== "string" || !Number.isFinite(Date.parse(body.createdAt))) {
    return genericFailure();
  }
  const { inviteToken } = body;
  return {
    httpStatus: 201,
    body: {
      id: body.id,
      circleId: body.circleId,
      url: `${SITE_ORIGIN}/i#${inviteToken}`,
      role: body.role,
      expiresAt: body.expiresAt,
      maxUses: body.maxUses,
      createdAt: body.createdAt,
    },
  };
}

export function normalizeMemberDispatch(result: RpcEnvelope, fallbackStatus: number): RpcEnvelope {
  if (result.httpStatus >= 200 && result.httpStatus < 300 && result.httpStatus === 200 && fallbackStatus !== 200) {
    return { ...result, httpStatus: fallbackStatus };
  }
  return result;
}
