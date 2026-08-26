# Letta source audit: current MemFS write, recall, reflection, and sync paths

Audit date: **2026-08-26**. This is a static source audit; no current Letta service was deployed and no vendor benchmark was reproduced.

## Repository and version boundary

| Role | Repository | Pinned commit | Audit decision |
|---|---|---|---|
| Current OSS implementation | [`letta-ai/letta-code`](https://github.com/letta-ai/letta-code) | [`852ca244b00253e7871e1e878bb8272d4b4a696a`](https://github.com/letta-ai/letta-code/commit/852ca244b00253e7871e1e878bb8272d4b4a696a), 2026-08-26 | Source of current implementation claims |
| Former Letta V1 repository | [`letta-ai/letta`](https://github.com/letta-ai/letta) | [`4511fa0bc91f68fbab32b91f694617271ea9012b`](https://github.com/letta-ai/letta/commit/4511fa0bc91f68fbab32b91f694617271ea9012b), 2026-08-23 | Boundary evidence only; implementation is retired |
| Historical architecture | MemGPT paper | [arXiv:2310.08560](https://arxiv.org/abs/2310.08560) | Research context, not 2026 source behavior |

`CONFIRMED` — the former repository’s `AGENTS.md` explicitly says `main` is a small landing page, V1 is preserved only on unsupported `archive`, old packages/images must not be used for benchmarks, and current code lives in `letta-ai/letta-code` ([pinned notice](https://github.com/letta-ai/letta/blob/4511fa0bc91f68fbab32b91f694617271ea9012b/AGENTS.md#L1-L38)).

Consequences:

- any old `letta/letta` Python class/function is `DEPRECATED` for current-behavior claims;
- current hosted backend internals not present in Letta Code are outside this source audit;
- API documentation can establish public behavior, but this audit does not infer its private persistence algorithm.

## Audited files and responsibilities

| File | Key symbols | Responsibility |
|---|---|---|
| [`src/agent/prompts/letta.md`](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/prompts/letta.md#L5-L80) | context architecture instructions | Defines recall history, in-context blocks, external memory, commit/recompile semantics |
| [`src/agent/memory-filesystem.ts`](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-filesystem.ts#L43-L143) | `getScopedMemoryFilesystemRoot`, `resolveScopedMemoryDir`, `ensureMemoryFilesystemDirs` | Maps agent identity to MemFS paths and creates directories |
| [same file](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-filesystem.ts#L177-L274) | `prepareRawCreateAgentBodyForMemfs`, `isMemfsEnabledOnServer`, `ensureLocalMemfsCheckout` | Enables/materializes MemFS for local and remote backends |
| [same file](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-filesystem.ts#L468-L575) | `applyMemfsFlags` | Reconciles prompt/settings/tools/tag and clones/initializes repository |
| [`src/agent/memory-runtime.ts`](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-runtime.ts#L5-L28) | `isActiveMemfsEnabled`, `getActiveMemoryDirectory` | Exposes active backend-specific MemFS state |
| [`src/tools/impl/memory.ts`](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/tools/impl/memory.ts#L98-L150) | `memory` | Foreground memory-tool entry point |
| [same file](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/tools/impl/memory.ts#L153-L321) | `applyMemoryCommand` | Create/replace/insert/delete/rename/update-description file operations |
| [`src/agent/memory-git.ts`](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-git.ts#L1188-L1381) | `commitMemoryPaths`, `assertMemoryRepoCleanForWrite`, `commitMemoryWrite` | Scoped staging, clean-tree guard, authored commit |
| [same file](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-git.ts#L1694-L1802) | `pullMemory`, `pushMemory` | Remote synchronization and retry/rebase behavior |
| [same file](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-git.ts#L1882-L2075) | `getMemoryConflictSummary`, `syncPendingMemoryCommitsAfterTurn` | Conflict/dirty/ahead detection and post-turn push |
| [`src/backend/message-search.ts`](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/backend/message-search.ts#L10-L52) | `searchMessagesForBackend`, `warmMessageSearchCacheForBackend` | Routes local/remote recall search; exposes vector/FTS/hybrid mode |
| [`src/cli/subcommands/dream.ts`](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/cli/subcommands/dream.ts#L92-L258) | `runDreamSubcommand` | Stages experience and launches background reflection |
| [`src/agent/subagents/builtin/memory.md`](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/subagents/builtin/memory.md#L9-L59) | memory-defrag prompt | Agentic split/dedup/reorganization policy |
| [`src/memory-confinement.ts`](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/memory-confinement.ts#L13-L28) | `createMemoryConfinementLauncher` | Exported wrapper whose documented contract requires fail-closed filesystem isolation for memory subagents |

## Call chain 1: create/enable and materialize MemFS

### Local backend

```text
applyMemfsFlags(agentId, ...)
→ backend.capabilities.localMemfs
→ getScopedMemoryFilesystemRoot(agentId)
→ initializeLocalMemoryRepo(memoryDir, agentId, files=[])
→ seedDefaultPersonalityFiles(..., syncMode="local")
→ settingsManager.setMemfsEnabled(agentId, true)
```

Evidence: [`applyMemfsFlags` local branch](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-filesystem.ts#L486-L510).

### Remote MemFS backend

```text
create-agent raw body
→ prepareRawCreateAgentBodyForMemfs
→ stamp GIT_MEMORY_ENABLED_TAG atomically with creation

applyMemfsFlags
→ validate backend/server capability
→ updateAgentSystemPromptMemfs
→ client.agents.recompile(update_timestamp=false)
→ persist local enabled setting
→ detachMemoryTools (old API memory tools)
→ addGitMemoryTag
→ cloneMemoryRepo OR pullMemory
→ seed default personality files
```

Evidence: [creation tag and checkout](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-filesystem.ts#L162-L274), [remote enable path](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-filesystem.ts#L513-L575).

`CONFIRMED` — current Letta Code deliberately switches from old API-based memory tools to Git-memory mode when MemFS is enabled. This is direct evidence that historical Letta server call chains are not the active public harness path.

## Call chain 2: foreground memory write

```text
model invokes memory(args)
→ validate command + non-empty reason
→ resolveMemoryDir()
   → resolveScopedMemoryDir()
      → current runtime agent ID first
      → explicit MEMORY_DIR / AGENT_ID fallbacks
→ ensureMemoryRepo
→ getAgentIdentity + determine local/remote sync mode
→ assertMemoryRepoCleanForWrite
→ applyMemoryCommand
   → create | str_replace | insert | delete | rename | update_description
→ commitMemoryWrite
   → normalize pathspecs
   → prepare local-only repo OR remote git/auth state
   → commitMemoryPaths
      → git add -A -- <affected paths>
      → verify staged change
      → git commit with agent name/email and reason
      → rev-parse HEAD
→ emitMemoryUpdated(affectedPaths)
→ return commit SHA prefix
```

Entry evidence: [`memory`](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/tools/impl/memory.ts#L98-L150). Mutation evidence: [`applyMemoryCommand`](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/tools/impl/memory.ts#L153-L321). Commit evidence: [`commitMemoryPaths` and `commitMemoryWrite`](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-git.ts#L1188-L1381).

### Concurrency semantics

`CONFIRMED` — the tool refuses to write when any uncommitted repository change exists. This prevents it from silently mixing an automated edit with an unrelated dirty working tree ([guard](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-git.ts#L1265-L1279)).

`CONFIRMED` — remote sync first checks dirty/conflict/ahead state. A non-fast-forward push triggers `pull --rebase`, conflict reinspection, and retry; conflicted files are returned as status instead of auto-resolved ([post-turn sync](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-git.ts#L1951-L2075)).

`INFERRED` — this is repository-level optimistic coordination, not record-level compare-and-swap. Two edits to different lines can merge cleanly while introducing a semantic contradiction. There is no reviewed function that compares normalized entity/predicate/time or produces `CURRENT/HISTORICAL/CONFLICTED/INVALIDATED`.

### Failure windows

| Window | Observed handling | Remaining risk |
|---|---|---|
| File mutated, Git commit fails | `commitMemoryPaths` tries to unstage then throws | Working tree can remain modified; later clean-tree guard forces explicit repair |
| Commit succeeds, remote push fails | Commit remains local; post-turn status reports `push_failed` | Other machines/agents have stale memory until retry |
| Remote advanced | pull/rebase then retry push | Manual semantic/text conflict resolution may be required |
| Process dies after local commit | Commit is durable locally; next sync can detect ahead count | Remote visibility is eventually consistent |
| Same logical event repeated | Identical resulting file yields no effective commit; otherwise a new commit | No source-event idempotency key; semantically duplicate text can accumulate |

## Call chain 3: read/recall and active context

The source exposes multiple read paths rather than one memory-search function.

### Always-present and external context

```text
committed MemFS revision
→ harness compiles system/ memory files into <memory> blocks
→ recent messages + summary enter current context
→ external file tree/descriptions expose discovery paths
→ agent uses file/search/reference tools to load detail on demand
```

`CONFIRMED` — the system prompt defines recall history, recent messages plus summary, editable system blocks, external files, shared memory, and the rule that a committed edit affects only a later recompile ([source](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/prompts/letta.md#L5-L80)).

### Recall-message search

```text
recall/history tool or subagent
→ searchMessagesForBackend(body)
→ local backend: searchLocalTranscriptMessages(storageDir, body)
→ remote backend: searchMessages({...body, search_mode})
→ search_mode ∈ {vector, fts, hybrid}
→ caller selects/uses returned experience
```

Evidence: [`searchMessagesForBackend`](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/backend/message-search.ts#L18-L38).

`N/E` — this adapter establishes supported routing/modes but not the private remote ranking formula, fusion weights, index consistency, or authorization implementation. The local search implementation was not in the bounded audit set. Do not claim a specific hybrid algorithm from this file.

`INFERRED` — message search retrieves retained experience that the agent is instructed not to mutate; it does not automatically promote a result into curated long-term learning. That promotion is another model/tool decision. This agent-facing constraint is not a claim that operators can never erase or rewrite backend data.

## Call chain 4: background reflection / dreaming

```text
letta dream [--from source/conversation] [--to target]
→ runDreamSubcommand
→ resolve agent and require MemFS enabled
→ optional parse/stage external source as synthetic transcript
→ optional sync target document into memory
→ build reflection/maintenance instruction
→ launchReflectionSubagent(
     agentId,
     conversationId,
     triggerSource="manual",
     description="Reflect on recent conversations",
     recompile tracking ...)
→ reflection agent reads experience + MemFS
→ edits/commits memory
→ completion triggers future context recompile
```

Evidence: [`runDreamSubcommand`](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/cli/subcommands/dream.ts#L92-L258).

`CONFIRMED` — `--effort` is accepted but explicitly not wired at this commit ([source](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/cli/subcommands/dream.ts#L141-L146)). Do not attribute an implemented effort policy to this version.

`INFERRED` — the reflection agent is a semantic consolidation worker. Its write quality depends on model/prompt behavior; Git records what changed but does not validate that the change is true, non-sensitive, non-duplicative, or temporally correct.

## Update, delete, temporal, and conflict audit

| Concern | Source behavior | MemOS interpretation |
|---|---|---|
| Create | New Markdown with required description | Free-form artifact, not typed fact |
| Update | Exact string replacement, insertion, rename, or description edit | No normalized semantic diff |
| Delete | File/directory unlink/removal, then commit | Logical removal; old blob may remain in retained Git history/remotes |
| Version | Content-addressed commit snapshot/diff while refs/objects are retained | Excellent human audit/rollback; not WORM, refs can rewrite and objects can GC |
| Concurrent conflict | Dirty-tree guard; Git merge/rebase conflict detection | Protects bytes, not beliefs |
| Event time | No reviewed field | Git author/commit time is edit time, not event/valid time |
| Truth state | No reviewed enum/transition | Must be added in MemOS domain model |
| Dedup | Agent/defrag prompt asks for one canonical fact | Useful heuristic; no invariant |
| Forgetting | Agent may delete/move/compact/reorganize files | No deterministic TTL/decay/retention proof |

## Security and privacy audit

### Confirmed controls

- Runtime identity precedes environment fallbacks in memory-path resolution ([source](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/memory-filesystem.ts#L90-L128)).
- The exported memory-subagent wrapper documents a fail-closed contract when no supported sandbox exists and says the launcher must deny access to other agents’ memory; it delegates to `createMemoryConfinementLauncherWithAvailability`, whose deeper policy implementation was outside this bounded audit ([source](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/memory-confinement.ts#L13-L28)).
- Tool paths are normalized/resolved inside the active memory directory before mutation; protected/read-only files are validated by hooks and loaders ([dispatcher](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/tools/impl/memory.ts#L324-L472)).
- The system prompt explicitly prohibits credentials/API keys/tokens in Git memory ([source](https://github.com/letta-ai/letta-code/blob/852ca244b00253e7871e1e878bb8272d4b4a696a/src/agent/prompts/letta.md#L26-L33)).

### Missing or insufficient for MemOS

- `N/E` — deterministic PII/sensitivity classification before persistence;
- `N/E` — source trust, taint, quarantine, or durable prompt-injection scanning;
- `N/E` — row/field-level multi-tenant authorization for content inside a shared repository;
- `N/E` — encryption/key rotation/crypto-erasure contract for repository history and every clone;
- `N/E` — verified cascading deletion across recall history, MemFS, remotes, caches, indexes, traces, and backups.

`INFERRED` — prompt-level “never store secrets” is defense in depth, not enforcement. The writer is the same fallible model interpreting untrusted experience.

## What the source proves—and does not

### Proven at the pinned commit

- current OSS memory artifacts are agent-scoped Git-backed files;
- experience/recent-context/curated-memory are separate tiers;
- effective foreground memory edits are committed with reason/agent identity;
- remote visibility is a later sync step with explicit dirty/conflict/failure states;
- recall search accepts vector/FTS/hybrid mode;
- reflection can process a conversation/source in a separate agent path;
- the exported memory-worker launcher has a documented fail-closed filesystem-confinement contract; the deeper policy implementation was not independently audited here.

### Not proven

- hosted backend database/schema/transaction behavior;
- search ranking/fusion algorithm behind the remote API;
- structured extraction/classification/dedup/conflict/time algorithms;
- retention, decay, PII, or compliance erase guarantees;
- independent benchmark superiority;
- correctness under multi-machine races beyond Git’s textual mechanisms.

## MemOS decisions from this audit

| Pattern | Decision |
|---|---|
| Git-backed learned prompts/skills with reasons and diffs | Adopt for procedural/artifact memory |
| Immutable-ish experience log separated from curated projection | Adopt, with tamper-evident/retention controls stronger than ordinary Git where required |
| Model proposes memory changes | Adopt only before deterministic policy/validation |
| Agent-scoped filesystem sandbox | Reuse principle for memory workers/tools |
| Post-turn asynchronous sync status | Reuse explicit state/metrics; implement outbox/retry/reconciliation for indexes |
| Free-form Markdown as factual source of truth | Reject for core semantic/episodic facts |
| Git timestamp/history as temporal reasoning | Reject; add event/observation/valid time and truth-status versions |
| File deletion as privacy erasure | Reject; build and verify a multi-projection erase workflow |

## Bottom line

`INFERRED` — current Letta Code is a strong reference for **agent-owned, auditable context artifacts** and for separating experience, in-context identity/rules, and external detail. It is not evidence that free-form Git memory solves production fact selection, semantic conflict, temporal truth, authorization, or erasure. MemOS should combine Letta’s context ergonomics with a transactional, versioned, provenance-bearing domain service.
