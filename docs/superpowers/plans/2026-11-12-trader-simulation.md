# Trader simulation implementation plan

> **For agentic workers:** Use superpowers:executing-plans to execute this plan and track completed steps.

**Goal.** Run a synthetic AIX market with 300 seeded traders by default and verify the resulting journal with independent state replay and deterministic rerun.

**Architecture.** A bounded priority queue on one owner thread delivers wake and order messages in virtual time. The simulation drives the existing persist-before-apply coordinator. A headless CLI provides a runnable entry point in the existing packaged server JAR.

**Stack.** Java 21, Maven Wrapper, managed Jackson 3, JUnit. No new runtime services.

**Spec.** [MVP design, sections 4, 5, 7 and 8](../specs/2026-10-19-ai-exchange-design.md).

## Global constraints

One AIX symbol, 300 traders by default, max 500, 20,000 open orders, 100,000 scheduled messages and 1,000,000 processed events. Keep Spring and HTTP outside the simulation module. Log every normalized exchange command through the journal. Keep schema 1 readable, and keep command/footer schemas and digests stable. Rerun the autonomous scenario with the recorded seed and configuration. Pause between bounded advance calls. Run the 300-trader 10,000-event demo and 500-trader 100,000-event profile. Full dashboard performance measurements belong to E.

## Review focus

- Equal-time messages keep insertion order across separate advance calls, and virtual time never regresses.
- Unknown or malformed scenario fields, overflow and resource caps are rejected visibly.
- No lookahead between wake and delayed order delivery. Canceled or filled quotes release reservations through the existing core.
- Schema 1 files remain readable and schema 2 genesis includes all resolved configuration and versions.
- A journal failure prevents further advance and leaves a replayable unsealed prefix. Fresh files use CREATE_NEW.

## Task 1 · Deterministic runtime primitives

- [x] Add red tests in `modules/simulation/src/test/java/io/aiexchange/simulation` for same-time FIFO, backwards time and queue overflow, pinned SplitMix64 draws, and strict scenario validation.
- [x] Implement package-private `VirtualScheduler<T>` and `AgentRandom`, and public `Scenario.load(Path)`, `Scenario.parameters()` and `Scenario.fromParameters(Map<String, Long>)` in `modules/simulation/src/main/java/io/aiexchange/simulation`. Add runnable `scenarios/aix-demo.json` and `scenarios/aix-500.json`.
- [x] Run `./mvnw -B -pl modules/simulation -am test` and commit the passing task.

## Task 2 · Journal manifest and simulation

- [x] Add red tests for v2 manifest round-trip, v1 compatibility, simulation close/replay equality, step partition invariance, quote and taker behavior, cap and fail-stop behavior.
- [x] Extend `RunManifest` with optional versioned simulation metadata and `JournalCodec` with a v2 genesis. Add next-ID and order-open access without full snapshot allocation through `Exchange` and `JournaledExchange` and add a fail-stop hook.
- [x] Implement `SimulationRun.create(Path, String, Scenario, long)`, `advance(long)`, `progress()`, `snapshot()` and `close()`. A wake observes the current trade prices and chooses a delayed intent. One delivery may cancel/replace both maker quotes or place one IOC order. Assign strategies round-robin by ascending trader ID. Pin all behavior in a strategy version. Track market price from committed trades only.
- [x] Run `./mvnw -B verify` and commit the passing task.

## Task 3 · Runnable demo and verification

- [x] Add red tests for `SimulationCli.execute(String[], PrintStream)` run and verify. Check an alternate output path, seed/versions, and mismatch reporting.
- [x] Implement CLI in simulation and add `scripts/simulate.sh` to invoke it from the existing Boot JAR. The run command compares the live final snapshot to offline replay. The verify command reconstructs the scenario from a sealed journal, runs it again and compares both hashes.
- [x] Document the quickstart, scenario and replay guarantees. Run the packaged CLI on 300 and 500 traders and record measured results and limits.

The pull request targets `journal-replay` and runs the Linux verification workflow.
