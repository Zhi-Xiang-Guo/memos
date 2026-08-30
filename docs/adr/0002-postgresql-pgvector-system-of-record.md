# ADR-0002: PostgreSQL plus pgvector and FTS

- Status: `PROPOSED`
- Date: 2026-08-26

## Problem

The MVP needs transactional evidence/version state, temporal and metadata queries, outbox/jobs, audit, lexical retrieval, and semantic retrieval. Separate databases create dual-write, deletion, backup, and local-environment complexity before scale requirements exist.

## Options

1. PostgreSQL for truth plus built-in FTS and pgvector.
2. PostgreSQL + OpenSearch + Qdrant/Milvus.
3. Document/vector database as the only store.
4. PostgreSQL + vector store + graph database.

## Decision

Use PostgreSQL as the system of record and initial retrieval engine. Use pgvector for dense candidates and PostgreSQL full-text search for lexical candidates. Vectors and FTS documents are rebuildable projections carrying embedding/index versions.

Official capability references: [PostgreSQL full-text search](https://www.postgresql.org/docs/current/textsearch.html) and [pgvector](https://github.com/pgvector/pgvector).

## Why

- one transaction can enforce source, lineage, version, provenance, job, and audit invariants;
- structured tenant/time/status filters are first-class;
- projection failure cannot erase authoritative content;
- local integration and benchmark reproducibility are simpler;
- the adapter boundary leaves a scale-out path.

## Trade-offs

- ANN indexes and filtered search require careful tuning and may not match a specialized engine at large scale;
- FTS relevance/language analysis is less feature-rich than OpenSearch for some workloads;
- search and OLTP can contend for resources;
- graph traversals are less natural than in a graph engine.

## Validation

Define small/medium/large reference load profiles, then record index size, ingest cost, filtered Recall@K, and p50/p95/p99 retrieval. Introduce a specialized projection only after an SLO/quality failure is reproduced and its improvement covers consistency/operations cost.

Feature 4 now implements the proposed PostgreSQL projection with generated FTS, HNSW cosine
vectors, structured/temporal indexes, hard scope/truth/time filters, and an explicit transition
watermark. GitHub Actions run `#17` verifies the PostgreSQL integration and runtime smoke. The
six-case deterministic fixture validates retrieval-policy mechanics only; this ADR remains
`PROPOSED` until Feature 6 measures a representative corpus and load profile.

Feature 5 adds implementation evidence for the authority/projection distinction: governed memory
or user erasure first removes vector/FTS/checkpoint rows, then a fenced PostgreSQL transaction
purges retained authoritative content and leaves opaque tombstones. Old projection jobs cannot
replay an inactive lineage. This validates deletion consistency for the current single-database
topology, not representative search capacity or future external projection deletion.

The published Feature 6 implementation makes vector length provider-configurable without changing
the authority boundary. V007 stores unbounded pgvector values with a checked declared/actual
dimension in the HNSW-supported `1..2000` range and adds a partial 1024-dimensional cosine HNSW
expression index for the selected MVP model. Retrieval filters model version and dimension before
casting to the configured `vector(N)`. Commit `9225ed1` and GitHub Actions run `#32` verify the
migration, dimension constraints, PostgreSQL retrieval path, and compose smoke. Legacy vectors
remain rebuildable data; no authoritative assertion is rewritten or deleted. Dimensions other
than 1024 and model-version changes require explicit index migration/reconciliation, and
representative Recall@K/latency is still `NOT RUN`, so the ADR remains `PROPOSED`.

The current Feature 6 candidate adds an operator-only, content-free storage observation over the
exact authenticated tenant/user/agent scope. It reports relation row counts and
`pg_column_size(record)` bytes, while deployment-wide `pg_table_size`/`pg_indexes_size` allocation
is recorded separately as a before/after delta. This makes storage-growth claims mechanically
observable without estimating from text length. Local API and artifact-verifier tests pass; remote
PostgreSQL execution and any representative storage-growth result remain pending, so this does not
change the ADR status.
