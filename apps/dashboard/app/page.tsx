import { getBackendStatus } from "../lib/backend-status";

export const dynamic = "force-dynamic";

export default async function Home() {
  const status = await getBackendStatus();
  return (
    <main>
      <header><span className="wordmark">AI Exchange<span> / 001</span></span><span className="eyebrow">Open-source · Local development</span></header>
      <section className="intro" aria-labelledby="title">
        <p className="eyebrow">Market microstructure simulator</p>
        <h1 id="title">A market built<br />from the ground up.</h1>
        <p className="description">An experimental exchange where prices will emerge from the orders of simulated traders. The web interface checks the Java backend; the matching core is available as a Java library.</p>
      </section>
      <section className="connection" aria-labelledby="connection-title">
        <div><p className="eyebrow">Local backend</p><h2 id="connection-title"><span className={status ? "dot connected" : "dot"} aria-hidden="true" />{status ? "Backend connected" : "Backend unavailable"}</h2>
          <p>{status ? "The Spring Boot service is responding. Stage: scaffold." : "Start the Java server using the quickstart in the README, then refresh this page."}</p>
        </div>
        <form action="/" method="get"><button className="refresh" type="submit">Refresh status <span aria-hidden="true">↗</span></button></form>
      </section>
      <aside className="notice"><strong>Simulation not implemented yet.</strong> No traders, trades or order books are running in this scaffold.</aside>
      <section className="roadmap" aria-labelledby="next-title">
        <div className="section-heading"><h2 id="next-title">Development milestones</h2><span className="eyebrow">Milestones B — E</span></div>
        <ol>
          <li><span className="step">B</span><div><h3>Matching &amp; risk</h3><p>Core library ready; market runtime integration follows.</p></div></li>
          <li><span className="step">C</span><div><h3>Event journal &amp; replay</h3><p>Persist events and verify reconstructed state.</p></div></li>
          <li><span className="step">D</span><div><h3>Simulated traders</h3><p>Seeded strategies on a virtual-time scheduler.</p></div></li>
          <li><span className="step">E</span><div><h3>Market dashboard</h3><p>Real order books, trades and agent inspection.</p></div></li>
        </ol>
      </section>
      <footer><span>Apache-2.0 · Synthetic markets only</span><a href="https://github.com/DTN104/ai-exchange">Source &amp; quickstart ↗</a></footer>
    </main>
  );
}
