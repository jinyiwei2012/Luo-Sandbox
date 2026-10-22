# Minecraft Control Security v1：角色认证、身体绑定与短期 permit

- 状态：SPEC_FROZEN，2026-10-23；Oracle 定向独立复审 PASS，四项规格阻塞已关闭。可作为离线 auth/session/permit 实现依据；不构成实现、G2 或 runtime 通过。
- 上位语义：[冻结世界消息契约](minecraft-contract-v1.md)；方向：[集成决定](minecraft-integration.md)；依据：[模式研究](../research/integration-patterns.md)、[交付计划](../autonomous-delivery-plan.md)。
- 已读协调进度：G1 独立 PASS，Phase2 仅获准离线开发；Java policy foundation 与 Python typed seam 并行，本文件不改其代码/共享规格。
- 本文只新增独立安全接口；世界八种消息及其 admission/receipt/UNKNOWN 规则保持不变；父会话批准后才交实现者。

## 1. 威胁边界与拥有者

固定 Forge 47.4.28、已冻结模组/配置 hash、单隔离 body、非 OP、单 Agent worker；覆盖恶意普通客户端和 stale/buggy controller。
不覆盖恶意服务端/模组、OS 或密钥失陷、跨机器主动代理、任意未知模组；HMAC 不是加密，不声称抵御这些边界。
物理 client/server 各自出站连接 Agent FastAPI hub；逻辑 Agent request → Server admission → real-client permit；事实/receipt Server 直达 Agent。
hub 属受信安装边界，但只路由；不能凭 CLIENT_CONTROLLER 的连接内容制造 SERVER_AUTHORITY origin，不能准入世界动作。
Agent ControlSessionRegistry 独占 actor/world/body/character 映射、sessionId 和单调 I63 epoch；server 独占 serverBootId、活体绑定、permit 表与世界结果。
本地静态配置严格含 actorId/worldId/bodyId、内部 characterId、允许的 server/client installId、期望离线 UUID、维度/区域和精确配置 hash。
离线 UUID 只是查找候选；登录后先禁止该候选普通包交互，直到实际游戏连接完成第 5 节证明；无证明者断开而非当普通玩家绕过。
同名/同 UUID 后登录者不得替换已绑定连接；出现冲突撤销授权并拒绝新连接，不迁移 playerdata 或沿用旧 permit。

## 2. 持久 epoch：独立 SQLite 修订（已批准离线实现）

本节替换 368689f 的 JSON计数文件方案；定向独立审查的文档冲突已修正，可作为离线实现依据。其余冻结角色/HMAC/绑定/permit语义不变，不表示平台能力或完整G2通过。
采用候选 B：Agent仓库外私有数据目录的独立 `epochs.sqlite3`，Python stdlib sqlite3 rollback DELETE+synchronous EXTRA（读回3），配 `epochs.lock` 与显式 `provision.json`。
详见[epoch存储决策](minecraft-epoch-store.md)的候选比较、schema、权限、CAS、恢复和证据门槛；不得复用业务WAL/NORMAL数据库或宣称EXTRA自动保证Windows目录/断电持久性。
Win32 no-share进程锁贯穿生命周期；版本/FS/VFS/ACL/锁/恢复证据不足即disabled，不退回JSON替换、内存epoch或自动清零。
固定顺序：read_candidate只计算next → PREPARE撤旧/证明live body静止 → 匹配READY → 事务原子CAS持久提交lastEpoch/generation → COMMIT → Server ACTIVE。
READY前崩溃不消费候选；持久提交后COMMIT前崩溃已消费不复用；COMMIT/ACTIVE丢失必须fence/对账后严格更高epoch恢复，PREPARE不创建permit。
commit_ready只接registry从实际SERVER认证上下文验证的不可变READY context，不能接外部ready bool；提交前fresh read+预期generation/provision/body精确CAS。
COMMIT/读回未知则禁用、保持锁、只读重开学习counter；不能据旧值推断未提交，不重用未知候选；hot journal只在受控SQLite恢复中处理，不自行删journal。
缺DB/marker/锁、损坏/类型/version/hash不符、只读/权限/锁冲突、I63耗尽、恢复不确定均fail closed，禁止已provision安装自动重建。
recordHash仅检偶然损坏，不防一致快照回滚；随机新sessionUUID/serverBootId不代替单调epoch、撤旧permit和quiescence。
旧UNKNOWN保持原语义；独立counter数据库不是action journal、durable exactly-once或人格记忆。父会话/oracle批准本修订前不据此实施存储。

