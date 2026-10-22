# Minecraft Contract v1：规范语义与 G1 审查输入

- 状态：SPEC_FROZEN／按 Oracle review3 条件性批准完成三项最小文本修复；父会话最终机械验证待完成。仅冻结语义依据，不代表完整 G1、实现或 runtime 验收通过。
- 冻结记录日期：2026-10-23；依据 Oracle review3 的条件性批准，最终验证与提交由父会话负责。
- 本文中的“必须”是已冻结的 v1 语义规则；完整 G1 与实现证据仍待完成，未完成项不得因本文存在而视为通过。
- 依据：[集成方向](minecraft-integration.md)、[模式研究](../research/integration-patterns.md)、[交付计划](../autonomous-delivery-plan.md)。

## 1. 范围、当前基线与权威

M1 仅覆盖固定隔离世界/区域、Forge 1.20.1-47.4.28、已记录模组/配置 hash、非 OP 身体。
防御 buggy/stale controller 与恶意客户端直接发送普通游戏包；不承诺防御恶意服务端模组、OS/密钥失陷或未知模组。
公开聊天、朝向、平面有界普通行走、查询和停止之外的能力一律关闭；正常饥饿/营养不被创造/回血替代。
Sandbox `276a141` 是 Java transport-neutral baseline；Map codec 不是 JSON wire，现有字段不足以满足本文。
Java 需扩展 body、独立 admission/cancel/reconcile、服务端证据、强制非成功原因和有类型结果；不得静默宣称旧 Map 兼容。
现读上游 ingress 已有 `MinecraftAdmission`、shielded submission 和 bounded in-flight；这是观察的 Stage 接纳，不是动作 admission。
fix9 路径现可见改动不等于本轮已验证：提交归属和取消/结算所有权回归证据由父会话核实，不复用旧 31+1 为修复证明。
历史七个 Java 测试只证明旧基线相应范围；本文没有执行 Java/Python 实现测试。

物理拓扑：Forge client/server 各自出站 WS 到现有 Agent hub；仅单 Agent worker，hub 路由而不判断世界成功。
逻辑拓扑：Agent typed request → server sole admission/outcome → real-client short permit/revoke；server facts/receipts 直达 Agent。
服务端到客户端的 permit/revoke 是合法控制链；客户端绝不转发或创造权威观察/回执。
当前消息的 actor/world/body/session/epoch 必须与当前认证连接受信绑定逐项一致；历史响应仅按第 4 节例外验证，离线昵称/UUID 不授权。
`characterId` 仅由内部绑定解析到实际 WorldStage/角色，不是外部消息字段或可选目标。
`source` 等内容标签不证明身份：只有已验证 SERVER_AUTHORITY 连接及受信路由上下文能产生权威数据。

## 2. JSON、公用 envelope 与基础类型

每条消息严格只有 `{protocol,type,actorId,worldId,bodyId,sessionId,sessionEpoch,messageId,timestamp,payload}` 十个键。
`protocol` 固定字符串 `luosandbox.minecraft.v1`；`type` 为第 3 节八个小写判别值之一；`payload` 按判别值严格校验。
所有键必须存在；无声明的键、重复键、注释、尾随逗号、BOM、非法 UTF-8/孤立 surrogate 均拒绝；禁止任意事实 map。
`Id`：1–128 个 ASCII 字符，正则 `[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}`，不 trim/大小写归一化；不含凭据。
`Uuid`：小写规范 UUID 文本 `8-4-4-4-12` 十六进制，非全零；用于 message/event/request/body/session/boot/evidence 标识。
`actorId/worldId` 用 Id；`bodyId/sessionId/messageId` 用 Uuid；bodyId 为 provisioned 身体键，不从登录昵称自行推导。
`I63`：JSON 整数词法、范围 `0..9223372036854775807`；Python 必须 `type(v) is int`，拒绝 bool、1.0、指数整数和字符串数字。
`sessionEpoch` 为 I63；Agent ControlSessionRegistry 在 M1 单 worker 内拥有 sessionId/epoch 分配与绑定；epoch 单调、耗尽拒绝续期、禁止 wrap，重启无可靠 fence 则禁用控制。serverBootId 由服务端拥有；hub 仅路由，不拥有世界权威。
`Utc`：严格 `YYYY-MM-DDTHH:mm:ss.SSSZ` UTC、恰好三位小数、proleptic Gregorian 日历且年份仅 0001..9999（拒绝 0000）、无闰秒；拒绝 offset/local/缺少或额外精度，比较统一用 epoch milliseconds。
Java encoder 必须拒绝 Instant nanos%1000000≠0；Python encoder 必须拒绝 datetime.microseconds%1000≠0；不得在 encoder 截断，必须显式输出三位（包括 .000Z）。
创建者可在首次分配 ID/存记录之前截断到毫秒一次；随后重传复用记录时刻，不重新取时/舍入。
`timestamp:Utc` 是发送时刻，不是成功证据；同一 messageId 在保留期内重投须语义完全相同（含 timestamp），否则冲突。
`F64`：JSON number 转 IEEE754 double 后必须有限；拒绝 NaN/Infinity/溢出，解码后所有零值（含 -0/-0.0）统一为正零语义，encoder 一律输出 0，不以数字表示权限。
`Position` 严格 `{x:F64,y:F64,z:F64}`；每轴绝对值 ≤30000000；具体维度高度/安全边界仍由服务端另检。
`dimensionId:Id` 必须为合法资源位置 `namespace:path`；绝不据请求中的维度或坐标授予权限。
`Reason` 为第 6 节枚举或 null；`Evidence` 为 ≤8 个不重复 Uuid 引用，引用服务器有界诊断记录而不是外部 URL。
`Stamp` 严格 `{serverBootId:Uuid,serverRevision:I63,serverTick:I63,eventId:Uuid,evidence:Evidence}`。
revision 是该 server boot 内权威快照/结果事件序号，严格递增；tick 可重复但不倒退，不能由 wall-clock 合成。
重传同一权威消息保留 envelope.messageId、stamp.eventId 和完整原 stamp；eventId 是权威事件身份，不是消息关联 ID。不同 boot 的 revision/tick 不可直接比较，必须重新同步/fence。
本规范不规定 JSON 对象键顺序；golden 比较解析后值，不签名 Map 遍历结果，也不把普通 JSON 拼接充当认证 transcript。

