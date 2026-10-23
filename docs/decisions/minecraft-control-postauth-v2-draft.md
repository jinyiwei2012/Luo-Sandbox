# Minecraft control post-auth v2

> **SPEC_FROZEN / NORMATIVE CONTRACT**
>
> **Implementation NOT APPROVED / Runtime activation PROHIBITED / Production default DISABLED**
>
> 文件名中的 `draft` 是历史稳定路径，不代表当前状态；该路径已被测试与证据工具固定引用，不重命名。
> Parent freeze decision: 2026-10-23. Independent review result: `PASS_FOR_SPEC_FREEZE`；两项精确文字
> remedy（manifest key count、COMMIT_IO_PENDING ownership）已由 parent 验证。

## 0. 状态与边界

- 状态：**SPEC_FROZEN / NORMATIVE CONTRACT / IMPLEMENTATION NOT APPROVED**。
- 本文已通过独立规范评审并冻结。它规范显式 opt-in 的 v2 session；当前已实现的 v1 heartbeat-only
  transport 保持不变。冻结不授权生产实现、部署或激活。
- 本文不授权实现、端点启用、BODY 绑定、epoch 提交、permit、输入控制或游戏运行。
- 对显式 opt-in v2 session，本文 supersede 与其冲突的 post-auth heartbeat/notice-routing/candidate 条款；
  v1 session与未被本文替代的BODY/epoch/binding/permit语义保持现状。
- 已有 206/216 测试、历史 Java/Python 向量及 build/reobf 证据均不证明本 v2 草案。
- 文中“现有”描述当前源码事实；“必须”表示已冻结的规范要求。
- 冻结只冻结协议与所有权契约，不等于实现、运行时、BODY 绑定、permit、游戏、EULA 或发布批准。
- 当前 Sandbox 生产源仍为 `1b68d40`；upstream epoch cleanup 修复为 `70dcafb1`。两者均不包含 v2 runtime。

## 1. 不变的所有权模型

1. Python `MinecraftControlBindingCoordinator` 继续是唯一 binding owner。
2. 不创建第二 coordinator、第二 epoch store 或可绕过既有 PREPARE→READY→COMMIT→ACTIVE
   顺序的 binding 状态机。
3. Hub 是最终 currentness fence：它拥有当前已认证 Server/Controller 连接、路由 capability
   和 coordinator 的同一串行 owner turn。
4. remote Server 只能产生非授权的 `BODY_BOUND_CANDIDATE`；candidate 不是 binding、READY、
   COMMIT、ACTIVE、permit 或世界权限。
5. Hub 只有在同步核验当前 Server、当前 Controller、route capability、allowlist、tuple、期限和
   coordinator 状态后，才可让同一个 coordinator 接纳 PREPARE。
6. controller 断开或替换时，Hub 先从 current role 表移除该连接，并在同一 owner turn 同步执行
   local fence；对应 pending routes、candidates 和 coordinator pending 立即协议失效。
7. 已发送或仍在队列中的 remote bytes 可以物理存在，但失效后不得触发 PREPARE、COMMIT、
   ACTIVE 或 permit。
8. 不存在“断连前已授予、断连后仍可兑换”的独立 grant；本草案保留 v1 的 controller
   replacement/loss 立即失效语义。
9. Hub transport/current-role removal、route table、READY input 与 coordinator admission 必须由同一
   asyncio owner turn 串行；其他线程/EventBus/worker completion只能投递immutable input，不得直接修改状态。
10. controller loss/replacement 必须先从 current-role table 移除 exact connection并同步调用local fence，
    然后才允许任何 close/revoke/notification await。

当前上游 coordinator 的 `_fence_local` 已表达“在任何 cleanup await 前同步清除本地 authority”
的原则；本草案要求未来 transport/current-role removal 与该同步步骤处于同一串行 owner turn。
这是一项实现门槛，不是当前 transport 已具备的能力。

## 2. 协议不兼容性

- v2 handshake `protocol` 与 post-auth `profile` 字面量均精确为 `LUO_CONTROL_POSTAUTH_V2`。
- `security` 字面量继续为 `luosandbox.control-auth.v1`；KDF 与 frame-MAC 使用独立 v2 domain。
- v2 不允许 v1 fallback、v1/v2 heartbeat 混用或同一物理连接原地升级。
- 未验证 v2 `SESSION_OPEN` 的 peer 不得发送任何 post-auth frame；验证后首个 frame 必须是
  sequence 0、payload 精确为 `{}` 的 `HEARTBEAT`，作为 open ACK。业务帧只能作为 sequence 1
  或更晚的帧，排在该 heartbeat 后的同一单一有序发送管道中。Hub 必须先验证 sequence 0 并同步
  将该 exact session 转为 `APPLICATION_READY`，才能处理后续业务帧；不存在额外的 Hub→peer
  application-ready 确认消息。
- 收到 v1 post-auth frame、未知 profile 或混合 sequence 空间时，关闭该 exact connection，释放
  其 role ownership，并同步 fence 相关本地状态。
- HELLO/CHALLENGE/PROOF/READY transcript 的 16 个字段、domain 和 label 不变；v2 连接只把其中
  `protocol` 的值设为 `LUO_CONTROL_POSTAUTH_V2`。当前已实现 transport 仍固定
  `luosandbox.minecraft.v1`，所以不是 v2 实现。
- v2 只替代其自身 WebSocket session 的 post-auth heartbeat、notice/route/candidate envelope。
  游戏侧 BODY proof、epoch CAS、PREPARE→READY→COMMIT→ACTIVE、permit、revoke 和 UNKNOWN 语义不被替代。

