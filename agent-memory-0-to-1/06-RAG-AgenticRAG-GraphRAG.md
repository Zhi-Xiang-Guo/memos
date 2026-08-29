# 06 RAG、Agentic RAG 与 Graph RAG

## 本章导学

**学习目标**：区分固定检索、Agent 驱动检索和图增强检索，能够按问题结构而非热点名词选择路由，并能解释每种方案的失败链和成本。

**前置知识**：第 04 章的 dense/lexical/hybrid retrieval，以及基本 Agent loop 概念。

**读完产出**：一个 query router、复杂问题分解策略、Graph RAG 数据生成流程和针对 single-hop/multi-hop/global/temporal 的分层 benchmark。

## 1. RAG 的本质

Retrieval-Augmented Generation：在生成前，从外部语料中找相关证据并放入上下文。

```text
query -> retrieve -> context -> LLM -> answer
```

它主要解决模型参数知识陈旧、私有知识不可见和回答需要证据的问题。RAG 不自动提供跨会话记忆形成、事实更新、权限、删除或时间状态。

## 2. 经典 Vector RAG

### 流程

```text
ingest documents
-> chunk
-> embed
-> vector index

query
-> embed
-> ANN TopK
-> optional rerank
-> prompt
-> answer
```

### 优点

- 结构简单、生态成熟；
- 对语义改写和跨语言有效；
- 大量非结构化文档可快速建立 baseline。

### 局限

- 一次 query、一次检索容易遗漏复杂问题的多个证据；
- TopK 对实体、精确值和时间弱；
- 文档 chunk 与原子事实更新粒度不同；
- 相似片段不等于当前、可信、授权。

## 3. Agentic RAG

### 解决什么

复杂问题需要规划、多次检索、查询改写、工具选择和证据自检。Agent 不只消费一个固定 retriever，而是在 loop 中决定下一步。

```text
understand question
-> choose source/tool
-> retrieve
-> inspect evidence
-> if insufficient: rewrite/decompose/retrieve again
-> synthesize with citations
```

### 常见能力

- retrieval gate / router；
- query decomposition；
- multi-query / iterative retrieval；
- SQL、web、vector、graph 工具选择；
- relevance/groundedness check；
- retry、fallback、停止条件。

### 风险

- 延迟、token、工具调用和不可预测路径增加；
- agent 可能 query drift、循环或选择错误数据源；
- 工具结果是 untrusted data；
- 每步都要 trace，才能区分规划错和检索错。

### 何时不需要

问题结构稳定、一次 hybrid retrieval 已满足；低延迟在线服务；没有足够评估数据证明迭代带来收益。

## 4. Graph RAG

Graph RAG 不是单一固定算法，至少包含两种常见路线：

### 4.1 实体知识图谱型

从文本提取实体和关系，构建图；查询时做实体链接、邻居/路径遍历，再取相关原文或边进入上下文。

适合：多跳关系、人物/组织网络、依赖链、时序关系。

### 4.2 社区摘要/全局主题型

对图做社区发现和多层摘要，用于“整个语料有哪些主题/关系”这类全局问题。它不等于简单邻居遍历。

### 流程

```text
documents/events
-> entity & relation extraction
-> entity resolution
-> graph projection + provenance
-> query entity linking / community routing
-> traversal/subgraph retrieval
-> text/vector retrieval over nodes/edges/evidence
-> answer
```

### 图与向量不是二选一

节点和边的文本可以 embedding；query 可先向量找到入口节点，再 graph expand。Graph 回答结构关系，vector 回答语义近似，二者常结合。

不要把“graph embedding”误用于“给图节点文本做 embedding”。严格的 graph embedding 指 node2vec/GNN 等编码拓扑结构；若没有证据，不应声称某产品使用了它。

## 5. Temporal Graph RAG

给关系边加 `valid_from/valid_to` 与 provenance：

```text
(Elon)-[ARRIVES_AT {valid:[t0,t1)}]->(19:00)
(Elon)-[ARRIVES_AT {valid:[t1,inf)}]->(21:00)
```

