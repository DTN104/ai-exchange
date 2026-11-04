# Journal and Replay Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for the preserved Native method, with TDD and one fresh final reviewer.

**Goal:** Implement milestone C as a local, durable event journal with offline state replay and integrity/hash verification.

**Architecture:** JournaledExchange owns a live Exchange and writes each planned batch before applying it. A separate read-only core Replayer validates and reduces recorded events without calling matching. One new journal file per run contains an initialization record, command transactions and an optional verified completion record; reads and explicit tail repair are offline operations.

**Tech Stack:** Java21 FileChannel/FileLock/MessageDigest; Jackson3 already managed/used by the repository; JUnit. Core retains only java.base.

**Spec:** [Approved MVP design, section5](../specs/2026-10-19-ai-exchange-design.md). Owner requested continued development after milestone B; preserve Native execution and feature-branch delivery. Base is matching-risk/PR5; do not merge it implicitly.

## Global Constraints

- Local UTF-8 NDJSON; schemaVersion1, engine matching-v1, strategy/prng metadata none (agent runtime is not implemented).
- Manifest includes runId, signed-long seed, canonical scenario hash, sorted endowments, symbol AIX and open-order limit. Run metadata/path are excluded from economic-state hash.
- Long fields are decimal JSON strings. Canonical JSON recursively sorts object keys and preserves array order. Every record carries a SHA-256 checksum of its canonical data; checksums are not authentication.
- Initialization is journal record0 (RunInitialized) and starts core counters at0. Each command record includes normalized command, outcome, nondecreasing simTimeNanos, contiguous command/event sequences and ordered events.
- Default/hard maximum 256MiB per file, configurable lower; maximum physical record8MiB. Reserve2048bytes for completion when admitting command records.
- File create is CREATE_NEW: never overwrite. Hold an exclusive writer lock. Offline reads use a shared lock; explicit recovery uses an exclusive lock. No reopening an existing run for writes or agent/scheduler resume in C.
- Append complete record plus LF, force(true), then apply to live state, then return. Any append/force or reducer failure fails the coordinator closed; no further commands. Successful input/command validation errors before admission do not poison the run.
- A clean close appends/forces a completion record with final state/event hashes. An unsealed valid prefix is replayable and identified as unsealed; it has no persisted completion hash to compare.
- Missing final LF is a torn-tail error. Recovery is an explicit method that validates every complete prefix record before truncating only that tail and forcing the file. Complete corruption, checksum/schema/version/sequence errors are never skipped or auto-repaired.
- Replay applies validated events, never calls Exchange.plan or reruns matching. Prefix replay captures a requested committed command boundary but validates the entire file, including the completion hashes if present.
- HTTP/OpenAPI behavior stays status-only; no new order-entry or replay endpoint in this library milestone.

## Review Focus

1. Persisted-before-applied ordering and uncertain IO failures: inject append failure and durable-write-then-failure; require unchanged live state, failed admission and replay of durable records.
2. Corrupt/untrusted bytes: strict UTF-8, duplicate/unknown JSON fields, versions, checksum/sequence gaps and semantically impossible events must reject without returning corrupted state.
3. Replay must not rematch: manually supplied valid batches and recorded mixed batches rebuild identical accounts, reserves, orders, priority and counters; malformed replay poisons only the isolated Replayer.
4. Torn tails and locking: default read never truncates; recovery cannot hide complete-prefix corruption or race an active writer; existing paths are untouched.
5. Limits/lifecycle/hash determinism: bounded records/files, completion reservation, close/idempotency, full/prefix hashes and runId/genesis-map-order independence.

## Files and interfaces

