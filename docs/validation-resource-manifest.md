# 验证资源清单

**状态：P0 候选验证输入已冻结，但 P0 尚未完成。** 本页冻结的是已下载并校验的分层候选输入，不是生产模组包，亦不构成兼容性、可启动性、真实客户端 runtime、世界、EULA 或动作范围批准。二进制位于被 Git 忽略的 `.local-resources/forge-1.20.1-baseline/`；原始 API/官方元数据、促销清单、publisher SHA-1 与机器可读下载记录同在该目录，未纳入版本控制。

冻结日期：`2026-11-03`。冻结组合为 Minecraft `1.20.1`、Forge `47.4.28`、Java 17 约束、Diet `2.1.1+1.20.1` 与 Farmer's Delight `1.20.1-1.3.4`。Forge installer/MDK 与 Minecraft client/server jar 作为分层的构建/游戏输入保留；它们尚未组成已安装或可启动的 Forge runtime。未列入本次冻结的后续候选（包括 spark、ModernFix、FerriteCore、Embeddium、Baritone）不属于冻结包。

下载窗口为 `2026-10-22T11:49:49Z` 至 `2026-10-22T11:50:15Z`。Forge promotion 来源在该窗口确认 `1.20.1-latest` 为 `47.4.28`。每个工件均计算 SHA-256；“发布方校验”表示实际 digest 与相应官方 Forge `.sha1`、Modrinth API SHA-1/SHA-512 或 Mojang 版本 JSON SHA-1 相符。

