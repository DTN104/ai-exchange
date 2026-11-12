# Trader simulation verification

Measured on 12 November 2026 on a MacBook Pro (Mac14,9, Apple M2 Pro, 32 GiB RAM), macOS 26.6.2 arm64, Microsoft OpenJDK 21.0.10. The CLI used `-Xmx512m`, seed 42 and the checked-in scenario files. These are individual elapsed-time and peak resident-memory observations from macOS `/usr/bin/time -l`.

| Profile | Virtual events | Committed commands | Trades | Journal per run | Initial run + state replay | Peak RSS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 300 traders | 10,000 | 5,826 | 2,262 | 4,239,197 bytes (4.04 MiB) | 12.80 s | 380.4 MiB |
| 500 traders | 100,000 | 59,163 | 25,016 | 44,809,350 bytes (42.73 MiB) | 100.58 s | 415.3 MiB |

Both profiles passed offline replay and fresh deterministic rerun. The rerun compares the complete exchange snapshot, state hash and event digest. The 300-trader verification invocation took 10.60 s and 416.0 MiB peak RSS. The 500-trader verification on the final build took 99.82 s and 422.6 MiB peak RSS. Verification includes reading the source journal, writing a new run and replaying it. Elapsed time depends on the machine and file flushes. These measurements cover the headless CLI. Connected Spring Boot, SSE and dashboard measurements are part of milestone E.

| Profile | Event digest | Economic state hash |
| --- | --- | --- |
| 300 traders | `7434b15f835f9f668f43500ef135ecc4b6feda902137e0480ee877af3128f1b9` | `b98ecc98ea498cffdf2211a1a2ce9c1bd3e11a6f47cf4ee86cc75ebcd290bad5` |
| 500 traders | `c15e6d6b7831c6404ade3e50c1d6503aaaa6aaa8e6247641b85165cf00d749a1` | `151a962df62b2107d1f8e22fef056bbe50185ef91416d760001ea2407e72aa20` |

Reproduce from the repository root with fresh output filenames:

```sh
./mvnw verify
mkdir -p data
/usr/bin/time -l ./scripts/simulate.sh run scenarios/aix-demo.json 42 data/aix-300.ndjson
/usr/bin/time -l ./scripts/simulate.sh verify data/aix-300.ndjson data/aix-300-rerun.ndjson
/usr/bin/time -l ./scripts/simulate.sh run scenarios/aix-500.json 42 data/aix-500.ndjson
/usr/bin/time -l ./scripts/simulate.sh verify data/aix-500.ndjson data/aix-500-rerun.ndjson
```

An overlapping Maven repackage interrupted the first 500-trader verification attempt because the launcher was still reading the JAR. The retry used a stable package and a new journal. Run the build and CLI sequentially. CI also runs the packaged 300-trader profile and reruns it from the resulting journal.

Local validation included 72 Java tests, nine dashboard tests, two smoke shutdown tests, lint, type checking, production build and the real status-page smoke test. The unchanged OpenAPI contract passed lint with two existing warnings for health and status response coverage. Dashboard validation used standalone Node 22.23.3 after the Homebrew Node 22 binary failed to load its local simdjson library.

Independent review identified an open-order cap that was recorded but did not stop the run. The fix has a real-file regression test, and the packaged CLI returned exit code 1 with the cap message. Potential follow-up tests include a frozen pre-simulation journal fixture and dedicated checks for retaining and replacing quotes.
