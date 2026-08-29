# 04 Embedding、向量 RAG 与召回

## 本章导学

**学习目标**：从向量表示一直推导到可上线的混合检索，理解召回、过滤、融合、重排和上下文选择是五个不同问题。

**前置知识**：基本线性代数、SQL 和第 03 章。不了解矩阵也可以先掌握直觉与实验方法。

**读完产出**：一套 lexical/vector/entity/temporal 独立召回实现、RRF 融合、deadline rerank、离线指标和一次可解释的检索失败分析。

## 1. Embedding 解决什么问题

关键词要求用词重合。Embedding 学习一个映射：

```text
f(text) -> vector in R^d
```

语义相近文本在向量空间中更接近，因此英文记录 “Paul Graham owes me 200 dollars” 可以被中文问题“Paul Graham 欠我多少钱”召回，前提是 embedding 模型具有良好的多语言对齐能力。

Embedding 不是“理解结果”或真值，它只是可用于相似度计算的稠密表示。

## 2. 相似度

常见 cosine similarity：

```text
cos(q, x) = (q · x) / (||q|| ||x||)
```

若向量已归一化，内积与 cosine 排序等价。欧氏距离也可用，但索引配置、模型训练目标和距离度量必须一致。

注意：不同模型、不同索引、不同 query 的 raw score 通常不可横向解释。“0.83 就一定相关”不是普遍规则，阈值必须由固定数据集校准。

## 3. 从文本到可检索单元：Chunking

### 为什么要切块

整段太长会把多个主题混在一个向量中；太短会失去关系和上下文。Memory 往往更适合**原子事实/事件**而不是固定字符切块。

### 常见策略

| 策略 | 优点 | 风险 |
|---|---|---|
| 固定 token | 简单稳定 | 可能切断语义 |
| 段落/标题 | 保留结构 | 文档格式依赖 |
| 语义切块 | 主题更完整 | 模型成本和漂移 |
| 对话 turn | 来源清晰 | 一轮含多个事实 |
| 原子 memory | 检索精确、便于更新 | 需要 LLM 提取和治理 |

建议保存两层：source event 保留原文，atomic memory 做检索投影，并通过 provenance 相连。

### 上下文补偿

向量文本可编码：

```text
[type=semantic][subject=Paul Graham][predicate=debt]
Paul Graham owes the user 200 dollars.
```

但 metadata 过滤与正文 embedding 各司其职；不要把 tenant ID、敏感标签等安全字段只塞进文本，希望模型“理解后过滤”。

## 4. 索引：精确搜索与 ANN

N 条向量暴力扫描是 O(Nd)，规模大时使用 Approximate Nearest Neighbor：

- **HNSW**：图式近邻索引，查询快、召回好，内存与构建成本较高；更新/删除需看实现。
- **IVF**：先聚类到倒排桶，再扫描部分桶；训练、`nlist/nprobe` 影响速度与 recall。
- **Flat**：小规模基线，精确，评估 ANN recall 时必需。

关键参数是质量/延迟/内存的权衡，不能只测查询时间：

```text
ANN Recall@K = exact_topK 与 ann_topK 的相关覆盖
```

## 5. Query 处理

### Query rewrite

把指代和口语改为独立检索问题：

```text
“他后来几点到？” + session context
-> “Elon current arrival time”
```

风险：rewrite 可能错误绑定实体。应保留原 query、rewrite 和来源上下文，在评估中单独测。

### Multi-query

生成多个表达提高 recall，再去重融合。适合复杂问题，但增加 embedding/检索成本，也可能引入 query drift。

### HyDE

先生成假想答案，再 embed 假想文本检索。对知识文档可能有效；对个人事实可能把模型猜测带入 query，必须与原 query 并行而非盲目替代。

## 6. Hybrid Retrieval

### 为什么 vector-only 不够

- 姓名、金额、错误码、日期通常 lexical 更强。
- 语义改写、跨语言 vector 更强。
- “现在/去年/改变前”需要 temporal。
- subject/predicate/类型需要 structured/entity。

### 正确流水线

```text
hard scope/security filters
  ├─ vector candidates
  ├─ lexical candidates
  ├─ entity/metadata candidates
  └─ temporal candidates
          -> fusion -> rerank -> truth policy -> budget selection
```

每一路独立召回，不能让 vector TopK 先裁掉 lexical/entity 的候选。

### Reciprocal Rank Fusion

对文档 d：

```text
RRF(d) = sum_r 1 / (k + rank_r(d))
```

优点：不需要直接比较 BM25、cosine、图分数等不同量纲；对异常 raw score 较稳。缺点：丢失分数幅度，需要调 `k`，不能表达所有业务优先级。

