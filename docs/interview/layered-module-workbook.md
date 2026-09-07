# MemOS 分层模块导学：从看懂到能独立改动

更新：2026-09-07。配合[完整导学](../../导学-MemOS.md)、[STAR 主卷](../../面经-MemOS.md)和[社招增补卷](market-star-qa.md)使用。本页是训练与验收手册，不是新增功能完成清单。仍处于 Feature 6 evidence gate。

## 0. 五层学习法与三条主链

每个模块按同一顺序学：L0 用业务语言说明存在理由；L1 画出输入、输出和正常链路；L2 指到源码、表和事务；L3 注入一个失败并解释状态；L4 用对照实验判断是否值得优化。读过文档只能算 L0–L1，测试通过不等于本人能独立实现。

写链：可信身份 → source/outbox 原子受理 → 领取/续租 → 模型提案 → 确定性政策 → 权威版本/转移 → 派生投影。

读链：可信 scope → 时间意图/查询向量 → 合法候选 → RRF/可选重排 → token 预算 → selectedVersionIds → 消费者答案与引用。

删链：授权删除请求 → authority 中先隐藏 → 持久擦除意图 → fenced worker 清理 → 非内容 tombstone → 防旧任务复活。保留期间 append-only 与受治理擦除并不矛盾。

统一练习场景：用户先声明偏好，之后纠正；第二会话询问当前值；另一租户使用同名主体；最后删除并重放旧任务。所有实验用独立测试身份，不接触既有 benchmark 原始包。

## 1. M01 领域：先判断事实，再讨论存储

L0：解决“相似的两句话，究竟强化、替代还是冲突”，不是把向量相似度当事实真伪。L1：输入候选、已有谱系和时间；输出 transition plan。L2：[TemporalTransitionPlannerTest](../../modules/memory-domain/src/test/java/dev/memos/domain/temporal/TemporalTransitionPlannerTest.java)先读用例，再回到同包领域实现；标注 SINGLE/SET、valid time、recorded time、CURRENT/CONFLICTED 的区别。

L3：构造迟到旧事实、同时间互斥值、缺时间的更正、集合成员撤回。逐条写出“保留哪些版本、当前读到什么、为什么不能直接覆盖”。边界：上游实体和时间提取错误仍可能传入合法但错误的结构；领域代码不保证语言理解正确。

L4 产物：六行状态转移真值表及对应测试。通过标准：不依赖 Spring、数据库或模型就能解释并改一个边界用例。社招追问：订单状态机/配置版本如何借用相同思想？不能把记忆领域规则原样复制到订单。

## 2. M02 受理：202、幂等与副作用不是一件事

L0：让重复网络请求具有稳定身份。L1：请求规范化后，source 与 outbox 在一个数据库事务中保存，返回受理回执。L2：[SourceIngestionService](../../modules/ingestion/src/main/java/dev/memos/ingestion/SourceIngestionService.java)与[测试](../../modules/ingestion/src/test/java/dev/memos/ingestion/SourceIngestionServiceTest.java)；寻找 canonical payload、重复键判断和存储 port。

L3：提交成功而回包丢失；同键同体并发；同键异体；删除后重放旧键。先画提交点，再判定客户端可以做什么。202 只证明受理；没有 worker 时依然可以受理，不能显示“长期记忆已可查询”。原始 payload 在候选政策之前已落库，拒绝候选不是入口脱敏。

L4 产物：请求/回执/重试时间线及 SQL 唯一约束说明。通过标准：能分清请求去重、业务去重和模型计费去重，并承认跨系统 exactly-once 不能靠一个 UUID 宣称。

## 3. M03 物化：领取任务不等于拥有永久提交权

L0：耗时模型计算不能占住数据库长事务。L1：claim → 外部计算 → 再校验 lease/fence → commit effect。L2：[OutboxWorkerService](../../modules/materialization/src/main/java/dev/memos/materialization/OutboxWorkerService.java)、[测试](../../modules/materialization/src/test/java/dev/memos/materialization/OutboxWorkerServiceTest.java)及 StrictCandidateProposalDecoder 的失败用例。

