#!/usr/bin/env node
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { createRequire } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";

export const PROJECT_REF = "oxfcxucpzrvbifefnwvp";
export const PROJECT_URL = `https://${PROJECT_REF}.supabase.co`;
export const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
export const GOOGLE_ADMIN_DISPLAY_NAME = "Google Administrator";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function createSupabaseClient(url, secretKey) {
  const webRequire = createRequire(path.join(REPO_ROOT, "apps", "web", "package.json"));
  const { createClient } = webRequire("@supabase/supabase-js");
  return createClient(url, secretKey, {
    auth: { autoRefreshToken: false, persistSession: false, detectSessionInUrl: false },
  });
}

export function normalizeGoogleEmail(value) {
  if (typeof value !== "string") return null;
  const email = value.trim().toLowerCase();
  return email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ? email : null;
}

export function googleAdminUsername(email) {
  const normalized = normalizeGoogleEmail(email);
  if (!normalized) throw new Error("A valid Google email is required");
  let local = normalized.split("@")[0].replace(/[^a-z0-9._-]+/g, ".").replace(/^[._-]+|[._-]+$/g, "").slice(0, 24);
  if (local.length < 2) local = "google.admin";
  const username = `${local}.admin`;
  if (!/^[a-z0-9][a-z0-9._-]{1,30}[a-z0-9]$/.test(username)) throw new Error("The normalized admin username is invalid");
  return username;
}

export function googleAdminRequestId(email, projectRef = PROJECT_REF) {
  const normalized = normalizeGoogleEmail(email);
  if (!normalized || projectRef !== PROJECT_REF) throw new Error("The Google admin request target is invalid");
  const bytes = createHash("sha256").update(`opentrack360:google-admin:${projectRef}:${normalized}`).digest().subarray(0, 16);
  bytes[6] = (bytes[6] & 0x0f) | 0x50;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function parseArguments(argv) {
  const options = { execute: false, keyRotationVerified: false, projectRef: null, email: null, envFile: null, help: false };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--execute") options.execute = true;
    else if (arg === "--key-rotation-verified") options.keyRotationVerified = true;
    else if (arg === "--help" || arg === "-h") options.help = true;
    else if (arg === "--project-ref") { options.projectRef = argv[index + 1] ?? null; index += 1; }
    else if (arg === "--email") { options.email = normalizeGoogleEmail(argv[index + 1]); index += 1; }
    else if (arg === "--env-file") { options.envFile = argv[index + 1] ?? null; index += 1; }
    else throw new Error("Unknown command option");
  }
  if (options.help) return options;
  if (!options.email) throw new Error("A valid target Google email is required");
  if (options.projectRef !== null && options.projectRef !== PROJECT_REF) throw new Error("Project reference does not match the configured production project");
  if (options.execute && options.projectRef !== PROJECT_REF) throw new Error("Production execution requires the exact --project-ref value");
  if (options.execute && !options.keyRotationVerified) throw new Error("Production execution requires --key-rotation-verified after the owner confirms key rotation");
  return options;
}

export function parseLocalEnv(text, allowedKeys = null) {
  const values = {};
  const allowed = allowedKeys ? new Set(allowedKeys) : null;
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const match = /^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line);
    if (!match || (allowed && !allowed.has(match[1]))) continue;
    let value = match[2].trim();
    if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))) value = value.slice(1, -1);
    values[match[1]] = value;
  }
  return values;
}

export function isRequestScopedGoogleUser(user, email, requestId) {
  return user?.email?.toLowerCase() === email && user?.email_confirmed_at != null &&
    user?.user_metadata?.opentrack_google_admin_email === email &&
    user?.user_metadata?.opentrack_google_admin_request_id === requestId;
}

export function isVerifiedGoogleUser(user, email) {
  return user?.email?.toLowerCase() === email && user?.email_confirmed_at != null &&
    Array.isArray(user?.identities) && user.identities.some((identity) =>
      identity.provider === "google" &&
      identity.identity_data?.email_verified === true &&
      identity.identity_data?.email?.toLowerCase() === email,
    );
}

export function buildOperatorSql({ requestId, operatorSubjectId, targetUserId, email, username, projectRef = PROJECT_REF }) {
  if (!UUID.test(requestId ?? "") || !UUID.test(operatorSubjectId ?? "") || !UUID.test(targetUserId ?? "")) throw new Error("Invalid operator provisioning identifier");
  const normalizedEmail = normalizeGoogleEmail(email);
  if (!normalizedEmail || projectRef !== PROJECT_REF || username !== googleAdminUsername(normalizedEmail)) throw new Error("Google administrator target is outside the requested project scope");
  const literal = (value) => `'${String(value).replaceAll("'", "''")}'`;
  return [
    "select private.opentrack_operator_add_google_admin(",
    `  ${literal(requestId)}::uuid,`,
    `  ${literal(operatorSubjectId)}::uuid,`,
    `  ${literal(targetUserId)}::uuid,`,
    `  ${literal(normalizedEmail)},`,
    `  ${literal(username)},`,
    `  ${literal(projectRef)}`,
    ") as result;",
  ].join("\n");
}