### 2.1 Role-scoped deployment manifest

不增加 wire hash 字段。`configHash` 精确等于 **role-scoped deployment manifest 原始文件 bytes** 的
SHA-256 raw32。建议 basename 为 `minecraft-control-deployment-v2.json`，但启动必须接收一个受信任、
显式、绝对路径；不得按 cwd、环境目录或文件搜索自动发现。

manifest 是 ASCII UTF-8、无 BOM、单行 compact JSON、精确 23 个键并按下列顺序、末尾精确一个 LF；不得有其他
空白、重复/未知/缺失键、类型替代、别名或额外尾随 bytes。这是该 manifest 的唯一 bytes，不建立通用
wire JSON canonicalization 规则：

```text
schema="luosandbox.minecraft-control-deployment.v2"
security="luosandbox.control-auth.v1"
peerRole="SERVER_AUTHORITY" | "CLIENT_CONTROLLER"
peerInstallId=ASCII Id
hubInstallId=ASCII Id
protocol="LUO_CONTROL_POSTAUTH_V2"
postAuthProfile="LUO_CONTROL_POSTAUTH_V2"
sessionKdfDomain="LUO_CONTROL_SESSION_KEY_V2"
frameMacDomain="LUO_CONTROL_FRAME_V2"
frameMaxUtf8Bytes=4096
payloadMaxRawBytes=2048
asciiIdMaxBytes=128
outboundQueueFrames=8
openTimeoutMs=5000
heartbeatIntervalMs=500
heartbeatTimeoutMs=2000
routeCapacity=2
routeTtlMs=5000
bindingPendingCapacity=8
storeOutstandingCapacity=2
modManifestSha256=64 lowercase hex
policyConfigSha256=64 lowercase hex
bindingManifestSha256=64 lowercase hex
```

Server 与 Client 使用各自 role/install manifest，允许不同 `modHash`/`configHash`；Hub 不用自身模组求
peer hash。受信 startup provisioning 必须另外提供该 peer role/install 的 allowlist 与三个受信 artifact
绝对路径：mod manifest、raw policy config、binding manifest。loader 必须对实际 raw bytes 求 SHA-256，
要求：

- `modHash == raw32(modManifestSha256)`；
- `bindingHash == raw32(bindingManifestSha256)`；
- policy artifact digest 等于 `policyConfigSha256`；
- `configHash == SHA256(raw deployment manifest bytes)`；
- allowlist 的 role/install/hashes 来自受信 provisioning，不从 HELLO 或 payload 学习。

artifact 缺失、symlink/reparse、非 regular、共享位置不满足已批准 ACL/VFS 假设、hash mismatch 或解析失败
均使整个 control capability inactive。不得自动 provision identity/key/epoch，不得把 secret 写入 manifest。
这三个 artifact 继续遵守现有 v1 deployment artifact 内容规则；v2 container 只绑定其 raw-byte digest，
不重新定义 world policy 内容。

现有 174-byte 四字段 fixture 只是 NON-NORMATIVE historical fragment，不是 production manifest；其中 dummy
hash 绝不能进入生产文件。本文变化也使历史 v2 vector/draft-hash pin 不再构成冻结规范的验收，后续实现
验证必须重新生成与评审向量。

## 3. 从现有认证 transcript 派生方向 key

现有 `ControlAuth.transcript(PEER_PROOF, authenticatedTranscript)` 使用 U32BE byte-length LP，
精确串联以下 16 个既有字段：

```text
domain
PEER_PROOF
peerRole
AGENT_HUB
peerInstallId
hubInstallId
keyId
protocol
modHash_raw32
peerNonce_raw32
hubNonce_raw32
challengeId
issuedAt
expiresAt
configHash_raw32
bindingHash_raw32
```

定义：

```text
authDigest = SHA256(ControlAuth.transcript(PEER_PROOF, authenticatedTranscript))

peerToHubKey = HMAC-SHA256(
  rolePSK,
  LP_U32BE("LUO_CONTROL_SESSION_KEY_V2", authDigest_raw32, "PEER_TO_HUB")
)

hubToPeerKey = HMAC-SHA256(
  rolePSK,
  LP_U32BE("LUO_CONTROL_SESSION_KEY_V2", authDigest_raw32, "HUB_TO_PEER")
)
```

- `rolePSK` 是该 authenticated peer 已有的 K_server 或 K_client；不得跨 role 复用。
- `authDigest` 对既有精确 transcript bytes 求 SHA-256，不重新编码 JSON 或 hashes。
- 派生 key 仅属于该认证连接，不进入 public DTO、日志、URL、模型上下文或持久存储。
- 连接关闭时清除两个派生 key、sequence、session ID、route references 和 queued ownership。
- HMAC 只提供消息认证，不提供加密；本草案不假定 TLS，也不扩大 v1 的网络威胁边界。
- security literal 继续固定为 `luosandbox.control-auth.v1`；协议必须同时要求 v2 profile、独立
  KDF domain 和 frame-MAC domain。不得接受别名、替代 literal 或仅 security 匹配的降级帧。

## 4. 外层 authenticated frame

每个 post-auth frame 必须是 strict UTF-8 JSON object，**精确 14 个键**：

```text
security
kind
profile
connectionId
challengeId
sequence
messageKind
routeId
sourceInstallId
sourceConnectionId
recipientConnectionId
challengeExpiresAt
payload
mac
```

字段规则：

