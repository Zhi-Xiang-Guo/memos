# Reproduce the evidence

The original real-model packages are committed under `docs/benchmark/runs/`. Every failed and
successful attempt is retained in its original directory. Do not edit their JSON or Markdown.
Original provider/model runs are distinct from offline report reconstruction and from dev probes.

## Local prerequisite evidence

Follow [the runbook](../local-runbook.md). A clean detached checkout at c1b5225 was built with
Java 25 and Maven Wrapper 3.9.16, passed 193 tests, and launched API/worker from its rebuilt JARs.
A separate actual SSH clone from GitHub at d82376e then passed the same 193 tests and model
preflight; its JARs now run the separate Codex development mode.
This reused this machine's installed prerequisites, PostgreSQL volume and Ollama model cache;
independent-machine reproduction has not been established. No Ollama/Podman login service was
registered. Original user files were preserved in the main workspace.

## Verify and regenerate without model calls

From the repository's `benchmark` directory:

```bash
uv sync --locked --python 3.14.7
uv run memos-benchmark-verify \
  --run-dir ../docs/benchmark/runs/dev-20260907-d0869fb-01 \
  --dataset-manifest datasets/memos-assistant-smoke/v1/manifest.json
uv run memos-benchmark-verify \
  --run-dir ../docs/benchmark/runs/dev-20260907-c1b5225-02 \
  --dataset-manifest datasets/memos-assistant-smoke/v1/manifest-temporal-v2.json
uv run python ../scripts/rebuild-benchmark-report.py \
  --source ../docs/benchmark/runs/dev-20260907-c1b5225-02 \
  --destination ../benchmark-artifacts/my-independent-rebuild \
  --dataset-manifest datasets/memos-assistant-smoke/v1/manifest-temporal-v2.json
```

Choose a destination that does not exist. The script reads only manifest/raw JSONL to generate
metrics, costs, storage, failures, report and integrity; then verifies both packages and checks
matching hashes. It uses the same published metric implementation in a separate process, not an
independent human reviewer or independently implemented scorer. A tampered source is rejected by
the final source verifier; it cannot be legitimized by regenerating its integrity file.

Confirmed second-dev reconstruction hash:
`8cdd1f31682747d178e3ddb1e4cb2f5a85c33980a1602c9ff65ac5bef12c136c`.
First-dev hash:
`70576b00669cdeaae0dbeb16fc55234bee10d024b89e7336b0137591842f2cc5`.
The frozen test configuration was committed before execution in
[the freeze record](frozen-test-2026-09-07.json); the model runner uses code c1b5225 and exactly
three repetitions. It is not silently rerun for better scores.

## Interpret the boundaries

- Execution SUCCESS is valid protocol completion, not answer correctness or useful retention.
- A verifier pass establishes artifact coverage and mechanical consistency, not positive quality.
- Three repeated outputs of one question are correlated; they are not three independent questions.
- The dataset is small, synthetic and versioned. It is not representative of production usage.
- Full history/summary need context completeness reporting; vector/MemOS also have retrieval ranks.
- Usage includes preprocessing and token counting; failed settlements may leave incomplete usage,
  which the package reports. Dollar pricing is absent and no cost advantage may be claimed.
- Logical retained bytes differ from native relation allocation deltas. Shared global catalog,
  deployment generation metadata, provider model files, backups and WAL are outside scoped logical
  rows. Shared PostgreSQL allocation deltas cannot be assigned to a tenant as exact physical cost.
- Global host swap and other desktop workloads remain confounders for latency. No comparative
  throughput, representative p95/freshness SLO, or production scale conclusion is established.

## Scoring names require a code-level reading

The frozen v1 scorer normalizes Unicode/case/whitespace, checks exact accepted strings or exact
sets, and checks abstention shape; it is not a human semantic-quality judgment. Citation validity
and completeness are separate fields and are not prerequisites for the answer accuracy field.

The artifact field `recall_at_k` is actually the proportion of eligible questions with **any** gold
event in the ranked result (an any-hit rate). It is not the mean fraction of all gold events
retrieved. `complete_recall_at_k` separately counts questions whose whole gold set is present.
MRR uses the first matching event. See [the frozen scorer](../../benchmark/src/memos_benchmark/metrics.py).
Human summaries must label these definitions explicitly, especially on multi-evidence questions.
This reporting qualification does not modify the frozen scorer or silently regenerate original
packages with new semantics. A future metric-schema correction requires an explicit version and
recomputation record; it is outside this cycle's two-root-cause repair budget.

## Frozen test reconstruction

```bash
# From benchmark/
uv run memos-benchmark-verify \
  --run-dir ../docs/benchmark/runs/test-20260907-c1b5225-01 \
  --dataset-manifest datasets/memos-assistant-smoke/v1/manifest-temporal-v2.json
uv run python ../scripts/rebuild-benchmark-report.py \
  --source ../docs/benchmark/runs/test-20260907-c1b5225-01 \
  --destination ../benchmark-artifacts/my-frozen-test-rebuild \
  --dataset-manifest datasets/memos-assistant-smoke/v1/manifest-temporal-v2.json
```

Confirmed original and reconstructed hash:
`7a9d67314fa53365e0ff5a5652219c3385509464da8482316f5e149314c2104f`.
Both verifications report FROZEN_TEST, 120 SUCCESS, complete usage and complete storage. The copied
published directory is byte-identical to the first local package. This is offline reconstruction,
not another model campaign; the one controlled frozen run was not repeated.
