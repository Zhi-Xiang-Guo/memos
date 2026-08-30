# Feature 6 - reproducible evaluation and evidence package

Status: `DOING`. The initial product workload and smoke contract were published through commit
`4120144` and
[GitHub Actions run #24](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33273671807). The
current v1 contract additionally pins Java extraction resources and forbidden-context labels; the
run-package/metrics core is `DONE / PUBLISHED` through commit `afcabe7` and
[GitHub Actions run #26](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33274839393). No
four-baseline result has been published yet. Provider and non-MemOS baseline primitives are
`DONE / PUBLISHED` through commit `367e0fa` and
[GitHub Actions run #28](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33276271008). The
source-level MemOS materialization status endpoint and bounded wait client are `DONE / PUBLISHED`
through commit `2bf7689` and
[GitHub Actions run #30](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33277348058).
The Java Ollama embedding and long-call lease-safety milestone is `DONE / PUBLISHED` through
commit `9225ed1` and
[GitHub Actions run #32](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33279370001).
The equal-budget Java context milestone is `DONE / PUBLISHED` through commit `c5035c3` and
[GitHub Actions run #34](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33280316053).
The unified-runner and exact provider-usage milestone is `DONE / PUBLISHED` through commit
`db213df` and
[GitHub Actions run #35](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33281737584).
The storage-observation and mechanical-report candidate is implemented and locally verified; its
PostgreSQL and publication gate remains pending remote CI.
Every selected-model result remains `NOT RUN`.

## Product workload

Feature 6 evaluates one bounded product scenario: a bilingual personal/project assistant that
must remember durable user preferences and project facts across sessions, preserve updates and
conflicts, retrieve only authorized evidence, abstain when evidence is missing or unresolved, and
treat external/tool memory as untrusted data.

This closes OQ-001 for the MVP evaluation boundary. It does not claim that the same configuration
generalizes to every agent or enterprise workload.

## Frozen local-model path

The credential-free real-model path uses an existing Ollama installation with explicit local
model IDs:

| Role | Model tag | Observed Ollama model ID |
|---|---|---|
| extraction, summary, answer | `qwen3:4b` | `359d7dd4bcdab3d86b87d73ac27966f4dbb9f5efdfcc75d34a8764a09474fae7` |
| embedding | `qwen3-embedding:0.6b` | `ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d` |

The first environment observation used Ollama `0.33.2`. A run remains ineligible if its manifest
does not record the actual version and IDs or if they differ from the selected experiment
configuration. A model tag alone is not treated as an immutable snapshot.

## Smoke dataset

`benchmark/datasets/memos-assistant-smoke/v1` is a MemOS-authored synthetic bilingual dataset. It
contains no real user records or credentials. Scenario families stay in one split, test scenario
IDs are frozen, questions are withheld from the ingestion/extraction path, and gold evidence may
not point past the declared question cutoff.

The manifest pins the cases, answer/summary prompts, Java extraction prompt/schema, complete CC BY
4.0 license, and attribution notice with SHA-256 over UTF-8 text after LF normalization. The
dataset license applies only to the files named in its notice; it does not imply a license for the
Java extraction resources or unrelated repository material.

The dataset covers simple and multi-session recall, preferences, updates, temporal questions,
unresolved contradiction, multi-hop linking, abstention, noise retention, bilingual retrieval,
and a write-to-use poisoning case. Idempotency/concurrency and privacy/deletion remain system
tracks exercised by the Java/PostgreSQL fault and smoke suites rather than being collapsed into an
answer-quality score.

## Baseline contract

The primary smoke comparison will run:

1. ordered full history with deterministic chronological truncation;
2. rolling session summary plus the declared recent-turn allowance;
3. raw-turn vector TopK with the same hard scope boundary;
4. the Java MemOS API with versioned lifecycle, hybrid retrieval, and context policy.

Every baseline receives the same answer prompt, answer model, evidence-token budget, question, and
deterministic output schema. Preprocessing calls and tokens remain attributable to the baseline.
The smoke run uses one repetition to validate harness integrity; a frozen model-dependent test
campaign requires three predeclared repetitions before producing a formal result.

## Evidence gate

A Feature 6 result is eligible only when the verifier confirms:

- clean committed revision and exact dataset/prompt hashes;
- actual model IDs, Ollama version, environment, seed, temperature, and budgets;
- one raw row for every expected baseline, question, and repetition;
- explicit failed/excluded rows rather than silent removal;
- attributable model calls, tokens, latency, retrieval evidence, and storage observations;
- metrics and Markdown tables regenerated from the raw artifact package;
- a baseline-parity report and no inspection-driven edit to the frozen test split.

Until that gate passes, `docs/benchmark/results.md` remains `NOT RUN`.

## Implemented run-package core

`memos_benchmark.artifacts` expands the frozen split into exactly one execution identity per
baseline, question, and repetition. It creates a run directory only when the target does not
exist, records a full Git SHA and dirty-worktree state, pins dataset/case/split membership hashes,
hashes the comparison configuration, and writes an integrity manifest over every raw and derived
artifact. A dirty worktree is recorded but remains ineligible.

The package verifier requires the exact file set and rejects missing, duplicated, or unexpected
execution rows. `FAILED` and `EXCLUDED` rows require a content-safe error class and remain in metric
denominators. `metrics.json`, `costs.json`, `storage.json`, `failures.md`, and `report.md` are
independently regenerated from the raw rows; updating a checksum cannot legitimize a hand-edited
derived result.

The deterministic metrics currently include:

- overall and per-track answer accuracy, with forbidden-answer leakage;
- abstention precision/recall/F1;
- Recall@K, complete recall, MRR, selected-evidence completeness, and context precision;
- forbidden-context exposure for explicitly labeled stale or hostile events;
- nearest-rank p50/p95/p99 total latency with sample counts;
- attributable input/output/embedding tokens and model calls by baseline.

Local-model monetary and energy cost remain `N/E`; the usage report emits `null`, not a fabricated
zero-dollar claim. The verifier now requires exact scenario-level preprocessing coverage and an
explicit usage object/completeness marker on every cost-bearing row. Storage readings and final
Markdown/table generation are now implemented locally but require remote PostgreSQL publication
verification before a smoke result is eligible.

Publication verification for this core passed Java 25, PostgreSQL/compose regression, a clean
Python 3.14.7 install with 28 tests, and the Markdown gate in run `#26`. These gates establish
implementation integrity only; they contain no model-dependent answer or retrieval score.

## Storage observation and mechanical report

Every baseline/scenario/repetition write row now carries an explicit storage completeness flag,
measurement method, retained bytes, byte components, and item counts. The three local baselines
do not pretend to be database measurements:

- full history measures canonical JSON UTF-8 bytes for all retained source events;
- rolling summary measures the final canonical summary plus the declared recent-turn state;
- raw-turn vector measures canonical event UTF-8 plus an explicit dense little-endian float32
  representation.

MemOS uses an operator-only `GET /v1/operations/storage` endpoint. It derives the exact
tenant/user/agent scope from the verified JWT, returns no content or identifiers, and reports a
fixed whitelist of relation row counts and `pg_column_size(record)` bytes. Deployment-wide
`pg_table_size`, `pg_indexes_size`, and total allocation are labeled separately. The runner first
requires its unique scope to contain zero rows, then records a post-settlement scope observation
and the database-native before/after delta. Page allocation can move in coarse steps, so that
delta is supplemental rather than presented as per-user physical storage.

`storage.json` aggregates only like-named representations and preserves every method label.
`report.md` mechanically renders run/dataset/git/model identity, the SMOKE/FROZEN_TEST disclaimer,
answer and temporal/contradiction accuracy, abstention F1, Recall@K/MRR, p95 latency with samples,
usage completeness/totals, storage methods, and failure/exclusion counts. `N/A` is used for
structurally inapplicable metrics and `N/E` for incomplete usage/storage observations. Both files
are integrity-hashed; the verifier regenerates and byte-compares them, so rehashing a manual edit
does not make it eligible.

Abstention F1 is `0.0` when a baseline misses every required abstention or produces only spurious
abstentions. It is `N/A` only when neither the frozen gold labels nor the baseline output contain
an abstention-positive case.

Local Java compilation, five focused API/security tests, Python format, lint, and all 65 Python
tests pass. The local Windows environment has no Docker, so Testcontainers stopped before the new
PostgreSQL query executed. Remote PostgreSQL and compose verification remain required. No row in
these deterministic tests is a benchmark storage or performance result.

## Provider and non-MemOS baseline primitives

The published Feature 6 implementation adds a bounded Ollama client that verifies the server
version, full model digests, declared capabilities, and structured chat/embedding response shapes.
It records wall latency, provider total/load duration, input/output/embedding tokens, and model
calls without retaining provider error bodies.

The candidate also implements the three non-MemOS context builders. Full history selects recent
complete source events and restores chronological rendering; rolling summary updates once per
session, rejects provenance outside the evidence observed at that point, and adds only the
declared recent-event allowance; raw-turn vector embeds source turns without importing MemOS
truth-state policy. Every final rendered context, including its envelope and separators, is
measured through the pinned embedding-model tokenizer before it is admitted under the shared
evidence budget. These are runner primitives and do not constitute an executed baseline result.

Publication verification passed 40 Python tests plus the Java 25, PostgreSQL/compose, Python, and
documentation gates in GitHub Actions run `#28`.

## Unified runner and exact usage

The published runner rejects dirty worktrees, verifies dataset/prompt hashes and full
Ollama digests, expands every expected identity, and uses one answer prompt/model/schema across
full history, rolling summary, raw-turn vector, and MemOS. Each MemOS scenario/repetition receives
a unique hard scope and short-lived `USER`+`OPERATOR` JWT. The runner ingests every source, waits
for source-to-authority-to-projection settlement, maps returned MemOS source UUIDs back to dataset
event IDs, and fails closed on unknown citations or provenance.

For equal-budget evidence, the runner independently sends the exact Java-rendered context through
the selected embedding tokenizer and requires the returned count, configured digest identity, and
shared token limit to match. This verification call is recorded separately as harness overhead;
it is not charged as MemOS retrieval work.

V008 adds a content-free `projection_provider_usage` record committed atomically with successful
or superseded projection completion. The source status combines it with existing extraction
attempt usage and marks completeness false for retries, replay, missing rows, or incomplete
settlement. `writes.jsonl` must contain exactly one explicit-usage row for every
baseline/scenario/repetition, and `costs.json` propagates completeness instead of converting
missing usage into zero. Failed write, retrieval, answer, and timing work remains visible through
content-safe error classes.

GitHub Actions run `#35` passed Java 25, V008/PostgreSQL integration, Python format/lint and 58
tests, documentation, and the complete compose smoke. No real Ollama campaign or result row has
run. The local storage/result-renderer candidate still requires remote publication, so
`docs/benchmark/results.md` remains `NOT RUN`.

## Equal-budget Java context

The published milestone removes the remaining Java/Python token-budget mismatch.
Java context assembly now counts each tentative complete rendered context through the configured
embedding port instead of summing independently tokenized fragments. Under the selected Ollama
configuration, this is the same digest-pinned embedding tokenizer used by the three Python
baseline builders. The response reports the immutable counter identity, final context tokens, and
the provider calls/input tokens consumed by budget checks so the runner can attribute that cost.

The deterministic fake remains credential-free and reports no provider usage. Focused context and
API tests plus Java 25 compilation passed locally. GitHub Actions run `#34` remotely passed the
Java 25, PostgreSQL, Python, documentation, and complete compose-smoke regression gates. The
independent runner-side parity assertion is covered locally with deterministic fakes but has not
run against the selected Ollama snapshots. This is a fairness mechanism, not an executed baseline
or quality result.

## Java Ollama embedding and long-call lease safety

The published implementation connects the Java MemOS projection and retrieval paths to the same
digest-pinned Ollama embedding contract used by the Python baseline primitives. It keeps the
deterministic 1024-dimensional fake as the credential-free default. A real environment must set
the same values for both API and worker, for example:

```text
MEMOS_EMBEDDING_PROVIDER=ollama
MEMOS_EMBEDDING_BASE_URL=<reachable-ollama-base-url>
MEMOS_EMBEDDING_MODEL_TAG=qwen3-embedding:0.6b
MEMOS_EMBEDDING_MODEL_VERSION=sha256:ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d
MEMOS_EMBEDDING_MODEL_DIGEST=ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d
MEMOS_EMBEDDING_DIMENSIONS=1024
MEMOS_EMBEDDING_TIMEOUT=300s
```

The base URL is deployment-specific and therefore has no repository-wide real-provider default.
The immutable model version is a MemOS projection identity; the adapter separately verifies the
Ollama tag and full observed digest. Startup rejects missing/drifted models, capability mismatch,
and dimension mismatch. Embedding responses reject model drift, malformed or oversized JSON,
multiple vectors, non-finite values, wrong dimensions, and invalid token counts. Timeouts,
transport errors, HTTP 429, and 5xx responses become transient projection failures; 4xx,
configuration/model drift, malformed responses, and dimension failures become permanent. Error
classes omit provider bodies and memory content.

The storage migration accepts retained vectors with dimensions `1..2000`, enforces equality
between declared and actual dimensions, and creates the selected 1024-dimensional partial HNSW
index. Existing 64-dimensional rows are retained as rebuildable state but excluded by current
model/dimension filters. A model-version reconciliation command is still missing, so changing an
already populated deployment's embedding model is not yet operationally complete.

Because one projection job may make several provider calls and the worker claims a serial batch,
the candidate also renews every claimed lease from the moment the batch is returned. Renewal uses
PostgreSQL time and the existing job/owner/token/unexpired fence at `lease-duration / 3`; the
worker stops and joins renewal before any separate terminal update. Low-cardinality counters
record renewal success, observed loss, and renewal errors. Existing commit fences remain the
authority under crash or reclaim races.

Focused tests cover strict Ollama requests/responses, digest/capability/dimension drift, timeout
and safe failure classification, provider-to-job retry mapping, batch heartbeat ordering,
stop-before-finalize, PostgreSQL migration and wrong-dimension rejection, valid/stale renewal, and
a handler exceeding its original lease. GitHub Actions run `#32` remotely passed those Java 25 and
PostgreSQL paths plus Python, docs, and the complete compose smoke. This is implementation
evidence, not a provider latency, retrieval-quality, freshness, or scale result.

## Observable MemOS settlement

The published implementation adds an authenticated
`GET /v1/source-events/{sourceEventId}/materialization` endpoint. It derives one content-free
source status from the complete durable job chain: any `DEAD` job is `FAILED`, all jobs
`SUCCEEDED` is `SUCCEEDED`, and every other state is `PROCESSING`. Per-job diagnostics include
type, state, attempt bounds, bounded error class, replay count, and lifecycle timestamps, but omit
source/provider payloads, credentials, lease identity, and lease tokens. Scope mismatch and
absence share the same `404` result.

The Python MemOS client uses per-request and total deadlines and polls this observable state rather
than applying a fixed post-ingestion sleep. Its strict decoder independently derives the aggregate
state, verifies source/job identities, pipeline ordering, terminal/schedule/lease invariants, and
aggregate timestamps, and turns transport, malformed response, terminal failure, and total-timeout
conditions into content-safe error classes. This closes the harness mechanism needed to measure
freshness; it does not establish the distribution, an SLO, or a baseline result. Selected-model
execution and result publication remain incomplete.

Publication verification in run `#30` passed Java 25 unit and PostgreSQL integration tests,
Python format/lint and 49 tests, Markdown links, and the complete compose smoke suite. This is
implementation and recovery evidence only; no model-dependent baseline execution occurred.

### Provider-contract spike

Purpose: verify that the client rejects model drift and that the selected embedding response can
supply the exact dimension and tokenizer accounting needed by the Java migration and runner.

On 2026-08-30, a direct repository-local probe against the declared Ollama endpoint reconfirmed
server `0.33.2`, both full model digests, the required completion/embedding capabilities, and a
1024-dimensional `qwen3-embedding:0.6b` response. The one-input probe reported seven embedding
tokens plus provider total/load durations. This is a contract observation from one call, not a
warm/cold latency sample or benchmark result; no quality or SLO claim follows from it.
