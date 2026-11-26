# OpenTrack360 web platform

The Next.js App Router website serves the public guest trip viewer, software-only pet recovery cards, private administrator screens, and the Android-facing API at `/api/v1`.

## Local development

Use Node.js 20.9 or newer and npm. The Vercel project should use `apps/web` as its Root Directory; npm resolves the repository's root workspace and lockfile.

```powershell
npm install
Copy-Item .env.example .env.local
npm run dev
```

For type checking and a production build:

```powershell
npm run typecheck
npm run build
```

## Configuration

Copy the names from the repository-root `.env.example` into the local or Vercel environment. Supply values only through the protected environment settings; never commit `.env.local` or paste secret values into tickets, logs, or client code.

| Variable | Use | Exposure |
| --- | --- | --- |
| `SUPABASE_URL` | Existing Supabase project URL | Server-side configuration |
| `SUPABASE_PUBLISHABLE_KEY` | Supabase publishable key used for Auth client operations | Server-side configuration; do not use it to bypass OpenTrack360 authorization |
| `SUPABASE_SECRET_KEY` | Privileged Data API, Storage, and Auth Admin operations | Server-only secret; send as `apikey` only and never expose to browser or Android |
| `OPENTRACK_AUTH_EMAIL_DOMAIN` | Controlled internal domain for opaque Supabase Auth email identities | Server-side configuration |
| `OPENTRACK_ADMIN_BOOTSTRAP_SECRET` | One-time operator-only bootstrap of the first web administrator | Restricted server-only secret; remove after bootstrap |

No default administrator account or password is created. Public Supabase Auth sign-up is disabled. Use synthetic accounts and locations in preview environments. An admin password reset immediately revokes member sessions, advances the sharing epoch, disables Circle location/activity consent, ends guest links and active trips, and clears the latest location; the member must explicitly re-enable sharing after rotating the temporary password.

## Privacy and provider behavior

- Guest share and pet recovery secrets are read from the URL fragment in the browser and sent in the bearer header only. Never add a secret to a query string, request URL, telemetry event, or log.
- The guest trip page uses the last response only until its display lease expires. It clears the marker on an invalid/ended response and when a backgrounded page resumes before revalidating.
- MapLibre loads the OpenFreeMap public vector style with visible attribution. The provider/CDN receives requests for the map area being viewed. The public map service has no SLA and does not supply search, routing, or geocoding.
- See `docs/feature-scope.md` for provider boundaries, pilot calculations, and the currently unavailable weather/flight integrations.

## Database

Apply versioned migrations from `supabase/migrations/` only after inspecting the target project and reviewing the SQL. Use the database tests under `supabase/tests/` with a local or disposable Supabase project and synthetic records. Admin Auth calls and API RPC calls run on trusted server code with the service-role key.
