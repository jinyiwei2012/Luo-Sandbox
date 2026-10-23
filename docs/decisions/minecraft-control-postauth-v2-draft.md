# Minecraft control post-auth v2（草案）

## 0. 状态与边界

- 状态：**DRAFT / NON-NORMATIVE / NOT FROZEN**。
- 本文只记录一个待独立评审的协议与协调器演进方向，不修改或替代现行
  `minecraft-control-security-v1.md`。
- 本文不授权实现、端点启用、BODY 绑定、epoch 提交、permit、输入控制或游戏运行。
- 只有后续独立安全评审、父会话明确批准并记录 supersession，才能冻结并替代冲突的
  v1 条款。
- 已有 206/216 测试、历史 Java/Python 向量及 build/reobf 证据均不证明本 v2 草案。
- 文中“现有”描述当前源码事实；“提议”“必须”均指本草案若获批准后的目标契约。

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

当前上游 coordinator 的 `_fence_local` 已表达“在任何 cleanup await 前同步清除本地 authority”
的原则；本草案要求未来 transport/current-role removal 与该同步步骤处于同一串行 owner turn。
这是一项实现门槛，不是当前 transport 已具备的能力。

## 2. 协议不兼容性

- 新 post-auth profile 字面量：`LUO_CONTROL_POSTAUTH_V2`。
- v2 不允许 v1 fallback、v1/v2 heartbeat 混用或同一物理连接原地升级。
- 未验证 v2 `SESSION_OPEN` 的 peer 不得发送任何 post-auth frame；验证后首个 frame 必须是
  sequence 0、payload 精确为 `{}` 的 `HEARTBEAT`，作为 open ACK。业务帧只能作为 sequence 1
  或更晚的帧，排在该 heartbeat 后的同一单一有序发送管道中。Hub 必须先验证 sequence 0 并同步
  将该 exact session 转为 `APPLICATION_READY`，才能处理后续业务帧；不存在额外的 Hub→peer
  application-ready 确认消息。
- 收到 v1 post-auth frame、未知 profile 或混合 sequence 空间时，关闭该 exact connection，释放
  其 role ownership，并同步 fence 相关本地状态。
- v1 HELLO/CHALLENGE/PROOF/READY transcript 的 16 个字段、domain 和 label 不因本草案改变。
- 本草案选择不增加 wire hash 字段。既有 `configHash` 必须传递覆盖一份显式部署记录，其精确
  语义至少包含：

```text
security=luosandbox.control-auth.v1
postAuthProfile=LUO_CONTROL_POSTAUTH_V2
sessionKdfDomain=LUO_CONTROL_SESSION_KEY_V2
frameMacDomain=LUO_CONTROL_FRAME_V2
```

  v2 连接必须使用覆盖该记录的新 manifest/config hash；旧 manifest、旧 hash、alias 或未声明
  v2 profile 的配置一律拒绝，不提供 v1 fallback。`modHash` 与 `bindingHash` 保持 v1 原义。
  包含该记录的具体 manifest、canonical bytes 与 hash 构造仍是实现和独立互操作验证门槛，本文
  不声称已经存在或验证。

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
| `messageKind` | 本文列出的 ASCII Id |
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

每个方向使用单一有序 drain，最多一个 in-flight send。sequence 在 bounded queue admission 时
分配，而不是在实际发送或 completion 时分配。一次 admission 必须原子完成：

1. 使用 provisional sequence 验证 message kind、payload schema、metadata 和 raw payload预算；
2. 确认 queue capacity 可用；
3. 编码完整 frame 与 MAC；
4. 对实际 encoded UTF-8 message 再检查 4096-byte 上限；
5. append immutable frame；
6. 递增 next sequence。

上述任一步在 commit 前失败都不消费 sequence；策略可以选择关闭 session，但不得留下 hole 后继续。
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

## 10. Coordinator admission/dispatch 拆分提议

现有 `prepare()` 在创建 `_pending` 后直接 `await commands.send()`。v2 若获批准，必须在**同一个
现有 coordinator** 内拆成同步 admission 与异步 dispatch ticket；不是新增 coordinator。

### 10.1 同步 `admit_prepare`

