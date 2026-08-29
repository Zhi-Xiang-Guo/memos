# Feature 6 - reproducible evaluation and evidence package

Status: `DOING`. The product workload and first smoke contract are `DONE / PUBLISHED` through
commit `4120144` and
[GitHub Actions run #24](https://github.com/Zhi-Xiang-Guo/memos/actions/runs/33273671807). No
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

The manifest pins the cases, answer/summary prompts, complete CC BY 4.0 license, and attribution
notice with SHA-256 over UTF-8 text after LF normalization. The dataset license applies only to
the files named in its notice; it does not imply a license for unrelated repository material.

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
