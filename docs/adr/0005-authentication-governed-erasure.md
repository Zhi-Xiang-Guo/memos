# ADR-0005: Verified scope, role boundaries, and governed erasure

- Status: `PROPOSED` — implementation complete; remote verification pending
- Date: 2026-08-30

## Problem

Forgeable scope headers and a static operator key can demonstrate repository filtering but cannot
serve as an authentication boundary. Privacy deletion also cannot be a best-effort `DELETE` from
one table: authoritative evidence, derived content, projections, jobs, retries, and old work may
leak or recreate the deleted memory. MemOS needs a small reference boundary that is testable
without adding an identity service or another infrastructure system.

## Options

1. Keep trusted headers/operator key and document an upstream proxy requirement.
2. Validate signed tokens in the API, enforce explicit roles, and run deletion as a durable,
   tenant-bound PostgreSQL workflow with immediate hiding and asynchronous fenced erasure.
3. Add a full identity provider, policy engine, event bus, deletion service, and external audit
   store in the MVP.

## Decision

Use option 2.

- Validate issuer, audience, expiry, subject, scope claims, signature, and an allowlisted role set.
- Derive tenant/user/agent scope only from verified claims.
- Use `USER`, `OPERATOR`, and `PRIVACY_ADMIN` for the current API boundaries.
- Keep self-service memory deletion exact-scope and user deletion tenant-bound to a privacy admin.
- Hide lineages/projections and cancel relevant incomplete jobs in the request transaction.
- Erase authoritative and derived content in one lease-fenced PostgreSQL transaction.
- Retain only content-safe operation/audit metadata and opaque append-only tombstones.
- Treat `DEAD` as isolated incomplete work; only a tenant privacy admin can requeue the same
  operation, and dead user deletion continues to block ingestion.
- Keep hostile memory as escaped text under an explicit untrusted-data context boundary.

The checked-in HS256 mode is for local/reference verification. It does not select a production
identity provider or key-management design.

## Why

- Verified claims remove client control over authorization scope.
- Three roles are enough to exercise real least-privilege boundaries without a premature policy
  service.
- Immediate hiding gives a deterministic read contract while the durable worker handles retries.
- A single PostgreSQL erasure transaction matches the current authority/projection topology and
  avoids partial cross-store completion.
- Database-time leases, tokens, triggers, and final fencing prevent a stale worker from committing
  partial erasure.
- Opaque tombstones block accidental reuse without retaining guessable content hashes.
- Explicit dead-operation recovery avoids either silent abandonment or automatic infinite retry.

## Trade-offs

- HS256 uses a shared secret; production deployments normally need asymmetric discovery, rotation,
  revocation, and issuer operations outside this repository.
- Application scope checks are not database row-level security; a compromised adapter credential
  remains a larger boundary.
- One transaction is appropriate while all retained/projection data is in PostgreSQL, but future
  external indexes/providers need a reconciled multi-system deletion protocol.
- Audit rows are append-only in the same database, not an independently administered tamper-evident
  archive.
- Deletion completion does not prove erasure from backups, WAL archives, replicas, provider logs,
  or legally mandated retention systems.
- XML escaping and an untrusted-data root reduce structural prompt injection; they do not prove
  behavioral resistance in any LLM.

## Validation

- MVC/security tests reject forged headers, invalid signatures/audiences/claims, and unauthorized
  roles while deriving scope from valid claims.
- PostgreSQL integration tests cover tenant isolation, request idempotency, immediate hiding,
  complete memory/user erasure, tombstone immutability, stale replay, lease-expiry rollback,
  dead-operation requeue, and concurrent user deletion/ingestion.
- Runtime smoke crosses API, worker, PostgreSQL, restart, trace audit, and post-delete write paths.
- A frozen four-case poisoning fixture verifies structural escaping and exact text round-trip.
- Promote this ADR to `ACCEPTED` only after the coherent commit is pushed and remote Java,
  PostgreSQL, docs, Python, and compose-smoke jobs are green.
