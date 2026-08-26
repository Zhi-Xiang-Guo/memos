# Memory design lessons for MemOS

Cutoff: 2026-08-26. These lessons synthesize the system studies; they are project decisions only when an ADR says so.

Evidence rule for this document: every numbered lesson is an `INFERRED` design synthesis unless a sentence carries another grade. Upstream empirical findings are `UPSTREAM-REPORTED`; proposed benefits and scale assumptions remain `HYPOTHESIS` and are tracked in [open questions](../open-questions.md).

## 1. Preserve evidence; version interpretations

An extraction model may later improve, a user may correct a fact, and a policy may be wrong. Therefore:

- a retained source message/event is append-only evidence;
- an append-only memory assertion version is an interpretation derived from one or more sources;
- truth status and effective validity changes are append-only transition records, with a rebuildable current-state projection;
- a current-memory view is a projection that can be rebuilt;
- edits create versions rather than rewriting the only copy.

“Append-only” is a correction/history rule, not a promise to defeat erasure. A policy- or legally required hard delete may purge source and derived content; only a non-content tombstone/audit fact remains when policy permits.

This supports replay, audit, model migration, and root-cause analysis. It also avoids pretending that LLM extraction turns natural language into certain truth.

## 2. Stable identity and changing truth are separate

A logical memory lineage needs a stable `memory_id`; each interpretation needs its own `version_id` and version number. `CURRENT`, `HISTORICAL`, `CONFLICTED`, and `INVALIDATED` are explicit domain states recorded as transitions, not mutable fields inferred from whichever vector happens to rank highest.

Example:

```text
memory_id = residence:user-42
v1 assertion: Shanghai; later transition sets effective validity [2024-01, 2026-05), HISTORICAL
v2 assertion: Hangzhou; acceptance transition sets effective validity [2026-05, ∞), CURRENT
```

The old content is retained because “where did the user live before?” is different from “where does the user live now?”.

## 3. Use temporal semantics, not timestamp decoration

MemOS should distinguish at least:

- `source_observed_at`: when the system received the evidence;
- `event_time`: when the described event happened;
- proposed and effective `valid_from` / `valid_to`: when the assertion applies in the domain; effective revisions are transition records;
- `created_at` plus transition timestamps: transaction/audit time;
- `last_accessed_at`: a rebuildable access-statistics projection for retrieval/decay experiments, not assertion truth.

Unknown or partial dates should remain intervals with confidence and original text. A parser must not silently turn “last spring” into a precise instant.

## 4. Make the vector index disposable

The authority is the transactional version/provenance model. Embeddings, FTS documents, graph edges, and caches are derived projections with rebuild state and model/version metadata. This prevents partial embedding failure from corrupting truth and makes delete/re-embedding workflows testable.

## 5. Treat memory formation as an at-least-once workflow

LLM extraction and embeddings are slow and can time out. The chat path should generally commit the source event plus outbox record, then return. A worker consumes idempotently.

Required keys/invariants:

- unique source message ID within tenant;
- extraction job key includes source ID + extractor policy/model version;
- version writes use a deterministic candidate fingerprint where possible;
- retries do not create duplicate logical memories;
- partial projection failure is visible and repairable;
- dead jobs can be replayed without replaying successful side effects.

## 6. Separate model judgment from hard policy

LLMs may propose candidates, types, normalized content, entities, dates, importance, and contradiction hypotheses. Deterministic application code must own:

- tenant/user/agent authorization;
- idempotency and transaction boundaries;
- sensitive-data allow/deny policy;
- schema and temporal invariants;
- allowed status transitions;
- destructive deletion;
- audit emission.

This makes nondeterminism explicit instead of hiding it inside the database write.

## 7. Deduplication and contradiction need entity–predicate–time context

Embedding similarity is only a candidate signal.

- “I like coffee” / “Coffee is my usual drink” may merge.
- “I like coffee” / “I stopped drinking coffee” may conflict over an overlapping valid interval.
- “I lived in Shanghai” / “I live in Hangzhou” may be jointly true at different times.
- “The project uses Java” may refer to a different project or branch.

The decision requires resolved subject, predicate, object/value, scope, source trust, and temporal overlap. Ambiguous cases should become `CONFLICTED` or await review, not be destructively reconciled.

## 8. Retrieve broadly, authorize early, decide late

The read pipeline should apply hard scope/authorization/status/time filters before or during candidate generation, then combine complementary retrievers. A useful staged design is:

1. query understanding: intent, entities, requested time, expected memory types;
2. semantic, lexical, structured/entity, and temporal candidate generation;
3. rank fusion (RRF is a low-assumption starting point);
4. optional cross-encoder/LLM reranking for ambiguous cases;
5. conflict/current-state policy, diversity, and token-budget selection;
6. context rendering with provenance and untrusted-data delimiters.

