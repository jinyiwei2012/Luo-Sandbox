# Python 编排器边界草案

> 未实现。此目录不包含服务入口、依赖清单或可启动代码。

未来 Python 编排器拟作为组合根，显式装配领域、Stage、Agent、World 和基础设施适配器。它通过本地 NDJSON stdio 与世界桥接器通信，而不直接依赖 Mineflayer。

计划职责：

- 将观察转换为 `Stimulus`，再由受限认知/技能产出 `ActionPlan`
- 维护会话、截止时间、有界队列、取消和每 actor 一个变更行动的约束
- 审核 `world_id`、`agent_id`、`session_epoch`、行动白名单及参数边界
- 保存最小事件账本，支持未来回放、排障和人工审计

模型、存储和配置属于基础设施适配层，必须可替换。模型输出只能是被验证的计划候选；不得获得 JavaScript、shell 或未受限游戏命令执行能力。
