# 人类参考基线：启动前清单与操作步骤

> 状态：**准备材料，尚未执行。** 本文落实维护者 2026-11-10 确认的纸面输入，并给出“仅人类基线的隔离启动”的启动前清单、世界准备、人类操作步骤和记录模板。本文不授予安装、启动、EULA、世界或试点批准；启动本身、EULA 接受与客户端获取方式须逐项另行确认。机器可读记录见 [试点输入记录](../tests/p0-pilot/pilot-inputs.json)，复核脚本见 `scripts/minecraft/verify_p0_pilot_inputs.py`。

## 1. 范围

- 目标：取得 P0 剩余三项只能靠真实启动获得的输入——模组生效配置 hash、实测离线 UUID 与 playerdata 行为、人类参考基线（S1–S4 的人类可操作部分）。
- 参与：仅一个 Forge 专用服务端加维护者手动操作的一个真实客户端；同机、仅回环。
- 不含：Agent/Hub、控制链路、受控身体登录、任何自动输入；S5–S7 属 P1。
- 模组：冻结的 Diet 与 Farmer's Delight；是否同时加载自有伴随模组未决，见 3.3。

## 2. 已确认输入（2026-11-10）

| 项目 | 取值 |
| --- | --- |
| 人类参考身份 | `LuoP0Human`，预测离线 UUID `ef578af7-17f1-34db-8d89-3eaa9f6c3c20`（未实测） |
| 受控身体身份 | `LuoP0Body`，预测离线 UUID `72212196-314f-382e-bb12-fcc3585e6d10`；本清单不登录 |
| 网络 | `127.0.0.1:25565`，仅回环 |
| 世界 | `p0-pilot-world`；仅主世界；超平坦、固定种子 `20261110`、不生成结构、关闭怪物生成、简单难度、生存模式 |
| 区域 | X/Z ∈ [−16, 16]，以 `max-world-size=16` 的原版边界兜底 |
| 数据包 | 无外置 |
| 启动输入 | `tests/p0-pilot/server.properties`，712 字节，SHA-256 `aaf21979aa36bfaf847337fdb6a10cf86245efc564587c1aae0ad2bfaf8c9890` |
| Java | Temurin `17.0.20.1+1` 指纹（见[验证资源清单](validation-resource-manifest.md)） |
| 责任人 | 授权、备份与恢复、人类操作均由维护者本人担任 |
| 有效期 | `2026-12-10` 或 P1 结论，先到为准 |

预测 UUID 按 `UUID.nameUUIDFromBytes(("OfflinePlayer:" + 昵称).getBytes(UTF_8))` 计算，静态依据是开发环境 mapped jar 中 `UUIDUtil` 的常量与方法引用。它未经登录实测；实测不一致即停，不得反过来修改预测值去贴合结果。

## 3. 启动前清单（任一项未满足即不启动）

### 3.1 只有维护者本人能做的同意

- [ ] 阅读并接受 Minecraft EULA；助手不代为接受，也不创建或修改 `eula.txt`。
- [ ] 决定客户端获取方式并承担相应许可责任；本文不提供启动器、二进制或认证绕过指引。
- [ ] 给出“仅人类基线的隔离启动”批准：范围为本清单，有效期同上。

### 3.2 环境与输入

- [ ] 复核脚本全部通过：`python scripts/minecraft/verify_p0_pilot_inputs.py --jdk-home C:\Java\jdk-17`，七项检查都须 PASS。
- [ ] JDK 指纹与记录一致；不一致即停。
- [ ] 内存：`2026-11-10T13:40Z` 观测总量 `16,502,599,680` bytes、可用 `1,117,241,344` bytes（约 1.04 GiB）。同机运行服务端与客户端前须先释放内存；可用下限随 JVM 参数另行确定（未定）。
- [ ] 磁盘：同时刻剩余 `74,031,087,616` bytes（约 68.9 GiB）。
- [ ] 端口 `25565` 在回环上未被占用（启动前的只读检查）。
- [ ] 备份目录就绪：`.local-resources/p0-pilot/backups/<UTC>/`，被 Git 忽略。

### 3.3 仍待决定的组装事项

| 事项 | 静态事实 | 待决定 |
| --- | --- | --- |
| 服务端安装 | 安装器 `install_profile.json` 的 36 个 libraries 中 7 个内嵌、29 个需联网；`version.json` 的 29 个中 2 个内嵌、27 个需联网；10 个处理器中 9 个适用服务端，含 `DOWNLOAD_MOJMAPS` | 是否批准在被忽略目录内执行安装器并联网补齐依赖 |
| 客户端 | 版本元数据声明资源 `651,728,646` bytes、88 个 libraries；仓库 Gradle 缓存两层目录内无 `assets`/`natives`，`bridge/forge/run` 不存在 | 由谁、以何种方式准备；与生产安装字节的差异如何记录 |
| 模组集合 | 自有伴随模组构建产物不在六项冻结工件内 | 基线是否只含 Diet 与 Farmer's Delight（纯普通玩家对照），还是同时加载伴随模组（会首次在真实运行时应用 Mixin） |
| JVM 参数 | 未定 | 内存与启动参数取值 |
| 动物与 NPC | 模板只关闭怪物生成（`spawn-monsters=false`），动物与 NPC 保持默认 | 接受为环境变量，或另行排除 |
| Forge 版本检查 | `FMLConfig` 的 `versionCheck` 默认 `true`（字节码常量证据） | 是否在启动时关闭，或断网运行；两种选择都须记入证据 |

