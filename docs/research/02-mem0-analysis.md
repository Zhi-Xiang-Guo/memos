# Mem0 analysis: architecture, lifecycle, limits, and MemOS decision

Research cutoff: **2026-08-26**. Primary sources are the official Mem0 paper, official documentation, and the official repository pinned at commit [`39bc02330563764e7d4465f1ecff5f002d94da1`](https://github.com/mem0ai/mem0/commit/39bc02330563764e7d4465f1ecff5f002d94da1). Code-level detail is in [the pinned source audit](../source-analysis/01-mem0-source.md).

## Fact grades and product boundary

- `CONFIRMED`: directly supported by a linked paper, official document, or pinned source.
- `INFERRED`: our engineering interpretation of confirmed behavior.
- `HYPOTHESIS`: must be tested in MemOS.
- `DEPRECATED`: historically real but not current for the named product/version.
- `UPSTREAM-REPORTED`: a paper author or vendor reports the setup/result; MemOS has not independently reproduced it.
- `N/E`: no evidence of the required capability in the reviewed source boundary.

“Mem0” is not one stable algorithm. Three boundaries must remain explicit:

1. **2025 paper / old algorithm** — extraction plus a second LLM decision over ADD, UPDATE, DELETE, or NOOP; optional typed relationship graph backed by Neo4j ([paper §2](https://arxiv.org/html/2504.19413#S2)).
2. **2026 OSS V3** — one ADD-only extraction call, vector payload persistence, BM25/entity boosts, optional reranker; no external graph store or structured temporal layer ([migration guide](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/migration/oss-v2-to-v3.mdx#L12-L23)).
3. **2026 managed Platform V3** — ADD-only extraction plus proprietary native graph/entity, temporal-reasoning, decay, hosted persistence, dashboard/governance features. Platform benchmark scores explicitly include optimizations absent from OSS ([evaluation caveat](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/core-concepts/memory-evaluation.mdx#L125-L140)).

Any statement below names the boundary it describes.

## Executive verdict

`CONFIRMED` — Mem0 frames long-term memory as selected, persistent facts retrieved across sessions rather than replayed transcripts. Current docs describe messages as input and extracted, searchable memories as output; applications call `add` after useful interactions and `search` before a model call ([How Mem0 Works](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/core-concepts/how-it-works.mdx#L7-L51)).

`INFERRED` — Mem0's strongest current contribution is a pragmatic accuracy/cost architecture: distill verbose turns once; keep changes additively; retrieve with semantic, lexical, and entity signals; send a bounded subset to the answer model. It is substantially more than `Embedding + Vector DB + TopK`.

`CONFIRMED` — It is not, however, the complete production memory contract required by MemOS. Current OSS lacks structured versions and truth status, automatic conflict resolution, valid-time intervals, temporal retrieval, admission-time PII policy, complete erase semantics, transactional cross-store writes, and independently generated benchmark evidence. Platform closes some retrieval gaps but remains proprietary.

`UPSTREAM-REPORTED` — Mem0's own evaluation also reports weak BEAM 10M slices for temporal reasoning, event ordering, and contradiction resolution ([official evaluation](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/core-concepts/memory-evaluation.mdx#L101-L123)). These vendor-reported observations are hypotheses for MemOS, not reproduced evidence.

Recommendation: **study and benchmark Mem0; do not make it MemOS's source of truth or outsource MemOS's lifecycle semantics to it.**

## What Mem0 calls memory

`CONFIRMED` — The official mental model distinguishes conversation turns from stored memory: an LLM extracts reusable preferences, decisions, plans, and facts; deduplicates/embeds them; derives entities; and retrieves relevant records later ([official flow](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/core-concepts/how-it-works.mdx#L23-L64)). Cross-session continuity comes from stable `user_id`, `agent_id`, or `run_id` scopes rather than an open chat window.

`INFERRED` — A precise description of current OSS Mem0 is:

> An LLM-distilled, additively accumulated collection of scoped natural-language facts, stored with embeddings and lightweight metadata, recalled through semantic candidates plus lexical/entity ranking signals.

This definition is useful but narrower than MemOS's target of governed, versioned state derived from evidence.

### Conversation history is not memory

`CONFIRMED` — Mem0 does not normally store a whole transcript as the retrievable unit. It turns verbose turns into standalone facts, while a small rolling raw-message window is used only as extraction context ([write flow](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L916-L989), [message retention](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/storage.py#L257-L324)). This reduces irrelevant input and makes cross-session search possible.

`INFERRED` — History remains evidence of what was said; memory is a fallible projection optimized for future use. History alone does not decide durability, current truth, authority, sensitivity, contradiction, or token priority. Conversely, a memory without a source reference is difficult to audit. MemOS should preserve both layers and link them.

### Vector search is not memory

`CONFIRMED` — Current Mem0 itself supplements vectors with normalized BM25 and entity matching, optionally followed by a reranker ([retrieval implementation](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L1628-L1813)). Its paper also found different strengths for dense natural-language facts and graph structure ([paper results](https://arxiv.org/html/2504.19413#S4.SS1)).

`INFERRED` — Similarity produces candidates, not memory truth. It cannot by itself determine whether “lives in Shanghai” is current or historical, whether a new statement is authoritative, whether two paraphrases are duplicates, whether content is allowed for this tenant, or whether a retrieved sentence is safe to inject.

### Why `Embedding + Vector DB + TopK` is insufficient

Such a pipeline omits at least:

- selective, attributable extraction;
- exact/entity/temporal retrieval signals;
- deduplication and conflict policy;
- versions and historical/current state;
- idempotent, atomic writes and repair;
- expiration, decay, deletion propagation, and privacy controls;
- context selection under a token budget;
- benchmark separation of retrieval, answer quality, latency, and cost.

Mem0 covers the first two and parts of the third, sixth, and eighth. It does not cover the complete list in OSS.

## Algorithm evolution: paper versus current products

| Concern | 2025 paper | 2026 OSS V3 | 2026 Platform V3 |
|---|---|---|---|
| Write decision | Extract facts, then LLM classifies ADD/UPDATE/DELETE/NOOP | One LLM call, ADD-only | ADD-only |
| Change handling | Overwrite/delete related fact | Preserve old and new facts; caller may explicitly update/delete | Preserve old/new; managed temporal ranking |
| Retrieval | Dense memory search; graph variant separately searches graph | Dense candidate pool + BM25 + entity boost; optional reranker | Managed multi-signal retrieval including temporal |
| Graph | Typed entity-relation triples in Neo4j | No graph; parallel entity vector collection | Native entity-memory co-occurrence graph, always on; no typed relation API |
| Temporal | Graph conflicts can be invalidated rather than physically removed | Dates may appear in text; no structured temporal score | Extracted temporal metadata and time-intent boost |
| Decay | Not central | Not available | Optional access-history score multiplier |

Sources: [paper architecture](https://arxiv.org/html/2504.19413#S2.SS1), [paper graph](https://arxiv.org/html/2504.19413#S2.SS2), [OSS migration](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/migration/oss-v2-to-v3.mdx#L351-L411), [Platform graph](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/platform/features/graph-memory.mdx#L25-L55), [Platform temporal](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/platform/features/temporal-reasoning.mdx#L7-L16), [Platform decay](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/platform/features/memory-decay.mdx#L6-L46).

`CONFIRMED` — This evolution reverses the 2025 consolidation strategy. The old algorithm risked destroying temporal evidence through an early LLM decision; V3 accepts more accumulation and shifts “which fact matters now?” toward retrieval. That is an intentional trade-off, not a documentation detail.

## Lifecycle coverage matrix

| Required dimension | Current evidence | Grade and gap |
|---|---|---|
| Memory definition | Extracted reusable facts, scoped and searchable across sessions | `CONFIRMED`; no explicit evidence/projection contract |
| Write flow | Context lookup → one LLM extraction → embed → exact hash dedup → vector/history/entity writes | `CONFIRMED`; non-atomic and non-idempotent in OSS |
| Memory extraction | Rich ADD-only prompt for facts, preferences, decisions, plans, events, assistant recommendations | `CONFIRMED`; model quality and prompt injection remain risks |
| Memory selection | Prompt skips greetings/generic filler but says “when in doubt, extract” | `CONFIRMED`; no calibrated `shouldRemember`, importance, or risk threshold |
| Classification | Procedural special case only; semantic/episodic enum values are not implemented ([types](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/core-concepts/memory-types.mdx#L8-L43)) | `N/E` for operational semantic/episodic/entity/task classification |
| Storage | OSS vector payload + SQLite history/recent messages + entity collection; Platform managed stores | `CONFIRMED`; OSS has no single transactional authority |
| Retrieval | Semantic, BM25, entity boost; optional rerank; filters | `CONFIRMED`; BM25/entity do not expand the semantic candidate set |
| Ranking | Fixed additive normalized score; optional provider/cross-encoder/LLM rerank | `CONFIRMED`; no learned/calibrated weights or importance/recency in OSS |
| Update | Explicit in-place content/metadata replacement with history entry and re-embedding | `CONFIRMED`; not automatic and not versioned truth |
| Delete | Explicit record/bulk delete and entity unlink | `CONFIRMED`; OSS history/raw-message copies remain |
| Forgetting / TTL | `expiration_date` hides records after a date but does not delete ([expiration](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/platform/features/memory-expiration.mdx#L7-L19)) | `CONFIRMED` soft expiration; `N/E` for policy-driven archive/purge in OSS |
| Decay | Platform multiplies ranking by access-derived factor `0.3×–1.5×`, never filters | `CONFIRMED` vendor feature; Platform-only and not type/importance aware |
| Deduplication | LLM sees ten neighbors; exact MD5 text match over those neighbors/current batch | `CONFIRMED`; not global semantic dedup, no database uniqueness |
| Conflict resolution | V3 stores old and new; explicit CRUD left to caller | `N/E` for conflict detection/status/resolution in OSS; ranking is not truth state |
| Temporal reasoning | Platform-only temporal metadata/boost; OSS rejects `timestamp` and `reference_date` | `CONFIRMED`; `N/E` in OSS for event/valid time and historical/current queries |
| Entity linking | OSS spaCy entities in parallel vector collection; Platform native entity-memory graph | `CONFIRMED`; no robust canonical identity or typed predicates in current design |
| Context management | Last ten scoped raw messages + ten related memories for extraction; application injects search results | `CONFIRMED`; no OSS summary lifecycle, context builder, provenance rendering, or token budget |
| Prompt injection | Role-separated extraction/rerank prompts; no admission trust/taint/quarantine layer found | `N/E` for durable-memory poisoning defense; adversarial tests required |
| Token optimization | Fact distillation and bounded `top_k`; paper/current docs report token savings | `CONFIRMED` architectural mechanism; no OSS token-budget enforcement |
| Persistence | Multiple vector providers, default Qdrant; SQLite history/message window | `CONFIRMED`; durability/HA/backup depend on operator and backend |
| Multi-agent memory | `user_id`, `agent_id`, `run_id`; Platform also `app_id`, org/project controls | `CONFIRMED` scoping; no general sharing ACL/promotion/conflict protocol in OSS library |
| Privacy | Scope filters, explicit delete/export, identity-metadata hardening; docs warn not to store secrets | `CONFIRMED` basic controls; `N/E` for content PII policy, encryption/keys, full erase proof |
| Evaluation | Paper evaluates LoCoMo; current official suite covers LoCoMo, LongMemEval, BEAM | `CONFIRMED` coverage/suite existence; scores are `UPSTREAM-REPORTED`, not reproduced, and Platform has proprietary optimizations |

## Write path analysis

### Context and extraction

`CONFIRMED` — Current OSS retrieves the last ten raw messages for the exact scope and the ten closest existing memories to the new exchange, then asks one LLM to emit standalone ADD facts ([source chain](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L916-L989)). The prompt tries to preserve exact names, dates, quantities, transitions, attribution, and multiple topics ([active prompt](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/configs/prompts.py#L605-L701)).

What this gets right:

- recent raw context resolves pronouns and local references;
- related memories give the model a semantic dedup/linking view;
- standalone natural-language facts are cheaper to retrieve and inject than raw turns;
- a single LLM call reduces latency/cost versus extract-then-diff.

What remains risky:

- `CONFIRMED` — the prompt prefers over-extraction and treats assistant-generated recommendations/research as first-class memory. `INFERRED` — an assistant hallucination can become durable state unless the application supplies a trust policy;
- `CONFIRMED` — no persisted source-message ID/evidence span accompanies each fact. Attribution says user versus assistant, not why the statement is trusted;
- `CONFIRMED` — if extraction returns nothing, OSS still persists the raw turn in its rolling SQLite context. Selection is therefore not a data-minimization boundary;
- `HYPOTHESIS` — one-pass extraction improves recall/cost but may increase pollution. Measure write precision/recall and downstream harmful-use rate, not only answer accuracy.

### Add-only and conflict semantics

`CONFIRMED` — The prompt explicitly captures transitions such as switching from one preference to another, and V3 preserves both new and previous memories ([prompt](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/configs/prompts.py#L607-L620)). The official evaluation page argues that this avoids information loss from premature consolidation ([evaluation architecture](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/core-concepts/memory-evaluation.mdx#L18-L43)).

`INFERRED` — Preservation is necessary but insufficient. Given:

```text
T1: User lives in Shanghai.
T2: User moved to Hangzhou.
```

OSS can store both sentences but cannot deterministically expose `Shanghai=HISTORICAL`, `Hangzhou=CURRENT`, or the May 2026 change point. Similarity ranking may return both in either order. MemOS should keep additive evidence while separately computing version/status/valid-time state.

### Storage and consistency

`CONFIRMED` — OSS writes primary text/metadata into vector-store payloads, audit-like events and recent messages into SQLite, and entity links into another collection. These calls are ordered but not transactionally coupled; fallbacks log and continue in several paths. The exact failure windows are enumerated in the [source audit](../source-analysis/01-mem0-source.md#persistence-and-consistency-audit).

`INFERRED` — This architecture optimizes library portability. A production infrastructure service instead needs an authoritative transaction, idempotent source event, outbox, projection status, retries, replay, and reconciliation metrics.

## Read path analysis

### Candidate retrieval and fusion

`CONFIRMED` — OSS over-fetches dense results (`max(4 × top_k, 60)`), performs BM25/full-text search where supported, extracts query entities, and combines available scores. The formula is a normalized sum of semantic score, BM25 score, and a maximum `0.5` entity boost; semantic thresholding occurs before fusion ([implementation](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/utils/scoring.py#L57-L139)).

Strengths:

- lexical matching helps names, identifiers, and exact phrases that embeddings blur;
- entity matching connects facts about the same named subject;
- `explain=True` returns component scores for debugging;
- graceful degradation preserves semantic search when optional NLP/BM25 support is absent.

Limits:

- `CONFIRMED` — only dense results become candidates, so lexical/entity search cannot rescue a dense miss;
- `CONFIRMED` — constants and query-length sigmoid are hand-authored heuristics, not workload-calibrated probabilities;
- `CONFIRMED` — reranking sees only the already-truncated `top_k`, not the wider pool;
- `N/E` — no OSS temporal, truth-status, authority, importance, access-frequency, or diversity score;
- `N/E` — no context builder decides which results fit a token budget or how provenance is rendered.

`HYPOTHESIS` — MemOS should compare independent semantic/lexical/entity/temporal candidate union plus RRF against Mem0's semantic-gated additive fusion at equal candidate and token budgets.

### Entity memory and graph claims

`DEPRECATED` — The paper's `Mem0g` represents typed directed triples, performs conflict resolution, marks obsolete relations invalid, and uses Neo4j ([paper §2.2](https://arxiv.org/html/2504.19413#S2.SS2)). This is not current OSS.

`CONFIRMED` — Current OSS stores extracted entity strings and their linked memory IDs in a parallel vector collection; they contribute ranking boosts. Current Platform builds a native graph in which shared entities connect memories, but official docs explicitly say it does not expose typed labeled relationships such as `person --manages→ company` ([Platform graph semantics](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/platform/features/graph-memory.mdx#L47-L55)).

`INFERRED` — Calling all three designs “graph memory” hides materially different semantics. MemOS should introduce a graph projection only if typed entity/predicate/multi-hop benchmarks justify its identity-resolution and dual-write cost.

## Update, delete, forgetting, and decay

### Update

`CONFIRMED` — Explicit OSS `update` replaces text/metadata under the same memory ID, re-embeds it, records old/new content in SQLite, and repairs entity links ([source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L2038-L2098)). V3 `add` does not call this automatically.

`INFERRED` — This supports operator correction but not historical truth. MemOS should create a new version with an optimistic version check and close/invalidate the prior version in the same transaction.

### Delete and privacy erase

`CONFIRMED` — Mem0 exposes single, batch, filtered, and entity deletion in its products ([delete guide](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/core-concepts/memory-operations/delete.mdx#L8-L40)). In OSS, deleting a vector record writes its full prior content into SQLite history, and bulk deletion does not clear recent raw messages.

`INFERRED` — A success response proves main-index deletion, not complete erasure across history, recent context, caches, jobs, snapshots, and backups. MemOS needs a deletion saga with per-projection acknowledgements and a policy for minimal tombstone versus content-bearing audit.

### Expiration versus decay

`CONFIRMED` — `expiration_date` is a soft visibility deadline: expired records remain stored and are retrievable by ID; `show_expired` can surface them ([expiration docs](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/platform/features/memory-expiration.mdx#L7-L19)).

`CONFIRMED` — Platform decay is an optional search-time multiplier derived from recent accesses, bounded `0.3×–1.5×`; it never removes a candidate and preserves at most twenty access timestamps. It is unavailable in OSS ([decay docs](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/platform/features/memory-decay.mdx#L23-L46)).

`INFERRED` — Expiration is useful for known validity deadlines and decay for soft relevance. Neither is a complete forgetting policy across memory type, importance, access, legal retention, confidence, and storage pressure.

## Temporal reasoning

`CONFIRMED` — The 2025 graph paper invalidated conflicting relationships rather than physically deleting them, enabling some temporal reasoning. Current Platform V3 separately extracts event timing and classifies query time intent to boost matching dated memories. `timestamp` preserves historical observation time and `reference_date` changes the query's temporal anchor ([Platform temporal docs](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/platform/features/temporal-reasoning.mdx#L7-L73)).

`CONFIRMED` — Current OSS rejects both parameters and has no `event_time`, `valid_from`, `valid_to`, or temporal score. Its extraction prompt may turn “yesterday” into an absolute date in prose, using current date because the active call supplies no historical timestamp.

`INFERRED` — Textual dates improve retrieval but do not support bitemporal questions such as “what did the system believe on date X about what was valid on date Y?” MemOS requires structured source time, event time, valid interval, transaction time, original expression, and parser confidence.

## Context injection and token optimization

`CONFIRMED` — Mem0 is a memory service/library, not a complete context-policy engine. Its docs say the application chooses which search results to include in the prompt ([official boundary](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/core-concepts/how-it-works.mdx#L7-L9)). The common pattern is to concatenate selected memories before the model call.

`UPSTREAM-REPORTED` — In the 2025 LoCoMo experiment, the authors reported Mem0 with 1,764 retrieved memory tokens, 0.200 s search p95, and 1.440 s total p95 versus 26,031 tokens and 17.117 s total p95 for full context ([paper Table 2](https://arxiv.org/html/2504.19413#S4.SS2)). These are authors' results for an old algorithm/dataset setup, not independently reproduced facts or guarantees for current OSS or MemOS.

`INFERRED` — A count-based `top_k` is not a token budget. MemOS should select evidence after authorization/truth filtering, enforce a tokenizer-measured budget, diversify near-duplicates, render provenance, and clearly delimit recalled content as data rather than instructions.

## Multi-agent memory and isolation

`CONFIRMED` — OSS requires at least one `user_id`, `agent_id`, or `run_id` on writes and searches; identity fields supplied inside free-form metadata are stripped so callers cannot move a record into an unrequested scope ([source](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/memory/main.py#L314-L404)). Platform additionally supports `app_id` and organization/project membership, and documents entity-scoped partitions ([Platform scoping](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/platform/features/entity-scoped-memory.mdx#L6-L55)).

`INFERRED` — Partition keys prevent accidental mixing only when every caller uses them correctly. They do not define who may read/write/promote shared facts, how private agent memory becomes team memory, or how concurrent agents resolve competing updates. MemOS needs tenant/user/agent scopes plus an authorization matrix, explicit sharing, provenance, and optimistic concurrency.

## Prompt injection and memory pollution

`CONFIRMED` — Active extraction instructions demand evidence-bound facts and use system/user role separation. The optional LLM reranker truncates inputs and also separates its system instruction from query/document data ([reranker](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/mem0/reranker/llm_reranker.py#L78-L147)).

`N/E` — The audited OSS write path has no deterministic trust channel, prompt-injection classifier, provenance/taint field, quarantine state, or rule preventing recalled procedural text from later being treated as instruction. It also has no importance-based pruning or periodic merge/archive job.

`HYPOTHESIS` — Ordinary role separation will not be sufficient against durable memory poisoning. MemOS must test attacks at three stages: write admission, retrieval, and downstream use. Procedural memories require a stricter policy than preferences or events.

## Privacy assessment

Confirmed controls:

- scoped reads/writes and immutable identity metadata in OSS;
- optional self-hosting for data control;
- explicit update/delete/export APIs on Platform;
- telemetry can be disabled; config-secret fields are redacted in a telemetry-copy helper;
- official docs explicitly warn against storing secrets or unredacted sensitive data ([warning](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/core-concepts/how-it-works.mdx#L82-L87)).

Missing or not evidenced in the audited OSS core:

- content-level PII/sensitivity classification before extraction and raw-message retention;
- field-level encryption, tenant keys, crypto-shredding, and rotation contract;
- consent/purpose/retention metadata;
- a deletion proof spanning vector, entity, SQLite history, raw messages, caches, jobs, backups;
- authorization checks beyond caller-provided filters;
- audit entries that preserve accountability without retaining deleted content.

`INFERRED` — Mem0 provides useful data-management primitives, not a complete privacy architecture. Privacy must precede persistence, including the “recent message context” side store.

## Evaluation evidence and its limits

### 2025 paper

`UPSTREAM-REPORTED` — The paper evaluates ten LoCoMo conversations of roughly 600 turns across single-hop, multi-hop, open-domain, and temporal questions. Its full-context baseline table reports 26,031 input tokens under that experiment's preprocessing/token accounting; this is not the dataset's intrinsic size and should not be conflated with the original LoCoMo paper's roughly 16K-token average. It excludes the adversarial/unanswerable category because ground-truth answers were unavailable ([dataset setup](https://arxiv.org/html/2504.19413#S3.SS1), [Table 2](https://arxiv.org/html/2504.19413#S4.SS2)). It reports F1, BLEU-1, LLM-as-a-Judge across ten runs, token count, search latency, and end-to-end latency ([metrics](https://arxiv.org/html/2504.19413#S3.SS2)).

`UPSTREAM-REPORTED` — Authors report overall judge scores 66.88 for Mem0 and 68.44 for Mem0g, with lower reported p95 latency and tokens than their full-context baseline ([Table 2](https://arxiv.org/html/2504.19413#S4.SS2)). In their table, graph improved temporal/open-domain results but did not improve single-hop or multi-hop across all metrics ([Table 1](https://arxiv.org/html/2504.19413#S4.SS1)).

Limits:

- it evaluates the now-deprecated write algorithm, not current V3;
- it is an author evaluation, not independent reproduction;
- LoCoMo is small and the unanswerable category was excluded;
- LLM-as-a-Judge and hosted model versions introduce variance/drift;
- it does not establish write precision, privacy, deletion completeness, concurrency, or long-run pollution.

### 2026 official evaluation suite

`UPSTREAM-REPORTED` — Current official docs report, at a `top_200` retrieval budget, LoCoMo 92.5, LongMemEval 94.4, BEAM 1M 64.1, and BEAM 10M 48.6, with roughly 6.7K–7.0K mean tokens/query. The vendor states scores have ±1 point judge uncertainty and reflect managed Platform optimizations absent from OSS ([official results and caveat](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/core-concepts/memory-evaluation.mdx#L65-L140)).

`UPSTREAM-REPORTED` — The same vendor table reports lower BEAM 10M slices: multi-session reasoning 26.1, temporal reasoning 16.3, event ordering 20.2, and contradiction resolution 32.5. These reported weaknesses are useful hypotheses for MemOS, not independently verified measurements.

`INFERRED` — MemOS must not copy any Mem0 number into its own results. A fair baseline must pin dataset split, model/prompt/config, retrieval and answer budgets, run manifest, raw outputs, uncertainty, and Platform versus OSS backend.

## Why adopt or not adopt

### Adopt as design lessons

- `INFERRED` — **Extract durable facts instead of replaying transcripts.** This is a useful cross-session/token boundary for MemOS to test.
- `CONFIRMED` — **Keep new evidence additive by default.** It prevents early destructive LLM consolidation.
- `CONFIRMED` — **Use multiple retrieval signals.** Semantic, lexical, entity, and temporal signals solve different query classes.
- `CONFIRMED` — **Batch expensive work and degrade gracefully.** Batch embeddings/writes and optional NLP keep the library usable.
- `CONFIRMED` — **Scope memory explicitly.** User/agent/run identifiers are mandatory retrieval constraints.
- `CONFIRMED` — **Evaluate accuracy, tokens, and latency together.** The official evaluation reports all three categories.
- `UPSTREAM-REPORTED` — The vendor's 1M/10M tables expose weak slices worth targeting, but those observations are not independently reproduced.

### Do not adopt unchanged

- **ADD-only as the whole truth model** — preserve evidence, but derive explicit versions and statuses.
- **Vector payload as source of truth** — use transactional records as authority and rebuild indexes.
- **Exact hash/top-ten dedup** — add idempotency, global uniqueness, semantic/entity/predicate dedup, and provenance.
- **Semantic-gated hybrid retrieval** — union independent candidate generators before fusion.
- **Fixed score constants** — establish a transparent baseline and tune on labeled data without test leakage.
- **Best-effort multi-store writes** — use transaction + outbox + repair/reconciliation.
- **Equal trust for user and assistant facts** — persist source type, evidence, confidence, and admission policy.
- **Delete that retains content in side stores** — define and test end-to-end erasure.
- **Prompt-only security/privacy** — enforce deterministic pre-write and pre-injection controls.

## MemOS decision

`INFERRED` — Use Mem0 in three roles:

1. **Competitor baseline**: run pinned OSS and, if budget permits, Platform under the same benchmark contract.
2. **Implementation reference**: borrow batch extraction, explicit scoping, hybrid-signal observability, and graceful fallbacks.
3. **Negative-design evidence**: demonstrate why current-state truth, history, privacy deletion, idempotency, and transactional persistence cannot be delegated to similarity ranking.

Do not embed Mem0 as MemOS's core domain layer. MemOS should own:

```text
source evidence
→ idempotent extraction proposal
→ sensitive/trust policy
→ type/entity/predicate normalization
→ semantic dedup + conflict/temporal decision
→ versioned transactional record
→ outbox-driven semantic/lexical/entity projections
→ authorized hybrid retrieval
→ truth/time policy + rerank + token-budget context
```

## Open questions for benchmark or spike

- `HYPOTHESIS` — Does ADD-only evidence plus deterministic temporal versioning outperform LLM-driven overwrite/delete on update and contradiction cases?
- `HYPOTHESIS` — How much Recall@K is lost by Mem0's semantic-only candidate universe on exact IDs, rare names, and dates?
- `HYPOTHESIS` — Is entity matching worth its write amplification beyond PostgreSQL FTS + metadata/entity tables?
- `HYPOTHESIS` — What write precision is achieved when the prompt says “when in doubt, extract,” especially for assistant output and sensitive facts?
- `HYPOTHESIS` — Can a poisoned memory survive extraction, rank highly, and alter agent behavior despite role separation?
- `HYPOTHESIS` — What stale/raw data remains after each Mem0 deletion mode under the selected backend?
- `HYPOTHESIS` — At equal `top_k`, token budget, answer model, and latency budget, which gain comes from extraction, hybrid retrieval, temporal metadata, or reranking?

## Primary-source ledger

All accessed 2026-08-26.

- [Mem0 paper: *Building Production-Ready AI Agents with Scalable Long-Term Memory*](https://arxiv.org/abs/2504.19413)
- [Pinned Mem0 repository commit](https://github.com/mem0ai/mem0/tree/39bc02330563764e7d4465f1ecff5f002d94da1)
- [Pinned OSS V2→V3 migration guide](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/migration/oss-v2-to-v3.mdx)
- [Pinned How Mem0 Works](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/core-concepts/how-it-works.mdx)
- [Pinned memory evaluation](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/core-concepts/memory-evaluation.mdx)
- [Pinned memory types](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/core-concepts/memory-types.mdx)
- [Pinned add/search/update/delete guides](https://github.com/mem0ai/mem0/tree/39bc02330563764e7d4465f1ecff5f002d94da1/docs/core-concepts/memory-operations)
- [Pinned Platform graph memory](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/platform/features/graph-memory.mdx)
- [Pinned Platform temporal reasoning](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/platform/features/temporal-reasoning.mdx)
- [Pinned Platform memory decay](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/platform/features/memory-decay.mdx)
- [Pinned memory expiration](https://github.com/mem0ai/mem0/blob/39bc02330563764e7d4465f1ecff5f002d94da1/docs/platform/features/memory-expiration.mdx)
