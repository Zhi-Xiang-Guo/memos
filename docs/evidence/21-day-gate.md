# MemOS evidence gate — 2026-09-07 to 2026-09-27

September 7 follow-up: Waku `dbcda82615506db89f07d3187d64ba4c7032800a` adds tested read-error
visibility and opaque-ID forwarding (630 passed / 73 skipped), not full task utility. Its live
conformance was not rerun. Partial update, write-failure and stable retry semantics remain open;
the original 12/12 live result is limited to `b75adf2`. The market review and layered interview
workbooks do not change the KEEP-with-weaker-claims decision or extend this phase boundary.

D1 is 2026-09-07, Asia/Shanghai; D21 ends 2026-09-27 23:59:59. This is a calendar-day gate,
not permission to expand features or silently extend the deadline. The user authorized local
installation, manual service startup, projection reconciliation, dev → error analysis → frozen
test, interview material, and publication into the existing Agent Wiki.

## Decision contract

Current technical decision: **KEEP WITH WEAKER CLAIMS**. The formal four-baseline result is
verified and negative, fresh-clone operation is documented, three failures are traceable, and a
real Waku Agent consumer is pushed. Personal interview mastery and a recorded demo remain human
tasks; generated prose cannot prove them. Fixture tests remain mechanism evidence.

- KEEP: verifier-accepted reproducible formal four-baseline run; a real consumer or CodeFlow
  integration demo; three trace/source-linked failure cases; fresh-clone reproducibility.
- KEEP with weaker claims: the above evidence exists but MemOS does not beat simple baselines.
  Explain the negative result and complexity trade-off; do not claim significant improvement.
- DEMOTE at D21: no acceptable run, environment not reproducible, core-chain understanding cannot
  be demonstrated, or consumer/demo remains absent. MemOS becomes CodeFlow's optional memory
  module. Do not use implementation size or more features to override this decision.

## Ordered work and evidence

| Days | Dates | Work | Required evidence |
|---|---|---|---|
| D1–D3 | Sep 7–9 | Java 25, container runtime, Ollama preflight and fresh clone | Versions, startup commands, failure logs |
| D4–D7 | Sep 10–13 | Projection identity reconciliation | Migration, generation fence, concurrency and rollback checks |
| D8–D10 | Sep 14–16 | Four-baseline dev smoke | Every expected row, usage, storage, failures |
| D11–D14 | Sep 17–20 | Fix at most top two observed root causes | Taxonomy, regression, frozen configuration |
| D15–D17 | Sep 21–23 | One controlled frozen test campaign | Immutable manifest and verifier-accepted package |
| D18–D19 | Sep 24–25 | Independent report regeneration | Commands and hashes |
| D20–D21 | Sep 26–27 | Demonstration and interview claims | Five-minute demo, three failures, claim ledger, KEEP/DEMOTE |

Work can finish earlier while preserving the declared order. Never inspect held-out answers to
tune extraction or prompts. Runs keep timeout, malformed-output, ingestion and materialization
failures; a fix creates a new immutable run rather than replacing old rows.

## Fairness contract

Pin dataset version/splits/evidence cutoff; model digests and capabilities; prompt/schema hashes;
decoding settings; one evidence-token counter and budget; retry, concurrency and timeout policy;
actual environment; answer versus retrieval metrics; preprocessing and background provider costs;
and each retained representation with logical bytes separate from physical allocation. Every
baseline/question/repetition and preprocessing scenario must have a row. The mechanical verifier,
not manual report editing, decides artifact integrity.

## Remaining NOT RUN / NOT ESTABLISHED

Representative model quality beyond the completed synthetic test; hybrid/rerank ablations; write precision/recall and conflict accuracy;
representative latency/freshness distributions; cost/storage superiority; fixed-model injection
resistance; production IdP/key/backup/WAL/provider erasure contracts; production consumer scale.
A small synthetic real-model run cannot close representative workload or production SLO claims.

## Activation observation

The machine is arm64 with 16 GiB RAM. Homebrew Java 25.0.4.1 already exists, but the login shell
selected Java 8. Docker CLI and Ollama were absent. Existing Podman had no VM. Python checks passed
65 tests. The initial Java check failed because Testcontainers could not find Docker; its local
log is retained at `logs/baseline-java.log` (ignored runtime artifact). No model result follows.
Existing local Codex proxy health is reachable on loopback port 31415. It is an SDK-backed
compatibility service, not a digest-pinned replacement for the declared Ollama benchmark.

Progress and final validation are recorded in [progress](../progress.md),
[local runbook](../local-runbook.md), and [benchmark results](../benchmark/results.md).

## D1 accelerated evidence checkpoint

Environment and reconciliation validation, two dev runs, exactly two bounded repairs, the frozen
test and offline report reconstruction are complete in the declared order. The [test result](../benchmark/results.md)
is negative: MemOS 0/30, each simple baseline 21/30, with all 120 executions and complete accounting.
The run gap has closed; frozen improvement has not been established. Three
[failure cases](failure-cases.md) and the [interview guide](../interview/memos-grill.md) are
published. Waku Agent commit
[`b75adf2`](https://github.com/Zhi-Xiang-Guo/waku-agent/commit/b75adf2) is the real consumer: its
live MemOS backend passed all 12 `FactStore` conformance cases and the full Waku deterministic gate
passed 619 tests. The policy-v3 post-gate dev smoke is separately published and verifier accepted;
because it is a repaired development set, it cannot replace the negative held-out result. A
recorded five-minute video and personal core-chain explanation remain unverified human evidence.