export function extractJsonObject(output) {
  const start = output.indexOf("{");
  if (start < 0) throw new Error("Supabase CLI did not return JSON");
  let depth = 0; let quoted = false; let escaped = false;
  for (let index = start; index < output.length; index += 1) {
    const char = output[index];
    if (quoted) {
      if (escaped) escaped = false;
      else if (char === "\\") escaped = true;
      else if (char === '"') quoted = false;
      continue;
    }
    if (char === '"') quoted = true;
    else if (char === "{") depth += 1;
    else if (char === "}" && --depth === 0) return JSON.parse(output.slice(start, index + 1));
  }
  throw new Error("Supabase CLI returned incomplete JSON");
}

export function localSecretMatchesLatestServerKey(secretKey, apiKeysOutput) {
  if (typeof secretKey !== "string" || !secretKey.startsWith("sb_secret_")) return false;
  let document;
  try { document = extractJsonObject(apiKeysOutput); } catch { return false; }
  if (document.target?.project_ref !== PROJECT_REF || !Array.isArray(document.keys)) return false;
  const latest = document.keys
    .filter((key) => key?.type === "secret" &&
      typeof key.name === "string" && /^opentrack360_server_\d{8}$/.test(key.name) &&
      typeof key.prefix === "string" && key.prefix.startsWith("sb_secret_") &&
      typeof key.inserted_at === "string" && Number.isFinite(Date.parse(key.inserted_at)))
    .sort((a, b) => Date.parse(b.inserted_at) - Date.parse(a.inserted_at))[0];
  return !!latest && secretKey.startsWith(latest.prefix) && secretKey.length > latest.prefix.length;
}

function quotePowerShell(value) { return `'${String(value).replaceAll("'", "''")}'`; }

function supabaseCli(args) {
  const fullArgs = ["--yes", "supabase@3.4.0", ...args];
  if (process.platform === "win32") {
    const command = `& npx ${fullArgs.map(quotePowerShell).join(" ")}`;
    return execFileSync("powershell.exe", ["-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command], {
      cwd: REPO_ROOT, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], maxBuffer: 4 * 1024 * 1024,
    });
  }
  return execFileSync("npx", fullArgs, { cwd: REPO_ROOT, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], maxBuffer: 4 * 1024 * 1024 });
}

function verifyManagementContext() {
  const who = extractJsonObject(supabaseCli(["whoami", "--output-format", "json"]));
  if (!UUID.test(who.id ?? "")) throw new Error("Supabase CLI profile has no verified operator subject id");
  const migration = extractJsonObject(supabaseCli(["migration", "list", `--remote=${PROJECT_REF}`, "--output-format", "json"]));
  if (migration.target?.project_ref !== PROJECT_REF || migration.target?.environment !== "production") throw new Error("Linked Supabase CLI project is not the expected production database");
  const roleResult = extractJsonObject(supabaseCli(["db", "query", `--remote=${PROJECT_REF}`, "--yes", "--output-format", "json", "select current_user as db_role, session_user as login_role, current_database() as database_name"]));
  const role = roleResult.rows?.[0];
  if (role?.db_role !== "postgres" || role?.login_role !== "postgres" || role?.database_name !== "postgres") throw new Error("Supabase CLI database channel is not authenticated as the project database owner");
  return { operatorSubjectId: who.id, databaseRole: role.db_role };
}

function secureDirectory() {
  const localAppData = process.env.LOCALAPPDATA || path.join(os.homedir(), "AppData", "Local");
  const directory = path.resolve(localAppData, "OpenTrack360", "google-admin-provisioning");
  const relative = path.relative(REPO_ROOT, directory);
  if (!relative || (!relative.startsWith("..") && !path.isAbsolute(relative))) throw new Error("Operator SQL must be created outside the repository");
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
  if (process.platform === "win32") {
    const account = process.env.USERDOMAIN ? `${process.env.USERDOMAIN}\\${process.env.USERNAME}` : process.env.USERNAME;
    if (!account) throw new Error("Current Windows account could not be determined for private operator permissions");
    execFileSync("icacls.exe", [directory, "/inheritance:r", "/grant:r", `${account}:(OI)(CI)F`, "/grant:r", "SYSTEM:(OI)(CI)F", "/grant:r", "BUILTIN\\Administrators:(OI)(CI)F"], { stdio: "ignore" });
  } else fs.chmodSync(directory, 0o700);
  return directory;
}

async function findAuthUser(admin, email) {
  for (let page = 1; page <= 100; page += 1) {
    const { data, error } = await admin.auth.admin.listUsers({ page, perPage: 1000 });
    if (error) throw new Error("Supabase Auth Admin could not check the target identity");
    const match = (data.users ?? []).find((user) => user.email?.toLowerCase() === email);
    if (match) return match;
    if ((data.users ?? []).length < 1000) return null;
  }
  throw new Error("Supabase Auth user scan exceeded its safety limit");
}

