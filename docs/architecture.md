# Agent-LuoTianyi × Minecraft 集成架构草案

> 状态：提案，待人工评审；不代表已经实现、可运行或已完成安全验证。

## 目标

Luo-Sandbox 计划让 Agent-LuoTianyi 驱动的洛天依在 Minecraft Java 中形成观察→决策→行动→反馈闭环。它不是独立 Agent、通用世界框架或新的认知运行时。上游保留人格、认知、对话、记忆、规划/技能及 Agent/WorldStage 的交互调度；本仓库提出 Minecraft 具身连接和协议映射。

未来集成可按需复制或改造上游实现，但不得由此形成第二套“脑”或独立业务所有权：人格、认知、记忆和决策仍以 Agent-LuoTianyi 为事实来源。导入模块应保留适用 MIT 版权与许可证，逐项记录来源路径、提交和修改，并制定更新/兼容策略；可共享的改动优先在上游维护。

## 分层与职责

| 层 | 计划职责 | 不负责 |
| --- | --- | --- |
| 上游 Agent-LuoTianyi | 人格、认知、对话、记忆、规划/技能，及 Agent/WorldStage 生命周期、队列、截止时间和取消语义 | Minecraft SDK、机器人连接 |
| 集成适配器（提案） | 将 MC 观察映射为 `Stimulus`/WorldStage 输入，将 `ActionPlan`/技能决定映射为类型化 MC 行动，并回填 `ActionResult` | 新的业务规划器或认知服务 |
| Mineflayer 桥接器（提案） | Minecraft 连接、受限事实采集、白名单移动/交互执行、回执和桥接会话生命周期 | 对 LLM 开放任意 JS、shell 或游戏命令 |
| 上游基础设施 | 模型、存储、配置与日志的既有适配和组合根 | 由本仓库替代或复制 |

可在上游与桥接器共同部署时设置本地 Python 侧适配缝隙；它不是新的编排服务器。跨仓库传输拓扑尚未决定、未实现：本地 NDJSON stdio 是候选，远程传输与认证仍待设计。本仓库不声称上游既有 HTTP/WebSocket 已支持这些 Minecraft 行动。

## 本地桥接协议

若选择本地 NDJSON stdio 候选，AgentLuo 集成适配器与 Node 桥接器每行交换一个 UTF-8 JSON 对象。stdio 管道不等同于可信输入；桥接器仍须验证世界、洛天依运行时映射、epoch、行动类型、参数上限和时间限制。

初始消息种类为：

- `world.observation`：Minecraft 事实快照或增量，以及与事实分离的原始/不可信文本
- `action.request`：由上游决定映射出的受限 Minecraft 行动
- `action.result`：桥接器的终态回执，引用请求的 `action_id`

样例见 [contracts README](../contracts/README.md)。样例是说明材料，不是运行时 JSON Schema。

## 身份、顺序与终态

每个请求与回执必须带有 `schema_version`、`action_id`、`world_id`、`agent_id`、`session_epoch` 和 ISO 8601 `timestamp`。`agent_id` 是拟议的上游洛天依运行时映射，不是 Minecraft 用户名。`session_epoch` 在桥接重连或世界会话更替后递增；旧 epoch 的请求一律拒绝，旧事件不得驱动新会话决策。

单个 `(world_id, agent_id, session_epoch)` 同时只能有一个改变世界的行动。计划中的终态为：`succeeded`、`failed`、`timed_out`、`cancelled`、`disconnected`、`unknown`。超时、取消或断连并不证明行动未发生；若结果为 `unknown`，上游 Stage 应通过新的观察或人工介入确认，禁止盲目重试变更操作。

## 队列、取消与授权

上游 WorldStage 保持业务调度所有权；集成适配器与桥接器须配合其有界队列、容量、行动持续时间、截止时间和取消限制：

- 高速观察可按最新快照或同类 delta 合并，明确记录合并语义
- 行动请求与最终回执不可因观察拥塞而静默丢失
- 取消是对正在进行或排队行动的显式请求，结果仍必须落入一个终态
- 桥接器只接受已授权的 `world_id`、`agent_id` 与行动白名单
- 每项行动有固定参数边界和最长执行时间；LLM 不获得任意 JS、shell 或游戏命令通道

计划中的事件账本应保存请求、接受/拒绝、取消、终态回执和关联观察的最小审计信息。它服务于回放和排障，不等同于事实数据库，也不应记录凭据。

## 世界事实模型

`WorldObservation` 的映射计划区分：可验证的 Minecraft 事实快照/增量、来源元数据，以及不可信文本（聊天、告示牌或服务端文本）。上游认知可参考文本，但不得把它未经验证地提升为权限、位置、库存或行动完成事实。行动完成以可靠 `action.result` 与后续事实观察共同判断；持久世界状态与记忆反馈由上游能力演进承接。

## 未决评审点

- 由上游当前代码能力审查 `Stimulus`、`ActionPlan`、`ActionResult` 和 WorldStage 的实际映射
- 选择具体的契约校验器、跨仓库传输和认证策略
- 明确账本保留周期、脱敏规则与回放权限
- 明确 Minecraft 接入的行动集合、授权来源和限流值
- 为生活目标（移动、探索、聊天、材料、建造、日常习惯）定义逐项授权和验证策略