- Modify core Exchange: ENGINE_VERSION="matching-v1" and nested read-only Replayer from `Exchange.replayer(Map<Long,Endowment>, int)`, with `accept(ExchangeCommand,List<ExchangeEvent>)` and `snapshot()`; live opaque Plan/apply unchanged.
- Core ExchangeReplayTest: manual valid events, event metadata/semantic tampering, fail-stop behavior and equivalence to existing live execution.
- Journal RunManifest(runId, seed, traders, maxOpenOrders): immutable validated genesis.
- JournalCodec (package-private): strict canonical JSON and record/checksum/schema codecs; snapshotHash and event-digest encoding. Reuse managed tools.jackson.core:jackson-databind; no Jackson annotations/dependency in core.
- AppendSink (package-private IO seam) and JournalFile: exclusive-locked CREATE_NEW file, full writes and force; narrow seam supports deterministic IO-failure tests.
- JournaledExchange: `create(Path, RunManifest)` / overload maxBytes, `execute(command, simTimeNanos)`, `snapshot()`, `isFailed()`, `close()`; owns state and sink so callers cannot bypass persistence.
- JournalReplay: `read(Path)` / `read(Path,long throughCommandSequence)`, `recoverIncompleteTail(Path)` returning removed byte count; ReplayReport includes manifest, selected snapshot/stateHash/eventDigest, total journal commands/bytes and sealed status.
- Tests in modules/journal/src/test/java/io/aiexchange/journal: codec/schema/canonical, writer ordering/failures/limits and actual-file replay/corruption/repair/locking.
- Journal/core READMEs, root README, roadmap, contribution versioning note and verification record; mechanical status-page milestone copy and CI failure-report paths.

### Task 1: Isolated validated state replay

- [ ] Write failing core tests for manual accepted/rejected/cancel batches, matching-result replay equivalence, invalid event/command sequence, bad reservation/trade/maker/terminal events and poisoned replay state.
- [ ] Observe missing Replayer API failure; implement Replayer without altering the live Plan trust boundary.
- [ ] Validate recorded command correspondence, exact counter metadata and event transitions; use existing risk checks/reducer but never planPlace. Apply only to private isolated replay state, and fail-stop it on any malformed batch.
- [ ] Verify core tests and existing full reactor; commit.

### Task 2: Canonical format and append-only storage

- [ ] Add managed Jackson and JUnit to journal. Write failing round-trip/canonical/hash and real-file lock/create/limit tests.
- [ ] Implement immutable manifest, strict codec with duplicate-key/trailing-token/unknown-field rejection and bounded NDJSON, existing-file refusal, full write loop and force.
- [ ] Confirm all core event types, nullable business-invalid input fields and near-Long.MAX_VALUE values round-trip exactly; hash does not depend on map insertion order/runId/path.
- [ ] Verify journal+reactor tests; commit.

### Task 3: Durable coordinator, replay verifier and explicit repair

- [ ] Write failing tests for coordinator commit order, IO failure admission, saved-event replay, sealed/unsealed/prefix runs and tampering.
- [ ] Implement persist-before-apply execution and completion sealing. Check monotonic nonnegative simulation time before planning. Failure-injection seam remains package-private.
- [ ] Implement streaming bounded offline reader and full-file validation, prefix capture and completion-hash comparison. Implement exclusive-locked explicit tail truncation only after complete-prefix validation succeeds.
- [ ] Exercise mixed commands on a real file, a multi-buffer sweep record, durable-but-unapplied restart, checksum/schema/sequence/semantic damage, bad UTF-8/duplicate keys, torn LF, prefix corruption + tail, active writer locks, file caps and lifecycle boundaries.
- [ ] Verify full reactor and commit.

### Task 4: Review, docs and public delivery

- [ ] Document file format, limits, Java example, failure/uncertain-ack semantics, seal versus prefix, explicit recovery, version changes and OS-level durability limits.
- [ ] Update module/project status and CI failure artifacts; HTTP behavior unchanged. Run existing dashboard checks/build and real-server/signal smoke after moving the project.
- [ ] Fresh whole-branch read-only review; fix important findings with failing-first regression tests, then verify the suite.
- [ ] Push journal-replay and open a stacked PR targeting matching-risk while PR5 is open; attach it to chat and verify Linux CI. Do not merge either PR without integration authorization.
- [ ] Mark plan complete and record evidence; final scope excludes agent runtime, agent resume, real data, HTTP replay endpoints and full hardware power-loss testing.

## Self-review

The initialization/completion records provide versioned genesis and persisted hash comparison without computing a full state copy per command. A completion seal is optional after a crash, never fabricated on IO failure. Prefix replay validates the whole file so it cannot silently bypass later corruption. Explicit tail repair never opens a general append/resume API. This refines the already-approved section5 within the owner's continued-development authorization.
