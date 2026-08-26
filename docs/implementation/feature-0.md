# Feature 0 — engineering foundation

Status: `DONE` on 2026-08-27. The local build, migration, integration, architecture, Python, documentation, and runtime smoke gates passed; the published GitHub workflow is configured, but GitHub had not produced a hosted run record at closure time.

## Pinned toolchain

| Component | Version / policy |
|---|---|
| Java target | OpenJDK 25 LTS; local/CI baseline 25.0.4.1 |
| Build | Maven Wrapper 3.3.4 running Maven 3.9.16; distribution and wrapper SHA-256 are pinned |
| Spring Boot | 4.1.1 |
| Flyway / PostgreSQL JDBC / Testcontainers | 12.4.0 / 42.7.13 / 2.0.5 from the Spring Boot BOM |
| Database image | PostgreSQL 18.6 + pgvector 0.8.6, pinned to a multi-architecture image digest |
| Java formatting | Spotless Maven 3.10.0 + google-java-format 1.36.1 |
| Benchmark Python | CPython 3.14.7, uv 0.12.6, pytest 9.1.1, Ruff 0.16.0 |

The CI actions are pinned to full commit SHAs and jobs receive only `contents: read`. Version upgrades must be explicit commits so migrations, tests, and image provenance are reviewed together.

## Module graph

```text
memory-domain
├── audit-observability
├── governance
│   ├── ingestion
│   ├── materialization
│   └── retrieval
│       └── context
└── adapters (implements ports; depends inward)
    ├── memos-api
    └── memos-worker
```

The actual build graph is declared by the module POMs. `architecture-tests` imports the built modules and checks cycles, framework-free domain code, adapter direction, and application separation. `memory-domain` contains no Spring, HTTP, JDBC, persistence annotation, or model-provider dependency.

## Runtime roles

- `memos-api` is the future business HTTP boundary on port `8080`.
- `memos-worker` is a separate executable artifact on port `8081`; Feature 0 exposes management endpoints only.
- `/livez` checks process liveness without a database dependency.
- `/readyz` includes database readiness and therefore fails closed during database or migration failure.
- Both artifacts run with deterministic fake extraction, embedding, and reranking adapters. No paid API or credential is required.

API errors use RFC 9457 `ProblemDetail` plus stable `code` and `traceId` properties. `X-Trace-Id` is returned to callers and placed in the logging MDC; request and memory bodies are not logged by the application.

## Persistence and migration

`compose.yaml` starts only the infrastructure needed by the MVP: the pinned pgvector image with a persistent PostgreSQL 18 volume mounted at `/var/lib/postgresql` and a `pg_isready` health check. `V001__bootstrap.sql` creates the `vector` extension and authoritative `memos` schema from an empty database. Flyway keeps its history in `public`; business objects remain in `memos`. The migration deliberately creates no speculative Feature 1 tables.

Flyway clean is disabled. The Testcontainers test migrates an empty database, validates migration metadata, and checks that pgvector 0.8.6 is installed. A production operator may need a DBA to install the extension before the application role runs migrations.

## Commands

```bash
cp .env.example .env
docker compose up -d --wait postgres
./mvnw -B -ntp clean verify
./scripts/smoke.sh
python3 scripts/check_markdown_links.py

cd benchmark
uv sync --locked --python 3.14.7
uv run ruff format --check .
uv run ruff check .
uv run pytest
```

The build retains thin JARs for architecture inspection and emits `-exec.jar` Spring Boot artifacts for runtime use. `scripts/smoke.sh` starts both executable artifacts, waits for liveness/readiness, and verifies that the worker does not expose a business memory route.

## Profiles and configuration

Configuration is environment-based and uses safe local defaults only. `.env.example` documents database URL/user/password, ports, and the image digest; `.env` is ignored. Production credentials must come from the deployment secret mechanism and must not be committed.

The current profiles are intentionally minimal: the artifact selects the API or worker role, while the same configuration keys work in local, test, and production environments. Later feature-specific provider profiles must preserve the deterministic fake as the test default.

## Private test deployment

The repository root now contains a multi-stage `Dockerfile` for the `memos-api` artifact. It builds with the checked-in Maven Wrapper on JDK 25 and copies only the executable JAR into a JRE 25 runtime image. JDK 21 is not usable for this repository because the parent POM compiles with `release 25` and enforces Java `[25,27)`. The container runs as an unprivileged user and exposes port `8080`; runtime database credentials remain external configuration.

For the private Windows-hosted Linux test VM, `scripts/deploy-to-winvm.sh` validates the application name and port, requires a root `Dockerfile`, excludes VCS/build/dependency directories, and streams the project to the pre-existing receiver over the `winvm` SSH host. It does not contain a password or change remote network policy.

```bash
./scripts/deploy-to-winvm.sh memos-api 18080
curl --fail --show-error http://windows-dev-vm:18080/livez
```

The remote receiver, container runtime, and health URL must be verified over Tailscale before this path is marked operational.

## Verification record

| Gate | State |
|---|---|
| Java compile + deterministic unit tests | `PASS` locally on the Java 25 bytecode target |
| Architecture boundary tests | `PASS` locally |
| Python lock, format, lint, unit tests | `PASS` locally |
| Markdown relative-file and anchor links | `PASS` locally |
| Testcontainers migration/pgvector | `PASS` locally against PostgreSQL 18.6 + pgvector 0.8.6 |
| Compose API/worker smoke | `PASS` locally against the Compose database |
| GitHub CI | `CONFIGURED / NOT RUN`; workflow is published, but GitHub exposed no hosted-run record at closure time |

The local Docker gate used Lima's rootless socket. In that setup Testcontainers also required `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/502/docker.sock`; ordinary Docker Desktop installations do not normally need that override.

No benchmark quality, latency, cost, scale, or production-readiness result is produced by Feature 0.
