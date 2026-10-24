# Epoch OPEN lifetime custody — staged dormant interface and evidence boundary

**Staged dormant API design scoped PASS_WITH_RESIDUALS — 2026-10-25; parent
accepted after independent momus review.** The three prior architecture BLOCKs
(failed-open resource ownership, connection-close/transaction boundaries and
dormant worker/API scope) are closed at design level; history is retained, not
converted into implementation evidence. Parent authorized only the four-file
implementation envelope below; ora18 and parent now accept its dormant actual-store
integration as scoped source/SQLite-semantic PASS-with-residuals at local-only
upstream `aa522dc8259515bf0bb454bcb99a4ebcbb54bd4a` (no push/PR). This lane edits documentation only.
The later explicit worker API seam is separately accepted below at local-only
`294a12c76a47ea5c8bdd4fcdf298c25dc2a82be5`; it is not startup composition-root integration.
No native backend/test, capability=true, provisioning or new
deployment consent is authorized.
Baseline Sandbox: `c0a477d3003a622925fb2e7e6fe08758da5159e4`;
upstream references below are read-only paths in the isolated Agent-Luo worktree,
not an upstream delivery claim or permission to push.

## 1. Problem and bounded next scope

The selected direction is an explicit new OPEN-only entry using the actual
`EpochStore` internally, not a parallel counter, `hasattr` dispatch, automatic
fallback or a stronger meaning silently assigned to the legacy bool capability.
The existing [epoch decision](minecraft-epoch-store.md) remains the durability
baseline; custody cannot establish missing durability or READY authority.

The currently authorized upstream implementation envelope is exactly:

1. `server/src/adapter/minecraft/epoch_platform.py`
2. `server/src/adapter/minecraft/epoch_store.py`
3. `server/tests/unit/adapter/test_minecraft_epoch_platform.py`
4. `server/tests/unit/adapter/test_minecraft_epoch_store.py`

At the original dormant-store slice, no worker, routes, deployment modules/private helpers, protocol or coordinator
changes belong to that envelope. Parent's separate authorization permits those
four edits; the accepted bounded implementation evidence is recorded in §7.
At that slice the API was deliberately dormant: worker integration was excluded, not
an unresolved promise that the four-file slice will solve startup classification.

The pre-amendment legacy source order is `_directory` expanduser/resolve → capability → pathname
marker read → exact lock acquisition → pathname SQLite open. Each read and CAS
opens a fresh connection. Resolving first loses lexical aliases; checking a
capability then reading before locking supplies neither anchored namespace
custody nor lifetime identity continuity. Keeping only `epochs.lock` does not
pin ancestors/root/DB or make repeated pathname connections trustworthy.

Historical pre-amendment source anchors under `server/src/adapter/minecraft/`
(line numbers are baseline references, not current amended-file offsets):

| Source | Relevant existing behavior |
|---|---|
| `epoch_store.py:28-55,102-177,323-327,346-369` | Plain EpochStoreError; bool capability/close-only LockHandle; legacy provision/open and resolve-first marker ordering |
| `epoch_store.py:179-216,233-303,376-388` | Store terminal cleanup state/operation RLock; fresh rw connection per read/CAS, BEGIN/COMMIT/readback; DELETE/EXTRA/integrity checks |
| `epoch_platform.py:38-78,89-131` | Production capability always unverified; real volume probe; no-share lock and terminal UNKNOWN/no native close retry |
| `epoch_worker.py:435-475,718-719` | store=None startup; OPEN calls legacy open; fatal or cleanup-string classification; close attempted only if store returned |
| `deployment_acl.py:58-99,102-142` | Public trusted policy, conservative descriptor assessment and handle-bound inspection; not namespace custody |
| `deployment_files.py:220-340,343-384,483-552` | Separate load-time namespace verifier; private path/ancestor/native backend; resources released before returned deployment object |

These anchors are inspected source facts, not proof of runtime Windows behavior.

## 2. Explicit policy and proposed API

Proposed frozen `TrustedEpochPolicy` has these explicit fields (no defaults):

| Field | Draft validation contract |
|---|---|
| `raw_root_path` | Exact str, original absolute Windows spelling; lexical validation before resolve/Path normalization |
| `expected_volume_serial` | Exact int, not bool, 1..0xFFFFFFFF |
| `expected_file_index` | Exact int, not bool, 1..0xFFFFFFFFFFFFFFFF; root identity |
| `expected_owner_sid` | Canonical bytes SID under the existing public policy constructor's validation semantics |
| `content_writer_sids` | Tuple of 1..16 distinct canonical SID bytes |
| `security_mutator_sids` | Separate tuple of 1..16 distinct canonical SID bytes |
| `namespace_mutator_sids` | Separate tuple of 1..16 distinct canonical SID bytes |
| `provision_id`, `body_id` | Canonical nonzero lowercase hyphenated UUID str, existing `_identities` semantics |

The 32-bit serial/64-bit file-index bounds match current `TrustedDeploymentRoot`
(`deployment_files.py:85-100`), not an assertion that FILE_ID_INFO's 128-bit ID is
interchangeable. Native evidence must justify this identity model or return
unsupported, not truncate wider IDs. Canonical SID byte semantics are provided by
the public `TrustedDeploymentAclPolicy`/`HandleSecurityDescriptor` constructors:
revision 1, bounded subauthority count and exact byte length; there is no public
SID-validator function to invent or private validator to import. The expected
owner is included in `security_mutator_sids` because owner security-control
authority must be explicitly trusted. It is included in content/namespace tuples
only if actual policy grants and justifies those roles; no implicit insertion,
flattening or implication that ownership alone grants file-data/namespace write.
Cross-set membership is explicitly allowed and remains separate, not merged.
Structural policy validation is not deployed effective-ACL proof. Constructor semantics validate
shape, not actual effective Windows authority.

