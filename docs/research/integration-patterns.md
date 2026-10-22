# Minecraft 集成模式研究

> 状态：用于已获准的实现规划；不是运行时实现、生产安全证明、EULA 接受或世界动作批准。来源核查于 2026-10-22；未下载新依赖。

## 结论摘要

- 选定的物理传输候选是 Forge 客户端和服务端**各自出站**连接既有 Agent FastAPI WebSocket hub；hub 只路由，不是授权者、世界事实来源或规划器。
- 逻辑控制仍是 `Agent 请求 → 服务端唯一准入/结果 → 对客户端 permit/revoke`。服务端权威事实直接到 Agent，不经客户端转发；物理连接方向不能被误读为逻辑授权方向。
- 首期认证候选是仅回环本地的、角色分离 PSK 双向 HMAC-SHA-256 challenge-response。远程场景留待 mTLS 与受验证 TLS；不得以明文远程或“有 HMAC”替代 TLS。
- Forge 侧优先伴随模组、事件与普通包路径检查；必要时按固定 `47.4.28` 源码实际检查后做最小 `ServerGamePacketListenerImpl` patch。完整 fork 目前不选，不因用户允许源码改动而自动采用。

## 选项对比：传输与职责

| 方案 | 优点 | 主要问题 | 结论 |
| --- | --- | --- | --- |
| A. Java 17 WebSocket 出站到既有 FastAPI hub | JDK 自带客户端；双向低延迟；可在一个连接上传递观察、permit/revoke、回执 | 须实现片段重组、背压、断连与认证 | **采用为首个实现候选** |
| B. HTTP POST + long-poll | 常见且实现直观 | 游标、重试、重复与停止延迟增加；双向生命周期更难对账 | 不选作首期 |
| C. 原始 TCP + 自定义 framing | 可自定义 | 需自担 framing、升级、限流、TLS 与互操作，安全面无收益 | 拒绝 |

Java 17 的 `WebSocket` 是客户端 API；发送返回 `CompletableFuture`，接收由 `Listener` 和显式 `request(n)` 控制，文本可分片，故桥接实现必须有有界重组和队列，且“send 完成”不是世界动作成功。[Java 17 WebSocket](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/WebSocket.html)

