# Third-party notices

AI Exchange's original source is licensed under Apache-2.0. Dependency packages retain their respective licenses; consult the installed packages and Maven artifacts when redistributing them.

## Vendored tooling

`mvnw` and `mvnw.cmd` come from the Apache Maven Wrapper 3.3.4 only-script distribution, licensed under Apache-2.0. Their original license headers are preserved. Source artifact: https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper-distribution/3.3.4/maven-wrapper-distribution-3.3.4-only-script.zip . Maven source: https://github.com/apache/maven-wrapper . The repository's LICENSE includes Apache-2.0.

## Research references

ABIDES (BSD-3-Clause), Bristol Stock Exchange (MIT), and exchange-core (Apache-2.0) informed the proposed architecture. No source code from these three projects has been copied, translated or vendored into the scaffold. They are not runtime dependencies. See the [research report](docs/research/2026-10-19-market-simulators.md) for exact commits, license paths and academic citations.

The simulator implements SplitMix64 arithmetic with an AI Exchange-specific per-agent seed derivation. Algorithm reference: [Fast Splittable Pseudorandom Number Generators](https://doi.org/10.1145/2660193.2660195), Guy L. Steele Jr., Doug Lea and Christine H. Flood, OOPSLA 2014.

If code is later reused, add file-level source and modification details here and retain all applicable license, copyright and NOTICE material. The source projects and their authors do not endorse this project.
