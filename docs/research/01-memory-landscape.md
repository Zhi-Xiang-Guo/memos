# Agent Memory landscape (2025–2026)

Research cutoff: **2026-08-26**. This document uses official documentation, official repositories, and original papers. It describes what the sources establish; it does not treat marketing language or an unreviewed survey’s synthesis as an independently verified benchmark result.

## Fact grades

- `CONFIRMED`: directly supported by linked official documentation/source code/paper, or by a repository-local experiment once one exists.
- `UPSTREAM-REPORTED`: a paper author or vendor reports a setup/result that MemOS has not independently reproduced.
- `INFERRED`: a design interpretation consistent with confirmed evidence but not directly guaranteed by the source.
- `HYPOTHESIS`: a claim that MemOS must test.
- `DEPRECATED`: a historical approach or API that should not be presented as current.
- `N/E`: not established within the reviewed source boundary; this is not proof of universal absence.

## Operational definition

`CONFIRMED` — Recent memory research explicitly distinguishes agent memory from LLM memory, RAG, and context engineering, and analyzes it through its representation, function, and dynamics. A 2026 survey formalizes the subsystem as a **write–manage–read** loop rather than a store alone ([Memory in the Age of AI Agents](https://arxiv.org/abs/2512.13564), [Memory for Autonomous LLM Agents](https://arxiv.org/abs/2603.07670)).

MemOS adopts this operational definition:

> **Agent memory is governed, persistent state derived from attributable experience and selectively reconstructed into working context to improve future decisions.**

This definition has five necessary parts:

1. **Selection** — a policy decides what is worth retaining.
2. **State evolution** — memories can be normalized, merged, superseded, disputed, invalidated, decayed, archived, or deleted.
3. **Provenance and scope** — the system knows why a memory exists and who/what may use it.
4. **Selective recall** — retrieval is conditioned on query, entity, time, task, authorization, and token budget.
5. **Downstream utility** — success is measured by future behavior, not just storage or semantic similarity.

## Three boundaries that prevent a demo architecture

### Conversation history is not memory

`CONFIRMED` — Google ADK documents `Session` events/state as one conversation’s short-term state and `MemoryService` as searchable knowledge spanning prior interactions ([ADK Memory](https://adk.dev/sessions/memory/)). OpenAI’s conversation-state guide describes ways to preserve information across turns, while its long-term-memory cookbook adds a separate pattern that distills session notes, consolidates them with deduplication/conflict handling, and injects selected state on later runs ([Conversation state](https://developers.openai.com/api/docs/guides/conversation-state), [Long-term memory notes](https://developers.openai.com/cookbook/examples/agents_sdk/context_personalization)).

A history is an event log optimized for replay. A memory projection is optimized for future decisions. Keeping history alone leaves unanswered:

- Which statements are durable rather than transient?
- Which speaker or tool is authoritative?
- Which fact is current at a requested time?
- Which statement superseded another?
- What is safe and authorized to expose?
- How does the user delete a derived fact without corrupting unrelated history?

`INFERRED` — Raw history should remain evidence; long-term memory should be a rebuildable, versioned interpretation linked back to that evidence.

### Vector search is not memory

`CONFIRMED` — AutoGen’s `Memory` protocol deliberately permits vector or text search and leaves storage/retrieval behavior to implementations; its basic abstraction exposes operations such as add, query, update context, clear, and close ([AutoGen Memory and RAG](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/memory.html), [Memory API](https://microsoft.github.io/autogen/stable/reference/python/autogen_core.memory.html)). This makes retrieval a mechanism, not a complete lifecycle.

Cosine similarity cannot by itself establish:

- truth, authority, confidence, or tenant access;
- `CURRENT` versus `HISTORICAL` status;
- temporal overlap or a change point;
- semantic equivalence for deduplication;
- contradiction, invalidation, or deletion completion;
- whether retrieval is necessary for the current query;
- whether recalled text contains durable prompt injection.

`INFERRED` — A vector index should be a disposable candidate-retrieval projection over a transactional source of truth. The application, not the distance function, owns memory semantics.

### Long context is not long-term memory

`CONFIRMED` — Anthropic’s context-engineering guidance treats context as a finite attention budget and recommends compaction, persistent structured notes, and multi-agent separation for long-horizon work ([Effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)). OpenAI documents compaction and separate persistent state patterns rather than claiming a large window eliminates state management ([Compaction](https://developers.openai.com/api/docs/guides/compaction)).

`UPSTREAM-REPORTED` — Chroma’s published context-rot study reports degradation as input length and task complexity grow; MemOS has not reproduced the study, and it is not treated as a universal theorem ([Context Rot](https://www.trychroma.com/research/context-rot)).

Long context still lacks durable cross-session state, governed updates, efficient selective recall, independent deletion, access control, and bounded per-request token cost. It is a working-memory substrate, not the whole subsystem.

## Functional taxonomy

The familiar cognitive labels are useful only if they create different system behavior.

| Type | Operational purpose | Typical retention | Required behavior | Initial MemOS decision |
|---|---|---:|---|---|
| Working | Current goal, plan, unresolved task state | minutes/days | fast update, compaction, session/task scope | First-class, separate from durable profile facts |
| Semantic | Stable facts, preferences, constraints | weeks/years | dedup, supersession, confidence, current-state query | First-class |
| Episodic | Time-bound events and decisions | policy-defined | event time, sequence, historical query, provenance | First-class |
| Procedural | Reusable rules or verified ways of acting | until invalidated | evidence threshold, applicability conditions, versioning | First-class but stricter write policy |
| Entity/relationship | Linked people, systems, projects, predicates | derived | entity resolution, edge validity, multi-hop traversal | Deferred projection until benchmark value is shown |
| Task | Commitments, state machines, deadlines | until complete + retention | explicit state transitions, ownership | May remain a domain model rather than “memory” |

`INFERRED` — “Task memory” should not duplicate a reliable task service. The memory layer may store evidence and retrieval projections about tasks while the task service remains authoritative.

## Representation taxonomy

| Substrate | Strength | Failure mode | Appropriate role |
|---|---|---|---|
| Context-resident text | transparent, no external query | finite attention, drift under repeated summary | working state and final context |
| Append-only event/history | complete provenance, replay | noisy and expensive to query | evidence source |
| Structured relational rows | transactions, constraints, temporal queries, deletion | schema evolution and extraction cost | source of truth |
| Dense vectors | semantic candidate recall | weak exact/time/entity semantics, opaque score | derived semantic index |
| Sparse/lexical index | names, IDs, exact phrases | synonym and paraphrase gaps | complementary candidate index |
| Graph | explicit relations and multi-hop paths | entity resolution and dual-write complexity | optional derived projection |
| Parametric/latent memory | potentially low inference overhead | difficult attribution, update, audit, and deletion | outside MVP scope |

## Control-policy taxonomy

- **Developer-controlled**: deterministic rules select and inject memory. Predictable, but brittle across domains.
- **LLM-controlled**: a model chooses writes, updates, queries, or consolidation. Flexible, but nondeterministic and vulnerable to self-reinforcing error.
- **Hybrid**: model proposes candidates; deterministic policies validate scope, sensitivity, idempotency, time, and state transitions.
- **Learned policy**: write/read/forget decisions optimized from downstream reward. Research-frontier direction with data, stability, and audit challenges.

`HYPOTHESIS` — A production MVP should use hybrid control: LLMs extract/normalize uncertain meaning, while deterministic code owns authorization, idempotency, temporal invariants, status transitions, and destructive actions.

## Required lifecycle

### Write path

```text
source event
→ idempotency and trust classification
→ candidate extraction
→ shouldRemember policy
→ sensitive-data decision
→ normalization and type classification
→ entity/predicate resolution
→ semantic deduplication
→ contradiction and temporal-overlap detection
→ importance/confidence scoring
→ versioned transaction + provenance
→ derived-index update
```

The write policy must optimize more than recall. A false negative loses one useful fact; a false positive can influence every later decision and may be harder to discover.

### Read path

```text
query
→ intent/time/entity/scope understanding
→ authorization and hard metadata filters
→ semantic + lexical + entity + temporal candidates
→ score/rank fusion
→ optional reranking
→ truth-state and conflict policy
→ diversity and token-budget selection
→ provenance-bearing context
```

Candidate recall, ranking, policy filtering, context construction, and final answer quality must be observable and evaluated separately.

### Manage path

The often-missing middle includes deduplication, consolidation, versioning, decay, archival, conflict review, delete propagation, re-embedding, index rebuild, and audit. Without it, a store’s quality monotonically degrades even if its retriever never changes.

## What current frameworks establish

This table compares framework-level primitives, not the full products analyzed in the other research documents.

| Framework | Confirmed current primitive | What it does not by itself prove |
|---|---|---|
| OpenAI API / Agents SDK | conversation state and compaction; official cookbook pattern for structured state, session-note distillation, consolidation, conflict handling, and injection | the cookbook is a pattern, not a hosted production memory service with MemOS semantics |
| Google ADK | explicit Session/State vs `MemoryService`; session/event/direct-memory ingestion and search; multiple implementations | application-specific extraction quality, temporal truth, deletion workflow, and benchmark superiority |
| AutoGen | extensible `Memory` protocol and context update; simple list and retrieval-oriented integrations | a complete write-management policy or versioned truth model |
| CrewAI | `remember`, `recall`, fact extraction, recency/semantic/importance scoring, and hierarchical scopes in current docs | correctness under contradiction, temporal queries, tenant isolation, or reproducible benchmark gains |
| Semantic Kernel | experimental agent-memory providers, including Mem0 and short-term whiteboard extraction | stable API or independent memory engine; docs explicitly call the feature experimental |
| Anthropic guidance | compaction, structured note-taking, memory tool, and subagent patterns for long-horizon context | general long-term user-memory lifecycle and database semantics |

Primary sources: [OpenAI long-term memory notes](https://developers.openai.com/cookbook/examples/agents_sdk/context_personalization), [Google ADK Memory](https://adk.dev/sessions/memory/), [AutoGen Memory](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/memory.html), [CrewAI Memory](https://docs.crewai.com/en/concepts/memory), [Semantic Kernel agent memory](https://learn.microsoft.com/en-us/semantic-kernel/frameworks/agent/agent-memory), [Anthropic context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents).

## 2025–2026 direction of travel

The following are research interpretations, not claims that every production system already implements them.

1. `INFERRED` — Across the reviewed papers, the research vocabulary is moving from storage/retrieval toward **formation, evolution, retrieval, and learned/agentic control** ([Memory in the Age of AI Agents](https://arxiv.org/abs/2512.13564), [From Storage to Experience](https://arxiv.org/abs/2605.06716)).
2. `INFERRED` — Across the reviewed framework documentation, session state is increasingly separated from longer-lived memory and exposed through pluggable services or structured scopes (ADK, AutoGen, CrewAI sources above).
3. `INFERRED` — The reviewed evaluation landscape is expanding beyond a single-session “needle” into multi-session recall, updates, temporal reasoning, abstention, and agentic utility; see [benchmark analysis](06-memory-benchmark-analysis.md).
4. `UPSTREAM-REPORTED` — MINJA's authors report memory injection through ordinary interaction; newer papers report delayed/sleeper poisoning and persistent prompt injection. MemOS has not reproduced these attacks, but the independent reports establish concrete threat hypotheses ([MINJA](https://arxiv.org/abs/2503.03704), [Sleeper Memory Poisoning](https://arxiv.org/abs/2605.15338), [Bad Memory](https://arxiv.org/abs/2607.14611)).
5. `INFERRED` — Production differentiation is shifting from “which vector database” to policies and evidence: write quality, temporal evolution, authorization, deletion, evaluation, and debugging.

## Hardest engineering problems

### 1. Selective writing under asymmetric risk

Write too little and the system forgets; write too much and retrieval precision, storage, privacy exposure, and attack surface worsen. Thresholds must vary by memory type and downstream consequence.

### 2. Identity, deduplication, and contradiction are coupled

“I like coffee” and “latte is my usual order” may be overlapping rather than identical. “I moved to Hangzhou” conflicts with “I live in Shanghai” only after entity, predicate, scope, and time are resolved. Embedding similarity cannot own this decision.

### 3. Time has multiple meanings

The source message time, described event time, business-valid interval, extraction time, and system-transaction time differ. A production answer may ask “what was believed then?” or “what do we now believe was true then?” These require versioned temporal semantics.

### 4. Asynchronous reliability without invisible loss

Extraction is expensive and failure-prone, so it should not usually extend chat latency. The resulting at-least-once pipeline requires a durable outbox, idempotency keys, retry taxonomy, dead-letter/replay tooling, and a user-visible materialization state.

### 5. Retrieval is a constrained decision, not nearest neighbors

High recall candidates must be filtered by tenant, user/agent visibility, truth status, requested time, and sensitivity before reranking and token budgeting. Score calibration and fusion require labeled evaluation data.

### 6. Error amplification and explainability

One false extracted rule can be repeatedly retrieved and appear increasingly authoritative. Every answer needs traceability from injected memory to version to source evidence, plus tooling to diff and replay memory state.

### 7. Deletion spans a dataflow

Deleting one memory may require changing the relational record, embeddings, lexical/graph projections, caches, snapshots, queued jobs, and backups under an explicit retention policy. A successful API response cannot mean only “row deleted.”

### 8. Multi-agent consistency and isolation

Shared memory introduces concurrent updates and leakage; fully private stores prevent useful transfer. The system needs scopes/ACLs, optimistic conflict checks, provenance, and explicit promotion from private to shared knowledge.

### 9. Evaluation is confounded by models

Extraction, embedding, reranking, and answer models may all drift. Without pinned versions, prompts, seeds where supported, run manifests, and component metrics, a reported gain cannot be attributed to the memory architecture.

### 10. Scaling depends on the workload, not a slogan

“Millions of users” does not imply Kafka, a graph database, or a dedicated vector store. Required components depend on writes per turn, memories per user, retention, fan-out, freshness, query shape, and SLOs. MemOS will create load profiles before introducing infrastructure.

## Security and privacy implications

`UPSTREAM-REPORTED` — MINJA reports that normal query interaction can inject malicious records into an agent memory bank, and 2026 papers report persistence across later sessions. MemOS has not reproduced the attacks; their exact rates are environment-specific and are not transplanted into project claims.

Initial controls to evaluate:

- label every source by trust channel and actor;
- never treat recalled text as system instruction;
- separate factual content from executable/procedural instructions;
- require stronger evidence and/or review for procedural memory;
- run sensitive-data policy before durable write;
- keep tenant/user/agent authorization as a hard pre-retrieval filter;
- preserve source citations and confidence in context;
- support quarantine, invalidation, and replay after a poisoned write;
- include adversarial write/retrieve/use stages in benchmark cases.

## Landscape conclusions

- `CONFIRMED` — No single reviewed framework source establishes the complete production lifecycle required by MemOS.
- `INFERRED` — The useful system boundary is a **versioned memory control plane plus retrieval data plane**, not a vector-store wrapper.
- `HYPOTHESIS` — PostgreSQL plus pgvector and built-in lexical search can implement the first correct vertical slice; specialized stores should be scale-out projections.
- `HYPOTHESIS` — Temporal update/contradiction cases and memory abstention will separate MemOS more clearly from a pure-vector baseline than simple recall alone.
- `HYPOTHESIS` — Write-path precision, provenance, and safe invalidation will matter at least as much as retrieval Recall@K for long-running quality.

## Source ledger

All accessed 2026-08-26 unless the source itself carries a publication date.

- [Memory in the Age of AI Agents, arXiv:2512.13564](https://arxiv.org/abs/2512.13564)
- [Memory for Autonomous LLM Agents, arXiv:2603.07670](https://arxiv.org/abs/2603.07670)
- [From Storage to Experience, arXiv:2605.06716](https://arxiv.org/abs/2605.06716)
- [A Survey on the Memory Mechanism of LLM-based Agents, arXiv:2404.13501](https://arxiv.org/abs/2404.13501)
- [OpenAI: Context Engineering for Personalization](https://developers.openai.com/cookbook/examples/agents_sdk/context_personalization)
- [OpenAI: Conversation state](https://developers.openai.com/api/docs/guides/conversation-state)
- [OpenAI: Compaction](https://developers.openai.com/api/docs/guides/compaction)
- [Google ADK: Memory](https://adk.dev/sessions/memory/)
- [Microsoft AutoGen: Memory and RAG](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/memory.html)
- [CrewAI: Memory](https://docs.crewai.com/en/concepts/memory)
- [Semantic Kernel: Using memory with agents](https://learn.microsoft.com/en-us/semantic-kernel/frameworks/agent/agent-memory)
- [Anthropic: Effective context engineering for AI agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [Chroma: Context Rot](https://www.trychroma.com/research/context-rot)
- [A Practical Memory Injection Attack against LLM Agents, arXiv:2503.03704](https://arxiv.org/abs/2503.03704)
- [Hidden in Memory, arXiv:2605.15338](https://arxiv.org/abs/2605.15338)
- [Bad Memory, arXiv:2607.14611](https://arxiv.org/abs/2607.14611)