查询先解析时间意图：

- “现在几点到” -> intersect(now) + CURRENT；
- “原来几点到” -> historical interval；
- “什么时候改的” -> transition/change point。

若图搜索只按语义返回边，却不应用 validity，仍会把旧事实交给 LLM。

## 6. 为什么 Hermes 和 Waku 可能一个复杂 RAG 都不用

这里必须区分“架构不需要”和“产品没有能力”。小型个人 Agent 的长期事实可能只有几十/几百条：

- Hermes 可用单个 `MEMORY.md` 全量注入；
- Waku 的教学实现以 SQLite + FTS5 为权威/查询，并通过 retrieval gate 按需搜索；
- Waku 可选接入 Supabase/mem0/Zep/LangMem，但核心不依赖它们。

原因不是 RAG 无效，而是 YAGNI：

- 数据小，全量或 FTS recall 更稳定；
- 精确人物/日程关键词占主导；
- 避免 embedding 成本、外部服务、异步 freshness 和供应商锁定；
- 教学目标要求机制可读。

当 paraphrase、中文跨语言或规模测试暴露缺口，再增加 vector；当多跳关系测试证明收益，再增加 graph。

## 7. 四种方案对比

| 维度 | Keyword/FTS | Vector RAG | Agentic RAG | Graph RAG |
|---|---|---|---|---|
| 主要强项 | 精确词、ID、名字 | 语义、改写、跨语言 | 复杂任务、多源迭代 | 关系、多跳、全局结构 |
| 写入成本 | 低 | embedding | 取决于索引 | 实体/关系提取高 |
| 查询延迟 | 低 | 低-中 | 中-高、不稳定 | 中-高 |
| 可解释性 | 高 | 中 | 路径需 trace | 路径高，抽取误差复杂 |
| 真值演化 | 不自动 | 不自动 | 不自动 | 需 temporal/lifecycle |
| 适用规模 | 小-大 | 中-超大 | 复杂查询 | 高关系密度 |

## 8. 选型的第一性问题

先问而不是先选技术：

1. 用户问题是精确查找、语义改写、关系多跳还是全局综合？
2. 所需证据是一条、几条还是未知数量？
3. 事实是否经常变化，是否需要 as-of？
4. 允许几次模型/工具调用，p95 延迟和成本是多少？
5. 能否建立 gold evidence 来验证新增复杂度？
6. 权限和删除是否能在候选阶段可靠执行？

## 9. 推荐演进

```text
Step 0: full context / text
Step 1: FTS baseline
Step 2: vector-only baseline
Step 3: independent hybrid + RRF
Step 4: reranker under deadline
Step 5: agentic iteration for明确失败类型
Step 6: graph projection for已证明的多跳用例
```

每一步都要用相同数据、相同 answer model、相同 evidence budget 对照；若没有提升，就保留更简单的方案。

## 10. Query Taxonomy 与路由器

先给问题分类：

| 类型 | 示例 | 推荐路径 |
|---|---|---|
| self-contained | 2+2 是多少 | 不检索 |
| exact/entity | Paul Graham 欠多少 | lexical + structured + vector |
| semantic | 我之前说过喜欢什么工作方式 | vector + lexical |
| temporal current | Elon 现在几点到 | truth/temporal + hybrid |
| temporal historical | 原来几点到、何时改 | transitions + temporal |
| multi-hop | 与带辣椒油的人讨论创业的是谁 | graph/iterative retrieval |
| global synthesis | 整个项目的反复失败模式 | hierarchical/Graph RAG/reflect |
| transactional | 订单是否退款 | 调领域 API，不从 memory 猜 |

```python
def route(question, session_state):
    intent = classify_intent(question, session_state)
    if intent.self_contained:
        return NoRetrieval()
    if intent.transactional:
        return DomainTool(intent.system_of_record)
    if intent.multi_hop or intent.global_synthesis:
        return AgenticOrGraphPlan(intent)
    return HybridMemoryRetrieval(intent)
```

路由器输出不仅是 store 名称，还应包含 entity、time intent、required evidence count、最大迭代和停止条件。

