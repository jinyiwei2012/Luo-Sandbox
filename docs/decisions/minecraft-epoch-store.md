# Minecraft epoch store：Windows 独立 SQLite 候选决策

- 状态：SPEC_APPROVED_FOR_OFFLINE_IMPLEMENTATION，2026-10-23；采用候选 B，定向独立审查指出的主规格候选表冲突已统一。不构成 G2 或平台能力通过。
- 上位：[控制安全规格](minecraft-control-security-v1.md)第 2 节的已批准修订；[世界消息语义](minecraft-contract-v1.md)不变。
- 当前进度记录：认证离线跨语言向量已核对，实际 epoch store/binding/permit 仍未交付；历史测试不证明此存储实现。

## 1. 已知边界与来源结论

本轮只读确认 Windows NT 10.0.29683.0，命令解析到 conda agent 环境 python.exe；未运行目标代码或 sqlite3。
Get-Volume 本轮未返回可用卷信息；不能据工作区在 C: 推断 NTFS、SQLite版本/VFS、ACL、flush 或断电保证。
CPython 3.10 的 Windows os.replace 使用 MoveFileExW，替换分支 flags 为 MOVEFILE_REPLACE_EXISTING，没有 WRITE_THROUGH。[S1]
os.fsync 普通文件与替换成功不共同证明父目录 rename 持久；不能把 POSIX 目录 fsync 直译到 Windows。
Microsoft 明确 REPLACEFILE_WRITE_THROUGH 不受支持；目录句柄可打开不等于 FlushFileBuffers 已提供 POSIX 等效目录持久屏障。[S2][S3]
SQLite rollback atomic commit 文档说明其依赖 OS flush、文件系统/设备行为；文档不能证明本机存储栈履约。[S4]
synchronous=EXTRA 在 DELETE rollback 模式额外要求 journal unlink 后目录同步；实际 VFS 是否/如何实现仍须核对，不能声称自动补足 Windows 目录同步。[S5]
Python sqlite3 是标准库绑定，实际 SQLite 库版本独立于 Python 版本；本轮不虚构本机版本或 SQLite 特性可用性。[S6]

## 2. 候选与自动选择

| 候选 | 选择 | 理由及证据限制 |
| --- | --- | --- |
| A JSON + replace + fsync | 不选当前 Windows 实现 | 小而直观，但缺可证明的目录替换持久屏障；不通过换成 ReplaceFileW 或“读回成功”修饰此缺口 |
| B 独立 sqlite3 rollback DELETE + EXTRA | 采用为待审设计 | 成熟事务/CAS/恢复路径，无新增数据库依赖；仍依赖实际 SQLite/VFS/OS flush，不保证硬件断电 |
| C unsupported/disabled | 必须保留 | 缺版本/配置/锁/ACL/恢复能力时拒绝激活，禁止退回 JSON、NORMAL、内存epoch 或自动清零 |

不复用 Agent 业务数据库（可能 WAL/NORMAL）、ORM/migration 或业务事务；不触碰现有数据库配置。
选择 B 不是声明 Windows 持久能力已证实；父会话须接受具体平台证据与限制，否则 C 是唯一运行状态。

## 3. 独立文件、权限与首次 provision

