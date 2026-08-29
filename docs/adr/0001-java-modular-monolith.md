# ADR-0001: Java modular monolith with Python benchmark tooling

- Status: `PROPOSED`
- Date: 2026-08-26

## Problem

MemOS must demonstrate mature backend boundaries, transactions, concurrency, idempotency, and failure recovery while retaining fast access to Python-heavy evaluation/model tooling. A service fleet would add distributed consistency before the workload is known; a synchronous research script would not exercise the required engineering.

## Options

1. Python/FastAPI synchronous monolith.
2. Java/Spring Boot modular monolith with API and worker roles; Python only for benchmark/analysis tooling.
3. Java API plus Python model microservices and an event platform from day one.

## Decision

Use option 2. Set Java 21 or newer LTS-compatible code as the production baseline and pin the exact JDK/Spring Boot versions at Feature 0 kickoff. Keep memory domain rules in Java. Use Python as a client-side benchmark and data-analysis workspace, not as a second implementation of truth semantics.

## Why

- one codebase and database preserve a small consistency boundary;
- explicit modules can encode domain ownership before network boundaries are justified;
- API and worker roles demonstrate asynchronous production concerns without mandatory service sprawl;
- Java aligns with the target backend interview while Python retains research ergonomics;
- model providers remain replaceable HTTP/library adapters.

## Trade-offs

- some emerging LLM libraries appear in Python first;
- a multi-language repository adds tooling overhead;
- module boundaries require enforcement or the monolith will become tangled;
- this choice does not prove Java is faster or more reliable by default.

## Validation

- module-boundary tests prevent adapter/domain dependency inversion;
- deterministic model fakes allow core tests without Python or paid APIs;
- one benchmark manifest can run every baseline through stable HTTP contracts;
- revisit if a required model/runtime cannot meet the adapter contract or adds unacceptable overhead.

Feature 6 now has a locally verified cross-language budget contract: Python baseline builders and
the Java MemOS context assembler count complete rendered contexts through the selected
digest-pinned embedding tokenizer, while the Java HTTP response exposes counting usage for runner
attribution. Remote publication and the unified four-baseline run remain pending, so this ADR is
still `PROPOSED`.
