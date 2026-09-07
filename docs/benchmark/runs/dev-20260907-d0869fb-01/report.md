# MemOS Benchmark Report

> SMOKE validates harness mechanics only; it is not a formal quality or production performance claim.

## Identity

| Field | Value |
|---|---|
| Run | dev-20260907-d0869fb-01 |
| Campaign | SMOKE |
| Dataset | memos-assistant-smoke-v1 |
| Split | dev |
| Repetitions | 1 |
| Git commit | `d0869fbf7f12685313df17d3f7c49d81f9fea8e3` |
| Started at | 2026-09-07T04:42:10.065721Z |
| Comparison config | `234b315fce909bc4560ad576290db2cfe59cfcd8374425f7a4d5b7b4da6095c6` |

## Model Identity

| Role | Model |
|---|---|
| extractor | qwen3:4b (`359d7dd4bcdab3d86b87d73ac27966f4dbb9f5efdfcc75d34a8764a09474fae7`) |
| summary | qwen3:4b (`359d7dd4bcdab3d86b87d73ac27966f4dbb9f5efdfcc75d34a8764a09474fae7`) |
| answer | qwen3:4b (`359d7dd4bcdab3d86b87d73ac27966f4dbb9f5efdfcc75d34a8764a09474fae7`) |
| embedding | qwen3-embedding:0.6b (`ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d`) |
| reranker | N/A |
| judge | N/A |

## Quality

| Baseline | Answer accuracy | Temporal accuracy | Contradiction accuracy | Abstention F1 | Recall@K | MRR |
|---|---:|---:|---:|---:|---:|---:|
| full_history | 66.67% | 100.00% | N/A | 0.00% | N/A | N/A |
| rolling_summary | 66.67% | 100.00% | N/A | 0.00% | N/A | N/A |
| raw_turn_vector | 66.67% | 100.00% | N/A | 0.00% | 100.00% | 75.00% |
| memos | 0.00% | 0.00% | N/A | 0.00% | 0.00% | 0.00% |

## Operations

| Baseline | Total latency p95 | Samples | Input tokens | Output tokens | Embedding tokens | Model calls | Storage observation |
|---|---:|---:|---:|---:|---:|---:|---:|
| full_history | 5858.978 | 3 | 987 | 130 | 590 | 11 | 390.333 |
| rolling_summary | 6405.2 | 3 | 2210 | 612 | 1778 | 20 | 645.333 |
| raw_turn_vector | 4301.57 | 3 | 987 | 130 | 673 | 17 | 7217.0 |
| memos | 1452.325 | 3 | N/E | N/E | N/E | N/E | N/E |

## Storage Measurement

| Baseline | Observations | Complete | Mean retained bytes | Total retained bytes | Measurement method | Database-native total delta |
|---|---:|---|---:|---:|---|---:|
| full_history | 3 | yes | 390.333 | 1171 | canonical-json-utf8-retained-events-v1 | N/A |
| rolling_summary | 3 | yes | 645.333 | 1936 | canonical-json-utf8-final-summary-plus-recent-turns-v1 | N/A |
| raw_turn_vector | 3 | yes | 7217.0 | 21651 | canonical-json-utf8-events-plus-dense-float32-le-v1 | N/A |
| memos | 3 | no | N/E | N/E | postgresql-pg-column-size-scope-rows-plus-native-relation-delta-v1 | N/A |

Retained bytes use baseline-specific declared representations. PostgreSQL scope row bytes use pg_column_size(record); database-native allocation deltas are supplemental and can change in page-sized steps. Methods are disclosed rather than treated as an identical physical-storage layer.

## Failures And Exclusions

| Baseline | Write failed | Write excluded | Answer failed | Answer excluded |
|---|---:|---:|---:|---:|
| full_history | 0 | 0 | 0 | 0 |
| rolling_summary | 0 | 0 | 0 | 0 |
| raw_turn_vector | 0 | 0 | 0 | 0 |
| memos | 2 | 0 | 3 | 0 |

Local model calls and tokens are counted, but monetary and energy costs are not established.
