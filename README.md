# AI Exchange

[![CI](https://github.com/DTN104/ai-exchange/actions/workflows/ci.yml/badge.svg)](https://github.com/DTN104/ai-exchange/actions/workflows/ci.yml)

An open-source market microstructure simulator built with Java and Next.js. The goal is a synthetic stock exchange with hundreds of strategy-driven traders, event sourcing and verifiable deterministic replay.

**Current status: the local CLI runs a synthetic AIX market with seeded traders.** The matching core supports price-time matching, GTC/IOC/cancel, reservations and settlement. The journal supports persist-before-apply records, checksums, offline state replay and deterministic reruns. The web application still shows connectivity only. See [the roadmap](docs/roadmap.md).

## Run the synthetic market

Requires Java 21. From the repository root:

```sh
./mvnw verify
mkdir -p data
./scripts/simulate.sh run scenarios/aix-demo.json 42 data/aix-demo.ndjson
./scripts/simulate.sh verify data/aix-demo.ndjson data/aix-demo-rerun.ndjson
```

The demo runs 300 traders for 10,000 virtual events. The first command prints the trade count, final state hash and event digest after checking state replay. The second reconstructs the scenario and seed from the journal and checks that a new run matches both hashes. Use a new output filename for each command. See the [simulation module](modules/simulation/README.md) and [synthetic profiles](scenarios/README.md).

## Start the web scaffold

Requires **Java 21** and **Node.js 22.23+ (22.x)** with npm. macOS ARM64 and Linux are supported by the scaffold. No global Maven, Docker, database, market feed, LLM key or paid service is needed. The first build downloads dependencies.

```sh
git clone https://github.com/DTN104/ai-exchange.git
cd ai-exchange
./mvnw verify
java -Xmx512m -jar apps/server/target/server-0.1.0.jar
```

In a second terminal, from the repository root:

```sh
cd apps/dashboard
npm ci
npm run dev
```

Open [the local dashboard](http://127.0.0.1:3000). Both processes bind to localhost. The dashboard reads the backend at port 8080; refresh the page after starting or stopping the backend. Use `npm run build` and `npm start` in place of `npm run dev` for a production build.

The 512 MiB Java heap cap limits the heap only. The headless simulator and the web server are separate entry points.

## Verify the project

From the repository root:

```sh
./mvnw verify
npm --prefix apps/dashboard ci
npm --prefix apps/dashboard test
npm --prefix apps/dashboard run lint
npm --prefix apps/dashboard run typecheck
npm --prefix apps/dashboard run api:lint
npm --prefix apps/dashboard run build
node scripts/smoke.mjs
node --test scripts/smoke-signals.test.mjs
```

Stop any existing local servers before the smoke test. It needs ports 3000 and 8080 free, starts the production servers, checks API responses and rendered page states, tests offline/recovery behavior, then stops its own processes. It never stops another process already using those ports. Java integration tests use random ports. Dashboard tests use Node's built-in test runner with TypeScript stripping and local HTTP fixtures.

## Repository layout

| Path | Responsibility | Implemented now |
| --- | --- | --- |
| `apps/server` | Spring Boot HTTP adapter | Health and service status |
| `apps/dashboard` | Next.js interface | Local connectivity page |
| `modules/exchange-core` | Matching, risk and state reducers | In-memory Java library; [rules and example](modules/exchange-core/README.md) |
| `modules/journal` | Event storage and replay | Locked journal, checksums, offline replay; [format and example](modules/journal/README.md) |
| `modules/simulation` | Virtual time and seeded strategies | Four trader strategies and a [headless CLI](modules/simulation/README.md) |
| `api/openapi.yaml` | Authoritative HTTP contract | Two read-only endpoints |
| `scenarios` | Synthetic experiment configurations | Versioned [300- and 500-trader profiles](scenarios/README.md) |

Core modules will remain independent of Spring, HTTP and databases. Dependency versions are pinned: Spring Boot 4.1.2, Maven 3.9.16 (Wrapper 3.3.4), Next.js 16.4.5, React 19.3.1 and TypeScript 5.9.3. Maven distribution downloads are verified against a pinned SHA-256 checksum, derived after checking the published SHA-512.

## API

- `GET http://127.0.0.1:8080/actuator/health` — application health; does not indicate simulation readiness.
- `GET http://127.0.0.1:8080/api/v1/status` — `{ "name": "AI Exchange", "stage": "scaffold", "simulationAvailable": false }`.

There is no run creation or order-entry endpoint yet. See [OpenAPI](api/openapi.yaml) for the implemented contract; future interfaces remain in the design document. The server is intended for local development and has no user authentication. Do not expose it to the public Internet.

## Design and research

- [MVP architecture](docs/superpowers/specs/2026-10-19-ai-exchange-design.md)
- [ABIDES, Bristol Stock Exchange and exchange-core: comparison and verified licenses](docs/research/2026-10-19-market-simulators.md)
- [Scaffold implementation plan](docs/superpowers/plans/2026-11-04-ai-exchange-scaffold.md)
- [Matching/risk implementation plan](docs/superpowers/plans/2026-11-04-matching-risk.md)
- [Journal/replay implementation plan](docs/superpowers/plans/2026-11-04-journal-replay.md)
- [Trader simulation implementation plan](docs/superpowers/plans/2026-11-12-trader-simulation.md)
- [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)

This is a synthetic-market engineering project. It does not reproduce HOSE rules, predict real stock prices or provide a real-money trading venue. Strategy-driven agents do not require machine learning or a paid model API.

## License

[Apache License 2.0](LICENSE). Research references and dependencies retain their own licenses. No code from ABIDES, BSE or exchange-core has been copied or ported into this scaffold.
