# 包治理：回答应用与现有记忆核心

本次是用户授权的应用扩展和结构重构，不迁移已有 PostgreSQL schema，不改变记忆权威与阶段边界。

## 依赖方向

```text
api.answering -> answering.service / answering.model / api.security
answering.service -> answering.port / answering.model
answering.port -> answering.model
answering.model -> memory-domain 的 scope 值对象
adapters.answering -> answering.port / answering.model / context / retrieval
context -> retrieval -> memory-domain
adapters.spring -> 各模块的组装入口
```

`api.answering` 目前读取 `adapters.spring.AnsweringProperties` 这一配置对象，作为显式组合边界；
它不能调用供应商、JDBC 或具体 PostgreSQL adapter。运行策略不应通过 controller 内的 if/else
变成第二套领域实现。

## 约束与理由

| 约束 | 要防止的实际问题 |
|---|---|
| 业务模块不依赖 adapters、API、worker | 测试必须启动数据库/模型，或接口 DTO 反向决定业务规则 |
| answering 不依赖 Spring/JDBC/HTTP/Jackson | 把 schema、状态判断和编排绑到一个供应商 SDK |
| model 不依赖 service/port | 值对象构造时产生网络调用或循环依赖 |
| port 不依赖 service | 接口和调用者互相引用，让替换实现必须引入完整应用 |
| HTTP 不访问模型/数据库 adapter | controller 混入重试、SQL、供应商 JSON 解析 |
| answering adapter 不依赖 Spring 组装包 | 适配器自己查配置/找 Bean，无法独立构造测试 |
| 模块依赖无环 | 小改动迫使所有模块一起初始化与发布 |

约束由 [ModuleBoundaryTest](../../architecture-tests/src/test/java/dev/memos/architecture/ModuleBoundaryTest.java)
执行。新功能不得用增加规则排除项来规避边界。

## 本次实际重构

1. 把重复出现的“调用 retrieval 再调用 context assembler”抽出为共享 MemoryEvidenceService。
2. 新回答模块通过 EvidenceSearchPort 消费这条路径；模型工具调用不能直接访问 SQL。
3. 将请求线程中的认证 scope 转为不可变 command，再传递给后台回答任务。
4. 提取 AnswerExecution，集中处理准入、取消与名额释放，避免 SSE 与普通请求各写一套。
5. 提取 OllamaChatTransport，复用回答与重排的截止时间、大小限制、重试和流协议处理。

## 逐步治理已有平铺包

现有 materialization/governance/retrieval 等平铺包保留兼容性。以后修改某个领域时，再按
“model / port / service”迁移该领域并同步架构测试；不要在没有行为变更的提交中一次移动
所有 Java 类。尤其不要把不同领域的 Command、Result、Exception 全部塞入一个公共 common 包。

重命名或迁移必须确认：调用点、Spring 扫描、测试包、序列化契约、文档源码链接和 CI。
本次没有声称已完成全仓所有旧包的重新分层。
