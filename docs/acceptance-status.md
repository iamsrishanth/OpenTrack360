# Deployment and acceptance checkpoint

**Snapshot date:** 28 November 2026

**Initial web/API implementation:** `45dbe9f56383179f1227d3eed90b1690fa562271`

**Production application code commit:** `30ddf1e4cbadc25e70950a53b72906c7142eceb9`

The current production deployment includes PR #15, which closes the global PostgreSQL function-default grant gap and stops the guest map from recentering when a poll returns the same location. The production OAuth client configuration was added to Vercel before this deployment; route probes below record the behavior observed on 28 November 2026.

This records production deployment, hosted migration, drive-end validator evidence, public route probes, and Android domain-verification state. Signed-in end-to-end acceptance still needs an authorized platform administrator and disposable synthetic member credentials.

## Production deployment and route probes

The existing Vercel project `opentrack360` serves [track.srishanth.me](https://track.srishanth.me). A read-only Vercel project inspection confirmed the project root is `apps/web` and the build command is `npm run build`. The production deployment used for the latest route probe is READY at `30ddf1e4cbadc25e70950a53b72906c7142eceb9` and aliased to the custom domain. This deployment also includes the production-only public OAuth client ID setting.

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
| `GET /api/v1/auth/oauth-config` | HTTP 200; public client ID, `openid`, S256, and exact `/oauth/callback` URI | The first-party Supabase OAuth Server client is registered as public PKCE and has no client secret. |
| Supabase OAuth discovery | HTTP 200 | The project publishes its OAuth 2.1 issuer and authorization/token discovery endpoints. |
| `GET /.well-known/assetlinks.json` | HTTP 200; `application/json`; one statement for package `me.srishanth.opentrack360` and release SHA-256 `22:32:AD:A1:CA:CB:94:55:50:16:AB:83:68:D8:DF:48:DF:10:36:33:A9:E3:A1:63:88:2F:64:3E:D0:44:32:24` | Production serves the expected release association. Google's Digital Asset Links check reports `linked: true`; a read-only `adb pm get-app-links` check reports `track.srishanth.me: verified` on the connected emulator and both Moto G57 Power devices. Android reports user link selection disabled on the emulator and Moto; actual message-app taps remain unverified. |

These route probes verify shell and signed-out behavior; they do not substitute for valid-member or valid-share flows.

## Supabase hosted database

The ignored local `.env.local` targets the existing Supabase Free project `OpenTrack360` (`oxfcxucpzrvbifefnwvp`). Supabase CLI 3.4.0 is authenticated and linked to that project. No credential values are committed to Git or included in fixtures.

The hosted database already contained the OpenTrack360 private schema, but it had no migration ledger. Before changing the ledger, read-only checks verified the existing structures and function markers for the first six repository migrations. Those six versions were reconciled as already applied with `supabase migration repair`; the later migrations were applied with the Supabase CLI. The owner-only synthetic-admin migration `20261127000500` passed the isolated CI database suite. On 28 November, migration `20261128000200` passed clean-database CI and was applied through Supabase CLI to the existing production project. The remote ledger now reports all 11 repository migrations applied.

Read-only checks confirm the operator function exists and is executable by `postgres` only; `service_role`, `anon`, and `authenticated` cannot call it. `service_role` has no direct `INSERT`, `UPDATE`, or `DELETE` privilege on `private.member_accounts`, and the operator audit table has RLS enabled. The new global function default ACL contains only the `postgres` owner; `anon` and `authenticated` are absent, while the `public` schema still grants the required default `EXECUTE` to `service_role`. A production query found 34 `opentrack_*` functions, with zero executable by `anon` or `authenticated` and 34 executable by `service_role`. The migration did not create or change any account. Earlier checks confirmed the one-time administrator bootstrap is consumed and an active platform-administrator record exists; no account identity or credential was read. Auth settings report `disable_signup=true`, `mailer_autoconfirm=false`, and `phone_autoconfirm=false`.

## Drive-end validator examples

The 9-minute movement-to-stop gap remains a negative case: the actual hosted `public.opentrack_share_event` validator leaves the share pending, and the guest read remains HTTP 200. The positive fixture supplies continuous qualifying movement evidence within the permitted transition window followed by five minutes of stationary evidence: the validator confirms the event, and the guest read returns HTTP 410 with no location or speed. Both cases were executed inside a transaction and rolled back. A read-only postcheck found no persisted fixture accounts, shares, or location samples.

## Build and test evidence

- `npm run test:api`: 30/30 passed after the OAuth and viewer updates.
- `npm run typecheck`: passed.
- `npm run build`: passed and generated the guest share, pet recovery, invitation, admin, and versioned API routes.
- Redocly lint for `contracts/openapi.yaml`: passed; all 125 contract JSON examples parsed.
- The [Supabase database workflow](https://github.com/iamsrishanth/OpenTrack360/actions/runs/46138607684) passed on PR #15, including operator helper tests, a clean local migration reset, and the full pgTAP suite. The migration is now applied to production.
- `node --test scripts/operator-provision-admin.test.mjs` passed 6/6; the non-mutating CLI check verified the expected project and `postgres` database-management session.
- On 28 November 2026, the production deployment at `30ddf1e4cbadc25e70950a53b72906c7142eceb9` was READY. Public probes returned 200 for `/`, `/admin`, `/s/{id}`, `/p/{id}`, `/i`, `/oauth/consent`, `/oauth/callback`, and `/.well-known/assetlinks.json`; 401 for signed-out `/api/v1/me`, `/api/v1/admin/accounts`, and `/api/v1/shares`; and 404 for an unknown public share without a secret. `/api/v1/auth/oauth-config` returned 200 with the public PKCE client configuration. Guest shells returned `private, no-store`, `no-referrer`, and `noindex` headers. The release association contained the expected package and fingerprint.

## Remaining acceptance and provider prerequisites

- No signed-in account flow or valid guest share was exercised, and no synthetic account or active test link has been created. The production one-time administrator bootstrap is consumed; no existing admin credential was used or changed. The local-only, database-owner-gated synthetic-admin command and migration passed isolated CI, and all 11 migrations are applied to production.
- The Supabase project has no preview branch. [Supabase deployment guidance](https://supabase.com/docs/guides/deployment) says preview branching requires the Pro plan, which would add cost; no paid branch or additional project was created. After authorized admin access is available, provision two disposable synthetic members and run password rotation/disable, Circle isolation, location ingest, valid link revocation, and valid guest reads. No production member records were used as test data.
- Production synthetic-admin creation remains paused pending verified Supabase secret-key rotation. The Vercel `SUPABASE_SECRET_KEY` environment metadata has not changed since 26 November; its value was not retrieved. A previous attempt to pull/decrypt the Vercel secret was blocked by automatic approval review, so the key match cannot be confirmed from this worktree. No secret value was printed or copied. Once the separate operator confirms rotation and the Vercel environment update, the supported command can save disposable account credentials under `%LOCALAPPDATA%\OpenTrack360\release-test-20261127\operator-credentials.json` with a private ACL.
- OAuth Server setup is live: authorization path `/oauth/consent`, dynamic registration disabled, a public Android PKCE client with `openid`, no client secret, and exact callback `https://track.srishanth.me/oauth/callback`. The API config endpoint is live. The current Android branch has no OAuth callback/client integration, so the Android App Link callback, login exchange, password rotation, and an OAuth member session still need Android implementation plus a synthetic member account.
- Android's release association is live and domain verification is confirmed by Google and read-only package checks. The Android acceptance report records user link selection disabled on both the emulator and Moto, and the implicit app-launch attempt was blocked by automatic review. A real external messaging-app tap and user-selected app dispatch remain unverified; no app default was forced or changed.
- FCM receipt testing needs Firebase server credentials and an opted-in test device.
- Weather and flight feeds remain unavailable until a free commercial source suitable for this product is confirmed. The map uses MapLibre with OpenFreeMap; attribution is required, there is no SLA, and tile requests disclose the viewed map area to the tile provider. See [feature scope](feature-scope.md).
- The Vercel team is on the Hobby plan; the owner confirmed this is a personal project. Keep the deployment personal/non-commercial unless the project moves to a suitable paid plan.
