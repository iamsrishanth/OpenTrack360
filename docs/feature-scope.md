# OpenTrack360 feature scope and pilot budget

OpenTrack360 is a free-to-members, invitation-only Android product. The web platform provides guest trip views, account administration, and the versioned API. This document records the mapping-service boundary, unavailable provider dependencies, and a bounded pilot workload for the current free hosting plans.

## Feature ownership

| Capability | Contract ownership | Web/API surface | Delivery boundary |
| --- | --- | --- | --- |
| Provisioned accounts, login, password rotation, reset, disable, and audit | Auth/database | `/auth/*`, `/me/*`, `/admin/*` | Supabase Auth stores passwords; website administrator manages accounts only and cannot view member maps or history. |
| Circles, roles, invites, temporary Circles, consent, and precision rules | API/database | `/circles/*`, `/circle-invites/*` | Every member action is scoped to an existing provisioned account and the caller's Circle role. |
| Live Circle map, Bubbles, and history | API/database | `/circles/{circleId}/map`, `/bubbles`, `/history` | Private, role-checked, precision-aware responses. A hidden precise location cannot be fetched through another endpoint. |
| Live location ingest | API/database | `/locations/batch` | Authenticated, bounded, replay-safe samples; device capture time, metres for accuracy, metres/second for speed. |
| Guest trip links | API + guest viewer | `/shares*`, `/public/shares/{shareId}`, `/s/{shareId}` | Secret lives only in the URL fragment locally and the bearer header on API reads. Guests see only one current fix; links can end by deadline, confirmed destination/drive-end, or revocation. |
| Places, geofence rules, and No Show | API/database | `/circles/{circleId}/places*` | Uses device fixes and bounded server-side rules. No paid routing or geocoding service is enabled. |
| Check-ins, Quick Notes, RSVPs, activity, and morning check-ins | API/database | `/circles/{circleId}/checkins`, `/quick-notes`, `/events`, `/activity*`, `/morning-check-ins` | Visible only to the scoped Circle. |
| Trips, driving reports, and driver/passenger corrections | API/database | `/circles/{circleId}/trips`, `/driving/*` | Mobile evidence is validated and corrected by the member; these are not insurance or reimbursement claims. |
| Circle SOS and emergency contacts | API/database | `/circles/{circleId}/sos*`, `/emergency-contact-invites/*` | In-app notification records are available. No phone location permission is requested from the guest. SMS, emergency dispatch, and roadside assistance are unavailable/out of scope. Push delivery stays unavailable until Firebase project credentials and an opted-in test device are configured and verified; do not claim provider receipt without evidence. |
| Lost-phone lookup | API/database | `/circles/{circleId}/map` | A Circle member can find a missing phone only through the same scoped current fix that is visible on the Circle map, and only while that phone is permitted to report. No external tracker, carrier, or hardware network. |
| Pet profiles, care routines, recovery QR, and sightings | API/database + pet viewer | `/pets*`, `/public/pets/{petId}*`, `/p/{petId}` | Software-only profile and QR; path ID alone returns no profile. No Pet Finder Network or third-party GPS tracker. |
| Guardian consent | API/database | `/guardian-consents*` | Explicit consent records only; no identity verification provider is configured. |
| Data export, account deletion, and privacy controls | API/database | `/privacy/*` | Requests are authenticated and scoped to the member; Circle-wide cleanup is transactionally enforced. |

## External dependencies and unavailable features

| Dependency or service | Status | Product behavior |
| --- | --- | --- |
| Base map | Enabled for pilot: MapLibre Native on Android and MapLibre GL JS on web, with OpenFreeMap's public vector style and required attribution. The public service needs no API key and has no published request cap, but offers no SLA. A tile request reveals the map viewport to OpenFreeMap and its CDN. | Map display only. No directions, geocoding, or address search is included. The user can still enter a destination coordinate through the app where supported. |
| Weather feed | Unavailable | The reviewed Open-Meteo Free tier is personal/non-commercial. No free source with suitable product rights has been confirmed. Do not show third-party weather data until the license and operational limits are verified. |
| Flight status | Unavailable | The reviewed Aviationstack Free tier is personal/non-commercial. No free source with suitable product rights has been confirmed. Do not show flight status until a suitable provider is verified. |
| FCM push | Unavailable pending setup | Firebase Cloud Messaging has no usage charge, but this project has no configured Firebase project or server credentials, and no test-device receipt has been verified. Keep Circle notices in-app and do not report push as submitted or received until credentials and device opt-in are configured. |
| Trackers, vehicle/hardware networks, and professional response | Out of scope | No external device network, paid tracker, police/ambulance dispatch, roadside assistance, insurance, reimbursement, or protection service is offered. |
| Payments, subscriptions, identity/credit services, and loyalty/advertising products | Out of scope | Every included feature remains free to each member. No billing, bank, credit, insurance, identity-verification, or partner commerce provider is configured. |
| Automatic crash detection | Feature-gated | Keep off until sensor thresholds, false-positive handling, Android background behavior, and a safe response flow are validated on supported devices. Phone-initiated Circle SOS remains available. |