Weights must be learned/tuned on a development set and frozen before test evaluation. Raw heterogeneous scores should not be summed merely because a formula looks precise.

## 9. Retrieval quality and answer quality are different

When an answer is wrong, the failure may be:

- write miss: the fact never became a candidate;
- write corruption: extracted memory is wrong;
- state error: update/conflict/versioning chose the wrong current fact;
- candidate miss: no relevant record entered top K;
- ranking error: the record existed but ranked too low;
- policy error: the right record was filtered out or a stale one survived;
- context error: selection exceeded budget or obscured the evidence;
- reasoning error: the model ignored correct context.

Instrumentation and benchmark labels must preserve these boundaries.

## 10. Abstention is a first-class success case

A system that always retrieves something will confidently answer from noise. Query gating, minimum evidence, conflict surfacing, and explicit “unknown” responses must be measured. Precision and calibration matter alongside recall.

## 11. Forgetting is a policy, not just TTL

Retention can depend on type, importance, confidence, recency, access frequency, task completion, user choice, and legal policy. Possible actions differ:

- reduce retrieval priority;
- compact/merge;
- archive outside the hot index;
- invalidate a derived interpretation;
- delete content and every projection;
- retain only a non-content tombstone if policy permits.

TTL alone cannot express durable preferences, completed tasks, historical events, and user-requested erasure correctly.

## 12. Deletion is a saga with a verifiable terminal state

“Forget my address” may touch source-event policy, memory versions, embeddings, lexical indexes, caches, graph projections, retry queues, snapshots, and audit/backup retention. The API needs an operation ID and status; tests need to query every relevant projection. Re-extraction jobs must not resurrect deleted content.

## 13. Memory content is untrusted input

`UPSTREAM-REPORTED` — Research on MINJA and persistent memory poisoning reports that durable state can amplify prompt injection across sessions; MemOS has not reproduced those attacks ([MINJA](https://arxiv.org/abs/2503.03704), [Bad Memory](https://arxiv.org/abs/2607.14611)). `INFERRED` practical consequences:

- provenance and trust labels accompany every candidate;
- retrieved content is data, never authority over system/developer instructions;
- procedural/instruction memories require stronger admission controls;
- external documents and tool outputs should not directly rewrite user profiles;
- quarantine and invalidation must propagate immediately;
- security evaluation covers write, retrieval, and downstream-use success.

## 14. Multi-agent memory requires explicit visibility

Memory is not automatically global. Use tenant → user/project → agent/team scopes, with read/write/promote permissions. Shared promotion should preserve the original private evidence and actor. Concurrent changes need optimistic locking or serialized subject/predicate updates; last-write-wins is not a semantic conflict policy.

## 15. Start with one transactional database, then measure

PostgreSQL can co-locate source records, versions, provenance, jobs/outbox, audit, structured filters, lexical search, and pgvector for the MVP. This reduces dual-write failure modes and makes invariants testable. `HYPOTHESIS` — it fits the initial scale envelope; this is not a claim that PostgreSQL is optimal at every scale.

Scale-out triggers should be explicit:

- Kafka when independent consumers, retention/replay, or outbox relay throughput justify it;
- OpenSearch when lexical relevance/aggregations or corpus size exceed measured PostgreSQL limits;
- a dedicated vector store when ANN scale, filtering, or update latency requires it;
- Redis when measured hot-key/query latency justifies cache invalidation complexity;
- graph storage when multi-hop quality gains justify entity-resolution and projection costs.

## 16. Benchmark the pipeline, not the story

Required comparison: full history, summary, vector-only, and MemOS under identical answer-model conditions. Report:

- write selection precision/recall and extraction correctness;
- deduplication and contradiction/update accuracy;
- Recall@K, MRR/nDCG where appropriate, and temporal candidate accuracy;
- answer accuracy, abstention, and evidence faithfulness;
- p50/p95/p99 write/retrieve latency;
- token consumption, model calls, storage/index growth, and failure rate;
- ablations for lexical, temporal, importance, fusion, and reranking components.

No architecture component earns a place through intuition alone.

## Decision summary

| Lesson | Current status |
|---|---:|
| Evidence + versioned interpretation | Proposed MVP invariant; pending implementation validation |
| PostgreSQL/pgvector MVP | Proposed ADR; still an experimental scale hypothesis |
| Asynchronous extraction via outbox | Proposed ADR; requires recovery spike |
| Hybrid retrieval | Proposed direction; fusion/reranker unsettled |
| Graph projection | Deferred |
| Learned memory policy | Research backlog |
| Sensitive procedural memory auto-write | Denied by default pending threat-model evidence |