目录为仓库外 Agent 私有数据目录 `minecraft-control/`；只含 `epochs.sqlite3`、`epochs.lock`、`provision.json` 及 SQLite 自己管理的 rollback journal。
拒绝网络盘、同步目录、reparse/symlink 跳转与不确定卷；不全局改 ACL、不改账户、不提权或开启备份权限。
实际服务 SID 是唯一业务写主体；目录/文件/新 journal 的有效 ACL 均须检查，不能只看只读 attribute。
SYSTEM/Administrators 可能有现存权限，应明确列入本机 OS 信任报告；不得暗中把其成员之外的 Users/Everyone 写权限视为允许。
是否接受这些系统主体由既有部署信任政策确认，不新增授权、不自动放宽“仅服务账户业务写入”的规则；不符则 disabled。
运行必须先持有 epochs.lock 的非继承 Win32 CreateFileW 句柄：GENERIC_READ|GENERIC_WRITE、dwShareMode=0、OPEN_EXISTING，进程全生命周期保持。
锁文件仅首次 provision 用 CREATE_NEW 建立，运行不创建/删除/替换；第二进程共享冲突立即拒绝，OS 关闭/进程退出释放句柄；不能仅检查 PID 文件存在。[S3]
SQLite 内部事务锁仍使用其 VFS；外部进程锁防两个 registry 同时生活，不用 locking_mode=EXCLUSIVE 代替此生命周期锁。[S7]
POSIX 锁是未来独立平台适配，本决策不声称已支持。
provision.json 严格字段 `{version:1,provisionId:Uuid,bodyId:Uuid}`，≤4096 UTF-8 bytes，无secret，无未知/重复键。
首次 provision 必须显式且仅在整个安装未初始化时进行：独占创建锁/marker、初始化唯一行、验证一致性；中途失败保持 disabled，不自动修补成新安装。
marker 在首次持久化完成后不随每次 epoch 改写；其初次创建持久性/ACL同样属于平台证据，不宣称跨文件事务。
marker 有而 DB 缺失、DB 有而 marker 缺失、锁缺失、任一 provisionId/bodyId 不匹配均禁用；绝不因不存在就 CREATE DB/lastEpoch=0。
DB/marker/锁同时被删无法由应用识别为历史安装；是否首次 provision 还必须与独立部署配置中的 provisionId 对照，不能靠目录为空判定。

## 4. 单行 schema 与必要配置

数据库只允许一个 version=1 单 body 行，字段：`version,provisionId,bodyId,lastEpoch,generation,recordHash`；不需要业务会话表。
lastEpoch/generation 为 SQLite INTEGER，严格非负 I63；读取检查 typeof、行数、schema版本/字段、无多余触发器/view，不按宽松 SQL coercion 接受值。
初始 lastEpoch=0、generation=0；每次提交二者各加一，任一达到 I63 最大则禁用，禁止 wrap/重建。
recordHash 为 SHA256(LP(domain="luosandbox.epoch-row.v1"),LP(version十进制ASCII),LP(provisionId),LP(bodyId),LP(lastEpoch十进制ASCII),LP(generation十进制ASCII))，LP 为 U32BE长度前缀。
hash 检出偶然字段损坏，不是 MAC、防恶意修改或完整快照回滚证明；一致旧 DB+marker/hash 不能被它检测。
现存 DB 打开必须用 sqlite3 uri=True、受控绝对 file URI 的 mode=rw，禁止默认自动创建/immutable=1；检查路径编码，用户不能提供任意 URI 参数。[S6]
首次 provision 才允许显式受限 create；既有安装不做迁移、自修复、VACUUM/ATTACH 或替换主库。
每次新连接在事务外设置 journal_mode=DELETE、synchronous=EXTRA；读回分别必须 delete 和整数3，未知 PRAGMA 可能静默忽略，不能只看 execute 未抛错。[S5]
采用 isolation_level=None 与显式 BEGIN IMMEDIATE/COMMIT/ROLLBACK；busy_timeout=0，不等待其他 registry 或在模型/游戏线程上阻塞。
启动检查 integrity_check 返回唯一 ok、schema/单行/hash/marker一致及配置值；检查失败保持 disabled，不删除 journal“修好”数据库。
真实 hot journal 只能交由 SQLite 在锁/权限正确的受控 rw 打开中恢复；恢复失败禁用，不用只读观察伪称已恢复。
EXTRA/DELETE 与完整性检查都是必要非充分条件：需记录实际 Python/SQLite版本、编译选项/VFS证据、文件系统和文件/目录权限，不能当作硬件断电验收。

## 5. 窄接口与 READY 后持久 CAS