## 3. 凭据、端点与安全消息格式

每个安装三组独立随机 32-byte key：K_server（Server↔hub）、K_client（Client↔hub）、K_body（Server↔指定 controller 的活体连接证明）。
K_server 只在 Server/hub，K_client 只在 Client/hub，K_body 只在 Server/指定 Client；不得三者复用、互相推导或向 client 下发 K_server。
角色为 `SERVER_AUTHORITY|CLIENT_CONTROLLER|AGENT_HUB`；keyId 为不含密钥的 Id，allowlist 精确映射 peerRole/installId/bodyId/keyId。
本地只允许 ws://127.0.0.1 或 ws://[::1]，hub 仅 bind loopback；拒绝 wildcard、URL userinfo/query token、TLS bypass 和隐式跨主机重定向。
非回环必须 wss+验证证书/主机名+同样双方证明，部署前另验 TLS/网络边界；首期未验证远程，不宣称仅 HMAC 可防 MITM。
secret 由本地受限文件引用 provision，不进 Git/命令行/URL/模型上下文；未 provision/mode=disabled 默认不启动控制。
安全消息为独立 strict JSON，`security="luosandbox.control-auth.v1"` 与 `kind`；不是伪装进世界八种消息的额外 type。
沿用世界契约 strict UTF-8、无重复/未知字段、I63/Uuid/Utc、正零规则；安全消息累计上限 4096 bytes、深度 4。
`B32`：32 原始 bytes 编码为恰好 43 字符 canonical base64url、无 `=`；只接受 alphabet `[A-Za-z0-9_-]`，解码后重编码必须相同。
Hash 为 SHA-256 的 32 原始 bytes，线上 B32；配置文件可显示 64 lowercase hex，但 transcript 不签 hex 字符串。
role/install/keyId/protocol 为 ASCII（Id 上限128），challengeId 为 Uuid；nonce 必须 CSPRNG 独立 32 bytes，生产拒绝固定测试 nonce。

### 3.1 不可变 origin、方向与 DEFAULT_DENY 矩阵

origin 必须取实际已认证 WS 连接对象（role/installId/connectionId/keyId）或实际 SimpleChannel 发送侧/连接对象，绝不取 payload role。以下是完整允许集合，未列 kind、反向、状态或来源全部 DEFAULT_DENY。
Hub→peer 的物理对端是 AGENT_HUB；仅 hub 内部 Agent/registry 拥有者可生成相应消息，转发 server/client 数据必须保留不可变原 origin，不能赋予 hub/registry 权限。
| 路径 / 实际源 → 接收方 | 允许 kind / 必须状态 |
| --- | --- |
| WS 未认证 peer → hub | HELLO，然后仅本连接 pending 的 PROOF；HELLO role 只是 allowlist 查询，不是已认证 origin |
| WS hub → 未认证 peer | CHALLENGE，然后仅该 pending 的 READY；须验证 hubProof/readyProof 后才能获得 AGENT_HUB 身份 |
| WS CLIENT_CONTROLLER → hub/Server | BODY_BIND_NOTICE、LOCAL_EMERGENCY_STOP；仅当前唯一已认证 controller，匹配 body/game tuple |
| WS SERVER_AUTHORITY → hub/registry | BODY_BOUND、BINDING_READY、BINDING_ACTIVE；仅当前已认证 Server，匹配 pending binding |
| WS registry（经 AGENT_HUB）→ Server | BINDING_PREPARE、BINDING_COMMIT；仅内部 registry 产生，绝不转发 peer 自报同名 kind |
| WS Agent（经 AGENT_HUB）→ Server | 世界 request、cancel、reconcile；仅内部 typed Agent 控制口产生，仍由 Server sole admission |
| WS SERVER_AUTHORITY → hub/Agent | 世界 observation、admission、receipt、cancel_result、reconcile_result；直接服务端 origin，含受控历史重放 |
| WS 双向：当前 Server↔hub 或 Client↔hub | HEARTBEAT；仅已认证连接，sequence 归该连接所有 |
| SimpleChannel Server → 候选实际 game connection | BODY_CHALLENGE；仅对应 pending connection |
| SimpleChannel 实际 Client connection → Server | BODY_PROOF；仅该 connection 的 pending tuple，无世界消息/registry 权限 |
| SimpleChannel Server → 已绑定实际 Client connection | BODY_ACTIVE、PERMIT、REVOKE、HEARTBEAT；精确绑定，PERMIT 另受撤销 latch/lease 限制 |