| 字段 | 草案约束 |
| --- | --- |
| `security` | 固定 `luosandbox.control-auth.v1` |
| `kind` | 固定 `AUTHENTICATED_FRAME` |
| `profile` | 固定 `LUO_CONTROL_POSTAUTH_V2` |
| `connectionId` | Hub transport 在接纳该 exact socket/role reservation 时已分配的 canonical nonzero UUID；READY 后不得另造第二个 ID |
| `challengeId` | 原认证 handshake 的 canonical nonzero UUID |
| `sequence` | I63，按方向严格连续 |
| `messageKind` | 本文列出的 ASCII Id，最长 128 ASCII bytes |
| routing/expiry metadata | 使用下述矩阵；未使用字段必须是空字符串，不得省略或为 null |
| `payload` | 任意 0..2048 raw bytes 的 canonical unpadded base64url |
| `mac` | HMAC-SHA256 raw32 的 canonical 43-char unpadded base64url |

`payload` 不是 v1 的 raw32 `B32` 字段，不能调用只接受 32 bytes 的现有 B32 validator。
解码后重编码必须逐字相同；alphabet 仍为 base64url 且禁止 `=`。

整个 UTF-8 JSON message 上限 4096 bytes，独立于 payload raw 2048-byte 上限。合法最大 Id
组合若无法容纳于 4096 bytes，必须拒绝，不得截断、缩写或跳过 MAC 字段。

解析要求：无重复/未知键、无非法 Unicode scalar、顶层 flat strings 加一个 I63 sequence、无
NaN/Infinity、受限深度和受限累计资源。`SESSION_OPEN` 与 `HEARTBEAT` 使用下述 exact bytes；
既有冻结 `BODY_BIND_NOTICE`/`BODY_BOUND` payload 则由各自 strict schema codec 验证精确键集合、
类型和值，不新增通用 JSON 键顺序或空白规范。任何 kind 都不得退回宽松通用 object 解码。
所有 ASCII Id 最长 128 bytes，UUID 精确 36 lowercase canonical bytes，UTC 精确 24 ASCII bytes
`uuuu-MM-dd'T'HH:mm:ss.SSS'Z'`；解析深度上限 4。outer 4096 与 payload raw 2048 是独立硬限制。

## 5. Frame MAC

MAC 输入使用 U32BE byte-length LP，除 sequence 外均按字段 bytes；sequence 使用固定
`U64BE`，但值域限制为 `0..I63_MAX`：

```text
LP_U32BE(
  "LUO_CONTROL_FRAME_V2",
  security,
  kind,
  profile,
  authenticatedPeerRole,
  authenticatedPeerInstallId,
  hubInstallId,
  connectionId,
  challengeId,
  directionLabel,
  U64BE(sequence),
  messageKind,
  routeId,
  sourceInstallId,
  sourceConnectionId,
  recipientConnectionId,
  challengeExpiresAt,
  rawPayload
)
```

- `directionLabel` 仅为 `PEER_TO_HUB` 或 `HUB_TO_PEER`，并选择对应派生 key。
- exact role/direction：`CLIENT_CONTROLLER` 与 `SERVER_AUTHORITY` 只能作为 authenticated peer；
  `AGENT_HUB` 只作为 Hub 端。`HEARTBEAT` 仅允许 authenticated peer↔Hub，不允许 peer↔peer。
- peer→Hub 时，authenticated peer role/install 来自该 socket 的认证上下文。
- Hub→peer 时，这两个字段仍是**该目标 peer session 的认证身份**，不是 `AGENT_HUB`
  label；这样双方对同一 session transcript 求得相同 bytes。
- role/install 绝不从 payload 或 routing metadata 推导。
- MAC 必须解码 raw32 后常量时间比较；不得比较字符串、前缀或反射另一方向 MAC。

### 5.1 Outbound sequence 与 bounded queue admission

每个方向使用单一有序 drain，最多一个 in-flight send。每个 exact session 的 outbound queue 容量精确
为 8 个已接纳 frame，**包含 in-flight frame**，无优先级旁路。sequence 在 bounded queue admission 时
分配，而不是在实际发送或 completion 时分配。一次 admission 必须原子完成：

1. 使用 provisional sequence 验证 message kind、payload schema、metadata 和 raw payload预算；
2. 确认 queue capacity 可用；
3. 编码完整 frame 与 MAC；
4. 对实际 encoded UTF-8 message 再检查 4096-byte 上限；
5. append immutable frame；
6. 递增 next sequence。

message kind、方向、schema、metadata、payload、编码、4096-byte 检查或 queue capacity 任一 precommit
失败均不消费 sequence，并必须同步 fence 后关闭 exact session；不得继续、重试或留下 hole。
frame 一旦入队，send exception、cancellation、unknown completion 或任何 post-admission failure 都必须
终止 exact session：先同步撤销其 role/currentness、fence authority、discard 剩余 queue，再 best-effort
await close。不得回收 sequence、继续 drain、重试或迁移 frame。I63 最大值后关闭，不 wrap。
旧 send completion 必须由 connection/generation identity fence，不能完成或推进 replacement session。

## 6. Bootstrap 与 sequence

### 6.1 SESSION_OPEN

Hub transport 在该 exact socket 建立并预留 role 时已生成 `connection_id` UUID。READY 完成后，
`SESSION_OPEN` 只向 peer 揭示这个已捕获的 immutable per-socket ID；不得再生成第二个 session ID。
重连必须获得新 ID。Hub 发送：