### Weighted fusion

```text
score = wv*norm(vector) + wl*norm(lexical) + wt*temporal + we*entity
```

必须在 dev set 校准 normalization/weights，并冻结后测试。不要凭直觉写 `0.7*vector+0.3*keyword` 后当成科学结论。

## 7. Reranking

### Bi-encoder vs Cross-encoder

- Bi-encoder：query/doc 分别编码，可预计算文档向量，适合高召回候选生成。
- Cross-encoder：把 query-doc 一起输入，交互更细，精度高但每对都推理，适合对 TopN 重排。

LLM reranker 能处理时间、指代和复杂约束，但成本高、不稳定，且不能拥有 ACL/真值的决定权。

生产要求：

- 严格 deadline；
- 输入 ID 集合与输出 permutation 校验；
- 超时/异常 deterministic fallback；
- 记录 rerank 前后排名和模型版本；
- 敏感内容的 provider 传输符合 policy。

## 8. 召回、排序、上下文的评价指标

### 候选召回

- `Recall@K`：至少一个所需证据进入 TopK 的比例。
- `Complete Recall@K`：多证据问题所需证据是否全部进入 TopK。
- `MRR`：第一个 relevant 的倒数排名，适合 first-hit 任务。
- `nDCG@K`：有 graded relevance 时评价排序质量。

### Memory 特有指标

- `Stale@K`：当前问题的上下文中出现不兼容的过时事实比例。
- `Conflict leakage`：未解决冲突被隐藏成单一答案的比例。
- `Scope leakage`：越权候选进入结果的比例，目标必须为 0。
- `Context precision/recall`：最终注入而非初始 TopK 的证据质量。
- `Abstention F1`：证据不足时是否正确拒答。

### 写入和管理指标

- write precision/recall/F1；
- extraction 字段准确率；
- dedup cluster B3 或 pairwise F1；
- transition macro-F1：ignore/create/reinforce/supersede/coexist/conflict/invalidate；
- current/historical/change-time accuracy。

只有把这些层拆开，才能知道错误来自哪里。

## 9. 一次错误答案的定位树

```text
事实是否成为 candidate？
  否 -> write gate/extraction 错
  是 -> authoritative current state 正确？
          否 -> dedup/conflict/temporal 错
          是 -> 是否进入各路候选？
                  否 -> query/embedding/index/filter 错
                  是 -> 是否进入最终 context？
                          否 -> fusion/rerank/budget/policy 错
                          是 -> LLM 是否正确使用？
                                  否 -> prompt/reasoning/answer 错
```

## 10. Embedding 工程细节

- 固定 `model_id + revision + dimension + distance`，写入 projection metadata。
- 内容规范化规则必须版本化；避免写入和查询使用不同前处理。
- 批量 embedding、重试、rate limit、超时和 provider 成本要可观测。
- 新模型先 shadow build 新 generation，离线评估后原子切 head。
- 删除要覆盖所有 generation、缓存和批处理队列，防止旧任务复活。
- 多语言基准必须包含真实中英改写，不能只相信模型宣传。

## 11. 最小伪代码

```python
def retrieve(query, scope, k=8):
    assert authorized(scope)
    q = understand(query)

    vector = vector_search(embed(q.semantic), scope=scope, limit=40)
    lexical = fts_search(q.keywords, scope=scope, limit=40)
    entity = entity_search(q.entities, q.predicates, scope=scope, limit=40)
    temporal = temporal_search(q.time_intent, scope=scope, limit=40)

    fused = rrf([vector, lexical, entity, temporal])
    eligible = apply_truth_and_sensitivity_policy(fused, q)
    ranked = rerank_with_deadline(q, eligible[:50], fallback=eligible)
    return build_context(ranked, token_budget=1200, top_k=k)
```

## 12. Embedding 流水线的完整契约

一次 embedding 不是只有模型名：

```json
{
  "provider": "...",
  "model_id": "...",
  "revision": "...",
  "dimension": 1536,
  "distance": "cosine",
  "input_type": "document",
  "normalization": "l2",
  "text_template_version": "memory-embed.v2",
  "projection_generation": 7
}
```

一些模型区分 query/document input type或要求不同前缀；漏用会造成分布错配。内容模板也影响表示：只 embed `200 dollars` 与 embed `subject=Paul Graham; relation=owes; value=200 USD` 的可检索性不同。

写入与查询必须使用兼容 spec。数据库应拒绝把不同维度写进同一 vector column；应用还要防止维度相同但模型不同的“静默混池”。

