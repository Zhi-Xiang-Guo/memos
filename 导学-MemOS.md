# MemOS 项目导学（AI 应用方向）

> 证据截止：仓库 `main` 分支提交 `6706a10`（2026-08-30）。Features 0–5 已实现并发布；Feature 6 的数据集、四基线运行器、用量与存储证据包已发布，但选定真实模型的正式实验仍为 `NOT RUN`。本文是学习与面试导航，不改变当前 MVP / Evaluation 阶段边界。

## 1. 前置知识（面试高频标注）

| 知识点 | 为何需要 | 在本项目中的位置 | 高频度 |
|---|---|---|---|
| Agent Memory 与 RAG 的边界 | 避免把“记忆”简化为向量 TopK | `README.md`、`docs/architecture/01-problem-definition.md` | ★★★★★ |
| LLM 结构化输出与不信任输入 | 模型只能提案，不能直接控制权威状态 | `modules/materialization`、`modules/governance` | ★★★★★ |
| 幂等、事务 Outbox、At-least-once | 跨会话记忆异步写入的基本可靠性问题 | `modules/ingestion`、`modules/materialization` | ★★★★★ |
| Lease、Fencing Token、指数退避 | 解释超时 worker、重复执行和旧拥有者覆盖 | `OutboxWorkerService`、PostgreSQL adapters | ★★★★ |
| 版本化事实与双时态思维 | 回答“现在是什么”与“过去什么时候是什么” | `modules/memory-domain/.../temporal` | ★★★★★ |
| PostgreSQL MVCC、唯一约束、行锁 | 不只在 Java 代码中维护不变量 | `modules/adapters/src/main/resources/db/migration` | ★★★★ |
| pgvector、FTS、结构化与时态召回 | 理解多路候选如何互补 | `modules/retrieval`、`V005__hybrid_retrieval_projection.sql` | ★★★★★ |
| RRF 与 Rerank | 解释不可直接比较的原始分数如何融合 | `HybridRetrievalService` | ★★★★ |
| Prompt Injection 的持久化风险 | 记忆会把恶意内容带到未来会话 | `MemoryContextAssembler`、poisoning fixture | ★★★★★ |
| JWT Scope、RBAC、租户隔离 | 召回相似不等于有权读取 | `applications/memos-api/.../security` | ★★★★ |
| 可治理删除与复活防护 | 解释为什么删一行远远不够 | `modules/governance`、`V006__governed_erasure.sql` | ★★★★ |
| 评测污染、基线公平与可复现性 | 避免把 fixture 通过率当成模型质量 | `benchmark/src/memos_benchmark`、`docs/implementation/feature-6.md` | ★★★★★ |

## 2. 重点亮点与学习顺序（先看这个）

| 亮点标题 | 为什么重要 | 通用技术关键词 | 先看哪些文件 | 建议学习顺序 |
|---|---|---|---|---:|
| 受治理的记忆写入 | 体现 AI 应用不是直接信任模型输出 | structured output、schema、trust、sensitivity、policy | `docs/implementation/feature-2.md`、`modules/materialization/src/main/java/dev/memos/materialization/CandidateExtractionService.java`、`modules/governance/src/main/java/dev/memos/governance/DeterministicCandidateWritePolicy.java` | 1 |
| 异步可靠编排 | 展示外部模型调用下的事务、重试与故障恢复 | outbox、idempotency、lease、fencing、retry | `docs/implementation/feature-1.md`、`SourceIngestionService.java`、`OutboxWorkerService.java` | 2 |
| 版本化时态状态建模 | 是“记住历史且不乱选真相”的核心 | lineage、append-only、valid time、transaction time、optimistic lock | `docs/implementation/feature-3.md`、`TemporalTransitionPlanner.java`、`V004__temporal_memory_authority.sql` | 3 |
| 多路检索与证据预算 | 将语义、精确、时间和权威状态分离 | pgvector、FTS、RRF、query gate、token budget | `docs/implementation/feature-4.md`、`HybridRetrievalService.java`、`MemoryContextAssembler.java` | 4 |
| 安全与删除闭环 | AI 记忆是长期数据和持久投毒面 | JWT、RBAC、immediate hiding、erasure、tombstone | `docs/implementation/feature-5.md`、`GovernedDeletionService.java`、`JdbcDeletionStore.java` | 5 |
| 可复现评测证据 | 让“做出来”和“比基线好”成为两个独立结论 | frozen dataset、manifest、baseline parity、artifact verification | `docs/implementation/feature-6.md`、`benchmark/src/memos_benchmark/runner.py`、`benchmark/src/memos_benchmark/artifacts.py` | 6 |