Held-object identity/descriptor baselines for ancestors/lock/marker/DB derive
only after the trusted root/volume, raw path and mutation policy pass acquisition
checks, and are bound for continuity, not retroactively called external pins.
Actual expected-object rules and future journal creation/owner/inheritance policy
must be supplied and reviewed before a native backend; missing values disable it.

Missing external policy disables the new entry. There is no default root/SID,
current-account inference or implicit Administrator/SYSTEM trust. Actual deployment
root/SIDs and acceptable mutation policy are currently missing: production stays
blocked. Missing real values do not block dormant mock tests or isolated temporary
SQLite semantic tests. Public policy construction supplies trusted configuration, not verified
native evidence or an authentication capability.

Concrete draft entry:

```python
EpochStore.open_with_custody(
    policy: TrustedEpochPolicy,
    *, custody: EpochCustodyPlatform, receipt: EpochOpenAttemptReceipt
) -> EpochStore
```

All arguments are required, without defaults, `hasattr` or fallback. Shared policy,
protocol, receipt and cleanup-error types live in `epoch_store.py`; platform code
may import them, never a store-to-platform import cycle. The new port explicitly
acquires an `EpochOpenLease`; its proposed operations are
`read_marker() -> bytes`, `validate() -> None`, and `close() -> None`. Acquisition
returns one composite resource only after required ancestor/root/exact-lock and
child identity/security checks. The **same claimed attempt context** is passed
into `custody.acquire(policy, receipt)` so partial resources are registered before
any later fallible boundary; acquisition cannot hide partial ownership behind an
exception or create a fresh internal receipt.
`read_marker` reads bounded bytes through its held, identity-checked marker handle,
not a new unchecked path read. Parsing retains exact three-key/version/UUID rules
and compares identities with policy. A lease is owned resource custody, not a bool.

The new entry must not call legacy `capability`/`acquire_lock` as fallback, accept
legacy fake capability as policy satisfaction, or mint a real native lease in
this mock/interface slice. Unsupported production native acquisition must reject
with **zero native calls**: do not first call legacy `capability` (which probes
the volume) or lock helpers. Native acquisition remains unsupported until
separately approved. Existing test platforms retain legacy semantic-test
coverage only. Production `PlatformCapability.verified` remains **False**; even
positive custody evidence would not establish SQLite/VFS/device durability.

This is OPEN only: no mkdir, ACL change, lock/marker creation, identity creation,
repair, migration or automatic provision; no new provision overload/mode framework.
Current legacy provision does not accept `custody=`, `policy=` or `receipt=`:
Python argument-binding TypeError rejects those keywords before its body. The
accepted four-file slice additionally rejects a new policy passed as `directory`
or a custody port passed as `platform` through explicit input validation **before
`_directory` or any resource call**, preserving valid legacy test platforms. No
duck-typed fallback to capability/lock is allowed. Test both argument-binding and
explicit type/protocol validation gates, not just eventual attribute failure.
Legacy provision remains separately scoped, never satisfies new custody policy.

At `aa522dc8`, `epoch_worker._open_store` used legacy `open` and config could not
select the new entry. The subsequently accepted explicit OPEN_WITH_CUSTODY worker
API seam below supersedes that limitation only; legacy OPEN remains compatible.
No production composition-root/startup migration is supplied.

## 3. Acquisition, handles and external mutation assumptions

Required order (after validating/claiming the required fresh receipt, before any acquisition):

1. Validate the **original raw absolute lexical input before resolve** or Path
   normalization. Reject relative/drive-relative, UNC/device/extended aliases,
   dot/dotdot, ADS, trailing-dot/space, reserved names and malformed components.
   No expanduser or silently accepted alternate raw spelling.
2. Acquire composite ancestor/root/exact `epochs.lock` custody before marker read.
   Establish expected volume/file identities, descriptors, local supported volume,
   drive mapping and per-component non-reparse continuity; unsupported is rejection.
3. `lease.read_marker`, strict parse, verify policy provision/body identities.
4. While custody remains held, open existing SQLite via controlled rw URI;
   configure/read back DELETE/EXTRA, validate integrity/schema/row/identity.
5. Successful return transfers the entire lease to the actual store for its whole
   lifetime, atomically with receipt TRANSFERRED and empty receipt resource refs.
   Until successful return the caller-owned receipt owns all acquired resources;
   constructor/validation failure cannot orphan resources or transfer ownership
   merely because an intermediate store object exists.

Proposed native access/share contract (requires native compatibility evidence):

| Object | Proposed lifetime treatment |
|---|---|
| Ancestors/root | Directory identity/security query handles, OPEN_EXISTING, no FILE_SHARE_DELETE; access must suffice for metadata/security and directory traversal checks |
| Exact lock | GENERIC_READ bitwise-OR GENERIC_WRITE, share=0, OPEN_EXISTING, non-inheritable |
| Marker | Held read/security-query handle with identity-bound bounded reads; no writer/delete sharing under the declared immutable-marker policy |
| DB | Compatible READ/WRITE sharing with SQLite, **no desired DELETE and no FILE_SHARE_DELETE**; exact access bits/security-query rights must be pinned and tested before native approval |
| DELETE rollback journal | Transient SQLite-owned object, not a lifetime pinned file; never prevent legitimate creation/deletion by pretending it is the immutable DB |

