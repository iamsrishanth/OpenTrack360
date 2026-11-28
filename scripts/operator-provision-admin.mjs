#!/usr/bin/env node
import { randomBytes, randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { createRequire } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";

export const PROJECT_REF = "oxfcxucpzrvbifefnwvp";
export const PROJECT_URL = `https://${PROJECT_REF}.supabase.co`;
export const ACCOUNT_DISPLAY_NAME = "OpenTrack360 Release Test Operator";
export const HANDOFF_DIRECTORY_NAME = "release-test-20261127";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(scriptDirectory, "..");
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function createSupabaseClient(url, secretKey) {
  const webRequire = createRequire(path.join(REPO_ROOT, "apps", "web", "package.json"));
  const { createClient } = webRequire("@supabase/supabase-js");
  return createClient(url, secretKey, {
    auth: { autoRefreshToken: false, persistSession: false, detectSessionInUrl: false },
  });
}

export function parseArguments(argv) {
  const options = { execute: false, keyRotationVerified: false, projectRef: null, help: false };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--execute") options.execute = true;
    else if (arg === "--key-rotation-verified") options.keyRotationVerified = true;
    else if (arg === "--help" || arg === "-h") options.help = true;
    else if (arg === "--project-ref") {
      options.projectRef = argv[index + 1] ?? null;
      index += 1;
    } else {
      throw new Error("Unknown command option");
    }
  }
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
    const keyMatch = /^([A-Za-z_][A-Za-z0-9_]*)\s*=/.exec(line);
    if (!keyMatch || (allowed && !allowed.has(keyMatch[1]))) continue;
    const match = /^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line);
    if (!match) continue;
    let value = match[2].trim();
    if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))) {
      value = value.slice(1, -1);
    }
    values[match[1]] = value;
  }
  return values;
}

export function getProjectRefFromUrl(value) {
  try {
    const parsed = new URL(value);
    const match = /^([a-z0-9]+)\.supabase\.co$/i.exec(parsed.hostname);
    return parsed.protocol === "https:" && match ? match[1].toLowerCase() : null;
  } catch {
    return null;
  }
}

export function buildAccountIdentity(requestId) {
  if (!UUID.test(requestId)) throw new Error("Invalid operator request id");
  const compactId = requestId.replaceAll("-", "").toLowerCase();
  return {
    username: `release-op-${compactId.slice(0, 8)}`,
    displayName: ACCOUNT_DISPLAY_NAME,
    authEmail: `opentrack360-release-operator-${requestId.toLowerCase()}@opentrack360.invalid`,
    userMetadata: {
      opentrack_username: `release-op-${compactId.slice(0, 8)}`,
      opentrack_operator_request_id: requestId.toLowerCase(),
    },
  };
}

export function buildPromotionSql({ requestId, operatorSubjectId, targetUserId, username, displayName, projectRef }) {
  for (const value of [requestId, operatorSubjectId, targetUserId]) {
    if (!UUID.test(value ?? "")) throw new Error("Invalid operator provisioning identifier");
  }
  if (projectRef !== PROJECT_REF) throw new Error("Project reference does not match the configured production project");
  if (!/^release-op-[0-9a-f]{8}$/.test(username ?? "") || displayName !== ACCOUNT_DISPLAY_NAME) throw new Error("Operator account name is outside the dedicated release scope");
  const literal = (value) => `'${String(value).replaceAll("'", "''")}'`;
  return [
    "select private.opentrack_operator_provision_admin(",
    `  ${literal(requestId)}::uuid,`,
    `  ${literal(operatorSubjectId)}::uuid,`,
    `  ${literal(targetUserId)}::uuid,`,
    `  ${literal(username)},`,
    `  ${literal(displayName)},`,
    `  ${literal(projectRef)}`,
    ") as result;",
  ].join("\n");
}

export function extractJsonObject(output) {
  const start = output.indexOf("{");
  if (start < 0) throw new Error("Supabase CLI did not return JSON");
  let depth = 0;
  let quoted = false;
  let escaped = false;
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
    else if (char === "}") {
      depth -= 1;
      if (depth === 0) return JSON.parse(output.slice(start, index + 1));
    }
  }
  throw new Error("Supabase CLI returned incomplete JSON");
}

