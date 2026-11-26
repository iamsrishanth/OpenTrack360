import { ApiFault, type ApiBodyKind, type JsonRecord } from "./types";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const ISO_DATE = /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?(?:Z|[+-]\d\d:\d\d)$/;
const SHARE_SECRET = /^[A-Za-z0-9_-]{40,64}$/;
const SAMPLE_KEYS = ["sampleId", "capturedAt", "latitude", "longitude", "accuracyMeters", "speedMps"];

export function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID.test(value);
}

export function isRecord(value: unknown): value is JsonRecord {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

export function assertRecord(value: unknown, label = "request body"): asserts value is JsonRecord {
  if (!isRecord(value)) throw new ApiFault(400, "INVALID_REQUEST", `${label} must be a JSON object`);
}

function assertOnlyKeys(value: JsonRecord, allowed: string[], label: string): void {
  const extra = Object.keys(value).find((key) => !allowed.includes(key));
  if (extra) throw new ApiFault(400, "INVALID_REQUEST", `${label} contains an unsupported field`);
}

export function assertUuid(value: unknown, label: string): asserts value is string {
  if (!isUuid(value)) throw new ApiFault(400, "INVALID_REQUEST", `${label} must be a UUID`);
}

function numberIn(value: unknown, min: number, max: number): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= min && value <= max;
}

function boundedString(value: unknown, label: string, minLength: number, maxLength: number): asserts value is string {
  if (typeof value !== "string" || [...value].length < minLength || [...value].length > maxLength) {
    throw new ApiFault(400, "INVALID_REQUEST", `${label} must contain ${minLength} to ${maxLength} characters`);
  }
}

function validateOptionalNullableString(value: unknown, label: string, maxLength: number): void {
  if (value !== undefined && value !== null && (typeof value !== "string" || [...value].length > maxLength)) {
    throw new ApiFault(400, "INVALID_REQUEST", `${label} must be null or a string of at most ${maxLength} characters`);
  }
}

function validateOptionalNullableDateTime(value: unknown, label: string): void {
  if (value !== undefined && value !== null && !isDateTime(value)) {
    throw new ApiFault(400, "INVALID_REQUEST", `${label} must be null or an ISO-8601 timestamp`);
  }
}

function validateUuidList(value: unknown, label: string, minItems: number, unique: boolean): asserts value is string[] {
  if (!Array.isArray(value) || value.length < minItems || !value.every(isUuid)) {
    throw new ApiFault(400, "INVALID_REQUEST", `${label} must contain at least ${minItems} UUID${minItems === 1 ? "" : "s"}`);
  }
  if (unique && new Set(value).size !== value.length) {
    throw new ApiFault(400, "INVALID_REQUEST", `${label} values must be unique`);
  }
}

function validatePetRoutineRecurrence(value: unknown): void {
  if (value === undefined || value === null) return;
  assertRecord(value, "recurrence");
  assertOnlyKeys(value, ["frequency", "interval", "weekdays", "localTime", "timeZone"], "recurrence");
  if (value.frequency !== "daily" && value.frequency !== "weekly") {
    throw new ApiFault(400, "INVALID_REQUEST", "recurrence.frequency must be daily or weekly");
  }
  if (!Number.isInteger(value.interval) || !numberIn(value.interval, 1, 52)) {
    throw new ApiFault(400, "INVALID_REQUEST", "recurrence.interval must be from 1 through 52");
  }
  if (value.weekdays !== undefined && (!Array.isArray(value.weekdays) || !value.weekdays.every((day) => Number.isInteger(day) && numberIn(day, 1, 7)))) {
    throw new ApiFault(400, "INVALID_REQUEST", "recurrence.weekdays must contain integers from 1 through 7");
  }
  if (value.localTime !== undefined && (typeof value.localTime !== "string" || !/^([01]?[0-9]|2[0-3]):[0-5][0-9]$/.test(value.localTime))) {
    throw new ApiFault(400, "INVALID_REQUEST", "recurrence.localTime must use 24-hour HH:mm format");
  }
  if (value.timeZone !== undefined && (typeof value.timeZone !== "string" || [...value.timeZone].length > 64)) {
    throw new ApiFault(400, "INVALID_REQUEST", "recurrence.timeZone must be at most 64 characters");
  }
}

