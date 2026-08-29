# Architecture Decision Records

ADRs capture consequential choices as `Problem → Options → Decision → Why → Trade-off → Validation`. Status meanings:

- `PROPOSED`: selected for the next phase but not yet validated by implementation/benchmark.
- `ACCEPTED`: implemented and verified against stated checks.
- `SUPERSEDED`: replaced by a later ADR; historical rationale remains.

ADRs 0001–0004 remain `PROPOSED` because their representative workload/benchmark validation is
still open. ADR-0005 is `ACCEPTED` against its narrower implementation gate after Feature 5
publication and remote CI completed. Each record states its precise boundary.

- [ADR-0001: Java modular monolith with Python benchmark tooling](0001-java-modular-monolith.md)
- [ADR-0002: PostgreSQL plus pgvector and FTS](0002-postgresql-pgvector-system-of-record.md)
- [ADR-0003: Transactional outbox for memory materialization](0003-transactional-outbox.md)
- [ADR-0004: Versioned memory and hybrid retrieval](0004-versioned-memory-hybrid-retrieval.md)
- [ADR-0005: Verified scope, role boundaries, and governed erasure](0005-authentication-governed-erasure.md)
