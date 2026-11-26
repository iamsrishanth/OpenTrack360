"use client";

import { useEffect, useState } from "react";
import styles from "./circle-invite-handoff.module.css";

type InviteState = "checking" | "ready" | "incomplete";
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{32,512}$/;

export default function CircleInviteHandoff() {
  const [state, setState] = useState<InviteState>("checking");
  const [appLink, setAppLink] = useState("");

  useEffect(() => {
    const token = window.location.hash.slice(1);
    const path = `${window.location.pathname}`;
    window.history.replaceState(window.history.state, "", path);

    if (!TOKEN_PATTERN.test(token)) {
      setState("incomplete");
      return;
    }

    // Keep the one-time credential only in component memory. It is never sent
    // to this page's server, an API, storage, analytics, or a query string.
    setAppLink(`${window.location.origin}/i#${token}`);
    setState("ready");
  }, []);

  return (
    <main className={styles.page}>
      <section className={styles.card} aria-live="polite">
        <a className={styles.brand} href="/" aria-label="OpenTrack360 home">
          <span className={styles.mark} aria-hidden="true">O</span>
          <span>OpenTrack<span className={styles.accent}>360</span></span>
        </a>
        <p className={styles.eyebrow}>PRIVATE CIRCLE INVITATION</p>
        <h1>{state === "incomplete" ? "This invite link is incomplete" : "You’ve been invited to a Circle"}</h1>

        {state === "checking" && <p className={styles.copy}>Checking the invitation link…</p>}
        {state === "incomplete" && (
          <p className={styles.copy}>Open the complete invitation link from the person who invited you. The link key is private and cannot be recovered from this page.</p>
        )}
        {state === "ready" && (
          <>
            <p className={styles.copy}>Circle invitations can be accepted in OpenTrack360 on Android. Continue in the app and sign in to the account that was invited.</p>
            <a className={styles.button} href={appLink}>
              Open in OpenTrack360 <span aria-hidden="true">↗</span>
            </a>
            <p className={styles.note}>This invitation link is private. Anyone who has it may attempt to join the Circle before it expires.</p>
          </>
        )}
        <a className={styles.homeLink} href="/">OpenTrack360 home</a>
      </section>
    </main>
  );
}