FastAPI 支持 WebSocket 路由、收发消息和断开异常；其示例的内存连接表仅适于单进程。因此本期明确只支持一个 Agent worker；多进程或多副本前必须引入外部 registry/session broker，不能假装进程内表可全局 fencing。[FastAPI WebSockets](https://fastapi.tiangolo.com/advanced/websockets/)

### 拟议拓扑与状态边界

```text
Agent adapter --request--> Server admission --permit/revoke--> Client controller
Agent adapter <--authoritative observation/receipt-- Server
Client and Server --separate outbound WS--> Agent hub (routing only)
```

- Agent session broker 是全局 body/epoch owner；服务端须以当前外部 epoch fencing，客户端不得自行复活旧 permit。
- 服务端持有与 authority role 对应的凭据；客户端 controller 使用不同凭据。安装时 allowlist provisioned binding，离线昵称/UUID 从不作为认证或授权。
- hub 不能把 client-local observation 提升成 server-authoritative，也不能接受/完成动作。客户端的断线会使持续输入释放；服务端的断线或未知结果按生命周期策略处理。
- 这是架构目标，**尚未实现**；当前 C02 receipt 没有可验证的 authority 来源字段，观察的 source/provenance 一致性与 admission/cancel/`UNKNOWN` 状态机仍待跨语言实现和测试。

## 认证与暴露边界

### 本地 PSK 候选协议

1. 连接建立后，双方先声明固定 `role`、`install_id`、协议版本与模组/配置 hash，再各自产生高熵随机 nonce/challenge。
2. 每方以自己的角色专用 PSK 计算 `HMAC-SHA-256`，输入为规范化、长度前缀的 transcript：协议版本、双方 role、双方 install_id、双方 nonce、challenge、hash、过期时间和单次使用标识。
3. 双方以常量时间比较验证对方证明；仅在角色、allowlist binding、单次 challenge、expiry 和 transcript 全部一致时建立连接，并把认证结果绑定到该连接。
4. 拒绝重放、角色互换、过期、重复 challenge、未知 binding 或 hash 不一致；不把密钥、MAC、nonce、URL token 或代理地址写入日志、URL 或仓库。

RFC 2104 定义 HMAC 是带共享密钥的消息认证，要求保护密钥并使用安全交换；Python `hmac` 提供 HMAC 与 `compare_digest`。它**不提供加密**，也不能在没有可信对端证明/TLS 时阻止主动代理。[RFC 2104](https://datatracker.ietf.org/doc/html/rfc2104)；[Python hmac](https://docs.python.org/3.10/library/hmac.html)

| 网络边界 | 允许候选 | 明确不允许 |
| --- | --- | --- |
| 回环本机 | `ws://127.0.0.1` + 上述双向 HMAC；只绑定 loopback | wildcard bind、凭据 URL、记录 secret、把回环误称为通用远程安全 |
| 非回环/远程 | `wss://`、已验证 TLS、双向对端证明；未来评估 mTLS | 明文 WS、跳过证书验证、仅凭 HMAC 声称抗中间人 |

OS IPC 被拒绝为首期跨平台方案：部署与权限语义分裂，仍要解决身份和服务发现；可在未来受控单机环境单列决策。

## Forge 执行边界

Forge 区分 physical client/server 与 logical client/server；`Level#isClientSide` 用于游戏逻辑，客户端类不得被专用服务端加载。不同 logical side 间必须走网络包，不能以共享静态状态偷越边界。[Forge sides 1.20.1](https://docs.minecraftforge.net/en/1.20.1/concepts/sides/)

`SimpleChannel` 适合模组 client/server 自定义包，但 handler 默认在网络线程，游戏对象工作须 `enqueueWork`；服务端必须防御意外数据和未加载区块访问。它不是服务端准入的唯一屏障：准入仍须落在普通游戏数据包路径。[Forge SimpleImpl 1.20.1](https://docs.minecraftforge.net/en/1.20.1/networking/simpleimpl/)

| 服务端插入选择 | 用途与代价 | 决定 |
| --- | --- | --- |
| 伴随 Forge 模组、事件及标准 hook | 最低维护成本，优先验证可否覆盖身份、区域、禁止交互、限速与事实回执 | **首选** |
| 精确最小 patch / 必要时 Mixin | 当事件 hook 无法在普通包路径上提供所需准入点；先检查固定 `47.4.28` 源码和 [1.20.x `ServerGamePacketListenerImpl` patch](https://raw.githubusercontent.com/MinecraftForge/MinecraftForge/1.20.x/patches/minecraft/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch) | **条件采用** |
| 完整服务端 fork | 最广控制面但升级、兼容与审计成本最高 | 当前不选；未来以工程证据决定，并非“证明其他方案不可能”才可用 |

Mixin 在类加载前变换字节码，目标、环境、映射和启动顺序敏感；如采用，client/server 集须分离、固定基线并以失败即停止的精确目标验证，不将其作为通用 hook 工具。[Mixin environment](https://github.com/SpongePowered/Mixin/wiki/Introduction-to-Mixins---The-Mixin-Environment)

## 控制器与历史替代项

- 首期客户端只实现 deterministic `look`、平面有界 walk 和 `stop`；无挖掘、放置、攻击、背包或任意命令。取消必须释放输入，但不将取消表述为即时世界回滚。
- Baritone `v1.10.5` 及其 1.20.1 路线可作后续研究；因其能力面更大且 pathing cancellation 不是原子世界回滚，首期延后，不嵌入白名单执行器。[Baritone release](https://github.com/cabaletta/baritone/releases/tag/v1.10.5)
- Mineflayer（MIT）、MineDojo（MIT）和 Voyager 仅为历史/研究对照；Voyager 的 Fabric 1.19+ Mineflayer 代码生成调度不符合已选 Forge 1.20.1、服务端唯一准入和无第二规划器边界。[Mineflayer](https://github.com/PrismarineJS/mineflayer)；[MineDojo](https://github.com/MineDojo/MineDojo)；[Voyager](https://github.com/MineDojo/Voyager)
- HeadlessMc 仅可在未来 CI 约束研究中评估，不能作为生产依赖，也不用于规避许可证、账户或 EULA 要求。[HeadlessMc](https://github.com/headlesshq/headlessmc)

## 序列化、记忆与威胁模型

### JSON：已选设计，尚未实现或运行验证

**ADOPTED（设计）**：将来只引入 `com.fasterxml.jackson.core:jackson-core:2.18.12`，使用 streaming API；不引入 `jackson-databind`、`jackson-annotations` 或 `ObjectMapper`。这是一个最小、成熟的词法/流式边界依赖选择，**不是**已下载、已打包、已加载或已兼容 Forge 的声明。

本机证据只说明现有候选依赖：Minecraft `1.20.1` 生成 POM 将 Gson `2.10` 列为 compile dependency；Forge `fmlloader 1.20.1-47.4.28` 的 `runtimeElements` 也列 Gson `2.10`。缓存中的其他 Gson `2.10.1` 工具依赖不构成游戏 runtime 证据。实际最终 artifact、classloader、发布物和运行时 classpath 尚未测试。[本机 client POM](../../.gradle-user-home/caches/forge_gradle/mcp_repo/net/minecraft/client/1.20.1/client-1.20.1.pom)；本机 `fmlloader-1.20.1-47.4.28.module`。

| 方案 | 证据与限制 | 结论 |
| --- | --- | --- |
| bundled Gson `2.10` + 手写严格词法 wrapper | `JsonObject.add` 写入同一 `members` map，重复 member 会覆盖；`JsonReader` 即使 `lenient=false` 仍容许部分非 RFC 严格形式（含控制字符等） | 不选：必须自担关键 lexical 纠正层 |
| Jackson core `2.18.12` streaming | 官方 POM 将其描述为 JSON Streaming API，Apache-2.0；可在单一 parser factory 施加流读取约束和 feature | **采用为设计依赖** |
| DIY parser | 必须自己承担 Unicode、数字、深度、重复字段和边界维护 | 拒绝 |

来源：[Gson `JsonObject.add`](https://raw.githubusercontent.com/google/gson/gson-parent-2.10/gson/src/main/java/com/google/gson/JsonObject.java)；[Gson `JsonReader` 2.10](https://raw.githubusercontent.com/google/gson/gson-parent-2.10/gson/src/main/java/com/google/gson/stream/JsonReader.java)；[Jackson core 2.18.12 POM](https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.18.12/jackson-core-2.18.12.pom)。Gson 2.11 的 `STRICT` 不能倒灌到 2.10。

未来实现必须仅用私有 `JsonFactory`，配置 `StreamReadConstraints`、strict duplicate detection、禁用 `INCLUDE_SOURCE_IN_LOCATION` 与 charset detection；帧在 parser 前按**精确 UTF-8**检验，拒绝 BOM、未配对 surrogate 与超 cap 内容；整数保留 lexical 值并以 `long`/I63 校验，禁止先转 `double`；十进制必须有限。深度/长度等具体上限遵从 canonical draft 的当前 `depth=12`，本研究不擅自改为 16。实际 API 名称（含 `StreamReadFeature`）须在下载后的 `2.18.12` 上编译验证。

发布前须下载后重新计算并比对 jar SHA-256；研究记录的 Maven published hash `367afb4be3efa8a1636200b5faefb67d7d0a1b730f64ec55a971229cb673bc23` 仅为**报告值**，尚非本机下载校验。还须检查最终 Forge/JarJar artifact 的嵌套 jar、LICENSE/NOTICE、多版本条目和实际 classpath 选择；只有确认真实冲突时才考虑 relocation，不能预先假定需要或不需要。Oracle canonical spec 就绪前，不实现 parser，也不下载该依赖。文档 Map fixture 仍不是冻结网络 schema。

M1 默认不自动写入持久公开 Minecraft 记忆；仅允许可审计的操作 journal，且它不是上游人格/长期记忆。用户长期记忆、受众过滤与撤销仍是未来上游能力和单独审批项。

威胁模型限定：固定 Forge/模组 hash、非 OP、可能 buggy/malicious 的客户端。它不覆盖被攻陷的服务端、未知模组、操作系统、被窃密钥或人类/EULA 证据；后两者仍是外部前提。源码实现获准不等于这些前提已满足。