- direction：`HUB_TO_PEER`
- sequence：`0`
- messageKind：`SESSION_OPEN`
- routing/expiry metadata：全部空字符串
- raw payload 由 ASCII prefix、outer canonical UUID 原样 ASCII bytes、suffix 拼接，精确为：

```text
ASCII('{"security":"luosandbox.control-auth.v1","kind":"SESSION_OPEN","connectionId":"')
|| ASCII(outer.connectionId)
|| ASCII('"}')
```

不接受空白、键顺序或 escape 变体。peer 必须要求 `challengeId` 匹配当前已认证握手，使用
已派生的 `hubToPeerKey` 验证覆盖 raw payload bytes 的 frame MAC，校验 outer `connectionId`
为 canonical nonzero UUID，再据此构造唯一 expected payload bytes 并做 exact-byte compare。
全部通过后，peer 才首次采用该 outer ID，作为此 socket 不可变的 Hub connection ID；现有握手
未携带该 ID，不得要求 peer 与不存在的预先已知 Hub ID 比较。Hub 则对后续每个 peer→Hub frame
要求 outer `connectionId` 等于其 transport owner 已捕获的内部 `connection_id`。
peer 从本地 READY 验证完成起最多等待 5 秒本地 monotonic 时间；超时关闭连接。
等待期间不得发送任何 frame。成功验证 `SESSION_OPEN` 后，peer 必须把其首个
`PEER_TO_HUB` frame 原子入队为 `HEARTBEAT` sequence 0、raw payload exact bytes `{}`；在该
ack frame 之后才可按严格 sequence 入队后续 heartbeat 或业务帧。

Hub 在 `SESSION_OPEN` 完整 encoded frame 成功进入其 bounded outbound queue 时启动 5 秒本地
monotonic `WAIT_OPEN_ACK` deadline。只有收到并验证当前 connectionId/challengeId、正确方向 key、
MAC 和 sequence 0 的 exact `{}` heartbeat 后，该 session 才进入 `APPLICATION_READY`。在此之前，
Hub 不接受 peer business frame，也不向该 peer发送 BODY route 或其他 application frame。
本 heartbeat 只证明对端持有派生 key 并处理到 v2 session bootstrap，不证明远端业务逻辑、BODY
状态、binding、世界效果或持久性成功。

### 6.2 Sequence 空间

- Hub→peer：`SESSION_OPEN` 为 0，下一帧必须为 1。
- peer→Hub：第一帧必须为 0。
- 每个方向、每个 authenticated pair 独立维护严格连续 sequence。
- gap、replay、倒退、overflow、未知 kind、错误方向或 metadata schema 错误均关闭 exact
  connection、释放 ownership、同步 fence；不得自动重试或迁移 sequence。

### 6.3 Heartbeat liveness（2026-10-24 明确补充）

- `heartbeatTimeoutMs=2000` 只由入站合法 `HEARTBEAT` 刷新；业务帧、发送完成及无效帧均不得刷新。
  合法意味着当前 connection/challenge、方向、MAC、精确 `{}` payload 和连续 sequence 全部通过。
- Hub 收到 seq0 heartbeat ACK 时启动入站 heartbeat watchdog；peer 成功验证 `SESSION_OPEN`
  时启动该 watchdog 的初始 2000ms 窗口，此后仅合法 Hub heartbeat 可刷新。
  两端原有 5000ms bootstrap deadline 保持独立；`SESSION_OPEN` 不作为后续可刷新 watchdog 的心跳。
- 双方在 bootstrap 允许发送后，以各自本地 monotonic 时间每 500ms 提交 heartbeat；
  heartbeat 与业务共用同一有序队列，不得绕过队列或优先插队，也不得因持续业务而停止生产。
- 在 owner 处理任一 receive、send admission 或 tick 前，若本地 monotonic `now >= lastHeartbeat + 2000ms`
  （peer 尚未收到首个 heartbeat 时使用上述初始窗口），必须先关闭并 fence，迟到心跳不得复活连接。
  负数、回退或期限加法溢出均 fail closed。调度暂停或队列阻塞导致超时同样不得延长期限。
- 本补充只明确 v2 liveness，不修改现有 v1 heartbeat-only 实现；实现与运行激活门禁不变。

## 7. 第一阶段允许的 message kinds

| messageKind | 方向 | raw payload exact schema |
| --- | --- | --- |
| `SESSION_OPEN` | `AGENT_HUB`→authenticated `CLIENT_CONTROLLER`或`SERVER_AUTHORITY` | §6.1 构造的 exact bytes |
| `HEARTBEAT` | authenticated `CLIENT_CONTROLLER`/`SERVER_AUTHORITY`↔`AGENT_HUB` | exact bytes `{}` |
| `BODY_BIND_NOTICE` | authenticated `CLIENT_CONTROLLER`→`AGENT_HUB` | 既有 6-key strict-schema notice |
| `BODY_BIND_ROUTE` | `AGENT_HUB`→authenticated `SERVER_AUTHORITY` | nested notice schema 不变 |
| `BODY_BOUND_CANDIDATE` | authenticated `SERVER_AUTHORITY`→`AGENT_HUB` | 既有 5-key strict-schema BODY_BOUND candidate |

`BODY_BIND_NOTICE` raw payload 精确六键：

```text
security, kind=BODY_BIND_NOTICE,
serverBootId, gameConnectionId, bodyId, gameNonce
```

`BODY_BIND_ROUTE` raw payload 与 notice 完全相同，`kind` 仍为 `BODY_BIND_NOTICE`；受信 origin
不写入 nested payload，而由 authenticated outer session 和 routing metadata 提供。

