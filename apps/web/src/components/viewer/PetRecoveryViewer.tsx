"use client";

import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import styles from "./pet-recovery.module.css";

type RecoveryCard = {
  petName: string;
  species: string | null;
  photoUrl?: string | null;
  ownerMessage: string;
};

type State = "loading" | "needs-secret" | "ready" | "invalid" | "expired" | "error";

const API_BASE = "/api/v1";

function validCard(value: unknown): value is RecoveryCard {
  if (!value || typeof value !== "object") return false;
  const card = value as Record<string, unknown>;
  return typeof card.petName === "string" &&
    (typeof card.species === "string" || card.species === null) &&
    (typeof card.ownerMessage === "string") &&
    (card.photoUrl === undefined || card.photoUrl === null || typeof card.photoUrl === "string");
}

export default function PetRecoveryViewer({ petId }: { petId: string }) {
  const [state, setState] = useState<State>("loading");
  const [card, setCard] = useState<RecoveryCard | null>(null);
  const [message, setMessage] = useState("");
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [sendError, setSendError] = useState(false);
  const credentialRef = useRef<{ petId: string; secret: string | null } | null>(null);

  if (typeof window !== "undefined" && credentialRef.current?.petId !== petId) {
    credentialRef.current = { petId, secret: window.location.hash.slice(1) || null };
  }

  useEffect(() => {
    let disposed = false;
    const controller = new AbortController();
    const secret = credentialRef.current?.petId === petId
      ? credentialRef.current.secret
      : window.location.hash.slice(1) || null;

    if (window.location.hash || window.location.search) {
      window.history.replaceState(window.history.state, "", window.location.pathname);
    }

    if (!secret) {
      setCard(null);
      setState("needs-secret");
      return () => {
        disposed = true;
        controller.abort();
      };
    }

    void fetch(`${API_BASE}/public/pets/${encodeURIComponent(petId)}`, {
      method: "GET",
      headers: { Authorization: `Bearer ${secret}` },
      cache: "no-store",
      credentials: "omit",
      referrerPolicy: "no-referrer",
      signal: controller.signal,
    }).then(async (response) => {
      if (disposed) return;
      if (response.status === 404) {
        setCard(null);
        setState("invalid");
        return;
      }
      if (response.status === 410) {
        setCard(null);
        setState("expired");
        return;
      }
      if (!response.ok) throw new Error("Recovery card request failed");
      const body: unknown = await response.json();
      if (!validCard(body)) throw new Error("Invalid recovery card response");
      setCard(body);
      setState("ready");
    }).catch(() => {
      if (disposed || controller.signal.aborted) return;
      setCard(null);
      setState("error");
    });

    return () => {
      disposed = true;
      controller.abort();
    };
  }, [petId]);

  async function submitSighting(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!card || !message.trim() || sending) return;
    const secret = credentialRef.current?.petId === petId ? credentialRef.current.secret : null;
    // Deliberately do not persist the QR secret. It lives only in this component.
    if (!secret) return;

    setSending(true);
    setSent(false);
    setSendError(false);
    try {
      const response = await fetch(`${API_BASE}/public/pets/${encodeURIComponent(petId)}/sightings`, {
        method: "POST",
        headers: { Authorization: `Bearer ${secret}`, "Content-Type": "application/json" },
        body: JSON.stringify({ message: message.trim() }),
        cache: "no-store",
        credentials: "omit",
        referrerPolicy: "no-referrer",
      });
      if (!response.ok) throw new Error("Sighting could not be sent");
      setSent(true);
      setMessage("");
    } catch {
      setSent(false);
      setSendError(true);
    } finally {
      setSending(false);
    }
  }

  const pageTitle = state === "ready" ? "Help get them home" : state === "loading" ? "Opening recovery card" : "Private recovery card";

  return (
    <main className={styles.page}>
      <div className={styles.shell}>
        <header className={styles.header}>
          <div className={styles.brand}>
            <span className={styles.brandMark} aria-hidden="true">O</span>
            <span>OpenTrack<span className={styles.brandAccent}>360</span></span>
          </div>
          <span className={styles.secureBadge}><span aria-hidden="true">⌑</span> Owner-approved details</span>
        </header>

        <section className={styles.content} aria-live="polite">
          <div className={styles.eyebrow}>SOFTWARE-ONLY PET TAG</div>
          <h1>{pageTitle}</h1>

          {state === "loading" && <div className={styles.panel}><span className={styles.spinner} aria-hidden="true" /><p>Checking this private recovery link…</p></div>}
          {state === "needs-secret" && <div className={styles.panel}><h2>This QR link is incomplete</h2><p>Scan the complete tag link or ask the pet’s owner for a fresh recovery QR. The pet profile is private without its access key.</p></div>}
          {state === "invalid" && <div className={styles.panel}><h2>Recovery details are unavailable</h2><p>This link may be incomplete, expired, or no longer shared. No pet profile was opened.</p></div>}
          {state === "expired" && <div className={styles.panel}><h2>This recovery link has ended</h2><p>The owner has closed access to this card. No pet details are available through this link now.</p></div>}
          {state === "error" && <div className={styles.panel}><h2>We can’t reach the owner’s card</h2><p>Check your connection and try scanning the tag again. The QR’s public ID alone never reveals profile details.</p></div>}

          {state === "ready" && card && (
            <>
              <article className={styles.petCard}>
                <div className={styles.photoWrap}>
                  {card.photoUrl && /^https:\/\//i.test(card.photoUrl) ? (
                    // The URL is a short-lived, owner-approved photo URL from the API.
                    // Suppress referrers when the browser requests that private image.
                    // eslint-disable-next-line @next/next/no-img-element
                    <img className={styles.photo} src={card.photoUrl} alt={`Photo of ${card.petName}`} referrerPolicy="no-referrer" />
                  ) : (
                    <div className={styles.photoPlaceholder} aria-hidden="true"><span>♡</span></div>
                  )}
                  <span className={styles.tagBadge}>RECOVERY TAG</span>
                </div>
                <div className={styles.petDetails}>
                  <span className={styles.petEyebrow}>A PET IS LOOKING FOR THEIR PEOPLE</span>
                  <h2>{card.petName}</h2>
                  {card.species && <p className={styles.species}>{card.species}</p>}
                  <div className={styles.ownerMessage}>
                    <span className={styles.messageMark} aria-hidden="true">“</span>
                    <p>{card.ownerMessage}</p>
                  </div>
                </div>
              </article>

              <section className={styles.sightingPanel}>
                <div className={styles.sightingHeading}>
                  <div>
                    <span className={styles.sectionEyebrow}>FOUND THEM?</span>
                    <h2>Send a kind note</h2>
                  </div>
                  <span className={styles.heartMark} aria-hidden="true">♡</span>
                </div>
                <p className={styles.sightingIntro}>Your note goes only to the owner’s chosen recipients. We won’t request your location or contact details.</p>
                <form onSubmit={submitSighting}>
                  <label className={styles.srOnly} htmlFor="sighting-message">A note for the pet’s owner</label>
                  <textarea
                    id="sighting-message"
                    value={message}
                    onChange={(event) => setMessage(event.target.value.slice(0, 1000))}
                    maxLength={1000}
                    placeholder="Share what you noticed, like a nearby landmark…"
                    required
                  />
                  <div className={styles.formFooter}>
                    <span>{message.length}/1000</span>
                    <button type="submit" disabled={sending || !message.trim()}>{sending ? "Sending…" : "Send note"}<span aria-hidden="true">↗</span></button>
                  </div>
                  {sent && <p className={styles.successMessage} role="status">Your note was sent to the owner’s selected recipients.</p>}
                  {sendError && <p className={styles.errorMessage} role="alert">We couldn’t send your note. Please check your connection and try again.</p>}
                  {!sent && message.trim() && !sending && state === "ready" && <span className={styles.formPrivacy}>No location is included with this message.</span>}
                </form>
              </section>
            </>
          )}
        </section>

        <footer className={styles.footer}>
          <span>OpenTrack360 · A little help goes a long way</span>
          <span>The card is visible only with its private QR access key.</span>
        </footer>
      </div>
    </main>
  );
}