CLIENT 明确不得发 BODY_BOUND/BINDING_READY/BINDING_ACTIVE 或任一 server 世界类型；SERVER 不得发 registry PREPARE/COMMIT、Agent request/cancel/reconcile 或 client notice。
所有权限检查同时核验 origin+方向+认证状态+绑定；authority/binding 消息错角色时关闭违规连接并 fence 当前相关 binding，清 pending、撤 permit，不仅丢包继续。未经认证输入只可关闭/拒绝，不得据其自报 body 撤销他人绑定。

## 4. WS 双向认证：精确 transcript 与流程

每条 payload 必须且仅有以下键；安全字段均在顶层，除下列字段外还必须含 security/kind。
| kind | 精确其余键 |
| --- | --- |
| `HELLO` | `peerRole,peerInstallId,hubInstallId,keyId,protocol,modHash,configHash,bindingHash,peerNonce` |
| `CHALLENGE` | `challengeId,hubNonce,issuedAt,expiresAt,hubProof` |
| `PROOF` | `challengeId,peerProof` |
| `READY` | `challengeId,readyProof` |

modHash 是部署固定 mod manifest 原始文件 bytes 的 SHA256（不签重新排序后的 JSON）；configHash 同理覆盖策略/限制配置。
bindingHash 是只读 provisioned binding manifest 原始 bytes 的 SHA256，包含实际内部角色映射；配置双方必须持有同一已核对文件。
HELLO 未认证，只可索引 allowlist；Server/Client 只接受本连接 pending HELLO 的 challenge，不能自动采纳未知 hubInstallId/hash。
`LP(x)=U32BE(len(x)) || x`，无 NUL/分隔符、无 BOM；所有字段分别 LP，长度单位 bytes；ASCII/UTF-8 不混用平台默认编码。
T(label) = LP 串联以下固定顺序的 16 个字段：
`domain, label, peerRole, "AGENT_HUB", peerInstallId, hubInstallId, keyId, protocol, modHash, peerNonce, hubNonce, challengeId, issuedAt, expiresAt, configHash, bindingHash`。
domain 固定 ASCII `luosandbox.control-auth.v1`；label 分别 `HUB_PROOF|PEER_PROOF|READY_PROOF`；role/install/key/protocol/UUID/Utc 使用 ASCII bytes。
mod/config/binding Hash 和两个 nonce 用原始 32 bytes；issuedAt/expiresAt 用严格三位 UTC ASCII；U32BE 字段长度不接受有符号/varint。
Hub 收 HELLO 后创建 challengeId/nonce，expiresAt=issuedAt+5000ms，保存本连接独占 pending transcript 与本机单调失效点。
hubProof=HMAC-SHA256(K_role,T(HUB_PROOF))；peer 校验全部预期字段、时间与 hubProof 后发送 peerProof=HMAC(K_role,T(PEER_PROOF))。
Hub 验证 peerProof 并消费 challenge 一次，发送 readyProof=HMAC(K_role,T(READY_PROOF))；peer 验证 READY 后才把连接标记 authenticated。
MAC 必须先严格解码为 32 bytes，再用 Java/Python 常量时间 bytes 比较；不得字符串 `==`、MAC 前缀比较或反射 HUB_PROOF 作为 peerProof。
challenge 单连接单次使用；失败/断线即销毁，重新握手生成双方新 nonce；跨连接/跨 role/换 hash/换 keyId 均不接受旧 proof。
expiry 同时检查本地 monotonic 5s 与 Utc（未来偏差≤1000ms、过期拒绝）；时钟异常 fail closed，不延长 pending deadline。
Hub 只有验证 peerProof 后才允许注册该 role；注册/READY 丢失不得 actuation，peer 必须确认其认证状态并走双连接绑定步骤。
READY 不是 server admission、world receipt 或 body grant；role 来源保存在不可变连接上下文，不从下游 payload 重新取值。