`BODY_BOUND_CANDIDATE` raw payload 精确五键：

```text
security, kind=BODY_BOUND,
serverBootId, gameConnectionId, bodyId
```

该 candidate 不是 v1 意义上的最终 registry BODY_BOUND authority，也不能直接调用 prepare。
第一版 v2 不定义 withdrawal message；Hub current-role removal 与同步 local fence 才是权威失效点。

## 8. Routing metadata 矩阵

| messageKind | routeId | sourceInstallId | sourceConnectionId | recipientConnectionId | challengeExpiresAt |
| --- | --- | --- | --- | --- | --- |
| `SESSION_OPEN` | `""` | `""` | `""` | `""` | `""` |
| `HEARTBEAT` | `""` | `""` | `""` | `""` | `""` |
| `BODY_BIND_NOTICE` | `""` | `""` | `""` | `""` | `""` |
| `BODY_BIND_ROUTE` | Hub-generated route UUID | current Controller install ID | current Controller session `connectionId` | current Server session `connectionId` | `""` |
| `BODY_BOUND_CANDIDATE` | exact echoed route UUID | `""` | `""` | echoed Server session `connectionId` | canonical UTC copied from the Server-local verified challenge |

外层 `connectionId` 始终是 transport 在建立该 exact socket/role reservation 时分配的 immutable
Hub transport connection ID；`SESSION_OPEN` 只揭示它，不另造 ID：

- Client notice 的 outer connectionId 是 Controller session ID。
- Hub route 的 outer connectionId 是 Server session ID；原 Controller session ID 仅在
  `sourceConnectionId`。
- Server candidate 的 outer connectionId 是同一个 Server session ID。

该 ID 就是 transport generation；reconnect 获得新 ID，本草案不增加另一个 wire generation 字段。

## 9. Hub route capability

Hub **全局总计**最多保存 2 个 route records，包括所有 body 的 live records 与 terminal
tombstones。不得按 body、role、connection 或其他子表分别乘以 2。有效 record 或未满 Hub TTL
的 terminal tombstone 不得提前驱逐；全局容量满时拒绝新 notice，不迁移或覆盖旧 route。
coordinator 的既有 pending 上限 8 是独立后续阶段，绝不把 route 全局容量从 2 放大。
当全局 route 容量已满时，不得 silent drop、返回普通 reject 后保持连接、evict 或自动重试；Hub
必须关闭发送该新 notice 的 exact authenticated Controller session，并把它作为 Controller loss
在同一 owner turn 同步 fence。不得关闭无关 Server 或其他无关 peer。

每个 route record 固定：

```text
routeId = cryptographically random canonical UUID
tuple = (serverBootId, gameConnectionId, bodyId, canonical gameNonce)
controllerInstallId
controllerConnectionId
serverInstallId
serverConnectionId
challengeExpiresAt = UNSET until an authenticated current Server candidate is accepted
createdAtHubMonotonic
deadlineHubMonotonic = created + 5 seconds
state = ROUTED | CONSUMED | INVALIDATED
```

- duplicate candidate、重复消费、错误 route/tuple/source/recipient 或 terminal route 均失败。
- controller/server replacement、disconnect、role removal、timeout、mismatch、cancellation 或
  backpressure 使相应 route/candidate halves fail closed；不得与未来连接拼接。
- `BODY_BIND_ROUTE.challengeExpiresAt` 必须为空，因为六字段 notice 不含 expiry，Hub 此时没有
  可认证的 expiry anchor。
- candidate 到达时，Hub 先认证 current Server session，再按 exact routeId、tuple 和 recipient
  查找 `ROUTED` record；candidate 的 `challengeExpiresAt` 必须是 Server 从其本地 verified
  challenge 复制的 canonical UTC，而不是“echo Hub route”。Hub 要求本地 UTC
  `now < challengeExpiresAt`，然后记录该 candidate 值并执行最终 currentness/admission 检查。
  该 candidate outer frame 的 source 必须是当前 `SERVER_AUTHORITY` authenticated session；
  仅 payload kind 或 routeId 匹配不构成 Server origin。
- MAC 保护该 UTC 值，但它只提供额外 envelope cap，不证明或刷新 Server monotonic proof TTL。
- Hub receipt TTL 只是 Hub 本地 retention cap，不延长原 BODY proof TTL。
- Server local consume 必须继续使用原始 Java monotonic deadline；绝不把 Java absolute
  monotonic 值上 wire，也绝不与 Python monotonic clock 比较。
- Server 必须在自身原始 monotonic deadline 内消费 local verified proof并产生 candidate；Hub
  看到 UTC 未过期不能证明 Server monotonic proof 仍有效。
- route retention 只使用原始 Hub receipt deadline；terminalization 不重置或延长 5000ms TTL。达到原始
  deadline 的 live record 先 `INVALIDATED`，再可在同一 prune turn 移除；不得永久泄漏过期
  `ROUTED` record。

## 10. Coordinator admission/dispatch 契约

现有 `prepare()` 在创建 `_pending` 后直接 `await commands.send()`。v2 若获批准，必须在**同一个
现有 coordinator** 内拆成同步 admission 与异步 dispatch ticket；不是新增 coordinator。

### 10.1 同步 `admit_prepare`

