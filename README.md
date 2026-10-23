# Luo-Sandbox

> 已确认的产品需求蓝图；离线契约、基础安全与禁用执行的传输证据已有受限实现，真实 Forge runtime 与逐项技术验证仍待完成。

Luo-Sandbox 让**同一位洛天依**在 Minecraft 中生活。它深度绑定 [Agent-LuoTianyi](https://github.com/SheepLiu712/Agent-LuoTianyi)：人格、认知、记忆、关系、接受/拒绝决策、技能与 Stage 均以上游为唯一来源；本仓库规划真实客户端环境、服务端安全伴随模组、授权感知、类型化执行、权威回执与连接桥接。

目标环境是公开的自定义 Forge 1.20.1 服务器：主路线是受项目控制、离线游戏登录的真实 Forge 客户端，配合服务端伴随模组执行安全权威与回执；Mineflayer 与服务端驻留 `ServerPlayer`/`FakePlayer` 仅保留历史或对照资料，不是当前依赖或选路门。客户端与服务端可共用源代码并按 side 隔离，最终单/多 JAR 打包尚未决定。本仓库已有最小 Forge 构建骨架，但没有可运行控制链路。

## 产品边界

- 所有玩家可提出请求；洛天依可依人格、关系与记忆接受、拒绝或协商，普通管理员角色扮演请求不构成强制服从
- 首个闭环是稳定观察→上游决策→公开聊天/有限朝向与移动→如实反馈，不是全自动生存、耕作、烹饪、建造、战斗或容器修改
- 可选的无人值守有限主动性仅限授权安全区；长期可恢复玩笑须显式 opt-in 且可撤销，首阶段不实际执行
- LLM 不能获得原始命令、Java、NBT、反射、OP、`give`、天气控制或其他任意执行能力
- Minecraft 内容、品牌、游戏文件与角色资源不由本仓库 MIT 许可证授权，见 [NOTICE](NOTICE.md)

## 文档

1. [已批准产品需求](docs/product-spec.md)
2. [真实客户端与服务端伴随架构](docs/architecture.md)
3. [接口语义草案](docs/interface-draft.md)
4. [感知与隐私矩阵](docs/perception-and-privacy.md)
5. [路线图与准入门槛](docs/roadmap.md)
6. [身体可行性验证](docs/body-validation.md)、[来源与边界](docs/provenance.md)、[Mineflayer 历史蓝图](docs/mineflayer-blueprint.md)
7. [集成实施状态与证据边界](docs/integration-status.md)
8. [当前工作计划（非批准规格）](docs/work-plan.md)
9. [P0 前置验证准备单](docs/validation-preflight.md)

## 复用策略

未来可按需复制或改造 Agent-LuoTianyi 实现，但不形成第二套“脑”。每个导入模块必须保留适用 MIT 版权与许可证，并记录来源路径、提交和修改；保持最少重复所有权、共享改动优先上游，并具备更新与兼容策略。当前尚未复制实现代码。