## 13. pgvector 实现骨架

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE memory_projection (
  memory_version_id uuid NOT NULL,
  tenant_id text NOT NULL,
  user_id text NOT NULL,
  generation bigint NOT NULL,
  embedding_model text NOT NULL,
  content text NOT NULL,
  embedding vector(1536) NOT NULL,
  truth_state text NOT NULL,
  valid_from timestamptz,
  valid_to timestamptz,
  PRIMARY KEY (memory_version_id, generation)
);

CREATE INDEX memory_projection_hnsw
ON memory_projection USING hnsw (embedding vector_cosine_ops);
```

概念查询：

```sql
SELECT memory_version_id,
       1 - (embedding <=> :query_embedding) AS cosine_similarity
FROM memory_projection
WHERE tenant_id = :tenant
  AND user_id = :user
  AND generation = :active_generation
  AND truth_state = 'CURRENT'
  AND (valid_from IS NULL OR valid_from <= :as_of)
  AND (valid_to IS NULL OR :as_of < valid_to)
ORDER BY embedding <=> :query_embedding
LIMIT :k;
```

真实系统需检查执行计划：metadata filter、ANN index 和数据分布可能让 planner 选择不同路径。强过滤下 ANN 得到的候选不足，应测试 iterative scan、分区、partial index 或先缩小 scope 的结构设计；不要假设“有 HNSW index 就一定快且完整”。

## 14. ANN 参数如何调

### HNSW

- 构建参数影响图连接度、索引时间、内存和 recall；
- 查询参数控制搜索宽度，越大通常 recall 越高、延迟越大；
- delete/update churn 可能导致膨胀，需测 vacuum/rebuild 行为。

### IVF

- `lists` 决定聚类桶数量；
- `probes` 决定查询访问多少桶；
- 数据分布变化后原聚类可能退化；
- 建索引前需要足够训练数据。

调参流程：以 exact flat TopK 为 gold，扫描参数并画 `ANN Recall@K vs p95 latency vs memory`。只报告 ANN 自身 recall，不能把它与业务 evidence Recall@K 混为一谈。

## 15. RRF 手算示例

假设 `k=60`：

| 文档 | vector rank | lexical rank | temporal rank |
|---|---:|---:|---:|
| current-9pm | 2 | 2 | 1 |
| old-7pm | 1 | 1 | 不合格 |
| dinner-episode | 3 | 无 | 3 |

如果 temporal/truth 是硬资格条件，old-7pm 应在 fusion 前或 policy 阶段排除，而不是靠一项缺失 rank“自然降分”。对 current-9pm：

```text
1/(60+2) + 1/(60+2) + 1/(60+1)
```

RRF 适合组合召回证据，不负责业务否决。硬约束优先级始终高于相关性融合。

## 16. Hard Negative 设计

随机负样本太容易，不能验证生产检索。Memory 特有 hard negatives：

- 同 subject/predicate 的 superseded 旧值；
- 同名不同实体；
- 同事实不同租户；
- 当前问题与历史事实；
- 词汇高度重合但否定极性相反；
- 同一日期但不同事件；
- 模型常识相关、却不是用户证据的公众人物事实。

把 negative 类型写进数据集标签，结果按切片报告。整体 Recall@K 很高可能掩盖所有 update case 都失败。

## 17. 离线与在线评估闭环

### 离线

固定 query、gold evidence、scope、time intent，输出每个 channel 的 candidates、rank、raw score、filter reason、最终 context。dev 调 K/threshold/RRF/reranker，test 冻结。

### 在线

监控 empty-result、fallback、latency、projection lag、用户纠正、answer evidence；使用脱敏反馈形成难例。在线点击/接受不一定代表事实正确，不能直接当唯一 reward。

### Shadow migration

升级 embedding/reranker 时并行跑 old/new generation，不影响线上答案；比较 per-slice recall、stale leakage、latency、cost。通过 promotion gate 后原子切换，保留回滚 generation。

## 18. 动手练习与验收

### 练习

用 30 条中英 facts 构造：5 条 exact、5 条 paraphrase、5 条跨语言、5 条 update、5 条同名实体、5 条 abstention。分别跑 FTS、vector、hybrid，输出 per-slice Recall@5 和 Stale@5。

### 本章验收

- 能解释 cosine、ANN Recall 与业务 Recall 的区别。
- 能给 embedding spec 做版本化和 generation 切换。
- 能手算一次 RRF，并说明硬过滤不应交给 RRF。
- 能构造 Memory 特有 hard negatives。
- 能从 query 到 context 逐层定位一次召回失败。