Exact masks/flags, handle-open sequencing and compatibility are proposed validation
gates, not guarantees of the current source. Namespace handles deny deletion but
do not freeze DACLs, permitted content writes, DOS drive mappings or privileged
mutation. Explicit trusted external mutators and their behavior are necessary;
the policy is not anti-ABA or protection from untrusted privileged mutation.
OPEN_REPARSE_POINT alone does not make all path components no-follow. Raw alias,
SUBST/UNC/reparse screening, component/drive identity continuity and same-path
reconnection binding require evidence; ambiguity fails closed.

Reuse only public deployment ACL structures/`assess_descriptor` and handle
inspection where their documented conservative semantics apply. No private
`deployment_files` namespace imports, no shared deployment handle share-mode
changes. Its protected-DACL/inherited-write restrictions may reject candidate
epoch/journal policies. The actual assessor combines dangerous rights into one
allowed-writer set and rejects inherited dangerous ALLOWs: it is **not** a full
three-set epoch authorization assessor. Never flatten the three policy sets to
make it pass. Needed ancestor/security-mutator/journal rules require a separately
reviewed assessment within the permitted modules, or an explicit unsupported
result; any broader public seam is a review blocker, not permission to
silently weaken assessment or broaden the four-file envelope.

Windows inheritance facts are not journal evidence: OI/CI propagation, CREATOR
OWNER substitution and token default owner affect newly created objects. The
future journal owner/DACL depends on actual creation context and inheritance;
an absent journal is **not** verified ACL. Require explicit future-journal policy
and actual creation/recovery/delete tests. Neither directory ACL inspection nor
an application field proves the deployed journal owner or effective permissions.

## 4. Every connection and CAS boundary

All new-path operations use the store's existing operation lock and a single
connection ownership slot. During initial open this is a provisional store slot
whose strong ownership remains in the receipt until successful transfer; a failed
constructor cannot leave a second owner. Register a connection in that slot immediately on
connect return, before configuration/validation. Before **each**
pathname SQLite connect, validate the lease and reread/parse the marker through
the lease, requiring exact original policy identity. Validate again after read
before publishing a candidate. The extra checks do not manufacture an atomic
check/connect primitive; their correctness depends on held custody and trusted
mutators, not defeating malicious transient changes.

CAS keeps existing candidate/READY matching and row/hash semantics. Validate and
reread marker before connect, configure, BEGIN IMMEDIATE, then validate lease and
marker again before conditional UPDATE. Recheck exact row/candidate inside the
transaction. Record COMMIT_ATTEMPTED immediately before invoking COMMIT. Before
that boundary a single best-effort rollback is allowed on failure, but cannot
resolve uncertain connection close. Once COMMIT was attempted, failures mean
transaction outcome UNKNOWN, not a claim that rollback restored the old epoch; no retry of the
candidate. After confirmed COMMIT, validate, close that connection once, and only
after confirmed close perform controlled readback with the same per-connection
checks. COMMIT uncertainty, post-validation or
readback failure means transaction outcome UNKNOWN/disabled; no assertion that rollback undid a
commit and no retry/reuse of that candidate. This draft authorizes no CAS activation
or READY producer; test calls are storage semantics only.

Attempt each owned connection's close **once**, including BaseException paths;
never let a finally block or readback close it a second time. Any close
BaseException disables the store, marks cleanup UNKNOWN and retains strong refs
to the connection plus dependent lease in the slot. No readback/new connection
or lease release beneath that unknown connection. `store.close` and reentrant/
repeated close return terminal UNKNOWN error without retrying resources. Only a
confirmed close clears the slot. Read/candidate/commit SUCCESS is published only
after confirmed close and all required custody checks. Every new-path
BaseException disables before propagation; the first non-Exception fatal identity
is preserved (a later fatal outranks an earlier ordinary error), with independent
cleanup evidence. Transaction outcome UNKNOWN and cleanup UNKNOWN are separate:
confirmed closure cannot establish transaction outcome, and known transaction
state cannot establish cleanup. No automatic lease release merely because an
operation throws. Set terminal/in-progress reentry guards before external cleanup/
ownership ports; reentry cannot retry close, open a replacement connection or
release another operation's custody.

After TRANSFERRED the store remains lifetime owner, including later operation
UNKNOWN: receipt does not reacquire resources. Caller must retain the store for
the containing lifetime when cleanup is UNKNOWN. This is deliberate quarantine,
not cleanup success or a global registry/destructor/retry mechanism.

## 5. Acquisition failure and typed cleanup evidence

The explicit caller-owned `EpochOpenAttemptReceipt` is required and single-use:
FRESH → IN_PROGRESS → exactly one of TRANSFERRED / RELEASED / UNKNOWN. Validate
receipt identity/type/state and claim it before acquisition; reused/invalid receipt
fails before acquiring anything. Invalid policy or unsupported native backend
after a fresh claim yields RELEASED with no resources. The receipt is not merely
status attached to an exception: it owns strong refs to every pre-return resource.
Acquisition registration/ownership callbacks are authority under the trusted
custody-port contract, not publicly writable fields or a public native-lease factory.

Catch **BaseException** across acquisition/validation, owning each resource
immediately after acquisition, before later checks. Attempt independent cleanup
in reverse ownership order, subject to connection-before-custody dependency above;
one failure cannot skip unrelated releases. Preserve the first non-Exception
fatal object's identity; a later fatal outranks an earlier ordinary error, while
retaining sanitized cleanup UNKNOWN evidence separately.
No native CloseHandle retry after uncertain close, and no fabricated RELEASED.
Store=None startup failure must remain observable even when no object returns.

