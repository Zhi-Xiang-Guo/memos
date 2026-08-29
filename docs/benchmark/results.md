# Benchmark results

Status: **NOT RUN**

No eligible formal benchmark execution exists yet. Features 0–4 include deterministic conformance fixtures, integration tests, and runtime smoke checks. Feature 4's six-case synthetic retrieval-policy fixture is mechanically verified implementation evidence rather than a benchmark result. This file intentionally contains no synthetic, borrowed, or estimated quality number.

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
