# Deployment and acceptance checkpoint

**Snapshot date:** 27 November 2026

**Web branch:** `teams/web-platform`

**Web revision:** `676c4bf71973286fdf895048ce5da55acdb887e3`
**Pull request:** [#2, Expose drive-end trip evidence scope](https://github.com/iamsrishanth/OpenTrack360/pull/2) (draft)

This is a dated evidence snapshot, not a claim that the complete production acceptance plan has passed. Re-run the probes after a deployment or database change.

## Deployments and live route probes

| Surface | Observed result | What this proves |
| --- | --- | --- |
| Production `https://track.srishanth.me/` | HTTP 200; deployment uses `main` revision `45dbe9f56383179f1227d3eed90b1690fa562271` | The custom-domain site is serving HTML. |
| Production `GET /api/v1/me` without a bearer token | HTTP 401 with generic `unauthorized` response | The versioned route is present and rejects an unauthenticated caller. It does not prove a database-backed member flow. |
| Production `GET /api/v1/admin/accounts` without an admin session | HTTP 401; `Cache-Control: no-store` | The account-management API rejects a signed-out caller. |
| Production `GET /api/v1/shares` without a member bearer token | HTTP 401; `Cache-Control: no-store, private` | The sender-only share list is not available to a signed-out caller. |
| Production `GET /api/v1/public/shares/00000000-0000-4000-8000-000000000000` | HTTP 404 with generic `NOT_FOUND` response | An unknown share ID returns no location. It does not prove a valid share flow. |
| Production `/s/00000000-0000-4000-8000-000000000000` | HTTP 200 guest-viewer shell; `Cache-Control: private, no-store`; `Referrer-Policy: no-referrer`; `X-Robots-Tag: noindex, nofollow, noarchive` | The viewer shell loads without exposing a location; data still requires the fragment secret in the bearer header. |
| Production `/admin` | HTTP 200 login shell; `Referrer-Policy: no-referrer`; `X-Robots-Tag: noindex, nofollow, noarchive` | The public HTML contains the sign-in shell only. Account data is requested from the separately authenticated, no-store API. |
| Production `GET /.well-known/assetlinks.json` | HTTP 200, body `[]` | The file is served, but no Android package/signing association is configured. |
| Vercel preview for `teams/web-platform` | READY at [opentrack360-67slznlpt-srishanths-projects.vercel.app](https://opentrack360-67slznlpt-srishanths-projects.vercel.app), revision `676c4bf71973286fdf895048ce5da55acdb887e3` | Vercel built this source revision. The deployment is SSO-protected and is not evidence of production database acceptance. |

The Vercel checks on PR #2 are green: `Vercel` and `Vercel Preview Comments`. The current production deployment is still on the merged `main` revision above; the preview revision has not been promoted.

## Checks recorded for the web revision

- `npm run test:api`: 22/22 passing.
- `npm run typecheck`: passing.
- Redocly lint for `contracts/openapi.yaml`: passing.
- All 116 JSON example files parsed successfully.
- `git diff --check`: clean at the implementation checkpoint.
- `npm audit --audit-level=high`: no high or critical findings.
- Vercel preview build: READY; both GitHub preview checks pass.
- Migration `20261127000300_opentrack_sos_trip_share_resume.sql` executed in temporary PGlite against minimal local schemas and synthetic function definitions. Its 17 exact source-fragment replacements applied across the dispatcher (8), share creation (1), share event validation (7), and share list (1). This checks migration patch preconditions and SQL installation in the harness; it does not validate the target Supabase schema or execute the patched business flows.
- Migration `20261127000400_opentrack_share_trip_start_projection.sql` executed in a temporary PGlite harness with synthetic rows. Create/list returned the linked trip ID, start time, session-match boolean and epoch; mismatched session or epoch returned `false`. This validates the projection behavior in that harness only; it is not a Supabase migration run or a full schema test.

## Database and signed-in acceptance still pending

No current Supabase migration ledger or full pgTAP result is available for the latest revision. Migrations `20261127000300` and `20261127000400` have not been verified as applied to Supabase, and the current `supabase/tests/auth_sharing_invariants.sql` plan of 98 assertions has not been run against the target database. The earlier 91-assertion run predates this test revision and is not evidence for the current plan.

This environment has no Supabase CLI, `psql`, Docker, local Supabase access token, direct database URL, or Supabase MCP connection. Do not treat production API responses or the PGlite harness as a substitute for checking the current project schema and applying migrations.

Vercel environment-variable names currently listed for **production** are `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `SUPABASE_SECRET_KEY`, `OPENTRACK_AUTH_EMAIL_DOMAIN`, and `NEXT_PUBLIC_SITE_URL`. Secret values were not read. No environment variables were listed for preview branch `teams/web-platform`; configure preview only with isolated database credentials and synthetic fixtures. `OPENTRACK_ADMIN_BOOTSTRAP_SECRET` is documented in `.env.example` and the web README but is not present in the current Vercel variable list. Confirm that the one-time bootstrap has already completed; otherwise configure the one-time secret, bootstrap the first administrator, then remove it.

Required evidence before production database acceptance:

1. Use an isolated Supabase project or branch with two disposable member accounts in separate Circles and synthetic share data.
2. Inspect its schema and migration ledger, apply compatible migrations, and run the current 98-assertion pgTAP suite.
3. Exercise signed-in provisioning, password rotation/reset/disable, Circle isolation, location ingest, link creation/revocation, and the web/native guest view using those synthetic records.
4. Only then assess promotion of the tested revision and any production migration plan against the actual production schema and existing data.

## Android and provider prerequisites

- Release App Links remain unavailable: `assetlinks.json` is empty and the Android release signing SHA-256 has not been supplied. Do not add the debug certificate to production.
- FCM delivery remains unavailable until Firebase server credentials and an opted-in test device are configured and receipt is verified.
- Weather and flight feeds remain disabled because no free source with suitable product rights for this use has been confirmed. The dependency and map-provider notes are in [feature scope](feature-scope.md).
- The current Android integration branch and the release-signed share-sheet/native/browser acceptance are separate from the Vercel web preview; complete them with the Android release build once its signing identity is available.