function validatePlaceRuleRecurrence(value: unknown): void {
  if (value === undefined || value === null) return;
  assertRecord(value, "recurrence");
  assertOnlyKeys(value, ["frequency", "interval", "weekdays", "localTime", "timeZone"], "recurrence");
  if (value.frequency !== "daily" && value.frequency !== "weekly") {
    throw new ApiFault(400, "INVALID_REQUEST", "recurrence.frequency must be daily or weekly");
  }
  if (!Number.isInteger(value.interval) || !numberIn(value.interval, 1, 52)) {
    throw new ApiFault(400, "INVALID_REQUEST", "recurrence.interval must be from 1 through 52");
  }
  if (value.weekdays !== undefined && (!Array.isArray(value.weekdays) || !value.weekdays.every((day) => Number.isInteger(day) && numberIn(day, 1, 7)))) {
    throw new ApiFault(400, "INVALID_REQUEST", "recurrence.weekdays must contain integers from 1 through 7");
  }
  if (value.localTime !== undefined && (typeof value.localTime !== "string" || !/^([01]?[0-9]|2[0-3]):[0-5][0-9]$/.test(value.localTime))) {
    throw new ApiFault(400, "INVALID_REQUEST", "recurrence.localTime must use 24-hour HH:mm format");
  }
  if (value.timeZone !== undefined && typeof value.timeZone !== "string") {
    throw new ApiFault(400, "INVALID_REQUEST", "recurrence.timeZone must be a string");
  }
}

function validatePetPhotoObjectPath(value: unknown, petId: string): void {
  if (value === null) return;
  if (typeof value !== "string" || value.length > 240) {
    throw new ApiFault(400, "INVALID_REQUEST", "photoObjectPath must be null or a path for this pet");
  }
  const parts = value.split("/");
  if (parts.length !== 2 || parts[0] !== petId || parts.some((part) => !part || part === "." || part === "..") ||
      !/^[A-Za-z0-9_-]+\.(?:jpg|png|webp)$/.test(parts[1])) {
    throw new ApiFault(400, "INVALID_REQUEST", "photoObjectPath must be a photo path returned for this pet");
  }
}

function validateRecoveryFields(value: unknown, unique: boolean): void {
  const allowed = ["petName", "species", "photo", "ownerMessage", "contactRoute"];
  if (!Array.isArray(value) || !value.every((field) => typeof field === "string" && allowed.includes(field))) {
    throw new ApiFault(400, "INVALID_REQUEST", "recoveryFields contains an unsupported field");
  }
  if (unique && new Set(value).size !== value.length) {
    throw new ApiFault(400, "INVALID_REQUEST", "recoveryFields values must be unique");
  }
}

export function isDateTime(value: unknown): value is string {
  return typeof value === "string" && ISO_DATE.test(value) && Number.isFinite(Date.parse(value));
}

export function validateLocationSample(value: unknown, index: number): JsonRecord {
  assertRecord(value, `samples[${index}]`);
  const sample = value;
  assertOnlyKeys(sample, SAMPLE_KEYS, `samples[${index}]`);
  assertUuid(sample.sampleId, `samples[${index}].sampleId`);
  if (!isDateTime(sample.capturedAt)) throw new ApiFault(400, "INVALID_REQUEST", `samples[${index}].capturedAt must be an ISO-8601 timestamp`);
  if (!numberIn(sample.latitude, -90, 90) || !numberIn(sample.longitude, -180, 180)) {
    throw new ApiFault(400, "INVALID_REQUEST", `samples[${index}] has invalid coordinates`);
  }
  if (!numberIn(sample.accuracyMeters, 0, 10_000)) {
    throw new ApiFault(400, "INVALID_REQUEST", `samples[${index}].accuracyMeters is out of range`);
  }
  if (!(sample.speedMps === null || numberIn(sample.speedMps, 0, 150))) {
    throw new ApiFault(400, "INVALID_REQUEST", `samples[${index}].speedMps is out of range`);
  }
  return sample;
}