### 4.1 可复算的非秘密 transcript 向量

固定测试 key = hex `000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f`，只用于离线测试。
输入：label=PEER_PROOF、peerRole=CLIENT_CONTROLLER、peerInstallId=client-1、hubInstallId=hub-1、keyId=test-key-1、protocol=luosandbox.minecraft.v1。
modHash=00×32、peerNonce=11×32、hubNonce=22×32、challengeId=00000000-0000-4000-8000-000000000001。
issuedAt=2026-10-23T00:00:00.000Z、expiresAt=2026-10-23T00:00:05.000Z、configHash=33×32、bindingHash=44×32。
LP transcript 长度=416 bytes；起始 hex 为 `0000001a6c756f73616e64626f782e636f6e74726f6c2d617574682e76310000000a504545525f50524f4f46`。
期望 HMAC-SHA256 hex=`95141dc1c381eb134b3d1f3df405d6e2ee2058e31f99b1269348b4f56f2a4921`。
该向量由本轮只读 Python 标准库离线计算，不是 Java 互操作/生产实现证据；完整 416-byte hex 与另两 label 的 fixture 由实现者生成并独立核对。

## 5. 真实游戏连接绑定：PSK 证明身份，不证明昵称或世界权限

Server 在候选离线玩家加入时建立 server-owned gameConnectionId:Uuid、随机 gameNonce:B32，按连接保持 pending，默认禁止身体普通交互。
通过实际 Forge SimpleChannel 发只对该连接可见的严格消息（下表字段均还含 security/kind）；连接对象/玩家引用从 handler 上下文取得，不从客户端声明选择。
| kind | 精确其余键 |
| --- | --- |
| `BODY_CHALLENGE` | `serverBootId,gameConnectionId,bodyId,gameNonce,issuedAt,expiresAt` |
| `BODY_PROOF` | `serverBootId,gameConnectionId,bodyId,clientInstallId,gameNonce,issuedAt,expiresAt,proof` |

Server BODY_PROOF transcript = LP 字段：`"luosandbox.body-bind.v1","CLIENT_BODY_PROOF",serverInstallId,clientInstallId,bodyId,serverBootId,gameConnectionId,gameNonce,issuedAt,expiresAt,bindingHash`。
其中 nonce/hash 用 raw32，其余 ASCII；proof=HMAC(K_body,transcript)。5s 单调失效、单次 challenge、严格常量时间比较，逻辑与 WS challenge 相同但域/凭据独立。
只接受原 gameConnection 对象上对应 pending challenge 的 proof；Server 拥有并关联精确 tuple=(serverBootId,gameConnection对象及gameConnectionId,bodyId,gameNonce,issuedAt,expiresAt)，仅该 pending owner 可一次性消费/撤销；所有标识/hash/时间必须吻合。
Client 同时经已认证 WS 将 `BODY_BIND_NOTICE`（security/kind+`serverBootId,gameConnectionId,bodyId,gameNonce`）交 registry；hub 只路由该 CLIENT role notice 给 Server。
BODY_BIND_NOTICE 不含 clientInstallId 字段；其身份必须取不可变 K_client WS origin 的 clientInstallId/connectionId，且必须等于 BODY_PROOF transcript 内 clientInstallId、allowlist 中该 body 唯一 controller 映射和当前唯一已认证 controller 连接。
hub 路由时保留受信 origin，不能把其他 controller 的认证存在性当作此 notice 的身份。Server 交叉匹配同一 pending tuple 的 notice+proof；仅证明活体归属，不准入世界动作。
controller 连接替换/失效、installId 不符或 tuple 不匹配立即使该 pending challenge/notice 无效，撤相关绑定/permit；不得让缓存 notice 跨连接替换后仍可消费。
Server 给 registry 的 `BODY_BOUND`（security/kind+`serverBootId,gameConnectionId,bodyId`）必须来自 SERVER_AUTHORITY；registry 才可准备 session binding。
registry 的 `BINDING_PREPARE/COMMIT`（security/kind+`actorId,worldId,bodyId,sessionId,sessionEpoch,serverBootId,gameConnectionId`）仅经已认证 hub→Server 控制面发出。
Server 收 PREPARE 先撤旧 permit、封住普通包，验证 monotonic epoch、活体和两 role 仍在线；独立证明 quiescence 后返回同字段 `BINDING_READY`。
Registry 必须先收到匹配 BINDING_READY，再原子持久提交候选 counter，成功后才发 COMMIT；Server 再核对全部字段/旧 fence/双方在线，激活并回 `BINDING_ACTIVE`。仅同一存活 pending 事务允许同值幂等；崩溃或 COMMIT/ACTIVE 失证按第 2 节更高 epoch 恢复，不以幂等重传复活旧代。
Client 只经该实际游戏连接收到同字段 `BODY_ACTIVE` 后记录 session/epoch；PREPARE/READY/COMMIT/ACTIVE 总握手限本机单调5000ms，任何一环超时或断线保持禁用并撤销，不用登录 UUID 代替 proof。
该协议不抵抗拥有 K_body 的恶意 controller 故意委托自己的游戏连接；其委托仍只有同 body 的身份，绝无越区/高风险权限。

