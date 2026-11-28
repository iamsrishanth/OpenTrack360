# Deployment and acceptance checkpoint

**Snapshot date:** 28 November 2026

**Merged PRs:** [#1](https://github.com/iamsrishanth/OpenTrack360/pull/1), [#2](https://github.com/iamsrishanth/OpenTrack360/pull/2), [#3](https://github.com/iamsrishanth/OpenTrack360/pull/3), [#5](https://github.com/iamsrishanth/OpenTrack360/pull/5), [#6](https://github.com/iamsrishanth/OpenTrack360/pull/6), [#7](https://github.com/iamsrishanth/OpenTrack360/pull/7), [#8](https://github.com/iamsrishanth/OpenTrack360/pull/8), [#9](https://github.com/iamsrishanth/OpenTrack360/pull/9)

**Initial web/API implementation:** `45dbe9f56383179f1227d3eed90b1690fa562271`

**Production application code commit:** `0959572e15f1a045c042a2a5a341172d574da327`

**Current production deployment:** `dpl_J5HMsqeCG3svC5tSWN49sTTgYsPC` (READY at `9ac93f85a35db42b099bd6fb92102e02dcc9ae42`; [deployment](https://opentrack360-pmx92oicb-srishanths-projects.vercel.app), aliased to `track.srishanth.me`)

This records production deployment, hosted migration, drive-end validator evidence, and public route probes. The current application code was introduced at `0959572e15f1a045c042a2a5a341172d574da327`; PRs #8 and #9 only update acceptance documentation. Signed-in end-to-end acceptance still needs an authorized platform administrator and disposable synthetic member credentials.

## Production deployment and route probes

The existing Vercel project `opentrack360` serves [track.srishanth.me](https://track.srishanth.me). A read-only Vercel project inspection confirmed the project root is `apps/web` and the build command is `npm run build`. The current production deployment is READY at `9ac93f85a35db42b099bd6fb92102e02dcc9ae42` and is aliased to the custom domain. Its application code is from `0959572e15f1a045c042a2a5a341172d574da327`; the later PR #8 and #9 changes only updated documentation.

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
| `GET /.well-known/assetlinks.json` | HTTP 200; `application/json`; one statement for package `me.srishanth.opentrack360` and release SHA-256 `22:32:AD:A1:CA:CB:94:55:50:16:AB:83:68:D8:DF:48:DF:10:36:33:A9:E3:A1:63:88:2F:64:3E:D0:44:32:24` | Production serves the expected release association. Android's latest acceptance report still records custom verifier error `1024`, so on-device verified-link behavior is not yet established. |

These route probes verify shell and signed-out behavior; they do not substitute for valid-member or valid-share flows.

## Supabase hosted database

The ignored local `.env.local` targets the existing Supabase Free project `OpenTrack360` (`oxfcxucpzrvbifefnwvp`). Supabase CLI 3.4.0 is authenticated and linked to that project. No credential values are committed to Git or included in fixtures.

The hosted database already contained the OpenTrack360 private schema, but it had no migration ledger. Before changing the ledger, read-only checks verified the existing structures and function markers for the first six repository migrations. Those six versions were reconciled as already applied with `supabase migration repair`; migrations `20261127000300` and `20261127000400` were then applied with `supabase db push`. The owner-only synthetic-admin migration `20261127000500` passed the isolated CI database suite and was applied through Supabase CLI to the existing production project. The remote ledger now reports all nine repository migrations applied.

Read-only checks confirm the operator function exists and is executable by `postgres` only; `service_role`, `anon`, and `authenticated` cannot call it. `service_role` has no direct `INSERT`, `UPDATE`, or `DELETE` privilege on `private.member_accounts`, and the operator audit table has RLS enabled. The migration did not create or change any account. Earlier checks confirmed the one-time administrator bootstrap is consumed and an active platform-administrator record exists; no account identity or credential was read. Auth settings report `disable_signup=true`, `mailer_autoconfirm=false`, and `phone_autoconfirm=false`.

## Drive-end validator examples

The 9-minute movement-to-stop gap remains a negative case: the actual hosted `public.opentrack_share_event` validator leaves the share pending, and the guest read remains HTTP 200. The positive fixture supplies continuous qualifying movement evidence within the permitted transition window followed by five minutes of stationary evidence: the validator confirms the event, and the guest read returns HTTP 410 with no location or speed. Both cases were executed inside a transaction and rolled back. A read-only postcheck found no persisted fixture accounts, shares, or location samples.

## Build and test evidence

- `npm run test:api`: 23/23 passed.
- `npm run typecheck`: passed.
- `npm run build`: passed and generated the guest share, pet recovery, invitation, admin, and versioned API routes.
- Redocly lint for `contracts/openapi.yaml`: passed; all 120 contract JSON examples parsed.
- The [Supabase database workflow](https://github.com/iamsrishanth/OpenTrack360/actions/runs/45952892371) passed on PR #3. The same workflow passed on [PR #7](https://github.com/iamsrishanth/OpenTrack360/actions/runs/45988903479), including command-helper tests, a clean local migration reset, and the full pgTAP suite.
- `node --test scripts/operator-provision-admin.test.mjs` passed 6/6; the non-mutating CLI check verified the expected project and `postgres` database-management session.
- On 28 November 2026, read-only Vercel inspection found the production deployment READY at `9ac93f85a35db42b099bd6fb92102e02dcc9ae42`. Public probes returned 200 for `/`, `/admin`, `/s/{id}`, `/p/{id}`, `/i`, and `/.well-known/assetlinks.json`; 401 for signed-out `/api/v1/me`, `/api/v1/admin/accounts`, and `/api/v1/shares`; and 404 for an unknown public share without a secret. The guest shells returned `private, no-store`, `no-referrer`, and `noindex` headers. The release association contained the expected package and fingerprint.

## Remaining acceptance and provider prerequisites

- No signed-in account flow or valid guest share was exercised, and no synthetic account or active test link has been created. The production one-time administrator bootstrap is consumed; no existing admin credential was used or changed. The local-only, database-owner-gated synthetic-admin command and migration passed isolated CI, and the migration is applied to production.
- The Supabase project has no preview branch. [Supabase deployment guidance](https://supabase.com/docs/guides/deployment) says preview branching requires the Pro plan, which would add cost; no paid branch or additional project was created. After authorized admin access is available, provision two disposable synthetic members and run password rotation/disable, Circle isolation, location ingest, valid link revocation, and valid guest reads. No production member records were used as test data.
- Production synthetic-admin creation remains paused until the separate operator confirms the Supabase secret key rotation. The rotation and Vercel environment update are not performed by this worktree. Once confirmed, the command will save the credentials under `%LOCALAPPDATA%\OpenTrack360\release-test-20261127\operator-credentials.json` with a private ACL.
- Android's release package association is live, but Android's latest acceptance report says both connected verifiers returned custom error `1024`. The release link is therefore not yet verified on-device, and an external messaging-app tap still needs to be tested.
- FCM receipt testing needs Firebase server credentials and an opted-in test device.
- Weather and flight feeds remain unavailable until a free commercial source suitable for this product is confirmed. The map uses MapLibre with OpenFreeMap; attribution is required, there is no SLA, and tile requests disclose the viewed map area to the tile provider. See [feature scope](feature-scope.md).
