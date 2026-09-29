# ADR-0006: Scoped RAG answering as a separate application module

- Status: `PROPOSED` — implementation gate locally verified; real-model quality remains unmeasured.
- Date: 2026-09-29

## Problem

MemOS can retrieve evidence, but cannot demonstrate the complete consumer-side flow of tool
selection, grounded answer generation, incremental transport, citation validation, and cancellation.
Adding provider-specific code to the retrieval controller would duplicate context policy and mix
HTTP, authentication, model transport, and business decisions.

## Decision

Introduce a framework-free `answering` module with model/port/service packages. Reuse one
`MemoryEvidenceService` for the existing retrieval API and the answering consumer. Keep provider
protocols in adapters, composition in Spring configuration, and JSON/SSE delivery in the API.
A model may propose at most one read-only `search_memory(query)` call. Authenticated scope is
captured by the HTTP caller and cannot be supplied by the model. No write tool is exposed.

Structured output remains a proposal. Deterministic code validates exact fields, refusal/citation
consistency, canonical IDs, uniqueness, and membership in selected evidence. Every SSE event has
a JSON-object payload. Deltas are provisional; only the validated answer followed by done is a
successful result. Provider errors after any content is emitted do not restart the generation.

Use native Ollama chat for this bounded practice path, plus the existing injectable embedding and
reranker ports. A deterministic fake is the credential-free default. Do not add infrastructure or
change the frozen Feature 6 campaign to accommodate this practice path.

## Trade-offs

- One tool round is easy to audit but does not support iterative autonomous research.
- Incremental JSON preserves native streaming but requires clients to treat fragments as provisional.
- Citation membership proves provenance identity, not semantic faithfulness.
- A shared answer/rerank model simplifies setup; independent provider configurations and cost
  accounting can be added after comparative evidence.
- API deadlines and stream cancellation do not prove that an external provider stopped computing.
- The new adapter validates response tags rather than immutable digests; formal claims still use
  the existing benchmark identity contract.

## Verification

The [practice guide](../implementation/rag-practice.md) records local verification, reproduction,
known limits, and the separate retrieval/answer exercise. Architecture rules reject package and
module boundary regressions. This ADR remains PROPOSED until the declared real-model workload
establishes utility and latency/cost trade-offs; protocol test success alone does not establish them.
