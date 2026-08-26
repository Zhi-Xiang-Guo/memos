# Phase 1 summary

- Date: 2026-08-26
- Scope: research → problem definition → competitor analysis → architecture → MVP plan
- Boundary: no full implementation and no benchmark score

## Research Summary

The reviewed literature and official framework documentation converge on a useful boundary: memory is not a storage product or prompt trick; it is a feedback loop that **forms**, **manages**, and **selectively recalls** state. Conversation state, context compaction, RAG, vector indexes, and knowledge graphs are possible substrates or mechanisms inside that loop.

The product/framework studies cover Mem0, MemGPT/Letta, LangGraph, and Zep/Graphiti with pinned source paths, plus current OpenAI Agents SDK patterns, Google ADK, AutoGen, CrewAI, Semantic Kernel, and Anthropic context-engineering guidance. Benchmark research covers LoCoMo, LongMemEval, and BEAM from original papers/repositories. See the [research index](../README.md#research-map).

The 2025–2026 frontier increasingly emphasizes memory evolution, consolidation, agent/model-controlled policies, temporal/graph representations, multi-session evaluation, and trustworthiness. This strengthens the project premise: the unsolved production work is concentrated in selection, change, conflict, evidence, authorization, deletion, evaluation, and diagnosis—not merely embedding storage.

## Competitive Matrix

See [the evidence-graded matrix](research/08-competitive-matrix.md). It distinguishes a confirmed first-class feature from a partial primitive and from a capability not established by the reviewed source. “Not established” is not presented as proof of absence.

At a high level:

- Mem0 focuses on an embeddable long-term-memory layer with model-driven memory operations.
- Letta evolves the MemGPT hierarchical/context-management idea into stateful agents with editable memory primitives.
- LangGraph provides persistence/checkpoint and cross-thread store primitives on which an application builds its policy.
- Zep/Graphiti emphasizes temporally aware knowledge-graph construction and retrieval.
- General agent frameworks increasingly expose memory interfaces or patterns, but do not automatically supply MemOS’s complete temporal, consistency, governance, and benchmark contract.

## Key Insights

1. **Evidence and memory are different records.** Preserve source events; version derived interpretations.
2. **Current truth is a domain projection.** Vector rank must not determine `CURRENT`, `HISTORICAL`, `CONFLICTED`, or `INVALIDATED`.
3. **Time is multi-dimensional.** Source-observed time, event time, valid interval, and transaction/version time answer different questions.
4. **The write path is the first quality gate.** Long-lived false positives amplify across future sessions.
5. **Deduplication, conflict, and entity resolution are coupled.** Text similarity supplies candidates, not final state transitions.
6. **Retrieval is constrained decision-making.** Authorization, truth state, time, evidence sufficiency, and token budget surround semantic/lexical ranking.
7. **Model calls require an asynchronous reliability design.** At-least-once processing, idempotency, retry, reconciliation, and visible materialization state are mandatory.
8. **Deletion is a workflow.** Truth rows, indexes, jobs, caches, graph projections, and resurrection guards need a verifiable terminal state.
9. **Persistent memory creates a durable injection surface.** Trust labels and instruction/data separation belong on the write and read paths.
10. **Benchmarking must localize failure.** Write, manage, retrieve, select, and answer stages need separate metrics plus cost/latency.

## Architecture Candidates

Three candidates were evaluated in [architecture candidates](architecture/02-architecture-candidates.md):

1. Python synchronous research monolith — fastest experiments, weakest latency/failure isolation.
2. Java modular monolith + transactional outbox worker + PostgreSQL — strongest current balance.
3. Kafka/OpenSearch/vector/graph distributed platform — strongest theoretical component scale, unjustified operational/consistency cost for the unknown workload.

The scoring is an explicit design judgment, not performance data.

## Recommended Architecture

Use Candidate 2:

- Java/Spring Boot modules with API and worker runtime roles;
- PostgreSQL for retained source evidence, lineages, append-only assertion versions and state transitions, provenance, audit, jobs/outbox, temporal/structured queries, FTS, and pgvector;
- asynchronous candidate materialization with semantic idempotency keys;
- vectors and lexical/graph/cache data as rebuildable versioned projections;
- semantic + lexical + entity/metadata + temporal candidate retrieval;
- initial RRF experiment, optional reranking behind a deadline/feature flag;
- provider-neutral LLM/embedding/reranker ports;
- Python only for benchmark/data analysis.

The full consistency, data, failure, privacy, observability, and scale-out design is in [recommended architecture](architecture/03-recommended-architecture.md).

## Trade-offs

- Eventual memory availability keeps chat latency independent of LLM extraction but requires receipts/status and explicit synchronous critical-write behavior.
- One PostgreSQL boundary simplifies correctness and local reproduction but may later require search/workload isolation.
- Assertion versions and state transitions preserve history/audit while retained but make lifecycle and erasure more complex.
- Hybrid retrieval improves signal coverage but adds latency and tuning surface; it must beat vector-only on held-out cases.
- Java strengthens the backend implementation story; Python benchmark tooling prevents the language choice from slowing research.
- Deferring Kafka, Redis, OpenSearch, graph DB, and a dedicated vector store reduces distributed failure modes but postpones claims about extreme scale.
- Strong write/sensitivity rules protect quality and privacy but can miss useful information; thresholds must be risk- and type-specific.

## MVP Scope

The planned sequence in [MVP plan](architecture/04-mvp-plan.md) is:

1. executable modular skeleton and local PostgreSQL/pgvector environment;
2. idempotent source ingestion and transactional outbox;
3. structured extraction and `shouldRemember` policy;
4. versioned temporal memory with dedup/conflict transitions;
5. hybrid retrieval and token-budgeted context;
6. tenant isolation, deletion, audit, and poisoning defenses;
7. reproducible baselines, ablations, and result generation.

Every completed feature is independently tested, documented, committed, and pushed to the remote.

## Open Questions

The blockers for implementation decisions—not for completing Phase 1—are tracked in [open questions](open-questions.md). The highest-impact items are initial workload/SLOs, write-policy risk thresholds, uncertain date semantics, conflict cardinality rules, sensitive-memory admission, RRF versus learned/calibrated fusion, external benchmark licenses, deletion/audit policy, and graph-value evidence.

## Stage checkpoint

### 已确认事实

- Conversation/session state and long-term governed memory are distinct concepts in current official framework material.
- The reviewed systems use meaningfully different memory models: model-driven operations, hierarchical context, persistence/store primitives, and temporal knowledge graphs.
- Persistent memory can carry injection effects across later interactions; it requires its own threat model.
- No MemOS benchmark has run and no performance claim is available.

### 技术决策

- Evidence, assertion versions, and state transitions are append-only while retained; correction never overwrites content, while policy/legal erasure may purge it and retain only a non-content tombstone.
- PostgreSQL + pgvector + FTS is the MVP source/retrieval boundary.
- Ordinary conversation memory is materialized asynchronously through an outbox worker.
- Retrieval is hybrid and evidence-bearing; fusion configuration is benchmark-selected.
- Specialized infrastructure and graph storage are deferred behind measured triggers.

### Trade-off

The central trade is intentional: accept eventual memory freshness and a richer domain model to gain low chat latency, temporal correctness, auditability, and recoverable external-model failures—without paying distributed multi-store cost before it is justified.

### 当前实现状态

Research and architecture documentation only. No application source, migration, API, container, or benchmark harness has been implemented.

### Benchmark 状态

Protocol designed; LoCoMo/LongMemEval/BEAM studied; all baselines and metrics specified; results remain `NOT RUN`.

### 未解决问题

See [open questions](open-questions.md). No unresolved hypothesis is represented as a verified capability or score.

### 下一阶段计划

After review, begin only Feature 0 and Feature 1, proving local reproducibility, atomic source/outbox persistence, at-least-once idempotency, and fault recovery before integrating a real LLM.

## Next Step

Stop at the Phase 1 gate. The next Goal should review or amend this architecture, then authorize MVP Feature 0/1. Do not silently continue into full coding.
