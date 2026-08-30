# Project progress

Last updated: 2026-08-30 (Asia/Shanghai)

## Research

Status: `DONE`

- Repository inspected: initialized empty Git repository on `main`; no commit and no remote at inspection time.
- Primary-source studies cover Mem0, Letta/LangGraph, Zep/Graphiti, frameworks, and three memory benchmarks.
- Mem0, Letta, LangGraph, and Graphiti source paths are pinned to full commit SHAs with call-chain audits.
- The evidence-graded competitive matrix and cross-system design lessons are complete.
- Initial Phase 1 snapshot `183d876` was pushed to `origin/main`; remote publication is verified.

## Architecture

Status: `DONE` for Phase 1 design; ADRs 0001–0004 remain `PROPOSED`, ADR-0005 is `ACCEPTED`

- Problem definition, three candidates, trade-offs, and recommendation are documented.
- Proposed direction: Java modular monolith + durable outbox/projection workers + PostgreSQL/pgvector/FTS.
- Assertion versions and state transitions are append-only while retained; governed erasure is an explicit exception.

## MVP

Status: `DOING` — Feature 5 is `DONE / PUBLISHED`; Feature 6 evaluation is in progress

- Phase 1 was reviewed and the active project goal authorizes Features 0–6.
- Feature 0 engineering foundation is `DONE`: local Maven/Testcontainers, pgvector migration, architecture, Python, documentation, and API/worker smoke gates passed. The workflow is published and exercised by green GitHub Actions run `#17`.
- Feature 1 is `DONE` and published: source-event receipt and outbox commit atomically; claim/lease/fencing/retry/dead/replay and a payload-free logical-effect ledger passed PostgreSQL fault/concurrency tests and runtime smoke.
- Feature 2 is `DONE` and published in `6292b150851218fe6ab480115bde24a214b4d411`: provider-neutral strict extraction, deterministic trust/sensitivity/write policy, sanitized candidate/quarantine persistence, lease-fenced atomic completion, optional real-provider adapter, 17-case conformance fixture, and runtime smoke passed.
- Feature 3 is `DONE` and published in `5ff32fda451f3923e4130e309e5c19167e84905d`: versioned temporal authority, deterministic transition semantics, correction/invalidation, scoped APIs, 14-case temporal conformance, PostgreSQL fault/concurrency coverage, and API→worker→database restart smoke passed.
- Feature 4 is `DONE / PUBLISHED` through `69c5f63e836160aab04fc9995259f9c87aa2ca3e`: rebuildable vector/FTS projections, transition-watermarked projection jobs, scoped hybrid RRF retrieval, reranker fallback, trace restriction, evidence-budgeted context, and a six-case deterministic conformance fixture are implemented. GitHub Actions run `#17` passed JVM/PostgreSQL, Python, docs, and the full Feature 0–4 compose smoke, including governed invalidation, zero-row projection cleanup, and worker restart.
- Feature 5 is `DONE / PUBLISHED` through `ae377143929cf2a7fcfbfccae21d8792b7275d7e`:
  verified JWT scope, `USER`/`OPERATOR`/`PRIVACY_ADMIN` boundaries, content-safe errors and trace
  audit, memory/user deletion operations, immediate projection hiding, lease-fenced atomic erasure,
  retry/dead/privacy-admin requeue, opaque append-only tombstones, replay/resurrection guards, and
  a four-case hostile-memory rendering fixture are implemented. GitHub Actions
  [run #22](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33272314267) passed Java 25,
  PostgreSQL migration/fault/concurrency tests, Python, docs, and the full Feature 0–5 compose
  smoke. This is implementation evidence, not formal security effectiveness or model-quality data.
- Feature 6's first contract gate is `DONE / PUBLISHED` through
  `4120144a3afa2b9c7e443fe3c27e88eb01595330`: the bounded bilingual
  personal/project-assistant workload, 13-scenario/15-question synthetic smoke dataset, frozen
  train/dev/test IDs, CC BY 4.0 license and attribution, answer/summary prompt hashes, equal-budget
  four-baseline contract, and exact local Ollama model IDs are machine-verified. GitHub Actions
  [run #24](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33273671807) passed Java 25,
  PostgreSQL/compose regression, Python, and docs gates. No baseline run, quality score, latency
  result, or cost result exists yet.
- Feature 6 run-package core is `DONE / PUBLISHED` through
  `afcabe79670344dab8ed1500eb59b3a17f293842`: immutable file-set hashing,
  split-membership/config identity, full execution-row accounting, mechanically regenerated
  answer/retrieval/abstention/track/latency metrics, usage totals, and content-safe failure
  summaries pass 28 Python tests. The current v1 candidate also pins Java extraction prompt/schema
  hashes and forbidden-context labels. GitHub Actions
  [run #26](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33274839393) passed Java 25,
  PostgreSQL/compose regression, Python, and docs gates. This is harness evidence, not a baseline
  run.
- Feature 6 provider/baseline primitives are `DONE / PUBLISHED` through
  `367e0fa5d5232793e297ea007324ba144cd660f5`: full Ollama digest/capability verification,
  structured chat and embedding accounting, strict answer/summary schemas, exact rendered-context
  token budgeting, and the full-history, rolling-summary, and raw-turn-vector context builders pass
  40 Python tests. GitHub Actions
  [run #28](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33276271008) passed Java 25,
  PostgreSQL/compose regression, Python, and docs gates. The Java MemOS runner and all result rows
  remain `NOT RUN`.
- Feature 6's observable MemOS settlement path is `DONE / PUBLISHED` through
  `2bf7689cb11c37e6eb73744bcf6e79915775a9e7`: a hard-scoped source-level aggregate covers
  extraction, authoritative-materialization, and projection jobs, while a bounded Python client
  waits on processing/success/failure instead of a fixed sleep. GitHub Actions
  [run #30](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33277348058) passed Java 25,
  PostgreSQL integration, Python, docs, and the complete compose smoke. This is
  harness/freshness-mechanism evidence, not a measured freshness distribution or SLO; the unified
  runner remains incomplete.
- Feature 6's Java Ollama embedding and long-call lease safety is `DONE / PUBLISHED` through
  `9225ed101ca19b4441438d13915b238bf8e3869f`: API and worker share one
  digest/capability/dimension-pinned adapter, V007 supports a checked 1024-dimensional provider
  projection, provider failures map to durable retry/dead semantics, and PostgreSQL-time fenced
  heartbeats cover every serially claimed batch item. GitHub Actions
  [run #32](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33279370001) passed Java 25,
  PostgreSQL migration/renewal/fault/concurrency tests, Python, docs, and the complete compose smoke.
  This is implementation evidence, not model-quality, latency, freshness, or scale evidence.
  Model-version projection reconciliation remains unresolved.
- Feature 6's equal-budget Java context milestone is `DONE / PUBLISHED` through
  `c5035c313e84ef1e0b05b96d65f2829a64b339ba`: every tentative
  complete context is counted by the configured embedding tokenizer, the selected Ollama path
  therefore shares the Python baselines' digest-pinned counter, and provider calls/input tokens
  spent on budget checks are returned for cost attribution. GitHub Actions
  [run #34](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33280316053) passed Java 25,
  PostgreSQL migration/fault/concurrency tests, Python, docs, and the complete compose smoke.
  Runner-side parity verification remains pending; no baseline result exists yet.
- Feature 6's unified-runner and exact-cost milestone is `DONE / PUBLISHED` through
  `db213df6c51a8ce680a8df1aaba12cadb811ac38`: all four
  baselines share one answer path and evidence budget; MemOS uses isolated JWT scopes, waits for
  every source chain, maps API UUID provenance back to dataset event IDs, and independently
  retokenizes returned Java contexts. V008 persists content-free projection embedding usage, the
  source aggregate rejects retry/replay-incomplete accounting, and the artifact verifier now
  requires exactly one explicit-usage write row per baseline/scenario/repetition. GitHub Actions
  [run #35](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33281737584) passed Java 25,
  V008/PostgreSQL integration, Python format/lint and 58 tests, documentation, and the complete
  compose smoke. No real-model run, score, latency distribution, storage result, or SLO exists yet.
- Feature 6's storage-observation and mechanical-report milestone is `DONE / PUBLISHED` through
  commit `46ecdd7` and
  [GitHub Actions run #37](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33284193760).
  An operator-only endpoint derives the exact tenant/user/agent scope from JWT claims and returns
  relation-level PostgreSQL row counts/`pg_column_size(record)` bytes plus separately labeled
  database-native table/index allocation. The three local baselines disclose canonical UTF-8 or
  dense float32 representations. `storage.json` and `report.md` are integrity-covered and
  independently regenerated. Run #37 passed Java 25 clean verify with PostgreSQL integration,
  Python format/lint and 65 tests, documentation, and the complete compose smoke. Abstention F1
  reports `0.0` for missed or spurious positive cases and reserves `N/A` for the structurally
  inapplicable no-gold/no-prediction case. This is harness evidence, not a storage, quality,
  latency, or cost result.
- Feature 6's extraction-identity hardening is `DONE / PUBLISHED` through commit `4920e55` and
  [GitHub Actions run #39](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33287028091):
  OpenAI-compatible request tags are separate from deployment-attested provenance, native Ollama
  extraction verifies a full digest and completion capability, API/worker jobs share the immutable
  extraction version, and mismatched old jobs fail permanently without a provider call. Run #39
  passed Java 25, PostgreSQL integration, Python, documentation, and the complete compose smoke.
  This is not a real-model benchmark result.

## Advanced Memory

Status: `TODO`

- Temporal conflict semantics, forgetting, pollution control, and optional graph projections require MVP evidence.

## Benchmark

Status: `DOING` for Feature 6 harness; research/protocol and smoke contract `DONE`; execution `NOT RUN`

- LoCoMo, LongMemEval, and BEAM research plus the experiment protocol are complete.
- `memos-assistant-smoke-v1` freezes the first license-compatible local evaluation contract; its
  verifier rejects case, prompt, license, notice, split, count, family, or evidence-cutoff drift.
- No experiment has run; [results](benchmark/results.md) intentionally contain no scores.

## Optimization

Status: `TODO`

## Resume

Status: `TODO`

- No performance numbers will be written before benchmark completion.

## Interview

Status: `TODO`

## Phase-1 exit criteria

- [x] Eight required research documents complete and source-linked.
- [x] Critical Mem0, Letta, LangGraph, and Zep source paths pinned to commit SHAs.
- [x] Competitive matrix separates confirmed capability from inference.
- [x] Problem definition, architecture candidates, trade-offs, and recommendation complete.
- [x] MVP scope, benchmark plan, open questions, and next phase gate complete.
- [x] Documentation link check and unsupported-claim audit pass.
- [x] Initial commit pushed to a GitHub remote.

## Decision log snapshot

| Topic | Current decision | Fact grade | Evidence / follow-up |
|---|---|---:|---|
| Phase boundary | MVP Features 0–6 are authorized under one continuous goal | `CONFIRMED` | Active project goal |
| System of record | PostgreSQL; vector/FTS indexes are projections | `HYPOTHESIS` | Validate in MVP benchmark |
| Topology | Modular monolith plus asynchronous worker/outbox | `HYPOTHESIS` | Failure-recovery spike in MVP |
| Retrieval | Hybrid candidate generation and measured fusion | `HYPOTHESIS` | Tune only on benchmark dev split |
| Graph database | Not in MVP | `HYPOTHESIS` | Add only if entity/multi-hop ablation proves value |
| Benchmark scores | None available | `CONFIRMED` | No run has been executed |
| Initial workload | Bilingual personal/project assistant | `CONFIRMED` | Frozen Feature 6 v1 smoke manifest and cases |

## Next phase

Continue Feature 6 without entering Advanced Memory: add projection reconciliation, then execute
the predeclared dev smoke against the selected local model snapshots.
The legacy trusted scope headers and temporary operator key are removed and must not be
reintroduced.
