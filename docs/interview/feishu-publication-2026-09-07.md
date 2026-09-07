# Feishu publication verification — 2026-09-07

Status: **VERIFIED** for the published material below; the approximately 100 strict social-hire
interview target and authenticated BOSS full-JD coverage remain **NOT MET**.

## Scope and evidence

- Personal Agent Wiki space: `7603709140535430108`; all 14 node lookups and parent-directory listings match.
- 13 module workbooks at L0–L4; base and supplements total 25 main questions and 50 follow-ups.
- 34 native Table blocks / 1047 populated cells: table dimensions, row order, headers and cell links
  were checked against the source. No pipe-table Text blocks or unintended empty cell paragraphs remain.
- All table widths were read back after UI correction: 164–274 px per column, approximately
  820 px overall at the inspected desktop viewport. Representative long-text tables were visually
  checked. This is not a claim of pixel-perfect layout at every device width.
- The convert permission was unavailable. Authorized native block creation was used, followed by
  removal of confirmed empty placeholders. The create endpoint ignored supplied column widths;
  only width adjustments used the disclosed browser fallback. Existing content was replaced after
  the new blocks were populated and verified; existing guide, STAR and hub URLs were retained.
- Historical source and evidence-gate pages received dated corrective notes, verified by block readback.
- The local Feishu skill now includes placeholder cleanup, actual-width checks and rate-limit/recovery
  guidance. Its bundled skill validator passed.

Content source snapshot: MemOS `88ec3a08dfa58d27a7e436b7b8e6b641a9859883`, with the subsequent wording clarification
that 32 reviewed JDs are not 32 newly discovered jobs, and with the Waku baseline/current revision
line clarified. Waku fixes are pushed in `dbcda82615506db89f07d3187d64ba4c7032800a`.

## Published pages

| Page | Native tables | Cells |
| --- | ---: | ---: |
| [MemOS｜模块导学与分层面试 Q&A](https://my.feishu.cn/wiki/VpP1wOrvLioPVWkltUjcGpklngi) | 1 | 15 |
| [01｜MemOS 完整模块导学与五层训练](https://my.feishu.cn/wiki/LENSwWtpfivGbIkcq9rcBTH7nQe) | 6 | 173 |
| [01.1｜领域、受理、物化与治理（M01–M04）](https://my.feishu.cn/wiki/J0EkwbEBViMqJdkSEhwcKVG2n2e) | 0 | 0 |
| [01.2｜检索、上下文、适配器与观测（M05–M08）](https://my.feishu.cn/wiki/GVwHwL9g0i5nfmkPCuycuH3Tnec) | 0 | 0 |
| [01.3｜API、Worker、评测与 Waku（M09–M13）](https://my.feishu.cn/wiki/OsGBwFRrlilpGjkaH2WcTvgcnuh) | 1 | 18 |
| [02｜MemOS STAR 题库：25 主问与 50 追问](https://my.feishu.cn/wiki/QsiOwKuboi5Vt0kQW17c9WVjnQd) | 3 | 60 |
| [02.1｜故障与工具合同（Q23／F26–F33）](https://my.feishu.cn/wiki/N3Y1wxHjDi13Fpki2FNcbvSxnqa) | 0 | 0 |
| [02.2｜记忆与未见评测（Q24／F34–F41）](https://my.feishu.cn/wiki/BwpUwVlHeiXKOLkx1MDcDezYnBb) | 0 | 0 |
| [02.3｜岗位与项目取舍（Q25／F42–F50）](https://my.feishu.cn/wiki/F2e0wAHAWioR7OkZBe6czIRRn5e) | 0 | 0 |
| [03｜岗位研究与项目取舍（2026-09-07）](https://my.feishu.cn/wiki/S1J4wuyaKiyAoVkn3YEcXvbhnje) | 3 | 81 |
| [03.1｜牛客来源 001–040](https://my.feishu.cn/wiki/PLuvwNI08i6T5FkMtDjcwLX0nEk) | 5 | 180 |
| [03.2｜牛客来源 041–080](https://my.feishu.cn/wiki/ELnxwyR1liUQygkYmdPcnQfAn1c) | 5 | 180 |
| [03.3｜牛客来源 081–123](https://my.feishu.cn/wiki/T9tYwiMCaixaOdkDDUFc7ctEnnm) | 6 | 196 |
| [03.4｜JD 证据：5 官方全文＋27 BOSS 摘要](https://my.feishu.cn/wiki/GO3Ewdrd7idL8SkCfsZcXzmqn6c) | 4 | 144 |

Machine-readable metadata: [publication audit](feishu-publication-2026-09-07.json).
Local content: [module guide](../../导学-MemOS.md), [STAR base](../../面经-MemOS.md),
[layered workbook](layered-module-workbook.md), [STAR supplement](market-star-qa.md),
[market audit](../research/market-2026-09-07/README.md).

## Verification and remaining work

- Java 25 / checked-in Maven 3.9.16: `./mvnw -B -ntp clean verify` passed (latest run finished
  September 7, 19:41 Asia/Shanghai). Python workspace format/lint and 70 tests passed.
- `scripts/check_interview_material.py` passed, including visible-body categories, author concentration,
  explicit 1–3-year social-hire IDs and full-JD/snippet separation. Markdown link and whitespace checks passed.
- Waku deterministic gate: 630 passed / 73 skipped. New-version live conformance and full Agent task
  utility were NOT RUN. Older 12/12 live conformance remains attached to `b75adf2`.
- Frozen benchmark artifacts were not changed. The negative frozen result and limited DEV observation
  remain separate; no new effectiveness, latency, cost, scale or résumé metrics were added.
- 123 Nowcoder candidates yield 114 public bodies and two previews; seven bodies are unavailable.
  Only 22 readable self-reported social-hire interviews, from seven author groups, are in the strict
  social subset; 16 are from one group. This is not 100 independent social-hire candidates or interviews.
- Five official full JDs and 27 BOSS public snippets were reviewed. Active headcount and authenticated
  BOSS full requirements are unverified. Do not infer market frequencies from this convenience sample.
- Write-failure recovery, partial updates, stable cross-retry identity, end-to-end deadlines,
  real cross-session tasks and independent unseen evaluation remain open within Feature 6.

