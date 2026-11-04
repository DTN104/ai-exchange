# Contributing

Contributions are welcome. Start with the [roadmap](docs/roadmap.md) and [approved design](docs/superpowers/specs/2026-10-19-ai-exchange-design.md), then open an issue describing a focused change before building a new subsystem.

1. Fork and clone the repository, then create a branch for your change.
2. Follow the README quickstart using Java 21 and Node.js 22.23+.
3. Keep matching, accounting and replay logic out of Spring controllers and UI code.
4. Add a small behavior-focused test for new logic. Monetary arithmetic, priority and replay invariants need explicit edge-case coverage as those modules are implemented.
5. Run the README checks, including the production smoke test with ports 3000 and 8080 free.
6. Open a pull request explaining the problem, resulting behavior and checks actually run. Identify unverified behavior clearly.

Keep generated data, secrets, local research caches and browser artifacts out of commits. Commit the npm lockfile when dependencies change. Avoid introducing services or paid APIs into the local development path.

## License provenance

Contributions are submitted under Apache-2.0. Confirm that you have permission to contribute your changes. When copying or translating third-party code, record its source path and exact commit, preserve its copyright/license notices, mark modifications where required and update THIRD_PARTY_NOTICES.md. Translating Python into Java does not remove upstream license obligations.

No matching or agent code from the research repositories has been reused in the scaffold. Discuss reuse before adding it so we can review compatibility and notices. Keep external data licenses separate from software licenses.

## Scope

The matching/risk core is implemented as a Java library, with immutable event plans and snapshots. The web application remains status-only. The journal library now persists event batches and verifies saved-event replay; trader strategies and market visualization are later milestones. Avoid presenting these libraries as a running market. Changes to the core must retain independent conservation/reservation checks and must not mutate committed state during planning.

Persistent schemaVersion and matching engine semantics are versioned. Changes to event tags/fields, canonical encoding or reducer rules require an explicit compatibility/version decision and tests for unsupported or older input. Never silently reinterpret an existing journal.
