# Fresh-clone local runbook

The local stack uses manual processes. Do not run `brew services start ollama`, register launchd,
or enable VM login startup. Default fakes prove plumbing, not model quality.

## macOS arm64 prerequisites

```bash
brew install openjdk@25 podman docker docker-compose ollama uv
mkdir -p ~/.docker/cli-plugins
ln -s /opt/homebrew/lib/docker/cli-plugins/docker-compose ~/.docker/cli-plugins/docker-compose
podman machine init --cpus 2 --memory 3072 --disk-size 20 memos-local
podman machine start memos-local
```

If the Compose symlink or VM already exists, reuse it. Java and Docker environment changes are
scoped to the current terminal:

```bash
git clone https://github.com/Zhi-Xiang-Guo/memos.git
cd memos
# Until this evidence branch is merged:
git switch feat_evidence-gate
source scripts/local-env.sh
./scripts/preflight.sh
cp .env.example .env
docker compose up -d --wait postgres
./mvnw -B -ntp clean verify
(cd benchmark && uv sync --locked --python 3.14.7 && uv run ruff format --check . && uv run ruff check . && uv run pytest)
python3 scripts/check_markdown_links.py
./scripts/run-local.sh fake
```

On Linux or an existing Docker installation, set a Java 25 `JAVA_HOME` and use your existing
Docker daemon; `local-env.sh` only selects the named Podman VM if its socket exists. Maven always
uses the checked-in 3.9.16 wrapper. Podman disables Ryuk because its rootless socket may not support
the privileged reaper; normal Testcontainers teardown still runs. After an interrupted test,
inspect and remove only test containers, preserving the `memos-postgres` volume.

`run-local.sh` owns the two Java processes; Ctrl-C stops them. API readiness is on 8080 and worker
readiness on 8081. PostgreSQL is bound to loopback. Run the existing `smoke*.sh` scripts only when
these ports are free; each smoke owns its own API and worker.

## Real frozen models

In a separate terminal, start Ollama manually and keep this terminal open:

```bash
OLLAMA_HOST=127.0.0.1:11434 OLLAMA_NUM_PARALLEL=1 OLLAMA_MAX_LOADED_MODELS=1 ollama serve
ollama pull qwen3:4b
ollama pull qwen3-embedding:0.6b
```

The `serve` command is foreground: issue the two `pull` commands in another terminal. The 16 GiB
reference machine uses a 3 GiB VM and one loaded model at a time. Record actual OS, CPU/RAM,
Ollama/Java/Postgres/pgvector versions and swapping behavior for each run; these settings are not
an SLO recommendation. Downloads are resumable; keep their failure output.

```bash
./scripts/preflight.sh --models
./scripts/run-local.sh ollama-policy-v3
```

If a populated fake-model database refuses startup with `PROJECTION_RECONCILIATION_REQUIRED`, stop
all API/worker processes, then perform the maintenance operation below. Never delete the database
volume to make a model switch look successful.

## Explicit projection model change

Migrations run on ordinary startup. On an existing deployment V009 installs the reconciliation
function before the identity guard refuses incompatible state. With all API/worker processes
stopped, run a single operator-controlled database transaction:

```bash
docker compose exec -T postgres psql -U memos -d memos -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;
SET LOCAL lock_timeout = '10s';
SELECT memos.reconcile_projection(
  'sha256:ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d',
  1024, 'projection-v1', true);
COMMIT;
SQL
./scripts/run-local.sh ollama
```

This is a maintenance rebuild, not an online blue/green index swap. It atomically removes old
vector/FTS/checkpoint projections, cancels unfinished projection jobs, records a new append-only
identity generation and queues latest active lineages. Authority, retained versions, transitions,
usage and completed job evidence remain. Repeated same-identity calls are no-ops; this operation
repairs model/dimension/policy migration, not arbitrary same-generation index corruption.

Embedding happens after commit in the existing worker. Reads may have reduced/empty recall until
settlement; do not claim strong read-after-write or zero downtime. Roll back a model selection by
calling the same function with the previous model/dimensions/policy: it creates another generation
and rebuilds; it does not restore a stale cached index. A transaction failure restores the old
selection/projections. Non-1024 vectors lack the optimized partial index and need a measured index
migration before any performance claim.

The startup override `--memos.projection.allow-rebuild=true` exists for explicit maintenance only;
prefer the transaction above. Do not leave the override in environment files. Old worker claims
cannot publish across generations. Old writers with a different configured model/policy are
rejected by database guards. Source settlement and job failures remain observable.

## Optional existing Codex proxy for development

This machine already has an authenticated loopback proxy at `http://127.0.0.1:31415/v1`. Its token
is read at runtime from `~/.codex/codex-api-proxy-token`; never echo or commit it.

```bash
./scripts/run-local.sh codex
```

If this database has already run native Qwen embeddings, the Codex mode's deterministic development
embeddings have a different identity. After preserving the completed benchmark package and stopping
both Java processes, use the same explicit maintenance transaction with:

