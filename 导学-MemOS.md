# MemOS 模块导学：从请求、状态到岗位证据

更新：2026-09-07。源码基准：MemOS `85012f7`；Waku 原 live 基线 `b75adf2`，本轮消费者合同修复 `dbcda826`。本文是学习材料，不能代替个人掌握证明。
配套：[STAR 面经](面经-MemOS.md)、[原题库](docs/interview/memos-grill.md)、[结果账本](docs/benchmark/results.md)。
飞书阅读：[完整模块导学](https://my.feishu.cn/wiki/LENSwWtpfivGbIkcq9rcBTH7nQe)。
`CONFIRMED` 表示源码或本地验证可支持；`INFERRED` 表示教学与岗位判断；`HYPOTHESIS` 表示拟测。

## 1. 前置知识

2026-09-07 第二轮补充：[13模块分层工作簿](docs/interview/layered-module-workbook.md)、[市场样本与项目取舍](docs/research/market-2026-09-07/README.md)、[STAR增补卷](docs/interview/market-star-qa.md)。推荐先读本页全景，再按L0产品/L1流程/L2源码/L3故障/L4实验逐层练习。全套Q&A现为25主问、50追问；来源为116篇可见正文/预览，其中22篇社招自述面试，不冒充100篇严格社招。

下列“高频”表示本项目准备优先级，结合少量面经与 JD 推断，不是招聘市场统计。

| 知识点 | 为什么需要 | 本项目位置 | 高频度 |
|---|---|---|---|
| HTTP、202、幂等键、ETag | 分清受理、完成、重复与并发修改 | API、ingestion | 高 |
| Spring DI 与事务边界 | 找到事务在哪开始、外部调用在哪发生 | adapters、API | 高 |
| PostgreSQL 唯一约束、行锁、条件更新 | 防双写、丢更新、旧 worker 提交 | outbox、authority | 高 |
| 至少一次、租约、fencing | 理解重复调用与幂等效果的区别 | materialization | 高 |
| 向量、FTS、TopK、RRF | 解释候选从哪来及怎样比较 | retrieval | 高 |
| JWT 验签、scope、RBAC | 把身份和模型语义分开 | API security、governance | 高 |
| 事件时间、有效时间、记录时间 | 防迟到消息覆盖较新的真实状态 | memory-domain | 高 |
| 数据集切分、误报漏报、消融 | 解释机制通过却质量失败 | benchmark | 高 |
| JVM、线程池、连接池、超时 | 定位慢请求，不盲目扩容 | worker、provider adapters | 高 |
| 容器、模型身份与维度 | 复现环境，防索引与查询模型不兼容 | scripts、V007/V009 | 中 |

## 2. 重点亮点与学习顺序

| 亮点 | 为什么重要 | 通用技术关键词 | 先看文件 | 顺序 |
|---|---|---|---|---|
| 证据与状态建模 | 说过不等于当前有效 | provenance、version、transition | `modules/memory-domain/src/main/java/dev/memos/domain/temporal/TemporalTransitionPlanner.java` | 1 |
| 请求可靠性 | 超时重试不能制造不同结果 | outbox、唯一约束、幂等 | `modules/ingestion/src/main/java/dev/memos/ingestion/SourceIngestionService.java` | 2 |
| 异步恢复 | 外部模型不可放进长事务 | lease、fence、heartbeat | `modules/materialization/src/main/java/dev/memos/materialization/OutboxWorkerService.java` | 3 |
| 权限与生命周期 | 召回、更新、删除必须同一身份边界 | JWT、RBAC、tombstone | `modules/adapters/src/main/java/dev/memos/adapters/postgres/JdbcDeletionStore.java` | 4 |
| 投影与检索 | 索引随模型变化，不能成为唯一事实 | generation、RRF、token budget | `modules/adapters/src/main/resources/db/migration/V009__projection_reconciliation.sql` | 5 |
| 实验与消费合同 | 从“服务能跑”走到“调用方能用” | raw artifacts、conformance | `benchmark/src/memos_benchmark/runner.py` | 6 |

## 3. 必备知识点自检

- [ ] 能在白板上画写入、读取、删除三条链路，每条标注鉴权点和事务边界。
- [ ] 能区分 source、candidate、lineage、version、transition、projection、job 七种身份。
- [ ] 能解释“数据已提交、响应丢失”和“模型返回、提交前崩溃”两个不同故障窗口。
- [ ] 能用具体乱序例子解释 CURRENT、HISTORICAL、CONFLICTED、INVALIDATED。
- [ ] 能从 SQL 指出每路候选的 scope、真值状态、模型身份及代次过滤。
- [ ] 能解释冻结集 0/30 与开发集 3/3 为什么都必须保留，且不能相互替代。
- [ ] 能解释 Waku 接口测试通过与真实 Agent 任务成功率之间还有什么距离。
- [ ] 能不看答案修改一个已有小行为，并提出能使错误实现失败的反例。

## 4. 推荐阅读与练习产物

时间是学习预算建议，不是完成时长记录。相对路径以 MemOS 根目录为准。

| 主题 | 技术点 | 建议阅读位置 | 预计时间 | 读完交付什么 |
|---|---|---|---|---|
| 入口与运行 | profile、工具链、迁移 | `docs/local-runbook.md`、`scripts/run-local.sh` | 30–45 分钟 | 用自己的话写启动/停止步骤 |
| 写入事务 | source 与 outbox 原子性 | `modules/adapters/src/main/java/dev/memos/adapters/postgres/JdbcSourceIngestionStore.java` | 45–60 分钟 | 画三个提交/崩溃时序 |
| 异步状态 | lease 与条件提交 | `modules/materialization/src/test/java/dev/memos/materialization/OutboxWorkerServiceTest.java` | 45–60 分钟 | 解释一条旧 owner 失败断言 |
| 版本语义 | 单值、集合、乱序 | `modules/memory-domain/src/test/java/dev/memos/domain/temporal/TemporalTransitionPlannerTest.java` | 60–90 分钟 | 手算三条事件的状态变化 |
| 权威数据库 | 约束与状态派生 | `modules/adapters/src/main/resources/db/migration/V004__temporal_memory_authority.sql` | 60–90 分钟 | 区分权威表与当前视图 |
| 治理删除 | 隐藏、擦除、防复活 | `modules/adapters/src/test/java/dev/memos/adapters/postgres/JdbcDeletionStoreIntegrationTest.java` | 60–90 分钟 | 画删除与重建交错过程 |
| 检索与上下文 | 独立候选、预算与引用 | `modules/retrieval/src/main/java/dev/memos/retrieval/HybridRetrievalService.java`、`modules/context/src/main/java/dev/memos/context/MemoryContextAssembler.java` | 60–90 分钟 | 手算 RRF 并找到被预算丢弃的证据 |
| 真实负结果 | 分母、失败分类与污染 | `docs/evidence/failure-cases.md`、`benchmark/src/memos_benchmark/metrics.py` | 60–90 分钟 | 复述一个失败及证据限制 |
| 消费适配 | 同步接口与异步服务 | `docs/evidence/waku-integration-2026-09-07.md` | 45–60 分钟 | 解释 update 的非原子窗口 |

## 5. 自学提醒

若某文件或原理看不懂，请继续追问 AI；本技能负责给学习路径与题目，不提供逐行讲解。
每次只追一个具体输入：例如“这个用户改了主题颜色，哪行保证旧 worker 不能写回旧投影”。
先写预测，再跑已有测试，最后核对错误预测；不要把朗读文档当作理解。
本仓库使用 PostgreSQL，准备 MySQL 岗位时要额外学习其隔离与锁行为，不能直接套用名称。

## 6. 项目技术定位

后端与 AI 应用交叉项目：核心是受治理的长期状态、异步可靠性和检索评测；不是模型训练或推理引擎。
最合适的主叙事是“有 Java 工程基础，能够把模型接进可测、可恢复的业务链路”。
纯后端岗位看事务、故障和数据库；AI 应用岗位看任务效用、检索和模型边界；Agent 平台岗位还看
工具执行、取消、恢复和隔离，这些需要由 Waku 或另一已核实消费项目补证据。

## 7. 核心原理

### 原理一：证据不是断言

问题：用户说过“下周可能搬家”，不能直接变成“当前住址已改变”。机制：保留事件作为证据，
模型提出候选，确定性政策与状态机决定能否变成事实。落点是 source → candidate → lineage/version。
一条 source 可以没有候选，也可以形成多个候选；一条断言可以被多份来源支持，不能用一对一假设写删除代码。

### 原理二：持久意图与最终效果分离

问题：数据库提交后进程可能立刻崩溃。机制：source 与工作意图在同一事务提交，后续至少一次执行，
效果以幂等与 fence 收敛。落点是 outbox 和 worker。外部模型可能重复调用，不能由数据库只写一次推导零重复计费。

### 原理三：真值状态与相似分数分离

问题：旧地址往往比新地址更贴近查询字面。机制：先确定访问范围、有效时间和状态，再做相关性排名。
落点是 SQL 硬范围与查询意图过滤。不同事实若被模型分到了不同谱系，状态机可能无法自动归并，
所以“按 CURRENT 过滤”也不能代替对真实语义的评测。

### 原理四：可重建表示必须有身份

问题：两个 embedding 模型维度相同，向量空间却不同；A→B→A 还会遇到旧任务重新出现。
机制：模型 digest 与 projection generation 分别回答“用什么模型”和“属于哪次构建”。
落点是 V009 的维护式切换、旧任务拒绝及重建。当前没有在线双索引无缝切换能力。

### 原理五：删除是生命周期变更

问题：删除向量后，旧 source 或重试任务还可能重建内容。机制：请求事务立即隐藏并阻断旧工作，
异步清理权威和派生内容，保留非内容墓碑。落点是 V006 和删除 worker。
数据库内可验证擦除，不等于备份、WAL、provider 全部已擦除。

### 原理六：实验必须能被别人推翻

问题：只报最终分数无法分辨提示、预算、模型还是记忆机制起作用。机制：锁定数据和配置，保留逐题
输出、失败、用量与报告重建逻辑。落点是 runner、metrics、artifacts。
当前冻结集只有 10 个独立问题，重复三次不能把独立样本量变成 30。

## 8. 逐模块走读

每个模块按职责、输入输出、正常路径、不变量、失败和练习六项阅读。所有源码与测试入口以本文件及面经证据索引为准。

### M01 memory-domain：状态推理

职责：只表达事实、时间区间、基数与状态转移，不连接 Spring 或数据库。输入是已规范化候选与
既有谱系快照；输出是转移计划，而不是直接更新的数据库行。
正常路径：相同 scope、subject、predicate 找到事实谱系，再按 SINGLE/SET 与时间关系判断强化、
并存、替代或冲突。所谓 append-only 是保留期间版本正文与转移历史不被重写，当前派生状态可以变。
失败边界：自然语言时间和实体归一来自上游；输入错，确定性状态机仍可能忠实执行错误语义。
练习：列出“喜欢茶”“不再喜欢茶”“曾经喜欢茶”的时间、基数及撤回区别。不能只改变相似度。
验证入口：`modules/memory-domain/src/test/java/dev/memos/domain/temporal/TemporalTransitionPlannerTest.java`。

### M02 ingestion：接收、身份与幂等

职责：将已鉴权事件规范化，并原子保存 source/outbox。输入包括身份、事件与幂等键；输出稳定的
受理回执。同键同载荷重试返回原结果，同键异载荷不能假装是新消息。
不变量：没有 source 成功但 intent 丢失的双写窗口；键不能在每次网络重试时随机变化。
失败边界：202 不是检索可见保证。原始 payload 在异步候选 policy 前已被保留，因此候选拒绝不是入口 DLP。
练习：模拟提交成功但响应丢失，说明客户端保留哪个键、服务端靠什么约束处理并发重复。
入口：`modules/ingestion/src/main/java/dev/memos/ingestion/SourceIngestionService.java`。

### M03 materialization：提取与异步编排

职责：领取任务、续租、调用模型、严格解析、提交效果、记录重试/永久失败。模型在事务外计算，
提交时重新检查数据库内 owner、lease 和 fence；不能只在调用前检查一次。
候选有三道门：Schema 形状、语义区间与来源、确定性写政策。模型输出合法 JSON 不代表事实可接受。
任务执行成功也可能零候选、全部拒绝或全部隔离；必须看 accepted 数和权威版本。
练习：A 调用模型时停顿，B 接管后成功，A 恢复；标出会重复的调用和必须拒绝的提交。
入口：`modules/materialization/src/main/java/dev/memos/materialization/OutboxWorkerService.java`。

### M04 governance：信任、权限与删除

职责：确定候选可自动接受、拒绝或进入治理流程。模型自述 DIRECT_USER 或 confidence=1
不能扩大可信来源上限，也不能获得 procedural/project 写权限。
本次修复曾发现：经过验证的角色没有保存到 source 写能力，下游看到空权限而拒绝项目记忆。
V010 只保存允许的能力枚举；OPERATOR 的诊断权限不能自动变成写授权。
练习：分别构造普通用户、项目写用户、诊断操作者，预测同一候选的结果；同时解释 secret 的原始 source
可能已经进入数据库，拒绝派生记忆并非全面防泄露。
入口：`modules/governance/src/main/java/dev/memos/governance/DeterministicCandidateWritePolicy.java`。

### M05 retrieval：查询与候选排名

职责：query gate → 时间意图 → query embedding → 各通道候选 → 按 version 去重 → RRF → 可选重排。
默认当前查询使用 vector、lexical、structured；存在历史时间意图时才加入 temporal，不能说每次都跑四路。
各候选通道独立取候选不等于它们并发执行。RRF 融合排名，不能把其分数当相关概率。
重排返回集合需与输入候选集合完全一致且无重复，错误就回退原排名；deadline 前后检查并不自动证明
阻塞调用可被中断，真实 provider 的超时合同仍需验证。当前没有已选择并完成质量消融的真实 reranker。
练习：k=60，A 排名 1/4，B 排名 2/2，手算 B 略高；解释这仍不能证明 B 内容更正确。
入口：`modules/retrieval/src/main/java/dev/memos/retrieval/HybridRetrievalService.java`。

### M06 context：证据预算与信任边界

职责：把已选事实变成带来源的不可信证据区域，按完整渲染后的 tokenizer 数量截断。
候选 TopK 不等于最终进入模型的证据数；标签和 provenance 也消耗 token。
需要同时核对 retrieval 候选列表与 selectedVersionIds，不能引用被预算剔除的候选。
失败边界：结构转义防格式逃逸，无法证明模型不会服从字符串中的恶意语义。引用在允许集合内
也不证明它支持当前答案，支持关系需要独立的 groundedness 检查。
练习：预算刚好够事实正文但不够来源包装，应删除证据还是偷偷超额？答：按确定合同裁剪并记录。
入口：`modules/context/src/main/java/dev/memos/context/MemoryContextAssembler.java`。

### M07 adapters：数据库与模型实现

职责：实现消费模块定义的 ports；负责 JDBC、HTTP、序列化、事务和外部错误映射。
数据库表组：source/outbox 是输入与执行意图；extraction/candidate 是提案与政策证据；
lineage/version/transition/source 是权威事实与来源；current/search/checkpoint 是派生表示；
deletion/tombstone 是生命周期记录；usage/audit 提供用量及诊断事实。
V001–V010 是按时间累积的迁移，不代表十个独立服务。已有迁移不能为了清理历史而改写。
练习：追踪一个 source 的写权限从 JWT 到 V010 再到提取 store，解释跨语言 fake 为什么容易共同写错。
入口：`modules/adapters/src/main/resources/db/migration/`。

### M08 audit-observability：诊断与计量

职责：把 trace、job、耗时、失败类别和用量关联起来。业务正文与凭证不应成为默认诊断载荷。
重要区分：API 受理延迟、排队、模型推理、权威提交、投影、首次可见分别测；日志有 trace ID
不能自动宣称所有环节已有完整分布式 tracing。数据库内 append-only 审计也不等于外部防篡改系统。
练习：用户说“记住了却查不到”，先比 source settlement、accepted 数、投影代次和最终 selected IDs，
而不是直接给模型换大参数。provider 重试、token 计数及后台 embedding 都要进入成本账。
入口：`modules/audit-observability/pom.xml`、`modules/adapters/src/main/java/dev/memos/adapters/metrics/`。

### M09 memos-api：鉴权与外部合同

职责：验证 JWT 签名、issuer/audience/expiry 和角色，推导可信主体，转换 HTTP 与业务错误。
scope 来自可信 token，不来自检索语句或任意 body；不同 tenant 下相同 ID 不得借错误信息泄露存在性。
版本纠正使用 If-Match 防基于旧快照的修改；这与请求幂等是两件事。
失败边界：本地 HS256 凭据是开发参考；企业 IdP、撤权、轮换及 agent 授予规则仍由部署方定义。
练习：比较 401、403、版本冲突与后台 DEAD，各自应触发什么客户端动作。
入口：`applications/memos-api/src/main/java/dev/memos/api/security/JwtActorContextResolver.java`。

### M10 memos-worker：进程生命周期

职责：独立启动轮询和管理健康端点，把持久任务交给应用服务；业务状态机仍在模块里。
worker 活着、数据库连通、队列可消费和新记忆已投影是四种不同状态。
失败边界：重启能恢复待执行意图，不代表每次模型请求可恢复到中间 token；重复调用仍可能有成本。
练习：先停 worker 再写入，观察受理及 pending，然后恢复并等 settlement；只在专用测试 scope
操作，不删除真实用户记忆或现有原始 benchmark。
入口：`applications/memos-worker/src/main/java/dev/memos/worker/OutboxPollingWorker.java`。

### M11 architecture-tests：依赖约束

职责：把“domain 不依赖框架、层间关系不能反向”变成可失败的检查。
它能防架构随开发逐步漂移，不能证明业务没有 bug，也不能代替质量评测。
练习：在临时改动中给领域类引入 Spring 依赖，预测哪项失败，然后恢复；不要把这种故障注入说成线上事故。
入口：`architecture-tests/src/test/java/dev/memos/architecture/ModuleBoundaryTest.java`。

### M12 benchmark：从协议完成到效果比较

职责：固定数据、split、模型完整身份、回答 Prompt 与预算，跑四基线并逐样本记录结果。
分层诊断：源事件是否受理 → 是否提取候选 → 是否接受 → 是否形成正确谱系 → 是否投影 → 是否召回 →
是否进上下文 → 答案及引用是否正确。每层要有分母；最后 0 分不能直接归罪于检索。
verifier 重算机械报告并检查覆盖和 hash，但可能与 runner 共享业务假设；它证明包合同，不能证明标注真理。
当前字段 recall_at_k 是 any-hit rate；complete_recall_at_k 是所有 gold 是否命中，不能按名字误读。
练习：离线重建已发布包；解释没有调用模型为何仍能验证报告，同时为何不能声称重现了模型输出。
入口：`benchmark/src/memos_benchmark/metrics.py`、`benchmark/src/memos_benchmark/artifacts.py`。

### M13 Waku 消费适配：同步方法包住异步链路

职责：为 Waku 提供 add/search/search_with_ids/list/update/delete 六方法，附加 settle。
本地源码在独立 Waku 工作目录，仓库固定入口见 [Waku 适配器](https://github.com/Zhi-Xiang-Guo/waku-agent/blob/b75adf280aab4456d123693dcc3c942ed4f73dcc/waku/memory/semantic/memos_store.py)。
add 等待 source 的抽取、物化和投影；update 先写替换来源、证明形成版本，再失效旧版本，最后观察旧版本
退出相关检索；delete 触发受治理擦除。search 只返回进入上下文且满足分数阈值或词项匹配的行。
必须理解四个尚未被 12 个 conformance case 解决的边界：

- update 包含多个 HTTP 请求，不是跨请求原子替换；新值已写而后续失败时可能部分完成。
- 用一条旧内容查询观察不可选中，不等于证明任意并发查询与所有 scope 都已完全收敛。
- 2026-09-07增补：读失败伪装为空集合已由Waku提交`dbcda82615506db89f07d3187d64ba4c7032800a`修复；管理工具强转整数导致UUID更新/删除失败也已修复。630个确定性测试通过，73项外部跳过；旧12/12 live结果仍只属于b75adf2。
- relevance 阈值和词项重合是开发启发式；中文、同义改写与罕见词的质量仍需单独评测。

练习：在隔离环境让替换来源提交后第二个 HTTP 请求失败，预测方法返回值、库中状态及重试风险。
这是下一步应测的命名假设，不冒充已经发现了每一种线上失败。

## 9. 关键设计决策与验证

| 决策 | 备选 | 当前取舍 | 风险 | 该用什么验证 |
|---|---|---|---|---|
| 单 PostgreSQL 权威 | 多存储双写 | 一致性边界更小 | OLTP 与检索争资源 | 代表负载下 SQL/队列观测 |
| 异步提取 | HTTP 同步提取 | 请求受理快、状态可查 | freshness 与用户预期差异 | 写入到首次可见分布 |
| 确定性转移 | 模型决定最终状态 | 不变量可测试 | 规范化错误仍会污染 | 抽取、实体归并、冲突分层标签 |
| 多通道融合 | 纯向量/纯词法 | 兼顾不同查询信号 | 噪声与额外调用 | 同候选预算的消融 |
| 维护式重建 | 在线双代索引 | 可解释和回滚 | 重建期间召回不完整 | A→B→A、删除竞态与恢复测试 |
| 消费端等待 | 接受最终一致 | 适配同步调用预期 | 超时与部分成功 | Waku 端失败注入、恢复合同 |

## 10. 量化与验证：已测和待测

已测：MemOS 最近全仓 198 Java 测试、70 Python 测试；Waku 12 个本地真实后端合同测试，完整 gate
619 passed/73 skipped。它们是特定提交下的测试计数，不能换成系统可用率或个人能力分数。
冻结测试：10 个独立问题×3 重复×4 基线，MemOS 0/30，简单基线各21/30；修复开发 smoke 为3/3。

建议后续测量以 Waku 的一个实际任务为起点：跨会话保留项目约束，收到更正后遵守新约束，删除后不再使用。
固定用例、模型、工具范围与 token 预算，比较无长期记忆、Waku 默认存储与 MemOS；用户任务验收和底层
四基线检索实验分别报告。独立未见评测需在开发调参前封存；同一工程师已经看过的旧测试不再充当未见集。
测量完成前，任务成功率、freshness p95、成本下降和错误恢复时间均是待测。

## 11. 岗位定位与是否还需深入优化

结论（INFERRED）：需要继续深化，但最值得补的是消费合同、未见评测和个人掌握，不是扩功能。
现有材料足以开始有选择地投递，不应把“所有指标都优秀”设为投递前提。

### 岗位适配

- 主投：Java 后端 / AI 应用后端。讲清事务、并发、SQL、故障恢复，再用模型评测证明能处理不确定输出。
- 主投或相邻：RAG / Agent 应用工程师。重点补用户任务、检索误差、Prompt 迭代和工具执行边界。
- 有针对性冲刺：Agent 平台、Memory / 检索基础设施应用侧。需要任务恢复、可观测性、容量与安全场景的更强证据。
- 暂不把 MemOS 单独定位成模型训练、CUDA/算子、分布式推理或底层推理 Infra 项目；它没有这些实现证据。

Waku 是已核实的消费者；CodeFlow 在早期规划里承担产品叙事，但本次没有审计其代码与业务使用，
不能把 Waku 结果自动记到 CodeFlow，也不能把 Waku 上游全部功能算成个人贡献。

### 按收益排序的后续工作

P0，继续补消费合同证据：本轮已修读故障与空结果混淆、工具ID合同；对 Waku 的“写失败布尔语义”“更新部分完成”“客户端跨重试身份”继续建立失败矩阵。
退出条件是每个失败有可观察状态、可解释重试行为及回归；只能做与已测缺口相连的有限修复。

P0，做一个真实任务闭环：用户给出项目约束，跨新会话完成任务，纠正后重做，删除后再问。
记录实际输出、来源、失败和人工验收。接口 conformance 已通过不替代这一层；不虚构活跃用户。

P1，冻结新评测：按任务族隔离新开发/测试，标注 shouldRemember、时态/冲突、相关证据和预期拒答。
先声明样本与失败处理，再运行；输出逐层 precision/recall、答案与引用分数。规模由任务覆盖和成本决定，
不要先承诺漂亮样本数。新测试标签应由独立标注/留出流程封存，自动生成样例本身不证明无污染。

P1，测本机代表负载：声明并发、历史长度、硬件、热/冷状态及保留范围；同时记错误、超时和队列 lag。
有瓶颈证据才优化。当前 3/3 开发答案和阈值不能作为优化成功率。

P1，若面向企业数据：先界定 source 入口最小化、provider 传输和访问授权；再决定是否需要入口 DLP、
生产 IdP、密钥轮换或备份恢复屏障。它们由具体部署风险触发，不为了面试把全部治理系统都造一遍。

P2，暂缓：Graph DB、自动反思记忆、更多模型、大规模分布式组件、未测 rerank 优化、微调。
只有已有测试证明现方案无法满足命名需求，而且替代方案能在预算内被比较时，才进入新 ADR。

### 来源与取样限制

检索日期 2026-09-07；这是定向样例研究，不是薪资/岗位频率统计。

第二轮系统台账见[研究报告](docs/research/market-2026-09-07/README.md)：123个候选、114正文/2预览/7不可读；社招A类22篇来自7个作者分组，其中16篇同作者，严格社招量仍不足100。32条JD分为5官方全文与27BOSS公开摘要；完整BOSS详情受验证限制，不能当完整要求。以下保留首轮来源作为补充，不与新台账重复计数。

- [两年 Java 转 AI 应用社招原帖](https://www.nowcoder.com/discuss/914627656245600256)：作者报告的题目包括项目动机、谁在用、效果与 RAG 路径。不能由一篇面经推断“社招不考基础”。
- [AI 应用及 Agent 开发职位样例](https://m.zhaopin.com/jobs/CCL1395276590J40906972712.htm)：列表标 1–3 年，正文覆盖编排、Memory、RAG、评估；页面也有无关职称文本，按低置信岗位样例处理，不能推断招聘方统一标准。
- [企业 Agent 应用职位样例](https://www.zhaopin.com/jobdetail/CC194595610J40874905703.htm)：搜索抓取展示业务需求、部署及 ROI；详情再次打开超时，且列表 1–3 年与正文三年以上有冲突，只作业务闭环方向参考。
- [Binance 官方 AI Agent Engineer JD](https://jobs.lever.co/binance/3a2ca7e0-e2c9-4248-b8fe-0de5d05dee1c)：强调生产经验、检索/评测、运行时和用户反馈。它是偏研究与平台的冲刺样例，不是所有初级岗位门槛；GraphRAG 被列为加分项不构成给本项目加图数据库的理由。
- [OWASP 提示注入防护](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)：支持分层防护与最小权限原则，不提供“结构转义根治注入”的保证。

## 12. 三轮训练与退出条件

第一轮基础：独立讲 source/outbox、事实版本和读取路径；每题先口答，再定位到代码。第二轮故障：
给重复、迟到、删除、provider 超时四个反例，先预测数据库状态。第三轮取舍：拿已发布负结果解释
为何保留机制、如何减少复杂度，提出有分母、有失败处理的下一项实验。

真正通过的标准是能解释并局部修改，而不是背出全文。复练只记录在个人会话；不把未经验证的掌握评价发布到飞书。