export function validateLocationBatch(value: unknown): { sharingEpoch: number; samples: JsonRecord[] } {
  assertRecord(value);
  assertOnlyKeys(value, ["sharingEpoch", "samples"], "request body");
  if (!Number.isSafeInteger(value.sharingEpoch) || (value.sharingEpoch as number) < 1) {
    throw new ApiFault(400, "INVALID_REQUEST", "sharingEpoch must be a positive integer");
  }
  if (!Array.isArray(value.samples) || value.samples.length < 1 || value.samples.length > 100) {
    throw new ApiFault(400, "INVALID_REQUEST", "samples must contain between 1 and 100 fixes");
  }
  const samples = value.samples.map(validateLocationSample);
  const seen = new Set<string>();
  for (const sample of samples) {
    if (seen.has(sample.sampleId as string)) throw new ApiFault(400, "INVALID_REQUEST", "sampleId values must be unique within a batch");
    seen.add(sample.sampleId as string);
  }
  return { sharingEpoch: value.sharingEpoch as number, samples };
}

export function validateShareCreate(value: unknown): JsonRecord {
  assertRecord(value);
  if (value.mode === "time") {
    assertOnlyKeys(value, ["mode", "expiresAt"], "request body");
    if (!isDateTime(value.expiresAt) || Date.parse(value.expiresAt) <= Date.now()) {
      throw new ApiFault(400, "INVALID_REQUEST", "time shares require an expiry in the future");
    }
    return value;
  }
  if (value.mode === "destination") {
    assertOnlyKeys(value, ["mode", "destination"], "request body");
    assertRecord(value.destination, "destination");
    const destination = value.destination;
    assertOnlyKeys(destination, ["latitude", "longitude", "radiusMeters"], "destination");
    if (!numberIn(destination.latitude, -90, 90) || !numberIn(destination.longitude, -180, 180) ||
        !Number.isInteger(destination.radiusMeters) || !numberIn(destination.radiusMeters, 50, 1000)) {
      throw new ApiFault(400, "INVALID_REQUEST", "destination coordinates or radius are invalid");
    }
    return value;
  }
  if (value.mode === "drive_end") {
    assertOnlyKeys(value, ["mode", "confirmedDrive"], "request body");
    if (value.confirmedDrive !== undefined && typeof value.confirmedDrive !== "boolean") {
      throw new ApiFault(400, "INVALID_REQUEST", "confirmedDrive must be a boolean");
    }
    return value;
  }
  throw new ApiFault(400, "INVALID_REQUEST", "mode must be time, destination, or drive_end");
}

export function validateShareEvent(value: unknown): JsonRecord {
  assertRecord(value);
  assertOnlyKeys(value, ["clientEventId", "eventType", "observedAt", "evidenceFixes"], "request body");
  assertUuid(value.clientEventId, "clientEventId");
  if (value.eventType !== "arrival" && value.eventType !== "drive_end") {
    throw new ApiFault(400, "INVALID_REQUEST", "eventType must be arrival or drive_end");
  }
  if (!isDateTime(value.observedAt)) throw new ApiFault(400, "INVALID_REQUEST", "observedAt must be an ISO-8601 timestamp");
  if (!Array.isArray(value.evidenceFixes) || value.evidenceFixes.length < 1 || value.evidenceFixes.length > 10) {
    throw new ApiFault(400, "INVALID_REQUEST", "evidenceFixes must contain between 1 and 10 fixes");
  }
  value.evidenceFixes = value.evidenceFixes.map(validateLocationSample);
  return value;
}

