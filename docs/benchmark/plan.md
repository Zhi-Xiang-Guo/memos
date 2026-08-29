# Benchmark plan

Status: protocol and first smoke contract frozen; harness execution `NOT RUN`. No result has been
generated.

The Feature 6 v1 contract is
`benchmark/datasets/memos-assistant-smoke/v1/manifest.json`: a versioned, synthetic bilingual
personal/project-assistant dataset with isolated scenario families, frozen test IDs, exact content
and prompt hashes, a complete CC BY 4.0 license/attribution boundary, fixed local model IDs, and an
equal-budget four-baseline declaration. The repository verifier checks this contract before a run;
its successful verification is not an answer-quality, latency, scale, cost, or safety result.

## Questions

1. Does a managed memory lifecycle improve held-out answer quality over full history, rolling summary, and pure vector retrieval?
2. Which gains come from write selection, version/conflict semantics, hybrid retrieval, reranking, or context policy?
3. What latency, token, model-call, storage, and operational cost buys each gain?
4. Where should the system abstain rather than inject weak or conflicting evidence?
5. Does quality degrade as sessions, noise, duplicates, updates, and elapsed time accumulate?

## Evaluation layers

Do not collapse the pipeline into one answer score.

| Layer | Question | Example metrics |
|---|---|---|
| Write gate | Was durable information selected and noise rejected? | precision, recall, F1 by memory type/sensitivity |
| Extraction | Was the fact/event/time/entity normalized correctly? | field exact/F1, temporal interval accuracy, schema failure |
| Manage | Were duplicate/update/conflict transitions correct? | cluster B³/P/R/F1, transition macro-F1, contradiction accuracy |
| Candidate retrieval | Did relevant evidence enter top K? | Recall@K, MRR, nDCG where graded relevance exists |
| Selection/context | Was the final evidence relevant, sufficient, authorized, current, and within budget? | context precision/recall, stale/conflict leakage, tokens |
| Answer | Did the agent answer correctly and cite/use evidence? | exact match, token F1, task-specific rules, abstention F1, human audit |
| System | Is it usable and economical? | p50/p95/p99 latency, throughput, tokens, calls, failures, bytes/memory |

## Benchmark sources

### External

Adapters are planned for LoCoMo, LongMemEval, and BEAM. Their actual tasks, metrics, repository commits, and license/redistribution constraints are recorded in [benchmark research](../research/06-memory-benchmark-analysis.md). Upstream evaluation must be used where feasible; incompatible metrics are reported rather than silently rewritten.

### MemOS benchmark

Create a version-controlled bilingual (Chinese/English) event dataset with explicit session boundaries and source-level labels. Each scenario includes:

- tenant, user, agent, session, actor, message IDs;
- ordered message/tool events and trusted/untrusted channel;
- event and observed times;
- gold memory candidates and memory types;
- gold entity/predicate/value and temporal intervals;
- duplicate cluster and relationship (`REINFORCES`, `SUPERSEDES`, `COEXISTS`, `CONFLICTS`, `INVALIDATES`);
- sensitivity and expected write-policy outcome;
- questions, answer, relevant evidence IDs, requested time intent, and whether to abstain.

Required tracks:

1. Simple recall.
2. Multi-session recall.
3. Preference recall.
4. Memory update.
5. Temporal reasoning.
6. Contradiction resolution.
7. Multi-hop/entity reasoning.
8. Memory abstention.
9. Long-term retention.
10. Noise/pollution resistance.
11. Idempotency/concurrency (system track).
12. Privacy/deletion and memory poisoning (safety track).

## Dataset split and leakage control

- Freeze train/dev/test scenario families and IDs before final tuning.
- Train contains examples for prompt/schema development; dev selects thresholds/fusion/K. Each declared final configuration gets one frozen test campaign whose predeclared repetitions follow the uncertainty protocol below; results are not repeatedly inspected and retuned.
- Paraphrases or templated variants of one user story stay in one split.
- Keep benchmark questions out of extraction prompts and source documentation.
- Record any model with plausible benchmark contamination risk; do not claim contamination-free hosted models.
- External benchmark demonstration examples must not be copied into MemOS held-out scenarios.

## Baselines

All baselines use the same answer model snapshot, system instruction, question, and answer evaluator where applicable. The primary comparison is a **budget-matched quality track**: every baseline receives the same evidence token budget, and any overflow follows a predeclared deterministic truncation policy. System-specific preprocessing/model calls are recorded as cost.

A secondary **native cost/overflow track** may allow each baseline to use its natural context strategy up to the answer model's declared input limit. Those outcomes, eligibility/overflow, latency, and cost are reported separately and are never labeled budget-matched.

### A — Full conversation context

For the primary track, supply ordered history without memory selection up to the shared evidence budget, using one predeclared deterministic chronological truncation policy. For the secondary track, allow ordered history up to the model's declared input limit. In either track, report overflow and never silently drop tokens; secondary-track reporting separates eligible-only and all-case outcomes when model-window overflow occurs.

### B — Rolling conversation summary

Maintain a fixed-budget summary after each session (or declared interval) using a pinned summarizer prompt/model. Supply summary plus the same recent-turn allowance used by the protocol. Record cumulative summarization calls/tokens and summary drift failures.

### C — Pure vector search

Required C1: embed raw conversation turns and retrieve TopK by semantic similarity. Apply the same hard authorization/scope filters as every system (tenant/user/agent/ACL and applicable sensitivity policy); disable only memory lifecycle and non-vector retrieval signals. No dedup, temporal truth, conflict state, keyword/entity fusion, importance, or reranking.

