# MemOS benchmark workspace

This Python workspace treats MemOS as a black-box HTTP service. Feature 0 contains only a
deterministic health client and environment check; benchmark datasets, baselines, metrics, and
run artifacts are added in Feature 6.

```bash
uv sync --locked --python 3.14.7
uv run ruff format --check .
uv run ruff check .
uv run pytest
uv run memos-benchmark --base-url http://localhost:8080
uv run memos-dataset-verify \
  --manifest datasets/memos-assistant-smoke/v1/manifest.json
uv run memos-benchmark-verify \
  --run-dir ../benchmark-artifacts/<run-id> \
  --dataset-manifest datasets/memos-assistant-smoke/v1/manifest.json
```

With the API, worker, PostgreSQL, and pinned Ollama models already running, the locally verified
runner candidate is invoked explicitly with observed environment versions:

```bash
uv run memos-benchmark-run \
  --split dev \
  --campaign-kind SMOKE \
  --java-version 25.0.4.1 \
  --postgres-version 18.6 \
  --pgvector-version 0.8.6
```

No paid model or provider credential is required. Feature 6 uses the versioned smoke dataset and
an explicitly verified local Ollama model path before any external benchmark campaign.

The run-package verifier rejects dirty-worktree manifests, dataset/model/prompt/config drift,
missing baseline-question-repetition rows, missing scenario-level preprocessing rows, absent usage
objects, artifact hash changes, manually edited metrics or usage totals, and failure summaries
that differ from raw status rows. The runner rejects a dirty worktree and verifies Ollama model
digests before any execution. A runner or verifier command is not a benchmark result; no selected
model has completed the four-baseline campaign yet.
