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

With the API, worker, PostgreSQL, and pinned Ollama models already running, the published runner is
invoked explicitly with observed environment versions:

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
or storage objects, artifact hash changes, manually edited metrics/usage/storage/report values,
and failure summaries that differ from raw status rows. `storage.json` discloses each baseline's
measurement method; `report.md` renders identity, disclaimers, quality, p95 latency with samples,
usage, storage, and failure/exclusion counts. The runner rejects a dirty worktree and verifies Ollama model
digests before any execution. Commit `db213df` and
[GitHub Actions run #35](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33281737584)
remotely verify its deterministic Python, Java/PostgreSQL, documentation, and compose gates. A
runner or verifier command is not a benchmark result; no selected model has completed the
four-baseline campaign yet.

The storage-observation and mechanical-report extension is published through commit `46ecdd7` and
[GitHub Actions run #37](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33284193760).
That run remotely verifies the fixed PostgreSQL relation contract, 65 Python tests, documentation,
and the complete compose smoke; it contains no selected-model benchmark result.