## 3. 必备知识点

- [ ] 能用一句话区分 conversation log、evidence、candidate、assertion、projection。
- [ ] 能画出 ingestion → extraction → policy → authority → projection → retrieval → context 完整链路。
- [ ] 能说清事务 outbox 解决什么，不解决 provider exactly-once。
- [ ] 能推导 claim、lease 过期、reclaim、stale completion 的状态。
- [ ] 能说清模型输出的 schema 正确为什么不等于可写入。
- [ ] 能用住址变更例子说清 event time、valid time、transaction time。
- [ ] 能解释 `CURRENT` / `HISTORICAL` / `CONFLICTED` / `INVALIDATED` 的转移边界。
- [ ] 能说清为什么权限、真值状态和敏感性必须在排序之前硬过滤。
- [ ] 能手算一个简单 RRF 示例，并说出它的假设与局限。
- [ ] 能说清投影为什么可重建，以及模型版本变更为什么需要 reconciliation。
- [ ] 能说清 token budget 是完整渲染上下文的预算，不是文本片段长度估算。
- [ ] 能说清“立即隐藏 + 异步物理擦除 + 墓碑防复活”的删除契约。
- [ ] 能区分确定性 fixture、smoke、真实模型实验和生产 SLO。

## 4. 推荐阅读（结合仓库）

| 主题 | 通用技术点 | 建议阅读位置 | 预计时间 | 读完能回答什么 |
|---|---|---|---:|---|
| 项目问题与边界 | Memory 生命周期、风险模型 | `README.md`、`docs/architecture/01-problem-definition.md` | 45 分钟 | 为什么不能保存所有聊天？ |
| 架构选型 | 模块化单体、单库权威 | `docs/architecture/02-architecture-candidates.md`、`docs/architecture/03-recommended-architecture.md` | 60 分钟 | 为什么当前不用 Kafka / OpenSearch / 图数据库？ |
| 模块边界 | 依赖倒置、端口与适配器 | `docs/implementation/feature-0.md`、`pom.xml`、`architecture-tests/src/test/java/dev/memos/architecture/ModuleBoundaryTest.java` | 45 分钟 | 为什么领域层不依赖 Spring 和 provider SDK？ |
| 可靠写入 | 事务 outbox、幂等、租约隔离 | `docs/implementation/feature-1.md`、`modules/ingestion/src/main/java/dev/memos/ingestion/SourceIngestionService.java`、`modules/materialization/src/main/java/dev/memos/materialization/OutboxWorkerService.java` | 90 分钟 | 任意崩溃点后数据库会留下什么？ |
| 结构化提取 | Schema、bounded parsing、provider identity | `docs/implementation/feature-2.md`、`modules/materialization/src/main/java/dev/memos/materialization/StrictCandidateProposalDecoder.java`、`modules/adapters/src/main/resources/providers/openai-compatible` | 75 分钟 | 如何处理超大、嵌套、重复键和漂移输出？ |
| 写入政策 | trust ceiling、sensitivity union、fail closed | `modules/governance/src/main/java/dev/memos/governance/DeterministicCandidateWritePolicy.java`、`DeterministicSensitivityDetector.java`、`benchmark/fixtures/write-policy/v1` | 60 分钟 | 为什么高 confidence 不能提升写入权限？ |
| 时态记忆 | append-only、lineage、冲突状态机 | `docs/implementation/feature-3.md`、`modules/memory-domain/src/main/java/dev/memos/domain/temporal/TemporalTransitionPlanner.java`、`benchmark/fixtures/temporal-memory/v1` | 120 分钟 | 如何区分更新、并存、冲突和回填？ |
| 混合检索 | 多路召回、RRF、时态意图 | `docs/implementation/feature-4.md`、`modules/retrieval/src/main/java/dev/memos/retrieval/HybridRetrievalService.java`、`modules/adapters/src/main/java/dev/memos/adapters/postgres/JdbcRetrievalCandidateStore.java` | 120 分钟 | 精确 ID、语义改写和历史询问各走哪个信号？ |
| 上下文安全 | 预算、多样性、不信任数据边界 | `modules/context/src/main/java/dev/memos/context/MemoryContextAssembler.java`、`benchmark/fixtures/poisoning/v1` | 45 分钟 | 恶意记忆为什么不能当 system instruction？ |
| 身份与删除 | JWT、RBAC、事务擦除、墓碑 | `docs/implementation/feature-5.md`、`docs/adr/0005-authentication-governed-erasure.md`、`modules/adapters/src/main/java/dev/memos/adapters/postgres/JdbcDeletionStore.java` | 120 分钟 | 如何证明删除不会被旧 job 复活？ |
| 评测系统 | frozen split、公平基线、失败计数 | `docs/implementation/feature-6.md`、`benchmark/src/memos_benchmark/runner.py`、`benchmark/src/memos_benchmark/artifacts.py`、`docs/benchmark/results.md` | 150 分钟 | 什么样的结果才能写进简历？ |

