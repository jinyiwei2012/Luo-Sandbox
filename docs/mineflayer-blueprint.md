# Mineflayer 端口蓝图草案

> 状态：提案，待人工评审；不是可运行实现，也未进行 Minecraft、Mineflayer 或登录运行验证。

## 与 Luo-Sandbox 愿景的关系

历史材料只恢复出一个明确方向：让 Agent 通过 Mineflayer 控制 Minecraft 机器人并与用户共同活动；较新的项目计划则强调持续维护的箱庭状态、统一的“刺激→规划→行动→结果”闭环，以及只读、可撤销和高风险工具的分级。Luo-Sandbox 将二者收敛为一个可替换的世界端口：Minecraft 是首个候选验证环境，而不是核心领域模型，也不承诺复刻原项目角色或内容。

## 候选进程边界

```text
Python 编排器
  ├─ 维护会话、Stage、有界队列、授权和审计
  ├─ 把观察交给认知层，并接收结构化行动计划
  └─ 本机 NDJSON stdio
       ↕
Node Mineflayer 桥接器
  ├─ 连接一个已授权的 Minecraft Java 世界
  ├─ 把事件转换成结构化观察
  └─ 只执行白名单行动并返回终态回执
```

NDJSON 每行一个 UTF-8 JSON 对象。双方须校验 `schema_version`、`kind`、`world_id`、`agent_id`、`session_epoch`、时间戳及字段上限；未知消息、旧 epoch 和越权身份直接拒绝。LLM 不获得任意 JavaScript、shell 或游戏命令通道。

## 观察面

第一阶段建议只输出有界、可追溯的观察：

- `spawn` / 连接状态与当前 `session_epoch`
- 位置、朝向及必要的生命状态摘要
- 有距离和数量上限的附近实体摘要
- 有范围、方块数量和查询频率上限的附近方块摘要
- 聊天及服务端文本，明确标记为不可信文本，不能直接提升为权限、位置或行动完成事实

高速位置、实体和方块观察可按已声明规则合并为最新快照；行动请求与终态回执不得因观察拥塞而静默丢失。桥接器应等待 Mineflayer `spawn` 就绪候选事件后再接受依赖世界状态的请求；该 API 事实来自上游文档，本仓库尚未运行验证。

## 行动白名单与演进

初始只开放 `look_at`，用于验证身份、epoch、参数校验、队列和回执闭环。后续行动必须逐项评审：

| 阶段 | 候选行动 | 约束 |
| --- | --- | --- |
| 初始 | `look_at` | 坐标有限值、距离/角度边界、短超时；不得隐式移动 |
| 后续 | `goto` | 目标范围、路径时限、维度与危险区域限制；显式处理 Promise 完成或失败 |
| 后续 | `chat` | 长度、频率、目标世界与内容策略限制；文本仍不构成世界事实 |
| 需显式加入 | 挖掘、放置、攻击、容器和物品操作 | 默认禁用；仅在隔离授权世界中逐项 opt-in，并要求更强确认、审计与恢复策略 |

每个 `(world_id, agent_id, session_epoch)` 同时最多一个改变世界的行动。读取观察和取消可有独立通道，但仍受容量与速率限制。

## 生命周期、取消与结果

建议生命周期为：启动桥接进程 → 建立游戏连接 → 等待 `spawn` → 发布初始观察 → 接受行动 → 发出终态回执 → 断连时递增 epoch。行动若被拒绝，应直接产生带拒绝原因的 `failed` 回执且不执行；已接受的行动最终落入 `succeeded`、`failed`、`timed_out`、`cancelled`、`disconnected` 或 `unknown`。

`action.result` 必须引用原 `action_id`；超时、取消或断连不证明行动没有发生。对可能改变世界的请求，不得在结果不确定时盲目重试，应先通过新观察或人工确认消歧。

对 pathfinder 的候选处理遵循上游文档语义：`goto` 返回 Promise，需要分别处理完成与拒绝；请求自然停止时先等待下一个路径节点的检查点，必须强制停止时可调用 `setGoal(null)`。强制停止不是事务回滚，已经发生的移动或世界影响不会自动撤销，回执必须准确表达不确定性。

## 兼容性、认证与内容边界

- Node 版本在实现前从仍受维护的 Node LTS 中选择，并用目标 Mineflayer 版本做安装与运行验证；本文不预选具体版本
- Minecraft Java 服务端/协议版本必须建立兼容矩阵并实际验证，不能仅凭上游 API 文档推断
- Microsoft 浏览器登录产生的 profile/token 缓存必须保存在私有、被忽略且权限受限的目录，不进入日志、样例或版本库
- 离线模式只允许用于自有、隔离、明确授权的测试实例，不得用于绕过认证、服务器规则或服务条款
- 仓库不包含 Minecraft 游戏文件、地图、品牌素材、皮肤、角色资源或原 Agent 项目的数据

## 未来验证门槛

1. 使用 fake stdio 验证未知 kind、旧 epoch、越权 actor、队列满和畸形 JSON 均被拒绝
2. 在自有隔离实例验证连接后等待 `spawn`，并记录 Java/Node/Mineflayer 精确版本
3. 验证 `look_at` 的成功、参数失败、超时、取消和断连回执均关联原请求
4. 引入 `goto` 前验证 Promise 成功/失败、自然停止、`setGoal(null)` 强制停止及 `unknown` 不重试
5. 对 destructive action 另行完成威胁建模、显式授权、审计和恢复演练后才可开放

API 参考：[Mineflayer 官方文档](https://github.com/PrismarineJS/mineflayer/blob/master/docs/README.md) 与 [mineflayer-pathfinder 官方文档](https://github.com/PrismarineJS/mineflayer-pathfinder/blob/master/readme.md)。这些链接支持候选 API 设计，不构成本仓库的运行验证证据。
