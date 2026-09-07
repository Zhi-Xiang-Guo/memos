# Benchmark results

September 7 consumer-only refinement: Waku `dbcda82615506db89f07d3187d64ba4c7032800a`
fixes read-error visibility and opaque fact-ID forwarding, with 630 deterministic tests passed
and 73 skipped. It does not add a benchmark run or a new live conformance run. The older Waku
12/12 live result belongs to `b75adf2`; all frozen raw artifacts and negative results below remain
unchanged. See the [market/claim audit](../research/market-2026-09-07/README.md).

Status: **FROZEN TEST VERIFIED — NEGATIVE RESULT; POST-GATE DEV SMOKE VERIFIED**

A real-model frozen synthetic test now exists; its limited result appears below. Features 0–5 include deterministic
conformance fixtures, integration tests, or runtime smoke checks. Feature 4's six-case synthetic
retrieval-policy fixture and Feature 5's four-case structural poisoning-boundary fixture are
mechanically verified implementation evidence rather than benchmark results. This file
keeps mechanism evidence separate from real-model synthetic test and dev observations.

## Result gate

A result may be added only when all are present:

- committed code revision;
- dataset name/version/split hash and license note;
- immutable run manifest;
- raw per-case outputs and failure/exclusion accounting;
- model/provider/prompt/config versions;
- environment and latency sample details;
- generated or mechanically verified metrics;
- baseline parity review.

## Frozen synthetic test — 2026-09-07

`CONFIRMED`: [test-20260907-c1b5225-01](runs/test-20260907-c1b5225-01/report.md), clean execution
code `c1b5225001e8ed938a4d5dbdff68d9a564fb655b`, temporal-v2 configuration, native Ollama with the
same full Qwen digests used in dev. The freeze record was pushed before execution. All 120 expected
answer rows and 96 preprocessing rows exist, with no execution failures or exclusions and complete
usage/storage. Ten unique test questions were repeated three times for each baseline; this is not
30 independent questions per system. The dataset is small and synthetic, not a production sample.

| Baseline | Exact-match correct / executions | Accuracy | Any gold hit @8 | Complete gold hit @8 | MRR |
|---|---:|---:|---:|---:|---:|
| Full history | 21 / 30 | 70.00% | N/A | N/A | N/A |
| Rolling summary | 21 / 30 | 70.00% | N/A | N/A | N/A |
| Raw-turn vector | 21 / 30 | 70.00% | 100.00% | 100.00% | 0.95 |
| MemOS | 0 / 30 | 0.00% | 0.00% | 0.00% | 0.00 |

