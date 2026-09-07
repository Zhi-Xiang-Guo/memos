# Benchmark results

Status: **FROZEN TEST RUN / VERIFIED / RECONSTRUCTED — NEGATIVE RESULT**

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
The overall [D21 gate](../evidence/21-day-gate.md) remains CONDITIONAL: a real consumer/CodeFlow
integration demo and personal explanation/modification evidence are still missing.

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
