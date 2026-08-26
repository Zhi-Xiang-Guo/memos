# Temporal-memory deterministic conformance fixture v1

This directory freezes Feature 3 temporal-memory scenarios and expected observations. It is a
`DETERMINISTIC_CONFORMANCE` fixture, **not a model or system benchmark**. Passing it does not
establish production quality, latency, throughput, cost, or safety.

The fixture deliberately separates:

- deterministic commands and trusted scope in [`cases.json`](cases.json);
- expected externally observable state, history, time views, diffs, and failure outcomes;
- predictions emitted later by a Java fixture runner or public-API harness.

The Python reporter compares observations. It does not implement normalization, deduplication,
temporal overlap, transition planning, optimistic concurrency, idempotency, provenance, or
projection rebuild semantics.

## Version and integrity contract

[`manifest.json`](manifest.json) pins the fixture, temporal contract, transition policy, report
schema, exact case SHA-256, counts, coverage, and frozen test IDs. Changing a command, expected
observation, split, or label requires an updated checksum. A behavioral-contract change requires a
new version directory. Frozen test cases must not be used to select transition rules.

The 14 cases cover Shanghai to Hangzhou current/former/change-time views, three coffee
paraphrases, overlapping contradiction, non-overlapping history, a set-valued preference,
late-arriving backfill, uncertain dates, concurrent corrections, replay, invalid intervals,
provenance, projection rebuild, explicit invalidation, and hard scope isolation.

All values are synthetic. Scope identifiers and source identifiers are fixture tokens, not
credentials or production data.

## Prediction format

The reporter expects one JSON object per non-empty JSONL line. `observed` must be produced from the
implementation, not copied into a formal result artifact by hand.

```json
{
  "case_id": "residence-shanghai-to-hangzhou",
  "observed": {
    "command_outcomes": ["APPLIED", "APPLIED"],
    "transition_operations": ["CREATE", "SUPERSEDE"],
    "lineage_count": 1,
    "assertion_version_count": 2,
    "final_lock_version": 2,
    "status_by_version": {"1": "HISTORICAL", "2": "CURRENT"},
    "current_values": ["Hangzhou"]
  }
}
```

The real runner includes every expected field. Every case must appear exactly once; missing,
unexpected, or duplicate case IDs fail closed. Arrays are order-sensitive unless the fixture
explicitly sorts their values.

## Report

From `benchmark/`, after a runner creates predictions:

```bash
uv run memos-temporal-conformance-report \
  --manifest fixtures/temporal-memory/v1/manifest.json \
  --predictions path/to/predictions.jsonl \
  --output path/to/report.json
```

The report identifies itself as `DETERMINISTIC_CONFORMANCE`, records manifest/case/prediction
SHA-256 values, and reports exact case pass/fail plus mismatch paths grouped by coverage. It does
not write [`docs/benchmark/results.md`](../../../../docs/benchmark/results.md), which remains
`NOT RUN` until an eligible Feature 6 benchmark executes.
