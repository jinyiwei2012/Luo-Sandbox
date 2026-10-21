# Forge 身体可行性验证门槛

> 这是未来限时 runtime spike 的评审清单，不是实现任务、测试结果或 `ServerPlayer` 采用决定。未通过时回到人工决策，不得静默切换为 `FakePlayer`、普通 Mob 或近似营养模型。

## 已知源码事实与证据限制

- [Diet 1.20.x](https://github.com/illusivesoulworks/diet/tree/1.20.x) 的 capability 附着对象限定为 `Player`；其更新链涉及物品使用完成事件、`PlayerTickEvent`、`FoodData` 与营养刷新。此事实支持“玩家式身体值得验证”，不证明自建 `ServerPlayer` 会被完整且恰好一次地驱动
- [Farmer's Delight 1.20](https://github.com/vectorwing/FarmersDelight/tree/1.20) 的 Nourishment 等效果包含玩家特定路径；这不证明所有食物、饮料、容器返还和附属模组都兼容无客户端身体
- [Forge 1.20.1 FakePlayer Javadoc](https://lexxie.dev/forge/1.20.1/net/minecraftforge/common/util/FakePlayer.html) 能确认 `FakePlayer` 覆盖 `tick`，但 Javadoc 不提供方法体。NeoForge 1.20.6 的实现不能证明 Forge 1.20.1 方法体相同；必须检查实际 Forge jar/source 并运行验证
- Forge 没有在上述证据中承诺“派生 `ServerPlayer` 是完整 NPC API”。`FakePlayer` 只能作为受限交互上下文候选，不能预设为常驻身体

## spike 前锁定输入

记录 Forge、Minecraft、Java、模组 loader 和每个目标 jar 的精确版本/哈希。目标包括 Farmer's Delight、Neapolitan、Diet、Serene Seasons、Macaw's Furniture、Storage Drawers、JEI、AppleSkin、小地图及 Forge 适配优化模组；“煨茶酝露”须先确认实际 artifact，不能假定就是 HerbalBrews。Sodium 不属于默认 Forge 方案。

## 候选与硬约束

首选验证受控 `ServerPlayer` 派生身体：固定 UUID，任意时刻零或一个活动实体，只在管理员批准的单一维度和有限区域。必须保留普通玩家饥饿、食物、营养、效果和背包语义；致命保护独立实现，不使用创造模式、永久满饥饿或持续治疗替代。

普通 Mob 只能作为不同产品语义的备选，不能宣称等价。若玩家式营养不可可靠实现，结论应为“身体决策失败/待人工决定”，而不是直接修改 `FoodData` 或自建近似 Diet 数值。

## 必须证明的运行行为

1. **tick 与注册**：身体只被 tick 一次；临时缺席时不制造假登录/克隆；`PlayerList`、连接表、tab 列表和维度实体表无重复或幽灵项
2. **正常进食路径**：通过普通物品使用完成，而非直接改 `FoodData`；逐项核对消耗数量、饥饿/饱和度、效果、容器返还和取消行为
3. **营养路径**：Diet capability 唯一附着且无重复刷新/属性；只对实际声明营养的食物断言变化。缺少 `FoodProperties` 的物品不应凭空增加营养，也不得假设所有茶饮都会计入 Diet
4. **模组交互**：对目标食物、季节、家具、抽屉和服务端能力做最小真实操作；JEI、AppleSkin、小地图等客户端展示不得被误当服务端语义依赖
5. **持久身份**：保存—移除—重启—恢复后仍是同一 UUID、一个身体，并保留位置、维度、生命、背包、效果及经验证可持久化的 Diet 状态
6. **断连语义**：仅 AgentLuo heartbeat 会话确认失效才停止、保存、移除；不死亡、不掉落。LLM 超时不触发断连。正常关闭先协商，最多 30 秒后强制；紧急关闭立即执行安全保存/移除
7. **恢复诚实性**：重启后重新认证、重新观察和对账；旧 epoch 不驱动新动作，不盲目重放，不生成重复回执或实体
8. **离线行为**：逐模组记录真实保存与离线演进；不得承诺“全部冻结”。可选状态、区域或存储增强仅在已授权且适配器可用时启用，否则明确降级为本地新鲜数据或不可用

## 负面与故障用例

- 无客户端连接、维度切换、区块卸载/重载、服务端重启、模组事件重复、动作中断和 heartbeat 丢失
- 食物无营养声明、物品使用失败、容器空间不足、效果冲突、Diet capability 缺失或反序列化失败
- 禁止出现两个相同 UUID 身体、重复属性、重复食物收益、死亡掉落、tab/PlayerList 污染、未授权越区或以缓存陈旧数据继续行动

## 出口判定

spike 必须产出版本矩阵、可复现实验步骤、逐项证据及失败清单。只有“真实玩家对照”与候选身体在批准范围内行为一致，且上述身份、tick、营养、保存和清理不变量成立，才可提交最终身体选型供人工批准；本阶段不作生产批准。
