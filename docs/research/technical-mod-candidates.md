# 技术模组候选记录

本页是候选研究，不是安装说明、生产模组包、兼容性结论或客户端/身体迁移批准。当前仅将下载并核验的最小验证候选冻结为 Minecraft `1.20.1`、Forge `47.4.28`、Diet `2.1.1+1.20.1` 与 Farmer's Delight `1.20.1-1.3.4`。Forge 促销清单在 `2026-10-22T11:50:15Z` 返回 `1.20.1-latest = 47.4.28`。

## 已选最小候选

| 组件 | 选择依据 | 运行侧与直接要求 | 作者/官方来源 |
| --- | --- | --- | --- |
| Forge `47.4.28` | 基线 loader；该构建仍为促销清单的 `1.20.1-latest` | Java 17；Forge 文档的 1.20.1 入门页是安装/开发的权威参考，但本次未执行 installer 或 MDK | [Forge 文档](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/)、[官方 Maven](https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.28/) |
| Diet `2.1.1+1.20.1` | 食物组营养验证候选 | `mods.toml`：Forge `>=46`、Minecraft `[1.20,1.21)`、`BOTH`；内嵌 Jar-in-Jar SpectreLib Forge `0.13.14+1.20.1`，无需另下载 | [作者仓库](https://github.com/TheIllusiveC4/Diet)、[Modrinth 版本 API](https://api.modrinth.com/v2/version/oHxMouff) |
| Farmer's Delight `1.20.1-1.3.4` | 提供可验证食物内容的候选 | `mods.toml`：Forge `>=47.1.0`、Minecraft `[1.20,1.20.1]`、`BOTH`；CraftTweaker `>=14.0.6` 为可选项；内嵌 MixinExtras Forge `0.3.6` | [作者仓库](https://github.com/vectorwing/FarmersDelight)、[Modrinth 版本 API](https://api.modrinth.com/v2/version/SiIpcZzM) |

MDK 是开发工作区模板，installer 是安装器；二者均未执行。已下载的 Minecraft server/client jar 也只是官方工件，不构成可运行安装：没有 Forge 安装输出、Gradle/libraries/assets、配置、EULA 接受、账户认证或世界数据。

## 后续可研究候选（未下载、未测试）

| 候选 | 记录版本与许可证 | 限制 |
| --- | --- | --- |
| spark | `1.10.53-forge`，GPL-3.0 | 仅作为诊断候选；尚未核验与本候选包的运行时兼容性 |
| ModernFix | `5.27.89`，LGPL-3.0 | 性能候选，不属于最小包 |
| FerriteCore | `6.0.1`，MIT | 内存候选，不属于最小包 |
| Embeddium | `0.3.31`，LGPL-3.0 | 客户端专用研究候选；元数据提示与 Rubidium/TexTrue's Embeddium Options 不兼容，未下载或测试 |
| Baritone API | `forge 1.10.5`，LGPL-3.0 | 未来 API 研究项，不是安全控制，也不是当前依赖；[作者发布页](https://github.com/cabaletta/baritone/releases/tag/v1.10.5) |

许可证名称仅据候选发布/项目声明记录，不推断“全部禁止再分发”等未验证结论。任何加入、客户端迁移或性能模组组合都须在隔离环境单独批准并实测。
