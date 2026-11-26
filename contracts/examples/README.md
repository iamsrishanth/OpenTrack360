# Shared contract fixtures

These JSON files are deterministic, synthetic examples for Android and web clients. They describe the wire format; token strings, IDs, and Mumbai-area coordinates are test data and must never be treated as credentials or real member records.

API base: `https://track.srishanth.me/api/v1`

- Username is normalized server-side; the internal Supabase Auth email is never returned.
- Every provisioned member starts with `sharingEpoch: 1`, returned by `GET /me`. Include it at the top level of each `POST /locations/batch` request. A stale epoch returns 409 `SHARING_EPOCH_MISMATCH` and accepts no samples; discard that old queued batch. Persist the new epoch returned by `POST /me/stop-sharing` before collecting or uploading again.
- `PUT /circles/{circleId}/sharing` accepts `{ "sharingEnabled": boolean }` and changes only the caller's consent for that Circle. It does not advance the account-wide epoch or restore guest links revoked by `POST /me/stop-sharing`.
- `PUT /circles/{circleId}/activity-sharing` accepts `{ "activitySharingEnabled": boolean }` and changes only the caller's activity consent in that Circle.
- Coordination wire shapes and fixtures: check-ins use `clientEventId`, `label`, device `capturedAt`, coordinates and `accuracyMeters`; Quick Notes use `clientEventId` and `text`; event creation uses `clientEventId`, `title`, `startsAt`, `inviteeMemberIds` plus nullable `endsAt`/`notes`; RSVP uses `{ "response": "yes" | "no" | "maybe" }`. See `checkins-*`, `quick-notes-*`, `events-*`, and `activity-sharing.*`.
- Morning check-ins use `clientEventId` and an optional `greeting`. Bubbles use `{ "clientEventId", "latitude", "longitude", "radiusMeters", "durationMinutes" }`, with duration up to 360 minutes; returned center coordinates are approximate. See `morning-check-in*`, `morning-check-ins-list.response.json`, and `bubbles-*`.
- Emergency contacts are existing provisioned usernames: `POST /circles/{circleId}/emergency-contacts` accepts `{ "clientRequestId": "<uuid>", "recipientUsername": "saanvi" }`; the recipient signs in and accepts by UUID at `POST /emergency-contact-invites/{inviteId}/accept`. The ID is not a bearer secret. The accepted list exposes only member ID, username and acceptance time. SOS creation and `GET /circles/{circleId}/sos/{sosId}` include per-recipient delivery/acknowledgement states. See `emergency-contact-*`, `emergency-contacts-list.response.json`, and `sos-*.response.json`.
- Trip stop is `POST /circles/{circleId}/trips/{tripId}/stop` with `{ "clientRequestId": "<uuid>", "endReason": "arrived" | "cancelled" | "other" }`. The server supplies `endedAt`; replaying the same command does not reopen the trip. See `trip-stop.*`.
- Pet-care routines use `clientRequestId` and a typed name/kind/notes/due time/recurrence shape; completion is idempotent with `{ "clientEventId": "<uuid>" }`. See `pet-routine-*`.
- `POST /pets/{petId}/recovery-link` returns `{ "id", "url", "createdAt", "expiresAt" }` once. The URL secret appears after `#`; only its digest is stored. `DELETE /pets/{petId}/recovery-link` revokes active recovery tokens. See `pet-recovery-link*`.
- Lost-pet reports are owner-scoped to the explicit recipient Circle IDs and expire within 72 hours. `DELETE /lost-pets/{reportId}` revokes the report; replay returns the terminal result. See `lost-pet-report*`.
- Member bearer tokens are sent in `Authorization`; refresh tokens are sent in request bodies only to the refresh and logout routes.
- Guest links use `https://track.srishanth.me/s/{id}#{secret}`. The secret after `#` is read by the client and sent as `Authorization: Bearer <secret>` to `GET /public/shares/{id}`. It is never sent in a query or initial page request.
- Software-only pet recovery QR links use `https://track.srishanth.me/p/{petId}#{secret}`. The fragment secret is sent only in the `Authorization` header to the public pet recovery routes; the path ID reveals no profile data by itself.
- Speed is metres per second on the API. Convert to km/h for display (`m/s × 3.6`). A nullable speed is unknown; numeric zero is a measured stop.
- The public viewer polls about every 10 seconds. Each successful response permits at most 30 seconds of display. Clear location at `viewValidUntil`; after 90 seconds without a fresh sender fix, the API returns `signal_lost` and `latest: null`.
- Destination arrival needs two fresh, sufficiently accurate fixes at least 60 seconds apart and within 150 metres. Drive-end needs a confirmed drive and five continuous minutes of credible stationary evidence. GPS gaps, poor fixes, traffic stops, and a parked start do not end a share.
- For offline terminal events, submit `POST /shares/{shareId}/events` with the event and bounded evidence before uploading any later fix. After the server confirms a terminal event, later fixes cannot revive or update that share.
- Invalid or missing guest secrets return 404. A known ended share returns 410. Neither response includes location.
