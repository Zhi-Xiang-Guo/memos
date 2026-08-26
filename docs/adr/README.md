# Architecture Decision Records

ADRs capture consequential choices as `Problem → Options → Decision → Why → Trade-off → Validation`. Status meanings:

- `PROPOSED`: selected for the next phase but not yet validated by implementation/benchmark.
- `ACCEPTED`: implemented and verified against stated checks.
- `SUPERSEDED`: replaced by a later ADR; historical rationale remains.

Current records remain `PROPOSED`. Features 0–1 now provide implementation evidence for the modular topology and the ingestion half of ADR-0003, but later version/projection and benchmark gates remain open; each ADR records its precise validation boundary.

- [ADR-0001: Java modular monolith with Python benchmark tooling](0001-java-modular-monolith.md)
- [ADR-0002: PostgreSQL plus pgvector and FTS](0002-postgresql-pgvector-system-of-record.md)
- [ADR-0003: Transactional outbox for memory materialization](0003-transactional-outbox.md)
- [ADR-0004: Versioned memory and hybrid retrieval](0004-versioned-memory-hybrid-retrieval.md)