L3：A 停顿，B 接管，A 返回；模型成功但数据库提交失败；毒任务耗尽重试；政策将全部候选隔离。模型请求可能重复，只有有效 owner 的提交被接受；SUCCEEDED 也可能零 REMEMBER，不能直接等同业务成功。

L4 产物：lease/heartbeat/fence 三者职责图、重复副作用清单、DEAD 的人工处置步骤。通过标准：能解释为什么“一天租约”“只加 Redis 锁”“无限重试”都不能替代提交栅栏。只对瞬时错误设计有界重试，参数与权限错误先修正原因。

## 4. M04 治理：来源可信上限和事实可信度分开

L0：防止模型把猜测升级为长期授权知识。L1：候选 → 来源上限、能力、类型与内容政策 → REMEMBER/IGNORE/REVIEW。L2：[DeterministicCandidateWritePolicy](../../modules/governance/src/main/java/dev/memos/governance/DeterministicCandidateWritePolicy.java)、[政策测试](../../modules/governance/src/test/java/dev/memos/governance/DeterministicCandidateWritePolicyTest.java)、[删除 worker 测试](../../modules/governance/src/test/java/dev/memos/governance/DeletionWorkerServiceTest.java)。

L3：普通用户自称管理员；工具输出夹带“永久记住”；诊断角色试图写项目事实；新旧删除任务并发。V010 修复的是已验证写能力没有持久化的问题，不是允许模型自己声明角色。来源可信也不代表因果结论成立，“重启后好了”不应自动变成通用修复 Skill。

L4 产物：主体×动作×记忆类型权限矩阵、来源证据卡、删除复活反例。通过标准：说明拒绝派生记忆、阻止外部模型传输、原文保留、法定擦除是不同治理面；尚未实现的面必须标待办。

## 5. M05 检索：先审合法候选，再优化相关性

L0：从正确主体和时间范围内找有用证据。L1：当前查询走 vector/lexical/structured；历史意图才加入 temporal；去重后 RRF，重排可选。L2：[HybridRetrievalService](../../modules/retrieval/src/main/java/dev/memos/retrieval/HybridRetrievalService.java)与[测试](../../modules/retrieval/src/test/java/dev/memos/retrieval/HybridRetrievalServiceTest.java)。当前 lexical 是本项目实现，不应口播为已经实现 BM25；面经中的 BM25 只是方案比较输入。

L3：精确型号只命中词项；同义改写只命中向量；权限过滤后不足 K；reranker 返回额外 ID/重复 ID/超时。不可扩大 tenant 边界补足 K；RRF 是排名融合，不是相关概率。通道独立也不等于并发执行。

L4 产物：用三条 query 对照纯词项、纯向量、混合；写出候选数、any-hit、完整 gold 命中和最终答案分母。阈值、索引、reranker 的优化先在 DEV 上验证，不看冻结 test 标签调参数。没有测量，不引入图数据库。

## 6. M06 上下文：入选证据与候选列表必须分开

L0：有限窗口里保留可追溯、可授权的证据。L1：排序候选 → 完整渲染 → tokenizer 计数 → 裁剪 → selectedVersionIds。L2：[MemoryContextAssembler](../../modules/context/src/main/java/dev/memos/context/MemoryContextAssembler.java)、[测试](../../modules/context/src/test/java/dev/memos/context/MemoryContextAssemblerTest.java)。检查来源包装和标签是否也计入预算。

L3：正文放得下而包装放不下；引用指向预算外候选；恶意片段试图关闭证据区域；两条正确证据在摘要中被错误合并。转义只解决结构边界，不证明语义注入消失。滚动摘要不是 authority，关键约束、未决问题及来源要能重新读取。

L4 产物：预算前后对照及一份压缩保留清单。通过标准：能解释“允许引用这个 ID”与“这个 ID 支持这句话”是两种验证。长期记忆用于跨会话复用，不能拿 UI 缓存或模型 KV cache 冒充。

