# Retrieval deterministic conformance fixture v1

This fixture exercises Feature 4 ranking and context-policy mechanics with synthetic candidate
ranks. It is `DETERMINISTIC_CONFORMANCE`, not a real-model or production-system benchmark.
Its Recall@1 and MRR values only describe these six frozen synthetic cases; they do not establish
retrieval quality, latency, scale, cost, or safety on an external workload.

The Java runner emits one observed vector-only and hybrid ordering plus selected context IDs per
case. The Python reporter verifies the pinned fixture and prediction sets, compares the exact
contract, and mechanically derives Recall@1 and MRR without reimplementing RRF, truth-state policy,
query gating, or context assembly.

From the repository root, generate Java observations with the normal verification build. Then,
from `benchmark/`, generate a report:

```bash
uv run memos-retrieval-conformance-report \
  --manifest fixtures/retrieval/v1/manifest.json \
  --predictions ../modules/adapters/target/retrieval/predictions.jsonl \
  --output ../modules/adapters/target/retrieval/report.json
```

Changing behavior requires a new version directory. Frozen test labels must not be edited to make
the implementation appear better.
