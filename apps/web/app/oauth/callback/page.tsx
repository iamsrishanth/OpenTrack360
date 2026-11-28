import type { Metadata } from "next";

export const dynamic = "force-dynamic";
export const metadata: Metadata = {
  title: "Return to OpenTrack360",
  robots: { index: false, follow: false, noarchive: true },
};

export default function OAuthCallbackPage() {
  return (
    <main className="policy-page">
      <p className="eyebrow"><span className="eyebrow-line" /> SECURE SIGN IN</p>
      <h1>Return to the OpenTrack360 app</h1>
      <p className="policy-lede">Your sign-in is ready to finish in OpenTrack360. If the app did not open, go back to it and start sign-in again.</p>
      <p className="policy-footnote">This page does not display or store your authorization code.</p>
      <p><a className="back-link" href="/">OpenTrack360 home</a></p>
    </main>
  );
}