该步骤与 current-role removal、route table、READY input 和 coordinator 共用一个 asyncio owner
turn。blocking store IO 明确禁止在该 owner loop 上同步执行。全部 store IO 必须提交到**同一个**
专用 store-owner worker；该 worker 拥有 open/provision、`read_candidate`、`commit_ready` CAS、readback、
native process-lock handle 与 close 的完整生命周期。全局最多 2 个 actual/logical outstanding store jobs，
包含 running、queued、completed-but-unconsumed handoff，以及已经 timeout/cancel/obsolete 但底层工作仍
未实际完成的 job；该上限覆盖 preflight 与 READY CAS，不是每类各 2。route 5 秒 deadline 或 coroutine cancellation 只能把
result 标记为不可用，不能取消未知 disk work、提前归还 worker slot或继续堆积任务。worker hung 时
fail closed 为 unavailable：不新建额外线程/worker、不无限重试、不承诺固定 wall-time 完成。

只有 exact running Job 可以完成一次并产生 immutable one-use snapshot；queued、clone或stale Job不能完成。
snapshot 至少绑定：Job identity、store instance identity、provision ID、body ID、
expected last epoch、store generation、next epoch、captured coordinator generation、route ID、Server
connection ID 和 Controller connection ID。该 snapshot 不是 epoch commit、binding 或 authority。

外部或可能 reentrant 的同步非 IO port 调用只能处于 pre-phase，返回后必须重新核验；
最终 capability consume、pending publication 和 ticket publication 必须处于无 external callback、
无 reentrancy、无 yield 的单一 final admission turn。该 final turn 期间：

- 不得 `await`；
- 不得调用可 yield 的用户 callback 或外部 async port；
- 精确核验当前 Server/Controller connection、install allowlist、route state、tuple、body、
  recipient、Hub monotonic deadline 和 UTC extra cap；
- 拒绝 `store is None`、quarantine、pending capacity 或 identity mismatch；
- pre-phase 接收专用 worker 返回的 immutable preflight snapshot；不得在 owner loop读磁盘；
- 对 snapshot 和任何可能 reentrant 的同步 port 返回后，进入 final turn 前重新核验 store instance、
  provision/body、expected epoch/store generation、coordinator generation、roles、route、candidate
  expiry、quarantine和pending capacity；
- 原子消费 one-use snapshot与route capability，构造并发布既有`_pending`和ticket；
- 返回 generation-scoped private dispatch ticket。

任一步失败必须回滚未发布 pending、使相关 capability `INVALIDATED`，移除所有相关 handoff 且不保留
unbounded consumed-history cache，也不产生 PREPARE effect。
兼容 wrapper 不得在生产代码中创建“可信 route”或 public test factory。

后续既有 `commit_ready` CAS 仍负责捕获 preflight 后发生的 epoch/store-generation race，并必须在同一
store-owner worker 执行；CAS mismatch 必须 fence，不能重新使用 snapshot。worker 必须创建并拥有其
SQLite connection/process-lock资源，不得把现有 connection 对象盲目跨线程移动。shutdown 必须
drain 或继续保有 worker及其资源直到真实工作结束；工作仍存活时不得报告 cleanup成功或释放资源。
现有 production epoch capability `UNAVAILABLE` 保持不变，本草案不声称 store worker 已实现。

READY 通过 ticket/currentness 校验后，对应 coordinator pending 进入 private internal phase
`COMMIT_IO_PENDING`（不是 wire COMMIT，也不表示 durable CAS success）。ticket 仍只保留下面定义的
state set，并且必须
捕获 coordinator generation、Server/Controller identity、pending identity、store identity/generation；
提交 CAS 前、worker completion 后、发送 wire COMMIT 前均重新核验。disconnect 发生在 CAS 期间不能取消
未知 disk work：必须立即同步 local fence；若 stale CAS 后来推进 epoch，该 epoch 是安全未使用 gap，绝不
rollback/reuse，且不得发送 COMMIT、建立 ACTIVE、binding或permit。

ticket 至少私有绑定：

```text
pending identity
coordinator generation
server connection ID
controller connection ID
session ID
route ID
state = UNDISPATCHED | DISPATCHING | DELIVERED_CURRENT | STALE_OR_UNKNOWN
```

`COMMIT_IO_PENDING` 不是 ticket state；它只属于对应 pending 在 READY CAS worker operation 期间的内部 phase。

### 10.2 异步 dispatch

- dispatch 在 `await commands.send(PREPARE)` 前后检查 ticket、pending identity、generation 和
  current roles。
- 不得持有 asyncio lock 跨 `await`。
- disconnect/replacement 必须先同步 `_fence_local`，清除 local authority/pending/routes，再做
  best-effort async revoke/notification。
- send await 期间失去 currentness 时，ticket 进入 `STALE_OR_UNKNOWN`；不能重试、迁移或声称
  PREPARE 未送达。
- ticket 为 `DISPATCHING` 时到达的匹配 READY，最多保留一个 unauthorized pending READY input；
  它不得改变 phase、写 epoch、发送 COMMIT、建立 binding 或产生 permit。duplicate 或 conflicting
  READY 必须 fail closed。
- `await send` 返回后必须重新核验 current Server、current Controller、coordinator generation、
  pending identity、ticket identity 和 phase仍为PREPARE。全部匹配才转为`DELIVERED_CURRENT`，
  再通过既有 READY validation 处理所保留的 READY，并在 epoch write 前再次核验 currentness。
- disconnect、replacement、cancellation、send failure 或 send result uncertain 必须同步 local fence，
  清除 retained READY，并令 ticket `STALE_OR_UNKNOWN`。
- 未达到 `DELIVERED_CURRENT` 的 ticket 绝不能因 READY 进入 epoch write 或 COMMIT；stale/unknown
  ticket 的 READY 必须拒绝。
