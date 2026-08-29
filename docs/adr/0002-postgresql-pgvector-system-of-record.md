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
watermark. Integration and runtime publication gates are pending. The six-case deterministic
fixture validates retrieval-policy mechanics only; this ADR remains `PROPOSED` until Feature 6
measures a representative corpus and load profile.
