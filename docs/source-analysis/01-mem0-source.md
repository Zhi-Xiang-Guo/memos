# Mem0 pinned source analysis

Research cutoff: **2026-08-26**. This is a static source audit of the Python OSS SDK, not a runtime benchmark.

## Audit identity

| Field | Value |
|---|---|
| Repository | [`mem0ai/mem0`](https://github.com/mem0ai/mem0) |
| Commit | [`39bc02330563764e7d4465f1ecff5f002d94da1`](https://github.com/mem0ai/mem0/commit/39bc02330563764e7d4465f1ecff5f002d94da1) |
| Commit time | 2026-08-24T22:22:04+05:30 |
| Package version at commit | [`mem0ai 2.0.19`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/pyproject.toml#L5-L15) |
| Audited implementation | `mem0.memory.main.Memory` (synchronous path); `AsyncMemory` mirrors it |
| Evidence method | `git ls-remote`, detached source inspection, call-site search; no Mem0 service or benchmark was executed |

Fact grades used below: `CONFIRMED` is directly visible in the pinned source; `INFERRED` is a consequence of that implementation; `HYPOTHESIS` requires an experiment. `N/E` means no implementation evidence was found in the audited OSS path.

## Executive source verdict

`CONFIRMED` — Current OSS Mem0 is no longer the two-LLM-call ADD/UPDATE/DELETE system described by the 2025 paper. Its default inferred write path is **single-call, ADD-only extraction**, followed by batch embedding, exact-text hash deduplication, vector-store insertion, a SQLite history/message side store, and an entity-matching projection. Its read path is **semantic candidates plus BM25 and entity boosts**, with an optional reranker. The official migration guide states the same breaking change ([pinned guide](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/migration/oss-v2-to-v3.mdx#L12-L23)).

`CONFIRMED` — The audited implementation has extraction batching and lightweight hybrid ranking, but no automatic conflict state, `valid_from`/`valid_to`, provenance key, idempotency key, transactional write across stores, hard privacy erase, or structured temporal reasoning.

`INFERRED` — It is a useful implementation reference but cannot supply the versioned truth model required by MemOS unchanged.

## Component map

| Concern | File | Class / function | Role |
|---|---|---|---|
| Wiring | [`mem0/memory/main.py#L487-L550`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L487-L550) | `Memory.__init__` | Creates embedder, vector store, LLM, SQLite manager, optional reranker |
| Scope | [`main.py#L314-L424`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L314-L424) | `_build_filters_and_metadata`, `_build_session_scope` | Requires one of `user_id` / `agent_id` / `run_id`; strips identity keys from free-form metadata |
| Write entry | [`main.py#L760-L877`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L760-L877) | `Memory.add` | Validates input, routes procedural vs ordinary, calls vector write pipeline |
| Extraction/write | [`main.py#L879-L1206`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L879-L1206) | `_add_to_vector_store` | Context lookup, LLM extraction, embedding, hash dedup, persistence, entity linking |
| Extraction prompt | [`prompts.py#L464-L944`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/configs/prompts.py#L464-L944) | `ADDITIVE_EXTRACTION_PROMPT` | ADD-only fact selection, attribution, textual time grounding, output schema |
| Prompt builder | [`prompts.py#L1016-L1062`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/configs/prompts.py#L1016-L1062) | `generate_additive_extraction_prompt` | Serializes summary, recent messages, existing memories, dates, custom instructions |
| Entity projection | [`entity_extraction.py#L731-L772`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/utils/entity_extraction.py#L731-L772) | `extract_entities`, `extract_entities_batch` | spaCy-based proper/quoted/topic/identifier extraction |
| Read entry | [`main.py#L1379-L1522`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1379-L1522) | `Memory.search` | Validates scope/options, performs hybrid search, optionally reranks |
| Candidate/ranking | [`main.py#L1628-L1813`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1628-L1813) | `_search_vector_store`, `_compute_entity_boosts` | Dense candidate pool, BM25 scores, entity matches, score fusion |
| Fusion formula | [`scoring.py#L16-L139`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/utils/scoring.py#L16-L139) | `get_bm25_params`, `normalize_bm25`, `score_and_rank` | Query-length sigmoid normalization and additive scoring |
| Explicit update | [`main.py#L1815-L1867`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1815-L1867), [`#L2038-L2098`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L2038-L2098) | `update`, `_update_memory` | Re-embed, overwrite vector payload, append history, rebuild entity links |
| Explicit delete | [`main.py#L1869-L1944`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1869-L1944), [`#L2100-L2128`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L2100-L2128) | `delete`, `delete_all`, `_delete_memory` | Deletes vector record, appends content-bearing history, removes entity links |
| Side persistence | [`storage.py#L11-L324`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/storage.py#L11-L324) | `SQLiteManager` | History events and rolling last-ten raw messages |
| Reranking | [`llm_reranker.py#L107-L173`](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/reranker/llm_reranker.py#L107-L173) | `LLMReranker.rerank` | One relevance-scoring LLM call per candidate, then sort |

## Write call chain

### `infer=False`: raw insertion

```text
Memory.add
→ _build_filters_and_metadata
→ _add_to_vector_store(infer=False)
→ embedding_model.embed(message.content, "add")
→ _create_memory
   → vector_store.insert(vector, UUID, payload)
   → SQLiteManager.add_history(event="ADD")
```

`CONFIRMED` — System messages are skipped; each other message becomes one independent memory with `role` and optional `actor_id` metadata ([source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L879-L914)). `_create_memory` assigns a random UUID, MD5 of the exact text, `created_at`, `updated_at`, and lemmatized text, then writes vector payload before history ([source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1961-L1991)). There is no deduplication or idempotency check on this path.

### `infer=True`: current V3 default

```text
Memory.add
→ _add_to_vector_store
  0. SQLite get_last_messages(scope, 10) + flatten new messages
  1. embed whole new exchange + vector search top 10 existing memories
  2. one LLM call with ADDITIVE_EXTRACTION_PROMPT
  3. batch-embed extracted memory texts
  4. lemmatize + create UUID/payload
  5. exact MD5 dedup against retrieved top 10 and current batch
  6. vector_store.insert(batch); per-record fallback on failure
  7. SQLite batch_add_history; per-record fallback on failure
  8. spaCy entity extraction + entity-vector upsert/link
  9. SQLite save_messages(scope), retaining last 10 raw messages
```

Evidence: phases 0–2 ([lines 916–969](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L916-L969)), parse/embed/dedup ([lines 971–1043](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L971-L1043)), persistence/history ([lines 1045–1084](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1045-L1084)), entity projection and message save ([lines 1086–1206](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1086-L1206)).

### Extraction and classification findings

- `CONFIRMED` — The active prompt says its **sole operation is ADD** and deliberately favors recall: “when in doubt, extract” ([prompt role](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/configs/prompts.py#L468-L476), [selection rule](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/configs/prompts.py#L551-L582)).
- `CONFIRMED` — It extracts both user facts and genuinely new assistant recommendations/plans/researched information, marking only `attributed_to` ([source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/configs/prompts.py#L553-L574)). There is no source-message ID, trust level, evidence span, or model-output flag in the persisted payload.
- `CONFIRMED` — The codebase still contains the old `DEFAULT_UPDATE_MEMORY_PROMPT` and helper that describe ADD/UPDATE/DELETE/NONE, but the audited `Memory.add` call chain does not call them; the V3 builder explicitly promises only ADD operations ([old prompt](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/configs/prompts.py#L176-L320), [active builder](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/configs/prompts.py#L1016-L1033)).
- `CONFIRMED` — `MemoryType` declares semantic, episodic, and procedural values, but `add` accepts only the procedural special case; ordinary facts are not semantically/episodically classified ([enum](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/configs/enums.py#L1-L7), [validation](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L831-L862)).

### Deduplication and linking findings

- `CONFIRMED` — Deterministic dedup is MD5 over the exact extracted text. Existing hashes come only from the ten semantically retrieved memories; duplicates outside that set can be inserted. Within-batch variants are distinct unless the LLM removes them ([source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1005-L1039)).
- `CONFIRMED` — Existing UUIDs are mapped to temporary integers before prompting, but `uuid_mapping` is never read afterward. The LLM's optional `linked_memory_ids` field is also never copied into a memory payload. Actual retrieval links are rebuilt independently by spaCy entity extraction into the separate entity store ([mapping](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L923-L953), [record construction](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1013-L1039), [entity links](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1086-L1188)). This is a pinned-commit observation, not a claim about future releases.
- `CONFIRMED` — Entity identity uses exact normalized text first, otherwise vector similarity `>= 0.95`; entity records contain text, type, scope, and `linked_memory_ids` ([source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L582-L650)). This is entity matching, not typed relationship or entity-resolution semantics.

## Search and ranking call chain

```text
Memory.search(query, top_k=20, threshold=.1, filters, rerank=False)
→ validate query and mandatory user/agent/run scope
→ _search_vector_store
   → lemmatize query + extract up to 8 entities
   → embed query
   → semantic vector search, internal_limit=max(4×top_k, 60)
   → keyword_search with same limit
   → normalize positive BM25 scores using query-length sigmoid
   → entity-store vector search; map entity matches to linked memory boosts
   → semantic-result candidates only
   → score_and_rank; semantic threshold first; truncate top_k
→ optional reranker.rerank(query, already-truncated top_k, top_k)
→ return formatted memories
```

`CONFIRMED` — BM25 and entity matching are boost signals, not recall expanders: candidate construction iterates only over dense semantic results ([source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1640-L1687)). A keyword-perfect record missing from the dense pool cannot enter the result.

`CONFIRMED` — Fusion is:

```text
raw = semantic + normalized_bm25 + entity_boost
max_possible = 1 + I(any BM25) + 0.5 × I(any entity boost)
final = min(raw / max_possible, 1)
```

The semantic score must pass `threshold` before other signals count. BM25 uses a query-length-dependent sigmoid; entity boost is similarity × `0.5` × a penalty for entities linked to many memories ([scoring source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/utils/scoring.py#L16-L139), [entity boost](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1733-L1813)). These constants are implementation heuristics, not learned/calibrated weights in this path.

`CONFIRMED` — Search invokes dense search, then keyword search, then entity processing in source order. Only the per-entity lookups use a four-worker pool. Therefore the documentation phrase “signals in parallel” should not be read literally for this synchronous OSS implementation.

`CONFIRMED` — Optional reranking occurs after hybrid results are already truncated to requested `top_k`; it reorders that set rather than reranking the wider `4×/60` candidate pool ([search](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1493-L1505), [truncation](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/utils/scoring.py#L103-L139)). The LLM reranker loops serially over documents, one model request each; cross-encoder/provider implementations have different cost profiles.

## Update and delete call chains

### Explicit update

```text
Memory.update(memory_id, text?, metadata?, expiration_date?)
→ embed new text when supplied
→ _update_memory
   → vector_store.get
   → merge payload, rejecting scope mutation
   → recompute MD5, lemma, updated_at
   → vector_store.update (same memory ID)
   → SQLite add_history(old, new, UPDATE)
   → if text changed: entity unlink + re-extract/relink
```

`CONFIRMED` — This is in-place replacement plus an audit row, not a first-class version model. There is no version number, truth status, valid interval, optimistic concurrency check, or automatic supersession ([source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L2038-L2098)).

### Explicit delete

```text
Memory.delete(memory_id)
→ vector_store.get
→ _delete_memory
   → vector_store.delete
   → SQLite add_history(old_memory=<full content>, event=DELETE, is_deleted=1)
   → entity unlink
```

`CONFIRMED` — Delete removes the main vector record but deliberately copies the deleted content into SQLite history ([source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L2100-L2128)). `delete_all` iterates scoped vector records; it does not clear the rolling raw `messages` table. Only `reset` drops both SQLite tables ([delete-all](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1890-L1944), [SQLite reset](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/storage.py#L326-L340)). Thus OSS `delete`/`delete_all` is not evidence of complete privacy erasure.

## Persistence and consistency audit

`CONFIRMED` — Default configuration uses Qdrant as the vector provider and gives file-backed stores a `/tmp/{provider}` path; history defaults to `~/.mem0/history.db` ([vector config](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/vector_stores/configs.py#L6-L67), [memory config](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/configs/base.py#L29-L57)). In this OSS path, memory text and metadata live in vector payloads; SQLite contains history plus a rolling raw-message window. Consequently, documentation describing SQL as the memory source of truth is not an exact description of this implementation.

`CONFIRMED` — SQLite serializes its own operations with a process-local lock and transaction, but there is no transaction spanning vector memory, SQLite history/messages, and the entity collection ([SQLite implementation](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/storage.py#L11-L18)). Concrete partial-failure windows follow directly from call order:

- vector batch failure falls back record by record, yet history is then attempted for every planned record, including a vector insert that may still have failed;
- vector success followed by history failure leaves retrievable memory without a complete audit trail;
- raw add/update/delete can mutate the vector store and then throw on SQLite failure, making caller retries non-idempotent;
- entity linking errors are logged and swallowed, so ranking projections may lag primary records;
- no source event ID or unique idempotency constraint prevents duplicate extraction after retry.

`INFERRED` — These are acceptable simplicity trade-offs for a local library, but they are incompatible with a production claim of atomic, replayable, exactly-once-visible memory writes without an outer consistency layer.

## Temporal, conflict, decay, context, and privacy coverage

| Capability | Pinned OSS evidence | Verdict |
|---|---|---|
| Conflict resolution | Active add is ADD-only; explicit update/delete require caller action | `N/E` for automatic `CURRENT/HISTORICAL/CONFLICTED/INVALIDATED` semantics |
| Temporal model | Prompt converts relative phrases to prose; payload has `created_at`, `updated_at`, optional `expiration_date` | `N/E` for `event_time`, valid intervals, event ordering, or temporal score |
| Historical import | `add(timestamp=...)` and `search(reference_date=...)` raise because they are Platform-only ([source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L787-L829), [search rejection](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1421-L1434)) | `N/E` for accurate backfilled observation time |
| Decay | `_OSSProject.update(decay=True)` raises ([source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L461-L484)) | Platform-only; no OSS access-frequency/importance decay |
| Expiration | Date stored in payload; search/list hide expired records unless requested | Soft visibility rule, not forgetting or deletion |
| Context | Top-10 related memories + last 10 scoped raw messages; prompt builder supports a summary but active call does not supply one | Useful local context; no durable summary/compaction or token budget |
| Prompt injection | Extraction uses system/user role separation; optional LLM reranker truncates inputs and also separates roles ([source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/reranker/llm_reranker.py#L78-L147)) | `N/E` for admission firewall, provenance/taint, quarantine, or adversarial-memory policy |
| Sensitive data | Config secret names are redacted in a telemetry-copy helper; docs warn callers not to store secrets ([source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L254-L298)) | `N/E` for content PII classification, encryption policy, or crypto-erasure |
| Scope | Mandatory user/agent/run filters; identity fields cannot be moved via metadata | Useful partition key, not a complete ACL/tenant authorization system |

One especially important privacy detail: `save_messages` runs even when the LLM extracts no memory, and stores the raw turn in SQLite before evicting older rows beyond ten ([no-fact path](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L971-L989), [message table](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/storage.py#L257-L324)). “Not selected as a memory” therefore does not mean “not persisted” in the audited OSS SDK.

## Paper-to-code version boundary

`CONFIRMED` — The 2025 Mem0 paper describes extraction followed by a second LLM/tool-call phase that chooses ADD, UPDATE, DELETE, or NOOP against the ten nearest memories ([paper §2.1](https://arxiv.org/html/2504.19413#S2.SS1), [Algorithm 1](https://arxiv.org/html/2504.19413#A2)). The current official OSS migration guide says that model was replaced by one ADD-only call ([guide](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/migration/oss-v2-to-v3.mdx#L351-L381)).

Therefore:

- paper claims about automatic UPDATE/DELETE/conflict handling are `DEPRECATED` for current OSS;
- current source behavior, not stale docstrings or unused prompts, governs implementation claims;
- Mem0 Platform adds proprietary temporal/graph/decay behavior that cannot be inferred from this OSS call chain.

## MemOS adoption decisions

| Mem0 technique | Decision | Reason |
|---|---|---|
| Single-pass structured extraction | Adopt as an adapter option | Lower write cost and clean separation between semantic proposal and deterministic policy |
| Preserve new and old evidence | Adopt | Avoids premature destructive consolidation, but MemOS will add explicit versions/status/valid time |
| Dense + lexical + entity retrieval | Adopt and benchmark | Covers paraphrase, exact identifiers, and entity-centric recall |
| Semantic-only candidate universe | Do not adopt | BM25/entity/temporal retrievers must be allowed to contribute candidates before fusion |
| Fixed additive score constants | Do not copy as truth | Start with a transparent baseline (for example RRF) and tune only on labeled dev data |
| Exact MD5 dedup over top-10 context | Keep only as cheap fast path | Requires global idempotency plus semantic/entity/predicate dedup and collision-safe identity |
| Vector payload as primary memory record | Do not adopt | MemOS needs transactional PostgreSQL records/versions as authority; indexes are rebuildable |
| SQLite + vector + entity best-effort writes | Do not adopt | Use one database transaction plus outbox/projection repair and observable materialization state |
| Explicit user/agent/run scope | Adopt and extend | Add tenant, ACL, provenance, and authorization before retrieval |
| Content-bearing delete history | Do not adopt for privacy erasure | Retain minimal tombstone/audit metadata under policy, not deleted sensitive content by default |
| Extract assistant output as equal memory | Do not adopt without trust policy | Assistant recommendations and hallucinations need source type, confidence, and stricter admission |

## Bottom line

`INFERRED` — Mem0 is best used by MemOS as a **baseline and a source of proven implementation patterns**, not as the production domain model. Its strongest reusable ideas are fact distillation, batched writes, graceful hybrid retrieval, scope keys, and benchmark attention to accuracy/cost/latency. Its most important negative lessons are equally valuable: ADD-only accumulation still needs temporal truth semantics; hybrid scoring still needs independent candidate generation and calibration; and a memory library does not become production infrastructure until writes, deletion, provenance, authorization, and indexes have explicit consistency contracts.
