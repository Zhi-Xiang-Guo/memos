# Architecture candidates and trade-offs

The scores below are an `INFERRED` design assessment for the initial portfolio/MVP workload, not benchmark data. `5` is favorable. Weighting makes assumptions visible; it is not a substitute for later experiments.

## Selection criteria

| Criterion | Weight | Why |
|---|---:|---|
| Correct temporal/version invariants | 20% | The project’s differentiator is evolving truth, not API breadth |
| Backend engineering signal | 15% | The project targets experienced backend roles |
| Research iteration speed | 15% | Extraction/retrieval policies require frequent experiments |
| Operational simplicity | 15% | One developer must run and explain the system |
| Failure recovery/testability | 15% | Async, retry, and partial failure are core requirements |
| Scale-out path | 10% | The design should evolve without pre-paying distributed complexity |
| Reproducible evaluation | 10% | Benchmark isolation and adapters are mandatory |

## Candidate A — Python synchronous research monolith

```text
FastAPI request
→ extract with LLM
→ embed
→ write PostgreSQL/pgvector
→ respond
```

### Strengths

- quickest access to LLM, embedding, reranking, and benchmark libraries;
- simple local debugging and fewer initial modules;
- good for algorithm spikes.

### Costs and failure modes

- user latency and availability are coupled to LLM/embedding providers;
- retries after uncertain request failure can duplicate memories;
- partial write/index failure is difficult to recover cleanly;
- synchronous simplicity avoids demonstrating the required async/idempotency design;
- Python can still be production-grade, but this topology underrepresents the target Java/backend story.

### When to choose

Choose only for a short-lived extraction/retrieval spike or if rapid ML experimentation is the primary product objective.

## Candidate B — Java modular monolith with outbox worker and PostgreSQL (recommended)

```text
Spring Boot API transaction
→ source event + outbox
→ return accepted/materialization receipt

outbox poll/claim
→ idempotent worker
→ extraction/policy/version transaction + durable projection jobs
→ idempotent pgvector + FTS projection workers
```

The API and workers live in one repository and share domain/application modules. They may run in one process locally and as separate process roles later. PostgreSQL stores source evidence, memory lineages/assertion versions/state transitions, provenance, jobs/outbox, audit, FTS, and vectors.

### Strengths

- strong transaction, concurrency, modular-domain, and failure-recovery story;
- one authoritative datastore minimizes dual-write inconsistency;
- async model work avoids chat-latency coupling;
- adapters let a small Python benchmark runner invoke the same HTTP API without moving domain truth into Python;
- clean evolution to Kafka/CDC or specialized indexes if measurements demand it.

### Costs and failure modes

- more initial code and contract design than Candidate A;
- Java’s LLM/research ecosystem may lag Python for novel models/rerankers;
- outbox polling and job claiming require careful locking, backoff, and observability;
- PostgreSQL may eventually become a write/search contention point.

### Risk controls

- use ports/adapters for model providers;
- keep experimental/evaluation tooling in Python without duplicating memory semantics;
- define projection rebuild and scale triggers from the start;
- Testcontainers and deterministic fakes for failure paths.

## Candidate C — Distributed polyglot memory platform

```text
Java API → Kafka
           ├→ Python extraction workers
           ├→ PostgreSQL truth writer
           ├→ OpenSearch lexical/entity projection
           ├→ Qdrant/Milvus vector projection
           └→ graph projection + Redis cache
```

### Strengths

- independent scaling and provider-specific tooling;
- durable event replay and multiple consumers;
- specialized search engines can improve performance at large corpus sizes;
- resembles a mature platform at genuine scale.

### Costs and failure modes

- cross-store consistency, schema evolution, replay ordering, deletion, and observability dominate the project;
- local setup and benchmark reproducibility become substantially harder;
- no workload evidence currently justifies Kafka, OpenSearch, dedicated vector/graph storage, and Redis together;
- architectural breadth risks hiding the actual memory-policy research.

### When to choose

Choose after Candidate B demonstrates one or more measured scale triggers: outbox throughput ceiling, PostgreSQL search SLO breach, independent consumer/replay need, or vector/graph workload that a specialized store materially improves.

## Decision matrix

| Criterion | Weight | A: sync Python | B: modular Java/outbox | C: distributed polyglot |
|---|---:|---:|---:|---:|
| Temporal/version correctness | 20 | 3 | 5 | 4 |
| Backend engineering signal | 15 | 2 | 5 | 5 |
| Research iteration speed | 15 | 5 | 4 | 3 |
| Operational simplicity | 15 | 5 | 4 | 1 |
| Failure recovery/testability | 15 | 2 | 5 | 3 |
| Scale-out path | 10 | 2 | 4 | 5 |
| Reproducible evaluation | 10 | 4 | 5 | 2 |
| **Weighted total / 5** | **100** | **3.30** | **4.60** | **3.30** |

The score selects Candidate B under current assumptions. It does not claim higher runtime performance.

## Decisions common to all viable candidates

- source evidence, assertion versions, and state transitions append-only while retained, with explicit policy/legal erasure semantics;
- tenant/user/agent scope on every access path;
- current/historical/conflicted/invalidated truth state;
- model and policy version in every derived artifact;
- vector and lexical indexes treated as projections;
- benchmark baselines use the same answer model and dataset split;
- no raw LLM decision may bypass authorization or destructive-action policy.

## Rejected shortcuts

| Shortcut | Reason rejected |
|---|---|
| Store every message as a vector | creates noise, no truth evolution, privacy and cost growth |
| Overwrite old facts | cannot answer history/change questions or audit extraction |
| Send all history to a long-context model | no governed cross-session state; token/attention/deletion problems remain |
| Add Kafka “for scale” immediately | no measured need; increases consistency and local-ops burden |
| Add graph DB for entity memory immediately | entity resolution and dual writes precede demonstrated multi-hop gain |
| Let the LLM choose tenant filters | authorization is a hard application invariant |
