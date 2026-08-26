# ADR-0004: Versioned memory and hybrid retrieval

- Status: `PROPOSED`
- Date: 2026-08-26

## Problem

Overwriting facts destroys history; appending every statement creates contradiction and pollution. Pure vector retrieval cannot reliably handle exact identifiers, time, truth status, or authorization. A fixed hand-written weighted sum has no calibration evidence.

## Options

1. One mutable memory row and vector TopK.
2. Append-only assertion versions and state-transition log + current projection; dense and lexical retrieval with structured/temporal filters and rank fusion.
3. Event-sourced memory plus independent vector, search, and graph services immediately.

## Decision

Use option 2. Model a stable memory lineage with append-only assertion versions and append-only transitions for explicit `CURRENT`, `HISTORICAL`, `CONFLICTED`, and `INVALIDATED` state. A transactionally maintained current projection supports reads and concurrency; it is rebuildable from the log. Retained records are never overwritten for correction, while a governed hard-delete workflow may purge content and leave a non-content tombstone. Generate semantic, lexical, entity/metadata, and temporal candidates. Start fusion experiments with Reciprocal Rank Fusion because it does not assume comparable raw scores. Keep reranking feature-flagged.

## Why

- historical and change-time questions remain answerable;
- provenance and extraction-policy migration remain auditable;
- exact and semantic signals complement each other;
- hard scope/time/status constraints are applied independently of similarity;
- RRF provides a low-assumption baseline before labeled calibration data exists.

## Trade-offs

- versioning and conflict rules increase schema and transaction complexity;
- duplicate versus supersession classification can be model-sensitive;
- multiple retrievers increase latency and evaluation surface;
- RRF ignores score magnitude and may underperform a calibrated learned fusion.

## Validation

Use held-out benchmark cases for update, temporal, contradiction, exact identifier, semantic paraphrase, noise, and abstention. Compare vector-only, vector+lexical, +temporal/state, RRF, calibrated weighting, and optional reranking. Report quality, latency, tokens, and storage together.
