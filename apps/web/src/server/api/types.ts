export type MemberContext = {
  userId: string;
  username: string;
  sessionId: string;
  mustRotatePassword: boolean;
  sharingEpoch: number;
};

export type JsonRecord = Record<string, unknown>;

export type RpcEnvelope = {
  httpStatus: number;
  body: unknown;
};

export type ApiBodyKind =
  | "location-batch" | "share-create" | "share-event" | "sos" | "pet-photo" | "pet-sighting" | "trip-start" | "place" | "place-update" | "place-rule"
  | "pet-create" | "pet-update" | "lost-pet" | "circle-sharing" | "circle-update" | "circle-create" | "drive-classification" | "privacy-deletion"
  | "guardian-consent" | "checkin-create" | "quick-note-create" | "event-create" | "event-rsvp" | "activity-sharing" | "morning-checkin-create"
  | "bubble-create" | "emergency-contact-invite" | "pet-routine-create" | "pet-routine-completion" | "trip-stop" | "circle-invite-create"
  | "circle-member-update" | "notifications-acknowledge" | "circle-invite-accept";

export type ApiRoutePlan = {
  operation: string;
  defaultStatus: number;
  auth: "member" | "share-secret" | "pet-secret";
  params: Record<string, string>;
  body?: ApiBodyKind;
};

export type ApiServices = {
  requireMember: (request: Request, options?: { allowPasswordRotation?: boolean }) => Promise<MemberContext>;
  dispatchMember: (member: MemberContext, operation: string, args: JsonRecord) => Promise<RpcEnvelope>;
  uploadLocationBatch: (member: MemberContext, clientSharingEpoch: number, samples: JsonRecord[]) => Promise<RpcEnvelope>;
  createShare: (member: MemberContext, input: JsonRecord, secret: string, secretHash: string) => Promise<RpcEnvelope>;
  readShare: (shareId: string, secretHash: string) => Promise<RpcEnvelope>;
  submitShareEvent: (member: MemberContext, shareId: string, event: JsonRecord) => Promise<RpcEnvelope>;
  readPetRecovery: (petId: string, secretHash: string) => Promise<RpcEnvelope>;
  submitPetSighting: (petId: string, secretHash: string, sighting: JsonRecord) => Promise<RpcEnvelope>;
  createPetRecoveryLink: (member: MemberContext, petId: string, secret: string, secretHash: string) => Promise<RpcEnvelope>;
  createPetPhotoUpload: (member: MemberContext, petId: string, input: JsonRecord) => Promise<RpcEnvelope>;
};

export class ApiFault extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiFault";
    this.status = status;
    this.code = code;
  }
}
