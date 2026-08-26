# Feature 3 — versioned temporal memory

Status: `IMPLEMENTED / VERIFIED / PUBLICATION PENDING`. Feature 3's local implementation and
verification gates passed on 2026-08-27; the Git publication gate remains pending.

Feature 3 consumes sanitized `REMEMBER` candidates and the durable
`CANDIDATE_MATERIALIZATION` intent created by [Feature 2](feature-2.md). It creates authoritative
memory lineages, immutable assertion versions, append-only state transitions, provenance links,
and a rebuildable current-state projection. It also exposes scope-bound inspection, correction,
invalidation, transaction-time views, and diffs.

The contract follows [ADR-0004](../adr/0004-versioned-memory-hybrid-retrieval.md), the
[recommended architecture](../architecture/03-recommended-architecture.md), and the
[MVP plan](../architecture/04-mvp-plan.md). PostgreSQL records remain authoritative; models and
provider-supplied relation hints cannot directly select a destructive transition.

## Feature boundary

Feature 3 owns:

- stable lineage identity and optimistic `lock_version` coordination;
- immutable assertion versions with event time, valid time, transaction time, and provenance;
- append-only `CURRENT`, `HISTORICAL`, `CONFLICTED`, and `INVALIDATED` transitions;
- deterministic exact/paraphrase reinforcement, supersession, coexistence, conflict, correction,
  invalidation, replay, and backfill planning;
- transaction-time `as-of` and diff views reconstructed from authoritative history;
- a transactionally maintained current projection that can be rebuilt from versions and
  transitions;
- hard tenant/user/agent-scoped inspection and mutation APIs.

Feature 3 does not build vector, FTS, entity, or ranking projections; those belong to Feature 4.
Hard erasure, cross-projection deletion, and resurrection guards remain Feature 5. Formal
retrieval/model/system benchmarking remains Feature 6.

## Authoritative records and pure contract

The pure temporal contract lives under `dev.memos.domain.temporal`. Its stable identifiers are
`MemoryLineageId`, `AssertionVersionId`, and `StateTransitionId`.

- `MemoryLineageIdentity` binds one ID to `LineageScope`, memory type, subject, normalized
  predicate, and `SINGLE` or `SET` cardinality.
- `AssertionVersion` is immutable while retained. It carries a canonical scalar/list value,
  normalized content, optional event/valid intervals, confidence/importance, provenance, ordinal,
  and transaction time.
- `AssertionStateTransition` is append-only and carries a monotonic sequence, operation, related
  versions, status changes, actor/source/policy context, reason, and transaction time.
- `MemoryLineageSnapshot` replays transitions to derive status. Its lock version advances once per
  authoritative transition, including provenance reinforcement.
- `TemporalMemoryAuthority` consumes `MaterializeCandidateCommand`, `CorrectAssertionCommand`,
  `InvalidateAssertionCommand`, `MemoryAsOfQuery`, and `MemoryDiffQuery` and returns
  `TransitionPlan`, `MemoryAsOfView`, or `MemoryDiff`.

Retained assertion content and transition history are not updated in place. Governed erasure is a
separate exception that may purge content while retaining a non-content tombstone; it is not
implemented by this feature.

## Deterministic transition semantics

| Input condition | Operation | Result |
|---|---|---|
| No active assertion | `CREATE` | Append one version and mark it `CURRENT` |
| Exact or deterministic paraphrase duplicate | `REINFORCE` | Append provenance-bearing transition; no new assertion version |
| Distinct value on a `SET` predicate | `COEXIST` | Append another `CURRENT` version |
| Later, non-overlapping valid interval on `SINGLE` | `SUPERSEDE` | Prior active version becomes `HISTORICAL`; new version becomes `CURRENT` |
| Earlier, non-overlapping valid interval on `SINGLE` | `SUPERSEDE` | Backfilled version is `HISTORICAL`; the later current value remains current |
| Overlapping, missing, or uncertain interval on `SINGLE` | `CONFLICT` | Incompatible active versions become `CONFLICTED`; no arbitrary winner |
| Authorized correction with matching lock | `INVALIDATE` correction transition | Incorrect version becomes `INVALIDATED`; immutable replacement version is appended |
| Authorized invalidation with matching lock | `INVALIDATE` | Target version becomes `INVALIDATED`; no replacement is created |
| Replayed candidate/correction identity | `IGNORE` / replay outcome | No duplicate version or transition |
| Stale expected lock | precondition failure | No partial authoritative write |

Candidate relation hints never bypass these rules. `SINGLE` versus `SET` is deterministic predicate
policy, not inferred opportunistically from a conflicting value.

## Temporal semantics

Feature 3 keeps three clocks distinct:

- event time describes when an event happened;
- valid time is a half-open interval `[start_inclusive, end_exclusive)` describing when an
  assertion applies;
- transaction time records when MemOS accepted a version or transition.

`GET .../as-of?at=` is a transaction-time view: it includes versions recorded and transitions
applied at or before the supplied instant. It does not pretend the database knew a late backfill at
the historical valid time. `GET .../diff?fromExclusive=&toInclusive=` returns versions appended and
transitions recorded in that transaction-time window. Valid-time history remains visible in each
version so callers can separately answer current, former, overlap, and change-time questions.

