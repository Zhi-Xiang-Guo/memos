# Feature 4 — hybrid retrieval and context builder

Status: `DONE / PUBLISHED`.

Feature 4 consumes the authoritative temporal memory and durable `PROJECTION_BUILD` intents from
[Feature 3](feature-3.md). It adds a rebuildable PostgreSQL search projection, asynchronous
embedding completion, independently generated vector/FTS/structured/temporal candidates,
deterministic RRF, truth-state policy, an optional reranker boundary, and provenance-bearing
context assembly under a bounded token budget.

This feature implements the read-path mechanism. The credential-free hashing embedding and the
six-case retrieval fixture validate plumbing and deterministic policy only. Formal external
dataset quality, comparable latency/cost, scale, and production safety remain `NOT RUN` and belong
to Feature 6.

## Feature boundary

Feature 4 owns:

- the `memory_search_projection` and `memory_projection_checkpoint` rebuildable tables;
- lease-fenced projection work with a transition-sequence watermark;
- hard tenant/user/agent, truth-state, embedding-version, and target-time SQL filters;
- vector cosine, PostgreSQL FTS, structured predicate/subject, and temporal candidates;
- RRF over component ranks and a deadline-bounded reranker port with deterministic fallback;
- query gating and a rule-based temporal-intent interface;
- diverse provenance-bearing evidence rendered as explicitly untrusted XML data;
- `POST /v1/retrieval` and operator-only `POST /v1/retrieval/trace`.

Feature 4 does not own JWT/RBAC, governed hard erasure, provider-grade embeddings/reranking,
calibrated learned fusion, external benchmark scores, or capacity claims. Those remain Features
5–6.

## Projection authority and failure semantics

PostgreSQL assertion versions, transitions, current state, and provenance remain authoritative.
`memory_search_projection` stores only rebuildable query material: scoped identity, truth status,
normalized content, valid time, source IDs, a generated `tsvector`, a fixed 64-dimensional vector,
provider/policy versions, transition sequence, and projection time.

The worker loads one scope-bound snapshot and releases the database transaction before embedding.
Commit then locks and verifies the exact claimed job lease. It also locks the lineage and compares
the latest authoritative transition sequence with the snapshot watermark.

| Condition | Commit result | Effect |
|---|---|---|
| Current lease and current transition | `COMMITTED` | Replace one lineage projection, advance checkpoint, complete job atomically |
| Newer transition appeared during embedding | `SUPERSEDED` | Write no projection rows; complete stale job as superseded |
| Owner/token/expiry fence no longer matches | `LEASE_LOST` | Write and complete nothing |
| Exact completed job replay | `ALREADY_COMMITTED` | Return existing logical result |
| Latest state is invalidated | `COMMITTED` with zero rows | Delete projection rows and retain a zero-count checkpoint |
| Provider returns a different model version/dimension | permanent failure | Do not enter the commit transaction |

Deleting and reinserting one lineage occurs in the same transaction. A worker restart or stale job
cannot overwrite a later transition because the database-time lease and transition watermark are
both checked at commit.

## Retrieval policy

Every component query includes exact tenant, user, and agent equality. Present queries admit only
`CURRENT` and `CONFLICTED`; a target time uses half-open valid-time containment. The Java policy
rechecks visibility after candidate generation as defense in depth.

`VECTOR_ONLY` requests only the vector generator. `HYBRID` independently requests the applicable
vector, lexical, structured, and temporal generators. Raw component scores are diagnostic data,
not cross-source weights. RRF uses `sum(1 / (k + rank))` with configured `k=60` by default,
followed by deterministic transaction-time and version-ID tie breaks.

Reranking is disabled by default. When enabled, it receives typed candidates, a fixed model
version, and an absolute deadline. Provider exceptions, model-version drift, deadline expiry,
duplicate/missing IDs, or a changed candidate set fall back to the deterministic fused order. No provider output controls
authorization, visibility, destructive actions, or projection state.

## HTTP and context contract

At Feature 4 publication time, retrieval used the trusted-upstream scope headers from Features
1–3 and protected `/v1/retrieval/trace` with a temporary operator key. Both mechanisms are now
`DEPRECATED` and removed. Feature 5 requires a verified bearer token for both endpoints and the
`OPERATOR` role for trace access; successful trace access is written to the append-only,
content-safe audit table. Ordinary responses continue to omit raw component diagnostics.

The context assembler:

