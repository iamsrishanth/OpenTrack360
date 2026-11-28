"use client";

import { FormEvent, useEffect, useState } from "react";
import styles from "./oauth-consent.module.css";

type SessionState = {
  username: string;
  mustRotatePassword: boolean;
};

type ConsentDetails = {
  clientName: string;
  clientUri: string;
  redirectUri: string;
  scopes: string[];
  username: string;
};

type ApiError = { error?: { code?: string; message?: string } };

async function readMessage(response: Response): Promise<string> {
  try {
    const body = await response.json() as ApiError;
    return body.error?.message ?? `Request failed (${response.status})`;
  } catch {
    return `Request failed (${response.status})`;
  }
}

async function postJson<T>(path: string, body: unknown): Promise<{ response: Response; data: T | null }> {
  const response = await fetch(path, {
    method: "POST",
    credentials: "same-origin",
    cache: "no-store",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  return { response, data: await response.json().catch(() => null) as T | null };
}

export default function OAuthConsent({ authorizationId }: { authorizationId: string }) {
  const [screen, setScreen] = useState<"loading" | "invalid" | "login" | "rotate" | "consent" | "redirecting" | "error">("loading");
  const [session, setSession] = useState<SessionState | null>(null);
  const [details, setDetails] = useState<ConsentDetails | null>(null);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!/^[A-Za-z0-9_-]{8,200}$/.test(authorizationId)) {
      setScreen("invalid");
      return;
    }
    window.history.replaceState(null, "", "/oauth/consent");
    void loadSession();
    // `authorizationId` is captured once, then removed from the browser address bar.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function loadAuthorization(username: string) {
    const { response, data } = await postJson<ConsentDetails & { status?: string; redirectUrl?: string; error?: { message?: string } }>(
      "/api/oauth/authorization",
      { authorizationId },
    );
    if (response.status === 401) {
      setSession(null);
      setScreen("login");
      return;
    }
    if (response.status === 428) {
      setSession({ username, mustRotatePassword: true });
      setScreen("rotate");
      return;
    }
    if (!response.ok || !data) throw new Error(await readMessage(response));
    if (data.status === "redirect" && data.redirectUrl) {
      setScreen("redirecting");
      window.location.replace(data.redirectUrl);
      return;
    }
    if (data.status !== "consent" || !data.clientName || !data.redirectUri || !Array.isArray(data.scopes)) {
      throw new Error("This sign-in request is not supported.");
    }
    setDetails(data);
    setSession({ username: data.username || username, mustRotatePassword: false });
    setScreen("consent");
  }

  async function loadSession() {
    try {
      const response = await fetch("/api/oauth/session", { credentials: "same-origin", cache: "no-store" });
      if (response.status === 401) {
        setScreen("login");
        return;
      }
      if (!response.ok) throw new Error(await readMessage(response));
      const data = await response.json() as { authenticated?: boolean; username?: string; mustRotatePassword?: boolean };
      if (data.authenticated !== true || !data.username) {
        setScreen("login");
        return;
      }
      const nextSession = { username: data.username, mustRotatePassword: data.mustRotatePassword === true };
      setSession(nextSession);
      if (nextSession.mustRotatePassword) setScreen("rotate");
      else await loadAuthorization(nextSession.username);
    } catch (reason) {
      setMessage(reason instanceof Error ? reason.message : "OpenTrack360 sign-in is unavailable.");
      setScreen("error");
    }
  }

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true); setMessage("");
    const form = new FormData(event.currentTarget);
    const username = String(form.get("username") ?? "");
    try {
      const { response, data } = await postJson<SessionState & { authenticated?: boolean; error?: { message?: string } }>(
        "/api/oauth/session",
        { username, password: form.get("password") },
      );
      if (!response.ok || !data) throw new Error(await readMessage(response));
      const nextSession = { username: data.username || username, mustRotatePassword: data.mustRotatePassword === true };
      setSession(nextSession);
      if (nextSession.mustRotatePassword) setScreen("rotate");
      else await loadAuthorization(nextSession.username);
    } catch (reason) {
      setMessage(reason instanceof Error ? reason.message : "Sign in could not be completed.");
    } finally {
      setBusy(false);
    }
  }

  async function rotatePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true); setMessage("");
    const formElement = event.currentTarget;
    const form = new FormData(event.currentTarget);
    const newPassword = String(form.get("newPassword") ?? "");
    if (newPassword !== String(form.get("confirmPassword") ?? "")) {
      setMessage("The new passwords do not match."); setBusy(false); return;
    }
    try {
      const { response } = await postJson("/api/oauth/password", {
        currentPassword: form.get("currentPassword"),
        newPassword,
      });
      if (!response.ok) throw new Error(await readMessage(response));
      setSession(null);
      setScreen("login");
      setMessage("Password changed. Sign in again with your new password to continue.");
      formElement.reset();
    } catch (reason) {
      setMessage(reason instanceof Error ? reason.message : "Password could not be changed.");
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    setBusy(true); setMessage("");
    try {
      const response = await fetch("/api/oauth/session", {
        method: "DELETE",
        credentials: "same-origin",
        cache: "no-store",
      });
      if (!response.ok) throw new Error(await readMessage(response));
      setSession(null); setDetails(null); setScreen("login");
    } catch (reason) {
      setMessage(reason instanceof Error ? reason.message : "Sign out could not be completed.");
    } finally {
      setBusy(false);
    }
  }

  async function decide(decision: "approve" | "deny") {
    setBusy(true); setMessage("");
    try {
      const { response, data } = await postJson<{ redirectUrl?: string; error?: { message?: string } }>(
        "/api/oauth/decision",
        { authorizationId, decision },
      );
      if (!response.ok || !data?.redirectUrl) throw new Error(await readMessage(response));
      setScreen("redirecting");
      window.location.replace(data.redirectUrl);
    } catch (reason) {
      setMessage(reason instanceof Error ? reason.message : "Your choice could not be saved.");
      setBusy(false);
    }
  }

  return (
    <section className={styles.card} aria-live="polite">
      <p className={styles.eyebrow}><span /> ACCOUNT ACCESS</p>
      {screen === "loading" && <div className={styles.state}><h1>Checking sign-in…</h1><p>Verifying this secure request.</p></div>}
      {screen === "invalid" && <div className={styles.state}><h1>Link unavailable</h1><p>This sign-in request is invalid or has expired. Return to OpenTrack360 and try again.</p></div>}
      {screen === "error" && <div className={styles.state}><h1>Sign-in unavailable</h1><p>{message || "Try again from OpenTrack360."}</p></div>}
      {screen === "redirecting" && <div className={styles.state}><h1>Returning to OpenTrack360</h1><p>Finishing sign-in securely…</p></div>}

      {screen === "login" && <>
        <div className={styles.heading}><h1>Sign in to continue</h1><p>Use the username and password provided for your OpenTrack360 account.</p></div>
        {message && <p className={styles.notice} role="status">{message}</p>}
        <form className={styles.form} onSubmit={signIn}>
          <label>Username<input name="username" autoComplete="username" autoCapitalize="none" autoCorrect="off" required /></label>
          <label>Password<input name="password" type="password" autoComplete="current-password" required /></label>
          <button className={styles.primary} disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
        </form>
      </>}

      {screen === "rotate" && <>
        <div className={styles.heading}><h1>Change your temporary password</h1><p>This required step protects your account before OpenTrack360 can sign you in.</p></div>
        {message && <p className={styles.notice} role="alert">{message}</p>}
        <form className={styles.form} onSubmit={rotatePassword}>
          <label>Current password<input name="currentPassword" type="password" autoComplete="current-password" required /></label>
          <label>New password<input name="newPassword" type="password" autoComplete="new-password" minLength={12} maxLength={128} required /></label>
          <label>Confirm new password<input name="confirmPassword" type="password" autoComplete="new-password" minLength={12} maxLength={128} required /></label>
          <button className={styles.primary} disabled={busy}>{busy ? "Changing password…" : "Change password"}</button>
        </form>
      </>}

      {screen === "consent" && details && <>
        <div className={styles.heading}><h1>Continue with OpenTrack360?</h1><p>Signed in as <strong>{session?.username}</strong></p></div>
        {message && <p className={styles.notice} role="alert">{message}</p>}
        <div className={styles.request}>
          <div className={styles.requestMark}>O<span>360</span></div>
          <div><strong>{details.clientName}</strong><span>OpenTrack360 first-party Android app</span></div>
        </div>
        <div className={styles.permission}>
          <p className={styles.permissionLabel}>SIGN-IN DETAILS</p>
          <p><strong>OpenID sign-in</strong><span>Confirm your OpenTrack360 account to the Android app.</span></p>
        </div>
        <p className={styles.callback}>After approval, sign-in returns to <code>{details.redirectUri}</code>.</p>
        <p className={styles.disclosure}>Approving gives the OpenTrack360 Android app access to the account features already enabled for you, including your Circles and location data under your sharing settings. This authorization does not share your location with new people or change your Circle permissions.</p>
        <div className={styles.actions}>
          <button className={styles.primary} onClick={() => void decide("approve")} disabled={busy}>{busy ? "Continuing…" : "Continue"}</button>
          <button className={styles.secondary} onClick={() => void decide("deny")} disabled={busy}>Cancel</button>
        </div>
        <div className={styles.accountFooter}><span>Not {session?.username}?</span><button type="button" onClick={() => void signOut()} disabled={busy}>Sign out</button></div>
      </>}
    </section>
  );
}
