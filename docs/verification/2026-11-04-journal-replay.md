# Journal and state replay verification — 2026-11-04

Scope: milestone C local Java libraries, stacked on matching-risk/PR5. The web application still serves status only.

## Evidence

- Core replay tests were written first and failed while Replayer was absent; all30 core tests now pass, including5 replay tests and1000 mixed recorded batches compared with live state.
- Codec/file tests were written first and failed while the implementation was absent. All17 journal tests now pass using actual temporary files plus a narrow package-private IO failure seam.
- Full clean Maven verify:50 Java tests (30core,17journal,3server), zero failures/errors.
- Existing dashboard9 tests, lint/typecheck/OpenAPI/production build pass from the new Work/Other project path. OpenAPI retains the existing two advisory warnings for status-operation4xx responses.
- Live-server connected/offline/recovery smoke and2 signal-cleanup regression tests pass. Total automated test count61, plus the smoke assertions.
- Real-file tests compare sealed replay with the committed snapshot and canonical state hash; verify a selected prefix while checking the entire file; exercise a sweep record spanning multiple input buffers; and compare state/event hashes across two250-command persisted runs with different run IDs.
- Corruption cases include checksum damage, bad UTF-8, duplicate keys, unknown/unsupported schema and engine versions, noncanonical numeric fields, event sequence gaps, impossible trade prices, time regression, forged outcomes, scenario hash mismatch and completion state/event hash mismatch.
- IO-failure tests establish append-before-apply: live state stays unchanged on failure; later commands reject. A durable-write-then-failure leaves an unsealed record that replay correctly reconstructs although it was never applied to the original live engine.
- Normal read never truncates a torn tail. Explicit repair validates the complete prefix, refuses complete corruption and truncates only the unterminated tail. Capacity, completion reservation, CREATE_NEW and active-writer exclusion are covered.

A fresh read-only reviewer found no blocking findings. It checked code, tests, format/commit ordering, failed replay isolation, prefix validation, repair ordering, limits and resource ownership without rerunning the full suite.

## Decisions and limits

1. Continue the previously approved MVP section5 under the owner's continued-development instruction, preserving Native execution and a feature branch in the moved checkout. PR5 is not implicitly merged.
2. RunInitialized is journal record0; existing core event counters start at0 and command/event sequences then remain contiguous. A healthy close writes an optional completion seal with final hashes. A crash prefix remains explicitly unsealed rather than being described as a completed verified run.
3. Externally decoded events reduce only on a separate validated Replayer. The live opaque Plan/apply trust boundary remains unchanged. Replay checks recorded transitions but does not call the matching planner to generate trades.
4. Prefix requests validate the entire file, so later corruption cannot be hidden by selecting an early boundary.
5. No existing-file append/resume or automatic tail repair is provided. Explicit recovery discards an unterminated record; continuing agent/scheduler/PRNG state is a later requirement.
6. Jackson3 is reused from the repository's managed dependencies. The matching core remains framework/JSON-library independent.

Deferred minor suggestions: add a subprocess lock regression (current lock tests use separate channels in one JVM); add record number/byte offset to corruption errors. Standard FileChannel locking is used, but interprocess behavior is not separately demonstrated by this test suite.

Not certified or implemented: hardware power-loss/directory-entry durability, authentication against deliberate hash recomputation, agent runtime/resume, a replay/order-entry HTTP API, hundreds-of-traders throughput or a market dashboard. force(true) is the Java/OS flush boundary, not a hardware survival claim.

Linux CI and the published PR are separate evidence sources; local success does not establish their state. No merge is included in this delivery.