## 3. 八类消息：字段全集与生产者

下列各 payload 的键全集均为“必须且仅有”；`T|null` 允许显式 null，绝不允许省略。响应 messageId 不得等于其关联请求 ID。
| type | 生产者 → 消费者 | payload 精确字段 |
| --- | --- | --- |
| `observation` | Server → Agent | `stamp:Stamp,audience:"PUBLIC",source:"SERVER_AUTHORITATIVE",freshness:Freshness,state:BodyState,capabilities:Capabilities,untrustedText:TextItem[]` |
| `request` | Agent → Server | `deadline:Utc,basis:Basis|null,intent:Intent` |
| `admission` | Server → Agent | `requestId:Uuid,decision:AdmissionDecision,reason:Reason,stamp:Stamp` |
| `cancel` | Agent → Server | `mode:CancelMode,targetRequestId:Uuid|null,targetEpoch:I63,deadline:Utc` |
| `cancel_result` | Server → Agent | `cancelId:Uuid,mode:CancelMode,targetRequestId:Uuid|null,targetEpoch:I63,result:CancelResult,reason:Reason,stamp:Stamp` |
| `receipt` | Server → Agent | `requestId:Uuid,status:TerminalStatus,reason:Reason,stamp:Stamp,outcome:Outcome` |
| `reconcile` | Agent → Server | `targetRequestId:Uuid|null,targetEpoch:I63,deadline:Utc` |
| `reconcile_result` | Server → Agent | `reconcileId:Uuid,targetRequestId:Uuid|null,targetEpoch:I63,result:ReconcileResult,reason:Reason,quiescent:bool,observationId:Uuid|null,admissionId:Uuid|null,receiptId:Uuid|null,stamp:Stamp` |

`Freshness` = `CURRENT|STALE|UNKNOWN`；只有 CURRENT 可进入当前事实认知，其他仅作诊断/失源通知。
M1 强制 PUBLIC + SERVER_AUTHORITATIVE；拒绝 CLIENT_LOCAL、私密/控制会话受众，包括“已认证 client 自称 server”情形。
`BodyState` = `{dimensionId:Id,position:Position,yaw:F64,pitch:F64}`；yaw ∈[-180,180)，pitch ∈[-90,90]。
`Capabilities` 严格含 `QUERY,PUBLIC_SAY,LOOK,BOUNDED_WALK,STOP` 五键，每值 `AVAILABLE|UNAVAILABLE|UNKNOWN`，缺一不可。
能力报告是可用性提示，不是 grant；每次 admission、普通包和执行 tick 仍检查真实权限。
`TextItem` = `{eventId:Uuid,text:string}`，数组 ≤8；text 非空，≤1024 Unicode scalar 且 UTF-8 ≤4096 bytes，禁止 NUL。
世界聊天/告示等仅放 untrustedText；其 eventId 仅标识文本来源事件，不使文本成为规则/动作/权限。
`Basis` = `{observationId:Uuid,serverBootId:Uuid,serverRevision:I63}`；非 null 时必须指向受信服务端 observation。SAY/LOOK/WALK 必须非 null、当前 boot/绑定且新鲜；QUERY 允许 null bootstrap/resync 或可信陈旧依据，不豁免认证/绑定/容量。
`Intent` 是以下四个封闭 union；STOP 单独映射 cancel，禁止 `request.intent.kind="STOP"` 双重语义。
| kind | 其余精确字段 | 含义 |
| --- | --- | --- |
| `QUERY` | `queryKind:"READ_ONLY_SUMMARY"` | 允许 basis=null bootstrap/resync，独立有界控制容量，不受 effect busy/quarantine 阻塞 |
| `PUBLIC_SAY` | `message:string` | 非空，≤256 Unicode scalar 且 UTF-8 ≤1024 bytes，无 NUL/CR/LF，首个非空白字符不可为 `/` |
| `LOOK` | `dimensionId:Id,target:Position` | 提议目标，服务器重新计算有限朝向并检查当前维度 |
| `BOUNDED_WALK` | `dimensionId:Id,target:Position,maximumDistance:F64` | 0<maximumDistance≤4 blocks，限平面安全普通移动 |