## 4. 世界准备（获批后在服务端控制台执行，不使用游戏内 OP）

1. 首次启动前写入 `whitelist.json`，只含人类参考一条：`[{"uuid": "ef578af7-17f1-34db-8d89-3eaa9f6c3c20", "name": "LuoP0Human"}]`。理由是字节码显示 `whitelist add <昵称>` 会先按**小写**名字走档案仓库查询、之后才回退离线 UUID，可能产生对外请求，而且大小写与登录路径未必一致；预写文件可完全避开这条路径。
2. 首次启动生成的配置：列出 `config/`、`p0-pilot-world/serverconfig/`（如有）与生效 `server.properties`，记录相对路径、字节数与 SHA-256。**不手改任何模组配置。**
3. 逐键对比生效 `server.properties` 与启动输入：启动输入的 20 个键值必须一致；服务端补充的键按默认记录。
4. 记录世界边界：预期 X/Z ∈ [−16, 16]；不符则记录偏差并停下。
5. 停服并做第一次备份，见第 6 节。

## 5. 人类操作步骤（S1–S4 的人类可操作部分）

统一读数命令（服务端控制台）：`data get entity LuoP0Human foodLevel`、`foodSaturationLevel`、`foodExhaustionLevel`、`Health`、`ActiveEffects`、`Inventory`；Diet 读数尝试 `/diet get …`——命令根与子命令字面量来自字节码常量，确切语法须按运行时帮助输出确认。

- **H0 登录**：记录服务端日志中的登录行与该行给出的 UUID，与预测值比较。
- **S1 普通食物**（每项一次）：`give` 指定物品 → 记录进食前读数 → 以普通冲刺跳跃消耗饥饿（不用命令改状态）→ 正常完成一次进食 → 记录进食后读数、手持数量与容器返还。
  候选：`minecraft:bread`、`minecraft:apple`、`minecraft:carrot`、`minecraft:honey_bottle`（原版容器返还对照）、`farmersdelight:fried_egg`、`farmersdelight:honey_glazed_ham`（盘装线索）。
  取消：开始进食后提前松开，记录消耗与读数都不变。
- **S2 Diet**：对每项食物记录五个营养组的前后读数；负例用 `minecraft:clock`（右键使用），预期无饥饿与 Diet 变化，静态依据是它不在七个 Diet 标签文件的显式条目中。
  暂缓：`special_food` 条目（如 `farmersdelight:apple_pie`、`minecraft:cake`）需要先放置方块才能食用，超出默认动作范围，须另行授权。
- **S3 模组事件与 tick**：单次进食只产生一次收益；静置 5 分钟记录读数变化。事件顺序与重复触发需要服务端日志或伴随模组，本轮不声称覆盖。
- **S4 保存、移除、重启、恢复**：登出 → 记录 `p0-pilot-world/playerdata/<uuid>.dat` 是否出现 → `stop` → 备份 → 启动 → 重新登录 → 对比位置、生命、饥饿、Diet 与背包。

## 6. 备份与恢复

- 备份：停服后复制 `p0-pilot-world/`、`config/`、`server.properties`、`whitelist.json`、`ops.json`、`usercache.json` 到 `backups/<UTC>/`，生成 `manifest.sha256`（相对路径、字节数、SHA-256），并对目标副本重新计算校验。
- 恢复：停服 → 把当前实例目录整体移到 `backups/<UTC>-pre-restore/`（移动，不删除）→ 复制备份 → 按清单逐文件校验 → 启动 → 确认状态回到备份时刻。

## 7. 记录模板

每次试验使用 `evidence/YYYY-MM-DD/<run-id>/`（是否入库由维护者在运行后决定），包含 `manifest.md`、`human-baseline.md`、`cases.md`、`events.log`（不含凭据）、`artifacts.sha256`、`failures.md`。

| 用例 | 前置条件 | 步骤 | 读数（前 → 后） | 证据路径 | 结论 |
| --- | --- | --- | --- | --- | --- |
| H0 / S1 / S2 / S3 / S4 | | | | | |

## 8. 停止条件

出现任一项即停服、按第 6 节备份现场并报告，不自行调整范围：预测 UUID 与登录 UUID 不一致；生效 `server.properties` 的 20 个键值与启动输入不一致；服务端监听地址不是回环；世界边界不是 X/Z ∈ [−16, 16]；模组配置生成失败、崩溃或需要手改；JDK 指纹不一致；出现未记录的对外请求。
