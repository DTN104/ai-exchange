# Roadmap

| Milestone | Outcome | Status |
| --- | --- | --- |
| A — Scaffold | Java modules, status API, Next.js connectivity page, tests, CI, public repository | Implemented; see CI for verification |
| B — Matching and risk | Price-time priority, GTC/IOC/cancel, reservations, cash/inventory conservation, self-trade prevention | Implemented as a Java library; web order entry not exposed |
| C — Journal and replay | Durable event transactions, checksum/sequence validation, state reducer replay and hash verification | Planned |
| D — Trader simulation | Virtual-time scheduler, per-agent seed, noise/market-maker/momentum/value strategies, deterministic rerun | Planned |
| E — Market dashboard | Real books/trades, agent inspection, live projection updates, M2 resource measurements | Planned |

The [design](superpowers/specs/2026-10-19-ai-exchange-design.md) defines correctness and replay acceptance criteria. The hundreds-of-traders target has not been implemented or benchmarked. The core correctness workload uses 8 accounts and 5,000 commands; it is not a trader-runtime benchmark.

Start with one synthetic symbol and 300 agents, then test 500 agents after the runtime exists. Prove matching correctness and replay determinism before adding throughput optimizations. Resume-from-snapshot, HOSE auctions, real data, distributed infrastructure and LLM-generated strategies remain outside the initial MVP.