Diagnostic C2: embed the same atomic candidates used by MemOS but disable lifecycle/version policy and non-vector signals. This separates extraction gains from management/retrieval gains.

### D — MemOS

Versioned write/manage lifecycle plus declared hybrid retrieval and context policy. No feature may be enabled only for test.

## Controlled configurations and ablations

Use a configuration matrix generated by the harness:

- vector only;
- vector + lexical;
- vector + lexical + structured/entity filters;
- add temporal/truth-state logic;
- add importance/recency features;
- RRF versus calibrated weighted/learned fusion;
- without/with cross-encoder reranker;
- without/with LLM reranker under deadline;
- without/with query retrieval gate;
- without/with write gate;
- without/with dedup/conflict management.

Do not run a combinatorial explosion blindly. Use targeted ablations tied to failure hypotheses.

## Metric definitions

### Retrieval

- `Recall@K`: fraction of questions for which at least one required evidence item is in top K; also report multi-evidence complete recall.
- `MRR`: reciprocal rank of first relevant item, only when first-hit relevance is meaningful.
- `nDCG@K`: only when graded relevance labels exist.
- `Stale@K`: fraction of present-state queries whose selected context contains a superseded incompatible version.
- `Conflict leakage`: answer contexts that hide a gold unresolved conflict.

### Write/manage

- write precision/recall/F1 at source-event and candidate levels;
- extraction field accuracy and invalid-schema rate;
- deduplication cluster B³ precision/recall/F1 plus pairwise diagnostics;
- transition macro-F1 over ignore/create/reinforce/supersede/coexist/conflict/invalidate;
- current-state, historical-state, and change-time accuracy;
- duplicate logical version count under retry/concurrency fault tests.

### Answer and abstention

- deterministic exact/normalized match where answers are unambiguous;
- token F1 or list/set F1 for multi-part answers;
- temporal tuple accuracy: `(current, historical, change interval)`;
- abstention precision/recall/F1 and risk-coverage curve if a confidence score exists;
- citation/evidence sufficiency and contradiction, with a human-reviewed stratified sample;
- an LLM judge may be supplemental only; record judge model/prompt and audit disagreement.

### Performance/cost

- ingest, extraction, persistence, projection, retrieval, reranking, and total latency separately;
- p50/p95/p99 after warm-up with sample counts;
- input/output tokens, embedding tokens/items, model calls, retries;
- configured unit-price snapshot and calculated cost, clearly separated from provider invoices;
- source bytes, memory/version rows, vector/FTS index size, and growth per session/user;
- queue lag, failure/retry/dead-job rate.

## Repetition and uncertainty

- Deterministic components run once per code/config revision plus regression tests.
- Nondeterministic model-dependent configurations run at least three times or until a predeclared budget cap; record sampling parameters and provider seed support.
- Report paired bootstrap 95% confidence intervals over scenarios for principal quality deltas.
- Use paired significance/error inspection where useful; do not turn a tiny p-value into a practical-value claim.
- Publish raw per-case outcomes so averages cannot hide temporal or privacy regressions.

## Run manifest

Every run writes an immutable manifest containing:

```yaml
run_id:
started_at:
git_commit:
dirty_worktree:
dataset:
  name:
  version_or_commit:
  split_hash:
system:
  baseline:
  config_hash:
models:
  extractor:
  embedding:
  reranker:
  answer:
  judge:
prompts:
  extraction_hash:
  summary_hash:
  answer_hash:
environment:
  java:
  python:
  postgres:
  pgvector:
  machine:
sampling:
  temperature:
  seed:
limits:
  context_tokens:
  memory_tokens:
  deadlines_ms:
pricing_snapshot:
```

Hosted model aliases that can drift are called out explicitly; a date is not proof of a frozen snapshot.

## Harness artifacts

The v1 immutable layout, verifier, and a locally verified four-baseline runner candidate are
implemented. The selected model path has not executed and the storage/result-rendering gates are
still in progress:

```text
benchmark-artifacts/<run-id>/
  manifest.json
  cases.jsonl
  writes.jsonl
  retrieval.jsonl
  answers.jsonl
  timings.jsonl
  costs.json
  metrics.json
  failures.md
  integrity.json
```

Only small, license-compatible artifacts are committed. Large/upstream datasets are fetched or referenced according to license.

## Integrity rules

- `metrics.json`, `costs.json`, and `failures.md` are mechanically regenerated from raw rows; the
  result-table renderer remains pending.
- `integrity.json` pins every raw and derived artifact; checksum updates alone cannot bypass the
  independent regeneration checks.
- Every baseline/scenario/repetition has one preprocessing/write row with explicit usage; absent
  usage is invalid, while known-incomplete usage is marked incomplete rather than treated as zero.
- Missing values render as `NOT RUN`, never zero.
- Failed and excluded cases include an explicit reason and remain counted.
- Manual corrections require a review log and do not overwrite raw outputs.
- Results identify warm/cold cache and local/hosted infrastructure.
- No claim uses another paper’s score as if MemOS reproduced it.

## First experiment order

1. Deterministic lifecycle unit corpus (updates, time, dedup, conflict, deletion).
2. Tiny end-to-end smoke set for all four baselines.
3. Retrieval ablation on the dev split.
4. Write-policy threshold experiment.
5. External benchmark adapters and reproducibility check.
6. Frozen test run.
7. Load/cost profile after correctness; optimization only from measured bottlenecks.
