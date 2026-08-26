# LangGraph Memory analysis: persistence primitives, application policy, and limits

Research cutoff: **2026-08-26**. Primary sources are current official LangChain/LangGraph documentation and the official repository pinned at commit [`38031739e551638e373fb553453256c23feeb41f`](https://github.com/langchain-ai/langgraph/commit/38031739e551638e373fb553453256c23feeb41f). Exact source call chains are in [the LangGraph source audit](../source-analysis/03-langgraph-source.md).

## Fact grades and boundary

- `CONFIRMED`: directly supported by linked official docs or pinned source.
- `INFERRED`: our engineering interpretation of confirmed behavior.
- `HYPOTHESIS`: must be validated in MemOS.
- `N/E`: no evidence of the requested capability in the reviewed boundary.

The most important boundary is:

> **LangGraph supplies persistence and storage primitives; it is not an opinionated end-to-end long-term-memory product.**

`CONFIRMED` — official docs define short-term memory as thread-scoped graph state persisted by a **checkpointer**, and long-term memory as arbitrary JSON documents persisted under custom namespaces by a **Store** ([memory overview](https://docs.langchain.com/oss/python/concepts/memory), [persistence](https://docs.langchain.com/oss/python/langgraph/persistence)).

`CONFIRMED` — the docs discuss semantic, episodic, and procedural memory plus hot-path/background formation, but explicitly present them as application design choices. The base framework does not automatically extract, classify, deduplicate, resolve conflicts, or construct a profile merely because a Store is configured ([memory overview](https://docs.langchain.com/oss/python/concepts/memory)).

`CONFIRMED` — LangMem is a separate companion library whose managers can extract/update/remove/consolidate memories and persist them through LangGraph’s Store. Those higher-level policies must not be attributed to `BaseStore` or a checkpointer ([LangMem concepts](https://langchain-ai.github.io/langmem/concepts/conceptual_guide/)).

## Executive verdict

`CONFIRMED` — LangGraph provides the following orchestration primitives:

- durable per-thread state snapshots at graph-step boundaries;
- pending writes and restart/fault-tolerance semantics;
- time-travel/state-history debugging;
- cross-thread namespace/key/value storage;
- optional metadata filtering, semantic indexing, TTL by supporting adapter, and checkpoint encryption;
- explicit subgraph persistence modes for multi-agent graphs.

`INFERRED` — these primitives solve **where graph state and arbitrary memory documents live**, not **what is true, worth remembering, safe, current, or relevant**. A production memory service still needs a domain model and lifecycle above them.

Recommendation: **use LangGraph as an orchestration/control-flow option and benchmark substrate, not as MemOS’s memory semantics or sole source of truth.**

## Two distinct persistence scopes

| Scope | LangGraph primitive | Identity | Typical contents | Lifecycle |
|---|---|---|---|---|
| Short-term / thread | `BaseCheckpointSaver` | `thread_id`, `checkpoint_ns`, `checkpoint_id` | messages, summaries, tool state, arbitrary graph channels | snapshot at graph steps; retrieve/history/fork/delete thread |
| Long-term / cross-thread | `BaseStore` | namespace tuple + key | arbitrary JSON document | explicit get/search/put/delete by application code |

`CONFIRMED` — checkpointers alone cannot share memory across threads; this is the documented motivation for Store ([persistence guide](https://docs.langchain.com/oss/python/langgraph/persistence#memory-store)).

`INFERRED` — resuming one thread is conversation continuity, not automatically a durable user profile. Conversely, putting one JSON record in Store is persistence, not automatically a trustworthy memory.

## What LangGraph calls memory

### Short-term memory

`CONFIRMED` — graph state is read at each step and persisted through a checkpointer when the graph is invoked or completes a step. With `MessagesState`, the `add_messages` reducer appends by default and replaces messages with matching IDs ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/langgraph/langgraph/graph/message.py#L60-L127)).

Official management patterns—trim, permanently delete, or summarize messages—are application nodes/reducers layered over state; the checkpointer does not decide when information has become irrelevant ([add/manage memory guide](https://docs.langchain.com/oss/python/langgraph/add-memory)).

### Long-term memory

`CONFIRMED` — `BaseStore` stores JSON-serializable dictionaries keyed by `(namespace, key)`. `Item` adds `created_at` and `updated_at`; `search` may filter and optionally use a natural-language query; namespaces may encode user, organization, application, agent, or any custom hierarchy ([Store docs](https://docs.langchain.com/oss/python/langgraph/persistence#memory-store)).

`INFERRED` — the Store is best understood as a pluggable scoped document store, not a fact model. Whether a value represents one preference, a whole profile, an episode, a prompt, or a task is entirely an application decision.

### Conversation history and vector search boundaries

`CONFIRMED` — LangGraph separates thread state from cross-thread Store data. It also distinguishes semantic memory (facts/knowledge) from semantic search (an embedding retrieval technique) ([memory overview](https://docs.langchain.com/oss/python/concepts/memory#semantic-memory)).

`INFERRED` — this supports two project principles:

- checkpointed history is evidence and resumable execution state, not selected long-term truth;
- vector similarity is an optional Store access path, not a write policy, version model, authorization decision, or conflict resolver.

## Product capability versus framework primitive

| Capability | LangGraph OSS primitive | Agent Server / LangSmith product behavior | Application remains responsible for |
|---|---|---|---|
| Thread persistence | Checkpointer interface | Managed automatically; production backing store | state schema, retention, summary/removal policy |
| Cross-thread memory | `BaseStore` | Managed Store, PostgreSQL by default | document shape, namespace authorization, write policy |
| Semantic search | Optional `index` configuration and adapter support | Configurable managed index | query formulation, candidate fusion, threshold/rerank |
| Memory formation | Nodes/tools/background graphs can call Store | templates/integrations may help | extraction, classification, provenance, conflict |
| Encryption | Optional encrypted checkpoint serializer | enabled when configured on supported deployment | Store-data encryption scope, keys, rotation, erase |
| Evaluation | Tracing and generic offline/online evaluation | LangSmith datasets/evaluators/experiments | memory-specific gold data, metrics, adversarial cases |

`CONFIRMED` — Agent Server persists checkpoints and Store data and handles infrastructure automatically; that is a product convenience, not evidence of domain-level extraction or temporal reasoning ([Agent Server](https://docs.langchain.com/langsmith/agent-server)).

## Lifecycle coverage matrix

| Required dimension | Confirmed primitive | Gap |
|---|---|---|
| Memory definition | Thread state and namespace-scoped JSON documents | No required evidence/projection schema |
| Write flow | Node/tool explicitly calls `put`/`aput`; checkpointer saves graph state | No built-in candidate extraction/admission pipeline |
| Extraction | Conceptual docs and examples show hot-path/background LLM logic | Application/companion-library concern |
| Classification | Docs describe semantic/episodic/procedural categories | No enforced enum or type-specific invariant |
| Storage | Checkpointer + Store adapters; managed server options | Deployment-dependent durability and isolation |
| Retrieval | direct get, prefix namespace search, metadata comparisons, optional vector query | No built-in BM25/entity/temporal candidate union or reranker |
| Update | `put` under the same namespace/key overwrites | No automatic semantic update, CAS, or version lineage in base contract |
| Delete | Store `delete`; checkpointer `delete_thread`; message reducers can remove messages | No cross-store cascade/erase proof |
| TTL/decay | Optional adapter TTL in minutes since last access; refresh controls | Not supported by every adapter; no importance/type decay |
| Deduplication | Caller controls stable key; same key overwrites | No semantic deduplication or idempotency-key contract |
| Conflict resolution | Backend/batch write behavior | No truth status, merge rule, or expected-version parameter |
| Temporal reasoning | Store created/updated time; ordered checkpoint history/time travel | No event time, valid interval, as-of truth query |
| Entity linking | Namespace/JSON can contain entity fields | No canonical resolver/graph/predicate semantics |
| Context management | trim/delete/summarize patterns; arbitrary graph logic | No automatic token-budget selector |
| Prompt injection | Arbitrary JSON and message content are stored | No trust/taint/quarantine/admission firewall |
| Token optimization | Trimming, summarization, Store search, delta checkpoint channels | Policy and quality are application-dependent |
| Persistence | Postgres/SQLite and third-party adapters; managed service | Operations/backup/HA depend on adapter/deployment |
| Multi-agent | Subgraphs, per-thread state modes, shared Store namespaces | Namespace is not an ACL; concurrent semantic conflicts remain |
| Privacy | Namespace partitioning, explicit delete, optional checkpoint encryption | No PII policy, row-level authorization, or verified erase by default |
| Evaluation | LangSmith generic agent/retrieval evaluation | No built-in LoCoMo/LongMemEval/BEAM result for a memory policy |

## Write path

### Short-term state

```text
graph invocation(thread_id)
→ load latest checkpoint
→ execute node(s) in superstep
→ reducers merge channel writes
→ persist pending writes from successful tasks
→ persist checkpoint + metadata + parent link
→ next invocation resumes from checkpoint
```

`CONFIRMED` — persistence enables restart after partial failure because successful writes from other tasks in the failed superstep are retained; it also enables state history and forks ([persistence guide](https://docs.langchain.com/oss/python/langgraph/persistence)).

`INFERRED` — this is durable execution, not memory formation. If a message channel grows forever, checkpointing faithfully persists the pollution. Summary/trim/promotion into long-term memory require explicit graph logic.

### Long-term Store write

```text
application node/tool/background job
→ derive namespace from trusted runtime context
→ choose key and JSON value
→ store.put(namespace, key, value, index=?, ttl=?)
→ BaseStore validates namespace/feature support
→ adapter batch(PutOp)
→ optional embedding/index update
→ document insert/overwrite (or value=None for delete)
```

`CONFIRMED` — `Runtime` injects both trusted run context and the configured Store into nodes, allowing code to derive a user namespace without asking the model for the user ID ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/langgraph/langgraph/runtime.py#L124-L205)). This is a valuable separation between authorization context and model-controlled text.

`INFERRED` — applications should never accept a model-provided tenant/user namespace without server-side authorization. Namespace tuples organize data; they do not themselves enforce who may read them.

## Retrieval and ranking

`CONFIRMED` — the base Store API supports:

- exact `get(namespace, key)`;
- namespace-prefix `search`;
- metadata filters with equality and comparison operators;
- optional natural-language query;
- limit/offset;
- optional refresh of TTL on retrieval.

`CONFIRMED` — the reference `InMemoryStore` first filters by namespace/metadata, embeds each unique query, computes cosine similarity against indexed field vectors, max-pools multiple vectors for the same item, sorts descending, and applies offset/limit ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/memory/__init__.py#L238-L373)). Semantic indexing is disabled unless configured and in-memory data disappears with the process ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/memory/__init__.py#L136-L204)).

Limits:

- `N/E` — lexical/BM25 candidates in the base Store contract;
- `N/E` — entity resolution or graph traversal;
- `N/E` — temporal intent/as-of filtering beyond application metadata filters;
- `N/E` — score fusion across independent retrievers, reranking, diversity, authority, importance, or truth status;
- `N/E` — a context builder that proves selected items fit a token budget.

`HYPOTHESIS` — MemOS should use LangGraph only to orchestrate separate semantic, lexical, entity, and temporal retrievers, then evaluate RRF/learned fusion and optional reranking. The Store’s semantic query can be one candidate source, not the final memory algorithm.

## Update, time, conflicts, and forgetting

### Update and concurrency

`CONFIRMED` — `BaseStore.put` has namespace, key, value, optional index fields, and optional TTL. It exposes no `expected_version` or compare-and-swap parameter ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L856-L944)).

`INFERRED` — a stable key can make an application write idempotent, but it does not provide semantic deduplication or prevent lost updates. MemOS requires a transactional version row, optimistic version check, source-event idempotency key, and explicit supersession/conflict state.

### Temporal semantics

`CONFIRMED` — Store items record persistence `created_at`/`updated_at`; checkpoint history records graph-state lineage and supports execution “time travel.”

`INFERRED` — neither is domain temporal reasoning. Persistence time cannot answer “when did the user actually move?”, distinguish backfilled history, or compute valid truth at an arbitrary time. MemOS must separately model observation time, event time, `valid_from`, `valid_to`, and status.

### TTL versus forgetting

`CONFIRMED` — `PutOp.ttl` is minutes since last access, refreshed by reads/writes and deleted best-effort when expired, but only adapters declaring `supports_ttl` accept it. `BaseStore` defaults to `supports_ttl = False` ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L431-L576), [base capability](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L708-L731)).

`INFERRED` — TTL is a storage lifecycle primitive, not memory decay. Access-refreshed TTL can preserve frequently retrieved false memories and delete rarely queried critical facts. MemOS should combine explicit retention class, type, importance, access, legal/privacy policy, and archive/purge state.

## Multi-agent memory

`CONFIRMED` — subgraphs can be per-invocation, per-thread, or stateless. Per-thread mode accumulates a subagent’s state; per-invocation mode is recommended for independent parallel subagent calls; the same stateful subgraph instance cannot safely be called multiple times within one node because checkpoint namespaces conflict ([subgraph persistence](https://docs.langchain.com/oss/python/langgraph/use-subgraphs#subgraph-persistence)).

`CONFIRMED` — multiple agents can deliberately share a Store namespace or use separate namespaces.

`INFERRED` — a multi-agent production design additionally needs ownership, read/write ACLs, provenance of which agent asserted a memory, promotion rules from private to shared state, concurrency control, and poisoning containment. LangGraph makes those implementable but does not choose them.

## Privacy and security

`CONFIRMED` — checkpoint serialization can be wrapped in `EncryptedSerializer`; the included AES implementation uses authenticated EAX mode and accepts a key or `LANGGRAPH_AES_KEY` ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/checkpoint/serde/encrypted.py#L8-L80)). Official docs state managed deployments enable checkpoint encryption when configured ([persistence encryption](https://docs.langchain.com/oss/python/langgraph/persistence#encryption)).

Important limits:

- `CONFIRMED` — this serializer protects checkpoint payloads passed through it; it does not establish that every Store adapter, vector index, cache, trace, or backup uses the same encryption/erasure path.
- `INFERRED` — arbitrary memory text can carry prompt injection. Encryption protects confidentiality at rest, not semantic integrity after decryption.
- `INFERRED` — `delete_thread` and Store `delete` are separate operations. A user-wide erase requires an application-level workflow across checkpoints, Store namespaces, indexes, caches, traces, queues, and backups.

## Evaluation

`CONFIRMED` — LangSmith supports offline and online evaluation with datasets, rule/human/LLM evaluators, repetitions, and experiments; its agent guidance covers final responses, steps, and trajectories ([evaluation](https://docs.langchain.com/langsmith/evaluation), [application approaches](https://docs.langchain.com/langsmith/evaluation-approaches)).

`INFERRED` — this is an evaluation framework, not a benchmark result for “LangGraph Memory.” A graph with excellent checkpoint durability can still have poor extraction, contradiction handling, retrieval, or downstream answer accuracy.

MemOS should instrument separately:

- candidate extraction quality and write precision/recall;
- retrieval recall/NDCG/MRR and filtered authorization correctness;
- temporal/update/conflict/abstention accuracy;
- end-to-end answer/task success;
- checkpoint/store write latency, retrieval latency, tokens, and storage growth;
- fault recovery, duplicate delivery, lost-update, cross-tenant, and erase-completion tests.

## MemOS adoption decisions

| LangGraph technique | Decision | Reason |
|---|---|---|
| Explicit short-term checkpointer vs long-term Store | Adopt conceptually | Prevents conversation replay from being confused with durable memory |
| Durable graph execution and pending writes | Consider for Python evaluation/orchestration workers | Excellent workflow recovery, but Java service remains authoritative |
| Trusted runtime context for namespace | Adopt | Identity/scope must come from server context, not model output |
| Namespace + key + JSON Store | Use as adapter/prototype surface | Flexible, but insufficient as the MemOS domain model |
| Optional semantic index | Use as one baseline retriever | Does not replace hybrid retrieval or truth filtering |
| Adapter TTL | Reuse only for cache/ephemeral policies | Not equivalent to importance-aware forgetting |
| Generic overwrite/delete as memory update | Do not adopt as domain semantics | Needs versions, CAS, status, provenance, and valid time |
| Encrypted checkpoint serializer | Adopt the principle and test scope | Key rotation and all derived stores still need an explicit contract |
| LangSmith evaluation | Optional experiment/tracing layer | Useful tooling; benchmark data/metrics must remain portable and reproducible |

## Bottom line

`INFERRED` — LangGraph is valuable precisely because it does **not** pretend a Store is a complete memory system. It provides durable state, scoped documents, search hooks, and orchestration boundaries on which a real policy can be built. MemOS should preserve that separation: use framework primitives where helpful, but own extraction, provenance, temporal truth, conflict/version semantics, authorization, deletion, hybrid retrieval, and evaluation as independent infrastructure.
