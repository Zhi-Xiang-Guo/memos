# Zep / Graphiti analysis: product boundary, temporal graph, and MemOS lessons

Research cutoff: **2026-08-26**. The open-source review is pinned to Graphiti commit [`993e081a6d7948a0d8851c12a5fbdbeb49fed862`](https://github.com/getzep/graphiti/commit/993e081a6d7948a0d8851c12a5fbdbeb49fed862) and Zep commit [`7de18dfa14da532cb782a0a14ae329e9a28b23d9`](https://github.com/getzep/zep/commit/7de18dfa14da532cb782a0a14ae329e9a28b23d9). Code-level evidence is in [the pinned source audit](../source-analysis/04-zep-source.md).

## Evidence grades and scope

- `CONFIRMED`: directly supported by a linked official document, paper, dataset, or pinned source line.
- `INFERRED`: an engineering consequence of confirmed behavior; it is not an upstream guarantee.
- `HYPOTHESIS`: a claim MemOS must test.
- `DEPRECATED`: historical behavior or product boundary that is no longer current/supported.
- `N/E`: no evidence for the capability was found inside the stated review boundary.

Three similarly named systems must not be conflated:

| Boundary | What it is now | Source/access boundary |
|---|---|---|
| **Graphiti** | Open-source framework for building a temporally aware knowledge graph from episodes and searching it | Apache-2.0; Python source and supported graph-driver implementations are inspectable ([README](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/README.md), [license](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/LICENSE)) |
| **Zep Cloud** | Managed agent-memory/context service built around a proprietary Context Graph and SDK/API | Public behavior is documentable; server implementation, operational controls, and internal ranking are not open for source audit ([memory overview](https://help.getzep.com/v2/memory), [quickstart](https://help.getzep.com/v2/quickstart)) |
| **Zep Community Edition** | Historical self-hosted Zep server | `DEPRECATED`: the official repository says Community Edition is no longer supported and retains it under `legacy/` ([pinned README](https://github.com/getzep/zep/blob/7de18dfa14da532cb782a0a14ae329e9a28b23d9/README.md), [legacy tree](https://github.com/getzep/zep/tree/7de18dfa14da532cb782a0a14ae329e9a28b23d9/legacy)) |

`CONFIRMED` — The current `getzep/zep` repository contains examples, integrations, and benchmark material, but it is not the source for the managed Zep server. Therefore no Cloud durability, isolation, consistency, deletion-completeness, or latency claim below is inferred from that repository.

## Executive verdict

`CONFIRMED` — Graphiti keeps raw episodes as provenance, extracts entities and factual relation edges, and represents facts with both real-world validity time (`valid_at`/`invalid_at`) and system observation time (`created_at`/`expired_at`). Search can combine dense similarity, BM25, graph traversal, and reranking ([paper architecture](https://arxiv.org/html/2501.13956#S2), [edge model](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/edges.py#L263-L285)).

`INFERRED` — This is a materially better model for changing facts than an append-only vector collection: a new statement can preserve the old assertion and close its valid interval instead of deleting its evidence. It also makes multi-hop/entity-centered retrieval possible.

`CONFIRMED` — Conflict decisions and timestamps depend on LLM extraction and candidate retrieval; the normal write path spans LLM calls and graph writes without an application transaction; the core edge model has no TTL, importance, access-frequency, trust, sensitivity, or authorization state; and the library explicitly recommends ordered background ingestion ([`add_episode` guidance](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L1052-L1059)).

`INFERRED` — Those gaps mean Graphiti alone does not satisfy the complete MemOS contract.

`INFERRED` — Recommendation: **adopt the episode → entity/fact projection, bitemporal fact intervals, provenance links, and hybrid candidate design; do not make probabilistic graph extraction the authoritative transaction or privacy boundary.**

## What Graphiti calls memory

`CONFIRMED` — The model has three layers:

1. **Episodes** preserve ingested text/message/JSON plus source description and reference time. They are the evidence layer.
2. **Entities and entity edges** are the semantic projection. An edge carries a natural-language fact and temporal fields; episodes link to the entities/facts they support.
3. **Communities** optionally summarize clusters for higher-level retrieval.

The paper describes this as episode, semantic entity/fact, and community subgraphs ([paper §2](https://arxiv.org/html/2501.13956#S2)); the current data classes expose episodic nodes, entity nodes, and entity edges ([nodes](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/nodes.py#L318-L504), [edges](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/edges.py#L263-L285)).

`INFERRED` — Memory is therefore a **fallible temporal projection of attributable episodes**, not the transcript itself and not a single vector row. This evidence/projection split is the strongest idea for MemOS to retain.

## Write lifecycle

### Zep Cloud API

`CONFIRMED` — A user can own multiple sessions. Messages added to a session become conversation history and, unless their role is ignored, feed user-level graph extraction; knowledge can then be recalled across that user's sessions ([users](https://help.getzep.com/v2/users), [sessions](https://help.getzep.com/v2/sessions), [add memory](https://help.getzep.com/v2/memory)). `ignore_roles` can keep selected messages in history/context while excluding them from graph construction.

`CONFIRMED` — `memory.get` uses recent messages to retrieve graph facts and returns a context string, recent messages, and raw facts. Official guidance recommends including roughly the last four to six messages because graph ingestion can take minutes ([memory retrieval](https://help.getzep.com/v2/memory)).

`INFERRED` — The API intentionally bridges an asynchronous projection with recent raw context. Applications must not assume read-after-write consistency from the graph alone; an explicit projection state or freshness fallback is required for MemOS.

### Graphiti open-source path

At the pinned commit, the normal `Graphiti.add_episode` call chain is:

```text
validate episode and group
→ load preceding episodes
→ create/reuse EpisodicNode(created_at=now, valid_at=reference_time)
→ extract_nodes
→ resolve_extracted_nodes
→ extract_edges
→ resolve_edge_pointers
→ resolve_extracted_edges
→ extract_attributes_from_nodes
→ build episode↔entity provenance edges
→ bulk-persist nodes and edges
→ optionally update communities
```

`CONFIRMED` — The exact entry point and persistence phase are visible in [`Graphiti.add_episode`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L980-L1223), with its edge helper in [`_extract_and_resolve_edges`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L631-L678) and persistence helper in [`_process_episode_data`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L680-L733).

`CONFIRMED` — Entity extraction includes prior episode context. Exact normalized duplicates in one extraction are collapsed; semantic candidates are found by embedded-name cosine search and then an LLM resolves identity ([extraction](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/node_operations.py#L70-L149), [exact collapse](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/node_operations.py#L336-L384), [semantic candidates](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/node_operations.py#L418-L450)).

`CONFIRMED` — Edge resolution first collapses exact duplicates in the episode, searches existing facts, and asks an LLM to identify duplicates and contradictions. Candidate sets include facts between resolved endpoint nodes plus hybrid-search candidates; timestamps and attributes are then extracted ([edge resolution](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/edge_operations.py#L325-L535), [single-edge resolution](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/edge_operations.py#L623-L847)).

## Temporal updates and contradiction semantics

`CONFIRMED` — `EntityEdge` contains:

- `created_at`: when this graph record was created;
- `expired_at`: when Graphiti learned that the record was superseded/invalidated;
- `valid_at` and `invalid_at`: the asserted real-world validity interval;
- `episodes`: provenance episode IDs;
- `fact`, endpoints, embedding, and extensible attributes.

This is a bitemporal representation, not a `CURRENT/HISTORICAL` enum ([model](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/edges.py#L263-L285); [Zep fact fields](https://help.getzep.com/v2/facts)).

`CONFIRMED` — When a newer contradictory fact overlaps an older edge, `resolve_edge_contradictions` can set the older edge's `invalid_at` to the new fact's `valid_at` and `expired_at` to the current system time ([contradiction update](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/edge_operations.py#L538-L573)). Timestamp extraction itself is an LLM operation followed by parsing ([timestamp extraction](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/edge_operations.py#L576-L620)).

`INFERRED` — Graphiti preserves history better than destructive overwrites, but “current truth” is only as sound as entity resolution, candidate recall, contradiction classification, and date parsing. A missed duplicate or contradiction can create parallel live edges; an incorrect time can close the wrong interval.

`HYPOTHESIS` — MemOS should compare Graphiti-style LLM resolution with deterministic predicate-specific transition rules. The likely winning design is additive evidence plus deterministic state transitions where a schema is known, with LLM classification recorded as an auditable proposal elsewhere.

## Retrieval and context construction

`CONFIRMED` — Zep Cloud documents graph search over edges, nodes, or episodes, using semantic similarity and BM25 with optional graph traversal and rerankers such as RRF, MMR, node distance, episode mentions, and cross-encoder ranking ([search guide](https://help.getzep.com/v2/searching-the-graph), [API reference](https://help.getzep.com/v2/sdk-reference/graph/search)). Cloud ranking internals beyond the documented API are `N/E` because the service source is unavailable.

`CONFIRMED` — In current Graphiti source, the convenience `search` chooses edge hybrid search with RRF by default, or a node-distance recipe when a center node is supplied. The lower-level `search_` defaults to a combined hybrid cross-encoder configuration ([entry points](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L1527-L1629), [recipes](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/search/search_config_recipes.py#L33-L153)).

`CONFIRMED` — The search orchestrator concurrently obtains candidates from configured edge/node/episode/community sources. Edge search over-fetches BM25, cosine, and BFS candidates and then applies the selected reranker; the cross-encoder path first creates an RRF shortlist ([search orchestration](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/search/search.py#L98-L250), [edge search](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/search/search.py#L253-L461)).

`INFERRED` — Graph traversal is a candidate source, not evidence that every query requires graph reasoning. MemOS should measure independent semantic, lexical, temporal, and entity/graph candidate recall before paying for a reranker.

`N/E` — No application-level context composer with token budgeting, evidence citation rendering, sensitivity filtering, or conflicting-fact presentation was found in the reviewed Graphiti core path. Zep Cloud returns constructed context, but its internal construction policy cannot be audited.

## Persistence, scoping, and ordering

`CONFIRMED` — The pinned Graphiti tree contains drivers for Neo4j, FalkorDB, Kuzu, and Amazon Neptune; capabilities differ by backend. Official overview documentation currently highlights Neo4j, FalkorDB, and Neptune ([drivers](https://github.com/getzep/graphiti/tree/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/driver), [overview](https://help.getzep.com/graphiti/getting-started/overview)). Backend-specific durability, backup, indexing, transaction isolation, and operational limits remain operator responsibilities.

`CONFIRMED` — Nodes and edges carry `group_id`, and `add_episode` verifies group consistency. This is a query/graph partitioning key ([node base model](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/nodes.py#L93-L100), [`add_episode`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L980-L1112)).

`N/E` — `group_id` alone is not evidence of cryptographically or operationally robust tenant isolation, authorization, encryption/key separation, quota enforcement, or row-level security. Those controls must live in MemOS's service and store boundary.

`CONFIRMED` — The `add_episode` docstring recommends a background queue and awaiting each episode sequentially to preserve chronological order. The extraction/resolution/persistence sequence contains no single application-level transaction across LLM work and all graph mutations ([guidance](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L1052-L1059)).

`INFERRED` — Concurrent writes for one subject can resolve against stale graph state and race to create or invalidate edges. MemOS needs a per-subject ordering key, idempotent source event ID, optimistic version or serialization policy, projection outbox, retries, and reconciliation.

## Deletion, privacy, and retention

`CONFIRMED` — Zep Cloud documentation distinguishes session deletion from user deletion: deleting a session removes that session/messages but does not promise deletion of knowledge already derived into the user's graph; deleting a user is the documented right-to-be-forgotten operation that removes the user's sessions and artifacts ([sessions](https://help.getzep.com/v2/sessions), [users](https://help.getzep.com/v2/users)). Graph objects also have separate deletion operations.

`INFERRED` — Deleting source history and deleting projections are different lifecycle operations. A complete erasure proof must enumerate raw messages, episodic nodes, entity/fact edges, embeddings, communities/summaries, caches, logs, backups, and downstream indexes.

`CONFIRMED` — Graphiti can omit stored raw episode content through `store_raw_episode_content`, while retaining the derived graph. That reduces retained raw text but does not make extraction safe or reversible ([persistence path](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L680-L733)).

`N/E` — The reviewed Graphiti core edge model has no built-in TTL, access-based decay, importance score, PII classification, consent state, legal hold, or complete erasure ledger. Cloud compliance claims are vendor claims and were not independently validated in this research.

## Failure and security gaps

| Risk | Evidence grade | MemOS implication |
|---|---|---|
| Entity/edge extraction is model-dependent | `CONFIRMED` | Validate structured output, record model/prompt/version, retain provenance, and permit replay |
| Duplicate/conflict recognition is candidate-dependent | `CONFIRMED` | Add deterministic uniqueness/transition rules and reconciliation scans |
| Temporal parsing is model-dependent | `CONFIRMED` | Preserve original expression and confidence; never silently replace observed time with inferred event time |
| Ordered ingestion is caller responsibility | `CONFIRMED` | Serialize by subject or use optimistic versioning and deterministic retries |
| Prompt-injected episode can become durable graph state | `INFERRED`; no admission defense found | Add trust/taint/sensitivity policy before projection; test poisoning and exfiltration |
| Graph partition key equals authorization | `N/E` | Enforce tenant/user ACLs outside Graphiti and include them in every query/write |
| Automatic TTL/decay/importance | `N/E` | Model these as explicit policy fields and jobs, not retrieval folklore |
| Cloud internal implementation and latency/SLA | `N/E` | Treat documented performance/compliance as vendor claims; independently load-test and contractually verify |

## What MemOS should adopt

1. `INFERRED` — Adopt the confirmed upstream pattern of retained append-only source episodes linked to projections; keep governed erasure as an explicit exception.
2. `INFERRED` — Adopt the confirmed upstream separation between real-world valid intervals and system observation/transaction time.
3. `INFERRED` — Preserve old facts by closing intervals or changing truth state; do not destructively overwrite history.
4. `INFERRED` — Use entity/edge graph as a rebuildable projection, while a transactional relational event/version store remains authoritative.
5. `INFERRED` — Union semantic, lexical, temporal, and graph candidates, then rerank under a fixed evidence-token budget.
6. `INFERRED` — Serialize a subject's projection work and expose freshness, failure, and replay state.

## What MemOS should not copy without evidence

- `N/E` — Do not infer deterministic truth from an LLM contradiction decision.
- `N/E` — Do not treat `group_id` as a complete tenant security model.
- `N/E` — Do not treat graph record deletion as proof that all derived copies and backups are erased.
- `N/E` — Do not assume graph retrieval improves simple recall; benchmark it as an ablation at equal candidate and token budgets.
- `N/E` — Do not use the Zep paper's reported scores as MemOS results. The paper is vendor-authored, reflects historical product/configuration, and has not been reproduced here ([paper](https://arxiv.org/html/2501.13956)).

## MemOS validation hypotheses

- `HYPOTHESIS` — Bitemporal intervals reduce stale-answer and update errors on LongMemEval knowledge-update/temporal tracks relative to vector-only retrieval.
- `HYPOTHESIS` — Graph expansion improves multi-hop recall but can reduce context precision through entity-resolution errors.
- `HYPOTHESIS` — A deterministic predicate transition layer plus LLM proposals outperforms unconstrained LLM invalidation on audited contradictions.
- `HYPOTHESIS` — Recent raw-message fallback hides asynchronous projection lag but risks inconsistent answers unless freshness is surfaced.
- `HYPOTHESIS` — Episode provenance improves deletion repair, human debugging, and poisoning containment enough to justify its storage cost.

## Primary-source index

- Zep paper: [Temporal Knowledge Graph Architecture for Agent Memory](https://arxiv.org/html/2501.13956) (paper page reports CC BY-NC-SA 4.0).
- Graphiti official docs: [overview](https://help.getzep.com/graphiti/getting-started/overview), [adding episodes](https://help.getzep.com/graphiti/core-concepts/adding-episodes).
- Zep Cloud official docs: [memory](https://help.getzep.com/v2/memory), [search](https://help.getzep.com/v2/searching-the-graph), [facts](https://help.getzep.com/v2/facts), [sessions](https://help.getzep.com/v2/sessions), [users](https://help.getzep.com/v2/users).
- Pinned Graphiti source: [`993e081...`](https://github.com/getzep/graphiti/tree/993e081a6d7948a0d8851c12a5fbdbeb49fed862), Apache-2.0.
- Pinned Zep repository: [`7de18df...`](https://github.com/getzep/zep/tree/7de18dfa14da532cb782a0a14ae329e9a28b23d9), Apache-2.0 for repository code; this does not license or expose the Cloud server.
