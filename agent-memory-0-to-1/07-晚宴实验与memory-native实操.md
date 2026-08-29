# 07 晚宴实验与 `memory-native` 实操

## 本章导学

**学习目标**：学会设计一个能反驳自身结论的 Memory 实验，而不是产品演示；掌握 control、隔离、readiness、等预算、重复运行和原始 artifact。

**前置知识**：第 03-06 章；准备 Python 环境与各 hosted 后端凭证时需理解费用和数据写入风险。

**读完产出**：一个可重复的晚宴 benchmark harness、统一 adapter 输出、run manifest、分层结果表和失败样本报告。

## 1. 实验目的

不要问“能不能存进去”，而要同时检验：

1. 写入后实际保存了什么；
2. exact、paraphrase、中文问题能否召回；
3. 新事实与旧事实冲突时怎样演化；
4. plain search 是否会返回已失效事实；
5. 系统何时真正 queryable；
6. 不提供 memory 的 control 能回答多少，排除模型预训练知识泄漏。

## 2. 统一晚宴数据

建议给所有后端使用相同 `run_id/user_id` 隔离的事件：

```text
E1  18:30 Jensen Huang arrived at dinner wearing a black leather jacket.
E2  18:40 Jensen brought a bottle of chili oil.
E3  18:50 Paul Graham said he owes me 200 dollars.
E4  19:00 Elon said he would arrive at 7 PM.
E5  19:20 Actually, Elon changed his arrival time to 9 PM.
E6  20:00 Lisa and Jensen discussed a robotics startup in Lisbon.
```

噪声事件也要加入，例如天气寒暄、通用知识、重复表述，否则无法测 write precision 和污染抵抗。

## 3. 问题集

| 类型 | 问题 | Gold evidence | 期望 |
|---|---|---|---|
| exact | Who brought chili oil? | E2 | Jensen |
| paraphrase | What condiment did Jensen carry? | E2 | chili oil |
| 中文 | Paul Graham 欠我多少钱？ | E3 | 200 dollars |
| current update | What time will Elon arrive? | E5 | 9 PM，不注入旧值 |
| historical | What time was Elon originally going to arrive? | E4+E5 | 7 PM |
| change time | When did Elon's plan change? | E5 event time | 19:20 |
| multi-hop | Who discussed the startup with the person who brought chili oil? | E2+E6 | Lisa |
| abstention | What dessert did Jensen eat? | none | 不知道 |
| control trap | What does Jensen usually wear? | none | 不应把预训练常识当实验记忆 |

## 4. 五个参赛者

### A. SQLite + FTS5

预期：名字和 exact 强；跨语言/大幅改写弱；更新语义若 manager 只 append，会同时存在 7 点/9 点。

要记录：原始 rows、FTS rank、重复 rows、status 是否存在。

### B. Supabase PostgreSQL + pgvector

预期：paraphrase/中文更强；若只做向量表，两条到达时间会作为近邻共同存在，无法自动知道谁当前。

要记录：embedding model、dimension、distance、raw score、metadata filter、projection freshness。

### C. mem0

预期：自动改写/提取 memories，并可能执行 add/update/delete/noop。必须读取完整 lifecycle，而不只看 search 列表。

要记录：said vs kept、行数变化、推断新增内容、superseded/replaced_by、add 到 queryable 时间。

### D. LangMem

预期：作为 manager 读取整段对话后形成较少、整理过的 memories；实际持久性由你提供的 store 决定。

要记录：输入 6 句输出几条、冲突是否写前解决、使用的 store 是否持久化。

### E. Zep / Graphiti

预期：实体和关系边、validity interval、多跳/历史能力。普通搜索仍需读取无效/有效状态。

要记录：episode accepted/processed/queryable、nodes/edges、valid_at/invalid_at、ontology 对抽取的影响。

### F. Control：无记忆

完全不告诉模型晚宴事实，只问相同问题。它答对的 probe 不能计为 memory 系统能力。尤其公众人物问题很容易被预训练知识碰巧答中。

## 5. 公平性规则

- 相同事件、顺序、session 边界和语言。
- 相同 answer model/prompt；存储内部必需模型调用单独计成本。
- 相同 evidence token budget，而不仅是相同 TopK。
- 每个 store 清理或使用唯一 namespace，防止旧数据污染。
- hosted ingestion 必须等待“可查询稳定”，不能只等 API 返回。
- 固定 SDK/version/commit；记录 hosted 算法无法冻结的限制。
- 模型相关配置至少重复 3 次，保留所有失败/排除样本。
- 先冻结 dev 配置再跑 test，禁止看到 test 后调参。

## 6. `waku-agent/examples/memory-native` 本地实操