## 5. 自学提醒

若某文件或原理看不懂，请继续追问 AI；本技能负责给学习路径与题目，不提供逐行讲解。

建议每次只学一条链路，并强制产出三样东西：一张状态/时序图、一个失败反例、一段三分钟口播。只读顺利路径很容易会用、不会讲。

## 6. 项目技术定位

**AI 应用 / AI Infra 交叉项目。** 它围绕跨会话 Agent 的长期记忆构建完整生命周期：模型负责提取语义候选，确定性代码负责授权、写入策略、时态转移、故障恢复和删除，最终通过混合检索与 token 预算生成可引用的上下文。

## 7. 核心原理解析

### 7.1 模型只提案，确定性代码掌握状态

**问题 →** LLM 能理解自然语言，但输出可能漂移、越权、夹带敏感数据或把临时消息误判为长期事实。

**机制 →** 模型输出先通过有界 JSON 解析和严格 schema，再由 trust、sensitivity、confidence、novelty 与 capability 共同决定 `REMEMBER` / `IGNORE` / `REVIEW`。

**在本项目中的落点 →** `CandidateExtractionService` 协调提取与解码，`DeterministicCandidateWritePolicy` 执行可测试策略；被拒绝或待审核的 proposal 不保留原始内容。

### 7.2 事务 Outbox 与租约隔离

**问题 →** 先插入业务数据后发队列会有丢消息窗口；先发队列又可能暴露未提交数据。外部模型调用还会超时、重试或在响应后崩溃。

**机制 →** 源事件与工作意图同事务落 PostgreSQL；worker 用 `SKIP LOCKED` 批量领取，以数据库时间的 lease 和新 token 隔离旧执行者，用语义幂等键保证可见效果不重复。

**在本项目中的落点 →** `SourceIngestionService` 构建源事件和 semantic job key，`OutboxWorkerService` 处理 claim、heartbeat、retry、dead 与 stale completion。

### 7.3 时态版本而非原地覆盖

**问题 →** “我现在住杭州”不应该把曾经住上海的事实抹掉；无法确定时间顺序时，也不应该随意选一个真相。

**机制 →** 稳定 lineage 下保留不可变版本和追加转移，分离 valid time 与 transaction time；单值谓词按时间关系决定更新、回填或冲突，集合值则可并存。

**在本项目中的落点 →** `TemporalTransitionPlanner` 产生纯转移计划，`JdbcTemporalMemoryAuthority` 以乐观锁和数据库约束提交版本、转移、来源和当前投影。

### 7.4 多路召回先独立，后融合

**问题 →** 向量相似擅长语义改写，却不擅长精确编号、时间范围、授权和真值状态。把所有逻辑放在 semantic TopK 之后，候选可能早已丢失。

**机制 →** 在硬 scope / truth / time 过滤下，独立生成 vector、lexical、structured、temporal 候选，再用 RRF 按名次融合；可选 reranker 必须在超时、身份和候选集完整性检查后才可重排。

**在本项目中的落点 →** `HybridRetrievalService` 完成 query gate、intent、融合与降级；`JdbcRetrievalCandidateStore` 承担各路 SQL 候选。

### 7.5 记忆以不信任证据进入上下文

**问题 →** 外部页面、工具输出或过去聊天可能包含伪造角色、闭合标签或工具命令，一旦持久化，攻击影响会跨会话延续。

**机制 →** 上下文使用单一不信任数据根结构，对内容和元数据做 XML 转义，附上不可伪造的来源 ID，按 lineage 去重，对冲突保留有限备选，并对完整渲染结果计 token。

**在本项目中的落点 →** `MemoryContextAssembler` 生成 `<memory-evidence trust="untrusted-data">`，投毒 fixture 检查恶意字符串仍是文本而不是新指令。

### 7.6 删除是状态机，不是单表 DELETE

