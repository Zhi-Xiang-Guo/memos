# 05 记忆维护：版本、时序、Retire、Reflect 与 Dreaming

## 本章导学

**学习目标**：把“更新记忆”从覆盖一行提升为可审计的时序状态机，理解 late event、冲突、退休、删除、反思和全局整合的不同语义。

**前置知识**：关系数据库事务、时间区间和第 01-04 章。

**读完产出**：一套 lineage/version/transition 模型、状态不变量、current/as-of/diff 查询、并发修正和 Dreaming promotion 流程。

## 1. 为什么维护比存储更难

记忆质量会随时间自然下降：重复积累、事实变化、错误提取、来源冲突、模型/embedding 升级、用户删除请求。没有 manage path 的系统，即使搜索算法不变，数据也会越来越脏。

## 2. DECIDE：写入决策不是二选一

面对新候选，至少需要：

```text
IGNORE      不值得长期保存
CREATE      新事实
REINFORCE   同一事实的新证据
SUPERSEDE   新事实替换旧事实的当前地位
COEXIST     同时成立的集合值/不同时段事实
CONFLICT    证据冲突且无法自动裁决
INVALIDATE  事实被证明错误或不应再用
REVIEW      需要人工/更强流程
```

判断不能只靠 embedding similarity。需要 scope、subject、predicate、value、cardinality、source trust 与 temporal overlap。

## 3. Retire 和 Delete 的区别

### Retire

事实不再是当前，但历史上可能成立，保留内容与溯源：

```text
Elon arrives 19:00  [valid_from=t0, valid_to=t1, HISTORICAL]
Elon arrives 21:00  [valid_from=t1, valid_to=inf, CURRENT]
```

支持：审计、历史问答、解释变化、纠错回滚。

### Delete

内容因用户请求、隐私、合规或保留策略被物理/加密擦除；派生索引、缓存、任务和备份策略都要覆盖。可能仅保留不含内容的 tombstone，防止重放复活。

### 为什么“1000 星到 1300 星”应该 retire

项目星数从 1000 增长到 1300：1000 曾经真实，不能说它“错误”；当前问题回答 1300，历史趋势问题需要两条。直接覆盖/删除会丢失事实演化。

## 4. 时间不是一个 `created_at`

至少区分：

- `observed_at`：系统何时看到证据。
- `event_time`：描述的事件何时发生。
- `valid_from/valid_to`：事实在现实中何时有效。
- `transaction_time`：系统何时把版本/转移写入权威库。
- `last_accessed_at`：检索使用统计，不能当真值时间。

### Bitemporal 思维

“事实何时为真”与“系统何时知道”是两条时间轴。迟到事件可能在今天写入，但描述三个月前的状态；只看 `created_at` 会错误重排历史。

### 区间

推荐半开区间 `[valid_from, valid_to)`，避免边界时刻同时命中两条互斥事实。模糊日期如“去年春天”应保留原文、区间和 precision，不要伪造精确秒。

## 5. 权威数据模型

```text
memory_lineage
  id, tenant_id, user_id, agent_id, subject, predicate, cardinality

memory_version
  id, lineage_id, value, normalized_content, type,
  proposed_valid_from, proposed_valid_to,
  confidence, sensitivity, created_at

memory_transition
  id, lineage_id, version_id, action,
  effective_valid_from, effective_valid_to,
  reason_code, actor, created_at

memory_source
  memory_version_id, source_event_id, extraction_run_id
```

版本保存不可变解释；transition 保存系统如何接纳、替换、冲突或失效它。current projection 可从 transition log 重建。

### 不变量

- 同一 SINGLE lineage 的互斥 CURRENT 有效区间不能重叠，除非显式 `CONFLICTED`。
- source/trust/scope 由服务器边界确定，不能相信模型或客户端回显。
- correction 新增 version 和 transition，不原地覆盖 retained evidence。
- 所有 derived projection 都能从 authority 重建。

## 6. Consolidation：局部整合

Consolidation 通常读取最近 N 个未处理 turns：

```text
recent log -> summarizer/extractor -> durable facts + one episode -> policy -> store
```

为什么批量：每条消息都总结成本高、上下文不足、噪声大。触发可按 turn 数、token、任务完成或时间。

失败安全：只有事实成功写入后才能标记 source 已 consolidated；模型失败/JSON 解析失败时保留未处理标记，避免静默丢失。

### Forward-only 的结构性缺陷

如果每次只看最近窗口，它无法发现数月前的重复和矛盾。局部 consolidation 解决“近期对话变记忆”，不解决“全局记忆库持续整洁”。

