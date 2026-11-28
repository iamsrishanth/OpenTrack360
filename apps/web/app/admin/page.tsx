"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import styles from "./admin.module.css";

type Account = {
  accountId: string;
  username: string;
  displayName: string;
  status: string;
  mustRotatePassword: boolean;
  createdAt: string;
};
type AuditEvent = {
  id: number;
  actorUsername: string | null;
  targetAccountId: string | null;
  eventType: string;
  createdAt: string;
};
type ErrorBody = { error?: { message?: string } };

const API = "/api/v1/admin";

async function responseMessage(response: Response): Promise<string> {
  try {
    const body = await response.json() as ErrorBody;
    return body.error?.message ?? `Request failed (${response.status})`;
  } catch {
    return `Request failed (${response.status})`;
  }
}

export default function AdminPage() {
  const [csrfToken, setCsrfToken] = useState("");
  const [googleOAuthEnabled, setGoogleOAuthEnabled] = useState(false);
  const [authenticated, setAuthenticated] = useState(false);
  const [mustRotate, setMustRotate] = useState(false);
  const [username, setUsername] = useState("");
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [audit, setAudit] = useState<AuditEvent[]>([]);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [issuedPassword, setIssuedPassword] = useState("");

  const refreshCsrf = useCallback(async () => {
    const response = await fetch(`${API}/session`, { cache: "no-store", credentials: "same-origin" });
    if (!response.ok) throw new Error(await responseMessage(response));
    const data = await response.json() as { csrfToken?: string; googleOAuthEnabled?: boolean };
    if (!data.csrfToken) throw new Error("Session protection could not be initialized");
    setCsrfToken(data.csrfToken);
    setGoogleOAuthEnabled(data.googleOAuthEnabled === true);
    return data.csrfToken;
  }, []);

  const loadRecords = useCallback(async () => {
    const [accountResponse, auditResponse] = await Promise.all([
      fetch(`${API}/accounts?limit=100`, { cache: "no-store", credentials: "same-origin" }),
      fetch(`${API}/audit?limit=100`, { cache: "no-store", credentials: "same-origin" }),
    ]);
    if (accountResponse.status === 428) {
      setAuthenticated(true);
      setMustRotate(true);
      return;
    }
    if (accountResponse.status === 401) {
      setAuthenticated(false);
      setMustRotate(false);
      return;
    }
    if (!accountResponse.ok) throw new Error(await responseMessage(accountResponse));
    if (!auditResponse.ok) throw new Error(await responseMessage(auditResponse));
    const accountData = await accountResponse.json() as { data?: Account[] };
    const auditData = await auditResponse.json() as { data?: AuditEvent[] };
    setAccounts(accountData.data ?? []);
    setAudit(auditData.data ?? []);
    setAuthenticated(true);
    setMustRotate(false);
  }, []);

  useEffect(() => {
    const currentUrl = new URL(window.location.href);
    const authStatus = currentUrl.searchParams.get("auth");
    if (authStatus === "google-unavailable") setError("Google sign-in is not configured yet. Use the existing administrator sign-in for now.");
    else if (authStatus === "google-signin-failed") setError("Google sign-in failed or this account is not authorized for platform administration.");
    if (authStatus) {
      currentUrl.searchParams.delete("auth");
      window.history.replaceState(null, "", currentUrl.toString());
    }
  }, []);

  useEffect(() => {
    void (async () => {
      try {
        await refreshCsrf();
        await loadRecords();
      } catch (reason) {
        setError(reason instanceof Error ? reason.message : "Admin service unavailable");
      }
    })();
  }, [loadRecords, refreshCsrf]);

  async function mutate(path: string, method: string, body?: unknown): Promise<Response> {
    const token = csrfToken || await refreshCsrf();
    return fetch(`${API}${path}`, {
      method,
      credentials: "same-origin",
      cache: "no-store",
      headers: { "x-csrf-token": token, ...(body ? { "content-type": "application/json" } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
  }

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true); setError(""); setNotice("");
    const form = new FormData(event.currentTarget);
    try {
      const token = csrfToken || await refreshCsrf();
      const response = await fetch(`${API}/session`, {
        method: "POST", credentials: "same-origin", cache: "no-store",
        headers: { "content-type": "application/json", "x-csrf-token": token },
        body: JSON.stringify({ username: form.get("username"), password: form.get("password") }),
      });
      if (!response.ok) throw new Error(await responseMessage(response));
      const result = await response.json() as { username?: string; requiresPasswordRotation?: boolean };
      setUsername(result.username ?? String(form.get("username") ?? ""));
      setAuthenticated(true);
      setMustRotate(result.requiresPasswordRotation === true);
      if (!result.requiresPasswordRotation) await loadRecords();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Sign-in failed");
    } finally { setBusy(false); }
  }

  async function rotatePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(""); setNotice("");
    const form = new FormData(event.currentTarget);
    const currentPassword = String(form.get("currentPassword") ?? "");
    const newPassword = String(form.get("newPassword") ?? "");
    if (newPassword !== String(form.get("confirmPassword") ?? "")) {
      setError("The new passwords do not match."); setBusy(false); return;
    }
    try {
      const response = await mutate("/session/password", "POST", { currentPassword, newPassword });
      if (!response.ok) throw new Error(await responseMessage(response));
      setAuthenticated(false); setMustRotate(false);
      setNotice("Password changed. Sign in again with your new password.");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Password could not be changed");
    } finally { setBusy(false); }
  }

  async function provision(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(""); setNotice(""); setIssuedPassword("");
    const form = new FormData(event.currentTarget);
    try {
      const response = await mutate("/accounts", "POST", {
        username: form.get("username"), displayName: form.get("displayName"), requestId: crypto.randomUUID(),
      });
      if (!response.ok) throw new Error(await responseMessage(response));
      const result = await response.json() as { temporaryPassword?: string; username?: string };
      setIssuedPassword(result.temporaryPassword ?? "");
      setNotice(`Account ${result.username ?? ""} was created. Copy its temporary password now.`);
      event.currentTarget.reset();
      await loadRecords();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Account could not be created");
    } finally { setBusy(false); }
  }

  async function resetPassword(account: Account) {
    setBusy(true); setError(""); setNotice(""); setIssuedPassword("");
    try {
      const response = await mutate(`/accounts/${account.accountId}/reset`, "POST");
      if (!response.ok) throw new Error(await responseMessage(response));
      const result = await response.json() as { temporaryPassword?: string; username?: string; sharingStopped?: boolean; sessionsRevoked?: boolean };
      setIssuedPassword(result.temporaryPassword ?? "");
      const privacyNotice = result.sharingStopped && result.sessionsRevoked
        ? " All sessions, Circle sharing, guest links and active trips were stopped; the member must opt in again after password rotation."
        : "";
      setNotice(`Password reset for ${result.username ?? account.username}. Copy the temporary password now.${privacyNotice}`);
      await loadRecords();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Password reset failed");
    } finally { setBusy(false); }
  }

  async function disableAccount(account: Account) {
    setBusy(true); setError(""); setNotice(""); setIssuedPassword("");
    try {
      const response = await mutate(`/accounts/${account.accountId}/disable`, "POST");
      if (!response.ok) throw new Error(await responseMessage(response));
      setNotice(`Account ${account.username} was disabled and active sessions were invalidated.`);
      await loadRecords();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Account could not be disabled");
    } finally { setBusy(false); }
  }

  async function signOut() {
    setBusy(true); setError("");
    try {
      const response = await mutate("/session", "DELETE");
      if (!response.ok) throw new Error(await responseMessage(response));
      setAuthenticated(false); setMustRotate(false); setAccounts([]); setAudit([]); setUsername(""); setIssuedPassword("");
      setNotice("Signed out.");
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Sign-out failed"); }
    finally { setBusy(false); }
  }

  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <a className={styles.brand} href="/">OpenTrack<span>360</span></a>
        <div className={styles.headerRight}><span>Platform administration</span>{authenticated && <button className={styles.textButton} onClick={signOut} disabled={busy}>Sign out</button>}</div>
      </header>
      <section className={styles.content}>
        <div className={styles.heading}><p className={styles.eyebrow}>ACCOUNT OPERATIONS</p><h1>Admin console</h1><p>Provision member access and manage account recovery.</p></div>
        {error && <div className={styles.alertError} role="alert">{error}</div>}
        {notice && <div className={styles.alertSuccess} role="status">{notice}</div>}

        {!authenticated && <section className={styles.panel + " " + styles.narrow}>
          <h2>Administrator sign in</h2>
          <form className={styles.form} onSubmit={signIn}>
            <label>Username<input name="username" autoComplete="username" required /></label>
            <label>Password<input name="password" type="password" autoComplete="current-password" required /></label>
            <button className={styles.primary} disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
          </form>
          {googleOAuthEnabled && <>
            <div className={styles.loginDivider}><span>or</span></div>
            <a className={styles.googleButton} href="/api/v1/admin/google/start">Continue with Google</a>
          </>}
        </section>}

        {authenticated && mustRotate && <section className={styles.panel + " " + styles.narrow}>
          <div className={styles.required}><span>Required step</span><h2>Change the temporary password</h2><p>Choose a private password before opening account tools.</p></div>
          <form className={styles.form} onSubmit={rotatePassword}>
            <label>Current password<input name="currentPassword" type="password" autoComplete="current-password" required /></label>
            <label>New password<input name="newPassword" type="password" autoComplete="new-password" minLength={12} required /></label>
            <label>Confirm new password<input name="confirmPassword" type="password" autoComplete="new-password" minLength={12} required /></label>
            <button className={styles.primary} disabled={busy}>{busy ? "Changing…" : "Change password"}</button>
          </form>
        </section>}

        {authenticated && !mustRotate && <>
          <div className={styles.welcome}><span>Signed in as <strong>{username}</strong></span><button onClick={() => void loadRecords()} disabled={busy}>Refresh</button></div>
          {issuedPassword && <section className={styles.passwordCard}>
            <div><p className={styles.eyebrow}>TEMPORARY PASSWORD</p><code>{issuedPassword}</code><p>It is shown only in this response. Share it securely and ask the member to change it at first sign-in.</p></div>
            <button className={styles.secondary} onClick={() => void navigator.clipboard.writeText(issuedPassword)}>Copy</button>
          </section>}
          <div className={styles.columns}>
            <section className={styles.panel}>
              <div className={styles.panelTitle}><div><p className={styles.eyebrow}>NEW MEMBER</p><h2>Provision account</h2></div></div>
              <form className={styles.inlineForm} onSubmit={provision}>
                <label>Username<input name="username" autoComplete="off" minLength={3} maxLength={32} required /></label>
                <label>Display name<input name="displayName" maxLength={80} required /></label>
                <button className={styles.primary} disabled={busy}>{busy ? "Working…" : "Create account"}</button>
              </form>
              <p className={styles.help}>Usernames are unique and case-insensitive. The temporary password appears once after creation.</p>
            </section>
            <section className={styles.panel}>
              <div className={styles.panelTitle}><div><p className={styles.eyebrow}>MANAGED ACCESS</p><h2>Member accounts</h2></div><span className={styles.count}>{accounts.length}</span></div>
              <div className={styles.tableWrap}><table><thead><tr><th>Member</th><th>Status</th><th>Created</th><th>Actions</th></tr></thead><tbody>
                {accounts.map((account) => <tr key={account.accountId}>
                  <td><strong>{account.displayName}</strong><small>@{account.username}</small></td>
                  <td><span className={account.status === "active" ? styles.active : styles.disabled}>{account.status}</span>{account.mustRotatePassword && account.status === "active" && <small>Rotation required</small>}</td>
                  <td>{new Date(account.createdAt).toLocaleDateString()}</td>
                  <td className={styles.actions}>{account.status === "active" && <><button onClick={() => void resetPassword(account)} disabled={busy}>Reset</button><button className={styles.dangerText} onClick={() => void disableAccount(account)} disabled={busy}>Disable</button></>}</td>
                </tr>)}
                {accounts.length === 0 && <tr><td colSpan={4} className={styles.empty}>No provisioned accounts yet.</td></tr>}
              </tbody></table></div>
            </section>
          </div>
          <section className={styles.panel}>
            <div className={styles.panelTitle}><div><p className={styles.eyebrow}>TRACEABILITY</p><h2>Recent account events</h2></div></div>
            <div className={styles.tableWrap}><table><thead><tr><th>Event</th><th>Operator</th><th>Target account</th><th>Time</th></tr></thead><tbody>
              {audit.map((event) => <tr key={event.id}><td>{event.eventType.replaceAll(".", " ")}</td><td>{event.actorUsername ?? "System"}</td><td>{event.targetAccountId ?? "—"}</td><td>{new Date(event.createdAt).toLocaleString()}</td></tr>)}
              {audit.length === 0 && <tr><td colSpan={4} className={styles.empty}>No audit events yet.</td></tr>}
            </tbody></table></div>
          </section>
        </>}
      </section>
    </main>
  );
}