## 11. Agentic RAG 的控制平面

一个健壮 loop 需要：

```text
PLAN -> RETRIEVE -> ASSESS
                 -> enough: SYNTHESIZE
                 -> missing subquestion: REWRITE/DECOMPOSE
                 -> wrong source: ROUTE
                 -> budget exhausted: ABSTAIN/PARTIAL
```

关键 guardrails：

- 最大检索轮数、总 token、总 wall-clock 和工具调用预算；
- query 去重与循环检测；
- 每次必须声明还缺什么证据；
- 工具来源、权限和时间范围不可由网页内容改写；
- synthesis 只能引用已记录 evidence ID；
- 证据不足时停止，而不是继续搜索直到凑出答案。

Agentic RAG 的收益要扣除更多调用和更高方差。报告 pass rate、平均/尾部迭代、工具失败、成本和 answer quality，而不只挑成功轨迹展示。

## 12. Graph 构建的真正流水线

```text
source chunks/episodes
-> entity mention extraction
-> canonicalization/entity resolution
-> relation + qualifier + time extraction
-> provenance validation
-> node/edge upsert into a projection generation
-> contradiction/validity handling
-> graph quality checks
```

### Entity resolution

`Alex`、`Alex Chen`、`陈 Alex` 是否同一人，需要 scope、上下文、稳定 ID 和置信度。错误 merge 比漏连更危险，因为会把两个主体的所有关系混在一起。系统需支持 merge proposal、split/undo 和 provenance 回溯。

### Edge Contract

```json
{
  "subject": "person:jensen",
  "predicate": "discussed_project_with",
  "object": "person:lisa",
  "qualifiers": {"project": "robotics-startup"},
  "valid_from": "2026-08-29T20:00:00Z",
  "valid_to": null,
  "source_memory_version_ids": ["mem_e6"],
  "confidence": 0.91,
  "projection_generation": 4
}
```

图边没有 provenance 就是另一个难以审计的模型输出。

## 13. Local、Global 与 Temporal Graph RAG

- **Local**：从 query-linked entity 向外扩展有限跳数，适合具体关系问题。
- **Global**：社区发现/层级摘要聚合整个图，适合主题与全局模式。
- **Temporal**：遍历时同时过滤 edge validity 和 requested time。

它们的 gold 不同：local 看路径证据，global 看主题覆盖与摘要支持，temporal 看 current/as-of/change。把三者混成一个平均分会失去诊断意义。

## 14. RAG 失败归因

| 故障 | 典型症状 | 需要修什么 |
|---|---|---|
| routing miss | 本应查订单 API 却查 memory | intent/router |
| decomposition miss | 多跳问题只搜一遍 | planner |
| entity-link miss | 图中有节点却进错入口 | entity linker |
| graph construction miss | source 有关系、图中无边 | extractor/projection |
| traversal miss | hop/depth/filter 错 | graph query |
| retrieval miss | 正确 source 未入候选 | indexes/query |
| evidence assessment miss | 已足够却继续循环 | critic/stop rule |
| synthesis miss | 正确路径却答错 | answer prompt/model |

## 15. 安全边界

Agentic RAG 会把更多 query 发给更多系统，扩大数据传输面。router 先执行权限；工具参数由可信 control plane 约束；网页或 retrieved memory 不能要求 Agent 读取更高 scope。图遍历也要在每个 node/edge 执行 scope，而不是遍历后再过滤。

## 16. 动手练习与验收

### 练习

为晚宴问题集写 router gold：每题的 intent、retrieval channels、时间语义、所需 evidence 数、最大轮数和应否 abstain。再加入一个订单状态问题，要求路由到领域 API。

### 本章验收

- 能解释 Vector/Agentic/Graph RAG 分别解决什么，不解决什么。
- 能给 Agentic loop 定义预算和停止条件。
- 能画出从 source 到 graph edge 的生成与溯源链。
- 能区分 local/global/temporal Graph RAG。
- 能把错误定位到 routing、construction、traversal、retrieval 或 synthesis。