## 7. Reflect：从轨迹中提炼经验

Reflect 通常在任务/会话后分析：

- 哪些步骤成功或失败；
- 哪些偏好/事实值得记；
- 是否出现重复错误；
- 是否形成可复用 procedural rule；
- 哪些旧记忆应降权、合并或标记冲突。

反思结果是 proposal，不是自动真理。尤其 procedural memory 应通过测试/人工批准，并记录适用条件和证据。

## 8. Anthropic Dreaming：跨会话后台重组

截至 2026-08-29，Anthropic Managed Agents 的 Dreaming 是 research preview。官方描述：异步 job 读取一个已有 memory store 和 1-100 个历史 sessions，产生一个**新的、独立的 output memory store**；用于合并重复、替换陈旧/矛盾条目并发现新洞察。输入 store 不被修改，可审查后采用或丢弃。

### 第一性原理

在线增量写入只能看到局部上下文；Dreaming 相当于离线全局 compaction/curation：

```text
input store + session transcripts
  -> async synthesis
  -> new output store
  -> review/evaluate
  -> promote or discard
```

创建新 store 而不原地改写具有 MVCC/blue-green 的味道：可比对、可回滚、失败产物可检查。

### 仍未自动解决的问题

- 模型可能把错误洞察固化；需要评估和 promotion gate。
- 费用随 session 数量/长度增长。
- 输入大小和 session 数有限制，且可能运行数分钟到数小时。
- 删除/隐私必须覆盖 input、output、transcript 和 job 中间状态。
- Dreaming 是产品术语；一般架构应称 offline reflection/consolidation，避免把通用概念绑定单一厂商。

## 9. Provenance：为什么“谁说的”比“说了什么”同样重要

建议溯源链：

```text
source_event
 -> extraction_run(model, prompt, schema)
 -> candidate
 -> policy_decision(reason_codes)
 -> version
 -> transition
 -> projection_generation
 -> retrieval_trace
 -> answer/tool action
```

它支持解释、纠错、模型迁移、删除传播和安全审计。只存一个 `source='conversation'` 字符串远远不够。

## 10. 并发、重试和复活

### At-least-once

LLM/embedding 调用会超时，worker 会重试。不要声称 exactly-once provider call；真正可保证的是相同 logical key 只产生一次可见领域效果。

### Fencing

lease 过期后旧 worker 可能晚回来提交。completion 必须携带递增 fencing token/CAS；旧 token 更新 0 行并丢弃。

### Resurrection guard

用户删除时，pending extraction/projection/replay 可能重新生成内容。用 deletion epoch/generation fence，让旧 job 在写入前检查当前 epoch，不允许跨删除边界提交。

## 11. Forgetting 不只是 TTL

可能动作：

- 降低召回权重；
- 合并/压缩；
- 从热索引归档；
- 标记 invalidated；
- retire 为历史；
- 擦除正文与所有派生投影；
- 保留不含内容的 tombstone。

策略可依 memory type、用户选择、任务完成、法律保留、confidence、访问统计而定。TTL 无法同时表达永久偏好、短期任务和历史事件。

## 12. 维护任务调度建议

| 任务 | 触发 | 输出 | 关键保护 |
|---|---|---|---|
| consolidation | N turns/任务完成 | facts + episode | 幂等、失败不标记完成 |
| dedup | 写入时 + 定期 | merge/reinforce proposal | entity/predicate/time |
| retire | 新证据/业务事件 | transition | 不物理删除历史 |
| reflect | session/task 后 | insight/procedure proposal | 高门槛、可回滚 |
| dream/global compaction | 周期/体量阈值 | 新 generation/store | shadow eval + promotion |
| re-embed | 模型升级 | 新 projection generation | 不混模型、原子切换 |
| deletion reconcile | 删除请求/定时 | verified receipt | 全投影、队列、复活防护 |

## 13. 从候选到 Transition 的决策算法

```text
1. hard scope/trust/sensitivity policy
2. resolve subject + predicate + cardinality
3. find candidate lineages in same scope
4. compare normalized value + temporal overlap + provenance
5. classify relationship:
   exact duplicate       -> REINFORCE
   paraphrase equivalent -> REINFORCE or MERGE
   different SET value   -> COEXIST
   different SINGLE value, non-overlap -> historical COEXIST
   different SINGLE value, later trusted change -> SUPERSEDE
   overlapping incompatible evidence -> CONFLICT
   evidence proves prior false -> INVALIDATE
6. persist immutable version/source and append transition atomically
7. enqueue projection build
```

