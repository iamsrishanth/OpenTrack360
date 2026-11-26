"use client";

import { useEffect, useRef, useState } from "react";
import ShareMap from "./ShareMap";
import styles from "./share-viewer.module.css";

type PublicFix = {
  latitude: number;
  longitude: number;
  accuracyMeters: number;
  capturedAt: string;
  speedMps: number | null;
};

type PublicShare = {
  status: "waiting" | "active" | "signal_lost";
  serverTime: string;
  viewValidUntil: string;
  latest: PublicFix | null;
};

type LoadState = "loading" | "needs-secret" | "ready" | "invalid" | "ended" | "offline" | "error" | "stale";

const API_BASE = "/api/v1";
const DISPLAY_LEASE_MS = 30_000;
const REFRESH_MS = 10_000;
const SIGNAL_LOST_MS = 90_000;
const STALE_SPEED_MS = 30_000;

function validFix(value: unknown): value is PublicFix {
  if (!value || typeof value !== "object") return false;
  const fix = value as Record<string, unknown>;
  return (
    typeof fix.latitude === "number" && fix.latitude >= -90 && fix.latitude <= 90 &&
    typeof fix.longitude === "number" && fix.longitude >= -180 && fix.longitude <= 180 &&
    typeof fix.accuracyMeters === "number" && Number.isFinite(fix.accuracyMeters) &&
    typeof fix.capturedAt === "string" && Number.isFinite(Date.parse(fix.capturedAt)) &&
    (fix.speedMps === null || (typeof fix.speedMps === "number" && Number.isFinite(fix.speedMps)))
  );
}

function validShare(value: unknown): value is PublicShare {
  if (!value || typeof value !== "object") return false;
  const share = value as Record<string, unknown>;
  return (
    ["waiting", "active", "signal_lost"].includes(String(share.status)) &&
    typeof share.serverTime === "string" && Number.isFinite(Date.parse(share.serverTime)) &&
    typeof share.viewValidUntil === "string" && Number.isFinite(Date.parse(share.viewValidUntil)) &&
    (share.latest === null || validFix(share.latest))
  );
}

function formatLastUpdate(iso: string): string {
  return new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" }).format(new Date(iso));
}