本地目录：`/Users/guozhixiang/Agent/waku-agent/examples/memory-native/`

它的设计价值是四个脚本分别按产品原生用法运行，不经过 Waku 的统一 `FactStore`，避免抽象层抹平产品差异。

```bash
cd /Users/guozhixiang/Agent/waku-agent
uv pip install -e '.[arena]'
python examples/memory-native/langmem_native.py
python examples/memory-native/mem0_native.py
python examples/memory-native/zep_native.py
python examples/memory-native/supabase_native.py
```

运行 hosted 后端会产生远程数据与费用；使用独立测试账号/namespace，检查 `.env`，不要在录屏或日志中泄漏密钥。

## 7. 本地固定版本实验已经揭示的现象

下列是本地说明文档对 2026-08-12/15、固定 SDK 版本的实测，不应外推为所有当前版本的永久结论：

- LangMem：三句（其中 May 改 June）在写入前整理为两条 memory，冲突先被合并。
- mem0 `2.0.17`：旧事实可标记 superseded，但 plain search 仍可能返回，甚至与当前事实分数极接近；列表接口未必直接展示完整 lifecycle。
- Zep Cloud `3.27.0`：旧事实边被标记在某时刻失效，仍可作为历史查询；plain graph search 也可能返回旧边。
- Supabase pgvector：中英文/改写召回可以很好，但两个日期会永久作为相近向量并存，除非应用层管理 lifecycle。
- hosted store 的“写入 API 返回”“后台 processed”“图/索引可查询”可能不是同一时刻。

## 8. 重点案例拆解

### 8.1 黄仁勋与辣椒油

只问“黄仁勋通常穿什么”是坏 probe，因为基础模型可能知道皮衣。辣椒油作为人为设定的情节更能测试记忆。更严谨的方法是使用虚构人物，彻底消除预训练知识。

### 8.2 中文问 Paul Graham 欠款

这是跨语言 embedding 与 exact entity 的组合测试：

- FTS 可能命中 `Paul Graham`，但“欠/owes”语言不同；
- 多语言 vector 可语义命中；
- hybrid 应利用姓名 lexical + debt semantic；
- 金额最终答案必须来自 E3，而非模型猜测。

### 8.3 Elon 7 点改 9 点

这是 memory manager 而非 retriever 的测试：

- vector-only：两条并存，Top1 由噪声决定；
- row manager：旧 row superseded，但检索消费端必须过滤；
- temporal graph：旧 edge 有 `valid_to`，新 edge current；
- current 问题不能注入 7 点；historical 问题需要两条和 transition。

## 9. 结果记录模板

```yaml
run_id: dinner-2026-08-29-001
system: sqlite|pgvector|mem0|langmem|zep|control
version:
models:
  extractor:
  embedding:
  reranker:
  answer:
seed_namespace:
write:
  accepted_at:
  queryable_at:
  said_count:
  kept_count:
questions:
  - id: current-elon
    retrieved_ids: []
    states: []
    scores: []
    context_tokens:
    answer:
    correct:
    stale_leakage:
latency_ms:
cost:
notes:
```

## 10. 不能得出的结论

- 一次 run 不能证明稳定质量。
- hosted 默认模式不能代表整个产品全部功能。
- control 未通过不能证明所有正确答案都来自 memory，还需 evidence tracing。
- search 返回旧事实不代表 store 没管理 lifecycle，也可能是调用方没读状态。
- “processed=true”不一定代表所有派生索引已可查。
- 不同系统返回数量不同，不能以固定 memory count 作为 readiness 条件。

## 11. 从实验变成项目亮点

亮点不是“接了五个 SDK”，而是：

> 我设计了包含无记忆 control、跨语言、更新、历史、多跳和拒答的等预算测试，发现相似检索会把已失效事实重新带回上下文，并把问题定位为 lifecycle 与 retrieval contract 的断层；随后用版本化权威状态、预排序真值过滤和 Stale@K 指标修复并验证。

## 12. 实验前置清单

### 环境隔离

- 每个 run 使用唯一 `tenant/user/namespace/project`；
- 不复用个人真实 memory；
- hosted 项目 ontology/config 纳入 manifest；
- 清理动作先 list/dry-run，确认测试分区后执行；
- `.env`、API key、raw PII 不进入 artifact 或录屏；
- 固定本地代码 commit、SDK lockfile 和数据 hash。

### 数据隔离

事件 ID 确定且可重放；所有参赛者输入完全相同。产品若自动改写/合并，记录差异而不强制它“必须存六条”，否则会关闭产品核心能力。

### 模型隔离

answer model 统一。memory 产品内部不可替换模型若无法冻结，标记 hosted drift；成本单独记录，不假装系统完全等价。