```sql
SELECT memos.reconcile_projection('deterministic-hashing-1024-v1', 1024, 'projection-v1', true);
```

Wrap it in `BEGIN`/`COMMIT` and set the lock timeout as in the model-change example. This creates
another generation; it does not alter original run artifacts or delete authority. Do not perform
this switch during a frozen campaign. The Codex development mode does not require Ollama, so its
manual terminal can be stopped after a completed campaign when no other local client needs it.

The `codex-proxy` adapter mode includes the schema in its system message because the existing
proxy does not forward `response_format`. It requires the distinct prompt identity
`candidate-extraction-v1-inline-schema`, keeps Java strict decoding, and leaves the frozen native
Ollama path unchanged. An initial exact-request probe produced valid JSON with missing candidate
fields; the failed raw synthetic response remains in `logs/proxy-extraction-probe.json`.
The adapter labels provenance `local-codex-proxy-dev-unpinned`, uses structured extraction through
the OpenAI-compatible path, and retains deterministic embeddings in this mode. This is a separate
development smoke, not the frozen Qwen four-baseline experiment. The adjusted mode passed real MemOS extraction, candidate materialization, projection and scoped
retrieval on this machine (one synthetic preference, not a quality evaluation). Its SDK context adds usage beyond the
short request, another reason it cannot replace the frozen benchmark model. The proxy's own retention and SDK/tool permissions are separate
from MemOS's database deletion boundary.

## Ordered benchmark execution

Use a clean committed checkout; preserve unrelated user files in the original workspace and use
a separate clean worktree for the runner if necessary. Start the real provider stack first. Run
`uv run memos-benchmark-run --help` and supply observed versions, then `--split dev --campaign-kind
SMOKE`. Keep every failed package. Analyze only dev; at most two root causes may be fixed before
freezing a new committed configuration. Formal testing uses the declared test repetitions and
`FROZEN_TEST` campaign mode as supported by the runner. Independently run `memos-benchmark-verify` with
the package path and dataset manifest. Result summaries remain NOT RUN until these steps succeed.

## Waku Agent consumer

With MemOS running in `ollama-policy-v3` mode, create a short-lived local token and start Waku from
its checkout. The token is the only source of tenant/user/agent scope; Waku never sends those
values in a body.

```bash
export WAKU_SEMANTIC_STORE=memos
export WAKU_MEMOS_BASE_URL=http://127.0.0.1:8080
export WAKU_MEMOS_TOKEN="$(python3 /path/to/memos/scripts/generate-dev-jwt.py \
  --tenant waku-local --user local-user --agent waku --subject waku \
  --role USER --role PROJECT_MEMORY_WRITER --lifetime-seconds 3600)"
cd /path/to/waku-agent
make run
```

Waku waits for extraction, authority and projection settlement before `add()` returns. Its update
path first proves the replacement source produced an accepted version, invalidates previously
active versions with the latest ETag, then waits until stale projection versions are no longer
selected. Delete uses MemOS self-service erasure, which hides the lineage in the request
transaction. Refresh the token after expiry. Neither Waku nor this runbook registers Ollama,
Podman, MemOS or Waku for login startup.

## Dev-repaired temporal configuration

The first dev run exposed two root causes; see [ADR 0007](adr/0007-dev-failure-freeze.md).
Keep v1 available for reproduction. To use the separately versioned repair, stop the old Java
processes and run:

```bash
./scripts/run-local.sh ollama-temporal-v2
```

The model identity is unchanged, so no projection reconciliation is needed for this prompt change.
From a clean committed checkout's `benchmark` directory, supply
`--dataset-manifest datasets/memos-assistant-smoke/v1/manifest-temporal-v2.json` to the runner and
verifier. Use a new run ID for dev and then the frozen test; never overwrite the original package.
All preprocessing, context-counting and answer calls use native Ollama. The existing Codex proxy
is excluded from these runs. Do not mix a v1 worker with a temporal-v2 manifest.

## Post-gate policy-v3 development configuration

Policy-v3 was created after the temporal-v2 frozen result and must not be used to rewrite or rerun
that inspected test campaign. It is the current local Waku integration mode:

```bash
./scripts/run-local.sh ollama-policy-v3
```

For development smoke and verifier commands, select
`benchmark/datasets/memos-assistant-smoke/v1/manifest-policy-v3.json` from a clean checkout and use
a new immutable run ID. Policy-v3 explicitly defines durable facts, noise, memory types,
sensitivity and canonical predicates for the selected small model. It also declares
`PROJECT_MEMORY_WRITER`; the server persists only capabilities derived from verified JWT roles.
Any future quality claim needs a newly frozen unseen evaluation contract.

## Stop

Ctrl-C the run-local and Ollama terminals. Then:

```bash
docker compose stop postgres
podman machine stop memos-local
brew services list
```

Do not use `docker compose down --volumes` on retained evidence. No login startup entry is needed.