## 6. Permit/revoke：服务器表是 grant，客户端无法自签授权

permit 仅在 server ACCEPTED 的 LOOK/BOUNDED_WALK 获单执行槽、复核新鲜依据/区域/截止后创建；SAY 由服务器按白名单广播，不让客户端发任意聊天/命令。
permit/revoke 经已绑定的真实 Forge game connection 的 SimpleChannel 下发；不绕回 hub 把 client 变成 authority。
无需给 permit 增加 client 可验证的共享“授权签名”：拥有 verification PSK 的恶意 client 也能伪造；真正安全性来自 Server 内存表与普通包独立检查。
SimpleChannel 数据是受信 Server→Client 控制通道（恶意服务端/网络主动代理超 M1）；伪造本地 permit 只能自欺，不在 Server 表中即拒绝普通包效果。
`PERMIT` 精确字段（还含 security/kind）：`permitId,actorId,worldId,bodyId,sessionId,sessionEpoch,serverBootId,gameConnectionId,requestId,issuedAt,expiresAt,leaseMs,intent,bounds`。
permitId=CSPRNG32 的 B32，不是客户端 token；全绑定必须匹配 live session/connection，requestId 为 canonical request.messageId。
intent 只为冻结 LOOK/BOUNDED_WALK union；bounds 严格 `{dimensionId,min:Position,max:Position,maximumDistance:F64}`，min≤max，距离≤4，均为服务器批准值且不得超静态 grant。
LOOK 使用 maximumDistance=0，只有方向授权；WALK 必须 >0，累计距离按服务端实际位移记录，不能由 client maximumDistance 自报。
issuedAt/expiresAt 为 Utc，0<leaseMs≤750；服务端 own monotonic expiry=min(request剩余、grant剩余、750ms)，UTC 只供客户端保守上界换算。
Client expiry=min(接收monotonic+leaseMs、按expiresAt换算的剩余期限)；时钟偏差异常立即释放，重放 PERMIT 永不重新开始或延长输入租期。
同一 request 续短 lease 时只允许 server 串行新 permitId、先使旧 permit 失效、累计距离/截止不重置；不允许两个有效 permit 并存。
每 tick/普通包检查绑定、当前 epoch/boot/gameConnection、permit 有效、当前路径/速度/区域和禁止交互；所有未知普通包/高风险动作 deny。
`REVOKE` 精确字段（还含 security/kind）：`actorId,worldId,bodyId,sessionId,sessionEpoch,serverBootId,gameConnectionId,reason`；reason 仅 `CANCEL|DEADLINE|CONTROL_LOST|QUARANTINE|REBIND|ROTATE|EMERGENCY`。
REVOKE 是该 binding 全撤销并锁住 effectFence，未知/已过期 permit 也不影响其执行；Client 无条件释放其控制输入，Server 先清表封普通包再尝试发通知。
迟到 permit、旧 epoch/boot、不同 connection 全拒绝；同绑定 REVOKE 后不得以迟到 PERMIT 复活，恢复需重新绑定/换 epoch 与 quiescence。
取消/过期/断线收尾不替换 canonical receipt；无法证明结果即 UNKNOWN，ACK/清键通知不证明世界成功或身体静止。

### 6.1 本地紧急停：先封输入，通知不构成停稳证据