| 工件 | 大小（bytes） | SHA-256 | 发布方校验 | 规范来源 |
| --- | ---: | --- | --- | --- |
| `forge-1.20.1-47.4.28-installer.jar` | 8,859,760 | `d615397a6b2f1e99d4b454e2db2b4ca2e94be91d5e0243c8c7e023d965db39e7` | 匹配官方 SHA-1 `585dd6071897becaed3b09bff4c3d9559e0cbc75` | [Forge Maven](https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.28/forge-1.20.1-47.4.28-installer.jar) |
| `forge-1.20.1-47.4.28-mdk.zip` | 114,861 | `3a738a7511df78e9aa45333cc53b9c31f6f1c2984e7af8c510157535153c8906` | 匹配官方 SHA-1 `eee9145e892bb5ca5fe72323bd81c8522c53417f` | [Forge Maven](https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.28/forge-1.20.1-47.4.28-mdk.zip) |
| `diet-forge-2.1.1+1.20.1.jar` | 368,483 | `2e3b631cd9773996c7eaefe3c1cd086045b9b9a193a8e4ab29cc8e64d937e5bd` | Modrinth SHA-1 与 SHA-512 均匹配 | [版本 API](https://api.modrinth.com/v2/version/oHxMouff) |
| `FarmersDelight-1.20.1-1.3.4.jar` | 3,311,051 | `34f03eb671ea1ca579b00c98e4541404cb96d1a292014aed9cc5eb45ad8afbce` | Modrinth SHA-1 与 SHA-512 均匹配 | [版本 API](https://api.modrinth.com/v2/version/SiIpcZzM) |
| `minecraft-1.20.1-server.jar` | 47,791,053 | `3af73a9dc5a102e38147946360dd27d4d70bae7055bf91cf2151cd5d121b79e0` | Mojang SHA-1 匹配 | [官方版本清单](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) |
| `minecraft-1.20.1-client.jar` | 23,028,853 | `56b71336d2b4fdffd197f56595b0da93e32a946f78f382a299b8f4b92758bb0f` | Mojang SHA-1 匹配 | [官方版本清单](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) |

非执行式 ZIP/JAR 检查读取了两个 mod 的 `META-INF/mods.toml` 与 `META-INF/jarjar/metadata.json`。Diet 声明 `BOTH`，并内嵌 SpectreLib；Farmer's Delight 声明 `BOTH`，CraftTweaker 可选，并内嵌 MixinExtras。检查记录存于忽略目录的 `jar-metadata-inspection.json`；它不是兼容性测试。

本清单覆盖的原始游戏/Forge/mod 工件未被用来启动 Minecraft、登录账户或接受 EULA，配置 hash 仍待生成。其后 C01 曾使用 Forge wrapper 下载最小工程的构建依赖并完成非游戏 `clean build`；这不证明完整真实客户端 runtime 的递归 libraries/assets、资源、配置或模组包已下载、验证、构建或启动。若后续准备启动验证，必须另行取得范围批准并按[前置验证准备单](validation-preflight.md)冻结配置与运行证据。

## 2026-11-10 离线复核

六项冻结工件均重新读取并计算大小与 SHA-256，**6/6 匹配上表及本地下载记录**。来源 URL、版本与历史下载时间仅与保存的官方/作者元数据交叉核对，本轮未联网、未重新计算整工件的发布方 SHA-1/SHA-512。下载记录 `download-provenance.json` 的 SHA-256 为 `b7505a871ca301e14e97784407e013863edbf2c02a7141ec51074f406f30bcdc`。原下载窗口上界对应 preparation 时间的秒级截断，不代表最后一个工件的下载时刻；冻结日期也不是下载日期。

Diet 内置 `data/` JSON 共 13 项，路径唯一、均可读取并解析。下表摘要针对 ZIP 条目解压后的精确字节；这些是**包内默认数据**，不是外置数据包、最终生效配置或 Diet/食物行为实测，不解除配置 hash 阻塞。

| Diet 包内条目 | SHA-256 |
| --- | --- |
| `data/diet/diet/groups/fruits.json` | `6eae0e4b9351c70fb916238d3d02954ce26e5f0ac767172a8e26b83e85a06be3` |
| `data/diet/diet/groups/grains.json` | `c9de4e0a89ca959ad799d2437c6119abdd0a736061b99f9c195594f815a39571` |
| `data/diet/diet/groups/proteins.json` | `27dd4e9a20eef74aef2e75f351d97d714ba5ecff11b8b23b00313bb73587b1c1` |
| `data/diet/diet/groups/sugars.json` | `730f9a0dd704b4af428cf0d5ef3bad58e92650dc8e8b8399e8539efac5d7f0be` |
| `data/diet/diet/groups/vegetables.json` | `da92be6bae0e5fb2cb705024747f92f47b699372c22729a4ca428d6bdbc81374` |
| `data/diet/diet/suites/builtin.json` | `b0736d7036499f0098f47125a3ff8967cd77a4ed4e5596b71bc3256ea3f3b15f` |
| `data/diet/tags/items/fruits.json` | `bc97e23efc9d61df707c865106dfe4dafdf7c988d491059926fee0dfa861a972` |
| `data/diet/tags/items/grains.json` | `02f37e58ca8356bba77b585cf4e344ddfce495d17047e9f73d359ed0e6ae9f64` |
| `data/diet/tags/items/ingredients.json` | `4f4160bcede6d7e64e1ba54c53829fd8fe07e6c66cfad8cc9b79a016b02276e6` |
| `data/diet/tags/items/proteins.json` | `07713eb966839004b03c6cae7bbd1f999c2e75a70360d20d5831b4811ceb0ad1` |
| `data/diet/tags/items/special_food.json` | `c161de6aa25754c43fb6fee32892ba9f61132f4bb915de0ca08dacc18c6a03f9` |
| `data/diet/tags/items/sugars.json` | `1590c4c14d8f28f36f03273e667767eb453aed5ae65321af1db2f898b48c2c40` |
| `data/diet/tags/items/vegetables.json` | `3cbd0e7ae49b4d83247e52757d9eb43286297f6a10ed6661407f5875ef884193` |

静态声明复核：Diet 内嵌 SpectreLib `0.13.14+1.20.1`，Farmer's Delight 内嵌 MixinExtras `0.3.6`，2/2 内嵌条目存在且可读取；依赖声明不是实际 classloader 加载或运行兼容性证明。本轮没有新增模组、试点配置、凭据或运行资源。

## 2026-11-10 试点输入冻结

维护者当日确认了试点固定身份、隔离世界与区域、责任人、配置冻结策略和“仅人类基线的隔离启动”方向。机器可读记录为 `tests/p0-pilot/pilot-inputs.json`，复核脚本为 `scripts/minecraft/verify_p0_pilot_inputs.py`。以下仍是**启动输入与纸面范围**，不是生效配置、runtime 或兼容性证据。

### 启动输入模板

`tests/p0-pilot/server.properties`：712 字节，纯 ASCII、仅 LF（`.gitattributes` 固定 `eol=lf`），SHA-256 `aaf21979aa36bfaf847337fdb6a10cf86245efc564587c1aae0ad2bfaf8c9890`。它是启动输入；服务端启动时会重写该文件，生效文件须另行冻结。

20 个键全部出现在冻结服务端 jar 属性类的字符串常量池中：内嵌 jar `META-INF/versions/1.20.1/server-1.20.1.jar`（SHA-256 `80db52b203ac5de6e5fc1c5082259df440fb2b5390b4c61d474e8fbc63cc41f5`）内的 `ahf.class`（SHA-256 `2efe2e3d243bba063a12185a0a19749475fdcbbcf03b2f09af0d8a66cba0ad2c`）。这是键名存在性证据，不是默认值或运行行为证据。

| 键 | 值 | 对应决定 |
| --- | --- | --- |
| `server-ip` / `server-port` | `127.0.0.1` / `25565` | 仅本机回环 |
| `online-mode` | `false` | 离线登录计划 |
| `white-list` / `enforce-whitelist` | `true` / `true` | 白名单（离线模式下不是可信身份边界） |
| `enable-rcon` / `enable-query` | `false` / `false` | 不开放额外远程管理面 |
| `max-players` | `2` | 两个固定身份 |
| `level-name` | `p0-pilot-world` | 全新专用世界 |
| `level-type` | `minecraft:flat` | 超平坦 |
| `level-seed` | `20261110` | 固定种子 |
| `generate-structures` | `false` | 不生成结构 |
| `allow-nether` | `false` | 仅主世界 |
| `max-world-size` | `16` | X/Z ∈ [−16, 16] 的边界兜底 |
| `difficulty` | `easy` | 保留正常饥饿（和平难度不消耗饥饿） |
| `gamemode` / `force-gamemode` / `hardcore` | `survival` / `true` / `false` | 生存语义，不使用创造 |
| `spawn-monsters` | `false` | 关闭怪物生成 |
| `motd` | `Luo-Sandbox P0 pilot - isolated loopback` | 隔离标识 |

字节码依据（静态阅读，未经运行实测）：`WorldBorder$StaticBorderExtent.updateBox()` 把 minX/minZ/maxX/maxZ 钳制到 `[-absoluteMaxSize, absoluteMaxSize]`；`ServerLevel` 调用 `MinecraftServer.getAbsoluteMaxWorldSize()` 后执行 `WorldBorder.setAbsoluteMaxSize(int)`；`DedicatedServerProperties` 读取 `max-world-size`。`FoodData.tick` 先比较 `Difficulty.PEACEFUL`；饥饿伤害在 `getHealth() > 10.0`、`HARD`、或 `getHealth() > 1.0 && NORMAL` 时触发，即简单与普通难度分别停在 10 点与 1 点生命。

### 试点 Java 身份（指纹）

2026-11-10T13:40Z 观测的 Temurin `17.0.20.1+1` 安装。启动前逐个复核下列文件，不一致即停；这是本机这份安装的指纹，不是发行包的发布方校验。

| 相对路径 | 字节 | SHA-256 |
| --- | ---: | --- |
| `release` | 1,674 | `171abb6be50980aeed01365f3032cb7a1bc6126ca48a5eed5ba87ac3e000f7a5` |
| `bin/java.exe` | 50,296 | `1977f302375adbb920d41dac65c7e22eb9c2ed8e1e8d6258964154ff16f14406` |
| `bin/javac.exe` | 24,184 | `9c71eee841aeebb723bfc2cceca7d72c6c1988fb679402f4123aa4d0e86aa549` |
| `bin/server/jvm.dll` | 12,684,920 | `6b9a2354b41134986d61d1f8ff0f9614e3a6b7e235469b7e9260fe7a1d9bb84a` |
| `lib/modules` | 128,750,335 | `a587ac5c7c093f0b17fb940d0bf46aca152c6013449a485538d43f136f5b92a8` |

### 仍未冻结

模组生成配置与生效 `server.properties` 的 hash、`whitelist.json` 与 playerdata、实测离线 UUID、备份与恢复演练、人类参考基线。它们只能由一次获批的“仅人类基线的隔离启动”产生，流程见[人类参考基线清单](validation-human-baseline.md)。