## 7. M07 适配器：依赖倒置不是多写一层接口

L0：隔离外部协议变动，保留领域政策的归属。L1：消费模块定义 port，JDBC/HTTP adapter 实现。L2：[V010迁移入口](../../modules/adapters/src/main/resources/db/migration/V010__source_write_capabilities.sql)、[JdbcExtractionCommitStoreIntegrationTest](../../modules/adapters/src/test/java/dev/memos/adapters/postgres/JdbcExtractionCommitStoreIntegrationTest.java)；从 source 的角色能力跟到 V010 和提取提交。

L3：JSON 合法但枚举无效；429/5xx/超时；schema 漂移；迁移后旧行缺能力；projection profile 相同名字但代次变化。不要把 Spring 注解放进 domain；不要为了让测试绿而让 fake 与真实 API 一起偏离合同。

L4 产物：表组/authority/派生表映射、一个 adapter 合同用例和迁移前后兼容说明。社招延伸：连接池、SQL 执行计划、索引选择性、MVCC、长事务；MemOS 使用 PostgreSQL，不把 MySQL 特有机制说成此项目实现。

## 8. M08 可观测：按阶段定位，而非多打正文日志

L0：区分慢在哪里、失败在哪里、钱花在哪里。L1：关联 request/source/job/attempt，按受理、排队、提取、提交、投影、检索和回答拆段。L2：[检索指标实现](../../modules/adapters/src/main/java/dev/memos/adapters/metrics/MicrometerRetrievalTelemetry.java)及[评测计划](../benchmark/plan.md)。存在 trace ID 不等于已覆盖完整分布式 tracing。

L3：CPU 不高却慢；队列积压；provider 长尾；空记忆但回答有文本；响应重试额外计费。默认事件只记录 ID、阶段、耗时、计数、错误类别；内容、token、请求 body 不进入通用日志。

L4 产物：一页诊断树与待测指标字典，分别定义 queue lag、materialization latency、首次可见、任务成功率和含重试总成本。阈值/SLO 标为待验证目标；不把本地测试时长升级为线上 P95。

## 9. M09 API：身份、授权、并发和格式逐层过关

L0：调用方不能自己扩大数据边界。L1：JWT 验证 → ActorContext → 业务请求 → 状态/HTTP 映射。L2：[JwtActorContextResolver](../../applications/memos-api/src/main/java/dev/memos/api/security/JwtActorContextResolver.java)与该应用的安全测试；检查 issuer、audience、expiry、角色和 body 的职责分离。

L3：过期 token、错误 audience、伪造 body scope、跨租户同 ID、陈旧 If-Match。401 不该无限重试，403 不能自动扩大 scope；并发冲突应重读后决定是否仍符合用户意图。开发 HS256 并不是企业身份体系的完成证据。

L4 产物：客户端错误动作表和请求授权链。能说清 MCP 标准化传输/发现并不能替代每次业务授权；MemOS 现在暴露的是 HTTP API，不宣称已有 MCP Server。

## 10. M10 worker 进程：活着、可用、收敛三种状态

L0：把后台工作从用户请求生命周期中解耦。L1：轮询器驱动应用服务，不在启动器里重写业务政策。L2：[OutboxPollingWorker](../../applications/memos-worker/src/main/java/dev/memos/worker/OutboxPollingWorker.java)与应用配置，检查关闭、调度与健康检查。

L3：停 worker 后受理；恢复后补做；处理一半重启；下游持续不可用。先验证 durable intent，再观察 accepted 及 projection settlement。不要用进程健康代替单条记忆可见，也不能声称恢复模型的中间 token。

L4 产物：仅面向专用测试数据的启停 runbook、恢复检查项和失败账本。吞吐需要 bounded queue、provider 配额、连接池和资源预算共同约束，不是无限加线程或容器副本。

## 11. M11 架构门禁：把原则变成可失败检查

