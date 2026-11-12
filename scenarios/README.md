# Synthetic scenarios

| File | Traders | Virtual events |
| --- | ---: | ---: |
| [aix-demo.json](aix-demo.json) | 300 | 10,000 |
| [aix-500.json](aix-500.json) | 500 | 100,000 |

Build once with `./mvnw verify`, then use `./scripts/simulate.sh run <scenario.json> <seed> <new-journal.ndjson>` from the repository root. See the [CLI quickstart](../modules/simulation/README.md).

Scenario schema 1 is a UTF-8 JSON object of up to 16 KiB. It requires the exact fields in the examples. Prices and thresholds are integer ticks, quantities and inventory are integer lots, and delays are integer nanoseconds. All traders receive the listed cash and inventory at genesis. Each trader uses the same configured order size. The initial price is also the value strategy's fixed fundamental price. Strategy allocation follows the trader ID, so the same population always has the same mix.

Limits are configurable up to 500 traders, 20,000 open orders, 100,000 pending scheduler messages, 1,000,000 processed simulation events and 256 MiB per journal. `maxScheduled` must be at least `agentCount`. Each delay is positive and at most 10¹² ns. A journal cap that cannot hold genesis and a seal fails before the writer opens. At runtime the cap can stop a run before its event limit. The journal records the resolved configuration, endowments, strategy and PRNG versions and master seed for rerun.

See the [M2 Pro measurements and replay digests](../docs/verification/2026-11-12-trader-simulation.md).
