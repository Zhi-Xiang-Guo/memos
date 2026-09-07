# MemOS Benchmark Report

> SMOKE validates harness mechanics only; it is not a formal quality or production performance claim.

## Identity

| Field | Value |
|---|---|
| Run | dev-20260907-0ce3ac0-01 |
| Campaign | SMOKE |
| Dataset | memos-assistant-smoke-v1 |
| Split | dev |
| Repetitions | 1 |
| Git commit | `0ce3ac0f238d903e874b5e2a52d0d91fcf557a26` |
| Started at | 2026-09-07T08:01:29.569248Z |
| Comparison config | `37354e184da4585d2aafc6e12ff9777197acd9b183e72500b13e29c7ede0fee4` |

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
| memos | 100.00% | 100.00% | N/A | 100.00% | 100.00% | 75.00% |

## Operations

| Baseline | Total latency p95 | Samples | Input tokens | Output tokens | Embedding tokens | Model calls | Storage observation |
|---|---:|---:|---:|---:|---:|---:|---:|
| full_history | 3191.379 | 3 | 987 | 130 | 590 | 11 | 390.333 |
| rolling_summary | 3922.792 | 3 | 2210 | 612 | 1778 | 20 | 645.333 |
| raw_turn_vector | 2945.382 | 3 | 987 | 130 | 673 | 17 | 7217.0 |
| memos | 4650.747 | 3 | 7025 | 721 | 726 | 21 | 11515.333 |

## Storage Measurement

| Baseline | Observations | Complete | Mean retained bytes | Total retained bytes | Measurement method | Database-native total delta |
|---|---:|---|---:|---:|---|---:|
| full_history | 3 | yes | 390.333 | 1171 | canonical-json-utf8-retained-events-v1 | N/A |
| rolling_summary | 3 | yes | 645.333 | 1936 | canonical-json-utf8-final-summary-plus-recent-turns-v1 | N/A |
| raw_turn_vector | 3 | yes | 7217.0 | 21651 | canonical-json-utf8-events-plus-dense-float32-le-v1 | N/A |
| memos | 3 | yes | 11515.333 | 34546 | postgresql-pg-column-size-scope-rows-plus-native-relation-delta-v1 | 180224 |

Retained bytes use baseline-specific declared representations. PostgreSQL scope row bytes use pg_column_size(record); database-native allocation deltas are supplemental and can change in page-sized steps. Methods are disclosed rather than treated as an identical physical-storage layer.

## Failures And Exclusions

| Baseline | Write failed | Write excluded | Answer failed | Answer excluded |
|---|---:|---:|---:|---:|
| full_history | 0 | 0 | 0 | 0 |
| rolling_summary | 0 | 0 | 0 | 0 |
| raw_turn_vector | 0 | 0 | 0 | 0 |
| memos | 0 | 0 | 0 | 0 |

Local model calls and tokens are counted, but monetary and energy costs are not established.
