# Agent-LuoTianyi 集成侧边界草案

> 未实现。此目录不包含服务入口、依赖清单或可启动代码；名称沿用目录骨架，后续可经人工评审改名。

这里不是新的 Python 编排器或独立认知服务。未来可在 Agent-LuoTianyi 侧设置一个集成适配缝隙，复用其既有组合根、Agent、WorldStage 与基础设施能力，并与 Mineflayer 桥接器通信。共同部署时的本地 NDJSON stdio 仅为候选；远程传输与认证尚未决定。

计划职责：

- 将 Minecraft 观察映射为 `Stimulus`/WorldStage 输入，并将 `ActionPlan`/技能决定映射为类型化 Minecraft 行动
- 配合上游的会话、截止时间、有界队列、取消和单一变更行动约束，不接管业务调度
- 审核 `world_id`、洛天依运行时映射、`session_epoch`、行动白名单及参数边界
- 支持最小事件账本的集成方案，供未来回放、排障和人工审计

模型、存储和配置仍属于上游基础设施适配层。本仓库不声称上游现有 HTTP/WebSocket 已支持新行动；模型输出只能经上游受限技能形成被验证的计划候选，不得获得 JavaScript、shell 或未受限游戏命令执行能力。
