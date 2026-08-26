# Write-policy deterministic fixture v1

This directory is a versioned conformance fixture for Feature 2 candidate extraction and deterministic write policy. It is **not a model benchmark** and does not establish real-model quality, production safety, latency, token use, or cost.

`cases.json` keeps three things separate:

- trusted source and policy context supplied by the application;
- deterministic fake provider output using `memory-candidate.v1` snake-case fields;
- expected validation and policy outcomes used as gold labels.

The Python report tool never reimplements MemOS policy. A Java fixture runner or public-API harness must produce a separate prediction JSONL file; the reporter compares those observations with the expected fixture labels.

## Version and integrity contract

[`manifest.json`](manifest.json) pins the fixture, schema, policy, prompt, fake-provider, and fake-model versions. It also pins the exact SHA-256 and case count of [`cases.json`](cases.json), the coverage labels, and the frozen test case IDs.

Changing a case, label, split, or provider output requires:

1. a new fixture version directory when the behavioral contract changes;
2. an updated case SHA-256 and counts in the manifest;
3. review that dev-only policy tuning did not use the frozen test cases;
4. regenerated report artifacts rather than hand-edited numbers.

The test split is frozen for the v1 contract. It must not be used to select confidence thresholds, trust rules, or sensitivity actions.

## Prediction format

The reporter expects one JSON object per line:

```json
{
  "case_id": "durable-preference",
  "validation": "VALID",
  "candidate_keys": ["semantic:user:preference.editor.theme:dark"],
  "decisions": [{"ordinal": 0, "decision": "REMEMBER"}]
}
```

Every fixture case must appear exactly once. Missing cases, unexpected cases, duplicate case IDs, duplicate candidate keys, or duplicate decision ordinals fail the report instead of being silently excluded.

## Report

From `benchmark/`, after a Java/API fixture runner has created predictions:

```bash
uv run memos-write-policy-report \
  --manifest fixtures/write-policy/v1/manifest.json \
  --predictions path/to/predictions.jsonl \
  --output path/to/report.json
```

The generated document identifies itself as `DETERMINISTIC_FIXTURE`. It reports candidate precision/recall/F1, REMEMBER precision/recall/F1, decision macro-F1, grouped policy metrics, invalid-schema rate, decision ratios, validation accuracy, and harmful-write rate. It does not write [`docs/benchmark/results.md`](../../../../docs/benchmark/results.md), whose formal benchmark state remains `NOT RUN` until Feature 6 executes an eligible run.

Synthetic contact and credential markers use reserved/example values only. They are test data, not working credentials.
