# Deployment and acceptance checkpoint

**Snapshot date:** 27 November 2026

**Merged PR:** [#2](https://github.com/iamsrishanth/OpenTrack360/pull/2)

**Web implementation:** `510dc92572e5838aa525d68e243e9fa569824501`

**Production application release:** `485fddc716aa098076be600645b69b0cd4f044b7`

This records production deployment and hosted migration evidence. Signed-in end-to-end acceptance still needs disposable member credentials in an isolated test environment.

## Production deployment and route probes

The existing Vercel project `opentrack360` serves [track.srishanth.me](https://track.srishanth.me). The production deployment is READY and the custom domain resolves to the `main` deployment. The application routes in that deployment use the release from merge commit `485fddc716aa098076be600645b69b0cd4f044b7`; the current acceptance-record commit changes documentation only.

| Production request | Observed result | What it proves |
| --- | --- | --- |
| `GET /` | HTTP 200 | The public site is serving. |
| `GET /admin` | HTTP 200; `X-Robots-Tag: noindex, nofollow, noarchive`; `Referrer-Policy: no-referrer` | The admin login shell loads without granting access to account data. |
| `GET /s/00000000-0000-0000-0000-000000000000` | HTTP 200; private no-store; no-referrer; noindex | Guest viewer shell loads without a share credential or location. |
| `GET /p/00000000-0000-0000-0000-000000000000` | HTTP 200; private no-store; no-referrer; noindex | Pet recovery shell loads without revealing pet details. |
| `GET /i` | HTTP 200 | Circle invitation landing route is deployed. |
| `GET /api/v1/me` without bearer | HTTP 401; no-store | Member API rejects a signed-out caller. |
| `GET /api/v1/admin/accounts` without admin session | HTTP 401; no-store | Account administration API rejects a signed-out caller. |
| `GET /api/v1/shares` without member bearer | HTTP 401; private no-store | Sender-only share list rejects a signed-out caller. |
| `GET /api/v1/public/shares/{unknown-id}` without bearer | HTTP 404; private no-store; noindex | A public ID by itself returns no location. |
| Same unknown share request with an invalid bearer value | HTTP 404; private no-store; noindex | A bad secret returns no location. The secret was sent only in the header, not the URL. |
| `GET /.well-known/assetlinks.json` | HTTP 200; body `[]` | The association file is served, but Android release association is not configured. |

The post-deployment Vercel runtime error scan found no error clusters in the preceding hour. These route probes verify shell and signed-out behavior; they do not substitute for valid-member or valid-share flows.

## Supabase hosted database

The ignored local `.env.local` targets the existing Supabase Free project `OpenTrack360` (`oxfcxucpzrvbifefnwvp`). Supabase CLI 3.4.0 was authenticated and linked to that project. Secret values were not printed or committed.

The hosted database already contained the OpenTrack360 private schema, but it had no migration ledger. Before changing the ledger, read-only checks verified the existing structures and function markers for the first six repository migrations. Those six versions were reconciled as already applied with `supabase migration repair`; migrations `20261127000300` and `20261127000400` were then applied with `supabase db push`. `supabase migration list --remote` now reports all eight repository migrations applied. The last two migrations are committed in the merged revision.

Read-only checks after the push confirm the new trip session and sharing-epoch fields, SOS notification linkage, indexes, and sender-only share projection. Auth settings report `disable_signup=true`, `mailer_autoconfirm=false`, and `phone_autoconfirm=false`. All 25 tables in the private schema have RLS enabled, and the `anon` and `authenticated` roles have no direct table `SELECT` grants. The one-time administrator bootstrap is consumed and an active platform-administrator record exists; no account identity or credential was read.

## Build and test evidence

- `npm run test:api`: 22/22 passed.
- `npm run typecheck`: passed.
- `npm run build`: passed and generated the guest share, pet recovery, invitation, admin, and versioned API routes.
- Redocly lint for `contracts/openapi.yaml`: passed; all 118 contract JSON examples parsed.
- The post-merge [Supabase database workflow](https://github.com/iamsrishanth/OpenTrack360/actions/runs/45947182673) applied all eight migrations to a fresh local Supabase stack and passed all 100 pgTAP assertions.
- The production Vercel deployment is READY for the current `main` revision, with application code from release commit `485fddc716aa098076be600645b69b0cd4f044b7`; the Vercel and Vercel Preview Comments checks passed before merge.

## Remaining acceptance and provider prerequisites

- No signed-in account flow or valid guest share was exercised: there are no disposable member credentials in this session, and the `teams/web-platform` Vercel preview has no environment variables. The production environment has the five server variables `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `SUPABASE_SECRET_KEY`, `OPENTRACK_AUTH_EMAIL_DOMAIN`, and `NEXT_PUBLIC_SITE_URL`; their secret values were not read.
- The Supabase project has no preview branch. [Supabase deployment guidance](https://supabase.com/docs/guides/deployment) says preview branching requires the Pro plan, which would add cost; no paid branch or additional project was created. Provide an isolated zero-cost test project and two synthetic member accounts, or specify another safe test environment, to run provisioning, password rotation/disable, Circle isolation, location ingest, valid link revocation, and valid guest reads. No production member records were used as test data.
- App Links need the release package signing SHA-256 for `me.srishanth.opentrack360`. `assetlinks.json` is currently empty. Do not publish the debug signing certificate.
- FCM receipt testing needs Firebase server credentials and an opted-in test device.
- Weather and flight feeds remain unavailable until a free commercial source suitable for this product is confirmed. The map uses MapLibre with OpenFreeMap; attribution is required, there is no SLA, and tile requests disclose the viewed map area to the tile provider. See [feature scope](feature-scope.md).
