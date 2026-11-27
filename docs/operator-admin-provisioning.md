# Owner-only synthetic administrator provisioning

The first administrator is created by a one-time bootstrap. That bootstrap has
already been consumed and must not be reopened. The website has no public admin
enrollment or member-facing role promotion.

`scripts/operator-provision-admin.mjs` is a local management command, not a web
route. It uses the Supabase CLI profile and database-management connection for
the audit actor and the role promotion. The Auth Admin API is used only to
create the request-scoped synthetic Auth identity. It reads the project URL and
the rotated Supabase secret key from the ignored root `.env.local`; the secret
key is never used to grant a platform role.

Before execution, the command verifies that:

- `.env.local` points to project `oxfcxucpzrvbifefnwvp`;
- `supabase whoami` returns an operator subject ID;
- the remote migration target is that production project; and
- the CLI's direct database session reports both `current_user` and
  `session_user` as `postgres`.

The operator function additionally checks the exact project, the Auth user's
request ID and username metadata, the consumed bootstrap, and the continued
presence of an active existing administrator. It inserts one dedicated
`release-op-*` synthetic admin with sharing disabled and forced initial
password rotation. It never edits an existing administrator. The database
audit row records the CLI operator subject ID, request ID, project, target
identity, and `postgres` database role. The function is executable only by
`postgres`; `service_role`, `authenticated`, and `anon` cannot call it. Direct
`service_role` writes to `private.member_accounts` are revoked as a second
guard. Application account operations continue through the existing
`SECURITY DEFINER` functions.

## Local checks

After the owner confirms that the exposed Supabase secret key has been rotated
and `.env.local` has the replacement, run a dry check:

```powershell
node scripts/operator-provision-admin.mjs --project-ref oxfcxucpzrvbifefnwvp
```

The dry check reads no Auth users and changes no database state. Only after the
owner's explicit rotation confirmation may an authorized project operator run:

```powershell
node scripts/operator-provision-admin.mjs --execute --project-ref oxfcxucpzrvbifefnwvp --key-rotation-verified
```

The command writes the temporary credential once to the current Windows
user's private directory:

```text
%LOCALAPPDATA%\OpenTrack360\release-test-20261127\operator-credentials.json
```

The directory ACL grants access only to the current user, `SYSTEM`, and local
Administrators. The command prints the path and status only; it does not print
the username, password, CLI identity, SQL output, or Supabase key. If creation
is interrupted, the private pending request retains its request ID and
temporary password for a safe retry. A completed credentials file prevents a
second administrator from being created by this command.

After the operator logs in, they must rotate the temporary password before
using the account. Use the normal website account-provisioning flow for
disposable synthetic member accounts. Keep the operator credential file outside
Git and remove the synthetic operator and test accounts only after Android
device acceptance has been acknowledged.
