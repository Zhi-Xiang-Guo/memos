# Memory benchmark analysis: LoCoMo, LongMemEval, and BEAM

Research cutoff: **2026-08-26**. This document defines evidence and adoption rules; **it contains no MemOS benchmark result**. Upstream paper scores are not reproduced or adopted as MemOS evidence.

## Evidence grades and pinned inputs

- `CONFIRMED`: supported by an original paper, official repository/dataset, or pinned evaluation source.
- `INFERRED`: an engineering interpretation of confirmed material.
- `HYPOTHESIS`: must be tested.
- `UPSTREAM-REPORTED`: a benchmark author reports a result or validation statistic; MemOS has not independently reproduced it.
- `N/E`: not established by the reviewed upstream materials.

| Benchmark | Original publication | Audited code revision | Released data/license boundary |
|---|---|---|---|
| **LoCoMo** | [ACL 2024 paper](https://aclanthology.org/2024.acl-long.747/) | [`snap-research/locomo@3eb6f2c585f5e1699204e3c3bdf7adc5c28cb376`](https://github.com/snap-research/locomo/commit/3eb6f2c585f5e1699204e3c3bdf7adc5c28cb376) | Repository/data release: [CC BY-NC 4.0](https://github.com/snap-research/locomo/blob/3eb6f2c585f5e1699204e3c3bdf7adc5c28cb376/LICENSE.txt) |
| **LongMemEval** | [arXiv](https://arxiv.org/html/2410.10813), [ICLR 2025 OpenReview](https://openreview.net/pdf?id=pZiyCaVuti) | [`xiaowu0162/LongMemEval@9e0b455f4ef0e2ab8f2e582289761153549043fc`](https://github.com/xiaowu0162/LongMemEval/commit/9e0b455f4ef0e2ab8f2e582289761153549043fc) | Repository: [MIT](https://github.com/xiaowu0162/LongMemEval/blob/9e0b455f4ef0e2ab8f2e582289761153549043fc/LICENSE); current cleaned HF dataset card: [MIT](https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned) |
| **BEAM** | [arXiv](https://arxiv.org/html/2510.27246), [ICLR 2026 proceedings](https://proceedings.iclr.cc/paper_files/paper/2026/hash/d7f0cfa0fe759b033d5262e1bb7d4065-Abstract-Conference.html) | [`mohammadtavakoli78/BEAM@3e12035532eb85768f1a7cd779832b650c4b2ef9`](https://github.com/mohammadtavakoli78/BEAM/commit/3e12035532eb85768f1a7cd779832b650c4b2ef9) | Code: [MIT](https://github.com/mohammadtavakoli78/BEAM/blob/3e12035532eb85768f1a7cd779832b650c4b2ef9/LICENSE); data cards: [CC BY-SA 4.0](https://huggingface.co/datasets/Mohammadta/BEAM), [10M card](https://huggingface.co/datasets/Mohammadta/BEAM-10M) |

`CONFIRMED` — A repository license and a dataset license are not interchangeable; the pinned releases above publish the stated boundaries.

`INFERRED` — MemOS must record both and must not redistribute CC BY-NC LoCoMo data in a commercial artifact without a separate rights review. Legal suitability is `N/E`; this is an engineering inventory, not legal advice.

## At-a-glance comparison

| Dimension | LoCoMo | LongMemEval | BEAM |
|---|---|---|---|
| Primary unit | Long multi-session, multimodal personal conversation | Question-specific long chat history | Extremely long coherent multi-domain conversation |
| Released scale | 10 conversations; 1,986 QA items in pinned JSON | 500 questions; oracle, S, and M variants | 100 conversations, 20 questions each; 128K/500K/1M/10M tiers |
| Best signal for MemOS | Personal recall and multi-hop/evidence retrieval | Update, temporal reasoning, abstention, retrieval diagnostics | Long-context stress plus ten memory abilities |
| Main answer metric | Normalized token/stem F1 | Question-type-specific LLM correctness judge | Nugget judge; ordering has Kendall-style metric |
| Retrieval metric | Evidence recall at K in official code | recall-any/all and nDCG at K | `N/E` as a standard official primary metric; add one in the MemOS adapter |
| Biggest limitation | Only 10 released conversations; partial multimodal/event-summary reproducibility | Synthetic/noisy filler, LLM judge, dataset revision drift | Synthetic, expensive, only two probes/ability/conversation, audited harness defects |
| Recommended phase | Phase 1 | Phase 1, strongest core benchmark | Phase 2 after adapter/judge validation |

Counts above describe the named pinned/current releases, not every historical paper-generation artifact.

## LoCoMo

### Task and data

`CONFIRMED` — LoCoMo studies very long-term conversational memory using multi-session conversations with personal events and evolving information. The paper defines three broad tasks: question answering, event summarization, and multimodal dialogue generation ([paper](https://aclanthology.org/2024.acl-long.747/)).

`INFERRED` — MemOS's initial adapter should use QA; the other tasks exercise capabilities beyond the Phase 1 text-memory core.

`CONFIRMED` — The pinned public release contains the ten longest/high-quality conversations selected from the original set; the README says images are not distributed, while image URLs, captions, and search queries remain ([pinned README](https://github.com/snap-research/locomo/blob/3eb6f2c585f5e1699204e3c3bdf7adc5c28cb376/README.MD)). The released JSON is [`data/locomo10.json`](https://github.com/snap-research/locomo/blob/3eb6f2c585f5e1699204e3c3bdf7adc5c28cb376/data/locomo10.json).

`CONFIRMED` — Direct enumeration of the pinned JSON yields **10 conversations and 1,986 QA items**:

| Official category ID | Task meaning in evaluation code | Count |
|---|---|---:|
| 1 | multi-hop | 282 |
| 2 | single-hop | 321 |
| 3 | temporal | 96 |
| 4 | open-domain | 841 |
| 5 | adversarial/unanswerable | 446 |
| **All** |  | **1,986** |

Categories 1–4 total **1,540**. Therefore papers or systems reporting “LoCoMo 1,540” may be excluding category 5; every MemOS run must explicitly name **full-1,986** or **non-adversarial-1,540** rather than calling both “LoCoMo.” Category handling is visible in the official evaluator ([`evaluation.py`](https://github.com/snap-research/locomo/blob/3eb6f2c585f5e1699204e3c3bdf7adc5c28cb376/task_eval/evaluation.py#L189-L241)).

`CONFIRMED` — The paper reports roughly 600 turns and 16K tokens per conversation on average, spanning up to 32 sessions. The released data are synthetic conversations generated with LLM-driven personas/scenarios and then human-verified/edited; they are not organic production histories ([paper abstract and dataset sections](https://aclanthology.org/2024.acl-long.747/)).

### Metrics and official code

`CONFIRMED` — QA generation and scoring are wired in [`task_eval/evaluate_qa.py`](https://github.com/snap-research/locomo/blob/3eb6f2c585f5e1699204e3c3bdf7adc5c28cb376/task_eval/evaluate_qa.py#L67-L113). The evaluator uses normalized token/stem F1 for answer overlap, reports category breakdown, and special-cases adversarial responses with an unanswerable-phrase heuristic ([`evaluation.py`](https://github.com/snap-research/locomo/blob/3eb6f2c585f5e1699204e3c3bdf7adc5c28cb376/task_eval/evaluation.py#L189-L241)). Retrieval evidence coverage/recall is also calculated from annotated evidence turns.

`CONFIRMED` — The paper uses ROUGE and FactScore-style precision/recall/F1 for event summarization. The [pinned README](https://github.com/snap-research/locomo/blob/3eb6f2c585f5e1699204e3c3bdf7adc5c28cb376/README.MD) marks event-summarization evaluation code as “coming soon,” so full paper-wide reproduction is **not available from this repository revision alone**.

`N/E` — Exact reproduction of the multimodal task is not established because the image files are not released. URL availability over time is also not guaranteed.

### Limitations

- `INFERRED` — Ten released conversations give limited persona/topic diversity and make conversation-level confidence intervals wide.
- `INFERRED` — Open-domain questions can reward parametric world knowledge rather than retrieval from memory; report category 4 separately.
- `INFERRED` — Token/stem F1 penalizes valid paraphrases and can reward lexical copying. Add a blinded human/LLM audit, but never replace the official score silently.
- `INFERRED` — Because Category 5 correctness depends on a brittle phrase heuristic, report an independently specified abstention metric as a secondary result.
- `INFERRED` — Synthetic, human-edited English conversations do not establish behavior for real multilingual, privacy-sensitive, tool-rich traffic.
- `N/E` — Current public artifacts do not establish contamination-free evaluation for hosted answer models.

### MemOS mapping

`INFERRED` — Ingest every session and turn chronologically as source events that remain append-only while retained. Retain official conversation/session/turn IDs so annotated evidence maps back to memory provenance. Do not reveal future sessions or QA to the write/extraction path.

Report at least:

1. official QA F1 by all five categories and overall, naming the 1,986/1,540 variant;
2. evidence `Recall-any@K`, complete multi-evidence recall, MRR/nDCG only when label semantics permit;
3. final-context evidence precision/recall and tokens supplied;
4. answer abstention precision/recall/F1 for category 5;
5. p50/p95 write, retrieval, and end-to-end latency; model calls/tokens/cost; storage growth.

`HYPOTHESIS` — Entity/graph retrieval should help category 1, while temporal truth state should help category 3. Category 2 is the control where a graph may add cost without accuracy.

## LongMemEval

### Task and data

`CONFIRMED` — LongMemEval contains **500 manually curated questions** spanning five target abilities: information extraction, multi-session reasoning, knowledge updates, temporal reasoning, and abstention ([paper](https://arxiv.org/html/2410.10813), [pinned README](https://github.com/xiaowu0162/LongMemEval/blob/9e0b455f4ef0e2ab8f2e582289761153549043fc/README.md)).

The released question types are:

- `single-session-user`, `single-session-assistant`, and `single-session-preference`;
- `multi-session`;
- `knowledge-update`;
- `temporal-reasoning`;
- `_abs` abstention variants. The official set contains 30 abstention questions.

`CONFIRMED` — Examples carry question ID/type/text/answer/date, haystack session IDs and dates, session turns, answer-session IDs, and turn-level `has_answer` evidence annotations. These labels support both answer and retrieval evaluation ([README/data format](https://github.com/xiaowu0162/LongMemEval/blob/9e0b455f4ef0e2ab8f2e582289761153549043fc/README.md)).

`CONFIRMED` — The upstream distributions serve different purposes:

| Variant | Intended scale/use |
|---|---|
| `oracle` | Only answer-bearing evidence sessions; reader upper bound, not a realistic retrieval score |
| `S` | Approximately 40–50 sessions and about 115K tokens per question history; practical initial end-to-end track |
| `M` | 500 sessions and about 1.5M tokens; retrieval/storage scalability track |

The README and paper use slightly different approximate session counts for `S`; preserve that approximation rather than inventing a single exact invariant.

`CONFIRMED` — The official project now points to a cleaned dataset revision that removes noisy sessions ([Hugging Face card](https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned)). This is a post-paper data evolution. A MemOS run must pin the exact Hugging Face revision/file hash and label results `original` or `cleaned`; scores across them are not directly interchangeable by name alone.

### Metrics and official code

`CONFIRMED` — Answer evaluation uses question-type-specific LLM judge prompts. At the pinned commit, [`evaluate_qa.py`](https://github.com/xiaowu0162/LongMemEval/blob/9e0b455f4ef0e2ab8f2e582289761153549043fc/src/evaluation/evaluate_qa.py#L11-L116) configures `gpt-4o-2024-08-06` with temperature 0 and reduces the response to a binary label through a “yes” text check. [`print_qa_metrics.py`](https://github.com/xiaowu0162/LongMemEval/blob/9e0b455f4ef0e2ab8f2e582289761153549043fc/src/evaluation/print_qa_metrics.py) reports overall/task averages and abstention accuracy.

`UPSTREAM-REPORTED` — The paper reports more than 97% agreement between this judge setup and human annotation. MemOS has not reproduced that validation, and it is not a guarantee for different prompts/models or MemOS outputs.

`CONFIRMED` — Retrieval utilities calculate:

- `recall_any`: whether at least one annotated evidence item is retrieved;
- `recall_all`: whether all required evidence items are retrieved;
- nDCG over ranked evidence.

Definitions are in [`src/retrieval/eval_utils.py`](https://github.com/xiaowu0162/LongMemEval/blob/9e0b455f4ef0e2ab8f2e582289761153549043fc/src/retrieval/eval_utils.py#L24-L46); [`print_retrieval_metrics.py`](https://github.com/xiaowu0162/LongMemEval/blob/9e0b455f4ef0e2ab8f2e582289761153549043fc/src/evaluation/print_retrieval_metrics.py) reports metrics such as Recall-all@5/10 and NDCG-any@5/10 and excludes abstention questions from evidence retrieval scoring.

### Limitations

- `INFERRED` — Relevant evidence is curated, but haystack conversations are largely constructed from public dialogue corpora; this is not a longitudinal study of persistent real users.
- `INFERRED` — Because binary correctness relies on a model judge and prompt parsing, pin the judge/prompt, retain raw decisions, and audit a stratified sample, especially negation, dates, partial answers, and abstentions.
- `INFERRED` — Original versus cleaned dataset drift can change difficulty. Pin a data revision and never pool the scores.
- `INFERRED` — Question-specific compiled histories do not measure online write-policy precision by themselves: the system already receives the history selected for that question. Add MemOS write-gate labels/tests separately.
- `INFERRED` — `M` is resource intensive; accuracy-only reporting hides indexing time, storage, token, and latency cost.
- `N/E` — Hosted-model contamination-free status is not established.

### MemOS mapping

`INFERRED` — LongMemEval is the strongest Phase 1 external benchmark because knowledge update, time, multi-session evidence, and abstention map directly to MemOS's versioned-memory contract.

Use this progression:

1. `oracle` to validate reader/context construction without retrieval;
2. cleaned `S` for first end-to-end runs;
3. cleaned `M` only after retrieval and storage instrumentation is stable.

Ingest sessions in timestamp order; preserve question date and session/turn evidence IDs. Report official overall/type accuracy plus recall-any/all and nDCG at declared K. Add stale-evidence rate, update-transition accuracy, abstention precision/recall/F1, evidence-token budget, latency/cost, and a human audit of judge disagreements.

`HYPOTHESIS` — Bitemporal state should improve `knowledge-update` and `temporal-reasoning`; an explicit retrieval gate should improve abstention without harming evidence-bearing questions.

## BEAM

### Task and data

`CONFIRMED` — BEAM is an ICLR 2026 benchmark for long-term conversational memory at extreme context sizes ([proceedings](https://proceedings.iclr.cc/paper_files/paper/2026/hash/d7f0cfa0fe759b033d5262e1bb7d4065-Abstract-Conference.html)). The official repository describes **100 conversations and 2,000 human-validated probing questions**, with 20 questions per conversation: two for each of ten abilities ([pinned README](https://github.com/mohammadtavakoli78/BEAM/blob/3e12035532eb85768f1a7cd779832b650c4b2ef9/README.md)).

The ten abilities are:

1. abstention;
2. contradiction resolution;
3. event ordering;
4. information extraction;
5. instruction following;
6. knowledge update;
7. multi-session/multi-hop reasoning;
8. preference following;
9. summarization;
10. temporal reasoning.

`CONFIRMED` — The standard [BEAM dataset card](https://huggingface.co/datasets/Mohammadta/BEAM) contains 90 conversations across the smaller tiers; [BEAM-10M](https://huggingface.co/datasets/Mohammadta/BEAM-10M) contains the remaining 10 conversations. Together they form the described 100-conversation suite.

`CONFIRMED` — Upstream labels the tiers 128K, 500K, 1M, and 10M in prose/dataset materials, while repository paths/artifacts also use `100K`. This naming inconsistency must be preserved in adapter manifests by recording actual file, row ID, and token count; do not silently rename a tier.

`CONFIRMED` — Conversations and probes are synthetically generated from coherent plans across multiple domains; probing questions are human validated and answer rubrics are represented as atomic “nuggets” ([paper](https://arxiv.org/html/2510.27246)). This enables controlled abilities at large scale but is not organic user traffic.

### Intended metrics

`CONFIRMED` — For nine abilities, the paper describes an LLM-as-judge assigning each answer nugget `0`, `0.5`, or `1`, then averaging nugget credit. Event ordering aligns extracted events semantically and applies a Kendall tau-b-style ordering score while considering event presence ([paper evaluation](https://arxiv.org/html/2510.27246)).

`N/E` — BEAM does not provide an official, standard primary evidence-retrieval score comparable to LongMemEval's recall-any/all in the reviewed harness. MemOS should add retrieval metrics using a separately versioned evidence annotation/adapter and label them non-official.

### Pinned evaluation-harness audit

The following are `CONFIRMED` observations of commit `3e120355...`; they are not claims about later upstream fixes.

1. **Question placeholder is not substituted.** [`run_evaluation.py`](https://github.com/mohammadtavakoli78/BEAM/blob/3e12035532eb85768f1a7cd779832b650c4b2ef9/src/evaluation/run_evaluation.py#L39-L77) passes `probing_question` to metric functions, but the nine nugget metric functions in [`compute_metrics.py`](https://github.com/mohammadtavakoli78/BEAM/blob/3e12035532eb85768f1a7cd779832b650c4b2ef9/src/evaluation/compute_metrics.py#L339-L636) replace `<rubric_item>` and `<llm_response>` without replacing `<question>`. The shared prompt contains `<question>` ([`prompts.py`](https://github.com/mohammadtavakoli78/BEAM/blob/3e12035532eb85768f1a7cd779832b650c4b2ef9/src/prompts.py#L11547-L11617)). Thus this pinned path sends a literal placeholder rather than the actual question to those judges.
2. **Half-credit is truncated.** Those nine ability functions convert the judge's score with `int(...)`, although the prompt permits `0.5`. In Python, `int(0.5)` becomes `0`; the implemented metric therefore differs from the stated three-level rubric ([`compute_metrics.py`](https://github.com/mohammadtavakoli78/BEAM/blob/3e12035532eb85768f1a7cd779832b650c4b2ef9/src/evaluation/compute_metrics.py#L339-L636)).
3. **Event-ordering coverage can be ignored by reporting.** The ordering path extracts facts and then overwrites that result with newline-split answer text ([`compute_metrics.py`](https://github.com/mohammadtavakoli78/BEAM/blob/3e12035532eb85768f1a7cd779832b650c4b2ef9/src/evaluation/compute_metrics.py#L392-L409)). Its scorer computes precision, recall, F1, normalized tau, and `final_score = tau_norm × f1` ([same file](https://github.com/mohammadtavakoli78/BEAM/blob/3e12035532eb85768f1a7cd779832b650c4b2ef9/src/evaluation/compute_metrics.py#L270-L308)), but [`report_results.py`](https://github.com/mohammadtavakoli78/BEAM/blob/3e12035532eb85768f1a7cd779832b650c4b2ef9/src/evaluation/report_results.py#L40-L47) aggregates `tau_norm`, not `final_score`. A system may therefore receive an ordering score that does not penalize missing events as intended by the combined score.
4. **README command drift.** The README names `src/model_inference/answer_generation.sh`, while the pinned tree places answer generation under [`src/answer_probing_questions/`](https://github.com/mohammadtavakoli78/BEAM/tree/3e12035532eb85768f1a7cd779832b650c4b2ef9/src/answer_probing_questions). Reproduction instructions need a local correction.

`INFERRED` — Until a versioned adapter fixes these discrepancies and validates parity against a hand-scored sample, the pinned harness should not be used to publish a single canonical BEAM number.

### Limitations

- `CONFIRMED` — Fully synthetic conversations do not establish robustness on messy, multilingual, privacy-sensitive production histories.
- `CONFIRMED` — Two questions per ability per conversation produce sparse within-conversation coverage; ability-level variance and confidence intervals matter.
- `CONFIRMED` — LLM judge/equivalence mapping introduces model and prompt dependence.
- `CONFIRMED` — 10M-token histories impose high indexing, storage, generation, and evaluator cost; not every model/system can accept or process them directly.
- `CONFIRMED` — Current harness discrepancies above threaten reproducibility unless patched and disclosed.
- `N/E` — Hosted-model contamination-free status and equivalence of different tokenizer-based tier labels are not established.

### MemOS mapping

`INFERRED` — Treat BEAM as a **Phase 2 scale and ability stress suite**, not the first correctness gate. Start with the smallest 128K/`100K`-named artifacts and pin actual token counts. Prioritize contradiction, knowledge update, temporal reasoning, event ordering, preference/instruction following, and summarization; these expose failures not fully covered by LoCoMo.

The adapter must:

1. fix `<question>` substitution and preserve fractional scores;
2. report both `tau_norm` and coverage-aware `final_score` for event ordering;
3. version prompts, judge model, parser, and code diff from upstream;
4. hand-score a stratified sample before accepting judge output;
5. add non-official evidence retrieval metrics and preserve raw provenance IDs;
6. report ingestion time, retrieval latency, storage, calls/tokens/cost, and failure rate per tier.

## Mapping to the MemOS benchmark

| MemOS capability | LoCoMo | LongMemEval | BEAM | Required first-party supplement |
|---|---|---|---|---|
| Simple/session recall | categories 2/4 | single-session types | information extraction | bilingual exact-field cases |
| Multi-session/multi-hop | category 1 | multi-session | multi-session/multi-hop | graph/entity gold labels |
| Preference | personal QA | preference type | preference following | explicit current-vs-historical preference |
| Update/conflict | some evolving dialogue | knowledge update | knowledge update + contradiction | deterministic transition labels |
| Temporal/order | category 3 | temporal reasoning | temporal + event ordering | event/observed/valid-time gold |
| Abstention | category 5 | 30 `_abs` questions | abstention | calibrated unsupported/conflict cases |
| Long-term retention | multi-session | S/M | 128K–10M tiers | controlled elapsed-time/noise growth |
| Write precision/noise rejection | `N/E` as direct gold write gate | `N/E` as direct gold write gate | `N/E` as direct gold write gate | required MemOS candidate/noise labels |
| Idempotency/concurrency/failure repair | `N/E` | `N/E` | `N/E` | required system-level fault suite |
| Privacy/delete/poisoning/tenant isolation | `N/E` | `N/E` | `N/E` | required safety/governance suite |

`INFERRED` — No one reviewed external benchmark covers the full production lifecycle. Accuracy on these suites is not evidence of atomic writes, erasure, tenant isolation, poisoning resistance, cost control, or durable correctness.

## Staged execution plan

### Gate 0 — deterministic MemOS unit corpus

Before external scores, validate ingestion order, idempotency, duplicate/update/conflict transitions, event/valid/observed time, delete cascade, tenant boundaries, and poisoned-memory rejection with exact gold state.

### Gate 1 — core external correctness

- LoCoMo full-1,986, while also reporting the 1,540 non-adversarial slice.
- LongMemEval cleaned `S`, pinned to a concrete dataset revision.
- LongMemEval `oracle` as reader upper bound, never mixed into retrieval scores.

### Gate 2 — ability/scale expansion

- BEAM smallest tier only after the patched evaluator passes hand audit.
- LongMemEval `M` after indexing/retrieval instrumentation is stable.

### Gate 3 — stress

- BEAM 500K/1M and then 10M only if earlier tiers identify a real scalability question and cost approval is explicit.

## Fair comparison protocol

All systems must use the same answer-model snapshot, question, final evidence-token budget, and declared evaluator. System-specific extraction/indexing calls remain allowed but must count toward latency and cost.

Required baselines:

1. full history with a predeclared overflow/truncation rule;
2. rolling summary with fixed summary budget/model/prompt;
3. raw-turn vector TopK with hard scope filters;
4. MemOS versioned lifecycle + declared hybrid retrieval/context policy.

Required run manifest:

- benchmark name, subset, file hashes/HF revision, upstream commit and local adapter diff;
- ingestion order and source-ID mapping;
- answer/extraction/embedding/reranker/judge model snapshots and prompts;
- K, candidate limits, filters, fusion/reranking, context token budget;
- random seeds, retry policy, concurrency, machine/runtime versions;
- latency, tokens, calls, cost assumption/date, storage size, errors, and excluded cases.

Required reporting separation:

- write selection/extraction quality where gold exists;
- retrieval recall-any and complete-evidence recall at K;
- final context precision/recall and stale/conflict leakage;
- official end-answer metric plus disclosed secondary judge/human audit;
- abstention, update, temporal, and ability/category breakdowns;
- p50/p95/p99 latency, throughput, failure rate, tokens/cost, and storage growth;
- paired bootstrap confidence intervals or another predeclared paired uncertainty method.

`HYPOTHESIS` — MemOS should be accepted only if its lifecycle gains survive equal answer model and equal final context budget, improve the targeted update/temporal/abstention categories, and do not create unacceptable write latency, storage growth, or stale/conflicting evidence.

## Reproducibility verdict

| Benchmark | What can be reproduced from reviewed artifacts | What remains unavailable or unsafe to assume |
|---|---|---|
| LoCoMo | Public 10-conversation QA data/evaluator and evidence labels | Event-summary evaluator absent at pinned commit; images absent; exact multimodal reproduction `N/E` |
| LongMemEval | Public code, question/history variants, cleaned data, QA/retrieval evaluator | Result equivalence across original/cleaned data or changed judge models `N/E` |
| BEAM | Public data tiers, code, prompts, intended scoring design | Pinned evaluator has audited defects; canonical parity requires a disclosed patch and manual validation |

No result should be entered into [benchmark results](../benchmark/results.md) until the corresponding manifest, raw outputs, and reproducible command exist.
