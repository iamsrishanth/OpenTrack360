import type { ApiRoutePlan } from "./types";
import { isUuid } from "./validation";

type RouteMatch = { plan: ApiRoutePlan | null; allowedMethods?: string[] };

const route = (
  operation: string,
  method: string,
  auth: ApiRoutePlan["auth"] = "member",
  options: { status?: number; body?: ApiRoutePlan["body"]; params?: Record<string, string> } = {},
): RouteMatch => ({
  plan: {
    operation,
    defaultStatus: options.status ?? (method === "GET" ? 200 : method === "POST" ? 201 : 200),
    auth,
    params: options.params ?? {},
    body: options.body,
  },
  allowedMethods: [method],
});

const missingMethod = (...methods: string[]): RouteMatch => ({ plan: null, allowedMethods: methods });
const notFound = (): RouteMatch => ({ plan: null });

export function resolveApiRoute(segments: string[], method: string): RouteMatch {
  const m = method.toUpperCase();
  const s = segments;
  const [first, second, third, fourth, fifth, sixth] = s;
  const is = (length: number) => s.length === length;

  // These areas have their own route modules and security policies. The catch-all
  // must never provide a fallback for them.
  if (first === "auth" || first === "me" || first === "admin") return notFound();

  if (first === "locations" && second === "batch" && is(2)) {
    return m === "POST" ? route("uploadLocationBatch", m, "member", { body: "location-batch", status: 200 }) : missingMethod("POST");
  }
  if (first === "shares" && is(1)) {
    if (m === "GET") return route("listShares", m);
    if (m === "POST") return route("createShare", m, "member", { body: "share-create" });
    return missingMethod("GET", "POST");
  }
  if (first === "shares" && second && is(2)) {
    return m === "DELETE" ? route("revokeShare", m, "member", { params: { shareId: second } }) : missingMethod("DELETE");
  }
  if (first === "shares" && second && third === "events" && is(3)) {
    return m === "POST" ? route("submitShareEvent", m, "member", { body: "share-event", params: { shareId: second }, status: 200 }) : missingMethod("POST");
  }
  if (first === "public" && second === "shares" && third && is(3)) {
    return m === "GET" ? route("readPublicShare", m, "share-secret", { params: { shareId: third } }) : missingMethod("GET");
  }

  if (first === "circles") {
    if (is(1)) {
      if (m === "GET") return route("listCircles", m);
      if (m === "POST") return route("createCircle", m, "member", { body: "circle-create" });
      return missingMethod("GET", "POST");
    }
    const circleId = second;
    if (!circleId) return notFound();
    if (is(2)) {
      if (m === "GET") return route("getCircle", m, "member", { params: { circleId } });
      if (m === "PATCH") return route("updateCircle", m, "member", { body: "circle-update", params: { circleId } });
      if (m === "DELETE") return route("leaveCircle", m, "member", { params: { circleId } });
      return missingMethod("GET", "PATCH", "DELETE");
    }
    if (third === "invites" && is(3)) {
      if (m === "GET") return route("listCircleInvites", m, "member", { params: { circleId } });
      if (m === "POST") return route("createCircleInvite", m, "member", { body: "circle-invite-create", params: { circleId } });
      return missingMethod("GET", "POST");
    }
    if (third === "members" && is(3)) {
      return m === "GET" ? route("listCircleMembers", m, "member", { params: { circleId } }) : missingMethod("GET");
    }
    if (third === "members" && fourth && is(4)) {
      if (m === "PATCH") return route("updateCircleMember", m, "member", { body: "circle-member-update", params: { circleId, memberId: fourth } });
      if (m === "DELETE") return route("removeCircleMember", m, "member", { params: { circleId, memberId: fourth } });
      return missingMethod("PATCH", "DELETE");
    }
    if (third === "map" && is(3)) return m === "GET" ? route("getCircleMap", m, "member", { params: { circleId } }) : missingMethod("GET");
    if (third === "history" && is(3)) return m === "GET" ? route("getCircleHistory", m, "member", { params: { circleId } }) : missingMethod("GET");
    if (third === "sharing" && is(3)) return m === "PUT" ? route("setCircleSharing", m, "member", { body: "circle-sharing", params: { circleId } }) : missingMethod("PUT");
    if (third === "bubbles" && is(3)) {
      if (m === "GET") return route("listBubbles", m, "member", { params: { circleId } });
      if (m === "POST") return route("createBubble", m, "member", { body: "bubble-create", params: { circleId } });
      return missingMethod("GET", "POST");
    }
    if (third === "bubbles" && fourth && is(4)) return m === "DELETE" ? route("endBubble", m, "member", { params: { circleId, bubbleId: fourth } }) : missingMethod("DELETE");
    if (third === "places" && is(3)) {
      if (m === "GET") return route("listPlaces", m, "member", { params: { circleId } });
      if (m === "POST") return route("createPlace", m, "member", { body: "place", params: { circleId } });
      return missingMethod("GET", "POST");
    }
    if (third === "places" && fourth && is(4)) {
      if (m === "PATCH") return route("updatePlace", m, "member", { body: "place-update", params: { circleId, placeId: fourth } });
      if (m === "DELETE") return route("deletePlace", m, "member", { params: { circleId, placeId: fourth } });
      return missingMethod("PATCH", "DELETE");
    }
    if (third === "places" && fourth && fifth === "rules" && is(5)) {
      if (m === "GET") return route("listPlaceRules", m, "member", { params: { circleId, placeId: fourth } });
      if (m === "POST") return route("createPlaceRule", m, "member", { body: "place-rule", params: { circleId, placeId: fourth } });
      return missingMethod("GET", "POST");
    }
    if (third === "places" && fourth && fifth === "rules" && sixth && is(6)) {
      if (m === "PATCH") return route("updatePlaceRule", m, "member", { body: "place-rule", params: { circleId, placeId: fourth, ruleId: sixth } });
      if (m === "DELETE") return route("deletePlaceRule", m, "member", { params: { circleId, placeId: fourth, ruleId: sixth } });
      return missingMethod("PATCH", "DELETE");
    }
    const circleCollection: Record<string, { get?: string; post?: string; put?: string }> = {
      checkins: { get: "listCheckins", post: "createCheckin" },
      "quick-notes": { get: "listQuickNotes", post: "createQuickNote" },
      events: { get: "listEvents", post: "createEvent" },
      "morning-check-ins": { get: "listMorningCheckIns", post: "createMorningCheckIn" },
      trips: { get: "listTrips", post: "startTrip" },
      "driving/reports": { get: "getDrivingReports" },
      "emergency-contacts": { get: "listEmergencyContacts", post: "inviteEmergencyContact" },
    };
    if (s.length === 3 && circleCollection[third]) {
      const definition = circleCollection[third];
      const operation = definition[m.toLowerCase() as "get" | "post" | "put"];
      if (!operation) return missingMethod(...Object.keys(definition).map((key) => key.toUpperCase()));
      const body = operation === "startTrip" ? "trip-start"
        : operation === "createCheckin" ? "checkin-create"
          : operation === "createQuickNote" ? "quick-note-create"
            : operation === "createEvent" ? "event-create"
              : operation === "createMorningCheckIn" ? "morning-checkin-create"
                : operation === "inviteEmergencyContact" ? "emergency-contact-invite"
                  : undefined;
      return route(operation, m, "member", { params: { circleId }, body, status: operation === "startTrip" ? 201 : undefined });
    }
    if (third === "events" && fourth && fifth === "rsvp" && is(5)) return m === "PUT" ? route("updateEventRsvp", m, "member", { body: "event-rsvp", params: { circleId, eventId: fourth } }) : missingMethod("PUT");
    if (third === "activity-sharing" && is(3)) {
      if (m === "GET") return route("getActivitySharing", m, "member", { params: { circleId } });
      if (m === "PUT") return route("setActivitySharing", m, "member", { body: "activity-sharing", params: { circleId } });
      return missingMethod("GET", "PUT");
    }
    if (third === "activity" && is(3)) return m === "GET" ? route("getCircleActivity", m, "member", { params: { circleId } }) : missingMethod("GET");
    if (third === "driving" && fourth === "reports" && is(4)) return m === "GET" ? route("getDrivingReports", m, "member", { params: { circleId } }) : missingMethod("GET");
    if (third === "trips" && fourth && fifth === "stop" && is(5)) return m === "POST" ? route("stopTrip", m, "member", { body: "trip-stop", params: { circleId, tripId: fourth }, status: 200 }) : missingMethod("POST");
    if (third === "driving" && fourth === "trips" && fifth && sixth === undefined && is(5)) return m === "PATCH" ? route("correctDriveClassification", m, "member", { body: "drive-classification", params: { circleId, tripId: fifth } }) : missingMethod("PATCH");
    if (third === "sos" && is(3)) return m === "POST" ? route("createSosAlert", m, "member", { body: "sos", status: 202, params: { circleId } }) : missingMethod("POST");
    if (third === "sos" && fourth && is(4)) return m === "GET" ? route("getSosAlert", m, "member", { params: { circleId, sosId: fourth } }) : missingMethod("GET");
    if (third === "sos" && fourth && fifth && is(5)) {
      const operation = fifth === "cancel" ? "cancelSosAlert" : fifth === "acknowledge" ? "acknowledgeSosAlert" : null;
      if (!operation) return notFound();
      return m === "POST" ? route(operation, m, "member", { params: { circleId, sosId: fourth } }) : missingMethod("POST");
    }
  }

  if (first === "circle-invites" && second === "accept" && is(2)) return m === "POST" ? route("acceptCircleInvite", m, "member", { body: "circle-invite-accept", status: 200 }) : missingMethod("POST");
  if (first === "notifications" && is(1)) {
    if (m === "GET") return route("listNotifications", m);
    if (m === "PATCH") return route("acknowledgeNotifications", m, "member", { body: "notifications-acknowledge" });
    return missingMethod("GET", "PATCH");
  }
  if (first === "emergency-contact-invites" && second && third === "accept" && is(3)) return m === "POST" ? route("acceptEmergencyContactInvite", m, "member", { params: { inviteId: second }, status: 200 }) : missingMethod("POST");

  if (first === "pets" && is(1)) {
    if (m === "GET") return route("listPets", m);
    if (m === "POST") return route("createPet", m, "member", { body: "pet-create" });
    return missingMethod("GET", "POST");
  }
  if (first === "pets" && second && is(2)) {
    if (m === "GET") return route("getPet", m, "member", { params: { petId: second } });
    if (m === "PATCH") return route("updatePet", m, "member", { body: "pet-update", params: { petId: second } });
    if (m === "DELETE") return route("deletePet", m, "member", { params: { petId: second } });
    return missingMethod("GET", "PATCH", "DELETE");
  }
  if (first === "pets" && second && third === "routines" && is(3)) {
    if (m === "GET") return route("listPetRoutines", m, "member", { params: { petId: second } });
    if (m === "POST") return route("createPetRoutine", m, "member", { body: "pet-routine-create", params: { petId: second } });
    return missingMethod("GET", "POST");
  }
  if (first === "pets" && second && third === "activity" && is(3)) return m === "GET" ? route("listPetActivity", m, "member", { params: { petId: second } }) : missingMethod("GET");
  if (first === "pets" && second && third === "photo" && is(3)) return m === "POST" ? route("createPetPhotoUpload", m, "member", { body: "pet-photo", params: { petId: second } }) : missingMethod("POST");
  if (first === "pets" && second && third === "recovery-link" && is(3)) {
    if (m === "POST") return route("createPetRecoveryLink", m, "member", { params: { petId: second } });
    if (m === "DELETE") return route("revokePetRecoveryLink", m, "member", { params: { petId: second } });
    return missingMethod("POST", "DELETE");
  }
  if (first === "pets" && second && third === "routines" && fourth && fifth === "complete" && is(5)) return m === "POST" ? route("completePetRoutine", m, "member", { body: "pet-routine-completion", params: { petId: second, routineId: fourth } }) : missingMethod("POST");
  if (first === "lost-pets" && is(1)) return m === "POST" ? route("reportLostPet", m, "member", { body: "lost-pet" }) : missingMethod("POST");
  if (first === "lost-pets" && second && is(2)) return m === "DELETE" ? route("revokeLostPetReport", m, "member", { params: { reportId: second } }) : missingMethod("DELETE");
  if (first === "public" && second === "pets" && third && fourth === "sightings" && is(4)) return m === "POST" ? route("submitPetSighting", m, "pet-secret", { body: "pet-sighting", params: { petId: third }, status: 202 }) : missingMethod("POST");
  if (first === "public" && second === "pets" && third && is(3)) return m === "GET" ? route("readPetRecoveryCard", m, "pet-secret", { params: { petId: third } }) : missingMethod("GET");
  if (first === "guardian-consents" && is(1)) return m === "POST" ? route("requestGuardianConsent", m, "member", { body: "guardian-consent" }) : missingMethod("POST");
  if (first === "guardian-consents" && second && third === "approve" && is(3)) return m === "POST" ? route("approveGuardianConsent", m, "member", { params: { consentId: second }, status: 200 }) : missingMethod("POST");
  if (first === "privacy" && second === "export" && is(2)) return m === "POST" ? route("requestDataExport", m, "member", { status: 202 }) : missingMethod("POST");
  if (first === "privacy" && second === "deletion" && is(2)) return m === "POST" ? route("requestAccountDeletion", m, "member", { body: "privacy-deletion", status: 202 }) : missingMethod("POST");

  return notFound();
}

export function validatePlanParams(plan: ApiRoutePlan): void {
  for (const [name, value] of Object.entries(plan.params)) {
    if (name.endsWith("Id") && !isUuid(value)) throw new Error(`invalid ${name}`);
    if (name === "inviteToken" && (value.length < 20 || value.length > 512 || !/^[A-Za-z0-9_-]+$/.test(value))) {
      throw new Error("invalid invite token");
    }
  }
}
