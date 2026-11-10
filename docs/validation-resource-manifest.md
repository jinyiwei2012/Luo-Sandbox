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