export default function ShareViewer({ shareId }: { shareId: string }) {
  const [loadState, setLoadState] = useState<LoadState>("loading");
  const [share, setShare] = useState<PublicShare | null>(null);
  const [connectionNote, setConnectionNote] = useState<string | null>(null);
  const credentialRef = useRef<{ shareId: string; secret: string | null } | null>(null);

  // Capture during the first client render so development Strict Mode can
  // replay effects after the URL fragment has been removed.
  if (typeof window !== "undefined" && credentialRef.current?.shareId !== shareId) {
    credentialRef.current = { shareId, secret: window.location.hash.slice(1) || null };
  }

  useEffect(() => {
    let disposed = false;
    let pollTimer: ReturnType<typeof setTimeout> | undefined;
    let leaseTimer: ReturnType<typeof setTimeout> | undefined;
    let activeRequest: AbortController | undefined;
    let lastGood: PublicShare | null = null;
    let leaseEndsAt = 0;
    let failures = 0;

    const clearTimers = () => {
      if (pollTimer) clearTimeout(pollTimer);
      if (leaseTimer) clearTimeout(leaseTimer);
      pollTimer = undefined;
      leaseTimer = undefined;
    };

    const clearLocation = (next: LoadState) => {
      lastGood = null;
      leaseEndsAt = 0;
      setShare(null);
      setLoadState(next);
    };

    // The secret exists only in this effect's memory. Remove the fragment and
    // any incidental query values from the visible URL before making requests.
    const secret = credentialRef.current?.shareId === shareId
      ? credentialRef.current.secret
      : window.location.hash.slice(1) || null;
    if (window.location.hash || window.location.search) {
      window.history.replaceState(window.history.state, "", window.location.pathname);
    }

    if (!secret) {
      clearLocation("needs-secret");
      return () => {
        disposed = true;
      };
    }

    const schedule = (delay: number) => {
      if (disposed || document.visibilityState !== "visible") return;
      if (pollTimer) clearTimeout(pollTimer);
      pollTimer = setTimeout(() => void readShare(), Math.max(250, delay));
    };

    const expireDisplayLease = (delay: number) => {
      if (leaseTimer) clearTimeout(leaseTimer);
      leaseEndsAt = performance.now() + delay;
      leaseTimer = setTimeout(() => {
        if (disposed) return;
        clearLocation("stale");
        setConnectionNote("The last confirmed update has expired. Reconnecting will check the link again.");
      }, delay);
    };

    async function readShare() {
      if (disposed || document.visibilityState !== "visible") return;
      if (!navigator.onLine) {
        setLoadState("offline");
        setConnectionNote("You are offline. The location will clear when its display window ends.");
        schedule(Math.min(30_000, Math.max(1_000, leaseEndsAt - performance.now())));
        return;
      }

      activeRequest?.abort();
      const controller = new AbortController();
      activeRequest = controller;
      const requestStartedAt = performance.now();
      const requestTimeout = setTimeout(() => controller.abort(), 8_000);

      try {
        const response = await fetch(`${API_BASE}/public/shares/${encodeURIComponent(shareId)}`, {
          method: "GET",
          headers: { Authorization: `Bearer ${secret}` },
          cache: "no-store",
          credentials: "omit",
          referrerPolicy: "no-referrer",
          signal: controller.signal,
        });

        if (disposed) return;
        if (response.status === 404) {
          clearTimers();
          clearLocation("invalid");
          setConnectionNote(null);
          return;
        }
        if (response.status === 410) {
          clearTimers();
          clearLocation("ended");
          setConnectionNote(null);
          return;
        }
        if (!response.ok) throw new Error("Share request failed");

        const body: unknown = await response.json();
        if (disposed) return;
        if (!validShare(body)) throw new Error("Invalid share response");

        const serverNow = Date.parse(body.serverTime);
        const serverLeaseMs = Math.max(0, Math.min(DISPLAY_LEASE_MS, Date.parse(body.viewValidUntil) - serverNow));
        const requestElapsedMs = Math.max(0, performance.now() - requestStartedAt);
        const leaseMs = Math.max(0, serverLeaseMs - requestElapsedMs);
        const latestAge = body.latest ? Math.max(0, serverNow - Date.parse(body.latest.capturedAt)) : 0;
        const safeBody: PublicShare =
          body.status === "active" && body.latest && latestAge > SIGNAL_LOST_MS
            ? { ...body, status: "signal_lost", latest: null }
            : body;

        if (leaseMs <= 0) {
          clearLocation("stale");
          setConnectionNote("The last confirmed update has expired. Reconnecting will check the link again.");
        } else {
          lastGood = safeBody;
          setShare(safeBody);
          setLoadState("ready");
          setConnectionNote(null);
          expireDisplayLease(leaseMs);
        }

        failures = 0;
        schedule(REFRESH_MS);
      } catch {
        if (disposed || controller.signal.aborted && document.visibilityState !== "visible") return;
        failures += 1;
        setLoadState(navigator.onLine ? "error" : "offline");
        setConnectionNote(navigator.onLine ? "We could not refresh the location. Any displayed update clears when its timer ends." : "You are offline. The location will clear when its display window ends.");
        const backoff = Math.min(30_000, 2_500 * 2 ** Math.min(failures - 1, 4));
        const remainingLease = lastGood ? Math.max(1_000, leaseEndsAt - performance.now()) : 30_000;
        schedule(Math.min(backoff, remainingLease));
      } finally {
        clearTimeout(requestTimeout);
        if (activeRequest === controller) activeRequest = undefined;
      }
    }

    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") {
        // Revalidate before restoring a marker after a background interval.
        if (pollTimer) clearTimeout(pollTimer);
        if (leaseTimer) clearTimeout(leaseTimer);
        pollTimer = undefined;
        leaseTimer = undefined;
        clearLocation("loading");
        void readShare();
      } else {
        if (pollTimer) clearTimeout(pollTimer);
        pollTimer = undefined;
        activeRequest?.abort();
      }
    };

    const onOffline = () => {
      activeRequest?.abort();
      setLoadState("offline");
      setConnectionNote("You are offline. The location will clear when its display window ends.");
    };

    const onOnline = () => {
      clearTimers();
      clearLocation("loading");
      void readShare();
    };

    document.addEventListener("visibilitychange", onVisibilityChange);
    window.addEventListener("offline", onOffline);
    window.addEventListener("online", onOnline);
    void readShare();

    return () => {
      disposed = true;
      clearTimers();
      activeRequest?.abort();
      document.removeEventListener("visibilitychange", onVisibilityChange);
      window.removeEventListener("offline", onOffline);
      window.removeEventListener("online", onOnline);
    };
  }, [shareId]);

  const latest = share?.status === "active" ? share.latest : null;
  const ageMs = latest && share ? Math.max(0, Date.parse(share.serverTime) - Date.parse(latest.capturedAt)) : 0;
  const isStale = Boolean(latest && ageMs > STALE_SPEED_MS);
  const speedLabel = latest && !isStale && latest.speedMps !== null ? `${Math.round(latest.speedMps * 3.6)} km/h` : "Unavailable";
  const accuracyLabel = latest ? `±${Math.round(latest.accuracyMeters)} m` : "—";

  let heading = "Connecting to your trip";
  let description = "Checking this private link. The sender’s location will appear when their first update arrives.";
  if (loadState === "needs-secret") {
    heading = "This link is incomplete";
    description = "Open the complete link shared with you. Its private access key must be present after the # symbol.";
  } else if (loadState === "invalid") {
    heading = "This link could not be opened";
    description = "The link may be incomplete or unavailable. Ask the sender to share a fresh link.";
  } else if (loadState === "ended") {
    heading = "Trip sharing has ended";
    description = "The sender’s location is no longer available through this link.";
  } else if (loadState === "offline") {
    heading = latest ? "Connection interrupted" : "You’re offline";
    description = latest ? "Showing the last confirmed update only until its display timer ends." : "Reconnect to check this private trip link.";
  } else if (loadState === "error") {
    heading = latest ? "Update delayed" : "We can’t reach the trip right now";
    description = latest ? "The last confirmed update will clear when its display timer ends." : "Check your connection and try again in a moment.";
  } else if (loadState === "stale") {
    heading = "Location temporarily unavailable";
    description = "The display window for the last update has expired. We’ll check again when connected.";
  } else if (share?.status === "waiting") {
    heading = "Waiting for the first update";
    description = "The sender has started sharing. Their location will appear when their phone reports a fresh fix.";
  } else if (share?.status === "signal_lost") {
    heading = "Waiting for a new signal";
    description = "The sender’s phone hasn’t reported a fresh location. The last marker has been cleared.";
  } else if (isStale) {
    heading = "Update delayed";
    description = "The marker is from the last confirmed fix. Speed is hidden until a fresh update arrives.";
  } else if (latest) {
    heading = "Trip in progress";
    description = "This link shows only the sender’s latest shared location.";
  }

  return (
    <main className={styles.page}>
      <div className={styles.shell}>
        <header className={styles.header}>
          <div className={styles.brand}>
            <span className={styles.brandMark} aria-hidden="true">O</span>
            <span>OpenTrack<span className={styles.brandAccent}>360</span></span>
          </div>
          <span className={styles.privateBadge}><span aria-hidden="true" /> Private trip link</span>
        </header>

        <section className={styles.content} aria-live="polite">
          <div className={styles.intro}>
            <div className={styles.eyebrow}><span className={styles.liveDot} /> LIVE TRIP</div>
            <h1>{heading}</h1>
            <p>{description}</p>
          </div>

          {latest && share?.status === "active" ? (
            <div className={styles.mapPanel}>
              <ShareMap fix={latest} stale={isStale} />
              <div className={styles.mapTag}><span className={styles.pulseDot} /> {isStale ? "Last confirmed position" : "Latest position"}</div>
            </div>
          ) : (
            <div className={styles.emptyPanel} role="status">
              <div className={styles.emptyIcon} aria-hidden="true">
                {loadState === "ended" || loadState === "invalid" ? "↗" : "⌁"}
              </div>
              <div className={styles.emptyLine} />
              <div className={styles.emptyLineShort} />
              <span className={styles.emptyCaption}>{loadState === "loading" ? "Securing connection…" : "Map appears with the first location"}</span>
            </div>
          )}

          <div className={styles.infoGrid}>
            <div className={styles.metricCard}>
              <span className={styles.metricLabel}>CURRENT SPEED</span>
              <strong className={styles.metricValue}>{speedLabel}</strong>
            </div>
            <div className={styles.metricCard}>
              <span className={styles.metricLabel}>ACCURACY</span>
              <strong className={styles.metricValue}>{latest ? accuracyLabel : "—"}</strong>
            </div>
            <div className={styles.metricCard}>
              <span className={styles.metricLabel}>LAST UPDATE</span>
              <strong className={styles.metricValue}>{latest ? formatLastUpdate(latest.capturedAt) : "—"}</strong>
            </div>
          </div>

          {connectionNote && <p className={styles.connectionNote} role="status">{connectionNote}</p>}
        </section>

        <footer className={styles.footer}>
          <span>Shared with care · OpenTrack360</span>
          <span className={styles.mapNotice}>Map imagery by OpenFreeMap · © OpenStreetMap contributors. Tile requests reveal the map area being viewed.</span>
        </footer>
      </div>
    </main>
  );
}
