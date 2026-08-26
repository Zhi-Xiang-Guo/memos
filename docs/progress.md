# Project progress

Last updated: 2026-08-27 (Asia/Shanghai)

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

Status: `DOING` — Feature 1

- Phase 1 was reviewed and the active project goal authorizes Features 0–6.
- Feature 0 engineering foundation is `DONE`: local Maven/Testcontainers, pgvector migration, architecture, Python, documentation, and API/worker smoke gates passed. The GitHub workflow is published but has no hosted-run record yet.
- Feature 1 is active: atomic source-event receipt and a durable transactional outbox.

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

Implement and publish Feature 1's smallest reliable slice: idempotent source-event ingestion → atomic outbox → leased worker → retry/dead-letter state with deterministic fault tests and metrics.
