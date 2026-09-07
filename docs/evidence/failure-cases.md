# Three traceable failure cases — 2026-09-07

These are local synthetic model runs and diagnostic replays, not production incidents. The first
two selected dev root causes were repaired; the third is an explicitly retained negative result.
No personal ownership or interview mastery follows from this document.

## 1. A valid JSON response still violated temporal semantics

- Trigger: first real native-Qwen dev extraction.
- Observed: `dev-theme-update` and `dev-missing-music` failed materialization; quarantine path was
  `$.candidates[0].event_time`, error `INVALID_RANGE`.
- A separate dev-only replay produced invented 2023 dates with equal start and end. It is not the
  original server response, which application logging intentionally did not retain.
- Boundary: JSON Schema shape validation did not enforce the domain's half-open interval order.
  [StrictCandidateProposalDecoder](../../modules/materialization/src/main/java/dev/memos/materialization/StrictCandidateProposalDecoder.java)
  and [ProposedTimeRange](../../modules/memory-domain/src/main/java/dev/memos/domain/candidate/ProposedTimeRange.java)
  correctly refused it; removing the check would store an invalid assertion.
- Repair: separately versioned temporal-v2 prompt, with unsupported time as null and start < end.
  Existing decoder regression stays strict. Same dev diagnostic then returned null ranges.
- Evidence: [failed campaign rows](../benchmark/runs/dev-20260907-d0869fb-01/failures.md),
  [v1 replay](probes/ollama-dev-temporal-probe.json), [v2 replay](probes/ollama-dev-temporal-v2-probe.json),
  [ADR 0007](../adr/0007-dev-failure-freeze.md).

Interview follow-up: Why is `format: date-time` insufficient? Why not silently swap the boundaries?
What evidence would distinguish a prompt instruction gap from a model capability limit?

## 2. Both sides' unit tests passed while the wire contract differed

- Trigger: first dev `dev-release-q1` reached retrieval with the real Java service.
- Observed: `RETRIEVAL_TOKENIZER_IDENTITY`; Java returned `embedding-model:sha256:<digest>`, while
  the Python runner and its fake expected only `sha256:<digest>`.
- Root: the fake mirrored an assumption instead of the actual Java contract. The matching digest
  alone did not mean the wire representation matched.
- Repair: runner expects the counter kind prefix and exact digest. Correcting the fake first made
  the old runner test fail; fixed runner passes, and missing-prefix/wrong-digest cases still fail.
- Evidence: [failed retrieval row](../benchmark/runs/dev-20260907-d0869fb-01/retrieval.jsonl),
  [Java counter](../../modules/context/src/main/java/dev/memos/context/EmbeddingContextTokenCounter.java),
  [runner regression](../../benchmark/tests/test_runner.py),
  [second dev rows](../benchmark/runs/dev-20260907-c1b5225-02/retrieval.jsonl).

Interview follow-up: Where should a shared contract test live? Why does accepting any prefix hide
future drift? How does a successful verification call affect measured token costs?

## 3. Successful settlement produced no usable evidence

- Trigger: second dev run, after the two bounded repairs.
- Observed: all 12 executions succeeded and usage/storage were complete. MemOS abstained on all
  three questions, so only the deliberately unanswerable question was correct.
- Database evidence: across the five ingested dev sources, three extractions proposed no candidate;
  one candidate was REVIEW with `PROCEDURAL_APPROVAL_REQUIRED`; one was IGNORE with `SECRET_REJECTED`.
  No REMEMBER candidate was produced. This is more precise than saying every extraction was empty.
- Interpretation: the write pipeline completed its policy decisions, but model classification and
  useful-memory recall were inadequate for these examples. Permitting procedural or secret writes
  merely to improve the score would weaken the safety contract and contaminate the benchmark.
- Decision: retain this failure, do not add a third root-cause repair or tune on frozen test labels.
- Evidence: [answer rows](../benchmark/runs/dev-20260907-c1b5225-02/answers.jsonl),
  [scoped extraction observation](probes/dev-02-extraction-observation.json),
  [quarantine reasons](probes/dev-02-quarantine-observation.json),
  [deterministic policy](../../modules/governance/src/main/java/dev/memos/governance/DeterministicCandidateWritePolicy.java).

Interview follow-up: How do you separate “pipeline succeeded” from “memory useful”? Which labeled
write-policy metrics would identify false secret classification? When is reducing complexity the
honest engineering decision?

## Frozen-test confirmation of the remaining utility failure

The controlled [test package](../benchmark/runs/test-20260907-c1b5225-01/report.md) completed every
execution, but MemOS scored 0/30 while each simple baseline scored 21/30. Every MemOS response
had no provided evidence: 12 abstained and 18 emitted an empty answer with abstain=false. The
[exact-scope runtime observation](probes/test-01-extraction-observation.json) records 51 extraction
runs, 31 candidates, 21 IGNORE, 10 REVIEW and zero REMEMBER, with content-free reason counts.
These are observed outcomes; deciding which rejected candidates were mislabeled needs a separate
labeled write-policy evaluation. The frozen model/configuration was not repaired or rerun.
