# AI Exchange

[![CI](https://github.com/DTN104/ai-exchange/actions/workflows/ci.yml/badge.svg)](https://github.com/DTN104/ai-exchange/actions/workflows/ci.yml)

An open-source market microstructure simulator built with Java and Next.js. The goal is a synthetic stock exchange with hundreds of strategy-driven traders, event sourcing and verifiable deterministic replay.

**Current status: matching/risk Java library implemented; web application remains status-only.** The core supports price-time matching, GTC/IOC/cancel, balance reservations and settlement. The backend serves health and service status; the web page checks its connection. A running market with agents, durable journals and replay remains planned in [the roadmap](docs/roadmap.md).

## Run locally

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

The 512 MiB Java heap cap is a starting configuration, not a measured total-memory guarantee. The core has a deterministic 5,000-command correctness test; there is no hundreds-of-agents runtime or M2 performance benchmark yet.

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
| `modules/journal` | Event storage and replay | Module boundary only |
| `modules/simulation` | Virtual time and seeded strategies | Module boundary only |
| `api/openapi.yaml` | Authoritative HTTP contract | Two read-only endpoints |
| `scenarios` | Synthetic experiment configurations | Documentation only |

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
- [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)

This is a synthetic-market engineering project. It does not reproduce HOSE rules, predict real stock prices or provide a real-money trading venue. Strategy-driven agents do not require machine learning or a paid model API.

## License

[Apache License 2.0](LICENSE). Research references and dependencies retain their own licenses. No code from ABIDES, BSE or exchange-core has been copied or ported into this scaffold.