target/maximumDistance/deadline 都是提议约束：不得扩大受信区域、grant、实际距离/路径限制或服务端期限。
`AdmissionDecision` = `ACCEPTED|REJECTED`；ACCEPTED 仅表示服务器完成验证并预留终态槽，不表示执行、Agent 接受或成功。
未知 admission 是调用方本地不确定状态，不由未确认的 hub 伪造 server ACCEPTED/REJECTED；不得自动重发执行。
`CancelMode` = `TARGET|EMERGENCY_REVOKE`；TARGET 的 targetRequestId 必须非 null 且不同于 cancel messageId；EMERGENCY_REVOKE 必须 null。
两种模式都不要求目标当前存在：TARGET 可得到 NOT_FOUND；EMERGENCY_REVOKE 撤销绑定身体全部 permit，不依赖目标 ID。
`CancelResult` = `REQUESTED|ALREADY_TERMINAL|NOT_FOUND|REVOKED|REJECTED|UNKNOWN`；它是取消操作结果，不是目标动作终态。
TARGET 只能产生 REQUESTED/ALREADY_TERMINAL/NOT_FOUND/REJECTED/UNKNOWN；EMERGENCY 只能 REVOKED/REJECTED/UNKNOWN。
`TerminalStatus` = `SUCCEEDED|FAILED|TIMED_OUT|CANCELLED|DISCONNECTED|UNKNOWN`；receipt 只关联曾 ACCEPTED 的 request。
`Outcome` 严格 `{kind:OutcomeKind,effect:Effect,state:BodyState|null,observationId:Uuid|null}`。
`OutcomeKind` = `SUMMARY|PUBLIC_BROADCAST|ORIENTATION|POSITION|STOPPED|UNDETERMINED`；`Effect` = `NONE|PARTIAL|COMPLETE|UNKNOWN`。
SUCCEEDED 分别要求 QUERY/SAY/LOOK/WALK 对应 SUMMARY/PUBLIC_BROADCAST/ORIENTATION/POSITION，effect=COMPLETE，reason=null。
除 PUBLIC_BROADCAST 外成功必须带非 null state/observationId；QUERY 成功必须创建新 CURRENT observation，receipt 引用其 envelope.messageId；广播 state/observationId 可为 null，但 stamp.evidence 必须非空。
成功 LOOK 偏差≤2°；成功 WALK 距目标≤0.25 block、实际累计位移≤批准上限；这是保守验收阈值，不是游戏性能实测。
取消停止可产生 STOPPED，effect=NONE/PARTIAL，state/observationId 非 null；UNKNOWN 必须 UNDETERMINED、effect=UNKNOWN。
其余非成功允许 STOPPED/UNDETERMINED 或原 intent 对应 kind，effect=NONE/PARTIAL；除广播外 kind 非 UNDETERMINED 必带状态/观察引用。
`ReconcileResult` = `ADMISSION_REJECTED|IN_FLIGHT|KNOWN|QUIESCENT|BLOCKED|UNKNOWN|REJECTED`；以下是完整目标/null 矩阵，未列组合非法。
| result | targetRequestId | admissionId | receiptId | observationId | quiescent / reason |
| --- | --- | --- | --- | --- | --- |
| ADMISSION_REJECTED | 非 null | 非 null，REJECTED admission | null | null | false / null |
| IN_FLIGHT | 非 null | 非 null，ACCEPTED admission | null | null | false / null |
| KNOWN | 非 null | 非 null，ACCEPTED admission | 非 null，含 UNKNOWN receipt | null | false / null |
| QUIESCENT | 必须 null，身体围栏检查 | null | null | 非 null，当前 CURRENT observation | true / null |
| BLOCKED | 必须 null，身体围栏检查 | null | null | null | false / QUIESCENCE_UNPROVEN |
| UNKNOWN | 非 null，历史记录无法证明 | null | null | null | false / OUTCOME_UNPROVEN 或 HISTORY_UNAVAILABLE |
| REJECTED | 原 reconcile 的值 | null | null | null | false / 第 6 节拒绝码 |
所有结果的 targetEpoch 与原 reconcile 相同；非 null targetRequestId 必须匹配该已有请求记录的 epoch；null 仅查指定 body epoch 的围栏。
KNOWN 只证明找到唯一 receipt（包括 UNKNOWN），不证明 quiescence；请求结果查询与身体围栏检查必须分开 reconcile，不能相互推断。
定向对账必须同时重放原 canonical 消息：ADMISSION_REJECTED 重放原 REJECTED admission；IN_FLIGHT 重放原 ACCEPTED admission；KNOWN 重放原 ACCEPTED admission 和唯一不可变 receipt。
重放必须原样保留原 envelope.messageId/timestamp/sessionId/sessionEpoch 及完整 stamp（含 eventId）；result 的 admissionId/receiptId 必须引用实际重放消息的 envelope.messageId，不能只返回不可解析的 ID。
不得假设 result/admission/receipt 到达顺序；接收方按既有请求与当前未完成 reconcile 关联，在第 7 节既定记录/控制容量内有界缓存、幂等合并；缺消息/容量不足则保留不确定并报告背压，不补造记录、不重新 admission/执行。
reconcile 的 targetRequestId=null 表示身体围栏检查；非 null 查询历史请求；响应 envelope 用当前绑定，targetEpoch 显式指历史 epoch。
历史 admission/receipt 保留原 request 的 sessionId/epoch；仅按第 4 节匹配已有精确记录，不进入当前执行或授予新 permit。