- `UNDISPATCHED` 时收到 READY 是强制协议失败：同步 fence 并关闭 exact Server。`DISPATCHING` 可保留
  一个匹配 READY；任何第二个 READY（重复或冲突）均同步 fence 并关闭 exact Server。
- automatic prepare、COMMIT、ACTIVE、binding 或 permit 均被禁止；每阶段仍需独立权威输入。
- `store is None` 或 quarantine 保持 disabled，不因 authenticated route/candidate 自动恢复。
- worker hung、store close `CLEANUP_UNKNOWN` 或仍有真实工作时，保持 capability disabled，不创建
  replacement worker/store，不重试native handle close。upstream `70dcafb1` 只证明 UNKNOWN/no-retry
  cleanup 状态机修复，不证明 ACL、VFS 或 durability。

共享单 owner loop、无 yield admission 和断连线性化是实现 gate；当前 Python transport/coordinator
尚未实现这些 v2 原子性保证。

## 11. 失败处理

- unknown kind、错误方向、MAC/sequence/profile/schema/metadata 失败：关闭 exact connection，
  释放 role，sync fence，不重试。
- notice/route/candidate tuple、recipient、source或expiry mismatch：双方 halves terminal invalidation，
  不保留可与未来连接匹配的缓存。
- 全局 route capacity 2 满：不接纳新 notice，不驱逐任何 live record 或 Hub TTL 内 tombstone；
  必须关闭该 exact Controller sender并同步执行Controller-loss fence，不得仅普通reject或silent drop。
  不得通过per-body子表或coordinator pending 8绕过该总量。
- send queue 满、send exception、cancellation 或 post-send currentness loss：fail closed；若发送结果
  不可证明则标记 UNKNOWN，不盲重试。
- disconnect 与 candidate/admit 并发：只有 shared owner turn 的线性化顺序决定结果；role removal
  与 sync fence 先发生则 candidate 不得 prepare。
- candidate 接纳成功仍仅产生 PREPARE ticket；COMMIT/ACTIVE/permit 继续由既有分阶段协议控制。
- `SESSION_OPEN` send admission/completion unknown：先同步移除并 fence 该 affected peer 的 role、
  routes 和 coordinator authority，再关闭 exact peer；不得关闭无关 peer。
- `BODY_BIND_ROUTE` send unknown：使该 route terminal invalid，按 recipient Server generation
  invalidation 该 Server generation 的全部 routes，关闭 exact Server session；不得关闭源 Controller
  或无关 peer。
- `BODY_BOUND_CANDIDATE` outbound send unknown：关闭发送方 exact Server session并同步 fence；
  不重试、不迁移 candidate。
- 上述关闭或失效不能修复 `store is None`、quarantine 或 platform `UNAVAILABLE`；保持 disabled。

## 12. 后续验证矩阵（尚未执行）

### 12.1 Cryptographic/interoperability

- Java/Python 对现有 16-field PEER_PROOF transcript bytes、authDigest、两个方向 key 完全一致。
- frame MAC LP bytes、U64BE sequence、empty metadata、raw payload和 known-answer MAC 一致。
- 反射方向、role/install、challenge、connectionId、profile、routing metadata 任一变化均失败。
- derived keys 不进入 DTO/log/repr，close 后不再可用。
- configHash fixture 必须覆盖精确 v2 deployment record；旧 manifest/hash、遗漏字段、security/profile/
  KDF/frame domain alias 全部拒绝，modHash/bindingHash原义不变。

### 12.2 Bootstrap/frame parser

- SESSION_OPEN 必须 seq0、匹配当前 handshake challengeId，并通过 derived-key frame MAC；
  校验 canonical outer connectionId 后构造唯一 ASCII payload bytes 精确比较，peer 才首次采用该 ID。
  Hub 对后续 peer→Hub frame 按其内部 transport connection_id 核验；5 秒本地 monotonic timeout，
  reconnect 必须新 ID。
- peer 在 SESSION_OPEN 前 heartbeat/business 均关闭连接。
- SESSION_OPEN 后 peer 首帧必须是 seq0 exact `{}` heartbeat；其前入队 business、错误首 kind或seq均
  fail closed。Hub 在 verified ack 前保持 WAIT_OPEN_ACK，不接受business或发送route；5秒超时关闭。
- replay/gap/overflow/mixed v1/v2/unknown kind/wrong direction 全部 fail closed。
- SESSION_OPEN whitespace/order/escape变体和heartbeat非`{}`拒绝；冻结notice/candidate使用既有
  strict-schema codec，不新增通用JSON键顺序要求。duplicate/unknown keys、invalid UTF-8/base64url、
  payload 2049、message 4097、最大 Id 超 envelope 均拒绝且不截断。
- queue admission 前 encode/size failure不消费sequence；admission后send/cancel/unknown failure关闭exact
  session、先fence并discard queue，不回收sequence或继续hole。单drain一inflight、I63无wrap、旧
  completion不能推进replacement generation。

### 12.3 Route/currentness

- notice→route（expiry空）→candidate（Server提供canonical UTC）exact happy path只消费一次；Hub
  验current Server、exact route/tuple/recipient和`now < expiry`后才记录candidate值并final admit。
- Controller A notice + Controller B current、Server replacement、wrong recipient、wrong nonce/tuple、
  stale route和duplicate candidate全部失败。
- controller disconnect/replacement 与 candidate 到达的两种 owner-turn线性顺序均验证；断连先发生
  时 prepare count 必须为零。
