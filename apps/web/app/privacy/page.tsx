import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "Privacy",
  description: "How OpenTrack360 handles location sharing, private Circles, and trip links."
};

export default function PrivacyPage() {
  return (
    <main className="policy-page">
      <Link className="back-link" href="/">← OpenTrack360</Link>
      <p className="eyebrow"><span className="eyebrow-line" />Privacy, in plain language</p>
      <h1>Your location is shared for a reason, and for a while.</h1>
      <p className="policy-lede">OpenTrack360 is designed for private, invitation-only Circles and temporary guest trip links. The mobile app controls when location is collected and shared.</p>
      <section>
        <h2>Guest trip links</h2>
        <p>A guest link shows the latest location for one trip. Its secret is held after the URL fragment, so it is not sent in the initial web request. The viewer sends the secret to the OpenTrack360 API as an authorization header. Guests do not receive Circle membership, saved Places, or trip history.</p>
      </section>
      <section>
        <h2>Map tiles</h2>
        <p>The web and Android viewers use OpenFreeMap with MapLibre and display map attribution. Loading a map sends requests for the visible map area to the tile service and its CDN. OpenFreeMap does not provide geocoding or directions in this setup.</p>
      </section>
      <section>
        <h2>Account access</h2>
        <p>Member accounts are provisioned for private Circles. Public sign-up is disabled. Platform administrators manage accounts but do not receive access to member maps or location history through that role.</p>
      </section>
      <section>
        <h2>Retention and contact</h2>
        <p>The pilot retains Circle location history for 30 days, then removes it through scheduled cleanup. The current location is kept separately while an active account needs it. A guest viewer holds the last successful location only until its displayed validity time, for no more than 30 seconds, and clears it when the link ends or expires.</p>
        <p>Account deletion requests enter a service processing queue. Ask the organization operating your OpenTrack360 service for its contact route and the status of a request.</p>
      </section>
      <p className="policy-footnote">This page describes the pilot’s current technical behavior. The service operator should publish a support contact and any deployment-specific retention changes before inviting members.</p>
    </main>
  );
}