## 4. 关联、去重、认证接口

requestId = 原 request.messageId；cancelId/reconcileId 同理。admissionId/receiptId/observationId 均引用对应 envelope.messageId，不能以 stamp.eventId 替代。
当前八类消息必须精确匹配当前 sessionId/epoch；reconcile_result envelope 用当前绑定，targetEpoch 指被查历史代。
历史 admission/receipt 是唯一例外：必须来自当前已认证 SERVER_AUTHORITY role，actor/world/body 匹配当前绑定，且原 sessionId/epoch/requestId 精确匹配本地已有请求记录；只接受该记录显式恢复/对账所请求的历史响应，拒绝 unsolicited history。
targetRequestId/targetEpoch 声明不能创建新历史记录或选择角色；跨 session 历史由已有记录解析，旧记录不能产生新 permit。
相同 ID+相同值在进程内保留期返回原 decision/result/receipt，不重新执行；相同 ID 不同值拒绝 ID_CONFLICT 并停止该连接准入。
不同 cancelId 对同一目标也幂等；目标 requestRecord 已有 REJECTED admission 或任何 receipt（含 UNKNOWN）时，TARGET 返回 ALREADY_TERMINAL/TARGET_TERMINAL，完整保留原记录，不为 REJECTED admission 伪造 receipt；身体仍需撤销/恢复时使用独立 effectFence 的 emergency revoke/reconcile。
无认证或 envelope 无法安全解析时只关闭/记录脱敏诊断，不编造 canonical response；AUTH_REQUIRED 等是诊断码而非世界 receipt。
握手是独立接口：角色分离 PSK、双向 HMAC-SHA-256 challenge-response；仅 loopback 可明文 WS，非回环必须验证 TLS。
必须校验 roles/install IDs、双方随机 challenge、单次性、expiry、协议/模组/配置 hash，并绑定不可变认证上下文。
transcript 必须独立定义长度前缀、字段顺序、编码和域分离；禁止签名 Map 顺序/含糊串联；密钥/MAC/nonce 不入 URL/日志。
PSK provision/rotation、精确 transcript 字节向量、hub 路由身份保持/防跨角色伪造属于安全绑定 owner，G2 前冻结；本语义规格不虚构其实现。
hub 必须把受信 SERVER_AUTHORITY origin 关联到路由，不能让 CLIENT role 发布 server 类型；失去 origin 校验即拒绝消息。
permit/revoke/heartbeat 是单独服务端—客户端控制接口，不属于八类 Agent 世界消息；G2 冻结其 lease/fence/认证向量，未冻结不得 actuation。

