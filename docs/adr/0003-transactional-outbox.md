# ADR-0003: Transactional outbox for memory materialization

- Status: `PROPOSED`
- Date: 2026-08-26

## Problem

LLM extraction and embedding are slow, rate-limited, nondeterministic external calls. Performing them in a conversation transaction inflates latency and couples availability. Publishing directly to a queue after a database commit creates a lost-event window; publishing before commit can expose nonexistent data.

## Options

1. Synchronous extraction in the request.
2. Database source event + transactional outbox, polled/claimed by an idempotent worker.
3. Dual-write source database and Kafka.
4. Kafka as the sole ingestion authority from day one.

## Decision

Use option 2 for the MVP. Atomically persist source evidence and an outbox/materialization job. The authoritative version transaction atomically appends its state/provenance/audit records, enqueues a separate durable idempotent projection job, and completes the materialization job. Projection workers call remote embedding outside a database transaction, then atomically upsert the rebuildable projection and complete their job. All workers assume at-least-once delivery, use leases and semantic idempotency keys, and never hold a database transaction across a remote model call.

## Why

- accepted source events cannot silently lose materialization intent;
- chat latency excludes provider work;
- PostgreSQL is already required and sufficient for the initial consumer count;
- retry/replay and failure state are explicit;
- a future outbox relay can publish to Kafka without changing the domain event contract.

## Trade-offs

- memory becomes eventually consistent after ordinary conversation;
- poll frequency trades freshness for database load;
- job leases, backoff, and dead-state operations must be built correctly;
- remote call completion followed by process crash requires downstream idempotency.

## Validation

Fault tests kill the process before/after source commit, claim, provider response, version commit, and projection commit. The invariant is no lost committed source, no duplicate logical version, visible incomplete state, and safe replay.

Feature 1 validates the ingestion half of this decision: atomic source/outbox commit, concurrent idempotency, `SKIP LOCKED` claims, lease-token fencing, transient/dead/replay paths, handler execution outside a transaction, and one payload-free logical effect under reclaim all pass against PostgreSQL 18. The ADR remains `PROPOSED` until Features 3–4 validate authoritative version and projection completion transactions; Feature 1 does not claim those later boundaries.

Feature 2 additionally validates a remote-call-outside-transaction extraction path and a lease-fenced transaction that commits sanitized candidate results, append-only policy decisions, content-free quarantine, one unique downstream intent, and source-job completion together. Fault injection proves rollback before commit and idempotent replay after commit. The ADR remains `PROPOSED` because authoritative memory-version and projection-job transactions are still Feature 3–4 work.

Feature 3 validates the authoritative memory transaction and durable projection intent. Feature 4
implements embedding outside a transaction followed by a database-time lease fence and
transition-sequence fence; a superseded snapshot cannot replace a newer projection, while an
invalidation commits a zero-row projection checkpoint. GitHub Actions run `#17` published the
PostgreSQL integration and runtime-smoke evidence; representative workload evidence is still
missing, so this ADR is not yet promoted.

Feature 6 adds a scope-safe source-level read model over the durable extraction, authority, and
projection intents plus a bounded benchmark client that waits on observable terminal state. Commit
`2bf7689` and GitHub Actions run `#30` verify the Java, PostgreSQL, Python, and compose-smoke
mechanics. This makes incomplete and failed chains machine-observable without exposing payloads.
It does not validate representative freshness, throughput, database contention, or the final
read-your-write contract, so the ADR remains `PROPOSED` pending the declared workload evidence.

The current Feature 6 candidate adds lease renewal for slow external calls and serial claimed
batches. Every claimed job is monitored before the first handler starts; renewal runs at one third
of the lease duration and extends from PostgreSQL `clock_timestamp()` only while job ID,
`CLAIMED`, owner, token, and unexpired lease match. Renewal is stopped and joined before a separate
success/retry/dead update, while atomic handlers keep their existing in-transaction fence. Unit
tests cover batch monitoring and renewal-loss behavior; PostgreSQL tests cover stale tokens,
reclaim races, and a handler longer than its original lease, pending remote CI publication. This
reduces avoidable duplicate provider work but does not claim end-to-end exactly once.
