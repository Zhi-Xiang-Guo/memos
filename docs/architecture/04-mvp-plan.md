# MVP development plan

Phase 1 stops before this plan is executed. Each feature below has its own verification gate, commit, and push to the GitHub remote.

## Definition of done for every feature

- behavior and failure semantics are documented;
- tests cover happy path, duplicate/retry, and relevant authorization boundary;
- metrics/log fields are added with sensitive-content redaction;
- migrations and API changes are backward-aware;
- `docs/progress.md` and applicable ADR/open questions are updated;
- local verification commands pass;
- the feature is committed and pushed; benchmark numbers are never hand-authored.

## Feature 0 — executable skeleton and reproducible local environment

Deliver:

- Java/Spring Boot modular build and module-boundary tests;
- PostgreSQL + pgvector local environment, migrations, and health checks;
- API error/idempotency conventions, clock/ID abstractions;
- Python benchmark workspace isolated from production domain logic;
- CI for formatting, unit/integration tests, migration validation, and docs links.

Exit gate: clean clone can start the reference stack and run deterministic tests without a paid model API.

## Feature 1 — idempotent source ingestion and transactional outbox

Deliver:

- source-event and outbox schema;
- ingest API with tenant/user/session/source/idempotency identifiers;
- atomic commit and duplicate semantics;
- worker claim/lease/retry/dead state;
- fake handler proving at-least-once idempotency.

Failure tests: duplicate concurrent requests, crash before/after commit, expired lease, worker restart, poison job.

Exit gate: no lost committed source and no duplicate side effect under the defined fault suite.

## Feature 2 — candidate extraction and write policy

Deliver:

- provider-neutral structured extraction port and deterministic fake;
- prompt/schema/model version tracking and usage accounting;
- `shouldRemember` policy with memory type, novelty, sensitivity, source trust, confidence;
- quarantine/review outcome and safe schema validation;
- labeled write-policy fixture set.

Exit gate: publish precision/recall by candidate class on the fixture set; do not tune on final test cases.

## Feature 3 — versioned temporal memory

Deliver:

- lineage/assertion-version/state-transition/provenance schema and repositories;
- current/historical/conflicted/invalidated transition state machine plus rebuildable current-state projection;
- normalization, dedup candidate lookup, temporal interval handling;
- optimistic concurrency/lineage locking;
- correction/invalidation APIs and memory diff.

Exit gate: deterministic Shanghai→Hangzhou, paraphrase dedup, overlapping conflict, set-valued predicate, concurrent update, and replay tests pass.

## Feature 4 — hybrid retrieval and context builder

Deliver:

- vector, FTS, structured/entity, and temporal candidate adapters in PostgreSQL;
- query gate and query-intent/time parsing interface;
- recorded component ranks, initial RRF, feature-flagged reranking;
- truth-state filtering, token budget, diversity, provenance rendering;
- retrieval trace endpoint restricted for benchmark/operator use.

Exit gate: component Recall@K/MRR and end-to-end cases run for vector-only and hybrid configurations; p50/p95/p99 and tokens recorded.

## Feature 5 — privacy, deletion, audit, and poisoning defenses

Implementation status: `DONE / PUBLISHED` on 2026-08-30 through commit `ae37714` and
[GitHub Actions run #22](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33272314267). See the
[Feature 5 implementation note](../implementation/feature-5.md). Formal security effectiveness
and model-level poisoning evaluation remain outside this implementation gate.

Deliver:

- strict tenant/user/agent scope tests;
- sensitive-memory policy hooks;
- delete-memory and delete-user workflows with operation status;
- projection deletion/reconciliation and resurrection guard;
- content-safe audit log and adversarial memory fixtures.

Exit gate: cross-tenant test has zero leakage, deletion completes across all projections, job replay cannot resurrect content, and malicious instruction memories remain data rather than instructions.

## Feature 6 — reproducible benchmark and baseline report

Implementation status: `DOING`. The workload, v1 synthetic smoke dataset, license/attribution,
prompt hashes, local model IDs, and four-baseline parity declaration are frozen and locally
verified through commit `4120144` and GitHub Actions run `#24`. Harness execution and all result
packages remain `NOT RUN`. The run-package core and provider/non-MemOS baseline primitives are
published through commit `367e0fa` and GitHub Actions run `#28`. The source-level materialization
aggregate and bounded wait client are published through commit `2bf7689` and GitHub Actions run
`#30`.
The Java Ollama embedding and long-call lease-safety milestone is published through commit
`9225ed1` and GitHub Actions run `#32`; the unified runner and all result packages remain
`NOT RUN`.
The complete-context tokenizer-parity milestone is published through commit `c5035c3` and GitHub
Actions run `#34`.
The unified runner and exact provider-usage milestone is published through commit `db213df` and
GitHub Actions run `#35`; deterministic four-baseline, V008/PostgreSQL, and compose gates pass.
The storage-observation and result-renderer candidate is locally implemented: all baselines expose
their measurement method, MemOS reports scoped PostgreSQL row bytes separately from database
allocation deltas, and the verifier regenerates `storage.json` and `report.md`. Remote PostgreSQL
publication verification, real-model parity, and all result packages remain `NOT RUN`.

Deliver:

- dataset adapters permitted by upstream licenses;
- fixed train/dev/test protocol and run manifest;
- full-context, summary, vector-only, and MemOS baselines;
- component and end-to-end metrics, cost/latency/storage collection;
- ablations and error analysis generated from raw artifacts;
- report command that regenerates tables/charts.

Exit gate: a fresh environment can reproduce the published smoke run and the full result package identifies every external model/version limitation.

## Deferred advanced-memory features

Not in MVP unless benchmark evidence changes scope:

- learned write/read/forget policies;
- graph database and general entity-linking service;
- Kafka/OpenSearch/dedicated vector store/Redis;
- multimodal memory;
- cross-user memory transfer;
- autonomous procedural-memory execution;
- parametric memory or fine-tuning;
- multi-region active-active deployment.

## Next action after Phase 1 review

Start Feature 0, then prove Feature 1’s fault semantics before integrating any real LLM. This prevents provider calls from hiding basic transaction and idempotency bugs.
