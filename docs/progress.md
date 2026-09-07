# Project progress

Last updated: 2026-09-07 (Asia/Shanghai)

## Research

Status: `DONE`

- Repository inspected: initialized empty Git repository on `main`; no commit and no remote at inspection time.
- Primary-source studies cover Mem0, Letta/LangGraph, Zep/Graphiti, frameworks, and three memory benchmarks.
- Mem0, Letta, LangGraph, and Graphiti source paths are pinned to full commit SHAs with call-chain audits.
- The evidence-graded competitive matrix and cross-system design lessons are complete.
- Initial Phase 1 snapshot `183d876` was pushed to `origin/main`; remote publication is verified.

## Architecture

Status: `DONE` for Phase 1 design; ADRs 0001–0004 remain `PROPOSED`, ADR-0005 is `ACCEPTED`

- Problem definition, three candidates, trade-offs, and recommendation are documented.
- Proposed direction: Java modular monolith + durable outbox/projection workers + PostgreSQL/pgvector/FTS.
- Assertion versions and state transitions are append-only while retained; governed erasure is an explicit exception.

## MVP

Status: `DOING` — Feature 5 is `DONE / PUBLISHED`; Feature 6 has a verified negative frozen result and a runnable consumer

- Phase 1 was reviewed and the active project goal authorizes Features 0–6.
- Feature 0 engineering foundation is `DONE`: local Maven/Testcontainers, pgvector migration, architecture, Python, documentation, and API/worker smoke gates passed. The workflow is published and exercised by green GitHub Actions run `#17`.
- Feature 1 is `DONE` and published: source-event receipt and outbox commit atomically; claim/lease/fencing/retry/dead/replay and a payload-free logical-effect ledger passed PostgreSQL fault/concurrency tests and runtime smoke.
- Feature 2 is `DONE` and published in `6292b150851218fe6ab480115bde24a214b4d411`: provider-neutral strict extraction, deterministic trust/sensitivity/write policy, sanitized candidate/quarantine persistence, lease-fenced atomic completion, optional real-provider adapter, 17-case conformance fixture, and runtime smoke passed.
- Feature 3 is `DONE` and published in `5ff32fda451f3923e4130e309e5c19167e84905d`: versioned temporal authority, deterministic transition semantics, correction/invalidation, scoped APIs, 14-case temporal conformance, PostgreSQL fault/concurrency coverage, and API→worker→database restart smoke passed.
- Feature 4 is `DONE / PUBLISHED` through `69c5f63e836160aab04fc9995259f9c87aa2ca3e`: rebuildable vector/FTS projections, transition-watermarked projection jobs, scoped hybrid RRF retrieval, reranker fallback, trace restriction, evidence-budgeted context, and a six-case deterministic conformance fixture are implemented. GitHub Actions run `#17` passed JVM/PostgreSQL, Python, docs, and the full Feature 0–4 compose smoke, including governed invalidation, zero-row projection cleanup, and worker restart.
- Feature 5 is `DONE / PUBLISHED` through `ae377143929cf2a7fcfbfccae21d8792b7275d7e`:
  verified JWT scope, `USER`/`OPERATOR`/`PRIVACY_ADMIN` boundaries, content-safe errors and trace
  audit, memory/user deletion operations, immediate projection hiding, lease-fenced atomic erasure,
  retry/dead/privacy-admin requeue, opaque append-only tombstones, replay/resurrection guards, and
  a four-case hostile-memory rendering fixture are implemented. GitHub Actions
  [run #22](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33272314267) passed Java 25,
  PostgreSQL migration/fault/concurrency tests, Python, docs, and the full Feature 0–5 compose
  smoke. This is implementation evidence, not formal security effectiveness or model-quality data.
- Feature 6's first contract gate is `DONE / PUBLISHED` through
  `4120144a3afa2b9c7e443fe3c27e88eb01595330`: the bounded bilingual
  personal/project-assistant workload, 13-scenario/15-question synthetic smoke dataset, frozen
  train/dev/test IDs, CC BY 4.0 license and attribution, answer/summary prompt hashes, equal-budget
  four-baseline contract, and exact local Ollama model IDs are machine-verified. GitHub Actions
  [run #24](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33273671807) passed Java 25,
  PostgreSQL/compose regression, Python, and docs gates. No baseline run, quality score, latency
  result, or cost result exists yet.
- Feature 6 run-package core is `DONE / PUBLISHED` through
  `afcabe79670344dab8ed1500eb59b3a17f293842`: immutable file-set hashing,
  split-membership/config identity, full execution-row accounting, mechanically regenerated
  answer/retrieval/abstention/track/latency metrics, usage totals, and content-safe failure
  summaries pass 28 Python tests. The current v1 candidate also pins Java extraction prompt/schema
  hashes and forbidden-context labels. GitHub Actions
  [run #26](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33274839393) passed Java 25,
  PostgreSQL/compose regression, Python, and docs gates. This is harness evidence, not a baseline
  run.
- Feature 6 provider/baseline primitives are `DONE / PUBLISHED` through
  `367e0fa5d5232793e297ea007324ba144cd660f5`: full Ollama digest/capability verification,
  structured chat and embedding accounting, strict answer/summary schemas, exact rendered-context
  token budgeting, and the full-history, rolling-summary, and raw-turn-vector context builders pass
  40 Python tests. GitHub Actions
  [run #28](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33276271008) passed Java 25,
  PostgreSQL/compose regression, Python, and docs gates. The Java MemOS runner and all result rows
  remain `NOT RUN`.
- Feature 6's observable MemOS settlement path is `DONE / PUBLISHED` through
  `2bf7689cb11c37e6eb73744bcf6e79915775a9e7`: a hard-scoped source-level aggregate covers
  extraction, authoritative-materialization, and projection jobs, while a bounded Python client
  waits on processing/success/failure instead of a fixed sleep. GitHub Actions
  [run #30](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33277348058) passed Java 25,
  PostgreSQL integration, Python, docs, and the complete compose smoke. This is
  harness/freshness-mechanism evidence, not a measured freshness distribution or SLO; the unified
  runner remains incomplete.
- Feature 6's Java Ollama embedding and long-call lease safety is `DONE / PUBLISHED` through
  `9225ed101ca19b4441438d13915b238bf8e3869f`: API and worker share one
  digest/capability/dimension-pinned adapter, V007 supports a checked 1024-dimensional provider
  projection, provider failures map to durable retry/dead semantics, and PostgreSQL-time fenced
  heartbeats cover every serially claimed batch item. GitHub Actions
  [run #32](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33279370001) passed Java 25,
  PostgreSQL migration/renewal/fault/concurrency tests, Python, docs, and the complete compose smoke.
  This is implementation evidence, not model-quality, latency, freshness, or scale evidence.
  Model-version projection reconciliation remains unresolved.
- Feature 6's equal-budget Java context milestone is `DONE / PUBLISHED` through
  `c5035c313e84ef1e0b05b96d65f2829a64b339ba`: every tentative
  complete context is counted by the configured embedding tokenizer, the selected Ollama path
  therefore shares the Python baselines' digest-pinned counter, and provider calls/input tokens
  spent on budget checks are returned for cost attribution. GitHub Actions
  [run #34](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33280316053) passed Java 25,
  PostgreSQL migration/fault/concurrency tests, Python, docs, and the complete compose smoke.
  Runner-side parity verification remains pending; no baseline result exists yet.
- Feature 6's unified-runner and exact-cost milestone is `DONE / PUBLISHED` through
  `db213df6c51a8ce680a8df1aaba12cadb811ac38`: all four
  baselines share one answer path and evidence budget; MemOS uses isolated JWT scopes, waits for
  every source chain, maps API UUID provenance back to dataset event IDs, and independently
  retokenizes returned Java contexts. V008 persists content-free projection embedding usage, the
  source aggregate rejects retry/replay-incomplete accounting, and the artifact verifier now
  requires exactly one explicit-usage write row per baseline/scenario/repetition. GitHub Actions
  [run #35](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33281737584) passed Java 25,
  V008/PostgreSQL integration, Python format/lint and 58 tests, documentation, and the complete
  compose smoke. No real-model run, score, latency distribution, storage result, or SLO exists yet.
- Feature 6's storage-observation and mechanical-report milestone is `DONE / PUBLISHED` through
  commit `46ecdd7` and
  [GitHub Actions run #37](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33284193760).
  An operator-only endpoint derives the exact tenant/user/agent scope from JWT claims and returns
  relation-level PostgreSQL row counts/`pg_column_size(record)` bytes plus separately labeled
  database-native table/index allocation. The three local baselines disclose canonical UTF-8 or
  dense float32 representations. `storage.json` and `report.md` are integrity-covered and
  independently regenerated. Run #37 passed Java 25 clean verify with PostgreSQL integration,
  Python format/lint and 65 tests, documentation, and the complete compose smoke. Abstention F1
  reports `0.0` for missed or spurious positive cases and reserves `N/A` for the structurally
  inapplicable no-gold/no-prediction case. This is harness evidence, not a storage, quality,
  latency, or cost result.
- Feature 6's extraction-identity hardening is `DONE / PUBLISHED` through commit `4920e55` and
  [GitHub Actions run #39](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33287028091):
  OpenAI-compatible request tags are separate from deployment-attested provenance, native Ollama
  extraction verifies a full digest and completion capability, API/worker jobs share the immutable
  extraction version, and mismatched old jobs fail permanently without a provider call. Run #39
  passed Java 25, PostgreSQL integration, Python, documentation, and the complete compose smoke.
  This is not a real-model benchmark result.
- Feature 6 post-gate remediation and consumer integration are `DONE / PUBLISHED` through MemOS
  commit `0ce3ac0` and Waku Agent commit
  [`b75adf2`](https://github.com/Zhi-Xiang-Guo/waku-agent/commit/b75adf2). Verified JWT roles now
  become persisted source write capabilities; the policy-v3 prompt passed the bounded dev cases;
  selected evidence aliases and constrained citation decoding close the observed harness mismatch.
  The immutable policy-v3 dev smoke completed 12/12 answer rows with complete accounting and
  verifier hash `00e47ad341e5a3b25aff6b41c8630a5ad94f568ff3b7f6420df99db0df7da714`.
  Waku's real MemOS backend passed 12/12 live `FactStore` conformance cases and its repository gate
  passed 619 deterministic tests. These are development and consumer-mechanism results; the frozen
  temporal-v2 result remains 0/30 and no new held-out quality claim is permitted.

## Advanced Memory

Status: `TODO`

- Temporal conflict semantics, forgetting, pollution control, and optional graph projections require MVP evidence.

## Benchmark

Status: `DOING` — three published real-model dev runs and one frozen test verified; reports reconstruct mechanically. The milestone history above records status at each earlier commit.

- LoCoMo, LongMemEval, and BEAM research plus the experiment protocol are complete.
- `memos-assistant-smoke-v1` freezes the first license-compatible local evaluation contract; its
  verifier rejects case, prompt, license, notice, split, count, family, or evidence-cutoff drift.
- Three published dev runs are preserved; the post-gate policy-v3 smoke is 12/12 execution-success
  and 3/3 MemOS answers on non-held-out dev questions. The predeclared frozen test ran from clean
  code c1b5225, with MemOS 0/30 and each simple baseline 21/30.

## Optimization

Status: `TODO`

## Resume

Status: `TODO`

- No performance numbers will be written before benchmark completion.

## Interview

Status: `DOING` — module guide and research-based question bank published in Feishu; live answers and ownership verification pending

## Phase-1 exit criteria

- [x] Eight required research documents complete and source-linked.
- [x] Critical Mem0, Letta, LangGraph, and Zep source paths pinned to commit SHAs.
- [x] Competitive matrix separates confirmed capability from inference.
- [x] Problem definition, architecture candidates, trade-offs, and recommendation complete.
- [x] MVP scope, benchmark plan, open questions, and next phase gate complete.
- [x] Documentation link check and unsupported-claim audit pass.
- [x] Initial commit pushed to a GitHub remote.

## Decision log snapshot

| Topic | Current decision | Fact grade | Evidence / follow-up |
|---|---|---:|---|
| Phase boundary | MVP Features 0–6 are authorized under one continuous goal | `CONFIRMED` | Active project goal |
| System of record | PostgreSQL authority; vector/FTS are rebuildable projections | `CONFIRMED` mechanism | Feature 3–4 implementation and ADR 0006 database tests |
| Topology | Modular monolith plus asynchronous worker/outbox | `CONFIRMED` mechanism | PostgreSQL fault/concurrency and process smoke checks |
| Retrieval | Hybrid candidates and RRF implemented; benefit unproven | `CONFIRMED` mechanism | Frozen negative result; no isolated hybrid/rerank ablation |
| Graph database | Not in MVP | `HYPOTHESIS` | Add only if entity/multi-hop ablation proves value |
| Benchmark scores | Real synthetic dev results available; no improvement established | `CONFIRMED` | Immutable packages in [results](benchmark/results.md) |
| Initial workload | Bilingual personal/project assistant | `CONFIRMED` | Frozen Feature 6 v1 smoke manifest and cases |

## Next phase

Continue Feature 6 without entering Advanced Memory: the Waku consumer gate is closed; finish
personal interview rehearsal and, only with a newly frozen unseen evaluation contract, test whether
policy-v3 generalizes. Do not rerun or tune against the already inspected frozen test labels.
The legacy trusted scope headers and temporary operator key are removed and must not be
reintroduced.

## Active evidence gate — September 7

The user authorized [D1–D21 evidence closure](evidence/21-day-gate.md), ending September 27,
2026, and explicitly prohibited Advanced Memory, new graph infrastructure and unmeasured
optimization. Local environment remediation and projection reconciliation have passed local verification.
The [runbook](local-runbook.md), [environment observation](evidence/local-environment-2026-09-07.md),
and [module/interview guide](interview/memos-grill.md) separate implementation from model results.
The interview reference was written and read back through Feishu MCP as a real child Wiki node
under the existing MemOS gate: [MemOS module and Q&A page](https://my.feishu.cn/wiki/VpP1wOrvLioPVWkltUjcGpklngi).
It now has 13 module explanations, 22 core questions, staged follow-ups, Waku integration and the
post-gate evidence boundary. The MCP read-back confirmed Q10, Q12, Q19, Waku and the current Claim
snapshot in Wiki space `7603709140535430108`. Personal ownership remains unverified; no personal
rehearsal answer or mastery judgment has been published.

The current local Java gate passed 198 tests, zero failures/errors/skips, with ten PostgreSQL migrations.
A targeted red/green regression demonstrated that restoring the old candidate store returns
wrong-model lexical/structured candidates; the repaired store passed. Python format/lint and
70 tests, the frozen 13-scenario/15-question dataset verifier, shell syntax and Markdown links
also passed. All six process smoke scripts (health and Features 1–5) passed on local PostgreSQL
18.6/pgvector 0.8.6. Their synthetic timing sample is not a real-model SLO or benchmark result.

Projection reconciliation, generation fences and model filters are implemented and database-tested;
see ADR 0006 for maintenance/rollback limits. Both frozen Ollama digests and capabilities passed
preflight. An actual Codex-mode startup exposed a missing provider identity mapping, now covered
by a regression test. After a retained UNKNOWN_FIELD failure and explicit instance/schema prompt
clarification, a separate synthetic preference passed all three jobs and scoped retrieval through
the real Codex proxy. The database was explicitly reconciled from fake embeddings to the frozen
Qwen embedding digest (generation 2) without deleting authority. At this earlier checkpoint formal scores were still NOT RUN; the completed frozen result below
supersedes that status. Initial environment failures remain in local logs.

First real dev smoke is now complete and preserved under docs/benchmark/runs: 12 expected rows,
9 SUCCESS and 3 FAILED, verifier accepted, usage/storage incomplete. Two observed root causes only
are addressed under ADR 0007; formal testing was still NOT RUN at that first-dev checkpoint. The environment/reconciliation
implementation was pushed as d0869fb on feat_evidence-gate. HTTPS credentials were unavailable;
the existing authenticated SSH identity was used through a repository-local push URL.

Second dev and its pre-test freeze were pushed in c1b5225/d82376e. The frozen execution uses a clean
checkout at c1b5225; no third repair is allowed. GitHub Actions
[run 34084744451](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/34084744451) passed all four
jobs (Java, Python, docs and complete compose smoke) for the freeze-record commit. Current Python
coverage at that checkpoint was 67 passing tests; the current suite is 70. Separate-process dev report reconstruction matched the original hash;
see [reproduction](evidence/reproduction.md) and [three real failure cases](evidence/failure-cases.md).

## Frozen-test checkpoint — September 7

The first formal synthetic test is RUN / VERIFIED / RECONSTRUCTED, not NOT RUN. All 120 answers
(10 unique questions × 3 repetitions × 4 baselines) and 96 preprocessing rows are present; usage
and storage are complete. MemOS scored 0/30; each simple baseline scored 21/30. The package and
byte-identical offline reconstruction share hash
`7a9d67314fa53365e0ff5a5652219c3385509464da8482316f5e149314c2104f`.
See [results](benchmark/results.md) for exact scoring, no-evidence behavior and all limits.
The old NOT RUN statements in the chronological milestone history describe their original commits.

No positive quality, speed, cost, production security or scale claim is supported. OQ-012's model
identity, maintenance reconciliation and bounded execution/reconstruction tasks have local evidence;
representative workload affordability and production lifecycle remain open. The Waku Agent
integration closes the real-consumer mechanism gate. The technical decision is KEEP WITH WEAKER
CLAIMS because the only frozen result remains negative; personal mastery still requires live
rehearsal and cannot be inferred from generated documentation.
