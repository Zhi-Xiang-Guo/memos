# MemOS assistant smoke dataset v1

This is a small, synthetic, bilingual dataset authored for the MemOS four-baseline harness. It
validates end-to-end experiment mechanics before external adapters or a frozen full campaign run.
It is not representative production traffic and is too small for a general quality claim.

- Workload: personal/project assistant conversations.
- Data: synthetic; no real PII, secrets, customers, or provider output.
- License: CC BY 4.0; see `LICENSE.txt` and `NOTICE.md` for exact scope and attribution.
- Integrity: SHA-256 values use UTF-8 text with CRLF/CR normalized to LF
  (`sha256-utf8-lf-v1`).
- Splits: scenario families are isolated; the manifest freezes every test scenario ID.
- Safety: source events may contain synthetic hostile instructions solely for red-team evaluation.

Run the integrity gate from `benchmark/`:

```bash
uv run memos-dataset-verify \
  --manifest datasets/memos-assistant-smoke/v1/manifest.json
```

Changing cases, prompts, split membership, model selection, or budgets requires a new version or a
manifest update before any run. A post-result edit to the v1 test split makes the original result
ineligible rather than silently replacing it.

The prompt boundary includes the answer and rolling-summary prompts plus the Java MemOS extraction
prompt and strict output schema. The latter two remain repository code, not CC BY dataset material.