该步骤与 current-role removal、route table、READY input 和 coordinator 共用一个 asyncio owner
turn。blocking store IO 明确禁止在该 owner loop 上同步执行。epoch preflight 必须提交到**一个**
专用 store worker；全局最多 2 个 outstanding preflights，包含 queued、running 以及已经 timeout/
cancel/obsolete 但底层工作仍未实际完成的 job。route 5 秒 deadline 或 coroutine cancellation 只能把
result 标记为不可用，不能取消未知 disk work、提前归还 worker slot或继续堆积任务。worker hung 时
fail closed 为 unavailable：不新建额外线程/worker、不无限重试、不承诺固定 wall-time 完成。

worker 返回 immutable one-use snapshot，至少绑定：store instance identity、provision ID、body ID、
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

任一步失败必须回滚未发布 pending、使相关 capability `INVALIDATED`，且不产生 PREPARE effect。
兼容 wrapper 不得在生产代码中创建“可信 route”或 public test factory。

后续既有 `commit_ready` CAS 仍负责捕获 preflight 后发生的 epoch/store-generation race；CAS mismatch
必须 fence，不能重新使用 snapshot。worker 必须创建并拥有其 SQLite connection/process-lock资源，
或使用经证明 thread-safe 的明确 API；不得把现有 connection 对象盲目跨线程移动。shutdown 必须
drain 或继续保有 worker及其资源直到真实工作结束；工作仍存活时不得报告 cleanup成功或释放资源。
现有 production epoch capability `UNAVAILABLE` 保持不变，本草案不声称 store worker 已实现。

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
- automatic prepare、COMMIT、ACTIVE、binding 或 permit 均被禁止；每阶段仍需独立权威输入。
- `store is None` 或 quarantine 保持 disabled，不因 authenticated route/candidate 自动恢复。

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
- 全局最多2个store preflight outstanding，timeout/cancel/obsolete但实际仍运行的job继续占slot；
  hung worker不回收slot、不增开线程、不堆积retry。SQLite connection/lock不得盲目跨线程，shutdown
  不得在live work尚存时伪称cleanup或释放owned resources。

## 13. Selected proposals 与剩余证明门槛

以下六项已作为**本草案内部的 selected proposals**统一正文，不再作为相互竞争的 OPEN 选项；
“selected”只表示草案方向已选定，不表示规范冻结、实现获准或证据通过：

1. **Deployment binding**：不增加 wire hash 字段；`configHash` 必须覆盖精确 v2 deployment record，
   旧 manifest/hash拒绝，`modHash`/`bindingHash`保持原义。
2. **Bootstrap acknowledgment**：不增加新 message kind；peer首个`PEER_TO_HUB` frame必须是seq0
   exact `{}` heartbeat，Hub验证后才`APPLICATION_READY`。
3. **Sequence reservation**：完整frame在bounded queue admission原子commit时分配sequence；
   post-admission任一send uncertainty关闭exact session并discard queue，不回收、不跨hole继续。
4. **Send uncertainty scope**：SESSION_OPEN只影响该peer；BODY_BIND_ROUTE影响exact recipient Server
   generation及其routes；candidate uncertainty关闭exact Server。全局route满关闭exact Controller。
5. **Store preflight**：blocking IO只在一个专用worker；全局最多2个真实outstanding jobs，obsolete
   work不提前释放slot；owner loop final admission无IO/callback/yield并消费one-use snapshot。
6. **Domain literals**：继续使用`security=luosandbox.control-auth.v1`，同时强制v2 profile、独立KDF
   与frame-MAC domains并纳入configHash deployment record；拒绝alias与v1 fallback。

冻结前仍必须由独立评审与父会话验证的实现证明门槛包括：deployment manifest精确内容与canonical
bytes/hash构造、Java/Python crypto vectors、SESSION_OPEN/ack真实状态机、bounded queue原子性与旧
completion generation fence、uncertainty精确关闭范围、single-owner READY linearization、store worker
线程/SQLite/process-lock所有权、hung/shutdown资源生命周期、snapshot one-use/CAS race，以及全部§12
负向矩阵。任何一项未证明都不得用“selected proposal”冒充实现完成。

未完成独立评审、明确 supersession 和父会话 freeze 批准前，本文继续保持
**DRAFT / NON-NORMATIVE / NOT FROZEN / NO IMPLEMENTATION PERMISSION**；现有
v1/heartbeat-only、BODY join未实现、production epoch `UNAVAILABLE`、binding/permit/actuation
disabled 的状态不变。