控制消息 envelope 必须属于当前受信绑定；targetEpoch 可指同 body 历史 epoch，不可跨身体查询/撤销。
request/cancel/reconcile 的 deadline 必须晚于 timestamp 且至多晚 5000 ms；本地紧急撤销不依赖过期网络命令。
取消先于 admission 时，服务端按目标 ID 串行处理：已接纳则撤销，无记录则 NOT_FOUND，不建立无界 tombstone。
NOT_FOUND 不证明在途 request 不会稍后接纳；调用方须保持不确定并可 emergency revoke/quarantine，绝不自动当作已取消。

## 5. 有限状态、取消与 UNKNOWN

将两个对象分开：`requestRecord` 保存 admission、执行阶段和最多一份不可变 canonical receipt；`effectFence` 保存 body/epoch 的执行互斥与 quarantine。
requestRecord 阶段为 `AWAITING_ADMISSION|ADMITTED|EXECUTING|CANCEL_REQUESTED|TERMINAL|UNCERTAIN`；UNCERTAIN 仅表示本地缺少权威记录，不是可覆写 UNKNOWN receipt 的状态。
每个 ACCEPTED request 最多产生一份 canonical receipt，连 UNKNOWN 也不可替换成 SUCCEEDED 或其他 status；本地缺 receipt 的 UNCERTAIN 可恢复服务器原有唯一 receipt。
| 原状态 / 事件 | requestRecord 下一状态 / effectFence 动作 |
| --- | --- |
| 新 request 已发 | AWAITING_ADMISSION；禁止自己发 permit |
| AWAITING_ADMISSION / REJECTED | TERMINAL，保存拒绝 admission，无 receipt |
| AWAITING_ADMISSION / ACCEPTED | ADMITTED，预留终态槽；effect 请求加入 FIFO，不代表执行 |
| ADMITTED / 获 effect 执行槽且通过复核 | EXECUTING；发有效 permit/广播，跨 epoch 最多一个 effect |
| ADMITTED/EXECUTING / TARGET cancel REQUESTED | CANCEL_REQUESTED；移出等待队列或撤 permit/释放输入 |
| 任意未持有 receipt 记录 / 获唯一 receipt | TERMINAL；UNKNOWN 同样终结 requestRecord，但保持 effectFence quarantine |
| 无 receipt / admission 丢失、断线、结果未确认 | 本地 UNCERTAIN；effectFence quarantine、撤 permit、释放输入 |
| 本地 UNCERTAIN / 恢复原 admission 或原 receipt | 按原 admission 恢复 ADMITTED/执行记录或拒绝终态；原 receipt 恢复 TERMINAL，不重新执行 |
| TERMINAL / 迟到取消或重复结果 | 不变；冲突 receipt 拒绝并报警/quarantine，绝不覆盖 |

PUBLIC_SAY/LOOK/BOUNDED_WALK 使用每 body 最多 8 个等待项的 FIFO，活动项不计入等待项；跨 epoch/runtime 至多一个活动 effect。
ADMITTED 排队期间 deadline 继续计时；开始前重检依据/权限，绝不暂停/续长期限；队满 BACKPRESSURE，不因已有活动项简单 BODY_BUSY 拒绝所有排队。
未发 permit/未广播的队列项到期：TIMED_OUT、effect=NONE、kind=UNDETERMINED，附 admission/deadline/未 dispatch 证据，outcome.state/observationId 均 null，不需伪造位移观察。
排队项被取消并证明未 dispatch：CANCELLED、effect=NONE、kind=UNDETERMINED，附 admission/cancel/无 dispatch 证据，outcome.state/observationId 均 null；无需等待不存在的输入停止。
QUERY 走每 body 8 项独立有界控制容量（与 cancel/reconcile 共享），不被 FIFO effect 锁、BODY_BUSY/quarantine 阻塞，但仍须先预留 terminal slot；满则 BACKPRESSURE，紧急 revoke latch 仍独立。
已 dispatch 的 CANCELLED 仅在取消先于已知完成且服务器证明 revoke + 身体停止/输入授权耗尽后；有部分位移不回滚，effect=PARTIAL；未 dispatch 队列项按上表 NONE 规则。
已 dispatch 的 TIMED_OUT/DISCONNECTED 仅在确定截止/控制丢失且证明已停止、可界定部分结果时；不能证明则 UNKNOWN；未 dispatch 到期项按上表 NONE 规则。
客户端 cleanup ACK 不能证明恶意客户端静止；服务端普通包拒绝/permit 耗尽和新鲜权威状态才建立 quiescence。
UNKNOWN receipt 使 requestRecord TERMINAL 且不可变，effectFence 仍 quarantine；后来的静止/观察只更新围栏证据，不替换 receipt。reconcile KNOWN 指向 UNKNOWN 只证明记录存在，不解除围栏。
quarantine 允许 QUERY、TARGET cancel、EMERGENCY_REVOKE、reconcile；用独立控制容量，不等待被 quarantine 的动作锁。
EMERGENCY_REVOKE 及本地紧急停绕过普通队列/模型；REVOKED 只证明授权撤销，不自动使所有目标 CANCELLED。
QUIESCENT 只证明身体围栏可安全换 epoch，不证明旧请求成功/失败；丢失 journal 的旧请求保留本地不确定/既有 UNKNOWN，不伪造第二份 canonical receipt，禁止盲重试。
新 epoch 必须：旧 permit 全撤/已过期 + 服务端确认身体 quiescent + registry 受信绑定续期 + 重新认证/观察/对账。
重启 bootId 改变，旧 fence 未获证即拒绝控制；不得通过“新进程=无动作”跳过普通包保护或单身体互斥。
普通网络/客户端延迟不等于模型慢；仅独立 heartbeat/lease 丢失触发控制失效，模型慢仅使未执行提议过期。

