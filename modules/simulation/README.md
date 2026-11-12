# Trader simulation

Run a local market from the repository root with Java 21:

```sh
./mvnw verify
mkdir -p data
./scripts/simulate.sh run scenarios/aix-demo.json 42 data/market.ndjson
./scripts/simulate.sh verify data/market.ndjson data/market-rerun.ndjson
```

The `run` command runs to the scenario's event limit and compares the final live snapshot with offline state replay. The `verify` command reads the sealed journal, restores the original configuration and seed, runs a fresh simulation, then compares the event digest, economic-state hash and full snapshot. It does not need the original JSON file. The output file must be new for each command. Use [the 500-trader profile](../../scenarios/README.md) for a larger run.

## Scheduler and strategies

The scheduler orders messages by virtual nanoseconds and then insertion sequence. Trader IDs start at 1 and rotate through market maker, noise, momentum and value. Each trader has an independent SplitMix64 stream, initialized by mixing `seed + 0x9e3779b97f4a7c15 * traderId` with 64-bit wraparound. The strategy rules are versioned as `aix-rules-v1` and the PRNG as `splitmix64-v1`.

Market makers wake at time zero and seed the book with buy and sell quotes. Other traders first wake after one configured interval plus seeded jitter shorter than the interval. Later wakes use the same interval and jitter after an arrival or a wake that generated no intent. A wake observes committed trade prices and any order intent arrives after the scenario latency. Strategies use the scenario's initial price until the first trade. The reference price moves only when the matching engine executes a trade.

| Trader | Behavior |
| --- | --- |
| Market maker | Quotes both sides around the observed reference price and replaces a quote when its price changes or the order is filled. |
| Noise | Draws a side from its PRNG and sends a limit IOC at up to three configured half-spreads from the reference. |
| Momentum | Sends a limit IOC in the direction of the latest trade-price move once it reaches the threshold. |
| Value | Sends a limit IOC toward the initial fundamental price once the deviation reaches the threshold. Its limit never crosses fair value. |

A wake and an arrival each count as a simulation event. A market-maker arrival can issue up to four exchange commands, and each command commits separately. All commands go through the matching and risk rules, including cancellations and business rejections. The runtime holds at most one pending message per trader.

## Java API and failure behavior

`SimulationRun.create(path, runId, scenario, seed)` opens a new journal. `advance(events)` processes at most that many messages and returns progress. Calling it in chunks preserves event ordering and the final outcome. `snapshot()` returns the committed exchange state. Keep calls on one owner thread and use try-with-resources.

A run stops at its configured event limit. Journal or scheduler failure stops admission and leaves any durable prefix available for offline replay. A healthy close seals the command stream so far, including when the caller stops early. CLI rerun compares the economic outcome to a fresh run through the full scenario limit. Journal replay reconstructs exchange state. Resuming agent, queue or PRNG state from an existing journal is outside the current runtime.

The Java module runs independently of Spring and HTTP. The shell entry point uses the existing packaged server JAR to load dependencies without starting the server. The web interface does not start or inspect runs yet.