## Free-tier pilot workload

The working pilot ceiling is **10 concurrent sharers**, with **3 viewers per shared trip**. Each share is viewed for up to one hour per day, and each sender is sampled every 15 seconds for one hour per day.

| Workload | Calculation | Estimated monthly usage |
| --- | --- | ---: |
| Guest viewer API polls | 10 sharers × 3 viewers × (3,600 seconds ÷ 10-second poll) × 30 days | 324,000 requests |
| Location sample submissions | 10 sharers × (3,600 seconds ÷ 15-second sample) × 30 days | 72,000 samples; at most 72,000 requests if sent one per request |
| Guest response data | 324,000 polls × about 1 KB of JSON | About 324 MB before transport overhead |
| Initial map loads | 30 viewer sessions/day × about 15 vector tile requests × 30 days | About 13,500 tile requests, excluding pan/zoom |

The estimated guest polls plus one-at-a-time location uploads are about **396,000 Vercel function invocations/month**, leaving roughly 604,000 of the current Hobby allowance for account, Circle, admin, and retry traffic. The calculation is a pilot bound, not a capacity guarantee; usage must be monitored and adjusted if actual view duration, map movement, retries, or member count grows.

The named Supabase project is on the Free plan. The current published allowances include 500 MB database size per project, 50,000 monthly active users, 5 GB egress, 5 GB cached egress, 1 GB storage, 500,000 Edge Function invocations, 2 million Realtime messages, and 200 peak Realtime connections. This design uses Vercel Functions rather than Supabase Edge Functions and does not use Realtime for guest polling. Free projects may pause after a week without activity and do not include the paid-plan backup support described in Supabase's plan comparison. See [Supabase billing and quotas](https://supabase.com/docs/guides/platform/billing-on-supabase).

The existing Vercel team is on Hobby. Current published allowances include 1 million function invocations, 4 active CPU hours, and 360 GB-hours of provisioned memory per month. Vercel limits Hobby to personal, non-commercial use. This deployment can support a non-commercial pilot within the listed caps. Any commercial operation requires an eligible hosting plan or a different host. See [Vercel Hobby plan](https://vercel.com/docs/plans/hobby) and [Vercel pricing](https://vercel.com/pricing).

The OpenFreeMap public service is best-effort and has no SLA. Its [terms](https://openfreemap.org/terms) and [project documentation](https://github.com/openfreemap/openfreemap) must be reviewed before a traffic increase. Firebase Cloud Messaging is listed as no-cost by [Firebase pricing](https://firebase.google.com/pricing); enabling push still requires a Firebase project, restricted server credentials, Android opt-in, and a verified test-device delivery.

## Location and guest-viewer retention policy

- The guest viewer caches the most recent successful response in memory only, for no longer than `viewValidUntil` (up to 30 seconds). It clears the marker immediately on a terminal response or lease expiry.
- The API's current-fix row is distinct from retained location history. A share cannot display any fix captured before `startedAt`, and a terminal event cannot be reversed by a later offline replay.
- Retain Circle location history for 30 days in the pilot, then purge it through bounded, indexed cleanup. Retain only the current fix while an active member account needs it. Remove current and historical location state when the account is deleted, subject to the service's documented deletion semantics.
- Do not retain guest request credentials, fragment secrets, precise coordinates, or request bodies in analytics or error logs. Keep map-tile requests separate from member API analytics.

The 30-day history window and pilot ceiling are explicit defaults for this implementation. The operator should adjust the schedule if operational measurement or legal obligations require a shorter retention period; do not silently extend it.
