# MemOS agent instructions

## Start every work session

Read, in order, when present:

1. `README.md`
2. `docs/progress.md`
3. `docs/open-questions.md`
4. `docs/benchmark/results.md`
5. the ADRs and phase document relevant to the requested change

## Phase discipline

Follow the project sequence:

`Research → Problem Definition → Competitor Analysis → Architecture → MVP → Advanced Memory → Evaluation → Optimization → Documentation → Resume → Interview Preparation`

Do not silently enter the next phase. Respect the current phase boundary in `docs/progress.md` and the user’s active Goal. Small spikes are allowed only to validate a named hypothesis and must document purpose and outcome.

## Evidence discipline

- `CONFIRMED`: directly observable specification/source behavior, or a repository-local experiment, verifies the claim; the existence/content of an upstream evaluation can be confirmed without treating its reported result as reproduced.
- `UPSTREAM-REPORTED`: a paper author or vendor reports a setup/result that MemOS has not independently reproduced.
- `INFERRED`: reasoned interpretation of confirmed evidence.
- `HYPOTHESIS`: awaiting experiment.
- `DEPRECATED`: historical behavior, not current.
- `N/E`: not established within the reviewed source boundary; this is not proof of universal absence.

Prefer official documentation, original papers, official repositories, and pinned commit links. Do not turn vendor-reported results into MemOS results. Never invent benchmark, latency, cost, scale, or résumé numbers.

## Architecture discipline

- Keep evidence, assertion versions, and state transitions append-only while retained; corrections add records, while governed policy/legal erasure may purge content and leave only a non-content tombstone.
- Treat PostgreSQL records as authority and vector/FTS/graph/cache data as rebuildable projections unless an ADR supersedes this.
- LLM output proposes semantic structure; deterministic code owns authorization, idempotency, invariants, state transitions, and destructive actions.
- Add infrastructure only after a measured need and an ADR.
- Test tenant isolation, concurrency, duplicate delivery, partial failure, retry, and deletion resurrection for affected features.

## Completion discipline

For every completed phase or feature:

- update confirmed facts, decisions, trade-offs, implementation status, benchmark status, unresolved questions, and next step;
- update affected ADRs and docs with implementation evidence;
- run proportionate tests and documentation/link checks;
- commit the coherent feature and push it to the configured GitHub remote before reporting completion;
- do not claim completion if the push failed.

Preserve unrelated user changes. Do not overwrite or delete benchmark raw artifacts or source evidence to make results look cleaner.
