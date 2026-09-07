# ADR 0007: Two-root-cause dev repair and test freeze

- Date: 2026-09-07
- Status: Accepted; frozen test completed with a negative result
- Boundary: Feature 6 evaluation only; no Advanced Memory or new infrastructure

## Observed failures

The first real four-baseline dev run at code `d0869fb` generated all 12 expected answer rows;
9 succeeded and all 3 MemOS rows failed. The package verifier accepted integrity but reported
incomplete usage/storage. The immutable [report](../benchmark/runs/dev-20260907-d0869fb-01/report.md)
and [failure rows](../benchmark/runs/dev-20260907-d0869fb-01/failures.md) are preserved.
These 3 dev questions do not establish general quality, latency or cost conclusions.

We select exactly two root causes for this gate's repair budget:

1. `CONFIRMED`: Java identifies context counters as `embedding-model:sha256:<digest>`, while the
   Python runner expected `sha256:<digest>`. Its test fake repeated the same mistaken convention.
   Updating the fake to the actual Java contract made the old runner fail. The runner now checks
   both counter kind and exact digest; regression cases reject an absent kind and a different digest.
2. `CONFIRMED` failure, limited diagnostic reproduction: two source settlements were quarantined
   at `$.candidates[0].event_time` with `INVALID_RANGE`. A separate replay of one dev source using
   the exact native Ollama prompt/schema/model/options produced invented 2023 timestamps with
   equal start/end. The domain correctly requires start < end. The v1 prompt did not describe
   half-open interval constraints or explicitly require null for unsupported time information.

## Decision and evidence

Keep the original v1 prompt, schema, dataset and all raw packages unchanged. Add the distinct
`candidate-extraction-temporal-v2` prompt, limited to explaining temporal null/uncertainty and
half-open interval constraints. The existing strict decoder is unchanged: equal boundaries still
fail. A separate dev diagnostic with the new prompt returned null event/valid ranges for that
same source. This single observation is not evidence of general extraction quality improvement.

`manifest-temporal-v2.json` is an explicit configuration variant over the identical v1 cases:
case bytes/hash, splits, answer/summary prompts, model digests, seed, temperature, budgets,
repetitions and baseline order stay the same. Only the extraction prompt path/hash changes.
The original `manifest.json` continues to verify the first package. Launch the updated worker
with `scripts/run-local.sh ollama-temporal-v2`; the source provenance records its distinct prompt
identity. Standard `ollama` still means the original v1 prompt.

Raw diagnostic request/response evidence lives in [probes](../evidence/probes/ollama-dev-temporal-probe.json)
and [temporal v2 probe](../evidence/probes/ollama-dev-temporal-v2-probe.json). These synthetic dev
replays are explicitly outside campaign metrics and are not silent retries of benchmark rows.
The existing decoder regression rejects non-exclusive temporal ends.

After the second dev run, record remaining failures and freeze this configuration for one test
campaign. Do not make a third root-cause repair or tune on held-out answers. Unknown sensitivity,
missed facts, abstention mistakes, incomplete usage after failed settlement and other quality
errors remain reportable limitations. Verifier acceptance alone does not imply a usable consumer,
positive results, cost completeness or satisfaction of the D21 KEEP gate.

## Freeze declaration

Second dev package `dev-20260907-c1b5225-02` has all 12 executions SUCCESS and complete usage/storage.
MemOS answered 1/3 correctly (all three responses abstained without evidence); each simple baseline
answered 2/3 correctly. The remaining missed-evidence problem is recorded, not repaired in this
two-root-cause budget. Freeze execution code c1b5225 and temporal-v2 for the one planned test run.
The [pre-test freeze record](../evidence/frozen-test-2026-09-07.json) fixes hashes, runtime and 120 rows.
This small development result is negative evidence, not an improvement claim.

## Frozen outcome

[test-20260907-c1b5225-01](../benchmark/runs/test-20260907-c1b5225-01/report.md) completed all 120
executions with complete usage/storage and a byte-identical offline report rebuild. MemOS answered
0/30 correctly versus 21/30 for each simple baseline. No third repair was made. The retained scorer's
`recall_at_k` field is an any-hit rate; [the interpretation](../evidence/reproduction.md) discloses this
without changing frozen code or raw reports. Keep negative evidence and the remaining consumer gate.
