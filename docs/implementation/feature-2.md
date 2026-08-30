# Feature 2 — candidate extraction and write policy

Status: `DONE` and published on 2026-08-27. The coherent implementation commit is `6292b150851218fe6ab480115bde24a214b4d411`; this note records implemented behavior and executed evidence.

Feature 2 consumes the durable source jobs established by [Feature 1](feature-1.md). It covers provider-neutral structured candidate proposals, strict validation, deterministic write policy, sensitivity/trust handling, review/quarantine outcomes, authoritative candidate persistence, and a downstream materialization intent. Versioned fixtures live in the [write-policy v1 fixture directory](../../benchmark/fixtures/write-policy/v1/README.md).

The contract follows the [problem definition](../architecture/01-problem-definition.md), [recommended architecture](../architecture/03-recommended-architecture.md), and [MVP plan](../architecture/04-mvp-plan.md): a model proposes semantic structure, while deterministic code owns scope, permission, validation, policy, idempotency, transaction boundaries, and destructive decisions.

## Feature boundary

The worker reads a Feature 1 `MATERIALIZE_SOURCE` job and its tenant-scoped `source_event`. It invokes a configured structured-extraction provider outside a database transaction, validates the response, applies write policy, and commits the accepted outcome under the current job lease.

One authoritative transaction must:

1. finish the extraction attempt record;
2. insert one idempotent extraction run;
3. insert only sanitized candidate proposals;
4. append deterministic policy decisions and content-safe quarantine records;
5. enqueue one unique downstream candidate-materialization intent when at least one candidate is `REMEMBER`;
6. complete the source-extraction job.

No provider call occurs inside that transaction. A provider may be called again after a crash; semantic keys and unique constraints prevent duplicate authoritative runs, candidates, policy decisions, and downstream intent.

Feature 2 does not create a memory lineage or assertion version. Temporal/version transitions are Feature 3 work, and searchable projections are Feature 4 work.

## Structured extraction contract

The provider output uses snake-case JSON and declares `schema_version: memory-candidate.v1`. The root contains a bounded `candidates` array. Each candidate proposes:

- `proposed_decision`: `REMEMBER`, `IGNORE`, or `REVIEW`;
- `memory_type`: `WORKING`, `SEMANTIC`, `EPISODIC`, or `PROCEDURAL`;
- subject kind/label, predicate, scalar/list value, and normalized content;
- original and parsed event/valid time with precision and confidence;
- importance and confidence in the closed interval `[0, 1]`;
- sensitivity labels;
- bounded advisory candidate relations.

The proposal cannot supply tenant, user, agent, ACL, trust, policy, model, or permission identifiers. Unknown fields, invalid enums/ranges, excessive output, malformed intervals, duplicate JSON keys, and other schema violations fail closed.

Provider, model, prompt, schema, and policy versions plus observed duration/token/call usage are application-owned metadata. They are not trusted because a model echoed them. Raw provider JSON, prompts, source bodies, credentials, and unrestricted exception messages are excluded from ordinary logs and authoritative candidate records.

The default adapter remains deterministic and requires no paid provider. A real provider is optional and environment-enabled. A request model tag is never persisted as immutable provenance merely because the deployment uses that tag:

- `openai-compatible` sends `model-tag` in the request and records a separately configured
  `model-version`. That version is deployment-attested; MemOS cannot independently prove a generic
  endpoint's backing weights or digest.
- `ollama` verifies the configured tag against `/api/tags`, verifies `completion` capability with
  `/api/show`, and requires `model-version` to equal `sha256:<model-digest>` before startup
  succeeds. Native `/api/chat` uses the pinned JSON schema, disables thinking/streaming, and sends
  the configured deterministic seed.
- ingestion records the same immutable extraction version in each source job. A worker refuses an
  active job whose recorded version differs from its provider identity before starting an attempt
  or calling the provider.

Transient transport, timeout, rate-limit, and server failures remain retryable. Client rejection,
model drift, capability mismatch, oversized output, and malformed responses are permanent and are
recorded separately in the extraction-attempt state. Error classes contain no provider body,
source content, credential, or secret.

Selected real-model example:

```text
MEMOS_EXTRACTION_PROVIDER=ollama
MEMOS_EXTRACTION_BASE_URL=<reachable-ollama-base-url>
MEMOS_EXTRACTION_MODEL_TAG=qwen3:4b
MEMOS_EXTRACTION_MODEL_VERSION=sha256:359d7dd4bcdab3d86b87d73ac27966f4dbb9f5efdfcc75d34a8764a09474fae7
MEMOS_EXTRACTION_MODEL_DIGEST=359d7dd4bcdab3d86b87d73ac27966f4dbb9f5efdfcc75d34a8764a09474fae7
MEMOS_EXTRACTION_SEED=42
MEMOS_EXTRACTION_TIMEOUT=300s
```