Uncertain original time text, precision, bounds, and confidence are preserved. Uncertain or
partially bounded intervals do not gain false exactness; where deterministic ordering cannot be
proved, a single-valued transition conflicts rather than selecting a winner.

## HTTP contract

All endpoints require the trusted-upstream `X-Tenant-Id`, `X-User-Id`, and `X-Agent-Id` headers.
Every repository/use-case call carries the resolved hard scope. A memory that exists outside the
caller scope is indistinguishable from an absent memory and returns the same content-safe `404`.

| Method and path | Contract |
|---|---|
| `GET /v1/memories` | Cursor-bounded lineage summaries; filters remain hard-scoped |
| `GET /v1/memories/{memoryId}` | Inspect lineage identity, lock version, statuses, and retained versions |
| `GET /v1/memories/{memoryId}/history` | Ordered immutable versions and append-only transitions |
| `GET /v1/memories/{memoryId}/current` | Current-state projection for the scoped lineage |
| `GET /v1/memories/{memoryId}/as-of?at=...` | Inclusive transaction-time view |
| `GET /v1/memories/{memoryId}/diff?fromExclusive=...&toInclusive=...` | Exclusive/inclusive transaction-time diff |
| `POST /v1/memories/{memoryId}/corrections` | Append an authorized replacement version and correction transition |
| `POST /v1/memories/{memoryId}/invalidations` | Append an invalidation transition |

Mutation requests require a non-blank `Idempotency-Key` and a strong `If-Match` entity tag carrying
the expected numeric lineage lock version, for example `If-Match: "2"`. Successful responses
return the resulting lock version as `ETag`. An exact idempotent replay returns the original logical
result without advancing history. Reusing the key for different immutable input returns `409`.
A stale `If-Match` returns `412`; malformed input returns `400`; an impossible transition returns
`409`; absent or out-of-scope memory returns `404`.

Correction and invalidation errors expose stable codes, trace ID, and target path only. They do not
echo memory content, replacement content, source evidence, idempotency keys, credentials, SQL,
lease tokens, or unrestricted exception messages.

### Mutation evidence boundary

Correction and invalidation use a deliberate two-step flow. The caller first submits a direct user
memory command through `POST /v1/source-events`, then waits for its scoped extraction/materialization
result. A mutation request references that existing `sourceEventId`; a correction also references
an existing `AVAILABLE` candidate with final policy decision `REMEMBER` that belongs to that source.
The server resolves the source, candidate, trust, and persisted provenance under the same
tenant/user/agent scope.

The client never supplies extractor/model/prompt/policy/schema versions, trust level, derivation
metadata, actor authority, or a free-form replacement value that bypasses write policy. A
correction body selects `incorrectVersionId`, `sourceEventId`, `candidateId`, and a bounded
uppercase reason code. An invalidation body selects `versionId`, `sourceEventId`, and the same kind
of content-free reason code. Missing,
out-of-scope, erased, non-`REMEMBER`, or unrelated evidence returns the same scoped not-found or a
stable conflict code without disclosing which internal check failed.

This costs an extra ingestion round trip and makes correction freshness asynchronous, but keeps
authorization, sensitivity review, schema validation, and provenance in the normal write path. It
also avoids fabricating candidate/source rows merely to satisfy authoritative foreign keys.

The HTTP layer calls the `dev.memos.materialization.TemporalMemoryMutation` application port.
`CorrectionSelection` and `InvalidationSelection` carry only hard scope, opaque target/evidence
IDs, idempotency key, expected lock version, reason code, trace ID, and request time. The
content-free `TemporalMutationResult` returns `APPLIED` or `REPLAYED`, operation, resulting lock
version, affected version IDs, and transition IDs. Typed failures map as follows:

| Application failure | HTTP | Public code |
|---|---:|---|
| `NOT_FOUND` | `404` | `MEMORY_NOT_FOUND` |
| `STALE_PRECONDITION` | `412` | `STALE_MEMORY_VERSION` |
| `IDEMPOTENCY_CONFLICT` | `409` | `MUTATION_IDEMPOTENCY_CONFLICT` |
| `INVALID_TRANSITION` | `409` | `INVALID_MEMORY_TRANSITION` |

## Atomicity, idempotency, and concurrency

| Concern | Required invariant |
|---|---|
| Candidate materialization | Lineage lock, versions, transitions, provenance, current projection, downstream intent completion, and idempotency record commit atomically |
| Correction | Idempotency claim, replacement version, status transition, provenance, current projection, and lock advance commit atomically |
| Invalidation | Idempotency claim, transition, projection change, and lock advance commit atomically |
| Optimistic concurrency | The lineage update succeeds only when stored and expected lock versions match |
| Replay | The same semantic/candidate or mutation identity returns the existing result without duplicate authoritative records |
| Projection rebuild | Deleting/rebuilding the current projection never changes versions, transitions, provenance, or lock history |
| Scope | Tenant, user, and agent participate in every lookup and mutation; unscoped ID lookup is forbidden |