export function validateBodyForRoute(kind: ApiBodyKind, value: unknown, routeParams: Record<string, string> = {}): JsonRecord {
  if (kind === "location-batch") return validateLocationBatch(value);
  if (kind === "share-create") return validateShareCreate(value);
  if (kind === "share-event") return validateShareEvent(value);
  assertRecord(value);

  if (kind === "circle-create") {
    assertOnlyKeys(value, ["name", "kind", "expiresAt"], "request body");
    if (typeof value.name !== "string" || value.name.trim().length < 1 || value.name.length > 80) {
      throw new ApiFault(400, "INVALID_REQUEST", "name must contain 1 to 80 non-whitespace characters");
    }
    if (value.kind !== undefined && !(value.kind === "standard" || value.kind === "temporary" || value.kind === "lightweight")) {
      throw new ApiFault(400, "INVALID_REQUEST", "kind must be standard, temporary, or lightweight");
    }
    validateOptionalNullableDateTime(value.expiresAt, "expiresAt");
    if (value.kind === "temporary") {
      const expiresAt = typeof value.expiresAt === "string" ? Date.parse(value.expiresAt) : Number.NaN;
      if (!Number.isFinite(expiresAt) || expiresAt <= Date.now() || expiresAt > Date.now() + 30 * 24 * 60 * 60 * 1000) {
        throw new ApiFault(400, "INVALID_REQUEST", "Temporary Circles must expire within 30 days");
      }
    }
  } else if (kind === "circle-update") {
    assertOnlyKeys(value, ["name"], "request body");
    boundedString(value.name, "name", 1, 80);
  } else if (kind === "circle-sharing") {
    assertOnlyKeys(value, ["sharingEnabled"], "request body");
    if (typeof value.sharingEnabled !== "boolean") throw new ApiFault(400, "INVALID_REQUEST", "sharingEnabled must be a boolean");
  } else if (kind === "circle-invite-create") {
    assertOnlyKeys(value, ["clientRequestId", "role", "expiresAt", "maxUses"], "request body");
    assertUuid(value.clientRequestId, "clientRequestId");
    if (value.role !== undefined && value.role !== "admin" && value.role !== "member") {
      throw new ApiFault(400, "INVALID_REQUEST", "role must be admin or member");
    }
    if (value.expiresAt !== undefined && value.expiresAt !== null) {
      const expiresAt = isDateTime(value.expiresAt) ? Date.parse(value.expiresAt) : Number.NaN;
      const now = Date.now();
      if (!Number.isFinite(expiresAt) || expiresAt <= now || expiresAt > now + 72 * 60 * 60 * 1000) {
        throw new ApiFault(400, "INVALID_REQUEST", "expiresAt must be in the future and no more than 72 hours away");
      }
    }
    if (value.maxUses !== undefined && (!Number.isInteger(value.maxUses) || !numberIn(value.maxUses, 1, 100))) {
      throw new ApiFault(400, "INVALID_REQUEST", "maxUses must be an integer from 1 through 100");
    }
  } else if (kind === "circle-invite-accept") {
    assertOnlyKeys(value, ["inviteToken"], "request body");
    if (typeof value.inviteToken !== "string" || value.inviteToken.length < 32) {
      throw new ApiFault(400, "INVALID_REQUEST", "inviteToken must contain at least 32 characters");
    }
  } else if (kind === "drive-classification") {
    assertOnlyKeys(value, ["classification"], "request body");
    if (value.classification !== "driver" && value.classification !== "passenger") {
      throw new ApiFault(400, "INVALID_REQUEST", "classification must be driver or passenger");
    }
  } else if (kind === "circle-member-update") {
    assertOnlyKeys(value, ["role", "precision"], "request body");
    if (Object.keys(value).length !== 1) throw new ApiFault(400, "INVALID_REQUEST", "Specify exactly one role or precision field");
    if (value.role !== undefined && value.role !== "admin" && value.role !== "member") {
      throw new ApiFault(400, "INVALID_REQUEST", "role must be admin or member");
    }
    if (value.precision !== undefined && !(["precise", "approximate", "hidden"] as unknown[]).includes(value.precision)) {
      throw new ApiFault(400, "INVALID_REQUEST", "precision must be precise, approximate, or hidden");
    }
  } else if (kind === "notifications-acknowledge") {
    assertOnlyKeys(value, ["notificationIds"], "request body");
    validateUuidList(value.notificationIds, "notificationIds", 1, true);
    if (value.notificationIds.length > 100) throw new ApiFault(400, "INVALID_REQUEST", "notificationIds must contain at most 100 UUIDs");
  } else if (kind === "checkin-create") {
    assertOnlyKeys(value, ["clientEventId", "label", "latitude", "longitude", "accuracyMeters", "capturedAt"], "request body");
    assertUuid(value.clientEventId, "clientEventId");
    boundedString(value.label, "label", 1, 80);
    if (!numberIn(value.latitude, -90, 90) || !numberIn(value.longitude, -180, 180) || !numberIn(value.accuracyMeters, 0, 10_000)) {
      throw new ApiFault(400, "INVALID_REQUEST", "latitude, longitude, or accuracyMeters is out of range");
    }
    if (!isDateTime(value.capturedAt)) throw new ApiFault(400, "INVALID_REQUEST", "capturedAt must be an ISO-8601 timestamp");
  } else if (kind === "quick-note-create") {
    assertOnlyKeys(value, ["clientEventId", "text"], "request body");
    assertUuid(value.clientEventId, "clientEventId");
    boundedString(value.text, "text", 1, 280);
  } else if (kind === "event-create") {
    assertOnlyKeys(value, ["clientEventId", "title", "startsAt", "endsAt", "notes", "inviteeMemberIds"], "request body");
    assertUuid(value.clientEventId, "clientEventId");
    boundedString(value.title, "title", 1, 120);
    if (!isDateTime(value.startsAt)) throw new ApiFault(400, "INVALID_REQUEST", "startsAt must be an ISO-8601 timestamp");
    validateOptionalNullableDateTime(value.endsAt, "endsAt");
    validateOptionalNullableString(value.notes, "notes", 1000);
    validateUuidList(value.inviteeMemberIds, "inviteeMemberIds", 1, true);
  } else if (kind === "event-rsvp") {
    assertOnlyKeys(value, ["response"], "request body");
    if (!(["yes", "no", "maybe"] as unknown[]).includes(value.response)) {
      throw new ApiFault(400, "INVALID_REQUEST", "response must be yes, no, or maybe");
    }
  } else if (kind === "activity-sharing") {
    assertOnlyKeys(value, ["activitySharingEnabled"], "request body");
    if (typeof value.activitySharingEnabled !== "boolean") throw new ApiFault(400, "INVALID_REQUEST", "activitySharingEnabled must be a boolean");
  } else if (kind === "morning-checkin-create") {
    assertOnlyKeys(value, ["clientEventId", "greeting"], "request body");
    assertUuid(value.clientEventId, "clientEventId");
    validateOptionalNullableString(value.greeting, "greeting", 120);
  } else if (kind === "bubble-create") {
    assertOnlyKeys(value, ["clientEventId", "latitude", "longitude", "radiusMeters", "durationMinutes"], "request body");
    assertUuid(value.clientEventId, "clientEventId");
    if (!numberIn(value.latitude, -90, 90) || !numberIn(value.longitude, -180, 180) ||
        !Number.isInteger(value.radiusMeters) || !numberIn(value.radiusMeters, 50, 10_000) ||
        !Number.isInteger(value.durationMinutes) || !numberIn(value.durationMinutes, 1, 360)) {
      throw new ApiFault(400, "INVALID_REQUEST", "Bubble coordinates, radius, or duration are invalid");
    }
  } else if (kind === "emergency-contact-invite") {
    assertOnlyKeys(value, ["clientRequestId", "recipientUsername"], "request body");
    assertUuid(value.clientRequestId, "clientRequestId");
    boundedString(value.recipientUsername, "recipientUsername", 3, 32);
  } else if (kind === "pet-routine-create") {
    assertOnlyKeys(value, ["clientRequestId", "name", "kind", "notes", "dueAt", "recurrence"], "request body");
    assertUuid(value.clientRequestId, "clientRequestId");
    boundedString(value.name, "name", 1, 80);
    if (!(["feeding", "medication", "walk", "grooming", "other"] as unknown[]).includes(value.kind)) {
      throw new ApiFault(400, "INVALID_REQUEST", "kind is not a supported pet routine type");
    }
    validateOptionalNullableString(value.notes, "notes", 500);
    validateOptionalNullableDateTime(value.dueAt, "dueAt");
    validatePetRoutineRecurrence(value.recurrence);
  } else if (kind === "pet-routine-completion") {
    assertOnlyKeys(value, ["clientEventId"], "request body");
    assertUuid(value.clientEventId, "clientEventId");
  } else if (kind === "trip-stop") {
    assertOnlyKeys(value, ["clientRequestId", "endReason"], "request body");
    assertUuid(value.clientRequestId, "clientRequestId");
    if (!(["arrived", "cancelled", "other"] as unknown[]).includes(value.endReason)) {
      throw new ApiFault(400, "INVALID_REQUEST", "endReason must be arrived, cancelled, or other");
    }
  } else if (kind === "sos") {
    assertOnlyKeys(value, ["clientEventId", "cancelCountdownSeconds", "latitude", "longitude", "accuracyMeters"], "request body");
    assertUuid(value.clientEventId, "clientEventId");
    if (!Number.isInteger(value.cancelCountdownSeconds) || !numberIn(value.cancelCountdownSeconds, 0, 30)) {
      throw new ApiFault(400, "INVALID_REQUEST", "cancelCountdownSeconds must be from 0 through 30");
    }
    const locationParts = [value.latitude, value.longitude, value.accuracyMeters];
    const locationAbsent = locationParts.every((part) => part === undefined);
    const locationNull = locationParts.every((part) => part === null);
    const completeLocation = numberIn(value.latitude, -90, 90) &&
      numberIn(value.longitude, -180, 180) && numberIn(value.accuracyMeters, 0, 10_000);
    if (!locationAbsent && !locationNull && !completeLocation) {
      throw new ApiFault(400, "INVALID_REQUEST", "latitude, longitude, and accuracyMeters must be all omitted, all null, or a complete fix");
    }
  } else if (kind === "pet-photo") {
    assertOnlyKeys(value, ["contentType", "sizeBytes"], "request body");
    if (!["image/jpeg", "image/png", "image/webp"].includes(String(value.contentType))) {
      throw new ApiFault(400, "INVALID_REQUEST", "contentType must be image/jpeg, image/png, or image/webp");
    }
    if (!Number.isInteger(value.sizeBytes) || !numberIn(value.sizeBytes, 1, 5 * 1024 * 1024)) {
      throw new ApiFault(400, "INVALID_REQUEST", "sizeBytes must be between 1 and 5242880");
    }
  } else if (kind === "trip-start") {
    assertOnlyKeys(value, ["recipientMemberIds", "destinationLabel", "destinationLatitude", "destinationLongitude", "shareLockScreenProgress", "confirmedDrive"], "request body");
    if (!Array.isArray(value.recipientMemberIds) || value.recipientMemberIds.length === 0 || !value.recipientMemberIds.every(isUuid)) {
      throw new ApiFault(400, "INVALID_REQUEST", "recipientMemberIds must include valid member UUIDs");
    }
    if (value.destinationLatitude !== undefined && value.destinationLatitude !== null && !numberIn(value.destinationLatitude, -90, 90)) {
      throw new ApiFault(400, "INVALID_REQUEST", "destinationLatitude is invalid");
    }
    if (value.destinationLongitude !== undefined && value.destinationLongitude !== null && !numberIn(value.destinationLongitude, -180, 180)) {
      throw new ApiFault(400, "INVALID_REQUEST", "destinationLongitude is invalid");
    }
    if (value.destinationLabel !== undefined && value.destinationLabel !== null &&
        (typeof value.destinationLabel !== "string" || value.destinationLabel.length > 120)) {
      throw new ApiFault(400, "INVALID_REQUEST", "destinationLabel must be null or a string of at most 120 characters");
    }
    if (value.shareLockScreenProgress !== undefined && typeof value.shareLockScreenProgress !== "boolean") {
      throw new ApiFault(400, "INVALID_REQUEST", "shareLockScreenProgress must be a boolean");
    }
    if (value.confirmedDrive !== undefined && typeof value.confirmedDrive !== "boolean") {
      throw new ApiFault(400, "INVALID_REQUEST", "confirmedDrive must be a boolean");
    }
  } else if (kind === "place-update") {
    const allowed = ["name", "latitude", "longitude", "radiusMeters", "alertOnArrival", "alertOnDeparture"];
    assertOnlyKeys(value, allowed, "request body");
    if (Object.keys(value).length < 1) throw new ApiFault(400, "INVALID_REQUEST", "request body must include at least one field");
    if (value.name !== undefined) boundedString(value.name, "name", 1, 80);
    if (value.latitude !== undefined && !numberIn(value.latitude, -90, 90)) throw new ApiFault(400, "INVALID_REQUEST", "latitude is out of range");
    if (value.longitude !== undefined && !numberIn(value.longitude, -180, 180)) throw new ApiFault(400, "INVALID_REQUEST", "longitude is out of range");
    if (value.radiusMeters !== undefined && (!Number.isInteger(value.radiusMeters) || !numberIn(value.radiusMeters, 50, 5000))) {
      throw new ApiFault(400, "INVALID_REQUEST", "radiusMeters must be an integer from 50 through 5000");
    }
    for (const field of ["alertOnArrival", "alertOnDeparture"] as const) {
      if (value[field] !== undefined && typeof value[field] !== "boolean") throw new ApiFault(400, "INVALID_REQUEST", `${field} must be a boolean`);
    }
  } else if (kind === "place-rule") {
    assertOnlyKeys(value, ["kind", "memberIds", "expectedAt", "graceMinutes", "recurrence"], "request body");
    if (!(value.kind === "arrival" || value.kind === "departure" || value.kind === "no_show")) {
      throw new ApiFault(400, "INVALID_REQUEST", "kind must be arrival, departure, or no_show");
    }
    if (value.memberIds !== undefined && (!Array.isArray(value.memberIds) || !value.memberIds.every(isUuid))) {
      throw new ApiFault(400, "INVALID_REQUEST", "memberIds must contain Circle member UUIDs");
    }
    validateOptionalNullableDateTime(value.expectedAt, "expectedAt");
    if (value.graceMinutes !== undefined && (!Number.isInteger(value.graceMinutes) || !numberIn(value.graceMinutes, 0, 1440))) {
      throw new ApiFault(400, "INVALID_REQUEST", "graceMinutes must be an integer from 0 through 1440");
    }
    validatePlaceRuleRecurrence(value.recurrence);
  } else if (kind === "place") {
    assertOnlyKeys(value, ["name", "latitude", "longitude", "radiusMeters", "alertOnArrival", "alertOnDeparture"], "request body");
    if (typeof value.name !== "string" || value.name.trim().length < 1 || value.name.length > 80 ||
        !numberIn(value.latitude, -90, 90) || !numberIn(value.longitude, -180, 180) ||
        !Number.isInteger(value.radiusMeters) || !numberIn(value.radiusMeters, 50, 5000)) {
      throw new ApiFault(400, "INVALID_REQUEST", "place name, coordinates, or radius are invalid");
    }
    for (const field of ["alertOnArrival", "alertOnDeparture"] as const) {
      if (value[field] !== undefined && typeof value[field] !== "boolean") {
        throw new ApiFault(400, "INVALID_REQUEST", `${field} must be a boolean`);
      }
    }
  } else if (kind === "pet-create") {
    assertOnlyKeys(value, ["name", "species", "circleIds", "recoveryEnabled", "recoveryFields", "ownerMessage", "contactRoute"], "request body");
    if (typeof value.name !== "string" || value.name.length < 1 || value.name.length > 80 ||
        !Array.isArray(value.circleIds) || value.circleIds.length < 1 || !value.circleIds.every(isUuid)) {
      throw new ApiFault(400, "INVALID_REQUEST", "pet name and circleIds are required");
    }
    validateOptionalNullableString(value.species, "species", 40);
    if (value.recoveryEnabled !== undefined && typeof value.recoveryEnabled !== "boolean") throw new ApiFault(400, "INVALID_REQUEST", "recoveryEnabled must be a boolean");
    if (value.recoveryFields !== undefined) validateRecoveryFields(value.recoveryFields, false);
    if (value.ownerMessage !== undefined) boundedString(value.ownerMessage, "ownerMessage", 0, 500);
    validateOptionalNullableString(value.contactRoute, "contactRoute", 200);
  } else if (kind === "pet-update") {
    assertOnlyKeys(value, ["name", "species", "circleIds", "recoveryEnabled", "recoveryFields", "ownerMessage", "contactRoute", "photoObjectPath"], "request body");
    if (Object.keys(value).length < 1) throw new ApiFault(400, "INVALID_REQUEST", "request body must include at least one field");
    if (value.name !== undefined) boundedString(value.name, "name", 1, 80);
    validateOptionalNullableString(value.species, "species", 40);
    if (value.circleIds !== undefined) validateUuidList(value.circleIds, "circleIds", 1, true);
    if (value.recoveryEnabled !== undefined && typeof value.recoveryEnabled !== "boolean") throw new ApiFault(400, "INVALID_REQUEST", "recoveryEnabled must be a boolean");
    if (value.recoveryFields !== undefined) validateRecoveryFields(value.recoveryFields, true);
    if (value.ownerMessage !== undefined) boundedString(value.ownerMessage, "ownerMessage", 0, 500);
    validateOptionalNullableString(value.contactRoute, "contactRoute", 200);
    if (value.photoObjectPath !== undefined) {
      const petId = routeParams.petId;
      if (!petId || !isUuid(petId)) throw new ApiFault(400, "INVALID_REQUEST", "petId must be a UUID");
      validatePetPhotoObjectPath(value.photoObjectPath, petId);
    }
  } else if (kind === "guardian-consent") {
    assertOnlyKeys(value, ["childMemberId", "guardianUsername", "relationship"], "request body");
    assertUuid(value.childMemberId, "childMemberId");
    boundedString(value.guardianUsername, "guardianUsername", 3, 32);
    if (value.relationship !== undefined && value.relationship !== "parent" && value.relationship !== "guardian") {
      throw new ApiFault(400, "INVALID_REQUEST", "relationship must be parent or guardian");
    }
  } else if (kind === "privacy-deletion") {
    assertOnlyKeys(value, ["reason", "details", "confirmationPhrase"], "request body");
    if (!(value.reason === "no_longer_needed" || value.reason === "privacy_concern" || value.reason === "switching_service" || value.reason === "other")) {
      throw new ApiFault(400, "INVALID_REQUEST", "reason is not a supported deletion reason");
    }
    if (value.details !== undefined && (typeof value.details !== "string" || [...value.details].length > 500)) {
      throw new ApiFault(400, "INVALID_REQUEST", "details must be a string of at most 500 characters");
    }
    if (value.confirmationPhrase !== "DELETE MY ACCOUNT") {
      throw new ApiFault(400, "INVALID_REQUEST", "confirmationPhrase must match the explicit confirmation text");
    }
  } else if (kind === "pet-sighting") {
    assertOnlyKeys(value, ["message", "latitude", "longitude", "accuracyMeters", "contactRoute"], "request body");
    if (typeof value.message !== "string" || value.message.trim().length < 1 || value.message.length > 1000) {
      throw new ApiFault(400, "INVALID_REQUEST", "message must contain 1 to 1000 characters");
    }
    const coordinatesAbsent = value.latitude === undefined && value.longitude === undefined;
    const coordinatesNull = value.latitude === null && value.longitude === null;
    const coordinatesValid = numberIn(value.latitude, -90, 90) && numberIn(value.longitude, -180, 180);
    if (!coordinatesAbsent && !coordinatesNull && !coordinatesValid) {
      throw new ApiFault(400, "INVALID_REQUEST", "latitude and longitude must both be omitted, both null, or a complete coordinate pair");
    }
    if (value.accuracyMeters !== undefined && value.accuracyMeters !== null && !numberIn(value.accuracyMeters, 0, 10_000)) {
      throw new ApiFault(400, "INVALID_REQUEST", "accuracyMeters is out of range");
    }
    if (value.contactRoute !== undefined && value.contactRoute !== null &&
        (typeof value.contactRoute !== "string" || value.contactRoute.length > 200)) {
      throw new ApiFault(400, "INVALID_REQUEST", "contactRoute must be null or a string of at most 200 characters");
    }
  } else if (kind === "lost-pet") {
    assertOnlyKeys(value, ["petId", "recipientCircleIds", "message", "expiresAt"], "request body");
    assertUuid(value.petId, "petId");
    if (!Array.isArray(value.recipientCircleIds) || value.recipientCircleIds.length < 1 || !value.recipientCircleIds.every(isUuid)) {
      throw new ApiFault(400, "INVALID_REQUEST", "recipientCircleIds must contain Circle UUIDs");
    }
    if (!isDateTime(value.expiresAt) || Date.parse(value.expiresAt) <= Date.now() || Date.parse(value.expiresAt) > Date.now() + 72 * 60 * 60 * 1000) {
      throw new ApiFault(400, "INVALID_REQUEST", "expiresAt must be within 72 hours");
    }
    if (value.message !== undefined && value.message !== null &&
        (typeof value.message !== "string" || value.message.length > 500)) {
      throw new ApiFault(400, "INVALID_REQUEST", "message must be null or a string of at most 500 characters");
    }
  } else {
    const unhandledBodyKind: never = kind;
    throw new Error(`An API request body schema is missing validation: ${unhandledBodyKind}`);
  }
  return value;
}

export function readBearerSecret(request: Request): string | null {
  const header = request.headers.get("authorization");
  if (!header) return null;
  const match = /^Bearer ([A-Za-z0-9_-]{40,64})$/i.exec(header.trim());
  return match?.[1] && SHARE_SECRET.test(match[1]) ? match[1] : null;
}