## Deterministic write policy

The final policy output is `REMEMBER`, `IGNORE`, or `REVIEW`; it is independent of the provider's proposed decision. Each output has stable ordered reason codes and a sensitivity action.

### Trust and permission

- Source trust comes from the retained source context, never provider output.
- Direct user evidence has a different default trust level from assistant, tool, web, and unknown sources.
- A candidate may not widen tenant/user/agent/project scope.
- Project memory requires an explicit trusted capability.
- Procedural memory requires stricter direct-user authorization; assistant/tool/web instructions never gain system or developer authority.

### Sensitivity

Model-proposed and deterministic sensitivity labels combine monotonically: provider output cannot downgrade a stricter deterministic classification. Actions are `NONE`, `REJECT`, `REDACT`, `TOKENIZE`, `RESTRICT`, or `REVIEW`, with the most restrictive applicable action winning.

Authentication secrets and private keys are rejected; their raw candidate content must not be persisted in candidate or quarantine records. Tokenization requires a configured tokenization capability. If it is unavailable, policy fails closed to `REVIEW` rather than substituting an unsafe raw hash.

### Confidence, novelty, and durability

- Duplicate exact candidates are ignored; possible/unknown duplicates are reviewed until Feature 3 owns richer deduplication and conflict rules.
- Low-confidence or uncertain candidates are reviewed.
- Importance cannot independently upgrade a candidate to `REMEMBER`.
- Greeting/noise and temporary facts without an explicit working-memory expiry are ignored.
- A provider relation is only a hint; it cannot supersede or invalidate an existing assertion.

These initial rules are conservative hypotheses and conformance targets, not benchmark-selected thresholds.

## Deterministic fixture v1

[`manifest.json`](../../benchmark/fixtures/write-policy/v1/manifest.json) pins:

- fixture `write-policy-v1`;
- schema `memory-candidate.v1`;
- policy `write-policy-v1`;
- prompt `candidate-extraction-v1`;
- fake provider/model versions;
- case count, dev/test split, required coverage, frozen test IDs, and exact case-file SHA-256.

The 17 cases cover:

| Case family | Contract exercised |
|---|---|
| Durable preference and stable fact | Direct-user semantic acceptance |
| Project decision/capability | Explicit project-scope permission |
| Episodic event | Typed candidate and bounded event interval |
| Temporary information and greeting/noise | Durability/noise rejection |
| Location and contact | Restriction/tokenization/review |
| Synthetic secret | Fail-closed rejection without real credentials |
| Assistant hallucination | Lower-trust source cannot become accepted fact automatically |
| Web prompt injection | Untrusted procedural content gains no authority |
| Low confidence | Review outcome |
| Duplicate paraphrase | Deterministic exact-duplicate outcome |
| Mixed candidates | Per-candidate decisions and one downstream intent |
| Invalid schema | Whole-output fail closed and quarantine |
| Scope escalation | Model cannot create permission |
| Tokenizer unavailable | Sensitive transformation fails closed to review |

Seven cases are development fixtures and ten are frozen test cases. Frozen test cases must not select thresholds or policy rules. A behavioral contract change requires a new version directory; label-only edits to make results look better are forbidden.

## Fixture report

The Java fixture runner or public-API harness produces one prediction JSON object per case. Predictions stay separate from gold labels and include only case ID, validation outcome, candidate keys, and ordinal policy decisions.

The Python reporter validates manifest/case SHA-256, version/count/split/coverage invariants, exact case-set completeness, unique candidate keys, and unique decision ordinals. It then compares predictions with gold labels. It does **not** extract candidates, classify sensitivity, apply trust policy, or reproduce Java domain semantics.

```bash
cd benchmark
uv run memos-write-policy-report \
  --manifest fixtures/write-policy/v1/manifest.json \
  --predictions path/to/predictions.jsonl \
  --output path/to/report.json
```

Every generated report declares `report_kind: DETERMINISTIC_FIXTURE` and includes an explicit disclaimer. It records manifest, case, and prediction SHA-256 values and reports:

- candidate precision, recall, and F1;
- REMEMBER precision, recall, and F1;
- per-decision and macro F1;
- grouped REMEMBER metrics by memory type, source type, and sensitivity;
- validation accuracy and invalid-schema rate;
- decision ratios and missing-decision rate;
- harmful-write count/rate over explicitly labeled harmful candidates.

These are deterministic policy-conformance measurements, not real-model quality results. Fake-provider model calls and tokens are not real usage, no price is inferred, and the formal [benchmark results](../benchmark/results.md) remain `NOT RUN`.

## Privacy and observability

