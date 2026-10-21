# 来源、恢复结果与许可边界

> 本文记录 2026-10-21 的只读历史核查。Luo-Sandbox 当前只是待评审蓝图，不是从原仓库拆出的可运行组件。

## 核查基线

- 来源仓库：[SheepLiu712/Agent-LuoTianyi](https://github.com/SheepLiu712/Agent-LuoTianyi)
- 本地核查提交：`fa4dff0e4bff33ff156e7e9c992fd61dc17ad13b`
- 核查范围：当前 `docs/**/*.md`、`server/docs/**/*.md`、`README*`、`CONTEXT.md`，指定项目计划书，以及相关 Git 历史；未读取运行数据、资源目录、凭据、`.git/config`、依赖目录或无关磁盘位置
- 原仓库许可证：标准 MIT，`LICENSE:1-21`，版权声明为 `Copyright (c) 2026 Agent-LuoTianyi Authors`

## 可恢复的直接证据

1. 历史提交 `4812240c5fefcb939a0b31e6e9dbb9b1920c2bcc`（“整合发布v0.2.1正式版本 (#18)”）的 `README.md:157` 将 `MineCraft-LuoTianyi` 列为“可能成为独立项目”的想法，内容是使用 Mineflayer 让机器人在 Minecraft 中活动并与用户一起游玩。这是恢复到的唯一明确 Mineflayer 产品意图。
2. 同一提交新增 `mineflayer` gitlink；当前索引仍记录模式 `160000`、对象 `03eba44f3e9cb93a0f0bf69a75938246e174dc6f`。当前及该提交均没有可用 `.gitmodules` 映射，本地对象库也没有该目标提交，工作树目录为空，因此无法从现有克隆可靠恢复子模块 URL、代码、设计或运行状态。
3. 当前项目计划书 `docs/项目说明/项目计划书/AgentLuo项目计划书.md:111-127` 说明箱庭应持续维护状态，由 LLM 理解状态、规划行动并表达经历，而非临时编造故事；`:221-231` 提出连续的“理解—调用—观察结果—继续回复”链路、不同类型刺激、限流合并及工具风险分级；`:267-289` 要求从可验证最小闭环逐步建设箱庭。
4. 当前代码地图 `docs/代码地图.md:7-15`、`:145-157`、`:203-213` 记录了服务端分层和 WorldStage 世界交互链，可作为职责分离的架构参考，但不证明 Minecraft 端口曾实现。

## 搜索结果与可恢复性限制

当前限定路径中没有 `Mineflayer` 或 `Minecraft` 命中；命中的“沙盒/箱庭”内容来自上述项目计划书。Git `-G` 历史搜索恢复了历史 README 条目，另有近期流程文档提及孤立 gitlink 导致 checkout 问题，但没有恢复出 Mineflayer 的协议、模块设计、测试或实现。`git show 4812240c` 只证明 gitlink 与一句计划同时出现，不能证明其目标仓库内容、完成度或许可证状态。

因此，本蓝图对 Python 编排器、Node 本地 NDJSON 桥接器、观察模型、行动白名单、epoch、回执、取消和无盲重试等细节均是基于现有项目架构原则重新提出的设计，不冒充“找回的旧实现”。历史事实只能支持“曾计划建立独立 Mineflayer/Minecraft 项目”这一有限结论。

## 采用、调整与暂缓

- **采用**：独立项目方向；Agent 在持续世界中观察、规划、行动并与用户共同活动；最小闭环和风险分级原则
- **调整**：将特定角色计划抽象为不绑定角色或游戏的 Luo-Sandbox 契约；以 Python 编排 + Node 世界桥接的受限本地协议代替未知旧实现
- **暂缓**：角色人格、记忆和素材集成；多 actor 社交；破坏性动作；生产服务器接入；任何登录、Java 版本和协议兼容承诺

## 外部 API 参考

- [PrismarineJS / mineflayer 文档](https://github.com/PrismarineJS/mineflayer/blob/master/docs/README.md)
- [PrismarineJS / mineflayer-pathfinder 文档](https://github.com/PrismarineJS/mineflayer-pathfinder/blob/master/readme.md)

两项目的仓库许可证均为 MIT。本文引用其公开文档来描述 `spawn`、`goto` Promise 和 `setGoal(null)` 等候选 API 语义；没有安装依赖、复制代码或完成运行测试，最终实现必须重新核对固定版本的文档与许可证。

## MIT 归属与内容边界

Luo-Sandbox 使用自己的 MIT 许可证。为谨慎标示架构参考，原 Agent-LuoTianyi 的版权及 MIT 文本保留在 [NOTICE](../NOTICE.md)。本仓库没有复制原项目的实现代码、资源、配置、用户数据或 Git 历史；也没有复制 Mineflayer 项目代码。Minecraft 游戏文件、地图、品牌内容，以及任何角色形象、声音、模型或数据均不由 Luo-Sandbox 的 MIT 许可证授权。