- renders evidence inside `<memory-evidence trust="untrusted-data">`;
- XML-escapes all memory content and metadata;
- includes opaque memory/version/source lineage rather than inventing citations;
- limits one item per lineage, except two conflicting alternatives;
- never exceeds the configured deterministic token budget;
- reports considered, selected, truncated, token-count version, and selected version IDs.

`CodePointTokenCounter` is an injected deterministic local counter, not a claim of provider
tokenizer parity. A real provider adapter must supply the matching tokenizer before comparable
token/cost experiments.

## Deterministic retrieval fixture

The versioned [retrieval fixture](../../benchmark/fixtures/retrieval/v1/README.md) contains six
synthetic cases: exact identifier, semantic paraphrase, present truth filtering, historical
intent, conversational gating, and context lineage diversity. The manifest pins its exact
normalized-text SHA-256, split, coverage, policy versions, and frozen test IDs.

The Java runner emits observed vector-only/hybrid rankings and selected context IDs. The Python
reporter independently validates fixture/prediction integrity, exact contract conformance, and
mechanically derives Recall@1/MRR. On the current deterministic observations, 6/6 cases conform;
among five eligible synthetic cases vector-only Recall@1/MRR are `0.6/0.6` and hybrid values are
`1.0/1.0`. Prediction SHA-256 is
`da73444fe0d9c4f965884b53dc08d2769024f74e8e4b15eff570d6c6da115d55`.

These values describe hand-constructed candidate ranks and policy mechanics only. They are not
eligible for [formal benchmark results](../benchmark/results.md), résumé claims, production
quality, or comparison with another system.

## Observability and runtime evidence

Low-cardinality telemetry records retrieval mode/outcome/duration and selected-count summaries.
Tenant, user, agent, memory, version, source, query, and provider payload are never metric labels.
Default logs must not contain query text, normalized memory content, request bodies, credentials,
lease tokens, or provider secrets.

`scripts/smoke-feature4.sh` runs source ingestion through extraction, temporal materialization,
projection, retrieval, trace authorization, cross-scope isolation, invalidation cleanup, and
worker restart. It also records one environment-local 40-sample p50/p95/p99 and context-token
observation in CI logs. Those changing smoke numbers are operational diagnostics, not published
benchmark results.

## Verification matrix

| Gate | Target evidence | State |
|---|---|---|
| Projection handler | Embed outside transaction; commit/empty/model-drift behavior | `PASS` — 3 unit tests |
| Fusion and visibility | Independent sources, RRF, present truth policy, vector-only, gate | `PASS` — 5 unit tests |
| Reranker fallback | Invalid candidate identity falls back without partial reorder | `PASS` |
| Context safety | Injection escaping, budget, provenance, lineage diversity | `PASS` — 2 unit tests |
| API contract | Scoped evidence, trace restriction, error mapping | `PASS` — 6 focused API tests |
| Fixture | Byte-stable Java observations and exact Python report | `PASS` — 6/6 synthetic cases |
| Python workspace | locked Python 3.14, Ruff, and pytest | `PASS` — 15 tests |
| PostgreSQL migration/query/fencing | V005, stale transition, lease loss, scope/truth filters | `PASS` — PostgreSQL/Testcontainers in CI run `#17` |
| Runtime smoke | Full projection/retrieval/invalidation/restart path and 40 latency samples | `PASS` — compose smoke in CI run `#17` |
| Full Java verification | `./mvnw -B -ntp clean verify` | `PASS` — JVM job in CI run `#17` |
| Markdown links | `python3 scripts/check_markdown_links.py` | `PASS` — 57 Markdown files locally and docs job in CI run `#17` |
| Git publication | Coherent commit pushed and CI green | `PASS` — published verification head `69c5f63e836160aab04fc9995259f9c87aa2ca3e` |

The implementation commit `34904ab82c7bf02676c1e3c2c7dfeb266135ea41` and publication fixes through
`69c5f63e836160aab04fc9995259f9c87aa2ca3e` are pushed to `origin/main`. GitHub Actions
[CI run #17](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33267375064) passed its JVM,
Python, docs, and compose-smoke jobs. The compose job exercised all Feature 0–4 smoke scripts;
the Feature 4 path included governed invalidation evidence, zero-row projection cleanup, and
worker restart persistence.

Feature 4 is therefore `DONE / PUBLISHED`. Its synthetic conformance values and environment-local
smoke samples remain implementation evidence only, not formal benchmark or production SLO data.
