# 验证资源清单

**状态：已下载并校验的验证候选，不是生产模组包，亦不构成真实客户端 runtime、世界、EULA 或动作范围批准。** 二进制位于被 Git 忽略的 `.local-resources/forge-1.20.1-baseline/`；原始 API/官方元数据、促销清单、publisher SHA-1 与机器可读下载记录同在该目录，未纳入版本控制。

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
