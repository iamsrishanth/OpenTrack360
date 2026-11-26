# OpenTrack360

India-first Android location sharing for private, invitation-only Circles, with a signed-out guest trip viewer and a private account administration site.

## Project areas

- `apps/android/` — native Android app (Kotlin and Jetpack Compose).
- `apps/web/` — Next.js website and versioned API.
- `contracts/` — shared OpenAPI contract and deterministic client fixtures.
- `supabase/` — versioned database migrations and database tests.
- `LIFE360_HANDOVER.md` — feature inventory and scope reference.

The API contract is at [`contracts/openapi.yaml`](contracts/openapi.yaml). Location values use latitude/longitude degrees, accuracy in metres, speed in metres per second, and RFC 3339 UTC timestamps. Guest links contain the read secret only after `#`; the browser reads it locally and sends it as a bearer token.

## Local web development

Web setup and environment variable names will be documented in `apps/web/README.md` when the web package is added. Never commit `.env` files, service-role keys, signing material, credentials, or real location samples.
