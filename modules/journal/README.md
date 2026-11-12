# Journal and state replay

Milestone C provides a Java21 local journal for the matching core. It uses FileChannel/FileLock and the repository's managed Jackson3 dependency; it has no Spring or database dependency.

## Create, execute, close, verify

```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import io.aiexchange.core.Exchange;
import io.aiexchange.core.ExchangeSnapshot;
import io.aiexchange.core.ExchangeCommand.*;
import io.aiexchange.journal.*;

Files.createDirectories(Path.of("data"));
var path = Path.of("data", "demo-001.ndjson"); // Choose a new path for every run.
var manifest = new RunManifest("demo-001", 42L, Map.of(
    1L, new Exchange.Endowment(1_000_000, 1_000),
    2L, new Exchange.Endowment(1_000_000, 1_000)), Exchange.MAX_OPEN_ORDERS);
ExchangeSnapshot expected;
try (var run = JournaledExchange.create(path, manifest)) {
    run.execute(new PlaceOrder(1, 1, "AIX", Side.SELL, TimeInForce.GTC, 100, 10), 1);
    run.execute(new PlaceOrder(2, 2, "AIX", Side.BUY, TimeInForce.GTC, 105, 6), 2);
    expected = run.snapshot();
}
var replay = JournalReplay.read(path);
if (!replay.sealed() || !replay.snapshot().equals(expected)
        || !replay.stateHash().equals(JournalReplay.stateHash(expected))) {
    throw new IllegalStateException("Replay verification failed");
}
```

This behavior is covered by real-file tests. From the repository root:

```sh
./mvnw -pl modules/journal -am test
```

The coordinator owns the live core so callers cannot bypass persistence through a mutable engine reference. All calls require a single owner thread. `execute` validates time, plans without mutation, encodes the complete batch, appends it plus LF, calls force(true), applies it, and only then returns the immutable events. Simulation time is a nonnegative, nondecreasing long supplied by the caller; there is no wall-clock scheduler here.

## Record formats

Each UTF-8 NDJSON physical line is an object with exactly `data` and `checksum`. SHA-256 covers the canonical JSON data: recursively sorted object keys, stable arrays and no whitespace. All long fields use canonical decimal strings, including balances, IDs, counters, seed and simulation time. Duplicate keys, unknown/missing fields, unsupported versions, trailing JSON and invalid UTF-8 reject.

| Record | Contents |
| --- | --- |
| RunInitialized, record0 | Schema/engine/strategy/PRNG versions, run ID, seed, scenario hash, AIX, open-order limit and sorted initial endowments. Core counters begin at0. |
| Command, record1..N | Normalized input, outcome, command sequence, simulation time, ordered versioned events with contiguous event sequence numbers. Business rejections are recorded. |
| RunCompleted, recordN+1 | Final counters/time, economic-state hash and event digest. Appended/forced only on a healthy close. |

The engine version is `matching-v1`. Schema 1 initialization represents command-driven runs, with strategy and PRNG versions set to `none`. Schema 2 initialization adds a `parameters` object of resolved simulation settings and records the strategy and PRNG versions. The scenario hash covers AIX, endowments, the open-order limit and any resolved parameters. Seed and run ID are separate metadata. Command and completion records retain schema 1 in both kinds of run. State replay can read either genesis format without running the strategies.

The state hash covers accounts, available/reserved assets, open orders, book levels/FIFO and counters. It excludes run ID, seed, path and wall-clock metadata. The event digest covers each canonical `{simTimeNanos, events}` followed by LF, in command order. Checksums and hashes detect corruption and inconsistent reconstruction; they do not authenticate against an attacker who can rewrite the file and recompute hashes.

## Offline replay and prefixes

Close the writer before replay. A reader takes a shared lock and the writer holds an exclusive lock. `JournalReplay.read(path)` streams and validates the file, reduces recorded events on an isolated core Replayer, and compares any completion hashes. It never calls the matching planner to regenerate trades.

`JournalReplay.read(path, commandSequence)` captures that committed prefix while still validating the **entire** file and any final completion proof. Boundary0 means genesis; Long.MAX_VALUE means the end. A boundary beyond the file fails. The report's snapshot/hash/eventDigest describe the selected prefix, journalCommands describes the full journal, and sealed describes the whole file's completion proof.

An unsealed valid prefix after a crash can be replayed, but `sealed()` is false: no persisted completion hash proves the run finished. A failure after a durable append but before live apply can leave a command in the file despite an error returned to its caller. Replay reconstructs that durable command. Do not blindly retry an uncertain command; there is no existing-run append/resume API here.

## Torn tails and explicit recovery

Default reads never modify a file. A final record without LF is rejected as a torn tail, even if its JSON happens to parse. Complete checksum/schema/sequence or event-transition corruption is never skipped.

`JournalReplay.recoverIncompleteTail(path)` is an explicitly mutating offline operation: it takes an exclusive lock, validates every complete prefix record, then truncates only the unterminated tail and forces the file. It returns the number of removed bytes. Invoke deliberately on a copy when investigating damaged data; it discards that incomplete record. If complete-prefix validation fails, nothing is truncated. An incomplete first initialization cannot be repaired into an empty valid run.

## Limits and durability boundary

- Default/hard maximum:256MiB per file; the create overload permits a lower positive cap. Oversized files reject on read before scanning.
- Maximum physical record:8MiB including LF. Streaming input and parser nesting limits bound individual record processing.
- Command admission reserves2048bytes for the completion record. Reaching the cap fails the run before changing committed state.
- CREATE_NEW never overwrites an existing path. Parent directories must already exist. No retention or automatic deletion is performed.
- Append/force, encoding/resource or reducer failure closes admission; `isFailed()` identifies that state. Failed runs do not fabricate a completion seal. Close is idempotent and releases owned resources.
- force(true) provides the Java/OS file-flush boundary; physical power-loss survival, directory-entry durability and storage-controller behavior are not certified by these tests.

See the [simulation module](../simulation/README.md) to rerun a sealed simulation journal with its original scenario and seed. Resuming scheduler and PRNG state, a replay HTTP endpoint and a market dashboard remain future work.
