# Benchmark results

Status: **DEV RUN COMPLETE; FROZEN TEST NOT RUN**

No eligible formal benchmark execution exists yet. Features 0–5 include deterministic
conformance fixtures, integration tests, or runtime smoke checks. Feature 4's six-case synthetic
retrieval-policy fixture and Feature 5's four-case structural poisoning-boundary fixture are
mechanically verified implementation evidence rather than benchmark results. This file
keeps formal claims separate from the real-model synthetic dev smoke below.

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

## Planned table (not data)

| System | Answer accuracy | Temporal accuracy | Contradiction accuracy | Abstention F1 | Recall@K | MRR | Retrieval p95 | Ingest p95 | Materialization/freshness p95 | Tokens / case | Storage growth |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Full history | NOT RUN | NOT RUN | NOT RUN | NOT RUN | N/A | N/A | NOT RUN | N/A | N/A | NOT RUN | N/A |
| Rolling summary | NOT RUN | NOT RUN | NOT RUN | NOT RUN | N/A | N/A | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Pure vector C1 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| MemOS | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |

`NOT RUN` is not a score. `N/A` means the metric is structurally inapplicable to that baseline under the declared protocol.

`Ingest p95` ends at source acceptance; `Materialization/freshness p95` ends when the memory is queryably projected at the declared watermark. Generated artifacts also report extraction, authoritative persistence, vector/FTS projection, and total stage latency separately.

See [benchmark plan](plan.md) and [external benchmark analysis](../research/06-memory-benchmark-analysis.md).

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
separately named. Frozen testing and independent reconstruction remain pending.
