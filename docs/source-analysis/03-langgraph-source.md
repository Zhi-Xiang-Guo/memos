# LangGraph source audit: Checkpointer and Store primitives

Audit date: **2026-08-26**. Repository: [`langchain-ai/langgraph`](https://github.com/langchain-ai/langgraph), pinned commit [`38031739e551638e373fb553453256c23feeb41f`](https://github.com/langchain-ai/langgraph/commit/38031739e551638e373fb553453256c23feeb41f), 2026-08-24.

This is a static audit of the Python reference primitives most relevant to memory. It does not claim behavior for an adapter whose source was not inspected, and it does not attribute LangMem or managed Agent Server policy to LangGraph OSS core.

## Audited source map

| File | Key symbols | Responsibility |
|---|---|---|
| [`libs/checkpoint/langgraph/store/base/__init__.py`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L51-L576) | `Item`, `SearchItem`, `GetOp`, `SearchOp`, `PutOp`, `TTLConfig` | Store data/operation contracts, timestamps, filters, TTL parameters |
| [same file](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L708-L944) | `BaseStore`, `get`, `search`, `put`, `delete` | Sync public Store API wrapping batch operations |
| [same file](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L1001-L1206) | `aget`, `asearch`, `aput`, `adelete` | Async public Store API |
| [`libs/checkpoint/langgraph/store/memory/__init__.py`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/memory/__init__.py#L136-L234) | `InMemoryStore.__init__`, `batch`, `abatch` | Reference dictionary/vector Store and operation pipeline |
| [same file](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/memory/__init__.py#L238-L460) | `_filter_items`, `_batch_search`, `_prepare_ops`, `_apply_put_ops`, `_extract_texts` | Filtering, cosine ranking, max-pooling, overwrite/delete, index preparation |
| [`libs/checkpoint/langgraph/checkpoint/memory/__init__.py`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/checkpoint/memory/__init__.py#L33-L123) | `InMemorySaver` | Reference thread/checkpoint/pending-write storage |
| [same file](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/checkpoint/memory/__init__.py#L230-L311) | `get_tuple` | Restores explicit or latest checkpoint plus blobs/pending writes/parent |
| [same file](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/checkpoint/memory/__init__.py#L312-L520) | `list`, `put`, `put_writes`, `delete_thread` | State history, checkpoint persistence, intermediate writes, thread erase |
| [`libs/langgraph/langgraph/runtime.py`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/langgraph/langgraph/runtime.py#L124-L205) | `Runtime` | Injects trusted run context and configured Store into nodes |
| [`libs/langgraph/langgraph/graph/message.py`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/langgraph/langgraph/graph/message.py#L38-L234) | `add_messages`, `REMOVE_ALL_MESSAGES` | Message-state append/replace/remove reducer |
| [`libs/checkpoint/langgraph/checkpoint/serde/encrypted.py`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/checkpoint/serde/encrypted.py#L8-L80) | `EncryptedSerializer` | Serialize then encrypt/decrypt checkpoint payloads; AES-EAX helper |

## Architectural boundary found in source

`CONFIRMED` — there are two separate interfaces:

- a **checkpointer** stores graph execution snapshots by thread/checkpoint namespace and retains pending writes;
- a **Store** holds arbitrary JSON-like dictionaries by namespace tuple and key, optionally indexed for semantic search.

There is no base class named “MemoryEngine” that performs extraction, deduplication, contradiction resolution, temporal reasoning, or context selection. Those behaviors must live in graph nodes/tools/middleware or a separate library.

## Long-term Store operation model

The base contract normalizes every public method to batch operations:

```text
get      → batch([GetOp(namespace, key, refresh_ttl)])
search   → batch([SearchOp(prefix, filter, limit, offset, query, refresh_ttl)])
put      → validate namespace/TTL support
         → batch([PutOp(namespace, key, value, index, ttl)])
delete   → batch([PutOp(namespace, key, value=None, ttl=None)])
```

Evidence: [`BaseStore.get/search`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L756-L854), [`put/delete`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L856-L944). Async methods mirror the same pattern through `abatch` ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L1001-L1206)).

### Contract data

`CONFIRMED` — `Item` contains `value`, `key`, `namespace`, `created_at`, and `updated_at`; `SearchItem` adds optional similarity `score` ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L51-L155)).

`CONFIRMED` — `SearchOp` supports namespace-prefix selection, a top-level field filter with equality/comparison operators, limit/offset, optional natural-language query, and TTL refresh control ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L203-L303)).

`CONFIRMED` — `PutOp` accepts a JSON-like value, optional fields to index, and optional TTL. `value=None` is the adapter-level delete signal ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L431-L534)).

### Missing concurrency/version semantics

`CONFIRMED` — public `put`/`aput` have no `expected_version`, ETag, compare-and-swap token, source-event idempotency key, parent-memory ID, or truth-state transition parameter.

`INFERRED` — applications can build some of these into key/value/backend transactions, but they are not guaranteed by `BaseStore`. MemOS cannot treat a successful `put` as a version-safe semantic update.

## InMemoryStore write call chain

```text
BaseStore.put(namespace, key, value, index?, ttl?)
→ _validate_namespace
→ reject non-null TTL because InMemoryStore does not opt into supports_ttl
→ InMemoryStore.batch([PutOp])
→ _prepare_ops
   → coalesce PutOps by (namespace, key)
→ _extract_texts
   → select configured JSON paths unless index=False
→ embeddings.embed_documents(texts) [only when index configured]
→ _insertinmem_store
   → _vectors[namespace][key][path] = embedding
→ _apply_put_ops
   → value is None: remove document and vectors
   → else: overwrite document with a new Item and current timestamps
```

Evidence: [`batch`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/memory/__init__.py#L206-L234), [`_prepare_ops`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/memory/__init__.py#L375-L402), [`_apply_put_ops`/`_extract_texts`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/memory/__init__.py#L404-L444).

Important details:

- `CONFIRMED` — multiple `PutOp`s for the same `(namespace, key)` in one batch are coalesced in a dictionary; later iterable entries replace earlier ones before application.
- `CONFIRMED` — overwrite creates a new reference `Item` with both timestamps set to current time. The reference implementation retains no prior value/version ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/memory/__init__.py#L404-L416)).
- `CONFIRMED` — vector insertion occurs before `_apply_put_ops`. This ordering is within an in-process reference object, not proof of a production adapter’s transaction semantics.
- `CONFIRMED` — the class warns that process exit loses all data and recommends a persistent adapter ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/memory/__init__.py#L136-L174)).

## InMemoryStore search call chain

```text
BaseStore.search(namespace_prefix, query?, filter?, limit, offset)
→ InMemoryStore.batch([SearchOp])
→ _prepare_ops
→ _filter_items
   → prefix match namespace
   → apply filter to item.value top-level fields
   → collect any vectors for each surviving item
→ _embed_search_queries
   → deduplicate identical query strings
   → embeddings.embed_query concurrently
→ _batch_search
   → flatten item field vectors
   → cosine similarity(query, vectors)
   → sort descending
   → max-pool by first/highest score per (namespace, key)
   → offset/limit
   → fill remaining capacity with unindexed scoreless items
→ list[SearchItem]
```

Evidence: [`_filter_items`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/memory/__init__.py#L238-L266), [`_embed_search_queries`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/memory/__init__.py#L268-L300), [`_batch_search`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/memory/__init__.py#L302-L373).

### Retrieval limits established by source

- `N/E` — BM25/lexical candidate generation in this reference Store;
- `N/E` — entity canonicalization, typed graph, or multi-hop traversal;
- `N/E` — event/valid-time retrieval or current/historical truth filtering;
- `N/E` — importance, authority, confidence, recency, access, or diversity score fusion;
- `N/E` — cross-encoder/LLM reranking and token-budget context building.

`INFERRED` — `BaseStore` is sufficiently generic for an adapter to implement additional behavior, but generic extensibility is not evidence that LangGraph core already implements it.

## TTL source semantics

`CONFIRMED` — `PutOp.ttl` is expressed in minutes since last access; access and write may refresh the timer; deletion after expiry is best-effort ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L510-L534)). `TTLConfig` can control refresh, omission of expired items, default TTL, and sweep interval ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L545-L576)).

