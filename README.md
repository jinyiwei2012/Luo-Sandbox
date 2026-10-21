# Luo-Sandbox

> 草案，待人工评审。Luo-Sandbox 是让洛天依在 Minecraft 中生活的具身接入蓝图，不是可运行实现。

它是 [Agent-LuoTianyi](https://github.com/SheepLiu712/Agent-LuoTianyi) 的 Minecraft 专用伴随集成项目。洛天依的人格、认知、对话、记忆、规划/技能与交互生命周期仍由上游项目驱动；本仓库只计划承担 Minecraft Java/Mineflayer 的连接、受限世界感知、移动与交互执行、行动回执和桥接会话生命周期。

当前仓库不安装依赖、不提供服务入口、不含游戏资源，也不提供启动命令。唯一自动检查为：

```text
python scripts/check_blueprint.py
```

## 范围和状态

- 这是公开 MIT 仓库 `jinyiwei2012/Luo-Sandbox` 的设计材料
- 用户已确认产品意图；全部接口、组件和里程碑细节仍为提案，尚未实现或验证
- LLM 只能收到经契约允许的结构化输入，不能执行任意 JavaScript、shell 命令或游戏命令
- Minecraft 品牌、游戏内容、游戏文件及角色资源不由本项目 MIT 许可证授权，详见 [NOTICE](NOTICE.md)

## 复用策略

未来深度集成可按需要复制或改造 Agent-LuoTianyi 的实现，但当前交付仍仅为蓝图，尚未复制实现代码。每个未来导入模块应保留上游 MIT 版权与许可证，记录来源路径、提交和修改；优先保持最少重复所有权，共享改动优先回馈上游，并为导入部分制定更新与兼容策略。详见[来源与边界](docs/provenance.md)。

## 阅读顺序

1. [总体架构](docs/architecture.md)
2. [Mineflayer 端口蓝图](docs/mineflayer-blueprint.md)
3. [路线图](docs/roadmap.md)
4. [来源与边界](docs/provenance.md)
5. [许可证与通知](LICENSE) / [NOTICE](NOTICE.md)

## 目录骨架

```text
.
├─ contracts/                 跨进程消息描述和示例
│  └─ examples/               仅作说明的 JSON 样例
├─ server/                    上游集成侧边界说明，不是新的认知服务
├─ bridge/mineflayer/         未来 Minecraft/Mineflayer 桥接器说明
├─ docs/                      架构、路线图与来源记录
├─ tests/                     未来验证范围说明
└─ scripts/check_blueprint.py 仓库结构检查
```

## 设计原则

- 世界事实与不可信游戏文本分离；事实使用快照或增量表达，不把聊天文本当作系统事实
- 同一洛天依运行时映射同时最多一个会改变世界的行动；读取、观察与取消应有独立语义
- 行动由 `action_id`、`world_id`、`agent_id`、`session_epoch` 共同约束，结果必须关联原请求
- 终态包括成功、失败、超时、取消、断连与 `unknown`；对可能已执行的变更绝不盲目重试
- `look_at` 仅是早期传输冒烟检查；目标是受约束的移动、探索、聊天、收集材料、建造与日常习惯闭环
- 队列必须有界；观察快照可合并，行动回执必须可靠传递

详细的取舍与状态机见 [总体架构](docs/architecture.md)。