本地 client emergency 立即置独立原子 latch，锁定被撤销 tuple=(bodyId,sessionId,sessionEpoch,serverBootId,gameConnectionId)，禁止再接纳/提交该 tuple 的任何排队或迟到 PERMIT，不等待 LLM、WS、ACK 或普通动作队列。
同时请求释放全部 controller-owned keys/buttons；物理键状态必须在合法 client thread 安全调度执行，不能宣称任意线程修改 Forge 输入合法。原子 latch 立即阻止新 actuation，实际释放最大延迟须由 G3 线程/卡顿测试建立，未验证前不得启控制。
latch 不能通过清 bool、重连同 epoch、重复 BODY_ACTIVE 或新 permitId 清除；只能服务端证明 quiescence、registry 持久分配严格更高 epoch 并完成新绑定后，为新 tuple 创建可执行状态。旧 tuple 撤销 tombstone 保留到连接废弃/旧 permit 不可能有效。
Client 可 best-effort 经当前已认证 K_client WS 发 LOCAL_EMERGENCY_STOP，精确字段为 security/kind+`bodyId,sessionId,sessionEpoch,serverBootId,gameConnectionId`，身份仍取 origin，不含 server 成功断言。
Hub 仅路由原 CLIENT_CONTROLLER notice；Server 校验 origin/allowlist/当前绑定后先置独立 revoke latch、清 permit/封普通包再异步通知。通知丢失不阻塞本地 latch/释放；通知发出不代表 server 已停、quiescent 或目标 CANCELLED。
服务端 permit 最迟在自身≤750ms monotonic expiry 失效；该授权失效不是实际物理 quiescence 或保存完成的时限保证，仍须权威对账证明。

## 7. 心跳、队列、重启与密钥轮换

两 role WS 与活体绑定都必须正常才可 actuation；任一确认失效撤 permit、quarantine 并停止/保存/正常登出，记录保存失败不伪称成功。
WS `HEARTBEAT` 精确含 security/kind+`sequence:I63`；双方每500ms发送，按连接严格递增，重复/倒退不刷新本地有效接收时间，2000ms失效。
游戏侧 SERVER→Client 心跳同结构并绑定实际连接；client 若收不到2s也置撤销 latch/请求安全释放。permit 到期必须立即禁止新 actuation并调度释放，实际输入释放延迟按第6.1节验证；≤750ms 是服务端授权上限，不是物理停稳承诺。
Server 独立 IO watchdog 可原子置 deny/revoke 标志，不等待模型、发送或普通 tick 队列；游戏状态修改仍须合法 server thread，guard 处理包/tick时检查标志。tick卡死不得宣称已保存/quiescent；client物理输入按第6.1节线程限制。
模型慢只使 proposal/deadline 过期，heartbeat 不从模型任务派生；正常网络延迟按 lease 保守降级，不能靠延迟证明世界成功。
未认证连接总数≤4、每连接 pending challenge 1、TTL5s；每安装认证尝试1/s burst2；全局2/s burst4，超限关连接，无无限失败缓存。
已认证 role 每 body Server/Client 各1；替换连接先 fence/revoke 旧绑定再认证续绑，禁止 last-connection-wins 偷换。
安全控制 pending 每 body 8、game challenge 最多2、active binding1、active permit1；revoke 使用独立原子 latch1，可合并，先置 deny 后异步发送。
沿用世界契约 effect FIFO8、terminal槽32、控制容量8、去重64/300s，不把握手/安全通知挤进普通动作队列；满时拒绝新操作，撤销 latch 不受限流阻塞。
敏感日志禁止 secret/MAC/nonce/credential URL/代理端点；只记 role/installId/keyId、correlation IDs、失败码、队列计数和 fence 状态，数据最小化。
轮换先禁新准入→撤所有 permit→确认 quiescence→断旧 role/游戏绑定→原子替换受限 credential文件和配置hash→新认证/新epoch；无双 key grace 自动续动作。
旧 keyId 立即停用，pending challenges 清空；配置不完整/ACL失败则保持 disabled。secret 不作为世界文本、fact 或 memory 持久化。

## 8. 候选比较、验证与执行交接

