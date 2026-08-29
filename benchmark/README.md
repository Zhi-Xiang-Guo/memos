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
```

No paid model or provider credential is required. Feature 6 uses the versioned smoke dataset and
an explicitly verified local Ollama model path before any external benchmark campaign.