`read_candidate()` 在独占进程锁下只读当前有效行，返回 provision/body、expectedLast/expectedGeneration、next 与候选 session context；不更新 DB、不发 permit。
registry 发送 PREPARE，Server 撤旧授权并证明 live body quiescence，返回精确匹配的 BINDING_READY；PREPARE 绝不是激活。
`commit_ready(candidate, verifiedReadyContext)` 只能由 registry 内部调用；Context 必须来自实际认证 SERVER origin、匹配 pending PREPARE 的 actor/world/body/session/epoch/boot/gameConnection，未消费且未过期。
不是来自网络 payload 的 ready=true 或泛用 bool；存储层不能认证世界事实，registry 负责验证并封装不可变 context，消费后不得复用。
取得 BEGIN IMMEDIATE 后重新读取完整行/marker、验证 hash/schema，并与 expected provision/body/last/generation 比较；任何偏差拒绝，不自动换 candidate。
CAS 条件更新精确匹配预期行，lastEpoch=next、generation=old+1、hash同步更新；影响行数必须=1，再 COMMIT 并重新读取核对提交行。
READY→持久 CAS 成功→registry 发 COMMIT→Server ACTIVE；在存储提交返回前绝不发 COMMIT/permit；提交过慢使 READY 过期也不得激活该候选。
READY 前失败不消费 epoch；存储已提交但 COMMIT 前崩溃则已消费、不复用；COMMIT/ACTIVE 失证仍 fence/对账后严格更高 epoch 恢复。
COMMIT抛异常/返回不确定、读回失败均立即禁止新绑定和 effect，标记存储结果 UNKNOWN，不认为已回滚、不重试同候选。
关闭故障连接并保持进程锁，先以 mode=ro 重新读取学习可见 committed counter；不执行写/激活；若 hot journal 导致无法只读，保持 disabled 等受控恢复。
即使读回旧值也不证明原提交从未发生；原候选标记废弃，后续受控恢复须取 max(已验证counter,本进程已尝试candidate)+1，且先静止/新 READY，保证未知候选不复用。
进程崩溃后未提交候选尚无外发 binding；如果存储事务结果都无法建立可靠恢复则不能依赖内存 high-water，持续 disabled，不能绕过持久性证明。
该数据库不存 action receipt、不改写旧 UNKNOWN、非消息 exactly-once、非人格/公开记忆；serverBootId 仍由服务端拥有。

## 6. 实施与最小证据路径（本轮未执行）

1. 父会话/oracle先定向批准本决策及安全规格第2节修订；存储owner再实现独立模块，不修改业务DB/world schema。
2. 用 dummy provision/body 和隔离临时目录验证显式创建、rw禁止自动创建、marker/DB/锁缺失、schema/version/hash/类型损坏、只读目录与有效ACL拒绝。
3. 两个隔离子进程验证 Win32 no-share 生命周期锁：第二进程拒绝、第一异常退出后可重新持锁；不得用真实账户/全局ACL或服务进程做实验。
4. 核验实际 sqlite_version/pragma_readback=delete/3；注入 PRAGMA忽略/错误、BEGIN忙、CAS generation变化、I63耗尽、COMMIT异常与读回异常。
5. 在READY前、CAS事务中、提交后COMMIT前、ACTIVE丢失点故障终止/reopen，检查新旧完整行、未知提交不重用、无额外COMMIT或permit；记录阶段/退出码/平台信息。
6. 分离只读无法hot-recover与受控rw恢复用例；不删journal，不把旧值读回证明成“从未提交”。
7. dummy进程kill/crash/reopen只证明软件恢复路径，不证明OS crash/硬件断电、VFS目录flush或设备缓存；真实电源故障不在本自动化实验范围。
8. 证据不足、unsupported FS/VFS/锁/权限或故障未解释时交付disabled能力状态；父会话最终验证与提交，不自动降低保证或开放runtime。

## 7. 主源与未解决项

[S1] [CPython3.10 posixmodule.c](https://github.com/python/cpython/blob/3.10/Modules/posixmodule.c)：研究交接已核查MoveFileExW flags，不冒充本机Python build源码相同。
[S2] [ReplaceFileW](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-replacefilew)：WRITE_THROUGH不支持。
[S3] [CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew)：share=0及句柄生命周期；不是目录flush等价保证。
[S4] [SQLite atomic commit](https://www.sqlite.org/atomiccommit.html)：rollback流程及OS/hardware假设。
[S5] [SQLite synchronous](https://www.sqlite.org/pragma.html#pragma_synchronous)：EXTRA/DELETE要求；实际Windows VFS目录行为尚未证明。
[S6] [Python3.10 sqlite3](https://docs.python.org/3.10/library/sqlite3.html)：URI mode、显式事务与实际SQLite版本接口。
[S7] [SQLite lockingv3](https://www.sqlite.org/lockingv3.html)：rollback锁和hot journal恢复，不替代进程owner锁。
精确未知：部署Python/SQLite/VFS版本、实际本地FS、有效ACL、Win32锁实现、系统flush/device假设与故障证据；无“EXTRA自动保证Windows断电持久性”结论。
