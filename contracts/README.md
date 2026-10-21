# 契约草案

> 这些文件是说明性 JSON 示例，不是运行时 Schema、协议实现或兼容性保证。

计划中，Agent-LuoTianyi 集成适配器与 Node Mineflayer 桥接器可在共同部署时使用逐行 NDJSON；这只是候选，跨仓库传输和认证尚未决定。每条消息带 `schema_version: "0.1-draft"`、`kind`、ISO 8601 `timestamp` 和会话身份字段。`agent_id` 是拟议的洛天依上游运行时映射，不是 Minecraft 用户名。桥接器必须拒绝未知版本、未知种类、未授权世界/运行时、过期 `session_epoch` 或越界行动参数。

| 文件 | `kind` | 说明 |
| --- | --- | --- |
| [observation.json](examples/observation.json) | `world.observation` | 非破坏性位置事实快照 |
| [action-request.json](examples/action-request.json) | `action.request` | 受限的 `look_at` 请求 |
| [action-result.json](examples/action-result.json) | `action.result` | 引用原请求的终态回执 |

集成适配器拟将观察映射为上游 `Stimulus`/WorldStage 输入，将上游 `ActionPlan`/技能决定映射为类型化 Minecraft 行动；具体可行性待按上游当前代码审查。`action_id`、`world_id`、`agent_id` 和 `session_epoch` 共同防止跨会话混淆。对改变世界的行动，同一洛天依运行时同时只允许一个；结果为 `unknown` 时不得盲目重试。观察可在有界队列压力下合并，行动终态回执不可静默丢弃。