Runtime metrics use low-cardinality extraction outcome, memory type, source type, and sensitivity-action dimensions. Tenant, source, job, candidate, and invocation IDs are correlation fields, not metric labels.

Default logs may carry trace/job/run/attempt IDs, version identifiers, counts, outcome, and stable reason codes. They must not carry source/candidate content, raw provider JSON, idempotency keys, contact/secret values, provider credentials, or lease tokens.

Candidate payloads and content-derived fingerprints are governed content. Feature 5 must remove them during hard erasure; they are not safe tombstones. The fixture's email uses the reserved `.invalid` domain and its credential strings are explicit synthetic markers.

## Explicit non-goals

Feature 2 does not claim or implement:

- real-provider quality, calibrated model confidence, benchmark latency, token cost, or production safety;
- memory lineage/version/state-transition semantics, temporal conflict resolution, or correction (Feature 3);
- embeddings, FTS, pgvector, hybrid retrieval, reranking, or context building (Feature 4);
- complete ACL administration, hard deletion, resurrection prevention, or poisoning-defense proof (Feature 5);
- formal baseline evaluation or changes to `docs/benchmark/results.md` (Feature 6);
- provider exactly-once execution, encryption/key management, Kafka, a graph database, or learned write policy.

## Verification matrix

| Gate | Target evidence | State |
|---|---|---|
| Fixture integrity | Manifest SHA/count/split/coverage/frozen IDs validate | `PASS` — 17 cases, dev 7/test 10, case SHA `af53179f5f729ea1baa168ef38ea3f50dea3f3429863af6f86fe83378f095685` |
| Fixture coverage | All 17 required case families load deterministically | `PASS` |
| Fake extraction | Same source/contract produces byte-stable structured output | `PASS` — adapter tests plus two identical prediction generations |
| Strict schema validation | Valid, malformed, unknown-field, range, size, duplicate-key, and interval cases | `PASS` |
| Trust and permission | Assistant/tool/web distrust, project capability, scope escalation, procedural gate | `PASS` |
| Sensitivity policy | Reject/redact/tokenize/restrict/review and tokenizer fail-closed behavior | `PASS` — fixture also caught and regressed an ISO-date/phone false positive |
| Candidate decisions | Durable/noise/temporary/confidence/duplicate/mixed outcomes | `PASS` — deterministic conformance labels only |
| Authoritative transaction | Attempt/run/candidates/decisions/quarantine/intent/job completion commit atomically | `PASS` — PostgreSQL fault test and runtime smoke |
| Idempotent replay | Repeated provider/worker delivery produces one logical run and downstream intent | `PASS` |
| Lease fencing | Stale worker cannot commit an extraction outcome | `PASS` — database-time owner/token fence |
| Provider transaction boundary | Provider call observes no active database transaction | `PASS` |
| Extraction identity | Request tag differs from durable version; Ollama verifies full digest/capability; mismatched old job makes zero provider calls | `PASS` — focused adapter/handler tests; PostgreSQL permanent-attempt execution awaits remote CI |
| Provider failure semantics | Transient failures retry; deterministic provider/config/response failures are permanent and content-safe | `PASS` — focused adapter/handler tests; PostgreSQL integration execution awaits remote CI |
| Sensitive persistence/logging | Synthetic secret/raw output absent from candidate, quarantine, logs, metrics, and errors | `PASS` — rejected proposal row is `ERASED`; predictions/logs exclude the marker |
| Prediction completeness | Missing, duplicate, or unexpected cases fail report generation | `PASS` — Python reporter tests |
| Reporter metrics | Candidate/decision/group/harmful-write metrics mechanically verified | `PASS` — report kind `DETERMINISTIC_FIXTURE`, prediction SHA `828cbe954393f4ba6448ae9a6ae6d75fe2cb638a675d61364be15705823fe9ca`; 17/17 fixture labels conform and harmful writes are 0/5; these are not real-model metrics |
| Java verification | `./mvnw -B -ntp clean verify` | `PASS` — 80 tests, zero failures/errors |
| Python verification | locked sync, Ruff format/lint, and pytest | `PASS` — 6 tests |
| Runtime smoke | Default fake requires no paid API and reaches the expected job status | `PASS` — source job succeeds and candidate-materialization intent remains `PENDING` |
| Markdown links | `python3 scripts/check_markdown_links.py` | `PASS` — 37 Markdown files |
| Git publication | coherent Feature 2 commit pushed; local `HEAD` equals `origin/main` | `PASS` — implementation commit `6292b150851218fe6ab480115bde24a214b4d411` |

Feature 2 may be marked `DONE` only after the Java/provider/persistence implementation and fault suite pass, the fixture report is generated from observed predictions, every applicable gate is updated with actual evidence, the coherent commit is pushed, and local `HEAD` matches `origin/main`.
