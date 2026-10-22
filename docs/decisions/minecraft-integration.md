# 决策记录：Minecraft 集成首期模式

- **状态：ADOPTED（架构方向） / PENDING（实现与外部前提）**
- **日期：2026-10-22**
- **范围：Forge 1.20.1 真实客户端、服务端直接权威和 Agent-LuoTianyi 适配边界**

## 背景与决策

当前主路线已是真实 Forge 客户端和服务端直接权威；人格、记忆、决策和调度仍归上游。采用下列可实施方向，但不把方向记录误写成已部署的安全能力：

1. 物理连接选 Java 17 WebSocket 出站至现有 Agent FastAPI hub；client/server 分别连接。hub 只作路由，绝不成为世界权威、准入器或 planner。
2. 逻辑链固定为 `Agent request → server sole admission/outcome → client permit/revoke`，server-authoritative observation/receipt 直接给 Agent，禁止 client relay。
3. 回环本地采用角色分离 PSK、双向 HMAC-SHA-256 challenge-response；非回环必须 `wss`、验证 TLS 与对端证明，mTLS 为未来候选。HMAC 不加密，也不单独解决主动代理。
4. 服务端先走 Forge 伴随模组/events；若固定 `47.4.28` 源码核查表明普通包路径缺关键准入点，才采用精确最小 patch 或 Mixin。完整 fork 暂不选，未来按复杂度、兼容性和可复现性证据决定。
5. 客户端第一闭环限 `look`、平面 bounded walk、`stop`；Baritone、GameScaling、Mineflayer/MineDojo/Voyager 不进首期执行链。单 Agent worker；多进程前必须有外部 registry/session broker。
6. JSON 设计选 `jackson-core:2.18.12` 的 streaming API，且仅此 artifact；不用 databind、annotations 或 `ObjectMapper`。依赖版本/发布 POM 已作研究核对，但下载、打包、classloading、API 编译和 runtime 兼容性均未验证。

## 被否决/延后的替代方案

| 方案 | 结论与理由 |
| --- | --- |
| HTTP POST + long-poll | 不选首期：cursor、去重、重连与 stop latency 的状态面更大 |
| raw TCP 自定义 framing | 拒绝：无必要地自担 framing/TLS/互操作与安全维护 |
| OS IPC | 拒绝首期：跨平台部署/权限不一致，未消除认证问题 |
| 明文远程 WS 或 TLS bypass | 拒绝：HMAC 不提供保密性或充分的主动代理防护 |
| SimpleImpl 作为唯一强制点 | 拒绝：自定义包不能单独保证普通游戏数据包路径上的授权 |
| 完整 fork 作为默认 | 暂不选：维护面过大；允许源码改动不是自动采用理由 |
| Baritone / 代码生成 agent | 延后：能力面和非原子取消不适合首期确定性执行器 |
| bundled Gson `2.10` + wrapper | 不选：本机 Minecraft/Forge 元数据确有 Gson 2.10，但其 object 覆盖重复 member，且 strict reader 仍需手写 lexical 收紧 |
| DIY JSON parser | 拒绝：解析安全与兼容维护风险 |

## 必须保留的不变量

- 单一当前 body epoch、服务端外部 fencing、不同 server/controller role 凭据；昵称/UUID 不是认证。
- request accepted 不等于 action succeeded；终态包含 succeeded/failed/timed out/cancelled/disconnected/unknown，`unknown` 后重新观察/对账而非盲重试。
- 世界文本不授予权限；客户端局部观察不升级为服务端事实；游戏线程不等待网络 IO 或 LLM。
- 不记录 secret、MAC、nonce、URL credential 或代理端点；不承诺安全、runtime、EULA 或人工证据已经实现。

## 已知缺口与后续准入

- C02 当前 receipt 不能证明 authority source；observation source/provenance 语义须在跨语言端一致执行。
- admission、cancel、`UNKNOWN`、断线和 epoch fencing 尚无完整状态机或跨语言 golden test。
- Jackson 依赖尚未下载或封装；strict duplicate/UTF-8/BOM/surrogate/I63/有限 decimal、canonical draft `depth=12`、最终 JarJar LICENSE/NOTICE/多版本条目/classpath 和 API 编译均未验证。不得在 Oracle canonical spec 就绪前实现 parser。
- 固定 Forge 源码插点、协议 hash、PSK provision/rotation、外部 session registry 均未实现。完整服务端 core patch 已获用户允许；选择伴随模组、最小 patch/Mixin 或完整 patch 仅依工程证据，不另设“其他方案不可能”或额外授权硬门。
- 授权的 runtime、EULA、人类对照和隔离世界证据是外部前提，未由本决定满足。

## 依据与置信度

高：Java 17 WebSocket、FastAPI、Forge sides/SimpleImpl、RFC 2104 与 Jackson core published POM 支持 API/依赖选择。中：Mixin、Baritone、Gson 2.10 源码和替代项目资料支持维护/能力面判断，实际 pinned 工件与行为仍须复核。低/待验证：具体 Forge patch 插点、Jackson 下载 hash/最终 classpath、跨仓库适配接缝和所有真实游戏语义。

详见[集成模式研究](../research/integration-patterns.md)、[架构](../architecture.md)及[实现计划](../implementation-plan.md)。