- BODY_BIND_ROUTE expiry 非空必须拒绝；candidate expiry 必须来自当前 Server authenticated frame，
  canonical UTC、`now < expiry`，且不得要求它 echo route 中不存在的值。
- 全局 live+tombstone 总容量2；多body不能乘表扩容，coordinator pending8不扩大route容量；
  5秒prune、无提前eviction或跨连接迁移。
- route全局容量满必须关闭exact Controller sender并同步Controller-loss fence；不silent drop、普通
  reject、evict或retry，也不关闭无关Server。
- SESSION_OPEN uncertainty只关闭/fence affected peer；BODY_BIND_ROUTE uncertainty invalidates route和
  exact recipient Server generation全部routes并只关闭该Server；candidate uncertainty只关闭exact
  Server。均不得影响无关peer或自动恢复disabled store。
- Hub UTC cap不能刷新 Server original monotonic deadline；不传输/比较跨进程 absolute monotonic。

### 12.4 Coordinator authority

- 专用 store worker 的 immutable snapshot 和 pre-phase reentrant non-IO port返回后做currentness重检；
  owner loop不执行blocking store IO。final admission turn无await、yield或external callback，并原子
  消费snapshot与route capability、发布pending和ticket。
- 失败回滚pending并invalidate route。
- send await 前/中/后 disconnect，local fence 均先清 authority；stale ticket UNKNOWN且不重试。
- DISPATCHING期间最多缓存一个未授权matching READY；duplicate/conflict fail closed，且不得phase
  change/epoch write/COMMIT。只有post-send全current recheck后`DELIVERED_CURRENT`才可按既有验证处理，
  并在epoch write前再次recheck。disconnect/cancel/uncertain清除缓存；stale READY不能commit。
- READY、disconnect、candidate admission 和 role replacement 必须由同一 asyncio owner loop串行；
  测试不得用独立锁/线程模型掩盖真实 owner-turn 顺序。
- store unavailable/quarantine不能因 candidate 自动激活。
- PREPARE 不是 binding；无 READY/epoch commit/COMMIT/ACTIVE 时 stage、permit、Agent mind不变。
- preflight过期、route/controller/server replacement或coordinator generation变化后返回的snapshot被
  忽略并终止；stale `commit_ready` CAS mismatch必须fence。
- 全局最多2个store outstanding（preflight、CAS、queued、running、completed-unconsumed handoff合计），
  timeout/cancel/obsolete但实际仍运行的job继续占slot；
  hung worker不回收slot、不增开线程、不堆积retry。SQLite connection/lock不得盲目跨线程，shutdown
  不得在live work尚存时伪称cleanup或释放owned resources。

## 13. Final decisions、实现 DAG 与冻结边界

本文已没有语义 OPEN：protocol/profile、manifest bytes、queue 8、route 2、pending 8、store outstanding 2、
bootstrap ACK、failure policy、READY ordering、whole-store worker ownership 与 cleanup UNKNOWN 均由正文确定。
剩余项目分为“实现证据”与“外部运行 gate”，不能再用测试 facade 冒充完成。

### 13.1 五个最小实现切片

1. **Manifest loader + session codec（保持 disabled）**：实现 role-scoped raw manifest loader/hash gate、
   LP/KDF/frame strict codec、key lifecycle与负向 corpus，不接 endpoint/BODY。
2. **Ordered transport + Hub role owner**：实现 SESSION_OPEN/implicit ACK、queue 8、generation fence、
   exact role removal与single-owner callback ingress，仍不接BODY route。
3. **Bounded route/candidate（无 activation）**：实现global route 2、原始TTL tombstone、notice/route/
   candidate metadata与failure scope，只产生非授权 candidate。
4. **Existing coordinator + whole-store worker**：只深化现有 coordinator，加入final admission、ticket、
   early READY、COMMIT_IO_PENDING，以及同一worker完整store生命周期；不创建第二coordinator/store。
5. **Disabled real Java/Python loopback acceptance**：真实进程验证manifest、codec、transport、route、worker
   与fault matrix；仍不启用permit、actuation或游戏。

依赖顺序为 `1 → 2 → 3 → 4 → 5`；不得并行绕过前序契约。

### 13.2 Startup 与注册

channel、discriminator与EventBus wiring只能在startup显式注册一次，启动后immutable；reconnect只创建新
session/generation/owner handles，不重复注册global channel。Server generation start/stop必须有显式owner。
当前 `BodyProofChannelRegistration.registerForge()` 没有production调用，Forge stop/logout adapters仍显式
unregistered；不得把声明类当成runtime wiring。

manifest、credential、binding provision或platform capability任一缺失，整个control保持inactive。协议冻结
不能将production ACL/VFS/durability从`UNAVAILABLE`变为available，也不授权hot activation。

### 13.3 Evidence 与外部 gate

- 历史crypto/config/model/worker evidence是test-only；本文变更使旧draft hash/vector pin发生drift，冻结后
  必须重生并独立评审，旧PASS不证明当前规范。
- protocol freeze 已由 parent 于 2026-10-23 在独立评审 `PASS_FOR_SPEC_FREEZE` 与两项文字 remedy 验证后
  生效；它不是 runtime implementation批准。后续每个实现切片仍须单独、明确、bounded授权与验收。
- EULA、账户、world、实际游戏、人工acceptance是后续外部authority gate，不是待继续设计的技术OPEN。
- 在五个实现切片、ACL/durability gate和独立runtime授权完成前，现有v1 heartbeat-only、BODY join未实现、
  epoch `UNAVAILABLE`、binding/permit/actuation disabled 的状态不变。
