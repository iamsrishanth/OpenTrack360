# Google sign-in for the platform administrator

OpenTrack360 has two different OAuth roles:

- Supabase OAuth Server lets the Android app authenticate a provisioned OpenTrack360 account through the website's consent page.
- The Google external provider lets the named platform administrator sign in to `/admin` with Google.

The Google path uses Authorization Code with PKCE (`S256`). The website accepts a Google identity only when its email is verified, exactly matches `OPENTRACK_ADMIN_GOOGLE_EMAIL`, and is already linked to an active `member_accounts` row marked as a platform administrator with `admin_auth_method = 'google'`. Google sign-in does not grant a role. The browser session is HTTP-only and scoped to `/api/v1/admin`. Platform administrators are denied access to member API routes, and their identities do not appear in the member account list or password reset flow.

## Provider setup

1. In Google Cloud, configure an OAuth consent screen for the personal project. It can stay in Testing while this administrator is the only test user.
2. Create a Google OAuth client with application type **Web application**. Set the authorized redirect URI to:

   ```text
   https://oxfcxucpzrvbifefnwvp.supabase.co/auth/v1/callback
   ```

3. In the existing Supabase project, open **Authentication → Providers → Google**, add the Google client ID and client secret, and enable the provider.
4. In **Authentication → URL Configuration**, allow this exact application callback:

   ```text
   https://track.srishanth.me/api/v1/admin/google/callback
   ```

   For local development, add `http://localhost:3000/api/v1/admin/google/callback` only while using a local Supabase Auth instance.
5. Keep public signup disabled. Supabase Auth must contain the target identity before the Google callback can receive an authorized admin session.
6. Set the following production variables by name. The allowed email is case-normalized and compared exactly; the Google client secret stays in Supabase Auth provider settings and is not a Vercel variable.

   ```text
   OPENTRACK_ADMIN_GOOGLE_EMAIL
   NEXT_PUBLIC_SITE_URL
   SUPABASE_URL
   SUPABASE_PUBLISHABLE_KEY
   SUPABASE_SECRET_KEY
   ```

The `/admin` page shows **Continue with Google** only when Supabase reports that Google is enabled and the allowlist variable is configured. Production callbacks are disabled on Vercel Preview deployments.

## Owner-only account provisioning

Use the local operator command after confirming Supabase key rotation. It verifies the Supabase CLI identity, the exact production project, a direct `postgres` database-management session, and that `.env.local` contains the newest named OpenTrack360 server key shown by Supabase. The secret key from `.env.local` is used only by Supabase Auth Admin to create a passwordless Auth identity when needed. The role grant is performed only by `private.opentrack_operator_add_google_admin`, which is executable by `postgres`, not the web runtime.

```powershell
node scripts/operator-provision-google-admin.mjs --email <verified-google-email> --project-ref oxfcxucpzrvbifefnwvp
node scripts/operator-provision-google-admin.mjs --execute --email <verified-google-email> --project-ref oxfcxucpzrvbifefnwvp --key-rotation-verified --env-file C:\Code\OpenTrack360\.env.local
```

The execution command refuses to modify an existing member account, preserves the existing administrator and consumed bootstrap, creates an admin-only account with sharing disabled, and records the verified Supabase CLI subject and database role in private audit tables. It does not set a usable password. OAuth callback access still requires an exact verified Google identity and the pre-provisioned database role.

Supabase OAuth Server has no separate charge; the Google-authenticated user still counts toward the project's monthly active users. See the current [Google sign-in setup](https://supabase.com/docs/guides/auth/social-sign-in/auth-google), [Supabase identity linking](https://supabase.com/docs/guides/auth/identities), and [Supabase OAuth Server pricing notes](https://supabase.com/docs/guides/auth/oauth-server#pricing).