No remote provider call is held inside these authoritative transactions.

## Deterministic conformance fixture

The versioned [temporal-memory v1 fixture](../../benchmark/fixtures/temporal-memory/v1/README.md)
contains 14 synthetic cases covering:

- Shanghai to Hangzhou current/former/change-time, transaction-time as-of, and diff views;
- three coffee paraphrases reinforcing one assertion while preserving three source links;
- overlapping contradiction and non-overlapping history;
- a set-valued preference with coexisting current values;
- late/backfilled valid time and uncertain date precision;
- optimistic concurrent correction, exact replay, invalid interval atomic rejection;
- correction provenance, explicit invalidation, projection rebuild, and cross-scope not-found.

The manifest pins the fixture/contract/policy/report versions, exact case SHA-256, coverage, split,
and frozen test IDs. Predictions remain separate. The Python reporter validates integrity and
performs exact structural comparison; it never reimplements the Java transition state machine.
Generated reports identify themselves as `DETERMINISTIC_CONFORMANCE` and list only mismatch paths,
not differing content values.

This fixture is not a benchmark. It does not establish model quality, retrieval quality, latency,
throughput, storage scale, cost, or production safety. Formal benchmark results remain `NOT RUN`.

## Observability and privacy

Low-cardinality counters may record operation, resulting status, replay/conflict/precondition
outcome, and projection rebuild outcome. Tenant, user, agent, memory, version, transition,
candidate, source, request, and idempotency identifiers are correlation fields, not metric labels.

Default logs may include trace ID, opaque lineage/version/transition IDs, operation, stable outcome
code, lock versions, counts, and policy version. They must not include normalized content, values,
raw source/provider output, provenance spans, idempotency keys, credentials, or exception messages
that may contain governed content.

## Explicit non-goals

Feature 3 does not claim or implement:

- semantic/vector similarity, FTS, hybrid fusion, reranking, context budgeting, or retrieval quality
  (Feature 4);
- governed hard deletion, projection-wide erasure, resurrection prevention, or a complete
  poisoning-defense proof (Feature 5);
- formal baseline results, latency percentiles, throughput, token/cost numbers, or production scale
  (Feature 6);
- cross-user memory transfer, graph databases, learned conflict policy, event sourcing across
  services, or multi-region consensus.

## Verification matrix

| Gate | Target evidence | State |
|---|---|---|
| Pure transition planner | Create/reinforce/supersede/coexist/conflict/correct/invalidate tests | `PASS` — 11 planner tests |
| Temporal boundary | Half-open intervals, uncertain dates, event/valid/transaction time separation | `PASS` |
| Shanghai to Hangzhou | Current/former/change-time/as-of/diff observations match fixture | `PASS` |
| Dedup and provenance | Three coffee paraphrases produce one assertion and retained source links | `PASS` — one version, three source links |
| Conflict/cardinality | Overlap conflicts; non-overlap histories; set values coexist | `PASS` |
| Concurrency | One matching correction wins; stale contender receives precondition failure | `PASS` |
| Replay | Candidate and mutation redelivery append no duplicate version/transition | `PASS` |
| Invalid input | Invalid interval/transition rejects atomically with content-safe error | `PASS` |
| Scope isolation | Cross-tenant/user/agent list/read/write disclose nothing | `PASS` |
| Authoritative transaction | Versions/transitions/provenance/projection/job outcome commit atomically | `PASS` — projection-insert fault rolls everything back |
| Projection rebuild | Rebuilt current state equals authoritative replay without history mutation | `PASS` |
| API contract | List/inspect/history/current/as-of/correct/invalidate/diff and status mapping | `PASS` — 13 API tests |
| Fixture integrity | Manifest SHA/count/split/coverage/frozen IDs validate | `PASS` — 14 cases; SHA `7a7808e17d8384d66bafe0917bc00764ae066d04fe34b6f7169a8a77e2eb0f37` |
| Reporter behavior | Exact comparison, missing/duplicate cases, mismatch redaction, CLI output | `PASS` — 14/14 exact, deterministic prediction SHA `de2c18cb86f540f15bbe55488c60b3b43ca883f5e7f83c12651220a23762f094` |
| Java verification | `./mvnw -B -ntp clean verify` | `PASS` — 128 tests after binding, concurrency, and correction additions |
| Python verification | locked sync, Ruff format/lint, and pytest | `PASS` — 12 tests |
| Runtime smoke | Routed source/candidate jobs create inspectable memory; correction/replay/stale ETag/as-of/diff work; scoped current state survives API restart | `PASS` — `smoke-feature3.sh` |
| Markdown links | `python3 scripts/check_markdown_links.py` | `PASS` — 39 files |
| Git publication | Coherent Feature 3 commit pushed and local `HEAD` equals `origin/main` | `PENDING` |

Feature 3 may be marked `DONE` only after the authoritative implementation, integration/fault
tests, public API, fixture observations, runtime smoke, documentation, and publication gates have
all passed with recorded evidence.