The original artifact calls the any-gold-hit field `recall_at_k`; its actual definition is disclosed
here rather than silently changing the frozen scorer. Answer accuracy uses normalized exact string/
set matching; citation correctness is a separate metric. See [scoring boundaries](../evidence/reproduction.md#scoring-names-require-a-code-level-reading).

Every MemOS answer had no provided evidence: 12 abstained and 18 returned empty answer/items while
setting abstain=false. None was correct. The content-free [runtime observation](../evidence/probes/test-01-extraction-observation.json)
records 51 extraction runs, 31 candidate proposals, 21 IGNORE, 10 REVIEW, and zero REMEMBER. Thus
there was no accepted memory to retrieve. These are observed policy outcomes; they do not prove
that every rejection was wrong or identify every underlying model classification error. No third
repair, held-out tuning or selective retry was performed.

This is a negative result for the tested configuration. It does **not** establish that every
possible MemOS configuration is inferior, or that more features would improve it. The simple
baselines also failed: each missed the required abstention and emitted a labeled forbidden answer
in three repeated outputs; all four abstention F1 values are zero. MemOS's zero forbidden outputs
with zero correct answers cannot establish useful injection resistance.

Package SHA-256: `7a9d67314fa53365e0ff5a5652219c3385509464da8482316f5e149314c2104f`.
A separate-process [report reconstruction](../evidence/reproduction.md), without any model call,
regenerated all files byte-identically and passed the verifier with the same package hash.
The copied published package is byte-identical to the original local package. Raw reports are
immutable, including their original column labels; this page supplies interpretation, not edits.

## Still not established

Representative quality and write/conflict precision/recall; vector vs hybrid vs rerank ablations;
representative latency/freshness SLOs; dollar/energy cost or storage superiority; comprehensive
fixed-model red-team effectiveness; production IdP, backups/WAL/provider erasure; consumer scale.
The raw report includes local timing and representation-specific storage observations. MemOS's
shorter local answer latency with no useful evidence is not a performance benefit. Global host
swapping and other desktop work confound the timing comparison. Logical retained bytes are not
identical physical-storage costs across the four baselines.

The formal execution/reconstruction evidence gap is now closed for this bounded synthetic contract.
The [D21 technical gate](../evidence/21-day-gate.md) is KEEP WITH WEAKER CLAIMS after the Waku
local interface integration described below. Task-level usefulness, production use and personal
explanation/modification evidence remain unestablished; interface conformance does not close them.

## Real-model development smoke — 2026-09-07

`CONFIRMED` local execution: [dev-20260907-d0869fb-01](runs/dev-20260907-d0869fb-01/report.md),
commit `d0869fb`, v1 dataset/configuration, Qwen3 4b and Qwen3 embedding 0.6b pinned by full digest.
All 12 expected answer rows exist (3 questions × 4 baselines, one repetition): 9 SUCCESS and
3 FAILED, all failures in MemOS. SUCCESS means valid execution, not a correct answer.
The package hash is `70576b00669cdeaae0dbeb16fc55234bee10d024b89e7336b0137591842f2cc5`.
Usage and storage are incomplete; no cost comparison is permitted. This development smoke is not
an eligible formal benchmark or a positive MemOS quality result.

The [two selected root causes](../adr/0007-dev-failure-freeze.md) are temporal range proposals and
counter identity parity. Original raw files remain byte-identical; the adjusted configuration is
separately named. The later test below uses the separately frozen configuration; original dev failures remain unchanged.

Second dev run [dev-20260907-c1b5225-02](runs/dev-20260907-c1b5225-02/report.md) completed all 12 rows
with usage/storage complete. MemOS correctly answered 1/3 questions; full history, rolling summary
and raw-turn vector each answered 2/3. MemOS abstained on every question without retrieved evidence.
This is a three-question diagnostic result, not a general comparison. Configuration is now frozen
for test in [the freeze record](../evidence/frozen-test-2026-09-07.json).

### Post-gate policy-v3 dev smoke

`CONFIRMED`: [dev-20260907-0ce3ac0-01](runs/dev-20260907-0ce3ac0-01/report.md) ran from clean
commit `0ce3ac0f238d903e874b5e2a52d0d91fcf557a26` after the frozen negative result was preserved.
It uses the separately versioned policy-v3 extraction prompt and explicit JWT write capabilities.
All 12 answer executions succeeded with complete usage/storage, and the independent verifier
reconstructed package hash
`00e47ad341e5a3b25aff6b41c8630a5ad94f568ff3b7f6420df99db0df7da714`.

| Baseline | Correct / dev questions | Accuracy | Recall@8 | MRR | Abstention F1 |
|---|---:|---:|---:|---:|---:|
| Full history | 2 / 3 | 66.67% | N/A | N/A | 0.00% |
| Rolling summary | 2 / 3 | 66.67% | N/A | N/A | 0.00% |
| Raw-turn vector | 2 / 3 | 66.67% | 100.00% | 0.75 | 0.00% |
| MemOS | 3 / 3 | 100.00% | 100.00% | 0.75 | 100.00% |

This is a three-question development smoke used during repair. It is neither held-out nor
representative, so it does not establish quality superiority. The report also shows one labeled
stale theme event entered MemOS context; the answer selected the current value, but the exposure
remains a retrieval-policy limitation. Two earlier immutable post-gate runs retained citation
validation failures while the harness was repaired. The final harness constrains structured
decoding to identifiers visible in the selected context and maps memory, version, or source
identifiers back to dataset provenance; it still rejects unknown citations.

`CONFIRMED` consumer evidence: Waku Agent commit
[`b75adf2`](https://github.com/Zhi-Xiang-Guo/waku-agent/commit/b75adf2) implements MemOS as its
six-method semantic `FactStore`. A live local scope passed 12/12 Waku conformance cases, including
write-after-settlement, miss, update, delete, list and non-ASCII behavior. Waku's full deterministic
gate passed 619 tests with 73 optional external cases skipped. This establishes one runnable
consumer integration, not production traffic, multi-agent scale, or an SLO.
