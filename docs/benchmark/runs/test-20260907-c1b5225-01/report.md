# MemOS Benchmark Report

> Model-dependent benchmark result; interpret with its manifest and raw failures.

## Identity

| Field | Value |
|---|---|
| Run | test-20260907-c1b5225-01 |
| Campaign | FROZEN_TEST |
| Dataset | memos-assistant-smoke-v1 |
| Split | test |
| Repetitions | 3 |
| Git commit | `c1b5225001e8ed938a4d5dbdff68d9a564fb655b` |
| Started at | 2026-09-07T04:53:35.198765Z |
| Comparison config | `4588bfdea0e2510c9c37419e5f766a7a22a6a62350c2599fd5637fdaa74f0371` |

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
| full_history | 70.00% | 100.00% | 0.00% | 0.00% | N/A | N/A |
| rolling_summary | 70.00% | 100.00% | 0.00% | 0.00% | N/A | N/A |
| raw_turn_vector | 70.00% | 100.00% | 0.00% | 0.00% | 100.00% | 95.00% |
| memos | 0.00% | 0.00% | 0.00% | 0.00% | 0.00% | 0.00% |

## Operations

| Baseline | Total latency p95 | Samples | Input tokens | Output tokens | Embedding tokens | Model calls | Storage observation |
|---|---:|---:|---:|---:|---:|---:|---:|
| full_history | 7507.518 | 30 | 11013 | 1308 | 8874 | 123 | 506.125 |
| rolling_summary | 8202.272 | 30 | 23160 | 5742 | 19089 | 207 | 703.5 |
| raw_turn_vector | 7248.169 | 30 | 11013 | 1308 | 9549 | 177 | 9210.125 |
| memos | 5629.525 | 30 | 21087 | 6856 | 753 | 141 | 4630.625 |

## Storage Measurement

| Baseline | Observations | Complete | Mean retained bytes | Total retained bytes | Measurement method | Database-native total delta |
|---|---:|---|---:|---:|---|---:|
| full_history | 24 | yes | 506.125 | 12147 | canonical-json-utf8-retained-events-v1 | N/A |
| rolling_summary | 24 | yes | 703.5 | 16884 | canonical-json-utf8-final-summary-plus-recent-turns-v1 | N/A |
| raw_turn_vector | 24 | yes | 9210.125 | 221043 | canonical-json-utf8-events-plus-dense-float32-le-v1 | N/A |
| memos | 24 | yes | 4630.625 | 111135 | postgresql-pg-column-size-scope-rows-plus-native-relation-delta-v1 | 368640 |

Retained bytes use baseline-specific declared representations. PostgreSQL scope row bytes use pg_column_size(record); database-native allocation deltas are supplemental and can change in page-sized steps. Methods are disclosed rather than treated as an identical physical-storage layer.

## Failures And Exclusions

| Baseline | Write failed | Write excluded | Answer failed | Answer excluded |
|---|---:|---:|---:|---:|
| full_history | 0 | 0 | 0 | 0 |
| rolling_summary | 0 | 0 | 0 | 0 |
| raw_turn_vector | 0 | 0 | 0 | 0 |
| memos | 0 | 0 | 0 | 0 |

Local model calls and tokens are counted, but monetary and energy costs are not established.
