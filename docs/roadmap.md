# Roadmap

| Milestone | Outcome | Status |
| --- | --- | --- |
| A — Scaffold | Java modules, status API, Next.js connectivity page, tests, CI, public repository | Implemented; see CI for verification |
| B — Matching and risk | Price-time priority, GTC/IOC/cancel, reservations, cash/inventory conservation, self-trade prevention | Implemented as a Java library; web order entry not exposed |
| C — Journal and replay | Durable event transactions, checksum/sequence validation, state reducer replay and hash verification | Implemented as a Java library; offline verification and explicit torn-tail repair |
| D — Trader simulation | Virtual-time scheduler, per-agent seed, noise/market-maker/momentum/value strategies, deterministic rerun | Implemented in the [headless CLI](../modules/simulation/README.md) |
| E — Market dashboard | Real books/trades, agent inspection, live projection updates, M2 resource measurements | Planned |

The [design](superpowers/specs/2026-10-19-ai-exchange-design.md) defines correctness and replay acceptance criteria. The CLI includes one synthetic symbol and [profiles for 300 and 500 traders](../scenarios/README.md). Milestone E adds the Spring Boot run controls, market projections and SSE, the Next.js market dashboard, and resource measurements of the connected applications.

Resume-from-snapshot, HOSE auctions, real data, distributed infrastructure and LLM-generated strategies remain outside the initial MVP.
