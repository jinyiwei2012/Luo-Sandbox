# Mineflayer 方案：已归档的设计历史

> **已被取代：Mineflayer 不是当前依赖或实现目标。** 当前批准路线是 Forge 1.20.1 真实客户端控制器与服务端伴随模组，见[当前架构](architecture.md)与[产品需求](product-spec.md)。本文仅保留历史来源，不能用于锁定 Node、NDJSON、登录、路径或里程碑要求。

## 历史来源

Agent-LuoTianyi 历史提交 `4812240c5fefcb939a0b31e6e9dbb9b1920c2bcc` 的 `README.md:157` 曾提出使用 Mineflayer 让天依控制 Minecraft 机器人。该提交还加入孤立 gitlink `03eba44f3e9cb93a0f0bf69a75938246e174dc6f`，但没有可用 `.gitmodules`，目标对象也无法从现有克隆恢复；因此没有旧实现、协议或完成度可供继承。

早期 Luo-Sandbox 草案据 Mineflayer 与 mineflayer-pathfinder 文档讨论过 `spawn`、`goto` Promise、`setGoal(null)`、Microsoft 登录缓存和本地 NDJSON。这些是未测试的候选设计，不是 Agent-LuoTianyi 的历史实现，也不适用于当前 Forge 真实客户端路线。

## 保留价值与边界

- 可保留的原则：结构化白名单动作、事实与不可信文本分离、关联回执、取消不是回滚、不确定结果不得盲目重试
- 不再采用的假设：Node 桥接器、Mineflayer 登录机器人、pathfinder 行动模型、以 stdio/NDJSON 为既定传输
- 若未来重新评估 Mineflayer，必须作为新的架构决策重新验证维护状态、许可证、认证、版本兼容和安全边界，不得把本文视为批准

来源详情见[来源记录](provenance.md)。
