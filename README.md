# Luo-Sandbox

> 草案，待人工评审。Luo-Sandbox 是面向独立沙盒世界 AI Agent 的架构蓝图，不是可运行实现。

项目目标是定义可迁移的观察→决策→行动→反馈闭环。首个候选世界端口是 Minecraft Java 与 Mineflayer；核心契约不绑定游戏或引擎，后续端口可接入其他受授权的沙盒世界。

当前仓库不安装依赖、不提供服务入口、不含游戏资源，也不提供启动命令。唯一自动检查为：

```text
python scripts/check_blueprint.py
```

## 范围和状态

- 这是公开 MIT 仓库 `jinyiwei2012/Luo-Sandbox` 的设计材料
- 所有组件、消息和里程碑均为提案，尚未实现或验证
- LLM 只能收到经契约允许的结构化输入，不能执行任意 JavaScript、shell 命令或游戏命令
- Minecraft 品牌、游戏内容、游戏文件及角色资源不由本项目 MIT 许可证授权，详见 [NOTICE](NOTICE.md)

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
├─ server/                    未来 Python 编排器的边界说明
├─ bridge/mineflayer/         未来 Node 世界桥接器的边界说明
├─ docs/                      架构、路线图与来源记录
├─ tests/                     未来验证范围说明
└─ scripts/check_blueprint.py 仓库结构检查
```

## 设计原则

- 世界事实与不可信游戏文本分离；事实使用快照或增量表达，不把聊天文本当作系统事实
- 每个 actor 同时最多一个会改变世界的行动；读取、观察与取消应有独立语义
- 行动由 `action_id`、`world_id`、`agent_id`、`session_epoch` 共同约束，结果必须关联原请求
- 终态包括成功、失败、超时、取消、断连与 `unknown`；对可能已执行的变更绝不盲目重试
- 队列必须有界；观察快照可合并，行动回执必须可靠传递

详细的取舍与状态机见 [总体架构](docs/architecture.md)。
