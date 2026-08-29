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

Status: `DONE` for Phase 1 design; ADRs remain `PROPOSED`

- Problem definition, three candidates, trade-offs, and recommendation are documented.
- Proposed direction: Java modular monolith + durable outbox/projection workers + PostgreSQL/pgvector/FTS.
- Assertion versions and state transitions are append-only while retained; governed erasure is an explicit exception.

## MVP

Status: `DOING` — Feature 5 is `IMPLEMENTED / PUBLICATION PENDING`

- Phase 1 was reviewed and the active project goal authorizes Features 0–6.
- Feature 0 engineering foundation is `DONE`: local Maven/Testcontainers, pgvector migration, architecture, Python, documentation, and API/worker smoke gates passed. The workflow is published and exercised by green GitHub Actions run `#17`.
- Feature 1 is `DONE` and published: source-event receipt and outbox commit atomically; claim/lease/fencing/retry/dead/replay and a payload-free logical-effect ledger passed PostgreSQL fault/concurrency tests and runtime smoke.
- Feature 2 is `DONE` and published in `6292b150851218fe6ab480115bde24a214b4d411`: provider-neutral strict extraction, deterministic trust/sensitivity/write policy, sanitized candidate/quarantine persistence, lease-fenced atomic completion, optional real-provider adapter, 17-case conformance fixture, and runtime smoke passed.
- Feature 3 is `DONE` and published in `5ff32fda451f3923e4130e309e5c19167e84905d`: versioned temporal authority, deterministic transition semantics, correction/invalidation, scoped APIs, 14-case temporal conformance, PostgreSQL fault/concurrency coverage, and API→worker→database restart smoke passed.
- Feature 4 is `DONE / PUBLISHED` through `69c5f63e836160aab04fc9995259f9c87aa2ca3e`: rebuildable vector/FTS projections, transition-watermarked projection jobs, scoped hybrid RRF retrieval, reranker fallback, trace restriction, evidence-budgeted context, and a six-case deterministic conformance fixture are implemented. GitHub Actions run `#17` passed JVM/PostgreSQL, Python, docs, and the full Feature 0–4 compose smoke, including governed invalidation, zero-row projection cleanup, and worker restart.
- Feature 5 is implemented locally but not yet published: verified JWT scope, `USER`/`OPERATOR`/
  `PRIVACY_ADMIN` boundaries, content-safe errors and trace audit, memory/user deletion operations,
  immediate projection hiding, lease-fenced atomic erasure, retry/dead/privacy-admin requeue,
  opaque append-only tombstones, replay/resurrection guards, and a four-case hostile-memory
  rendering fixture. Local production/test compilation and focused unit/security/fixture tests
  pass. PostgreSQL/Testcontainers, compose smoke, publication, and remote CI remain required.

## Advanced Memory

Status: `TODO`

- Temporal conflict semantics, forgetting, pollution control, and optional graph projections require MVP evidence.

## Benchmark

Status: `TODO` for execution; research/protocol `DONE`

- LoCoMo, LongMemEval, and BEAM research plus the experiment protocol are complete.
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

## Next phase

Finish Feature 5 publication: run the remote PostgreSQL and compose gates, fix any failures,
record the green commit/run evidence, and only then enter Feature 6 evaluation. The legacy trusted
scope headers and temporary operator key are removed and must not be reintroduced.