L0：阻止边界随着 AI 辅助开发逐渐被侵蚀。L1：构建扫描模块依赖，违反规则则失败。L2：[ModuleBoundaryTest](../../architecture-tests/src/test/java/dev/memos/architecture/ModuleBoundaryTest.java)；解释 forbidden dependency 与业务 invariant 的区别。

L3：在一次受控临时改动中引入非法依赖，验证确实红，再撤销自己的故障注入。不要删除用户改动；不要把本地故障实验描述为生产事故。测试通过仍可能缺失关键断言，必须有反例证明检测能力。

L4 产物：一项红绿回归记录和审查清单。通过标准：能手写小改动、讲解测试为何能抓住 bug，并指出自动门禁不能证明 UX、因果收益或完整安全。

## 12. M12 benchmark：结果为负时仍保留正确账本

L0：验证复杂记忆层是否比简单基线有增益。L1：固定数据/split/模型/预算 → 执行与用量 → 机械重建 → 标注质量 → 分层归因。L2：[metrics](../../benchmark/src/memos_benchmark/metrics.py)、[artifacts](../../benchmark/src/memos_benchmark/artifacts.py)、[当前结果](../benchmark/results.md)。

L3：冻结包 120 个答案行不等于 120 个独立问题；MemOS 0/30 时先查 accepted=0，不先怪 rerank；开发 smoke 3/3 不能覆盖冻结 0/30。SUCCEEDED、正确、grounded、费用齐全是不同列。

L4 产物：复建一份报告、解释 any-gold-hit 与 complete-gold-hit、设计新未见集封存流程。新未见集仍未执行；读过的 test 不能重新命名 heldout。策略有效性需要新样本、相同预算和独立复核。

## 13. M13 Waku：跨库协议通过后还要验证用户路径

L0：让记忆服务被真实 Agent 代码消费。L1：六方法适配 HTTP，settle 连接异步物化。L2：[本轮适配器](https://github.com/Zhi-Xiang-Guo/waku-agent/blob/dbcda82615506db89f07d3187d64ba4c7032800a/waku/memory/semantic/memos_store.py)、[工具](https://github.com/Zhi-Xiang-Guo/waku-agent/blob/dbcda82615506db89f07d3187d64ba4c7032800a/waku/tools/memory_admin.py)、[回归测试](https://github.com/Zhi-Xiang-Guo/waku-agent/blob/dbcda82615506db89f07d3187d64ba4c7032800a/evals/deterministic/test_memos_fact_store.py)。

L3 本轮已修两点：读失败原先伪装为空集合；管理工具原先强制 int(ID)，导致 UUID 纠正/删除失败。测试先复现，再保留真实空命中与失败的区别、原样传递后端 ID，并抑制普通错误链中的响应内容。

L4 产物：管理工具成功/空命中/错误三条运行记录，以及 UUID 的端到端字段流。Waku 门禁 630 passed、73 skipped；旧 12/12 live conformance 属于 b75adf2，不挪作新版本 live 结果。剩余：写失败布尔语义、部分更新、稳定重试身份、settle 总 deadline、真实跨会话任务。新代码并未一并解决这些问题。

## 14. 独立验收与岗位切换

| 训练层 | 应交付的东西 | 不能算通过的表现 |
| --- | --- | --- |
| L0 产品 | 60秒问题/用户/边界说明 | 只列技术名词 |
| L1 流程 | 三条链与持久化点 | 把202说成可见 |
| L2 源码 | 沿一个ID找到方法、表、测试 | 只背架构图 |
| L3 故障 | 可重复红绿测试、失败账本 | 只有happy path |
| L4 决策 | 基线、分母、代价与退出条件 | 把DEV当heldout |

Java 后端面试先展开 M02/M03/M07/M09；AI 应用后端先展开 M04/M05/M06/M12；Agent 应用/平台先展开 M03/M08/M10/M13。共同要求是个人责任和可验证交付。根据真实经历选择主线，不把项目中的设计练习说成公司生产规模。
