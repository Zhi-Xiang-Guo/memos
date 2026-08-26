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
