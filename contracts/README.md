# 历史示例与契约入口

> 这些 JSON 是遗留说明性草案，不是运行时 Schema、协议实现、固定数据包设计或兼容性保证。

当前语义单一来源为[接口草案](../docs/interface-draft.md)，经 AgentLuo 适配器映射。Forge 是当前主路线；传输、认证和最终消息结构尚未决定。现有例子保留历史说明用途，不保证与未来 Forge 接入兼容。

| 文件 | `kind` | 说明 |
| --- | --- | --- |
| [observation.json](examples/observation.json) | `world.observation` | 非破坏性位置事实快照 |
| [action-request.json](examples/action-request.json) | `action.request` | 受限的 `look_at` 请求 |
| [action-result.json](examples/action-result.json) | `action.result` | 引用原请求的终态回执 |

未来接口须表达 actor/session/epoch 关联、来源、受众、能力、动作结果（含不确定）、队列/背压和重连语义；具体可行性待按上游当前代码审查。
