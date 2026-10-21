# 契约草案

> 这些文件是说明性 JSON 示例，不是运行时 Schema、协议实现或兼容性保证。

计划中，本地 Python 编排器与 Node 世界桥接器使用逐行 NDJSON。每条消息带 `schema_version: "0.1-draft"`、`kind`、ISO 8601 `timestamp` 和会话身份字段。桥接器必须拒绝未知版本、未知种类、未授权世界/actor、过期 `session_epoch` 或越界行动参数。

| 文件 | `kind` | 说明 |
| --- | --- | --- |
| [observation.json](examples/observation.json) | `world.observation` | 非破坏性位置事实快照 |
| [action-request.json](examples/action-request.json) | `action.request` | 受限的 `look_at` 请求 |
| [action-result.json](examples/action-result.json) | `action.result` | 引用原请求的终态回执 |

`action_id`、`world_id`、`agent_id` 和 `session_epoch` 共同防止跨会话混淆。对改变世界的行动，单 actor 同时只允许一个；结果为 `unknown` 时不得盲目重试。观察可在有界队列压力下合并，行动终态回执不可静默丢弃。