async function ensureGoogleAuthUser(admin, email, requestId) {
  let user = await findAuthUser(admin, email);
  if (user) {
    if (isRequestScopedGoogleUser(user, email, requestId) || isVerifiedGoogleUser(user, email)) return user.id;
    throw new Error("A conflicting or unverified Auth identity exists; no account was changed");
  }

  const userMetadata = { opentrack_google_admin_email: email, opentrack_google_admin_request_id: requestId };
  const { data, error } = await admin.auth.admin.createUser({ email, email_confirm: true, user_metadata: userMetadata });
  if (!error && data.user?.id) return data.user.id;

  user = await findAuthUser(admin, email);
  if (user && isRequestScopedGoogleUser(user, email, requestId)) return user.id;
  throw new Error("Supabase Auth did not confirm the request-scoped Google identity; it is safe to retry");
}

function parseResult(output) {
  const document = extractJsonObject(output);
  const row = document.rows?.[0];
  const result = row?.result ?? row;
  if (result?.ok !== true || !UUID.test(result.accountId ?? "")) throw new Error("Database operator command did not return the expected account result");
  return result;
}

function usage() {
  return [
    "Usage:",
    "  node scripts/operator-provision-google-admin.mjs --email <verified-google-email> [--project-ref oxfcxucpzrvbifefnwvp] [--env-file <path>]",
    "  node scripts/operator-provision-google-admin.mjs --execute --email <verified-google-email> --project-ref oxfcxucpzrvbifefnwvp --key-rotation-verified [--env-file <path>]",
    "",
    "Dry-run verifies only the Supabase CLI project and database-owner channel.",
    "Execution creates a passwordless Auth identity if needed, then grants its Google-only admin role through the postgres-only audited function.",
  ].join("\n");
}

async function main(argv = process.argv.slice(2)) {
  const options = parseArguments(argv);
  if (options.help) { process.stdout.write(`${usage()}\n`); return; }
  const envPath = path.resolve(options.envFile ?? path.join(REPO_ROOT, ".env.local"));
  const allowedKeys = options.execute ? ["SUPABASE_URL", "SUPABASE_SECRET_KEY"] : ["SUPABASE_URL"];
  const localEnv = parseLocalEnv(fs.readFileSync(envPath, "utf8"), allowedKeys);
  if (localEnv.SUPABASE_URL !== PROJECT_URL && localEnv.SUPABASE_URL !== `${PROJECT_URL}/`) throw new Error(".env.local does not target the configured production Supabase project");

  const management = verifyManagementContext();
  if (!options.execute) {
    process.stdout.write(`${JSON.stringify({ mode: "dry-run", projectVerified: true, databaseRoleVerified: management.databaseRole })}\n`);
    return;
  }
  if (!options.keyRotationVerified || options.projectRef !== PROJECT_REF) throw new Error("Production creation requires owner-confirmed key rotation and the exact project reference");
  const secretKey = localEnv.SUPABASE_SECRET_KEY;
  if (!secretKey || !secretKey.startsWith("sb_secret_")) throw new Error("The rotated server-side Supabase secret key must be present in the selected .env.local");
  const keyList = supabaseCli(["projects", "api-keys", `--remote=${PROJECT_REF}`, "--output-format", "json"]);
  if (!localSecretMatchesLatestServerKey(secretKey, keyList)) {
    throw new Error("The selected .env.local does not contain the latest named OpenTrack360 server key");
  }

  const email = options.email;
  const requestId = googleAdminRequestId(email, options.projectRef);
  const username = googleAdminUsername(email);
  const supabase = createSupabaseClient(localEnv.SUPABASE_URL.replace(/\/$/, ""), secretKey);
  const targetUserId = await ensureGoogleAuthUser(supabase, email, requestId);
  const sql = buildOperatorSql({ requestId, operatorSubjectId: management.operatorSubjectId, targetUserId, email, username, projectRef: options.projectRef });
  const directory = secureDirectory();
  const sqlPath = path.join(directory, `google-admin-${requestId}.sql`);
  fs.writeFileSync(sqlPath, sql, { encoding: "utf8", mode: 0o600, flag: "wx" });
  try {
    const result = parseResult(supabaseCli(["db", "query", "--remote", "--yes", "--file", sqlPath]));
    process.stdout.write(`${JSON.stringify({ status: result.idempotent ? "already_created" : "created", username: result.username, projectRef: options.projectRef })}\n`);
  } finally {
    fs.rmSync(sqlPath, { force: true });
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  main().catch(() => {
    process.stderr.write("Google administrator provisioning failed. No credential, email, or key values were printed; resolve the owner-managed prerequisite and retry.\n");
    process.exitCode = 1;
  });
}
