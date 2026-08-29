# Feature 6 - reproducible evaluation and evidence package

Status: `DOING`. The initial product workload and smoke contract were published through commit
`4120144` and
[GitHub Actions run #24](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33273671807). The
current v1 contract additionally pins Java extraction resources and forbidden-context labels; the
run-package/metrics core is `DONE / PUBLISHED` through commit `afcabe7` and
[GitHub Actions run #26](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33274839393). No
four-baseline result has been published yet.

## Product workload

Feature 6 evaluates one bounded product scenario: a bilingual personal/project assistant that
must remember durable user preferences and project facts across sessions, preserve updates and
conflicts, retrieve only authorized evidence, abstain when evidence is missing or unresolved, and
treat external/tool memory as untrusted data.

This closes OQ-001 for the MVP evaluation boundary. It does not claim that the same configuration
generalizes to every agent or enterprise workload.

## Frozen local-model path

The credential-free real-model path uses an existing Ollama installation with explicit local
model IDs:

| Role | Model tag | Observed Ollama model ID |
|---|---|---|
| extraction, summary, answer | `qwen3:4b` | `359d7dd4bcda` |
| embedding | `qwen3-embedding:0.6b` | `ac6da0dfba84` |

The first environment observation used Ollama `0.33.2`. A run remains ineligible if its manifest
does not record the actual version and IDs or if they differ from the selected experiment
configuration. A model tag alone is not treated as an immutable snapshot.

## Smoke dataset

`benchmark/datasets/memos-assistant-smoke/v1` is a MemOS-authored synthetic bilingual dataset. It
contains no real user records or credentials. Scenario families stay in one split, test scenario
IDs are frozen, questions are withheld from the ingestion/extraction path, and gold evidence may
not point past the declared question cutoff.

The manifest pins the cases, answer/summary prompts, Java extraction prompt/schema, complete CC BY
4.0 license, and attribution notice with SHA-256 over UTF-8 text after LF normalization. The
dataset license applies only to the files named in its notice; it does not imply a license for the
Java extraction resources or unrelated repository material.

The dataset covers simple and multi-session recall, preferences, updates, temporal questions,
unresolved contradiction, multi-hop linking, abstention, noise retention, bilingual retrieval,
and a write-to-use poisoning case. Idempotency/concurrency and privacy/deletion remain system
tracks exercised by the Java/PostgreSQL fault and smoke suites rather than being collapsed into an
answer-quality score.

## Baseline contract

The primary smoke comparison will run:

1. ordered full history with deterministic chronological truncation;
2. rolling session summary plus the declared recent-turn allowance;
3. raw-turn vector TopK with the same hard scope boundary;
4. the Java MemOS API with versioned lifecycle, hybrid retrieval, and context policy.

Every baseline receives the same answer prompt, answer model, evidence-token budget, question, and
deterministic output schema. Preprocessing calls and tokens remain attributable to the baseline.
The smoke run uses one repetition to validate harness integrity; a frozen model-dependent test
campaign requires three predeclared repetitions before producing a formal result.

## Evidence gate

A Feature 6 result is eligible only when the verifier confirms:

- clean committed revision and exact dataset/prompt hashes;
- actual model IDs, Ollama version, environment, seed, temperature, and budgets;
- one raw row for every expected baseline, question, and repetition;
- explicit failed/excluded rows rather than silent removal;
- attributable model calls, tokens, latency, retrieval evidence, and storage observations;
- metrics and Markdown tables regenerated from the raw artifact package;
- a baseline-parity report and no inspection-driven edit to the frozen test split.

Until that gate passes, `docs/benchmark/results.md` remains `NOT RUN`.

## Implemented run-package core

`memos_benchmark.artifacts` expands the frozen split into exactly one execution identity per
baseline, question, and repetition. It creates a run directory only when the target does not
exist, records a full Git SHA and dirty-worktree state, pins dataset/case/split membership hashes,
hashes the comparison configuration, and writes an integrity manifest over every raw and derived
artifact. A dirty worktree is recorded but remains ineligible.

The package verifier requires the exact file set and rejects missing, duplicated, or unexpected
execution rows. `FAILED` and `EXCLUDED` rows require a content-safe error class and remain in metric
denominators. `metrics.json`, `costs.json`, and `failures.md` are independently regenerated from
the raw rows; updating a checksum cannot legitimize a hand-edited derived result.

The deterministic metrics currently include:

- overall and per-track answer accuracy, with forbidden-answer leakage;
- abstention precision/recall/F1;
- Recall@K, complete recall, MRR, selected-evidence completeness, and context precision;
- forbidden-context exposure for explicitly labeled stale or hostile events;
- nearest-rank p50/p95/p99 total latency with sample counts;
- attributable input/output/embedding tokens and model calls by baseline.

Local-model monetary and energy cost remain `N/E`; the usage report emits `null`, not a fabricated
zero-dollar claim. The runner, live provider calls, Java API ingestion/retrieval, storage readings,
and final Markdown/table generation remain to be implemented before a smoke result is eligible.

Publication verification for this core passed Java 25, PostgreSQL/compose regression, a clean
Python 3.14.7 install with 28 tests, and the Markdown gate in run `#26`. These gates establish
implementation integrity only; they contain no model-dependent answer or retrieval score.
