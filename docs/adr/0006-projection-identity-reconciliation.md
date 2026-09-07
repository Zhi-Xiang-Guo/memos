# ADR-0006: Explicit maintenance projection identity reconciliation

- Status: `ACCEPTED` — local implementation gate, 2026-09-07; production/quality claims excluded
- Date: 2026-09-07
- Boundary: Feature 6 evidence gate; no Advanced Memory or new infrastructure

## Problem and decision

A model/dimension change filtered the vector channel but could leave lexical/structured/temporal
channels serving stale generation data. Completed projection intents were not automatically
recreated. A model name alone also cannot fence A → B → A work.

Use an append-only deployment-level generation ledger. Startup registers a clean database or
rejects a mismatched/legacy populated database. The explicit operator maintenance transaction
locks outbox, lineages and projection state, appends the selected model/dimensions/policy,
hides old projections and checkpoints, marks unfinished projection work DEAD with a specific
reason, and enqueues latest ACTIVE lineages with active source evidence. Completed job, provider
usage, assertion and transition history remain intact. Each new intent has a generation-specific
semantic key; repeated calls for the current identity are no-ops.

The existing worker embeds outside the transaction. Job loading and commit require the current
generation as well as lease ownership; the checkpoint trigger additionally checks generation,
model, policy and dimensions. New projection intents with stale configured model/policy fail.
All candidate channels filter embedding identity/dimensions and current deployment policy.

## Trade-offs and recovery

This is a small-deployment maintenance workflow, not online blue/green. Stop API/worker writers
before selecting a new identity. A short transaction clears old projections; subsequent worker
rebuild can take arbitrarily longer and reports durable stage state. Reads may be incomplete
until rebuilt; model rollback creates another generation and re-embeds authority. No zero-downtime,
representative performance or SLO claim follows.

A failed maintenance transaction rolls back the selection, cancellations and projection changes.
Sequence gaps after rollback are allowed and carry no semantic meaning. SQL lock errors require
inspection and a new maintenance attempt, not clearing the database. Governed deletion shares
lineage/job locks and only ACTIVE lineages with ACTIVE sources are scheduled. Old-generation
replay cannot acquire publish rights simply by returning to the old model name.

The v1 optimized HNSW index is 1024-dimensional. Other dimensions can be represented safely but
have no performance claim. Same-generation physical corruption repair, online shadow indexing,
backup restore governance, provider erasure, and production deployment-scale reconciliation
remain outside this milestone.

## Evidence

Implementation: [V009](../../modules/adapters/src/main/resources/db/migration/V009__projection_reconciliation.sql),
[startup guard](../../modules/adapters/src/main/java/dev/memos/adapters/spring/ProjectionIdentityConfiguration.java),
[projection store](../../modules/adapters/src/main/java/dev/memos/adapters/postgres/JdbcProjectionBuildStore.java),
[candidate store](../../modules/adapters/src/main/java/dev/memos/adapters/postgres/JdbcRetrievalCandidateStore.java).
Regression coverage and final execution status are recorded in [progress](../progress.md).
Operator commands and limitations are in the [runbook](../local-runbook.md).

Local validation: Java 25 `./mvnw -B -ntp clean verify` passed 193 tests with no failures,
errors or skips. Reconciliation coverage exercises refusal of legacy populated state, duplicate
switch idempotency, A→B→A generation separation, stale load/commit/replay, bounded concurrent
maintenance lock contention, rebuilt visibility, deletion exclusion and transaction rollback.
A regression mutation restoring the old candidate store failed because lexical/structured
candidates leaked the wrong model identity; restoring the fix passed the full suite.
No representative-load or online-migration conclusion is established.