LLM 可以提出 relationship，但数据库事务必须重新验证 scope、lineage、lock version、允许的状态转移和 source evidence。

### Cardinality 为什么关键

- `preferred_editor_theme` 通常 SINGLE：dark 与 light 同时 current 可能冲突或更新。
- `liked_cuisines` 通常 SET：Chinese 与 Italian 可以共存。
- `lived_in_city` 是时间上的 SINGLE：上海与杭州可在不重叠区间共存。

没有 predicate cardinality，就无法区分“新增一个值”和“替换旧值”。

## 14. Current 与 As-of 查询

权威查询概念：

```sql
-- 当前有效版本
SELECT v.*
FROM memory_current_projection c
JOIN memory_version v ON v.id = c.memory_version_id
WHERE c.tenant_id = :tenant
  AND c.user_id = :user
  AND c.subject_key = :subject
  AND c.predicate = :predicate;

-- 某个业务时刻有效的版本（概念化）
SELECT v.*, t.effective_valid_from, t.effective_valid_to
FROM memory_effective_state t
JOIN memory_version v ON v.id = t.memory_version_id
WHERE t.lineage_id = :lineage
  AND (t.effective_valid_from IS NULL OR t.effective_valid_from <= :as_of)
  AND (t.effective_valid_to IS NULL OR :as_of < t.effective_valid_to);
```

`memory_current_projection` 是可重建视图；历史真值来自 retained versions/transitions。不要用 `ORDER BY created_at DESC LIMIT 1` 模拟 current，它无法处理 late event、invalidate 和 unresolved conflict。

## 15. Late Event 的推导

系统 8 月 29 日收到一句：“我 6 月已经从上海搬到杭州。”

```text
observed_at      = 2026-08-29
event/valid_from = 2026-06 (MONTH precision)
```

它可能把上海的有效区间关闭在 6 月，而不是 8 月 29 日。若 7 月曾基于旧知识回答“住上海”，transaction-time 审计仍需显示当时系统尚不知道搬家；这就是双时态的价值。

迟到证据也可能与已知区间冲突。系统应形成 `CONFLICTED` 或人工 review，而不是总把最后到达的消息当最新现实。

## 16. 并发修正的事务模式

两个请求同时把同一 SINGLE fact 改成不同值：

1. 读取 lineage 的 `lock_version`；
2. 在确定顺序下锁定 lineage/current rows，或用 optimistic CAS；
3. 事务内重新计算 transition，不相信事务外 plan；
4. `UPDATE lineage SET lock_version=lock_version+1 WHERE id=? AND lock_version=?`；
5. 影响 0 行则返回 conflict/retry；
6. append version/source/transition 与 outbox intent 同事务。

last-write-wins 只能解决存储覆盖，不能证明哪个业务事实更可信。不同可信来源的冲突应保留两边证据。

## 17. Dream/Reflect 的 Promotion Gate

后台重组不应直接替换线上 store：

```text
snapshot input generation
-> dream/reflect into candidate generation
-> structural validation
-> privacy and policy scan
-> diff review: add/merge/drop/rewrite
-> held-out recall/update/abstention benchmark
-> human or automatic approval threshold
-> atomic promotion
-> monitor + rollback window
```

至少比较：memory count、duplicate clusters、unresolved conflicts、stale leakage、gold recall、unsupported insight、sensitive content、token size。压缩率高不等于质量好；它可能只是删掉了有用证据。

## 18. 维护系统的可观测性

- candidates by decision/reason；
- duplicate/conflict/supersede rate；
- lineages with multiple CURRENT heads；
- invalid interval count；
- source without materialized outcome；
- projection generation lag；
- dream diff size、unsupported insight rate；
- deletion requests not reaching terminal state；
- replay resurrect attempts blocked。

这些指标检测的是状态健康，而不只是服务 CPU/错误率。

## 19. 动手练习与验收

### 练习

为“上海 -> 杭州 -> 迟到证据表明中间住过苏州”画 valid-time 与 transaction-time 两条轴；给出 current、7 月 as-of、系统在不同 transaction time 所知状态。

### 本章验收

- 能区分 REINFORCE、SUPERSEDE、COEXIST、CONFLICT、INVALIDATE。
- 能解释 cardinality 和 temporal overlap 如何参与决策。
- 能证明为什么 `created_at DESC` 不等于 current。
- 能设计并发 correction 的 CAS/lock 语义。
- 能为 Dreaming 输出设计 shadow evaluation、promotion 和 rollback。
