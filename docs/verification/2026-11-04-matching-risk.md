# Matching and risk verification — 2026-11-04

Scope: milestone B, implemented as an in-memory Java 21 library for AIX. The HTTP application remains status-only; there is no running agent market or durable event journal.

## Evidence

- Test-first domain suite: compilation failed while the domain types were absent; implementation then passed 20 business cases.
- Five additional invariant/boundary tests pass. The seeded workload executes 5,000 mixed place/cancel commands across 8 accounts. After every command, independent BigInteger checks verify cash/share conservation, nonnegative balances, exact outstanding reservations, book membership, FIFO and an uncrossed committed book.
- Reversed genesis insertion order produces equal events and snapshots for every command in that workload. This is an in-memory determinism check, not saved-event replay or market calibration.
- Boundaries include price-improvement refunds, both maker sides, large checked values near Long.MAX_VALUE, IOC remainder release, self-trade after earlier third-party fills, duplicate/out-of-sequence IDs, unknown references and invalid genesis.
- Pure planning leaves snapshots unchanged. Stale/reused/foreign plans reject before mutation. Engine-issued plans and returned views/events are immutable; callers cannot construct arbitrary apply batches.
- Clean full Maven verify passes: 25 core tests plus 3 existing server tests.
- Existing dashboard: 9 tests, lint, typecheck and production build pass. OpenAPI remains unchanged and validates with the same two existing advisory warnings about missing 4xx responses on status operations.
- Real-server smoke (connected/offline/recovery) and 2 signal-cleanup regression tests pass with the new core jar on the server classpath.
- jdeps reports the production core jar depends only on java.base. No production dependency was added.

A fresh read-only reviewer inspected the whole branch, checked money arithmetic/priority/immutability and found no blocking findings or necessary minor follow-ups. It relied on existing test reports rather than duplicating the full suite.

## Decisions and scope limits

- Continue the owner-authorized milestone B using the already-selected Native workflow and a feature branch in the shared checkout. This avoids another authorization loop for the approved matching/risk rules; there is no isolated filesystem worktree.
- Use engine-issued opaque plans at a checked command revision. Replaying externally decoded saved events requires a separate validated boundary in milestone C; this version deliberately does not accept public arbitrary event batches.
- Current sequential order IDs are consumed on a current-ID placement rejection, but invalid/old/skipped IDs do not advance the allocator. This is an explicit internal API policy, documented with the example.
- Aggregate genesis cash and shares must each fit a long; this bounds subsequent balances and prevents settlement overflow without using BigInteger in production.
- Update only status-page milestone wording to reflect the completed core library; keep HTTP behavior and simulationAvailable=false because no market runtime is exposed.

Not assessed as completed: persistent replay/durability, HTTP order entry, concurrent access, agent scheduling, hundreds-of-traders performance and a trading dashboard. Single-thread confinement is a requirement, not a concurrency guarantee. Existing development-tool advisory notes in SECURITY.md still apply.

[Linux CI passed](https://github.com/DTN104/ai-exchange/actions/runs/41799435495) for implementation commit `96e62141e89954bc5666275edf2c6ec93c4cbe29`, including Java verify, dashboard checks, production smoke and signal regressions. The implementation is available in [PR #5](https://github.com/DTN104/ai-exchange/pull/5); later documentation commits have their own CI status there. No merge into main is included in this delivery.