## 6. 冻结的原因矩阵（父会话最终验证待完成）

除这里列出的正常查询/接受结果外，其他响应及非成功 receipt 必须有矩阵允许 reason：admission ACCEPTED、cancel REQUESTED/REVOKED、reconcile ADMISSION_REJECTED/IN_FLIGHT/KNOWN/QUIESCENT 的 reason=null；ADMISSION_REJECTED 的拒绝原因在原 admission 中。
| 消息结果 | 允许 reason（精确大小写） | 含义/后续 |
| --- | --- | --- |
| 诊断关闭，不产生 receipt | `MALFORMED,UNSUPPORTED_PROTOCOL,AUTH_REQUIRED,ROLE_MISMATCH,FRAME_LIMIT` | 无可信请求上下文，不据此推断未执行 |
| admission REJECTED；cancel/reconcile REJECTED | `BINDING_MISMATCH,STALE_EPOCH,DEADLINE_EXPIRED,ID_CONFLICT,RATE_LIMIT,BACKPRESSURE` | 确认本次操作被拒绝，不推断其目标状态；改条件后新请求需重新决策 |
| admission REJECTED | `STALE_BASIS,AUDIENCE_DENIED,CAPABILITY_UNAVAILABLE,REGION_DENIED,UNSAFE_TARGET,BODY_BUSY,QUARANTINED` | 不发 permit；QUERY 允许 null basis 并豁免 basis 当前性/quarantine/busy，不豁免绑定/terminal-slot 容量 |
| cancel ALREADY_TERMINAL / NOT_FOUND | `TARGET_TERMINAL` / `TARGET_NOT_FOUND` | 原拒绝 admission/receipt 不变、不伪造 receipt / 不证明从未发生 |
| cancel UNKNOWN；reconcile UNKNOWN | `OUTCOME_UNPROVEN,HISTORY_UNAVAILABLE` | 保持 quarantine，禁止自动重试 |
| reconcile BLOCKED | `QUIESCENCE_UNPROVEN` | 不换 epoch，可继续 QUERY/STOP/reconcile |
| receipt FAILED | `UNSAFE_TARGET,STUCK,EXECUTION_REJECTED` | 已停止且 effect NONE/PARTIAL；需新事实/新决策，不自动重试 |
| receipt TIMED_OUT | `DEADLINE_EXPIRED` | 确定已停止；可有部分效果，不是未执行 |
| receipt CANCELLED | `CANCEL_CONFIRMED` | 证明目标已停；取消确认不代表世界回滚 |
| receipt DISCONNECTED | `CONTROL_LOST` | 确定已停止且结果可界定；保存/登出另外报告真实证据 |
| receipt UNKNOWN | `OUTCOME_UNPROVEN,HISTORY_UNAVAILABLE` | 不能界定效果/丢失历史，effect UNKNOWN |

receipt SUCCEEDED reason=null；其他组合非法。UNKNOWN 是非成功且非“未发生”；不得映射为 COMPLETED/NOT_STARTED。
结果 evidence 可为空仅当确实无可引用历史；SUCCEEDED、CANCELLED、TIMED_OUT、DISCONNECTED 的停止/完成断言必须 evidence 非空。

## 7. 保守有界默认值（配置 hash 的一部分，不是性能承诺）