## 13. Gold Dataset Schema

```json
{
  "scenario_id": "dinner-update-001",
  "events": [
    {
      "id": "E4",
      "session_id": "S1",
      "actor": "DIRECT_USER",
      "occurred_at": "2026-08-29T19:00:00Z",
      "text": "Elon will arrive at 7 PM."
    }
  ],
  "gold_memories": [
    {
      "subject": "Elon",
      "predicate": "arrival_time",
      "value": "19:00",
      "state_after_all_events": "HISTORICAL",
      "source_ids": ["E4"]
    }
  ],
  "questions": [
    {
      "id": "Q-current",
      "language": "en",
      "text": "What time will Elon arrive?",
      "time_intent": "CURRENT",
      "required_evidence_ids": ["E5"],
      "forbidden_evidence_ids": ["E4"],
      "answer": "9 PM",
      "should_abstain": false
    }
  ]
}
```

`forbidden_evidence_ids` 让 Stale@K 可机械计算；多跳题的 `required_evidence_ids` 必须全部出现才算 complete recall。

## 14. Readiness：什么时候才算写完

不要固定 sleep 10 秒。定义每个 adapter 的 readiness contract：

```python
def wait_until_queryable(store, sentinel_query, timeout_s):
    deadline = monotonic() + timeout_s
    last_fingerprint = None
    stable_reads = 0
    while monotonic() < deadline:
        snapshot = normalize(store.inspect())
        fingerprint = hash_canonical(snapshot)
        hits = store.search(sentinel_query)
        stable_reads = stable_reads + 1 if fingerprint == last_fingerprint else 0
        if hits and stable_reads >= 3:
            return Queryable(snapshot)
        last_fingerprint = fingerprint
        sleep(backoff())
    return TimedOut(last_snapshot=snapshot)
```

稳定 count 只是不得已的近似，因为后台可能在不改变数量时更新 lifecycle/edges。优先使用产品正式 readiness/watermark；没有时公开说明 heuristic，并保存时间序列。

## 15. Adapter 的最小统一输出

统一的是**观测格式**，不是强迫所有产品使用相同写入语义：

```python
class Observation:
    said_events: list[Event]
    stored_items: list[StoredItem]
    lifecycle: list[LifecycleState]
    graph_nodes: list[Node]
    graph_edges: list[Edge]
    search_hits: list[Hit]
    readiness_timeline: list[Sample]
    provider_calls: int
    token_usage: TokenUsage | None
    latency_ms: dict[str, float]
```

`stored_items` 保留 provider raw ID 和 normalized view；raw response 脱敏后作为 artifact，避免 adapter 丢掉产品特有字段。

## 16. 评分顺序

1. **Write**：gold durable facts 是否形成，噪声是否拒绝。
2. **Manage**：Elon 旧值是否 retire、历史是否保留。
3. **Retrieve**：required/forbidden evidence 的 rank。
4. **Context**：最终注入内容是否当前、足够、预算内。
5. **Answer**：正确、拒答、evidence faithful。
6. **System**：freshness、latency、tokens、cost、failure。

先评分证据再调用 answer model，可避免把模型猜对当检索成功。No-memory control 与“只给 gold evidence 的 oracle”共同给出下限和上限。

## 17. 报告表建议

| 系统 | Write F1 | Current acc | Recall@5 | Complete Recall@5 | Stale@5 | Abstention F1 | p95 queryable | Answer tokens | 备注 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| control | N/A | N/A | N/A | N/A | N/A | ... | N/A | ... | 无 memory |
| SQLite FTS |  |  |  |  |  |  |  |  |  |
| pgvector |  |  |  |  |  |  |  |  | vector-only |
| mem0 |  |  |  |  |  |  |  |  | 固定产品版本 |
| LangMem |  |  |  |  |  |  |  |  | 声明 store |
| Zep |  |  |  |  |  |  |  |  | 声明 ontology |

空格表示未运行，报告中写 `NOT RUN`，不能预填数字。

## 18. 动手练习与验收

### 练习

先只实现 `control + SQLite FTS + vector-only` 三个 baseline，跑 3 次。解释每个失败属于 write、manage、retrieve、context 还是 answer。然后再加 hosted 产品，避免一开始被 SDK 和账号问题淹没。

### 本章验收

- 每个 run 可由 manifest 和固定数据重放。
- control 能排除公众人物预训练知识。
- readiness 不是盲目 sleep 或固定 row count。
- required/forbidden evidence 可机械评分。
- 产品特有 lifecycle/graph 字段没有被统一 adapter 抹掉。
- 报告包含失败、超时、NOT RUN、版本和限制。