`CONFIRMED` — `BaseStore.supports_ttl` defaults to false; a subclass must opt in. Public `put` rejects a non-null TTL when unsupported ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L708-L731), [guard](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/store/base/__init__.py#L919-L935)). `InMemoryStore` does not override `supports_ttl` at this commit.

`INFERRED` — adapter TTL is a cache/retention mechanism, not a semantic forgetting policy. It lacks memory type, importance, source authority, legal hold, or archive state.

## Short-term checkpoint call chains

### Data layout

`InMemorySaver` documents and implements three maps:

```text
storage[thread_id][checkpoint_ns][checkpoint_id]
    = (serialized checkpoint, serialized metadata, parent_checkpoint_id)

writes[(thread_id, checkpoint_ns, checkpoint_id)][(task_id, write_idx)]
    = (task_id, channel, serialized value, task_path)

blobs[(thread_id, checkpoint_ns, channel, version)]
    = serialized channel value
```

Evidence: [class/data declarations](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/checkpoint/memory/__init__.py#L33-L99).

### Save checkpoint

```text
execution engine supplies config + Checkpoint + metadata + new_versions
→ InMemorySaver.put
→ copy checkpoint and remove channel_values
→ serialize each new/changed channel blob keyed by version
→ serialize checkpoint body + merged metadata
→ store parent checkpoint ID
→ return config containing new checkpoint_id
```

Evidence: [`put`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/checkpoint/memory/__init__.py#L421-L465).

### Save intermediate writes

```text
execution engine supplies channel writes + task_id/path
→ InMemorySaver.put_writes
→ build key (thread, namespace, checkpoint)
→ enumerate channel writes
→ derive stable write index for known channels
→ skip already-present non-negative task/write key
→ serialize and store pending write
```

Evidence: [`put_writes`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/checkpoint/memory/__init__.py#L467-L503).

`INFERRED` — the stable `(task_id, write_idx)` skip behavior provides idempotence for those intermediate writes in the reference saver. It does not make an arbitrary memory-extraction side effect outside the checkpointer exactly-once.

### Restore checkpoint

```text
get_tuple(config)
→ read thread_id + checkpoint_ns
→ use requested checkpoint_id or max/latest ID
→ deserialize checkpoint + metadata
→ load versioned channel blobs
→ deserialize pending writes
→ attach parent config
→ CheckpointTuple
```

Evidence: [`get_tuple`](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/checkpoint/memory/__init__.py#L230-L311).

### Delete thread

`CONFIRMED` — `delete_thread(thread_id)` removes that thread’s checkpoint storage, pending-write entries, and blobs in the reference saver ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/checkpoint/memory/__init__.py#L505-L520)).

`INFERRED` — it does not delete independent long-term Store namespaces. User deletion across both layers must be orchestrated explicitly.

## Message-state update/delete

`CONFIRMED` — `add_messages` is append-only by default, replaces an existing message with the same ID, recognizes `RemoveMessage`, and can remove all messages after a special sentinel ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/langgraph/langgraph/graph/message.py#L60-L234)).

`INFERRED` — this is deterministic state-reducer behavior, not long-term semantic update. A corrected statement in a new message does not automatically invalidate an older Store fact.

## Store injection and scope

`CONFIRMED` — `Runtime` exposes run `context` and `store` to a node. The source example derives a user ID from typed runtime context and then calls `runtime.store.get`; the graph is compiled with that Store ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/langgraph/langgraph/runtime.py#L124-L205)).

MemOS lesson:

```text
authenticated server request
→ trusted Runtime context {tenant_id, user_id, policy}
→ application derives namespace/filter
→ model supplies content/query, not authorization identity
```

`INFERRED` — a namespace is an address, not an ACL. Every Store operation still needs server-side authorization and tenant filters that a model cannot override.

## Encryption source path

```text
checkpointer serde.dumps_typed(obj)
→ EncryptedSerializer.dumps_typed
→ underlying JsonPlusSerializer
→ cipher.encrypt(serialized bytes)
→ store type+cipher name and ciphertext

checkpoint read
→ detect type+cipher name
→ cipher.decrypt and authenticate
→ underlying deserialize
```

`CONFIRMED` — `from_pycryptodome_aes` reads an explicit key or `LANGGRAPH_AES_KEY`, validates 16/24/32-byte size, defaults to AES-EAX, and stores nonce + authentication tag + ciphertext ([source](https://github.com/langchain-ai/langgraph/blob/38031739e551638e373fb553453256c23feeb41f/libs/checkpoint/langgraph/checkpoint/serde/encrypted.py#L38-L80)).

Limits:

- this proves encryption for values serialized by a checkpointer configured with this serializer;
- it does not prove encryption for every Store adapter, vector index, cache, trace, transport, or backup;
- it does not provide content-level PII policy, access control, key rotation workflow, or semantic prompt-injection defense.

## Consistency and failure analysis

| Scenario | What the audited source establishes | What remains open |
|---|---|---|
| Duplicate pending checkpoint write | Reference saver skips an existing stable task/write key | External node side effects and memory Store writes need their own idempotency |
| Duplicate Store put | Same namespace/key overwrites in reference Store | No semantic dedup; no lineage/history |
| Concurrent Store update | No CAS/expected version in base API | Adapter/app must prevent lost update |
| Checkpoint + long-term memory in one node | Separate interfaces/calls | No cross-interface transaction in reviewed source |
| Embedding failure in InMemoryStore put | Exception before `_apply_put_ops` | Other adapters may have different partial-failure windows |
| Delete thread | Removes reference checkpoint maps for one thread | Does not cascade to Store/index/cache/trace |
| TTL expiry | Adapter opt-in and best-effort | Timing/completeness depend on adapter/sweeper |

## Product and companion-library boundary

- `CONFIRMED` — LangGraph core exposes Store/checkpointer primitives audited above.
- `CONFIRMED` — official docs show application code for hot-path/background memory formation and message summarization; examples are patterns, not automatic base behavior.
- `CONFIRMED` — LangMem is a separate package adding memory managers/store managers; it must be versioned and audited independently if MemOS evaluates it.
- `CONFIRMED` — Agent Server may manage persistence infrastructure automatically; managed convenience does not add an OSS-visible extraction/conflict/time algorithm.
- `CONFIRMED` — LangSmith offers tracing/evaluation infrastructure; it does not make a particular Store content policy correct.

## MemOS source-level decisions

| Primitive/pattern | Decision |
|---|---|
| Separate thread checkpoints and cross-thread Store | Adopt conceptually |
| Runtime-injected trusted scope | Adopt |
| Checkpoint pending-write idempotence | Reuse for orchestration where LangGraph is used; do not generalize to DB side effects |
| Namespace/key JSON as authoritative fact model | Reject; too weak for versions/time/status/provenance |
| Store semantic query | Use as a baseline candidate retriever only |
| Same-key overwrite | Reject for semantic truth; append a version and transition prior status transactionally |
| Adapter TTL | Use for caches/ephemeral memory only unless policy explicitly maps to it |
| Optional encrypted serializer | Adopt principle; verify every projection and key lifecycle separately |
| Generic graph nodes for memory formation | Useful experimentation surface; production invariants stay in MemOS service |

## Bottom line

`INFERRED` — the pinned source validates LangGraph's workflow/checkpoint and scoped-storage primitives; durability requires a separately verified persistent adapter, because the audited `InMemorySaver`/`InMemoryStore` are process-local. The boundary is clear: extraction, semantic identity, conflict, temporal truth, authorization, complete deletion, hybrid retrieval, and memory-specific evaluation are not properties of `BaseStore` or `InMemorySaver`. MemOS must own them.