function quotePowerShell(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function supabaseCli(args) {
  const fullArgs = ["--yes", "supabase@3.4.0", ...args];
  if (process.platform === "win32") {
    const command = `& npx ${fullArgs.map(quotePowerShell).join(" ")}`;
    return execFileSync("powershell.exe", ["-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command], {
      cwd: REPO_ROOT,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
      maxBuffer: 4 * 1024 * 1024,
    });
  }
  return execFileSync("npx", fullArgs, {
    cwd: REPO_ROOT,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    maxBuffer: 4 * 1024 * 1024,
  });
}

function verifyManagementContext() {
  const who = extractJsonObject(supabaseCli(["whoami", "--output-format", "json"]));
  if (!UUID.test(who.id ?? "")) throw new Error("Supabase CLI profile has no verified operator subject id");

  const migrationStatus = extractJsonObject(supabaseCli(["migration", "list", "--remote", "--output-format", "json"]));
  if (migrationStatus.target?.project_ref !== PROJECT_REF || migrationStatus.target?.environment !== "production") {
    throw new Error("Linked Supabase CLI project is not the expected production database");
  }

  const roleStatus = extractJsonObject(supabaseCli([
    "db", "query", "--remote", "--yes", "--output-format", "json",
    "select current_user as db_role, session_user as login_role, current_database() as database_name",
  ]));
  const role = roleStatus.rows?.[0];
  if (role?.db_role !== "postgres" || role?.login_role !== "postgres" || role?.database_name !== "postgres") {
    throw new Error("Supabase CLI database channel is not authenticated as the project database owner");
  }
  return { operatorSubjectId: who.id, databaseRole: role.db_role };
}

function resolveHandoffDirectory() {
  const localAppData = process.env.LOCALAPPDATA || path.join(os.homedir(), "AppData", "Local");
  const directory = path.resolve(localAppData, "OpenTrack360", HANDOFF_DIRECTORY_NAME);
  const relative = path.relative(REPO_ROOT, directory);
  if (!relative || (!relative.startsWith("..") && !path.isAbsolute(relative))) throw new Error("Credential handoff directory must remain outside the repository");
  return directory;
}

function applyPrivateDirectoryAcl(directory) {
  if (process.platform !== "win32") {
    fs.chmodSync(directory, 0o700);
    return;
  }
  const account = process.env.USERDOMAIN ? `${process.env.USERDOMAIN}\\${process.env.USERNAME}` : process.env.USERNAME;
  if (!account) throw new Error("Current Windows account could not be determined for private handoff permissions");
  execFileSync("icacls.exe", [
    directory,
    "/inheritance:r",
    "/grant:r", `${account}:(OI)(CI)F`,
    "/grant:r", "SYSTEM:(OI)(CI)F",
    "/grant:r", "BUILTIN\\Administrators:(OI)(CI)F",
  ], { stdio: "ignore" });
}

function secureWriteJson(filePath, value, { exclusive = false } = {}) {
  const tempPath = `${filePath}.${randomUUID()}.tmp`;
  const data = `${JSON.stringify(value, null, 2)}\n`;
  fs.writeFileSync(tempPath, data, { encoding: "utf8", mode: 0o600, flag: "wx" });
  if (process.platform !== "win32") fs.chmodSync(tempPath, 0o600);
  if (exclusive && fs.existsSync(filePath)) {
    fs.rmSync(tempPath, { force: true });
    throw new Error("Private handoff already exists; refusing to overwrite it");
  }
  fs.renameSync(tempPath, filePath);
}

function locateAuthUser(users, authEmail, requestId, username) {
  const user = users.find((entry) => entry.email?.toLowerCase() === authEmail.toLowerCase());
  if (!user) return null;
  if (user.user_metadata?.opentrack_operator_request_id !== requestId || user.user_metadata?.opentrack_username !== username) {
    throw new Error("A conflicting Auth identity exists for this operator request");
  }
  return user;
}

async function findAuthUser(admin, authEmail, requestId, username) {
  for (let page = 1; page <= 100; page += 1) {
    const { data, error } = await admin.auth.admin.listUsers({ page, perPage: 1000 });
    if (error) throw new Error("Supabase Auth Admin could not check the request-scoped identity");
    const match = locateAuthUser(data.users ?? [], authEmail, requestId, username);
    if (match) return match;
    if ((data.users ?? []).length < 1000) return null;
  }
  throw new Error("Supabase Auth user scan exceeded its safety limit");
}

async function ensureAuthUser(admin, pending) {
  const existing = await findAuthUser(admin, pending.authEmail, pending.requestId, pending.username);
  if (existing) {
    const { error } = await admin.auth.admin.updateUserById(existing.id, {
      password: pending.temporaryPassword,
      email_confirm: true,
      user_metadata: pending.userMetadata,
    });
    if (error) throw new Error("The request-scoped Auth identity could not be resumed safely");
    return existing.id;
  }

  const { data, error } = await admin.auth.admin.createUser({
    email: pending.authEmail,
    password: pending.temporaryPassword,
    email_confirm: true,
    user_metadata: pending.userMetadata,
  });
  if (!error && data.user?.id) return data.user.id;

  // Auth may commit before a network response is lost. Re-read only the exact
  // request-scoped email, verify its metadata, and resume with the same secret.
  const raced = await findAuthUser(admin, pending.authEmail, pending.requestId, pending.username);
  if (!raced) throw new Error("Supabase Auth Admin did not confirm creation; the private pending request is safe to retry");
  const { error: resumeError } = await admin.auth.admin.updateUserById(raced.id, {
    password: pending.temporaryPassword,
    email_confirm: true,
    user_metadata: pending.userMetadata,
  });
  if (resumeError) throw new Error("The request-scoped Auth identity could not be resumed safely");
  return raced.id;
}

function buildCliResult(output) {
  const document = extractJsonObject(output);
  const row = document.rows?.[0];
  const result = row?.result ?? row;
  if (result?.ok !== true || !UUID.test(result.accountId ?? "")) throw new Error("Database promotion did not return the expected operator account result");
  return result;
}

function usage() {
  return [
    "Usage:",
    "  node scripts/operator-provision-admin.mjs [--project-ref oxfcxucpzrvbifefnwvp]",
    "  node scripts/operator-provision-admin.mjs --execute --project-ref oxfcxucpzrvbifefnwvp --key-rotation-verified",
    "",
    "The default mode verifies only the Supabase CLI project and database-owner channel.",
    "Production creation requires the owner-confirmed key-rotation flag and writes credentials only to the private local handoff folder.",
  ].join("\n");
}

async function main(argv = process.argv.slice(2)) {
  const options = parseArguments(argv);
  if (options.help) {
    process.stdout.write(`${usage()}\n`);
    return;
  }

  const envPath = path.join(REPO_ROOT, ".env.local");
  const localEnv = parseLocalEnv(fs.readFileSync(envPath, "utf8"), options.execute
    ? ["SUPABASE_URL", "SUPABASE_SECRET_KEY"]
    : ["SUPABASE_URL"]);
  const supabaseUrl = localEnv.SUPABASE_URL;
  if (getProjectRefFromUrl(supabaseUrl ?? "") !== PROJECT_REF) throw new Error(".env.local does not target the configured production Supabase project");

  const management = verifyManagementContext();
  if (!options.execute) {
    process.stdout.write(`${JSON.stringify({ mode: "dry-run", projectVerified: true, databaseRoleVerified: management.databaseRole })}\n`);
    return;
  }
  if (!options.keyRotationVerified || options.projectRef !== PROJECT_REF) throw new Error("Production creation requires the owner-confirmed key-rotation flag and project reference");
  const secretKey = localEnv.SUPABASE_SECRET_KEY;
  if (!secretKey || !secretKey.startsWith("sb_secret_")) throw new Error("The rotated server-side Supabase secret key must be present in .env.local");

  const handoffDirectory = resolveHandoffDirectory();
  fs.mkdirSync(handoffDirectory, { recursive: true, mode: 0o700 });
  applyPrivateDirectoryAcl(handoffDirectory);
  const pendingPath = path.join(handoffDirectory, "operator-provisioning-pending.json");
  const credentialPath = path.join(handoffDirectory, "operator-credentials.json");
  if (fs.existsSync(credentialPath)) throw new Error("Private operator credentials already exist; refusing to create another administrator");

  let pending;
  if (fs.existsSync(pendingPath)) {
    pending = JSON.parse(fs.readFileSync(pendingPath, "utf8"));
    if (!UUID.test(pending.requestId ?? "") || pending.operatorSubjectId !== management.operatorSubjectId || pending.projectRef !== PROJECT_REF) {
      throw new Error("Existing private operator request does not match the verified management context");
    }
  } else {
    const requestId = randomUUID();
    const identity = buildAccountIdentity(requestId);
    pending = {
      status: "pending",
      requestId,
      operatorSubjectId: management.operatorSubjectId,
      projectRef: PROJECT_REF,
      username: identity.username,
      displayName: identity.displayName,
      authEmail: identity.authEmail,
      userMetadata: identity.userMetadata,
      temporaryPassword: randomBytes(32).toString("base64url"),
      createdAt: new Date().toISOString(),
    };
    secureWriteJson(pendingPath, pending, { exclusive: true });
  }

  const supabase = createSupabaseClient(supabaseUrl, secretKey);
  const targetUserId = await ensureAuthUser(supabase, pending);
  const sql = buildPromotionSql({
    requestId: pending.requestId,
    operatorSubjectId: management.operatorSubjectId,
    targetUserId,
    username: pending.username,
    displayName: pending.displayName,
    projectRef: PROJECT_REF,
  });
  const sqlPath = path.join(handoffDirectory, `operator-provision-${randomUUID()}.sql`);
  fs.writeFileSync(sqlPath, sql, { encoding: "utf8", mode: 0o600, flag: "wx" });
  try {
    const output = supabaseCli(["db", "query", "--remote", "--yes", "--file", sqlPath]);
    const result = buildCliResult(output);
    const handoff = {
      kind: "synthetic_platform_admin",
      username: pending.username,
      password: pending.temporaryPassword,
      mustRotatePassword: true,
      requestId: pending.requestId,
      createdAt: new Date().toISOString(),
      apiBaseUrl: "https://track.srishanth.me/api/v1",
    };
    secureWriteJson(credentialPath, handoff, { exclusive: true });
    fs.rmSync(pendingPath, { force: true });
    process.stdout.write(`${JSON.stringify({ status: result.idempotent ? "already_created" : "created", handoffPath: credentialPath, mustRotatePassword: true })}\n`);
  } finally {
    fs.rmSync(sqlPath, { force: true });
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  main().catch(() => {
    process.stderr.write("Operator provisioning failed. No secret values were printed; keep the private pending request and retry after resolving the owner-managed prerequisite.\n");
    process.exitCode = 1;
  });
}