Confirmed cleanup yields RELEASED and empty refs. Uncertain connection close
retains the strong connection and dependent lease; uncertain lease close retains
a terminal ownership record without a native-close retry. UNKNOWN exposes only a
bounded sanitized immutable snapshot: no public handles/connection/reset/recovery/
retry-close or replacement transfer. Caller MUST retain the receipt for its entire
containing lifetime on failed open with UNKNOWN. Deliberate quarantine/leak is not
cleanup success; no global/unbounded registry or destructor provides ownership.

Minimal proposed types in `epoch_store.py`: immutable `EpochCleanupEvidence`
(state plus bounded reason codes), required `EpochOpenAttemptReceipt`, and an ordinary
`EpochOpenCleanupError(EpochStoreError)` carrying that typed evidence. Ordinary
uncertain cleanup must retain the exact compatibility phrase **"cleanup outcome is unknown"**
while the existing worker is unchanged. Fatal errors are re-raised as
the same object; caller inspects the preowned receipt, not arbitrary exception
attributes. Receipt evidence must be finalized even for fatal rethrow and cannot
mask the first fatal or imply cleanup success. Successful return leaves only
TRANSFERRED metadata in the receipt, not duplicate store resource ownership.

**Historical dormant worker boundary at aa522dc8:** `epoch_worker._cleanup_unknown_error` then
tests EpochStoreError message text, and `_thread_main` conservatively classifies
all non-Exception BaseExceptions as UNKNOWN. It calls legacy open and cannot
consume the new receipt on store=None. The four-file slice preserves ordinary
text compatibility and fatal identity, with typed receipt tests; that phrase is
**not worker integration**. Parent selected the dormant API boundary: existing
worker/config remains unchanged and never calls the new entry. Any later
worker+tests integration requires separate approval; typed worker-facing startup
classification is explicitly not delivered here.

The paragraph above records that original slice, not a current claim that the
accepted explicit worker seam below is absent.

## 6. Research basis and durability limits

The following official-source research basis was supplied on **2026-10-25**;
this drafting lane performed no new network research or native execution:

- [CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew):
  desired access, share compatibility, OPEN_EXISTING, reparse and directory flags
