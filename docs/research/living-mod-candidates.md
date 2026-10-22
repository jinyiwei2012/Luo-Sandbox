# 生活语义候选模组（Forge 1.20.1）

> 记录日期：2026-10-22。以下均为来源研究结论，未下载、未检查 jar、未启动或测试。它们用于协作选择最小**候选验证包**，不是生产模组包、兼容性结论或 runtime 批准；最终 Forge 构建由技术候选研究另行冻结。

## 最小候选：先验证饮食与食物语义

| 模组 | 建议锁定工件 | 来源与许可证 | 已知元数据/待核验 |
| --- | --- | --- | --- |
| Diet | `diet-forge-2.1.1+1.20.1.jar` | [Modrinth 版本 oHxMouff](https://api.modrinth.com/v2/version/oHxMouff)；`LGPL-3.0-or-later` | 元数据未列外部必需依赖；研究称 SpectreLib 为 jar-in-jar，须检查实际 jar 后确认。当前 [1.20.x 源码分支](https://github.com/TheIllusiveC4/Diet/tree/1.20.x) 不等同已发布 jar |
| Farmer's Delight | `FarmersDelight-1.20.1-1.3.4.jar` | [Modrinth 版本 SiIpcZzM](https://api.modrinth.com/v2/version/SiIpcZzM)；`MIT` | 元数据无外部必需模组，声明 Forge `>=47.1.0`；项目元数据为客户端和服务端均需要。1.3.x 的 Nourishment 变化及 Comfort 停止维护均须以锁定 jar 验证；当前 [1.20 源码分支](https://github.com/vectorwing/FarmersDelight/tree/1.20) 不等同已发布 jar |

该组合只验证玩家式食物、营养与持久化假设，不代表完整生活玩法，也不冻结 Forge `47.4.28` 或任何其他 Forge 构建。

### 最小验证用例

1. 原版食物：记录饥饿与饱和度
2. Farmer's Delight 食物、碗装/饮品与容器返还：记录消耗、效果与返还物
3. Nourishment：以锁定 jar 的实际行为为准
4. Diet：有营养声明食物的变化；无 `FoodProperties` 物品不应凭空获得营养
5. 取消、背包已满、保存—恢复与 tick：检查容器返还、状态恢复和无重复收益/重复 tick

所有用例仍受隔离世界、指定测试物品和单独授权约束；不开放通用库存、容器或生产动作。

## 后续候选（不纳入最小包）

| 模组 | 候选版本与依赖 | 研究结论与后续验证 |
| --- | --- | --- |
| Neapolitan | [5.1.0 / AC9UEwPQ](https://api.modrinth.com/v2/version/AC9UEwPQ)；[Blueprint 7.1.5 / ZQKDh6gd](https://api.modrinth.com/v2/version/ZQKDh6gd)，要求 `>=7.1.0` | Abnormals License；作为后续食物扩展候选，先单独检查依赖和 jar |
| HerbalBrews | [1.0.12 / qKfEj2BA](https://api.modrinth.com/v2/version/qKfEj2BA)；[Architectury 9.2.14 / 1MKTLiiG](https://api.modrinth.com/v2/version/1MKTLiiG)，要求 `>=9.1.12` | 代码 `MIT`、资源 ARR；Diet 营养关系未知，须用锁定 jar 测试。参考 [1.20.1 源码分支](https://github.com/Let-s-Do-Collection/HerbalBrews/tree/1.20.1)，不等同发布物 |
| Macaw's Furniture | [3.4.1 / mvGf4LNK](https://api.modrinth.com/v2/version/mvGf4LNK)，无依赖 | ARR；坐下语义另行测试，不能自动获得容器操作权限 |
| Handcrafted | [3.0.6 / N7wZwOFy](https://api.modrinth.com/v2/version/N7wZwOFy)；[ResourcefulLib 2.1.29 / OhsHaCcW](https://api.modrinth.com/v2/version/OhsHaCcW) | Terrarium：代码 MIT、资源 ARR；优先级低于饮食验证 |
| Farmer's Respite | [kucmCxP0](https://api.modrinth.com/v2/version/kucmCxP0)，元数据标记 `1.20.1-2.1` | 文件名 `2.1.2` 与元数据不一致；研究还发现上游不稳定/咖啡世界生成问题，暂缓，待独立核验 |

## 获取与归档边界

- 下载只可使用官方或信誉良好的作者来源；对每个工件记录版本 API URL、文件名、下载时间和 SHA-256
- 必须检查实际 jar 的依赖声明与内嵌库；元数据与源码分支仅用于提出验证假设
- 二进制不提交本仓库，保存至忽略的本地资源目录；许可证义务按各工件的权威许可证分别履行，不作统一的再分发结论
- 选择候选、冻结精确版本/依赖/配置 hash，以及启动任何 runtime，均须遵循 [P0 前置验证准备单](../validation-preflight.md) 的独立门槛
