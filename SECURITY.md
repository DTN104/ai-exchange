# Security

This early scaffold runs on localhost without authentication. It has no real funds, market-data credentials or trading account integration. Do not expose either server to the Internet. Public source code does not imply a hosted public service.

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/DTN104/ai-exchange/security/advisories/new) when it is available. If the private form is unavailable, open a public issue asking for a private reporting channel **without exploit details, credentials or personal data**. No support email address is currently published.

Only the current main branch is maintained. There is no response-time or production-support guarantee at this stage.

## Dependency checks

Before publishing the initial scaffold, `npm audit --omit=dev` reported no production dependency vulnerabilities. The full development dependency tree contains moderate reports for minimatch glob-processing denial of service (GHSA-g3hv-75wg-m249), through lint tooling. The compatible upstream versions have no safe automatic fix offered by npm; do not use a forced downgrade or incompatible override to hide the report. Run development tools against trusted source and review dependency-update pull requests. These reports are not a claim that the local status endpoint is exploitable through HTTP.

Audit results change over time. Re-run `npm audit` in apps/dashboard when reviewing dependency changes. CI validates builds and behavior; it does not certify the application as production-secure.