| 项目 | M1 默认上限/期限 | 理由与溢出行为 |
| --- | --- | --- |
| JSON 完整消息 / 嵌套深度 | 65536 UTF-8 bytes / 12 层 | WS 分片累计计数；超限断连接，不无限重组 |
| effect FIFO 等待项 / 活动 effect | 每 body 8 / 跨 epoch 每 runtime 1 | SAY/LOOK/WALK；活动项不占等待位，deadline 持续 |
| 终态槽 | 每 body 32，admission 前预留 | 活动槽不得驱逐；满则 BACKPRESSURE，无 silent drop |
| 终态/去重保留 | 300 s；最多 32 个动作记录 | 已结算槽满保留至到期；到期后拒绝旧 timestamp，不保证历史可查询 |
| 控制队列 / revoke latch | 每 body 8 / 1 个合并紧急撤销 latch | QUERY/cancel/reconcile 不占 effect FIFO；QUERY 仍预留 terminal slot，latch 绕过队列 |
| 控制请求去重 | 每 body 64 条 / 300 s | 满时拒绝新控制请求，但 emergency latch 仍置位且不伪称已应答 |
| 观察积压 | 每 body 1 个最新状态，最多 8 TextItem | 状态可合并；文本未进入队列则拒绝/记计数，不捏造已感知 |
| 普通请求速率 / 控制请求速率 | 每 body 2/s burst 4 / 4/s burst 8 | token bucket；紧急撤销标志不受普通限流阻断 |
| heartbeat 周期 / 控制失效 | 500 ms / 2000 ms 无有效 heartbeat | 独立 IO/单调时钟；不等待模型、tick 或动作队列 |
| client permit lease | 最长 750 ms，每 tick 复核 | 截止取 request、server grant、lease 最小值，不靠心跳自动续动作 |
| request deadline / basis TTL | 发送后最长 5000 ms / 1000 ms | LOOK/WALK/SAY 用当前依据；QUERY 允许 null bootstrap 或陈旧可信依据 |
| 新请求 timestamp 容差 | 过去≤5000 ms、未来≤1000 ms | 时间异常拒绝；已缓存相同 ID 重投先查记录，不重新执行 |

所有总量按 M1 单 worker、单 provisioned body；不宣称多副本全局 fencing。控制连接数限定 server/client 各 1，替换前先 fence。
时间期限统一从已校验 UTC 转为本机单调剩余期限，只能缩短；时钟跳变、server tick 卡顿不得延长 permit。
有限内存不是 durable messaging；崩溃/过期后 HISTORY_UNAVAILABLE，不重新准入旧 ID；新 epoch 不擦除旧 UNKNOWN receipt/本地不确定历史；effectFence 独立恢复。

## 8. 主要语义选择与被拒替代

| 决策 | 采用 | 拒绝/延后及理由 |
| --- | --- | --- |
| 三层确认 | server admission + terminal receipt + Agent settlement 独立 | 单 accepted=成功：错误；通用 exactly-once broker：超 M1 范围 |
| 认知输入 | 封闭 typed payload + PUBLIC server context | 解析 summary：丢类型/权限；client-local 合并：首期权威混淆 |
| 停止 | targeted cancel + 独立 emergency revoke | STOP 普通动作排队：死锁；cancel ACK=目标取消：证据不足 |
| UNKNOWN 恢复 | body/epoch quarantine + quiescence 换代，旧历史保留 | 自动重试：重复副作用；永久禁止 QUERY/STOP：恢复死锁 |
| 编码演进 | 新 protocol+共享 JSON fixtures+显式 Map adapter | 静默复用旧 V1 Map：字段不全；宽松未知字段：权限漂移 |
| 身体/角色绑定 | provisioned body + registry 内部角色映射 | username 授权：可伪造；外部 characterId：跨角色注入 |
| 传输/认证 | 已选出站 WS、loopback 双向角色 PSK HMAC | raw TCP/long-poll 不选首期；远程明文 HMAC 不提供保密性 |
| 记忆 | 默认不写公开长期记忆；操作 journal 仅诊断 | 假 userId 私有写入：受众错误；每帧保存：无必要且隐私风险 |

DispatchEffectRef 若实现，只表示已接受/已派发引用，不能当作世界成功；关联唯一 receipt 才构造真实 upstream ActionResult；UNKNOWN 非成功，后续 QUIESCENT 只更新身体围栏。
上游需有类型 Minecraft facts/actions；现 summary ingress 保留只读诊断，不作为 cognition payload，也不解析 Say 文本产生移动。

## 9. 拟建 golden fixtures、实施顺序与证据限制