**问题 →** 只删权威行会在向量、FTS、缓存、工作队列和审计中留下残留；旧任务还可能在删除后重建数据。

**机制 →** 请求事务先立即隐藏与终止相关任务，异步 worker 在 lease fence 内原子擦除内容派生数据，保留不含内容的审计事实和不透明墓碑防止重放复活。

**在本项目中的落点 →** `GovernedDeletionService`、`DeletionWorkerService` 与 `JdbcDeletionStore` 共同实现请求、领取、退避、死信、重排和最终擦除。

## 8. 关键设计决策

| 决策 | 备选 | 当前取舍 | 主要风险 | 验证方式 |
|---|---|---|---|---|
| Java 模块化单体 + Python 评测 | Python 同步单体；微服务集群 | 一个事务边界内保留强领域约束，同时保留 AI 评测生态 | 跨语言工具成本，模块可能退化 | 架构测试、同一 manifest 下四基线运行；后者待执行 |
| PostgreSQL 同时承担权威与初始检索 | OpenSearch + 专用向量库 + 图库 | 先减少双写、删除和本地复现成本 | OLTP / search 争用，超大规模 ANN 能力未知 | 代表性语料上测 Recall@K、p95/p99、索引体积；待测 |
| 异步记忆形成 | 请求内同步调模型 | 对话接收延迟与 provider 延迟解耦 | 读写之间有可见性窗口 | 源级 materialization 状态已可观测；代表性 freshness 分布待测 |
| 追加版本与转移 | 一行原地覆盖；完整事件溯源 | 保留历史和可解释转移，同时使用可重建当前投影加速读 | schema 与擦除复杂度上升 | 时态 fixture、并发与故障测试已过；真实分类质量待测 |
| RRF 作为初始融合 | 手写权重和；学习排序 | 在没有标注校准数据时不假设分数可比 | 忽略分数幅度，可能不如校准模型 | 开发/测试分割下做融合与 reranker 消融；待测 |
| 上下文只作不信任证据 | 把 memory 拼到 system prompt | 结构转义可测试，且不让检索内容拥有指令权 | 结构隔离不等于真实模型行为免疫 | 恶意字符串结构 fixture 已过；固定模型红队待测 |
| 立即隐藏 + 异步原子擦除 | 同步全链路删除；单表删除 | 先给读路径明确不可见契约，再做可恢复工作流 | backup、WAL、外部 provider 不在当前边界 | PostgreSQL 故障/重放/并发测试已过；外部生命周期待补 |

## 9. 量化与验证（含待测，建议）

| 要回答的问题 | 建议测量 | 当前证据 | 状态 |
|---|---|---|---|
| 写入策略是否少误写 | 真实模型标注集上的分类 precision / recall，按 memory type 和风险分组 | 17 例确定性机制 fixture，不代表模型质量 | **待测** |
| 时态转移是否符合真实语料 | 更新、冲突、回填、不确定日期的准确率与错误分析 | 14 例确定性状态机 fixture | **待测** |
| 混合检索是否优于向量检索 | 在冻结 dev/test 上比较 Recall@K、MRR、完整召回、上下文精度 | 6 例手工候选排名 fixture 只验证机制 | **待测** |
| 异步写入的用户可见延迟 | ingest p50/p95/p99、提取、权威写入、投影、总 freshness 分段耗时 | 链路终态可观测，但没有代表性分布 | **待测** |
| PostgreSQL 检索是否够用 | 小/中/大语料规模下的 Recall@K、p95/p99、索引体积、写放大 | 迁移、查询和 smoke 已通过 | **待测** |
| reranker 是否值得 | 无 rerank / 轻量模型 / LLM rerank 的质量-延迟-用量 Pareto | 仅有端口和失败降级 | **待测** |
| 删除是否真正终止 | 权威、投影、job、replay 、旧 worker 和索引逐层对账，再扩展到 backup / WAL / provider | PostgreSQL 当前边界的迁移、故障和并发测试已远程通过 | 当前边界 **已验证**；外部边界 **待补** |
| 项目是否比简单基线更好 | 相同问题、模型、token 预算与重复次数下，比较全历史、滚动摘要、原始轮次向量和 MemOS | 数据、运行器、成本与存储证据包已实现，真实模型运行未执行 | **NOT RUN** |

实验前不要在简历或口播中写“提升 xx%”、“p95 降至 xx ms”、“支持百万用户”。可以讲已验证的机制、远程 CI 和故障测试，并主动说明质量、延迟、成本和规模结论仍待正式实验。
