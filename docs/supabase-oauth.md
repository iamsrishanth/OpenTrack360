# OpenTrack360 Android sign-in with Supabase OAuth

OpenTrack360 uses Supabase Auth as an OAuth 2.1 identity provider for its own Android app. This is not Google or Apple social sign-in. The Android client is public and uses Authorization Code with PKCE (`S256`); no client secret belongs in Android or Vercel.

## Supabase project settings

In the existing Supabase project, configure **Authentication → OAuth Server** with:

- Enabled: on
- Authorization path: `/oauth/consent`
- Dynamic client registration: off
- Site URL: `https://track.srishanth.me`

Register a single public OAuth app named `OpenTrack360 for Android` with:

- Redirect URI: `https://track.srishanth.me/oauth/callback` (exact match)
- Token endpoint authentication: `none`
- Requested scope: `openid`
- No client secret

Set the returned public client ID as `SUPABASE_OAUTH_ANDROID_CLIENT_ID` in the production Vercel environment. The client ID is public. Do not add a client secret or the Supabase secret key to Android.

## Android flow

1. Fetch `GET https://track.srishanth.me/api/v1/auth/oauth-config`.
2. Generate a cryptographically random `state`, a PKCE verifier, and its SHA-256 `S256` challenge on device. Open the returned authorization endpoint in the system browser with `response_type=code`, the returned `clientId`, `redirectUri`, `scopes`, `state`, `code_challenge`, and `code_challenge_method=S256`.
3. Claim `/oauth/callback` as a verified HTTPS App Link in the release manifest. Validate the returned `state` before continuing. Read the one-time authorization `code` from the callback.
4. POST `{ "code": "…", "codeVerifier": "…" }` to the returned `tokenExchangeEndpoint`. The API exchanges the code with Supabase, verifies the user and signed OAuth `client_id`, and registers the OAuth session at the current account session epoch. The response is the regular `MemberSession` shape; never log the code, verifier, access token, or refresh token.
5. Send the returned access token as `Authorization: Bearer …` for member API calls. A temporary account receives `mustRotatePassword: true`; only password rotation is allowed until the member changes the temporary password.
6. Refresh through the returned `refreshEndpoint` with `{ "refreshToken": "…", "clientId": "…" }`. The server uses Supabase's OAuth refresh-token grant and rechecks the client ID and session epoch. Logout through `/api/v1/auth/logout` with the access-token bearer header and refresh token.

The consent page at `/oauth/consent` signs in with the provisioned username and password, enforces temporary-password rotation, and displays an explicit approval step. OAuth scopes govern OIDC claims, not database permissions; Supabase OAuth access tokens otherwise have the usual authenticated-user permissions. OpenTrack360 therefore keeps app tables and RPCs unavailable to direct `anon`/`authenticated` database clients, validates the signed `client_id` in its API, and checks each session against the account status and session epoch. Keep dynamic registration disabled and keep the OAuth client ID allowlist configured.

Supabase states that OAuth Server has no separate charge and OAuth users count toward the project's monthly active users. Check the project's current Free-plan MAU allowance before inviting additional users.
