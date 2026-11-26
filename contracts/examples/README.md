# Shared contract fixtures

These JSON files are deterministic, synthetic examples for Android and web clients. They describe the wire format; token strings, IDs, and Mumbai-area coordinates are test data and must never be treated as credentials or real member records.

API base: `https://track.srishanth.me/api/v1`

- Username is normalized server-side; the internal Supabase Auth email is never returned.
- Every provisioned member starts with `sharingEpoch: 1`, returned by `GET /me`. Include it at the top level of each `POST /locations/batch` request. A stale epoch returns 409 `SHARING_EPOCH_MISMATCH` and accepts no samples; discard that old queued batch. Persist the new epoch returned by `POST /me/stop-sharing` before collecting or uploading again.
- Member bearer tokens are sent in `Authorization`; refresh tokens are sent in request bodies only to the refresh and logout routes.
- Guest links use `https://track.srishanth.me/s/{id}#{secret}`. The secret after `#` is read by the client and sent as `Authorization: Bearer <secret>` to `GET /public/shares/{id}`. It is never sent in a query or initial page request.
- Software-only pet recovery QR links use `https://track.srishanth.me/p/{petId}#{secret}`. The fragment secret is sent only in the `Authorization` header to the public pet recovery routes; the path ID reveals no profile data by itself.
- Speed is metres per second on the API. Convert to km/h for display (`m/s × 3.6`). A nullable speed is unknown; numeric zero is a measured stop.
- The public viewer polls about every 10 seconds. Each successful response permits at most 30 seconds of display. Clear location at `viewValidUntil`; after 90 seconds without a fresh sender fix, the API returns `signal_lost` and `latest: null`.
- Destination arrival needs two fresh, sufficiently accurate fixes at least 60 seconds apart and within 150 metres. Drive-end needs a confirmed drive and five continuous minutes of credible stationary evidence. GPS gaps, poor fixes, traffic stops, and a parked start do not end a share.
- For offline terminal events, submit `POST /shares/{shareId}/events` with the event and bounded evidence before uploading any later fix. After the server confirms a terminal event, later fixes cannot revive or update that share.
- Invalid or missing guest secrets return 404. A known ended share returns 410. Neither response includes location.
