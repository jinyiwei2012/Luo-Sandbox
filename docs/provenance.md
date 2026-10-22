# 来源、证据与决策边界

> 本文区分历史恢复事实、外部研究事实、用户确认的当前方向与尚未决定事项。仓库仍是文档草案，不是可运行组件。

## 核查基线

- 来源仓库：[SheepLiu712/Agent-LuoTianyi](https://github.com/SheepLiu712/Agent-LuoTianyi)
- 本地核查提交：`fa4dff0e4bff33ff156e7e9c992fd61dc17ad13b`
- 核查范围：当前 `docs/**/*.md`、`server/docs/**/*.md`、`README*`、`CONTEXT.md`，指定项目计划书，以及相关 Git 历史；未读取运行数据、资源目录、凭据、`.git/config`、依赖目录或无关磁盘位置
- 原仓库许可证：标准 MIT，`LICENSE:1-21`，版权声明为 `Copyright (c) 2026 Agent-LuoTianyi Authors`

## 历史恢复事实

1. 历史提交 `4812240c5fefcb939a0b31e6e9dbb9b1920c2bcc`（“整合发布v0.2.1正式版本 (#18)”）的 `README.md:157` 将 `MineCraft-LuoTianyi` 列为“可能成为独立项目”的想法，内容是使用 Mineflayer 让机器人在 Minecraft 中活动并与用户一起游玩。这是恢复到的唯一明确 Mineflayer 产品意图。
2. 同一提交新增 `mineflayer` gitlink；当前索引仍记录模式 `160000`、对象 `03eba44f3e9cb93a0f0bf69a75938246e174dc6f`。当前及该提交均没有可用 `.gitmodules` 映射，本地对象库也没有该目标提交，工作树目录为空，因此无法从现有克隆可靠恢复子模块 URL、代码、设计或运行状态。
3. 当前项目计划书 `docs/项目说明/项目计划书/AgentLuo项目计划书.md:111-127` 描述持续状态箱庭，`:221-231` 描述“理解—调用—观察结果—继续回复”及工具风险分级，`:267-289` 要求先做可验证最小闭环。
4. 当前代码地图 `docs/代码地图.md:145-157,203-213` 记录 Agent、Stage、World 的职责和 WorldStage 链路；这可供集成设计参考，但不证明 Minecraft 端口已实现。

限定路径和 Git `-G` 搜索没有恢复 Mineflayer 协议、模块设计、测试或代码。历史只能证明“曾提出 Mineflayer/Minecraft 独立项目想法”和孤立 gitlink，不能证明目标内容、完成度或许可证。早期通用世界端口、Node/NDJSON 方案属于后来草案，不是恢复事实。

## 当前用户确认（取代早期通用/Mineflayer 与服务端驻留体主路线）

- 当前主路线是公开自定义 Forge 1.20.1 多人服务器中的真实客户端控制器，配合服务端伴随模组的安全权威和回执；Mineflayer、`ServerPlayer` 派生与 `FakePlayer` 仅保留为历史或替代研究
- 同一位天依深度绑定 Agent-LuoTianyi；人格、关系、记忆、接受/拒绝和决策归上游，本仓库拥有 Minecraft 具身与接口单一来源
- 首个里程碑是稳定连接、观察、公开聊天、有限移动/朝向和真实回执，不是完整自治；允许有限无人值守主动性
- 固定离线游戏身份仅绑定一个独立认证的控制会话、单维度管理员有限区域；保护生存保留真实饥饿/营养，致命保护由服务端另设
- 玩家请求可由天依依据人格和记忆接受、拒绝或协商；玩笑仅为后续 opt-in，首期不实施
- 公开经历可跨客户端进入上游；额外玩家状态须可撤销同意，不得公开。上游当前缺少完整 source/audience 隐私过滤，必须先补足
- 本地独立进程必须认证；传输、连接方向、具体认证机制、远程与私聊接口尚未定案。离线昵称/UUID 不构成控制授权或所有权声明。详见[产品需求](product-spec.md)、[架构](architecture.md)、[接口草案](interface-draft.md)和[感知与隐私](perception-and-privacy.md)
- Agent-LuoTianyi 适配器已获准纳入协调交付；其源码仍归上游仓库，并须以该仓库独立原子提交/PR和测试实现。当前未开始，待实际接缝确认
- 服务端直接权威路径与小范围服务端源码改动已获实现授权，以降低中继或适配复杂度；自有 Forge 伴随模组与源码改动之间须按复杂度、兼容性证据和可复现性选择，不预先决定 core fork 技术。源码改动须记录来源、固定版本、补丁与许可证，并通过对应上游构建/测试；这不批准游戏启动、EULA、live trial、世界动作或二进制分发

同日通过已认证 GitHub API `GET /repos/SheepLiu712/Agent-LuoTianyi/collaborators/jinyiwei2012/permission` 核实账号 `jinyiwei2012` 的 `permission` 与 `role_name` 均为 `write`；这不是 GitHub 的 `maintain` 角色，也不替代 MIT 要求。

## 外部研究事实与限制

- Diet 1.20.x、Farmer's Delight 1.20 和 Forge 1.20.1 的公开源码/Javadoc只支持制定验证假设，不证明真实客户端与服务端伴随模组的组合兼容；证据与限制见[身体验证](body-validation.md)
- 目标模组名称、精确 artifact、版本组合及离线保存行为尚未锁定；“煨茶酝露”疑为 HerbalBrews，仍须以实际 jar 核对
- 已讨论的 GameScaling 仅获准记录为可选动作提议器，不是主路线、验证器或实现批准。研究来源为其官方 README：[GameScaling README](https://github.com/THUSI-Lab/GameScaling#readme)。官方 README 声明代码与权重采用 Apache-2.0；各检查点的模型卡与许可证尚未独立核验，进入专项试点前须补齐

## 尚未决定或尚未验证

- 客户端/服务端 side 目录组织与单/多 JAR 打包、普通玩家兼容程度、加载机制、精确模组/构建、接口字段/端点及运行参数
- 破坏性动作、生产批准、公开服扩容条件和软预算阈值；预算目标是降活跃度而非断连
- GameScaling 的版本/许可证/模型卡、Forge 与 GUI 兼容性、帧感知摘要及有界目标可靠性；它不是主路线，仍须单独批准和测试
- 以上均不得从草案、源码阅读或未来 2 小时邀请测试中提前宣称完成

## MIT 归属与内容边界

Luo-Sandbox 使用自己的 MIT 许可证；上游完整 MIT 文本保留在 [NOTICE](../NOTICE.md)。当前没有复制 Agent-LuoTianyi 或 Mineflayer 的运行时代码、资源、配置、数据或 Git 历史。未来导入须记录来源路径、精确提交、日期、修改和许可证。Minecraft 文件、品牌内容及角色形象、声音、模型或数据不由本仓库 MIT 授权。
