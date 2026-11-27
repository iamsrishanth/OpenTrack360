# Deployment and acceptance checkpoint

**Snapshot date:** 27 November 2026

**Merged PRs:** [#2](https://github.com/iamsrishanth/OpenTrack360/pull/2), [#3](https://github.com/iamsrishanth/OpenTrack360/pull/3), [#6](https://github.com/iamsrishanth/OpenTrack360/pull/6)

**Web implementation:** `510dc92572e5838aa525d68e243e9fa569824501`

**Current production deployment commit:** `97b1c9b6271126d3bf8e30b4bdaf3d58d0f393e8`

**Current production deployment:** `dpl_FJCdxsRkdE6DUNunqew2uvWL5mQT` (READY; aliases include `track.srishanth.me`)

This records production deployment, hosted migration, and drive-end validator evidence. Signed-in end-to-end acceptance still needs an authorized platform administrator and disposable synthetic member credentials.

## Production deployment and route probes

The existing Vercel project `opentrack360` serves [track.srishanth.me](https://track.srishanth.me). The production deployment is READY and aliases the custom domain to the `main` deployment. Vercel reports that it was built from `main` at merge commit `aa28f422c80ed55f8087d5baf9f5ee239a6dff1a`.

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
| `GET /api/v1/public/shares/{unknown-id}` without bearer | HTTP 404 | A public ID by itself returns no location. |
| `GET /.well-known/assetlinks.json` | HTTP 200; JSON; no redirect; one statement for package `me.srishanth.opentrack360` and release SHA-256 `22:32:AD:A1:CA:CB:94:55:50:16:AB:83:68:D8:DF:48:DF:10:36:33:A9:E3:A1:63:88:2F:64:3E:D0:44:32:24` | Production Android App Links association is published. |

These route probes verify shell and signed-out behavior; they do not substitute for valid-member or valid-share flows.

## Supabase hosted database

The ignored local `.env.local` targets the existing Supabase Free project `OpenTrack360` (`oxfcxucpzrvbifefnwvp`). Supabase CLI 3.4.0 is authenticated and linked to that project. No credential values are committed to Git or included in fixtures.

The hosted database already contained the OpenTrack360 private schema, but it had no migration ledger. Before changing the ledger, read-only checks verified the existing structures and function markers for the first six repository migrations. Those six versions were reconciled as already applied with `supabase migration repair`; migrations `20261127000300` and `20261127000400` were then applied with `supabase db push`. The last verified hosted ledger had all eight repository migrations applied. The owner-only synthetic-admin migration has passed the isolated CI database suite but is not yet applied to the hosted project.

Read-only checks after the push confirm the new trip session and sharing-epoch fields, SOS notification linkage, indexes, and sender-only share projection. Auth settings report `disable_signup=true`, `mailer_autoconfirm=false`, and `phone_autoconfirm=false`. All 25 tables in the private schema have RLS enabled, and the `anon` and `authenticated` roles have no direct table `SELECT` grants. The one-time administrator bootstrap is consumed and an active platform-administrator record exists; no account identity or credential was read. `npx --yes supabase@3.4.0 migration list --remote` confirms all eight repository migrations are applied.

## Drive-end validator examples

The 9-minute movement-to-stop gap remains a negative case: the actual hosted `public.opentrack_share_event` validator leaves the share pending, and the guest read remains HTTP 200. The positive fixture supplies continuous qualifying movement evidence within the permitted transition window followed by five minutes of stationary evidence: the validator confirms the event, and the guest read returns HTTP 410 with no location or speed. Both cases were executed inside a transaction and rolled back. A read-only postcheck found no persisted fixture accounts, shares, or location samples.

## Build and test evidence

- `npm run test:api`: 23/23 passed.
- `npm run typecheck`: passed.
- `npm run build`: passed and generated the guest share, pet recovery, invitation, admin, and versioned API routes.
- Redocly lint for `contracts/openapi.yaml`: passed; all 120 contract JSON examples parsed.
- The [Supabase database workflow](https://github.com/iamsrishanth/OpenTrack360/actions/runs/45952892371) passed on PR #3. The same workflow passed on [PR #7](https://github.com/iamsrishanth/OpenTrack360/actions/runs/45959501458), including the new command-helper tests, a clean local migration reset, and the full pgTAP suite.
- The current production Vercel deployment is READY at commit `97b1c9b6271126d3bf8e30b4bdaf3d58d0f393e8`; it publishes the verified Android release association.

## Remaining acceptance and provider prerequisites

- No signed-in account flow or valid guest share was exercised. The production one-time administrator bootstrap is consumed, and no existing admin credential was used or changed. The local-only, database-owner-gated synthetic-admin command and migration passed isolated CI; the migration still needs to be applied to the hosted database before the command can run.
- The Supabase project has no preview branch. [Supabase deployment guidance](https://supabase.com/docs/guides/deployment) says preview branching requires the Pro plan, which would add cost; no paid branch or additional project was created. After authorized admin access is available, provision two disposable synthetic members and run password rotation/disable, Circle isolation, location ingest, valid link revocation, and valid guest reads. No production member records were used as test data.
- Production synthetic-admin creation remains paused until the separate operator confirms the Supabase secret key rotation. The rotation and Vercel environment update are not performed by this worktree.
- Android's release package association is live. Device-level App Links verification still needs to be confirmed by Android with the release APK.
- FCM receipt testing needs Firebase server credentials and an opted-in test device.
- Weather and flight feeds remain unavailable until a free commercial source suitable for this product is confirmed. The map uses MapLibre with OpenFreeMap; attribution is required, there is no SLA, and tile requests disclose the viewed map area to the tile provider. See [feature scope](feature-scope.md).
