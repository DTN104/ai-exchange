# Matching and Risk Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for the preserved Native execution method; implement task-by-task with TDD and one fresh whole-branch review.

**Goal:** Complete milestone B as a tested Java library for one synthetic symbol, AIX: price-time matching, reservations, settlement and cancellation.

**Architecture:** A single-thread-confined Exchange plans immutable event batches against committed state, then applies an engine-issued batch. Planning never mutates the book or accounts. Persistence and replay verification belong to milestone C; the HTTP service and dashboard remain status-only.

**Tech Stack:** Java 21 collections, checked long arithmetic, JUnit managed by the existing Maven BOM. No new production dependencies.

**Spec:** [Approved MVP design, sections 2, 3 and 8](../specs/2026-10-19-ai-exchange-design.md). The owner requested execution of milestone B after its five-step scope was presented; continue the previously selected Native workflow.

## Global Constraints

- Core stays independent of Spring, HTTP, database, wall clock and randomness.
- AIX only; positive integer price ticks and quantity lots; one lot is one synthetic share; fees zero.
- LIMIT GTC and IOC, maker-price execution, price-time FIFO, partial fill and cancel; no amend, short or margin.
- Buy orders reserve limit-price notional; sell orders reserve shares; cancel/IOC remainder releases outstanding reservations.
- Self-trade prevention cancels incoming remainder at the first own contra order; never skips that maker.
- Genesis uses positive trader IDs, 1–500 accounts, nonnegative endowments, checked aggregate cash/shares at most Long.MAX_VALUE. These aggregate bounds keep settlement representable.
- Open-order limit defaults to 20,000, configurable from 1 through 20,000. A GTC that would exceed the final committed limit rejects atomically; IOC may still trade without needing a slot.
- Order IDs are engine-issued sequential counters starting at 1. Callers read nextOrderId from snapshot for PlaceOrder; an attempted current ID is consumed even on domain rejection; duplicate/invalid/out-of-sequence IDs are rejected without advancing the counter.
- Event and command counters use checked increments. Public apply accepts only an opaque batch issued by this engine at the current command sequence; stale, repeated or foreign batches fail before changing state.

## Review Focus

1. Overflow or rejected input must not leave partial financial/book mutations; test notional and genesis overflow, unknown/null fields, insufficient assets and final-slot rejection.
2. Buyer price improvement and partial resting-maker fills must release exactly the correct reserve; verify both maker sides, multiple levels and subsequent cancellation.
3. Self-trade after earlier third-party fills keeps those fills and cancels only the incoming remainder; exercise that path explicitly.
4. Determinism and immutable inspection must survive different genesis map insertion orders and caller attempts to mutate returned lists.
5. Planning/applying must not permit stale/foreign/reused batches or caller-fabricated events to modify state; batch construction remains private and views/events immutable.

## Files and interfaces

- `modules/exchange-core/src/main/java/io/aiexchange/core/ExchangeCommand.java`: sealed PlaceOrder(orderId, traderId, symbol, side, timeInForce, price, quantity) and CancelOrder(traderId, symbol, orderId); nested Side and TimeInForce enums.
- `ExchangeEvent.java`: immutable Event(sequence, commandSequence, payload), sealed payload records for accepted/rejected order, trade, rested order, canceled order, canceled incoming remainder and cancel rejection; nested reason enums.
- `ExchangeSnapshot.java`: immutable account/order/price-level views and commandSequence, eventSequence, nextOrderId.
- `Exchange.java`: `Exchange(Map<Long, Endowment>)`, overload with maxOpenOrders; `Plan plan(ExchangeCommand)`, `void apply(Plan)`, `Plan execute(ExchangeCommand)`, `ExchangeSnapshot snapshot()`. Endowment and opaque Plan nested here; Plan exposes only immutable events and sequence metadata.
- `modules/exchange-core/pom.xml`: test-scoped JUnit only.
- `modules/exchange-core/src/test/java/io/aiexchange/core/ExchangeTest.java`: concrete business cases and planning boundaries.
- `ExchangeInvariantTest.java`: seeded workload and independent BigInteger conservation/reserve checks.
- Module README, root README, CONTRIBUTING, roadmap and a milestone-B verification note: explain current capability and planned persistence/runtime.

## Task 1: Transactional matching and accounting

- [ ] Add JUnit test dependency. Write failing ExchangeTest cases before implementation: genesis, exact 10@100 sell / 6@105 buy example, FIFO at equal price, best-price before time, both-side maker pricing, multi-level fills, GTC/IOC, reserve/refund/cancel, wrong cancel owner/symbol, unknown trader, malformed order, duplicate/out-of-sequence IDs, overflow, self-trade and final open-order capacity.
- [ ] Run `./mvnw -pl modules/exchange-core test` and observe missing-domain compilation failure.
- [ ] Implement records and Exchange with TreeMap price levels and insertion-ordered maker ID sets. Plan by scanning existing makers without modifying them. Settlement applies the planned events once; a failed unexpected reducer operation fail-stops that engine instead of continuing on uncertain state.
- [ ] Plan events have contiguous global sequence numbers and one command sequence. Rejected business commands still produce events; genesis validation errors throw before an engine is created.
- [ ] Add and run tests for non-mutating plan, stale/reused/foreign batch rejection, immutable account/book/event views and all rejection paths leaving financial state unchanged.
- [ ] Run module tests and full `./mvnw verify`, inspect output, then commit the core and tests.

## Task 2: Independent invariants and reproducibility

- [ ] Write a deterministic workload of at least 5,000 mixed place/cancel commands against 8 accounts. After every command, recompute total cash/shares with BigInteger and check nonnegative available/reserved balances, exact reserves implied by all open orders, uncrossed book and one book entry per active order.
- [ ] Run the same workload against genesis maps with reversed insertion order; assert equal event lists and snapshots at every step. No file-journal/replay claim follows from this test.
- [ ] Test aggregate near-Long.MAX_VALUE balances and settlement, empty IOC book, full-book IOC and partial-fill self-trade before finishing.
- [ ] Run invariants and full reactor tests; preserve failure seeds/messages for reproducibility. Commit verification tests.

## Task 3: Review and delivery

- [ ] Document the Java API/example and its sequential-ID, single-thread confinement, plan/apply and open-order-limit policies. Root docs distinguish the implemented core library from the status-only web application.
- [ ] Confirm no consumer currently calls the new library: existing server responses, OpenAPI, dashboard and smoke tests retain their current behavior.
- [ ] Run full Maven verify; existing dashboard tests/lint/typecheck/build and real-server smoke plus signal-cleanup regression checks. Add core test reports to CI artifact collection.
- [ ] One fresh read-only reviewer examines the branch, money/priority boundaries and independent invariants. Reproduce and fix important findings with tests before publication; note minor deferred items.
- [ ] Commit verified changes, push the feature branch and create a reviewable GitHub pull request targeting main, with actual test evidence. Attach it to this chat. Do not merge the PR without the user's integration decision.
- [ ] Confirm CI on the pull request, working tree status and final scope: B complete as a library; journal/replay, agents, API order entry and market dashboard still planned.

## Self-review

This plan refines the already-approved matching/risk rules without introducing new server or product flows. The five review conditions are each assigned concrete tests. The IDs and atomic capacity policy above settle routine internal choices that were not specified in detail. Native execution is preserved from milestone A; no repeated authorization is requested for the work the owner just instructed us to perform.
