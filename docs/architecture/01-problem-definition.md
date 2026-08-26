# Problem definition

## Product statement

MemOS is infrastructure for agents that operate across sessions and must reuse selected experience without replaying an unbounded conversation. It turns attributable interaction events into governed, versioned memory and reconstructs a small, relevant, authorized evidence set for a future query.

The project is successful only if it demonstrates, with reproducible experiments, that the additional lifecycle produces better behavior than full history, rolling summary, and pure vector retrieval at an acceptable cost.

## Initial workload

`HYPOTHESIS` — The first benchmark workload is a personal/project assistant because it naturally exercises preferences, project decisions, cross-session facts, updates, temporal questions, noise, and user-controlled forgetting. The core interfaces remain domain-neutral.

Representative acceptance stories:

1. **Cross-session preference**: after many irrelevant turns and a new session, recall a durable beverage or coding preference.
2. **Temporal update**: retain Shanghai as historical and Hangzhou as current after a move; answer current, previous, and change-time questions differently.
3. **Deduplication**: merge three paraphrases of one preference without discarding their source evidence.
4. **Contradiction**: surface unresolved conflicting assertions rather than silently selecting the nearest vector.
5. **Abstention**: answer “unknown” when no trustworthy memory supports a claim.
6. **Noise resistance**: irrelevant conversations do not crowd out relevant memory.
7. **Privacy deletion**: “forget my address” reaches every active projection and does not resurrect on retry/replay.
8. **Idempotent write**: duplicated messages and at-least-once worker delivery do not duplicate logical memory.
9. **Tenant isolation**: identical queries from another tenant or user cannot retrieve the first user’s facts.
10. **Failure recovery**: timeouts and partial embedding/index failures remain visible and repairable without corrupting the source of truth.

## System boundary

### Inputs

- conversation messages and session metadata;
- tool results and application events with explicit trust labels;
- direct user/application memory commands;
- query plus user, tenant, agent, and optional time/task context;
- deletion, correction, and review commands.

### Outputs

- ingestion/materialization receipts;
- versioned memory records with provenance and truth status;
- ranked, authorized memory evidence with scores/explanations;
- token-budgeted context for an agent;
- audit and operational events;
- benchmark run artifacts.

### Owned by MemOS

- memory candidate lifecycle and policies;
- temporal/version model;
- idempotency and consistency of memory materialization;
- derived retrieval indexes and context selection;
- memory authorization/scoping and delete propagation;
- memory-specific metrics, replay, and evaluation.

### Not owned by MemOS

- general chat orchestration or model hosting;
- the authoritative task/calendar/CRM database;
- generic document RAG ingestion;
- foundation-model training or parametric unlearning;
- legal determination of retention requirements;
- a universal ontology for every product domain.

## Functional requirements

### Write

- Accept events with stable tenant, actor, user, session, message, and idempotency identifiers.
- Preserve source evidence append-only while retained, independently of derived memory; correction adds evidence, while policy/legal erasure may purge content under an auditable workflow.
- Propose candidates with structured output and extractor/model/prompt version.
- Decide `shouldRemember` using memory type, durability, novelty, sensitivity, confidence, and source trust.
- Normalize values and uncertain temporal expressions without discarding original text.
- Detect duplicate, merge, reinforce, supersede, coexist, invalidate, and unresolved-conflict cases.
- Persist a stable logical memory plus append-only assertion versions, state transitions, and many-to-many provenance while content is retained.
- Update derived indexes asynchronously and expose materialization status.

### Read

- Understand requested entity, time, memory type, and whether retrieval is warranted.
- Enforce tenant/user/agent scope before content leaves storage.
- Generate semantic, lexical, entity/metadata, and temporal candidates.
- Fuse and optionally rerank candidates using a benchmarked policy.
- Prefer current truth for present-tense questions while retaining historical evidence for historical questions.
- Return selected memories with version, provenance, status, confidence, and score components.
- Build delimited context within a configurable token budget.
- Abstain or expose conflict when evidence is insufficient.

### Manage and govern

