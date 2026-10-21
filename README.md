# Luo-Sandbox

> 已确认的产品需求蓝图，仍待实现与逐项技术验证。

Luo-Sandbox 让**同一位洛天依**在 Minecraft 中生活。它深度绑定 [Agent-LuoTianyi](https://github.com/SheepLiu712/Agent-LuoTianyi)：人格、认知、记忆、关系、接受/拒绝决策、技能与 Stage 均以上游为唯一来源；本仓库规划 Minecraft 身体、授权感知、安全执行、回执与连接桥接。

目标环境是公开的自定义 Forge 1.20.1 服务器，可修改服务端并要求客户端安装展示模组。服务端驻留实体是主路线；Mineflayer 仅为历史候选，不是当前依赖。本仓库目前是文档，不提供依赖、入口或启动命令。

## 产品边界

- 所有玩家可提出请求；洛天依可依人格、关系与记忆接受、拒绝或协商，普通管理员角色扮演请求不构成强制服从
- 首个闭环是稳定观察→上游决策→公开聊天/有限朝向与移动→如实反馈，不是全自动生存、耕作、烹饪、建造、战斗或容器修改
- 可选的无人值守有限主动性仅限授权安全区；长期可恢复玩笑须显式 opt-in 且可撤销，首阶段不实际执行
- LLM 不能获得原始命令、Java、NBT、反射、OP、`give`、天气控制或其他任意执行能力
- Minecraft 内容、品牌、游戏文件与角色资源不由本仓库 MIT 许可证授权，见 [NOTICE](NOTICE.md)

## 文档

1. [已批准产品需求](docs/product-spec.md)
2. [集成架构](docs/architecture.md)
3. [接口语义草案](docs/interface-draft.md)
4. [感知与隐私矩阵](docs/perception-and-privacy.md)
5. [路线图与准入门槛](docs/roadmap.md)
6. [身体可行性验证](docs/body-validation.md)、[来源与边界](docs/provenance.md)、[Mineflayer 历史蓝图](docs/mineflayer-blueprint.md)

## 复用策略

未来可按需复制或改造 Agent-LuoTianyi 实现，但不形成第二套“脑”。每个导入模块必须保留适用 MIT 版权与许可证，并记录来源路径、提交和修改；保持最少重复所有权、共享改动优先上游，并具备更新与兼容策略。当前尚未复制实现代码。
