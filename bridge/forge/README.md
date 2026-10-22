# Forge 构建骨架

本目录是 Minecraft `1.20.1`、Forge `47.4.28`、Java 17 的最小可编译模组工程。它目前只包含 `luosandbox` 的 Forge 装载入口和不依赖游戏运行时的项目元数据；**没有**身体、玩家、服务端、客户端、网络协议、策略、认证或世界动作实现。

当前主路线仍是公开自定义 Forge 1.20.1 服务端的常驻身体，不是 Mineflayer 运行时依赖。身体生命周期、保护生存、断连保存/移除及恢复对账的设计边界仍以[产品需求](../../docs/product-spec.md)和[身体验证](../../docs/body-validation.md)为准，高风险世界修改保持默认禁用。

## 前提

- 64 位 Java 17（`java -version`）
- 网络访问 Gradle、Forge 与 Maven Central 的官方/上游依赖端点
- PowerShell；wrapper 使用随已校验 Forge MDK 提取的 Gradle 8.8 bootstrap，且 `distributionSha256Sum` 已固定

构建缓存被隔离到仓库根目录 `.gradle-user-home/`，该目录及所有 Forge build/run 产物都被 Git 忽略。首次构建会下载开发依赖；不会启动 Minecraft。

## 非游戏构建与测试

在仓库根目录运行：

```powershell
$env:GRADLE_USER_HOME = (Join-Path $PWD '.gradle-user-home')
Set-Location bridge/forge
.\gradlew.bat --version
.\gradlew.bat clean build
```

`build` 会编译、执行 JUnit 5 非游戏元数据测试并产出 reobfuscated jar，但不会调用 `runClient`、`runServer` 或 `runGameTestServer`。不要把该命令解释为 EULA 接受、账户认证或隔离游戏试点批准。

开发入口是 `io.github.jinyiwei2012.luosandbox.LuoSandboxMod`；`ModIdentity` 是可在 JUnit 中加载的中性常量边界，防止测试触发 Forge/Minecraft bootstrap。Forge MDK 模板来源与修改范围见 [NOTICE](NOTICE.md)。