拟在 `contracts/minecraft/v1/` 建 schema、manifest/hash、valid/invalid JSON 与 expected-result；上游镜像 `contracts/minecraft_v1/`。
每个用例固定绑定/时钟/认证上下文，不把 source 标签当认证；Java/Python 读同一文件，比较规范值/拒绝类别而非 JSON 键顺序。
有效用例：`observation_public_current`、`observation_stale_diagnostic`、`request_query`、`request_say`、`request_look`、`request_walk_4blocks`。
有效用例：`admission_accepted_no_receipt_yet`、`admission_region_rejected`、`cancel_target_requested`、`cancel_after_success_unchanged`、`emergency_no_target`。
有效用例：`receipt_success_each_intent`、`receipt_cancelled_partial_proven`、`receipt_unknown`、`reconcile_known`、`reconcile_quiescent_old_unknown`。
边界有效：I63=0/最大值、UTC 毫秒跨日、中文/emoji text、64KiB 完整消息、所有 capability 三态；epoch 耗尽禁止再分配。
无效用例：每层 missing/unknown/duplicate key、bool/float/exponent/string I63、负数/超 I63、NaN/Infinity/1e400、非法 UTF-8/surrogate/BOM。
无效用例：非法 UUID/UTC/维度、超 text/frame/depth、缺 capability、未知 enum、null 错位、PUBLIC client-origin、restricted audience、伪造 characterId。
无效用例：非成功缺 reason/错误组合、成功带 reason、成功无 evidence、receipt 错 request/body/epoch、revision 倒退/跨 boot 比较、cancel 自引用/错误模式。
时序用例：重复同 ID 不执行、冲突 ID、满槽拒绝、取消先于 admission、取消/完成竞争、断线失证→UNKNOWN、crash 无 journal、旧 epoch 迟到结果。
恢复用例：quarantine 允许 QUERY/STOP/reconcile、revoke saturation latch、模型慢但 heartbeat 正常、permit 过期、静止证明前禁止新 epoch。

B1：`local_missing_receipt_recovers_original`、`unknown_receipt_immutable_reject_success_replacement`、`known_unknown_does_not_clear_fence`。
B2：七类 reconcile_result 的 null/target/reason 矩阵正反例；`rejected_admission_no_receipt`、`accepted_in_flight_no_receipt`、`message_id_not_event_id`、重传保留两个 ID。
B3：`current_session_epoch_exact`、`authenticated_history_exact_existing_record`；拒绝错 role/actor/world/body/session/epoch、无记录、unsolicited history，历史结果不得发 permit。
B4：`query_null_basis_bootstrap`、`query_resync_current_observation`；effect null/stale basis 拒绝，QUERY 无认证/终态槽拒绝，成功引用新 CURRENT messageId。
B5：`fifo_8_waiting_plus_1_active`、`fifo_deadline_keeps_running`、`queued_expiry_none`、`queued_cancel_none`；QUERY 独立容量、terminal slot 满、跨 epoch effect 互斥。
B6：Java/Python `.000Z/.001Z/.999Z` 与 epoch-ms 相等；拒绝无/二/四位、offset、Instant 纳秒余数、datetime 微秒余数；creator 截断一次后重传不变。
review3 定向重放夹具：`reconcile_rejected_replays_original_admission`、`reconcile_inflight_replays_original_admission`、`reconcile_known_replays_admission_and_sole_receipt`；排列交付顺序、重复幂等、有界关联满容量、不重新准入/执行、保留原 messageId/timestamp/session/epoch/stamp。
review3 取消夹具：`target_cancel_rejected_admission_already_terminal_no_receipt`、`target_cancel_any_receipt_preserves_record`。
review3 边界夹具：`f64_zero_forms_0_neg0_0dot0_neg0dot0`（0、-0、0.0、-0.0 解码均为正零、编码均为 0）；`utc_year0000_rejected`、`utc_year0001_valid`、`utc_year9999_valid`。
1. 契约 owner 在父会话 G1 审查后实现 schema/fixtures 和 Java 中性类型/严格 adapter；验证每条消息/原因矩阵，旧 Map 测试不冒充 wire。
2. Python owner 实现 typed decoder/可信 binding/ActionResult 映射和 fix9 定向回归；验证真实 Stage 接纳与世界 admission 名称不混淆。
3. 安全绑定 owner 在 G2 前冻结握手 transcript/permit/revoke 向量及 fence；先 disabled-actuation WS，再 server state/permit，后 client input。
4. 父会话跑跨语言真实编解码/离线状态机/结构检查，汇总证据与 oracle 结论；运行时/普通包防绕过另在 G3/G4 验证，不能由 fixture 推断。
未实现/待验证：JSON 库及 duplicate-key 行为、跨语言 fixtures、握手字节向量、固定 Forge 普通包插点、所有游戏/供应商/人类验收。
风险关闭方式：字段/原因/权限冲突时禁用协议与 actuation；回退相依 adapter/契约提交，不回写旧字段冒充兼容；世界只能按备份恢复。
本文作为 SPEC_FROZEN 的语义实现依据；Oracle review3 条件性批准所列三项文本修复已纳入，父会话最终验证/差异审阅/提交待完成。实现证据与完整 G1 仍待完成，不宣称代码、跨语言测试或 runtime 通过；保留无三次硬上限的实质风险复审规则。
