# MemGPT / Letta analysis: virtual context, context repositories, and production gaps

Research cutoff: **2026-08-26**. Primary sources are the MemGPT paper, current Letta documentation, and current Letta Code source pinned at commit [`852ca244b00253e7871e1e878bb8272d4b4a696a`](https://github.com/letta-ai/letta-code/commit/852ca244b00253e7871e1e878bb8272d4b4a696a). Exact source call chains are in [the Letta source audit](../source-analysis/02-letta-source.md).

## Fact grades and version boundary

- `CONFIRMED`: directly supported by a linked paper, official document, or pinned source.
- `UPSTREAM-REPORTED`: a paper author or vendor reports a setup/result that MemOS has not independently reproduced.
- `INFERRED`: an engineering interpretation consistent with confirmed evidence.
- `HYPOTHESIS`: a claim MemOS must test.
- `DEPRECATED`: historically real but not current for the named implementation.
- `N/E`: no evidence of the requested capability in the reviewed boundary.

“MemGPT / Letta” now names materially different systems:

1. **MemGPT research architecture (2023/2024)** — an OS-inspired virtual-context design with an LLM controlling movement between in-context and external memory, plus interrupts and recursive tool use ([paper](https://arxiv.org/abs/2310.08560)).
2. **Letta API / hosted product** — stateful agents, persistent editable blocks, recall/archival memory, shared blocks, and sleep-time agents exposed through SDKs and APIs ([current SDK overview](https://docs.letta.com/api/typescript)).
3. **Letta Code (2026 OSS harness)** — a memory-first agent harness whose current long-term-memory substrate is a Git-backed Markdown filesystem, MemFS/context repositories, plus retained message history that the agent is instructed not to mutate ([current repository](https://github.com/letta-ai/letta-code/tree/852ca244b00253e7871e1e878bb8272d4b4a696a)).

`CONFIRMED` — the formerly canonical [`letta-ai/letta`](https://github.com/letta-ai/letta) repository no longer contains the current server on `main`. Its pinned `AGENTS.md` says `main` is a small landing page, V1 source is retired on `archive`, and current work belongs in `letta-ai/letta-code` ([retirement notice](https://github.com/letta-ai/letta/blob/4511fa0bc91f68fbab32b91f694617271ea9012b/AGENTS.md#L1-L38)). Any analysis that treats old `letta/letta` Python source as current Letta is `DEPRECATED`.

## Executive verdict

`CONFIRMED` — MemGPT treats the context window as scarce working memory and gives the agent explicit operations to move or rewrite information across memory tiers. The paper demonstrates this on long-document analysis and multi-session chat, where data exceeds the base model’s context window ([paper abstract and §3–4](https://arxiv.org/html/2310.08560)).

`CONFIRMED` — current Letta Code evolves that idea into **context ownership**: all message history is automatically retained as recall memory; recent messages and a summary occupy the active window; high-value memory blocks become editable system-prompt segments; other Markdown/files/skills stay external and are loaded on demand. The current prompt makes these tiers explicit ([context architecture](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/prompts/letta.md#L5-L47)).

`INFERRED` — this is more than `Embedding + Vector DB + TopK`: it includes autonomous write decisions, explicit hot/cold context tiers, procedural memory, message recall, background reflection, Git version history, shared memory, and a token-space learning loop.

`N/E` — the reviewed current public harness and product documents do not establish a typed, deterministic pipeline for `shouldRemember`, provenance spans, PII admission, semantic deduplication, entity resolution, valid-time intervals, `CURRENT/HISTORICAL/CONFLICTED/INVALIDATED` state, importance/decay, policy-complete erase, or benchmarked hybrid retrieval. This is a bounded evidence statement, not proof that no private component exists.

Recommendation: **adopt Letta’s tiered-context and auditable self-editing lessons; do not use free-form Git history as MemOS’s authoritative temporal truth model.**

## What counts as memory

### Historical MemGPT

`CONFIRMED` — MemGPT models an LLM as a CPU and the context window as fixed-size main memory. It divides prompt-resident context into system instructions, conversational context, and working context; external context contains recall storage for conversation history and archival storage for arbitrary long-term data. The LLM invokes functions to page data and edit working memory ([paper §2, Figure 1](https://arxiv.org/html/2310.08560#S2)).

`INFERRED` — the central abstraction is **LLM-controlled virtual context**, not automatic fact extraction. Memory quality therefore depends on whether the model recognizes pressure, selects the right operation, and writes a useful compressed representation.

### Current Letta Code

`CONFIRMED` — current source describes three operational layers:

- **Recall memory / experience**: all messages across concurrent conversations are automatically stored and cannot be mutated by the agent; recent messages plus a summary of older evicted messages occupy the current context ([prompt](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/prompts/letta.md#L8-L14)).
- **In-context memory blocks**: editable system-prompt segments for identity, durable preferences, behavioral rules, and indexes into deeper context ([prompt](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/prompts/letta.md#L26-L33)).
- **External memory**: skills, Markdown, images, and attached shared repositories outside the system prompt, discoverable and loaded on demand ([prompt](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/prompts/letta.md#L35-L47)).

The current docs call MemFS a Git-backed filesystem/context repository storing Markdown memory; files under `system/` are always loaded, while other files remain discoverable but are loaded only when relevant ([official MemFS documentation source](https://github.com/letta-ai/letta-docs-md/blob/main/concepts/memfs/index.md)).

### Conversation history is not memory

`CONFIRMED` — Letta itself separates retained recall history, which its prompt says the agent cannot mutate, from curated learning. It explicitly tells the agent not to copy material easily recovered from history into expensive in-context blocks ([history behavior](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/prompts/letta.md#L8-L14), [deduplication instruction](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/prompts/letta.md#L30-L33)).

`INFERRED` — history is attributable experience optimized for later search; memory blocks are a mutable policy/knowledge projection optimized to affect future behavior. The former preserves evidence but is noisy; the latter is concise but can be wrong, stale, or poisoned. MemOS should keep both and link every derived memory back to source evidence.

### Vector search is not memory

`CONFIRMED` — Letta Code’s message-search adapter supports `vector`, full-text (`fts`), and `hybrid` modes, while MemFS itself is navigated through file hierarchy, descriptions, references, and ordinary search tools ([adapter](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/backend/message-search.ts#L10-L38)).

`INFERRED` — retrieval only exposes candidates. It does not decide what should alter the agent’s identity, whether a correction supersedes an old fact, which content is sensitive, or whether a recalled instruction is authorized. Letta’s memory design is richer precisely because retrieval is only one part of a context-maintenance loop.

## Product capability versus architecture primitive

| Layer | Product-managed behavior | Primitive exposed to builders | Not guaranteed |
|---|---|---|---|
| Historical MemGPT | Virtual context, function-driven paging, recursive execution | Memory-edit/search tools and prompt policy | Deterministic extraction, temporal truth, production consistency |
| Letta API | Persistent agent state, blocks, recall/archival memory, SDK CRUD, shared blocks, sleep-time agents | Block/message/agent APIs | Public server implementation details, transactional cross-tier semantics |
| Letta Code OSS | Immutable experience, summaries, MemFS projection, Git commits/sync, recall/reflection subagents | Files, Git, memory commands, search modes, schedules | Typed facts, conflict/time engine, PII policy, complete erase |
| Model inside either product | Chooses when and how to write/reorganize memory | Natural-language reasoning and tool calls | Stable write precision, trust calibration, reproducible decisions |

The distinction matters: attaching the same block to several agents is a confirmed multi-agent product capability ([official tutorial](https://docs.letta.com/tutorials/attaching-detaching-blocks/)); it does not establish a general ACL, merge policy, or conflict-free replicated memory protocol.

## Lifecycle coverage matrix

| Required dimension | Current evidence | Grade and gap |
|---|---|---|
| Memory definition | Persistent, model-editable context/experience across invocations | `CONFIRMED`; no single typed domain model |
| Write trigger | Agent calls a memory tool/directly edits files; reflection/dream agent may edit between turns | `CONFIRMED`; model-driven, not calibrated `shouldRemember` |
| Extraction | Agent interprets experience and writes durable lessons; reflection prompt asks for facts/preferences/corrections | `CONFIRMED`; no public deterministic extractor contract or source span |
| Classification | In-context blocks, external files, skills/procedural memory, recall history | `CONFIRMED`; semantic/episodic/entity/task are organizational choices, not validated enums |
| Sensitive-data filter | Prompt says never store credentials/API keys/tokens | `CONFIRMED` instruction; `N/E` for content PII classifier or enforcement before commit |
| Normalization | Markdown frontmatter and a required description; memory-defrag subagent can restructure files | `CONFIRMED`; no canonical subject/predicate/object normalization |
| Deduplication | Reflection/defrag instructions remove redundancy and keep one canonical location | `CONFIRMED` agent policy; nondeterministic, no uniqueness invariant |
| Conflict detection | Git detects concurrent textual conflicts; reflection may repair stale contradictions | `CONFIRMED` repository conflict; `N/E` for semantic contradiction status |
| Importance | Agent is instructed to reserve system-prompt space for high-value context | `CONFIRMED` qualitative policy; no persisted/calibrated importance score |
| Temporal processing | Message history and Git commits preserve ordering/change history | `CONFIRMED` audit chronology; no `event_time`, `valid_from/to`, or as-of query |
| Persistence | Local or remote Git repository plus backend message storage | `CONFIRMED`; availability/backup depend on deployment and remote sync |
| Retrieval | Always-in-context blocks; tree/references/filesystem search; recall message search supports vector/FTS/hybrid | `CONFIRMED`; no unified rank/fusion contract across tiers |
| Update | `str_replace`, `insert`, rename, description update, direct file edit; every effective tool edit commits | `CONFIRMED`; content is free-form and no expected-version API is exposed by the tool |
| Delete | File/directory delete becomes a Git commit | `CONFIRMED` logical deletion; prior Git objects/remote clones can retain content |
| Decay/forgetting | Reflection may discard noise; blocks can be moved out of `system/` | `CONFIRMED` agentic consolidation; `N/E` for TTL/access/importance decay |
| Context management | Recent messages + summary; system files always loaded; external detail on demand; `/doctor` flags growth | `CONFIRMED`; behavior remains model/harness dependent |
| Prompt-injection defense | The exported subagent-launcher wrapper documents fail-closed filesystem confinement; read-only files/hooks exist | `CONFIRMED` wrapper contract and path controls; `N/E` for the deeper confinement policy and semantic durable-injection detection |
| Token optimization | Tiering, concise blocks, summaries, progressive disclosure, background work | `CONFIRMED`; no workload-level optimality guarantee |
| Multi-agent memory | Shared blocks/repositories and specialized subagents | `CONFIRMED`; sharing is not fine-grained row/field ACL |
| Privacy | Per-agent path scope, an exported wrapper contract for sandbox isolation, secret-store instruction | `CONFIRMED` path scope and wrapper contract; no demonstrated crypto-erasure or deletion proof |
| Evaluation | Letta publishes Context-Bench V2 and earlier filesystem-memory experiments | `UPSTREAM-REPORTED`; V2 is private and was not independently reproduced here |

## Write path

### Foreground self-editing

`CONFIRMED` — the public `memory` tool requires a command and reason, resolves an agent-scoped MemFS directory, refuses to run over a dirty repository, applies a file command, creates a Git commit with agent authorship, and emits a refresh event ([entry point](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/tools/impl/memory.ts#L98-L150)). Supported commands are create, replace, insert, delete, rename, and description update ([command dispatcher](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/tools/impl/memory.ts#L153-L321)).

```text
conversation / recalled experience
→ model decides a durable change is useful
→ memory(command, reason, target, content)
→ resolve agent-scoped MemFS
→ require clean working tree
→ mutate Markdown/file
→ stage only affected paths
→ Git commit with agent identity
→ emit memory_updated
→ after turn: push, or pull --rebase then push
→ future context recompile sees the committed revision
```

`CONFIRMED` — edits do not retroactively change the current turn’s compiled system prompt. They apply on a later conversation/recompile/revision ([prompt timing rule](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/prompts/letta.md#L49-L59)). This avoids a misleading “tool result instantly rewrote the instructions already executing” model.

### Background consolidation

`CONFIRMED` — Letta’s sleep-time design separates a primary conversational agent from a background agent that edits shared in-context memory, moving memory formation off the latency-sensitive path ([official explanation](https://www.letta.com/blog/sleep-time-compute/)). Current Letta Code’s `dream` command stages a source/conversation, launches a reflection subagent, and requests a later recompile ([source](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/cli/subcommands/dream.ts#L127-L258)).

Trade-off:

- foreground writes are immediately attributable and available for the next turn, but add tool latency and divide model attention;
- background writes avoid user latency and can consolidate across experience, but introduce freshness lag, asynchronous failure, and more complex conflict/retry semantics.

`HYPOTHESIS` — MemOS should benchmark both modes with equal extraction budget: write precision/recall, contradiction rate, time-to-visibility, foreground latency, and harmful-memory utilization.

## Read path and context construction

`CONFIRMED` — current Letta Code does not run one monolithic TopK query. It composes context from:

1. identity/system memory files already compiled into the system prompt;
2. recent messages and a summary of older messages;
3. a tree/description/index that helps the model discover external memory;
4. targeted file reads, references, skills, and recall-message search;
5. optional specialized recall/history subagents that protect the primary context budget.

`INFERRED` — this “hierarchical discovery” is a useful alternative baseline to flat vector recall. It makes memory paths human-auditable and lets the agent decide how much detail to load. Its weakness is model-dependent navigation: a missed description or failure to search becomes a recall miss even when data exists.

`HYPOTHESIS` — benchmark Letta-style hierarchy against independent semantic/BM25/entity/temporal candidate union under the same token budget. Measure retrieval recall, navigation tool calls, latency, and injected tokens—not only final-answer accuracy.

## Update, conflict, time, and forgetting

### Versioning versus truth state

`CONFIRMED` — while refs and objects are retained, Git provides content-addressed snapshots, diffs, author/reason metadata, rollback, remote synchronization, and textual merge conflicts. The tool commits only effective changes and scopes pathspecs ([commit path](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-git.ts#L1188-L1247)). `INFERRED` — this is useful version history, not an immutable compliance/WORM audit log: refs can be rewritten and unreachable objects can be garbage-collected.

`INFERRED` — repository history is an excellent audit/version mechanism but not a temporal truth model. Given “lives in Shanghai” followed by “moved to Hangzhou,” a file edit may replace the sentence and Git preserves the old revision, but no current public schema deterministically represents:

```text
Shanghai: HISTORICAL, valid_to = move time
Hangzhou: CURRENT, valid_from = move time
```

Git commit time is observation/edit time, not necessarily event time or validity time. An as-of query would require replaying unstructured Markdown and inferring semantics again.

### Conflict resolution

`CONFIRMED` — post-turn sync detects merge/rebase conflicts and reports conflicted files; it retries non-fast-forward pushes through pull/rebase ([sync path](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-git.ts#L1951-L2075)).

`INFERRED` — this handles concurrent bytes, not concurrent beliefs. Two non-overlapping lines can be semantically contradictory and merge cleanly. Conversely, a textual conflict may contain two compatible facts. MemOS needs entity/predicate/time-aware conflict detection above storage concurrency control.

### Forgetting and pollution

`CONFIRMED` — current prompts instruct agents to keep blocks lean, avoid duplicating recallable history, move detail external, generalize lessons, and use memory defragmentation to split topics/remove redundancy ([prompt](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/prompts/letta.md#L26-L41), [defrag policy](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/subagents/builtin/memory.md#L13-L59)).

`INFERRED` — this is agentic garbage collection. It can improve coherence and generalization, but it lacks deterministic retention guarantees, per-type TTL, calibrated decay, legal holds, or a reviewable reason for every removed fact.

## Security and privacy

### Useful confirmed controls

- Per-agent context is resolved from runtime/agent identity before environment fallbacks ([scope resolution](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-filesystem.ts#L90-L128)).
- The exported unattended-memory launcher documents a fail-closed filesystem-confinement contract: a worker may write its own memory but must not access other agents’ memory. The audited wrapper delegates to `createMemoryConfinementLauncherWithAvailability`; the deeper policy implementation was outside this bounded audit ([confinement](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/memory-confinement.ts#L13-L28)).
- Memory files may be marked read-only; pre-commit hooks validate frontmatter/protected files; the prompt tells agents never to place credentials in Git-tracked memory ([prompt](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/prompts/letta.md#L26-L33), [validation contract](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/prompts/letta.md#L54-L59)).

### Gaps

`INFERRED` — memory blocks are system-prompt material, so a malicious instruction that reaches them becomes a durable, higher-authority injection. Filesystem confinement limits which bytes a process may access; it does not judge whether those bytes are safe instructions. No reviewed path establishes trust labels, source authority, quarantine, or an admission-time durable-injection classifier.

`CONFIRMED` — `delete` removes a file and commits that deletion, but normal Git history preserves earlier blobs and remote clones may retain copies ([delete implementation](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/tools/impl/memory.ts#L249-L265)). Therefore logical deletion is not proof of privacy erasure.

`INFERRED` — production deletion requires a separate contract: stop serving immediately; remove/cryptographically erase authoritative content; delete search/vector/cache projections; cancel or neutralize queued jobs; retain only policy-approved tombstone metadata; verify completion across replicas/backups under stated retention.

## Evaluation evidence

`CONFIRMED` — the MemGPT paper evaluates document analysis and multi-session chat, demonstrating the virtual-context concept under then-current models; it is not evidence for current Letta Code’s Git/MemFS implementation or production scalability ([paper](https://arxiv.org/abs/2310.08560)).

`UPSTREAM-REPORTED` — Letta’s July 2026 article says Context-Bench V2 evaluates memory usage (adherence/retrieval) and memory generation (generalization/hygiene) with realistic accumulated memory profiles and multi-turn judged scenarios. The article also says the benchmark is private and uses vendor-selected profiles/judges; this audit did not independently reproduce its setup or results ([official report](https://www.letta.com/blog/evaluating-memory-in-production-agents/)).

`INFERRED` — the generation/hygiene split is a strong lesson for MemOS, but the reported model rankings are not independently reproducible evidence for Letta versus other memory systems. MemOS should add:

- write precision/recall and harmful-write rate;
- duplicate/stale/contradiction density after long runs;
- as-of temporal accuracy and change-point accuracy;
- source attribution and abstention;
- delete-completion and cross-tenant isolation tests;
- retrieval/answer accuracy at fixed latency/token/storage budgets.

## MemOS adoption decisions

| Letta technique | Decision | Reason |
|---|---|---|
| Separate retained, agent-read-only experience from curated learning | Adopt | Preserves an evidence tier while allowing compact, mutable projections; operator deletion/history-rewrite semantics remain separate |
| Tiered in-context blocks + external detail | Adopt conceptually | Explicit token budget and progressive disclosure are better than replaying everything |
| Agent-generated memory proposals | Adopt behind policy | Flexible semantic judgment, but authorization/time/status/destructive actions remain deterministic |
| Background reflection | Prototype and benchmark | Can improve latency and consolidation; needs idempotency, freshness, retries, and observability |
| Git audit trail for memory artifacts/prompts | Adopt for procedural/artifact memory | Human-readable diffs and rollback are excellent for learned prompts/skills |
| Git/Markdown as all-memory source of truth | Do not adopt | Weak structured queries, CAS, tenant authorization, valid-time semantics, and erasure |
| Hierarchical file discovery | Add as an external-memory option | Transparent and token-efficient; benchmark against hybrid indexed retrieval |
| Shared blocks/repositories | Adopt only with explicit ACL/version contract | Sharing is valuable but amplifies poisoning and concurrent-update risk |
| “Never store secrets” prompt alone | Do not treat as a control | Needs deterministic sensitive-data policy and secret scanning before persistence |

## Bottom line

`INFERRED` — MemGPT/Letta’s deepest lesson is that memory is **active context management and continual state evolution**, not a passive vector lookup. Current Letta Code adds unusually strong auditability and agent ownership through Git-backed context repositories. MemOS should preserve those ideas while moving factual memory into a governed transactional model with provenance, temporal versions, deterministic policy, rebuildable indexes, complete deletion, and benchmarked retrieval.