| 决策 | 采用及原因 | 拒绝/延后及原因 |
| --- | --- | --- |
| epoch 持久化 | 独立 stdlib SQLite、rollback DELETE、synchronous=EXTRA 与生命周期排他锁；事务 CAS/恢复不接业务数据库 | JSON replace：Windows 目录持久屏障证据不足；内存 epoch：不满足重启单调性；平台证据不足时保持 disabled |
| 身份认证 | role分离PSK+方向域分离HMAC，JDK/Python标准原语可互测 | universal key：client可冒server；mTLS：远程后续，不冒充现已配置 |
| 身体归属 | live game challenge+独立K_body+已认证WS notice交叉绑定 | 离线UUID/name：可伪造；只信hub client自报绑定：不能绑定实际连接 |
| permit真实性 | 受信游戏通道+server-owned grant表+普通包强制 | client共享PSK签permit：client能伪造；Ed25519/PKI：当前边界不需额外生命周期复杂度 |
| 防重放 | 单次challenge、连接上下文、epoch+boot+connection fence和不可延长lease | 单纯wall-clock签名包：跨连接/时钟风险；通用durable消息层：超M1 |

1. 安全owner按已审查规格实现标准库 transcript、独立 epoch store、状态机及 dummy-key向量；验证 Java/Python LP bytes、MAC一致与常量时间API使用，不启动端点。
2. Java owner接 server policy/epoch/ordinary-packet guard，Python owner接 registry/role routing，均由父会话串行安排独占文件；先 disabled transport/auth，再 server绑定/permit，最后 client input。
3. 离线验证：反射/换role/换install/改hash/过期/重复challenge、跨连接proof、错误K_body/UUID冲突、counter写失败/损坏/并发锁/重启不复活。
4. 故障验证：READY/COMMIT丢失、双role失联、重排permit/revoke、续lease重复包、queue满与紧急latch、模型卡住但心跳正常、permit先过期、旧UNKNOWN不改写。
5. 证据owner核对counter实际平台持久化/ACL与进程锁能力；父会话提供hash/命令/退出码和未覆盖项给G2 oracle，批准前不启live transport/actuation。
6. G3/G4另验证固定Forge handler覆盖、实际侧线程/input释放、保存/登出；离线密码向量不证明游戏/网络/许可证/账户/供应商/人类验收。

### 8.1 四项阻塞整改的不变量与定向用例（待实现证据）

| finding | 修正前歧义 → 修正后不变量 | 必须定向验证 |
| --- | --- | --- |
| F1 角色/方向 | 泛称认证 → 全 kind DEFAULT_DENY、不可变实际 origin | CLIENT伪造BODY_BOUND/READY/ACTIVE及五类server世界消息、SERVER伪造COMMIT、反向SimpleChannel、未认证heartbeat全部拒绝；authority/binding违规关闭+fence |
| F2 活体归属 | 有某个认证controller即可 → proof.installId=notice实际origin=allowlist=当前唯一连接 | controller A proof + B notice、payload伪造origin、同nonce错game tuple、notice后替换连接均失效；tuple单次消费 |
| F3 epoch顺序 | persist与READY前后不清 → candidate→PREPARE静止→READY→persist→COMMIT→ACTIVE | READY前crash不消费、persist后crash消费不复用、COMMIT/ACTIVE丢失不假定未激活；PREPARE无permit，更高epoch恢复 |
| F4 本地急停 | 普通清键/等通知 → 原子撤销tuple且不可同代复活 | 阻塞LLM/WS/ACK/动作队列后本地latch仍生效、排队/迟到permit拒绝、同代BODY_ACTIVE不清latch；实测合法client线程释放最大延迟 |

HMAC transcript 与既有 dummy向量未改变；以上用例是角色/关联/崩溃/输入状态证据，不以向量成功冒充安全实现通过。
待验证的精确缺口：计数文件在目标Windows文件系统的持久屏障/ACL/锁；实际SimpleChannel包注册与拒绝插点；完整跨语言三label/活体证明向量；TLS远程部署；合法client线程输入释放最大延迟与卡顿行为；真实控制延迟。
现有主源依据为研究中已核对的 RFC2104、Java Mac/WebSocket、Python hmac 和 Forge sides/SimpleImpl；本轮没有新增库/API实现或外部事实断言。
G2未通过或持久/身份/普通包保护任一证明不足：保持disabled、撤绑定/permit，不降低epoch、不换身体路线、不扩大runtime承诺；世界恢复使用备份而非Git。
