# Forge 身体与桥接草案

> 未实现。主路线是公开自定义 Forge 1.20.1 服务端的常驻身体，不是 Mineflayer 运行时依赖。

此桥接层计划负责单一持久实体、授权感知、有限白名单动作、回执与桥接会话；不负责洛天依的认知、记忆、关系、规划或 Stage 调度。Forge 与 AgentLuo 可为独立认证进程，游戏线程不得等待 IO/LLM；最终传输和连接方向未定。

先验证 `ServerPlayer` 派生方案及饥饿/Diet 的玩家语义；`FakePlayer` 不保证完整性。身体生命周期、保护生存、断连保存/移除及恢复对账以[产品需求](../../docs/product-spec.md)和[身体验证](../../docs/body-validation.md)为准。高风险世界修改默认禁用。