- List, inspect, correct, invalidate, archive, and delete memories.
- Delete all user memory and report propagation state.
- Prevent replay/retry from recreating a tombstoned memory.
- Re-extract and re-embed from source evidence under a new versioned policy/model.
- Audit writes, reads of sensitive memory, changes, deletes, and administrative actions.
- Apply retention/decay policies by type and scope.

## Domain invariants

1. Every memory version belongs to exactly one tenant and logical memory lineage.
2. Every derived memory has at least one attributable source unless it is explicitly user/application-authored.
3. A vector or lexical document cannot exist as the sole authoritative representation.
4. Within a lineage, version numbers are monotonic and writes are concurrency-controlled.
5. Two `CURRENT` versions with overlapping valid intervals are forbidden unless the lineage is explicitly `CONFLICTED` or supports a set-valued predicate.
6. `INVALIDATED` content is never selected into agent context.
7. Hard authorization filters are not delegated to an LLM or reranker.
8. A completed hard-delete workflow leaves no queryable content in active projections.
9. Reprocessing the same source with the same extraction policy is idempotent.
10. Benchmark results always identify code commit, dataset version, provider/model versions, prompts, configuration, and environment.

## Non-functional requirements

### Correctness and consistency

- Source-event acceptance and outbox publication are atomic.
- Memory materialization is eventually consistent and exposes status.
- At-least-once delivery is assumed; handlers are idempotent.
- Projection lag/failure never changes the authoritative truth record silently.
- Deletion has an operation state and is retryable.

### Performance targets

All values below are `HYPOTHESIS` gates for a local/reference deployment, not achieved results:

- source ingestion p95 < 100 ms excluding network, without LLM work;
- non-reranked retrieval p95 < 250 ms at the first representative corpus size;
- materialization freshness p95 < 30 s under nominal hosted-model behavior;
- zero cross-tenant retrieval in deterministic isolation tests;
- no duplicate logical version under defined duplicate/concurrency tests.

Targets will be replaced or refined after workload and capacity profiles are fixed.

### Operability

- Structured logs and traces carry request, source, job, memory, version, tenant, and policy/model identifiers without leaking content by default.
- Metrics isolate extraction, policy, state transition, projection, candidate retrieval, ranking, and final answer stages.
- Failed jobs are inspectable and replayable.
- Memory diffs show what changed and why.

### Portability and cost

- LLM, embedding, and reranking providers are adapters.
- Core correctness tests run without a paid API.
- A benchmark run records model calls, tokens, and estimated/actual configured price inputs separately.
- Specialized infrastructure is added only against measured thresholds.

## Failure model

The MVP architecture must intentionally handle:

| Failure | Required behavior |
|---|---|
| Duplicate source request | Return same acceptance outcome; no duplicate source/event |
| Transaction commit succeeds, worker unavailable | Outbox remains pending and recoverable |
| LLM timeout/rate limit | Retry by policy; source remains accepted; materialization state visible |
| Malformed extraction | Reject/quarantine; do not partially mutate current truth |
| Two concurrent updates | Optimistic conflict/retry or serialized lineage update |
| Embedding failure | Memory version persists as source truth; projection marked failed |
| FTS/vector projection partially succeeds | Reconciliation detects and repairs |
| Stale cache | Cache is absent in MVP; future design requires version-aware invalidation |
| Poisoned/untrusted source | Trust/sensitivity policy may reject or quarantine; memory never gains instruction authority |
| Delete races with retry | Tombstone/generation check prevents resurrection |
| Reranker failure | Fall back to fused deterministic ranking and emit degradation metric |

## Success criteria

Phase 1 success is evidence-backed scope and architecture, not code. Final project success later requires:

- quality gains over all three baselines on held-out cases;
- correct update/temporal/conflict/abstention behavior, not only simple recall;
- reported latency, token, storage, and infrastructure trade-offs;
- demonstrated idempotency, concurrency, retry, and deletion invariants;
- source-linked research and pinned code-path analysis;
- honest failure cases and no invented benchmark number.