- [GetSecurityInfo](https://learn.microsoft.com/en-us/windows/win32/api/aclapi/nf-aclapi-getsecurityinfo):
  handle-bound owner/DACL inspection, not namespace continuity or DACL freezing
- [TOKEN_OWNER](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-token_owner)
  and [GetTokenInformation](https://learn.microsoft.com/en-us/windows/win32/api/securitybaseapi/nf-securitybaseapi-gettokeninformation):
  default owner evidence is not inferred from configured SID
- [ACE inheritance](https://learn.microsoft.com/en-us/windows/win32/secauthz/ace-inheritance-rules)
  and [inheritance flags](https://learn.microsoft.com/en-us/windows/win32/secauthz/ace-strings):
  OI/CI and creator-owner semantics require actual journal policy validation
- [FILE_ID_INFO](https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-file_id_info),
  [GetFileInformationByHandleEx](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getfileinformationbyhandleex),
  [GetVolumeInformationByHandleW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getvolumeinformationbyhandlew):
  volume/file identity facts, not deployed trust or mutation prevention by themselves
- SQLite **3.46.1 source snapshot**,
  [os_win.c](https://sqlite.org/src/file?name=src/os_win.c&ci=version-3.46.1) and
  [pager.c](https://sqlite.org/src/file?name=src/pager.c&ci=version-3.46.1), plus
  [synchronous](https://sqlite.org/pragma.html#pragma_synchronous): winOpen rw uses
  READ/WRITE sharing without DELETE; winDelete's syncDir is UNUSED_PARAMETER,
  not a Windows directory flush. EXTRA readback is not directory-durability proof.

That SQLite snapshot is **not the deployed runtime**. Actual Python/SQLite build,
VFS/compile options, OS/storage/device behavior and share compatibility remain
unverified. Custody, PRAGMA readback, reopen and software crash tests cannot prove
power-loss durability. Existing PlatformCapability false and production disabled
remain correct even if later custody tests pass.

## 7. Authorized bounded evidence scope and residual review gates

This lane is docs only; the following is parent-accepted recorded evidence, not
a rerun. Dormant mock-safe and isolated temporary SQLite semantic validation is
accepted, not native custody. Upstream is committed clean at local-only
`aa522dc8259515bf0bb454bcb99a4ebcbb54bd4a`; no push/PR or future Sandbox commit is claimed.

Receipt-cleanup-window and operation-reentry P1s are closed: registration closes
before cleanup, RELEASED requires no retained refs, private single-use claim and
strong UNKNOWN ownership require caller quarantine. After TRANSFERRED the store
owns later UNKNOWN. An exact operation token blocks nested read/commit without a
new connection, slot replacement or outer close; internal readback uses the same
operation ownership. Transaction UNKNOWN remains separate from cleanup UNKNOWN;
close-once/non-retry/first-fatal identity are preserved. The backend contract is
trusted: check before acquire/register before the next fallible boundary, not
proof against a malicious backend or a GC experiment. SID role sets are not
flattened. Unsupported Windows acquisition makes zero native calls and production
verified remains false. Structural policy fixtures are not real deployment root/
SID/ACL/native journal/VFS durability evidence. No new custody provision path or
worker startup integration; worker continues legacy open.

Archive `Temp/opencode/epoch-reentry-20261025-015302`: 73 collected, 70 executed
PASS, 0 failures/errors/skips, 3 DESELECTED: `test_platform_is_fail_closed_without_acl_descriptor_verification`,
`test_windows_no_share_lock_rejects_second_handle_and_releases`,
`test_non_windows_lock_is_unavailable`. Command wall 6.8734622 s, exit 0; lint passed.
Verified final source 7/7 (including unchanged worker/deployment) and aggregate
268/268 identities. Before manifest `AA7CB934A2F594F5155D598C312AC378B01D59AD251AD28DE7F7477A3BD2A76A`;
final `0F1E34990CFFAC62794AFDABE3DB2252A90703748DEB76287E1250772584D5A7`;
aggregate `5CC525E8DC4E1925D086C05846E9FE0091D2A77300172BF931155D3A10E6654D`.
Accepted four-file SHA-256:

| File | SHA-256 |
|---|---|
| epoch_store.py | `9BFA97315FE3758DDDAE4A7B8C22BFD6468E458544B38A23251A9FFBD7C47A80` |
| test_minecraft_epoch_store.py | `74ED348FF94852A6011C001017DBD4A133352A86F56C009FDC78E1D82A02855C` |
| epoch_platform.py | `E2935437C9CF14FD78B1A8FCB47EA2E39BDCD47CD630DB1840FF810588F99CB5` |
| test_minecraft_epoch_platform.py | `85B7E5D7457D36A5529DE5AA2CF48272760BAA6FF896A2B6D2F378463BCCE334` |

Old 58-PASS/review BLOCK evidence remains genuine superseded history, not retroactive
PASS. Earlier codec and observational localhost acceptance at Sandbox `5170f1d` /
upstream `57e46850` remains valid historical scope, not execution at the new commit.
Existing strict interop/loopback gates still pin `57e46850` and are now STALE against
latest HEAD: future reruns would BLOCK until a separately reviewed profile refresh.
This documentation does not change gates or approve a new dependency closure.

Four-file mock/unit matrix: raw invalid spelling rejected
before resolve/native calls; marker never read before lease; acquisition/ownership
order; strict new entry/no legacy fallback; receipt claim/reuse, partial acquisition,
strong ownership/transfer/quarantine; missing policy/rejected provision;
capability remains false; each read/CAS connection has marker/lease checks;
BEGIN-before-update validation; COMMIT_ATTEMPTED rollback boundary; post-COMMIT
identity/readback failures UNKNOWN/no retry; volume/file/descriptor/drive mapping mismatch and reparse rejection;
BaseException at every acquisition/check/constructor point; reverse independent
cleanup, first fatal identity including later fatal over ordinary, store=None receipt,
single connection slot/one close on BaseException, no readback/new connect/unlock
after close UNKNOWN, reentry/repeated close and ambiguous native-close non-retry.
Tests exercise the actual store, not a replica counter. Legacy compatibility and
absence of worker/config selection are required; mocks never satisfy production capability.
Mock-safe selectors must explicitly exclude real volume-probe and native Windows
lock tests; broad file selection running those is not mock-only validation.
Real SQLite in isolated temporary directories may test semantics, not custody,
deployed VFS/native compatibility or durability. No native backend/ACL operation
is authorized by these semantic tests.

Later, separately authorized NTFS native evidence must use only isolated temporary
objects/ACLs, never real deployment ACL changes: exact access/share masks with real
SQLite DB/journal create/recovery/delete; ancestor/root/DB rename and lock conflicts;
owner/token/journal inheritance and denied writer/security/namespace mutation;
actual SQLite version/VFS/compile options/PRAGMA results, identities and close
outcomes. No power-loss, hostile-privileged-mutator or anti-ABA claim follows.

Concrete unresolved review items:

1. External actual root/identity/SIDs, security/namespace mutators and journal policy
   are absent; no safe production policy may be invented.
2. Exact native masks/alias/identity continuity and public ACL assessment compatibility
   require evidence; no native backend or private-helper reuse is approved.
3. Required receipt/connection-slot mechanics have scoped source/semantic acceptance,
   not malicious-backend/GC/native proof; worker selection/classification stays excluded.
4. UNKNOWN retention and release dependencies are accepted only within the bounded
   actual-store tests; future native/worker stages require separate authorization.
5. SQLite/VFS/OS/device durability is independently unverified; custody is not capability.

### Latest explicit worker-owned custody API acceptance

Ora18/parent accepted scoped source/SQLite-semantic PASS-with-residuals at clean
local-only upstream `294a12c76a47ea5c8bdd4fcdf298c25dc2a82be5` (no push/PR).
Explicit OPEN_WITH_CUSTODY config uses the actual store and receipt on the store
thread. Before transfer, UNKNOWN receipt owns strong refs; after transfer the
store remains strongly retained even after thread death. Terminal evidence is
published under an independent lock **before** notification: accepted notification
is not execution, and loop failure is polled. Snapshot preterminal retention is
NOT_YET_REPORTED; that P1 is closed without off-thread resource evidence.
Direct public lifecycle calls rethrow the same first fatal after drain, with
cancellation precedence; internal tasks stay sanitized and later cleanup fatal
cannot mask the first. Fatal cleanup_evidence yields UNKNOWN and strong STORE
retention even after traceback clear/GC. Caller must retain UNKNOWN worker for
its containing lifetime; no close retry/reset/restart, global other-worker
exclusion or resource-quiescence guarantee. Legacy config is compatible, no
fallback/autoprovision. This is worker API integration only, not production
startup composition-root, admission or capability activation. Native acquisition
remains unsupported with zero native calls, production capability UNAVAILABLE.

Archive `Temp/opencode/epoch-worker-snapshot-20261025-021659`: 38 collected and
38 executed PASS, 0 failures/errors/skips/deselection; command 7.0819641 s/exit 0.
Final-source 6/6 and artifacts 134/134 verified. Worker SHA-256
`3E8880EB9E1833A99A8A177F036E3523C91A73EB7058CF7EE097FE1FDD017357`;
test `E757278FAAE80824AA5A49AA0B0C88457F934DA194FE82B15B3538272369931E`.
Before manifest `D476D0DC33A81EFBD02D0C40F0A14DD81D8E754AC60DD94DB9904A5B27DCBCD3`;
final `154AC6FE4ED3E27BD6905CA71DA13803C4BC14A74EA8B536452E22766D693972`;
aggregate `25862DD991722206B3929125F624A2AE20BB68A88C67752912065189827B510F`.
Old 33-PASS/snapshot BLOCK remains genuine superseded history, not retroactive
PASS. Store `aa522dc8`/70 safe passes/3 deselected and actual network
`5170f1d`/`57e46850` remain distinct history, not execution at current HEAD.
Strict interop/loopback pins still expect `57e46850` and remain stale: future
reruns BLOCK until separately reviewed profile refresh; no automatic refresh/run.
Actual deployment SID/root/journal ACL/native/VFS durability remain unverified;
SQLite 3.46.1 research is not runtime attestation, EXTRA not Windows directory
durability proof. No future Sandbox hash or new deployment consent is asserted.

### Restricted native OPEN profile — staged design only

Parent adopted independent momus restricted-design **PASS_WITH_RESIDUALS** and
initially authorized the following three-file implementation plus **MOCK validation**.
That initial scope was subsequently extended to the public descriptor-allocation
ownership seam and its paired test: the final five-file source/mock slice is now
independently accepted below. **NATIVE NOT RUN**.
This does not change accepted dormant store/worker results or authorize deployment.
The original three-file edit scope was:

- MODIFY `server/src/adapter/minecraft/epoch_platform.py`
- MODIFY `server/tests/unit/adapter/test_minecraft_epoch_platform.py`
- NEW `server/tests/integration/adapter/test_minecraft_epoch_custody_native.py`

Store, worker and deployment_files remain unchanged in the final five-file slice.
The later authorized deployment_acl/public receipt change is recorded below, not
permission to import private deployment backends. The accepted inert
`NativeEpochOpenProfile(expected_token_owner_sid, journal_policy)` requires
canonical SID bytes and the sole literal `EXPLICIT_INHERITABLE_ALLOW_V1`.
`WindowsEpochCustodyPlatform(profile).acquire(policy, receipt)` is the explicit
new native port. Legacy `WindowsEpochPlatform.acquire` remains zero-native-call
unsupported; do not route through legacy capability/lock/provision or set any
production verified flag true. Native custody success is not durability capability.

#### Restricted descriptor language and complete access classification

Every held ancestor/root/lock/marker/DB must have a non-null **protected** DACL.
Accept only standard explicit ALLOW ACEs. Reject DENY, object/callback/conditional,
inherited ACEs, unknown flags/masks, CREATOR OWNER/GROUP and other substitution
principals. Directory flags are exactly 0 or OI|CI (`0x03`); file flags exactly 0.
No INHERIT_ONLY, NO_PROPAGATE or inherited flags in held-object descriptors.
This deliberately rejects otherwise valid Windows ACLs instead of trying to
approximate their effective permissions. No automatic Administrator/SYSTEM trust.

Expand recognized GENERIC_READ `0x80000000`, GENERIC_WRITE `0x40000000`,
GENERIC_EXECUTE `0x20000000`, GENERIC_ALL `0x10000000` by **object type** before
classification. File and directory aliases share numbers, not policy categories:

| Right / mask | Regular-file category | Directory category |
|---|---|---|
| `0x00000001` FILE_READ_DATA / FILE_LIST_DIRECTORY | Nonmutation | Nonmutation |
| `0x00000002` FILE_WRITE_DATA / FILE_ADD_FILE | Content | Namespace |
| `0x00000004` FILE_APPEND_DATA / FILE_ADD_SUBDIRECTORY | Content | Namespace |
| `0x00000008` FILE_READ_EA | Nonmutation | Nonmutation |
| `0x00000010` FILE_WRITE_EA | Content | Content (directory metadata) |
| `0x00000020` FILE_EXECUTE / FILE_TRAVERSE | Nonmutation | Nonmutation |
| `0x00000040` FILE_DELETE_CHILD | Unsupported for regular file | Namespace |
| `0x00000080` FILE_READ_ATTRIBUTES | Nonmutation | Nonmutation |
| `0x00000100` FILE_WRITE_ATTRIBUTES | Content | Content (directory metadata) |
| `0x00010000` DELETE | Namespace | Namespace |
| `0x00020000` READ_CONTROL | Nonmutation | Nonmutation |
| `0x00040000` WRITE_DAC | Security | Security |
| `0x00080000` WRITE_OWNER | Security | Security |
| `0x00100000` SYNCHRONIZE | Nonmutation | Nonmutation |

Mappings: GENERIC_READ → `0x00120089`, GENERIC_WRITE → `0x00120116`,
GENERIC_EXECUTE → `0x001200A0`; GENERIC_ALL → `0x001F01FF` for directories,
and the Windows FILE_ALL_ACCESS mask `0x001F01FF` for files **whose expanded
FILE_DELETE_CHILD bit is rejected by this restricted regular-file profile**.
Both regular-file GENERIC_ALL expansion and direct `0x001F01FF` reject the
unsupported `0x40` bit. This is a restricted availability policy, not a claim
that FILE_DELETE_CHILD grants meaningful regular-file authority; **never strip**
that bit to accept the mask. Common full-control files and inherited journal
GENERIC_ALL may therefore be unsupported; do not relax policy to force a positive.
Thus common file GENERIC_ALL is unsupported here, not silently stripped or
misclassified. All other bits (including ACCESS_SYSTEM_SECURITY/MAXIMUM_ALLOWED)
are unsupported. Any ALLOW touching multiple categories must satisfy **every**
corresponding content/security/namespace SID tuple. Pure nonmutation ALLOWs do not
mint write authority. Owner implies security-control authority and must be in
trusted security mutators: ancestor owner may differ from business owner; root
and all three fixed files must equal `expected_owner_sid`. Never flatten three
sets into the deployment assessor's combined dangerous-writer set. Public
descriptor types/inspection may be reused, not that assessor as full policy or
private deployment backend helpers. Token privileges/changes and trusted mutators
are explicit external assumptions, not enabled privileges or anti-admin protection.

#### Journal policy is different from held-object policy

The predicted future regular journal inherits supported root OI|CI ALLOWs using
Windows file inheritance semantics; flags are converted to the applicable
inherited file ACE form, not copied as explicit directory flags. No substitution
principals are allowed. Its descriptor need not be protected/explicit-only.
Classify resulting file masks against all applicable policy sets; owner must
match explicit `expected_token_owner_sid` and be a trusted security mutator.
Query effective thread TokenOwner, falling back to process token **only** for
ERROR_NO_TOKEN (`1008`), never TokenUser or a guessed configured service SID.
Revalidate effective token-owner assumptions at every lease.validate before any
pathname SQLite connection. Native scope assumes ordinary SQLite creation attrs;
arbitrary creation descriptors/token changes are unsupported. Actual journal
descriptor comparison needs later native evidence; absent journal is not proof.
Default owner and inheritance prediction do not establish deployed journal ACL.

#### Handle masks, namespace and bounded acquisition

All opens are OPEN_EXISTING, non-inheritable, without enabled privileges:

| Object | Desired access | Sharing | Flags |
|---|---|---|---|
| Ancestors/root | READ_CONTROL `0x00020000` + FILE_READ_ATTRIBUTES `0x80` | READ `1` + WRITE `2`, no DELETE | BACKUP_SEMANTICS `0x02000000` + OPEN_REPARSE_POINT `0x00200000` |
| Lock | GENERIC_READ + GENERIC_WRITE + READ_CONTROL + FILE_READ_ATTRIBUTES | 0 | OPEN_REPARSE_POINT |
| Marker | GENERIC_READ + READ_CONTROL + FILE_READ_ATTRIBUTES | READ only | OPEN_REPARSE_POINT |
| DB | READ_CONTROL + FILE_READ_ATTRIBUTES | READ + WRITE, no DELETE | OPEN_REPARSE_POINT |

DB desired access includes no DELETE; metadata pin/share compatibility with actual
SQLite is a native validation gate, not source assurance. Journal is transient
and **never lifetime pinned**. No creation or ACL repair in acquisition.

Raw lexical validation precedes resolve/native APIs, including embedded NUL rejection.
Reject UNC/device/extended,
SUBST/noncanonical DOS mappings, reparse components, unsupported lengths and
ambiguous aliases. Freeze raw path bound at 259 UTF-16 code units excluding NUL
by UTF-16 units (supplementary characters count as two, not Python len),
and at most 32 components **excluding drive root**; at most 33 directory slots
(drive root through business root) plus 3 fixed-file slots. Native buffer queries
are independently bounded; truncation/nonterminated/required-length overflow
rejects rather than retrying with unbounded allocation. Only fixed local NTFS,
same-volume held identities, externally expected root serial/file index. The final
identity API is explicitly `GetFileInformationByHandle` with
`BY_HANDLE_FILE_INFORMATION`: DWORD `dwVolumeSerialNumber` must exactly match
policy; 64-bit file index is `(nFileIndexHigh << 32) | nFileIndexLow`.
Zero/unsupported identities reject. Never truncate or hash FILE_ID_INFO's 128-bit
ID into this representation. Use separate bounded object-type/delete-pending/
reparse queries; this representation is restricted, not universal Windows-FS support.
Reject
wrong object types, delete-pending objects and non-single-link fixed files.
Recheck mapping, every held identity and descriptors after acquisition and each
validate. OPEN_REPARSE_POINT covers the final component, not atomic all-component
nofollow. No-delete-sharing does not freeze DACL/content/DOS mapping or defeat ABA;
trusted mutators must exclude prohibited changes throughout custody lifetime.

Native positive-test ancestry preassessment is **read-only**. Incompatible SYSTEM
root or user-profile ancestor policy is a precise BLOCK/SKIP category, not PASS.
Never alter preexisting ancestor ACLs. Only separately authorized, test-created
temporary directories may receive test ACLs; missing real SID/root policy blocks
deployment, not interface/mock tests.

#### Composite lifetime, marker read and failure discipline

Check receipt registration window/policy/profile before any native call. Register
an empty bounded composite lease **before first open**; each returned handle goes
immediately into its slot before inspection or the next fallible boundary.
Receipt owner performs failure cleanup: acquisition must not also close the same
resource. Temporary token handles/security descriptors have deterministic,
independent cleanup, cannot mask first fatal or bypass file-handle cleanup.
Set CLOSE_IN_PROGRESS before ports; reverse-close all owned handles once, mark
each attempt before CloseHandle. Confirmed success empties its slot; failure
retains terminal UNKNOWN record, numeric handle diagnostic only, never retried or
reused. Reentry yields UNKNOWN without a second pass. First non-Exception fatal
wins; later fatal outranks an ordinary error, otherwise sanitized EpochStoreError
includes exact phrase `cleanup outcome is unknown`. No handle getter, destructor
or global quarantine. Existing receipt/store strong-owner rules remain mandatory.

Held marker reads serialize file position under lease operation synchronization.
Read at most 4097 bytes to detect the 4096-byte cap, with pre/post identity, size
and ACL checks; truncation/change/oversize rejects and store parses strict bytes.
Validate/read failure permanently invalidates operational use while cleanup remains
owned; CLOSED/UNKNOWN cannot be reused. Store already closes connection before
lease; do not introduce a duplicate lifecycle or cleanup under an unknown connection.

#### Validation authorization boundary

Parent authorized implementation plus MOCK validation only; final five-file
source/mock acceptance is recorded below. The native integration file defaults its environment opt-in
**OFF**, checking that gate before any setup, then Windows and prerequisites;
never auto-run by platform detection. Real ACL/native-volume/lock/subprocess/
SQLite-journal tests are **not authorized and not run**. Later isolated native
testing needs separate parent execution authorization. Unsupported ancestry, UNKNOWN or forced cleanup
must not aggregate as PASS. No native result/counts are asserted here.
SQLite 3.46.1 winDelete syncDir-unused research is not deployed runtime pinning;
EXTRA is no Windows directory-durability guarantee. Old `57e46850` network/interop
pins stay stale and untouched; no new execution/activation authorization.

Parent/momus adopted the strict regular-file GENERIC_ALL rejection and explicit
64-bit identity representation above; final source/mock acceptance is recorded below,
not native execution acceptance.
Future actual deployment root/SIDs, native DB pin compatibility, journal inheritance
and VFS durability evidence remain separate gates. If practical ancestry fails this descriptor language, retain
unsupported rather than invent a safe native lease or relax policy automatically.

### Latest accepted five-file restricted custody source/mock slice

Parent accepted oracle final scoped source/mock PASS-with-residuals at clean
local-only upstream `6fe9e9dd8a16b9f6f6ee815aa7ded22d7ae04491`, no push/PR.
Final scope is epoch_platform, paired unit test, new native fixture, deployment_acl
and paired ACL test. Store/worker/deployment_files were not modified in this lane.
It implements restricted WindowsEpochCustodyPlatform plus optional public
DescriptorAllocationReceipt ownership, not default production activation. Descriptor,
token and composite terminal UNKNOWN is monotonic; temporary output storage is
preowned; opaque query failure is not freed, queried or retried. Legacy default
ACL API stays compatible, legacy WindowsEpochPlatform.acquire remains zero-call
unsupported and capability remains false.

The native fixture uses fixed bounded ResourceRecord session ownership to retain
unknown handles/allocations/connections after traceback clear/GC. Cleanup UNKNOWN
is FAIL, never ancestry SKIP; first fatal survives independent closes. Journal
checks exact owner/ACE multiset, and only sharing violation is expected lock
failure. These are source/mock results, not native ABI/ACL/share/journal/durability
proof. Fixture defaults OFF and was NOT RUN. Unsupported existing ancestry is
SKIP, not PASS; never alter existing ancestor ACLs. Real deployment root/SID policy
remains missing. Actual native execution needs separate parent authorization and
all native prerequisites. SQLite EXTRA is no Windows directory-durability proof.

Archive `Temp/opencode/epoch-fixture-priority-20261025-032009`: 104 collected,
98 PASS, 1 default-native SKIP, 5 DESELECTED, 0 failures/errors; wall 7.5278769 s,
exit 0. Verified 8/8 source and 13/13 artifacts. Final manifest
`BE772444A89B9E80487B4B09D8AD6919E3BF9B8B2778F86E3A155C4587D5C364`;
aggregate `8CDAAB619D6C83A55B6A52951EF2182547B76F89B146C56DDA758458DC77F6C7`.

| Accepted file | SHA-256 |
|---|---|
| epoch_platform.py | `177DF90328F7762F5E0CD000E004D6A2404CDBACF0B4EDC37119F438A6EB8823` |
| test_minecraft_epoch_platform.py | `FAE56AE5AAE7E7F87D2D4E53FE213E74664AF5729788D2BE9FFF1B5F0F5EDA6B` |
| test_minecraft_epoch_custody_native.py | `D1397A52F6AA3FF1E8EA2CB3FA2579E3E06700535082FE319F8DAD7546759A3E` |
| deployment_acl.py | `4C8B3539A7638A918DAFA85A6EC714B6E59E7F5E8185ADC3264997D898D0512D` |
| test_minecraft_deployment_acl.py | `DA3C86150139C2FD614F8C4925A0F062B210F26FEE3512FE5067FE02AF4DAEC3` |

Historical partial BLOCKs/92-test subset remain genuine superseded evidence, not
retroactive PASS. Worker `294a12c7` and store `aa522dc8` anchors remain distinct.
Old strict network/interop pins `57e46850` are still stale and untouched; historical
actual localhost run `5170f1d`/`57e46850` is not execution at latest HEAD. No new
native/network claim, automatic profile refresh, future Sandbox hash or capability
upgrade. Four UNAVAILABLE/complete=false/epoch UNAVAILABLE/Java custody/runtime
Mixin/G3/binding READY gates remain unchanged.

No protocol/coordinator implementation, native test execution, epoch capability,
BODY/READY/CAS activation or production startup is approved. Four observation scopes
remain UNAVAILABLE, production complete=false, runtime Mixin/G3 and binding READY/
delivery gates unchanged. Epoch production remains UNAVAILABLE. Scoped dormant
implementation acceptance authorizes no future production startup/native/deployment stage;
each requires separate review/authorization. No future commit hash is guessed.
