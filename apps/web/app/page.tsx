import Image from "next/image";
import Link from "next/link";

const principles = [
  {
    title: "A link for one trip",
    body: "Share a live trip with someone who needs to know you arrived. The link ends when its time is up, the trip ends, or you stop it."
  },
  {
    title: "Your Circle stays yours",
    body: "Guests see the latest fix for that link. They never get your Circle, saved Places, or route history."
  },
  {
    title: "Built for invited groups",
    body: "A Circle is private. Members join with an invitation and a provisioned account; there is no public sign-up."
  }
];

export default function HomePage() {
  return (
    <main>
      <header className="site-header">
        <Link className="wordmark" href="/" aria-label="OpenTrack360 home">
          <span className="wordmark-mark" aria-hidden="true">O</span>
          <span>OpenTrack<span className="wordmark-number">360</span></span>
        </Link>
        <nav className="header-nav" aria-label="Main navigation">
          <a href="#how-it-works">How it works</a>
          <Link className="admin-link" href="/admin">Administrator sign in <span aria-hidden="true">↗</span></Link>
        </nav>
      </header>

      <section className="hero-shell" aria-labelledby="hero-title">
        <div className="hero-copy">
          <p className="eyebrow"><span className="eyebrow-line" />Stay close, share on your terms</p>
          <h1 id="hero-title">For the trip between here and <em>home.</em></h1>
          <p className="hero-lede">OpenTrack360 lets people you trust follow one live trip—without an account, an app install, or a view into your Circle.</p>
          <div className="hero-actions">
            <a className="button button-primary" href="#how-it-works">See how sharing works <span aria-hidden="true">↘</span></a>
            <span className="hero-note"><span className="state-dot" /> Links end when you choose</span>
          </div>
          <div className="hero-facts" aria-label="At a glance">
            <div><strong>One trip</strong><span>Not your whole route history</span></div>
            <div><strong>No guest account</strong><span>Open a link in any browser</span></div>
            <div><strong>Private Circles</strong><span>Invitation only, by design</span></div>
          </div>
        </div>

        <figure className="hero-art" aria-label="Abstract route map illustration">
          <Image
            src="/route-atlas.png"
            alt="An abstract coastal city map with a single route and destination marker"
            fill
            priority
            sizes="(max-width: 860px) 100vw, 52vw"
          />
          <figcaption><span className="map-legend-dot" /> One shared trip. One clear arrival.</figcaption>
        </figure>
      </section>

      <section className="how-section" id="how-it-works" aria-labelledby="how-title">
        <div className="section-intro">
          <p className="eyebrow"><span className="eyebrow-line" />A smaller kind of sharing</p>
          <h2 id="how-title">Only what the moment needs.</h2>
          <p>Movement can be useful to someone you trust. A trip link keeps that useful moment small and temporary.</p>
        </div>
        <div className="principle-list">
          {principles.map((principle) => (
            <article className="principle" key={principle.title}>
              <span className="principle-mark" aria-hidden="true"><span /></span>
              <div>
                <h3>{principle.title}</h3>
                <p>{principle.body}</p>
              </div>
              <span className="principle-arrow" aria-hidden="true">↗</span>
            </article>
          ))}
        </div>
      </section>

      <section className="closing-band" aria-label="OpenTrack360 privacy promise">
        <p>Location belongs to the people sharing it.</p>
        <span>OpenTrack360 · Made for private Circles</span>
      </section>

      <footer className="site-footer">
        <Link className="footer-wordmark" href="/">OpenTrack360</Link>
        <nav aria-label="Footer navigation">
          <Link href="/privacy">Privacy</Link>
          <Link href="/admin">Administrator sign in</Link>
        </nav>
        <span>© {new Date().getFullYear()} OpenTrack360</span>
      </footer>
    </main>
  );
}
