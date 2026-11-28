import type { Metadata } from "next";
import OAuthConsent from "./OAuthConsent";
import styles from "./oauth-consent.module.css";

export const dynamic = "force-dynamic";
export const metadata: Metadata = {
  title: "Sign in to OpenTrack360",
  robots: { index: false, follow: false, noarchive: true },
};

export default async function OAuthConsentPage({
  searchParams,
}: {
  searchParams: Promise<{ authorization_id?: string }>;
}) {
  const params = await searchParams;
  const authorizationId = typeof params.authorization_id === "string" ? params.authorization_id : "";

  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <a className={styles.brand} href="/" aria-label="OpenTrack360 home">
          <span className={styles.brandMark}>O</span>
          <span>OpenTrack<span className={styles.brandAccent}>360</span></span>
        </a>
        <span className={styles.headerLabel}>Secure sign in</span>
      </header>
      <OAuthConsent authorizationId={authorizationId} />
      <footer className={styles.footer}>OpenTrack360 · Private by design</footer>
    </main>
  );
}
