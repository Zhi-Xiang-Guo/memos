# Zep / Graphiti pinned source audit

Audit cutoff: **2026-08-26**.

| Repository | Pinned commit | Role in this audit | License evidence |
|---|---|---|---|
| `getzep/graphiti` | [`993e081a6d7948a0d8851c12a5fbdbeb49fed862`](https://github.com/getzep/graphiti/commit/993e081a6d7948a0d8851c12a5fbdbeb49fed862) | Current open-source ingestion, temporal graph model, persistence adapters, and retrieval | [Apache-2.0](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/LICENSE) |
| `getzep/zep` | [`7de18dfa14da532cb782a0a14ae329e9a28b23d9`](https://github.com/getzep/zep/commit/7de18dfa14da532cb782a0a14ae329e9a28b23d9) | Product examples/integrations and deprecated Community Edition boundary | [Apache-2.0](https://github.com/getzep/zep/blob/7de18dfa14da532cb782a0a14ae329e9a28b23d9/LICENSE) |

## Audit rules and product boundary

- `CONFIRMED`: visible in the pinned source or linked first-party documentation.
- `INFERRED`: direct engineering consequence of pinned behavior, not an upstream guarantee.
- `HYPOTHESIS`: requires experiment.
- `N/E`: no evidence in the audited boundary.

`CONFIRMED` — Zep Community Edition is deprecated and moved to [`legacy/`](https://github.com/getzep/zep/tree/7de18dfa14da532cb782a0a14ae329e9a28b23d9/legacy). The current managed Zep Context Graph server is proprietary; its source is not present in either pinned repository ([Zep README](https://github.com/getzep/zep/blob/7de18dfa14da532cb782a0a14ae329e9a28b23d9/README.md)).

Accordingly, this file audits **Graphiti source behavior**. Zep Cloud API behavior is documented in [the product analysis](../research/05-zep-analysis.md), but Cloud implementation details—including transactions, queues, isolation, ranking, retention, and SLAs—are `N/E` here.

## Core persisted model

| Object | Pinned source fields | Audit interpretation |
|---|---|---|
| Base `Node` | `uuid`, `name`, `group_id`, `labels`, `created_at` ([source](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/nodes.py#L93-L100)) | `group_id` partitions a graph/query; it is not evidence of a complete authorization boundary |
| `EpisodicNode` | source, source description, content, `valid_at`, entity-edge references, metadata ([source](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/nodes.py#L318-L332)) | Raw/structured evidence layer with real-world reference time |
| `EntityNode` | name embedding, summary, dynamic attributes ([source](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/nodes.py#L499-L504)) | Canonicalized semantic projection, resolved probabilistically |
| `EntityEdge` | name, fact, embedding, source/target IDs, episode IDs, `created_at`, `expired_at`, `valid_at`, `invalid_at`, attributes ([source](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/edges.py#L263-L285)) | Versioned, attributable relation fact with transaction and valid-time dimensions |

`CONFIRMED` — The edge time fields encode intervals; there is no first-class truth-status enum in this model. `expired_at` records when the graph learned a fact was superseded, whereas `valid_at`/`invalid_at` describe asserted real-world validity.

`N/E` — No built-in TTL, decay, access-frequency, importance, authority, sensitivity, consent, legal-hold, or retention-policy field appears in the audited `EntityEdge` model.

## `add_episode` call graph

Entry point: [`Graphiti.add_episode`, `graphiti_core/graphiti.py:980–1223`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L980-L1223).

```text
Graphiti.add_episode
├─ validate types, timestamps, and group
├─ driver.retrieve_episodes(reference_time, last_n)
├─ construct/reuse EpisodicNode
│  ├─ created_at = utc_now()
│  └─ valid_at = reference_time
├─ extract_nodes(...)
├─ resolve_extracted_nodes(...)
├─ _extract_and_resolve_edges(...)
│  ├─ extract_edges(...)
│  ├─ resolve_edge_pointers(...)
│  └─ resolve_extracted_edges(...)
├─ extract_attributes_from_nodes(...) when new edges exist
├─ _process_episode_data(...)
│  ├─ build_episodic_edges(...)
│  ├─ optionally clear raw episode content
│  └─ add_nodes_and_edges_bulk(...)
└─ optionally update communities
```

### Phase 1: context and episode creation

`CONFIRMED` — The method validates/reconciles the supplied group, loads prior episodes before extraction, and creates an episodic node with separate ingestion and reference times ([source](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L1064-L1112)). Prior episode content becomes context for the extraction prompt.

`CONFIRMED` — The docstring recommends enqueueing ingestion and awaiting episodes sequentially to preserve chronology ([source](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L1052-L1059)). This is an explicit caller-side ordering requirement.

`INFERRED` — Without per-subject serialization or optimistic version checks, concurrent episode writes can extract and resolve against the same stale predecessor graph.

### Phase 2: node extraction and entity resolution

`CONFIRMED` — `extract_nodes` builds an LLM prompt from the current episode, previous episodes, and allowed entity types, parses entity candidates, and collapses exact normalized duplicates within the extracted set ([`extract_nodes`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/node_operations.py#L70-L149), [exact duplicate collapse](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/node_operations.py#L336-L384)).

`CONFIRMED` — Resolution embeds candidate names and searches for existing nodes with a cosine candidate limit of 15 and minimum score `0.6`; candidates then feed an LLM resolution step ([semantic candidate search](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/node_operations.py#L418-L450), [resolution orchestration](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/node_operations.py#L627-L760)).

`INFERRED` — Entity identity is candidate- and model-dependent. An alias missing the top candidate set or an incorrect resolution response can split or merge identities; graph traversal then propagates that error.

`CONFIRMED` — The repository also contains a combined node/edge extraction helper, but the audited `add_episode` entry point calls `extract_nodes` and later extracts edges through `_extract_and_resolve_edges`. The mere presence of the combined helper is not evidence that this normal path uses it.

### Phase 3: edge extraction, duplicate resolution, and contradiction handling

`CONFIRMED` — [`_extract_and_resolve_edges`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L631-L678) extracts relations, rewrites their endpoints to resolved node identities, and invokes `resolve_extracted_edges`.

`CONFIRMED` — [`resolve_extracted_edges`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/edge_operations.py#L325-L535):

1. collapses exact same-episode duplicates by resolved endpoints and normalized fact;
2. embeds extracted facts;
3. fetches existing edges between the same resolved endpoint nodes;
4. performs hybrid searches for duplicate and broader invalidation candidates;
5. resolves edges, with bounded concurrency, through the per-edge LLM path.

`CONFIRMED` — [`resolve_extracted_edge`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/edge_operations.py#L623-L847) has a fast exact-duplicate path; otherwise it asks the LLM for duplicate and contradiction indices, extracts attributes/timestamps, reuses a duplicate edge or creates a new edge, and applies invalidations.

`CONFIRMED` — [`resolve_edge_contradictions`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/edge_operations.py#L538-L573) closes an older overlapping edge by setting its `invalid_at` to the new edge's `valid_at` and `expired_at` to `utc_now()`. [`_extract_edge_timestamps`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/utils/maintenance/edge_operations.py#L576-L620) obtains valid/invalid timestamps through an LLM and parses them.

`INFERRED` — This is preservation-oriented conflict management, not a deterministic truth engine. Its correctness is the product of candidate recall × entity correctness × LLM duplicate/contradiction classification × temporal parsing.

### Phase 4: provenance and persistence

`CONFIRMED` — [`_process_episode_data`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L680-L733) builds episode-to-entity edges, can clear raw episode content when `store_raw_episode_content` is false, and calls `add_nodes_and_edges_bulk` for resolved nodes, episode, relation edges, invalidated edges, and provenance edges. Optional saga links are added in this phase.

`CONFIRMED` — Graphiti exposes driver implementations for [Neo4j, FalkorDB, Kuzu, and Neptune](https://github.com/getzep/graphiti/tree/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/driver). Backend behavior is not interchangeable by assumption; query features, consistency, indexing, durability, and deployment properties must be tested for the selected adapter.

`N/E` — No application transaction spans prior-context reads, LLM extraction/resolution, all graph writes, and optional community updates. `add_nodes_and_edges_bulk` is a driver operation, not evidence of end-to-end exactly-once ingestion.

`INFERRED` — A failure after partial projection or a caller retry can leave missing communities, repeated episodes, duplicate facts, or incorrect invalidation unless the application supplies idempotent event IDs, ordered work, retry state, and reconciliation.

## Search call graph

### Convenience API

```text
Graphiti.search(query, group_ids?, center_node_uuid?, num_results?)
├─ no center node → EDGE_HYBRID_SEARCH_RRF
└─ center node    → EDGE_HYBRID_SEARCH_NODE_DISTANCE
   ↓
search_(query, config, ...)
→ graphiti_core.search.search(...)
→ configured edge/node/episode/community candidate searches
→ configured reranker
→ SearchResults
```

`CONFIRMED` — The convenience and advanced entry points are in [`graphiti.py:1527–1629`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L1527-L1629). `search_` defaults to `COMBINED_HYBRID_SEARCH_CROSS_ENCODER`, while `search` defaults to RRF edge search unless node-distance routing is requested. These defaults are different and should be recorded in experiments.

### Candidate generation and reranking

`CONFIRMED` — The search orchestrator embeds the query when configured signals need it and dispatches configured edge, node, episode, and community searches concurrently ([`search`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/search/search.py#L98-L250)).

`CONFIRMED` — [`edge_search`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/search/search.py#L253-L461) can collect BM25, cosine, and BFS candidates at an over-fetched limit and then apply RRF, MMR, cross-encoder, node-distance, or episode-mention reranking. The cross-encoder route first makes an RRF shortlist.

`CONFIRMED` — Recipe definitions and their signal/reranker combinations are pinned in [`search_config_recipes.py`](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/search/search_config_recipes.py#L33-L153).

`INFERRED` — “Graph search” should be evaluated as candidate-union and reranking components, not as one indivisible feature. Log the recipe, per-signal candidates/scores, shortlist size, graph hops, model, prompt, and token budget.

`N/E` — The audited core call chain does not enforce a final application context token budget, provenance citation format, tenant authorization policy, or contradictory-evidence presentation rule.

## Delete and retention audit

`CONFIRMED` — Graphiti has node/edge deletion primitives and an episode-removal path, but projections are distributed: episodes link to nodes/edges, edges can cite multiple episodes, and communities can summarize graph state. Deleting one episode is therefore not equivalent to deleting all facts it once influenced.

`CONFIRMED` — `store_raw_episode_content=False` clears raw content before persistence but preserves derived graph artifacts ([source](https://github.com/getzep/graphiti/blob/993e081a6d7948a0d8851c12a5fbdbeb49fed862/graphiti_core/graphiti.py#L680-L733)). This is data minimization, not complete erasure.

`N/E` — No core-level proof of cascading erasure across graph nodes, embeddings, community summaries, caches, logs, replicas, and backups was found. No TTL/decay/retention scheduler is represented by the audited core edge model.

## Concurrency and consistency findings

| Finding | Grade | Consequence |
|---|---|---|
| Previous episodes and graph candidates are read before new graph writes | `CONFIRMED` | Two concurrent writes can make decisions from the same stale state |
| Sequential episode processing is recommended in the public docstring | `CONFIRMED` | Ordering is a caller responsibility, not an invisible library guarantee |
| LLM calls occur between graph reads and persistence | `CONFIRMED` | The resolution window is long and non-transactional |
| Duplicate/contradiction candidates are bounded retrieval results | `CONFIRMED` | Missed candidates can remain parallel live facts |
| A stable external idempotency key is enforced by `add_episode` | `N/E` | Caller retries must be governed by MemOS |
| Cross-store exactly-once visibility is guaranteed | `N/E` | Use an authoritative transaction + outbox/projection state |
| Per-tenant authorization is guaranteed by `group_id` | `N/E` | Always enforce ACL/tenant scope in the service and database |

## Security audit boundary

`CONFIRMED` — Episode content is placed into extraction prompts and model-produced entities/facts become durable graph data when persistence succeeds. The code path therefore crosses an untrusted-content → LLM → persistent-state boundary.

`N/E` — The reviewed path does not establish a policy engine for prompt-injection detection, durable-memory admission, trust/taint propagation, PII/sensitivity classification, consent, or human review. Schema validation alone does not answer whether extracted content is safe to remember.

`INFERRED` — MemOS should store source trust and extraction provenance, quarantine suspicious candidates, exclude tool/web instructions from durable rules by default, and test cross-tenant exfiltration and memory poisoning.

## Reproduction checklist

To reproduce or compare this audit:

1. Check out exactly `getzep/graphiti@993e081a6d7948a0d8851c12a5fbdbeb49fed862` and record the selected graph driver/version.
2. Record LLM, embedding, and reranker provider/model snapshots, prompts, temperature, retry policy, and concurrency limit.
3. Ingest episodes in declared order with stable external event IDs and fixed `reference_time` values.
4. Capture pre/post nodes, edges, valid/invalid/expired timestamps, provenance episode IDs, and community state.
5. Test duplicate, paraphrase, alias, update, contradiction, out-of-order, backfill, retry, partial failure, concurrent write, and delete scenarios.
6. Record the exact search entry point and recipe; `search` and `search_` have different defaults.
7. Report extraction/entity/edge accuracy and candidate retrieval separately from final answer quality, latency, tokens, calls, and storage growth.
8. Never compare a managed-Zep result with Graphiti source behavior without naming the product/configuration boundary.

No performance or accuracy result was generated by this audit.
